package ph.marbingo.field.tracking

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ph.marbingo.core.model.UserDto
import ph.marbingo.core.net.ApiClient
import ph.marbingo.core.net.InMemoryTokenStore
import ph.marbingo.field.data.db.AppDatabase
import ph.marbingo.field.data.db.LocationPointEntity
import java.time.ZonedDateTime

private class FakeService : ServiceController {
    override val running = MutableStateFlow(false)
    var starts = 0; var stops = 0
    var startError: String? = null
    override fun start(): String? { starts++; if (startError == null) running.value = true; return startError }
    override fun stop() { stops++; running.value = false }
}
private class FakeAlarms : AlarmScheduler {
    var at: Long? = -1
    override fun scheduleAt(millis: Long?) { at = millis }
}
private class FakePerms(var state: PermissionState) : PermissionChecker { override fun snapshot() = state }

@RunWith(RobolectricTestRunner::class)
class TrackingRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var tokens: InMemoryTokenStore
    private val service = FakeService()
    private val alarms = FakeAlarms()
    private val perms = FakePerms(PermissionState(fine = true, background = true, notifications = true, batteryExempt = true))
    private var now = manila(2026, 10, 26, 10) // Monday 10:00
    @Volatile private var consentResponse: () -> MockResponse = { MockResponse().setBody("""{"trackingConsentAt":"2026-10-26T02:00:00.000Z"}""") }
    private lateinit var repo: TrackingRepositoryImpl

    private fun manila(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, TrackingSchedule.ZONE).toInstant().toEpochMilli()

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        tokens = InMemoryTokenStore().apply { accessToken = "a"; refreshToken = "r"; user = UserDto(4, "R", "D", "r@x", "AGENT") }
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/api/tracking/consent" -> consentResponse()
                "/api/settings/tracking-schedule" -> MockResponse().setBody("""{"days":[1,2,3],"start":"08:00","end":"17:00"}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        repo = TrackingRepositoryImpl(
            db, ApiClient.create(server.url("/").toString(), tokens), tokens, perms, service, alarms,
            now = { now }, ticker = flowOf(Unit),
        )
    }

    @After fun tearDown() { server.shutdown(); db.close() }

    @Test fun startsServiceInHoursOnceConsentAndPermissionsAreIn() = runBlocking {
        db.trackingMeta().saveConsent(1L, true)
        repo.ensureScheduled()
        assertEquals(1, service.starts)
        // on duty: alarm is the 15 minute watchdog, not the 18:00 end
        assertEquals(now + TrackingRepositoryImpl.WATCHDOG_MS, alarms.at)
    }

    @Test fun doesNotStartWithoutConsentOrBackgroundPermission() = runBlocking {
        repo.ensureScheduled()
        assertEquals(0, service.starts)
        db.trackingMeta().saveConsent(1L, true)
        perms.state = perms.state.copy(background = false)
        repo.ensureScheduled()
        assertEquals(0, service.starts)
        assertTrue(repo.state.first().needsAttention)
    }

    @Test fun offDutyStopsServiceAndAlarmsForNextStart() = runBlocking {
        db.trackingMeta().saveConsent(1L, true)
        now = manila(2026, 10, 31, 19) // Saturday evening
        repo.ensureScheduled()
        assertEquals(0, service.starts); assertEquals(1, service.stops)
        assertEquals(manila(2026, 11, 2, 7), alarms.at) // Monday 07:00 across Sunday
        val st = repo.state.first()
        assertFalse(st.inHoursNow); assertFalse(st.needsAttention)
    }

    @Test fun lastHalfHourAlarmsAtTheEndOfHours() = runBlocking {
        db.trackingMeta().saveConsent(1L, true)
        now = manila(2026, 10, 26, 17, 50)
        repo.ensureScheduled()
        assertEquals(manila(2026, 10, 26, 18), alarms.at)
    }

    @Test fun signedOutCancelsEverything() = runBlocking {
        db.trackingMeta().saveConsent(1L, true)
        tokens.clear()
        repo.ensureScheduled()
        assertNull(alarms.at); assertEquals(0, service.starts)
    }

    @Test fun startFailureIsRecorded() = runBlocking {
        db.trackingMeta().saveConsent(1L, true)
        service.startError = "nope"
        repo.ensureScheduled()
        assertEquals("nope", db.trackingMeta().get()!!.lastError)
    }

    @Test fun consentIsPostedAndStored() = runBlocking {
        repo.giveConsent()
        val meta = db.trackingMeta().get()!!
        assertTrue(meta.consentSynced)
        assertEquals(java.time.Instant.parse("2026-10-26T02:00:00Z").toEpochMilli(), meta.consentAt)
        assertTrue(repo.state.first().consentGiven)
    }

    @Test fun consentGivenOfflineIsQueuedAndRemembered() = runBlocking {
        consentResponse = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) }
        repo.giveConsent()
        val meta = db.trackingMeta().get()!!
        assertEquals(now, meta.consentAt); assertFalse(meta.consentSynced)
        assertTrue(repo.state.first().consentGiven) // works locally straight away
    }

    @Test fun refreshScheduleStoresServerScheduleAndReplans() = runBlocking {
        repo.refreshSchedule()
        val s = repo.state.first().schedule
        assertEquals("Mon–Wed, 8:00–17:00", s.fullLabel())
    }

    @Test fun stateCountsPendingPoints() = runBlocking {
        db.locationPoints().insert(LocationPointEntity(recordedAt = 1, lat = 1.0, lng = 1.0, accuracy = null, speed = null, bearing = null, battery = null))
        db.trackingMeta().saveLastFix(500L)
        val st = repo.state.first()
        assertEquals(1, st.pendingPoints); assertEquals(500L, st.lastFixAt)
    }
}
