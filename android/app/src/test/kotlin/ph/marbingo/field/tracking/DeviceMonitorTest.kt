package ph.marbingo.field.tracking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ph.marbingo.field.data.db.AppDatabase

@RunWith(RobolectricTestRunner::class)
class DeviceMonitorTest {
    private lateinit var db: AppDatabase
    private val store = object : MonitorStore {
        var s = MonitorState()
        override fun read() = s
        override fun write(s: MonitorState) { this.s = s }
        override fun clear() { s = MonitorState() }
    }
    private val t0 = 1_000_000_000L
    private var snap = DeviceSnapshot(
        wallMs = t0, elapsedMs = 60_000, bootCount = 7, battery = 80,
        mobileDataOn = true, airplane = false, online = true, gpsOn = true, locationAllowed = true, autoTime = true,
    )
    private val probe = object : DeviceProbe { override fun snapshot() = snap }
    private lateinit var monitor: DeviceMonitor

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        monitor = DeviceMonitor(db, probe, store)
    }
    @After fun tearDown() = db.close()

    /** Moves both clocks forward, as real time passing does. */
    private fun advance(ms: Long) { snap = snap.copy(wallMs = snap.wallMs + ms, elapsedMs = snap.elapsedMs + ms) }
    private fun events() = runBlocking { db.deviceEvents().all().map { it.type to it.at } }

    @Test fun firstStartWithEverythingOnRecordsNothing() = runBlocking {
        monitor.onServiceStart()
        assertEquals(emptyList<Pair<String, Long>>(), events())
        assertTrue(store.s.running)
        assertEquals(t0, store.s.beatAt)
    }

    @Test fun stateChangesBecomeOnOffEvents() = runBlocking {
        monitor.onServiceStart()
        advance(30_000); snap = snap.copy(mobileDataOn = false, online = false)
        monitor.checkStates()
        val off = snap.wallMs
        advance(30_000); snap = snap.copy(gpsOn = false)
        monitor.onHeartbeat()
        val gpsOff = snap.wallMs
        advance(30_000); snap = snap.copy(mobileDataOn = true, online = true, gpsOn = true)
        monitor.onHeartbeat()
        // Data off is not also "no signal".
        assertEquals(listOf("data_off" to off, "gps_off" to gpsOff, "data_on" to snap.wallMs, "gps_on" to snap.wallMs), events())
    }

    @Test fun noSimAndOfflineIsNoSignalNotDataOff() = runBlocking {
        snap = snap.copy(mobileDataOn = null, online = false)
        monitor.onServiceStart()
        assertEquals(listOf("no_signal" to t0), events())
        snap = snap.copy(airplane = true)
        monitor.checkStates()
        assertEquals(listOf("no_signal" to t0, "airplane_on" to t0, "signal_back" to t0), events())
    }

    @Test fun uncleanRebootRecordsAnEstimatedPowerOffAtTheLastHeartbeat() = runBlocking {
        monitor.onServiceStart()
        advance(30_000); snap = snap.copy(battery = 64)
        monitor.onHeartbeat()
        val lastBeat = snap.wallMs
        // Off for two hours, then booted 90 s ago.
        snap = snap.copy(wallMs = lastBeat + 2 * 3600_000L + 90_000, elapsedMs = 90_000, bootCount = 8, battery = 60)
        monitor.onServiceStart()
        val rows = db.deviceEvents().all()
        assertEquals(listOf("power_off" to lastBeat, "power_on" to lastBeat + 2 * 3600_000L), rows.map { it.type to it.at })
        assertTrue(rows[0].estimated); assertEquals(64, rows[0].battery)
        assertFalse(rows[1].estimated)
    }

    @Test fun cleanShutdownIsExactAndNotDoubleCounted() = runBlocking {
        monitor.onServiceStart()
        advance(10_000)
        monitor.onShutdown()
        val offAt = snap.wallMs
        monitor.onServiceStop() // onDestroy during the shutdown
        snap = snap.copy(wallMs = offAt + 600_000, elapsedMs = 40_000, bootCount = 8)
        monitor.onServiceStart()
        val rows = db.deviceEvents().all()
        assertEquals(listOf("power_off" to offAt, "power_on" to offAt + 560_000), rows.map { it.type to it.at })
        assertFalse(rows[0].estimated)
    }

    @Test fun serviceKilledOnTheSameBootIsAppStopped() = runBlocking {
        monitor.onServiceStart()
        val lastBeat = snap.wallMs
        advance(20 * 60_000L)
        monitor.onServiceStart()
        assertEquals(listOf("app_stopped" to lastBeat, "app_started" to snap.wallMs), events())
    }

    @Test fun normalStopOrQuickRestartIsNotAppStopped() = runBlocking {
        monitor.onServiceStart()
        advance(60_000)
        monitor.onServiceStart() // START_STICKY restart within the grace period
        monitor.onServiceStop() // end of work hours
        advance(13 * 3600_000L)
        monitor.onServiceStart() // next morning, same boot
        assertEquals(emptyList<Pair<String, Long>>(), events())
    }

    @Test fun settingTheClockIsAClockChange() = runBlocking {
        monitor.onServiceStart()
        snap = snap.copy(wallMs = snap.wallMs - 3600_000L + 30_000, elapsedMs = snap.elapsedMs + 30_000)
        monitor.onHeartbeat()
        val row = db.deviceEvents().all().single()
        assertEquals("clock_changed", row.type)
        assertEquals(-3600_000L, row.shiftMs)
        snap = snap.copy(autoTime = false)
        monitor.checkStates()
        assertEquals("auto_time_off", db.deviceEvents().all().last().type)
    }

    @Test fun fakeGpsFixesOpenAndCloseAMockGap() = runBlocking {
        monitor.onServiceStart()
        monitor.onFix(mock = true)
        monitor.onFix(mock = true)
        advance(60_000)
        monitor.onHeartbeat() // the OS snapshot can't see mock: it must carry over
        monitor.onFix(mock = false)
        assertEquals(listOf("mock_on" to t0, "mock_off" to t0 + 60_000), events())
    }

    @Test fun resetForgetsTheHeartbeat() = runBlocking {
        monitor.onServiceStart()
        monitor.reset()
        snap = snap.copy(wallMs = t0 + 3600_000L, elapsedMs = 10_000, bootCount = 9)
        monitor.onServiceStart()
        assertEquals(emptyList<Pair<String, Long>>(), events())
    }
}
