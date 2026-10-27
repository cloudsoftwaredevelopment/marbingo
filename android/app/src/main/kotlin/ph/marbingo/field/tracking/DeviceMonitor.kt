package ph.marbingo.field.tracking

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ph.marbingo.field.data.db.AppDatabase
import ph.marbingo.field.data.db.DeviceEventEntity
import kotlin.math.abs

/** What the OS reports right now. A null means unknown on this device (e.g. no SIM: mobile data doesn't apply). */
data class DeviceSnapshot(
    val wallMs: Long,
    /** Milliseconds since boot, including deep sleep. */
    val elapsedMs: Long,
    val bootCount: Int?,
    val battery: Int?,
    val mobileDataOn: Boolean?,
    val airplane: Boolean,
    val online: Boolean,
    val gpsOn: Boolean,
    /** Precise location "all the time". */
    val locationAllowed: Boolean,
    val autoTime: Boolean?,
)

interface DeviceProbe { fun snapshot(): DeviceSnapshot }

/** Survives process death: the tracking service's last heartbeat and the kinds that were last seen "off". */
data class MonitorState(
    val beatAt: Long? = null,
    val beatElapsed: Long? = null,
    val bootCount: Int? = null,
    val battery: Int? = null,
    /** The service was running at the last heartbeat (false after a normal stop). */
    val running: Boolean = false,
    /** Set by a clean shutdown, so the next boot doesn't record an estimated power-off too. */
    val shutdownAt: Long? = null,
    val bad: Set<String> = emptySet(),
)

interface MonitorStore {
    fun read(): MonitorState
    fun write(s: MonitorState)
    fun clear()
}

/**
 * Records the device log (docs/tracking-api.md "Device log") into the device_events outbox: state changes
 * (mobile data, airplane mode, signal, GPS, permission, automatic time, fake GPS) plus what happened while the
 * tracking service wasn't running (power off/on from the boot count, the app being killed from a stale heartbeat).
 * Only used during work hours: the service and [TrackingRepository.ensureScheduled] call it then.
 */
class DeviceMonitor(
    private val db: AppDatabase,
    private val probe: DeviceProbe,
    private val store: MonitorStore,
) {
    private val lock = Mutex()

    /** The tracking service (re)started: work out what happened while it wasn't running, then record the states. */
    suspend fun onServiceStart() = lock.withLock {
        val prev = store.read()
        val snap = probe.snapshot()
        val out = mutableListOf<DeviceEventEntity>()
        if (prev.beatAt != null && prev.beatElapsed != null) {
            val rebooted = if (snap.bootCount != null && prev.bootCount != null) snap.bootCount != prev.bootCount
            else snap.elapsedMs < prev.beatElapsed
            if (rebooted) {
                val bootAt = snap.wallMs - snap.elapsedMs
                val offAt = prev.shutdownAt ?: prev.beatAt
                if (bootAt > offAt) {
                    // A battery pull, a crash or the battery dying sends no shutdown broadcast: use the last heartbeat.
                    if (prev.shutdownAt == null) out += DeviceEventEntity(type = "power_off", at = prev.beatAt, battery = prev.battery, estimated = true)
                    out += DeviceEventEntity(type = "power_on", at = bootAt, battery = snap.battery)
                }
            } else if (prev.running && snap.wallMs - prev.beatAt > APP_GAP_MS) {
                // Same boot, but the service died without stopping: force-stopped, or killed by the phone.
                out += DeviceEventEntity(type = "app_stopped", at = prev.beatAt, battery = prev.battery)
                out += DeviceEventEntity(type = "app_started", at = snap.wallMs, battery = snap.battery)
            }
        }
        val bad = badKinds(snap, prev.bad)
        out += diff(prev.bad, bad, snap.wallMs)
        save(out)
        store.write(beat(prev, snap).copy(running = true, shutdownAt = null, bad = bad))
    }

    /** Every loop of the running service: heartbeat, clock jumps and state changes. */
    suspend fun onHeartbeat() = lock.withLock {
        val prev = store.read()
        val snap = probe.snapshot()
        val out = mutableListOf<DeviceEventEntity>()
        if (prev.beatAt != null && prev.beatElapsed != null && snap.elapsedMs >= prev.beatElapsed) {
            // Wall time and time since boot advance together unless someone sets the clock.
            val shift = (snap.wallMs - prev.beatAt) - (snap.elapsedMs - prev.beatElapsed)
            if (abs(shift) > CLOCK_JUMP_MS) out += DeviceEventEntity(type = "clock_changed", at = snap.wallMs, shiftMs = shift)
        }
        val bad = badKinds(snap, prev.bad)
        out += diff(prev.bad, bad, snap.wallMs)
        save(out)
        store.write(beat(prev, snap).copy(running = true, bad = bad))
    }

    /** A broadcast or network change: record state changes now instead of at the next heartbeat. No heartbeat. */
    suspend fun checkStates() = lock.withLock {
        val prev = store.read()
        val snap = probe.snapshot()
        val bad = badKinds(snap, prev.bad)
        if (bad == prev.bad) return@withLock
        save(diff(prev.bad, bad, snap.wallMs))
        store.write(prev.copy(bad = bad))
    }

    /** A location fix arrived; [mock] = it came from a fake-GPS app. Also counts as a heartbeat. */
    suspend fun onFix(mock: Boolean) = lock.withLock {
        val prev = store.read()
        val snap = probe.snapshot()
        val bad = if (mock) prev.bad + MOCK else prev.bad - MOCK
        save(diff(prev.bad, bad, snap.wallMs))
        store.write(beat(prev, snap).copy(bad = bad))
    }

    /** ACTION_SHUTDOWN: the exact power-off time (only sent for a normal shutdown or restart). */
    suspend fun onShutdown() = lock.withLock {
        val prev = store.read()
        val snap = probe.snapshot()
        save(listOf(DeviceEventEntity(type = "power_off", at = snap.wallMs, battery = snap.battery)))
        store.write(beat(prev, snap).copy(shutdownAt = snap.wallMs))
    }

    /** The service stopped normally (end of hours, signed out, …): its absence afterwards is not "app stopped". */
    suspend fun onServiceStop() = lock.withLock { store.write(store.read().copy(running = false)) }

    /** Sign-in/out: the next agent's log starts clean. */
    suspend fun reset() = lock.withLock { store.clear() }

    private fun beat(prev: MonitorState, snap: DeviceSnapshot) =
        prev.copy(beatAt = snap.wallMs, beatElapsed = snap.elapsedMs, bootCount = snap.bootCount, battery = snap.battery)

    private suspend fun save(events: List<DeviceEventEntity>) {
        val dao = db.deviceEvents()
        events.forEach { dao.insert(it) }
    }

    companion object {
        /** A heartbeat older than this when the service restarts on the same boot means it was killed. */
        const val APP_GAP_MS = 3L * 60 * 1000
        const val CLOCK_JUMP_MS = 2L * 60 * 1000
        private const val MOCK = "mock"

        /** kind -> (event when it goes bad, event when it recovers) */
        val TRANSITIONS = linkedMapOf(
            "data" to ("data_off" to "data_on"),
            "airplane" to ("airplane_on" to "airplane_off"),
            "signal" to ("no_signal" to "signal_back"),
            "gps" to ("gps_off" to "gps_on"),
            "permission" to ("permission_revoked" to "permission_granted"),
            "clock" to ("auto_time_off" to "auto_time_on"),
            MOCK to ("mock_on" to "mock_off"),
        )

        /** The kinds that are "off" in [snap]. Fake GPS can't be read from the OS, so it carries over from [prev]. */
        fun badKinds(snap: DeviceSnapshot, prev: Set<String>): Set<String> = buildSet {
            if (snap.mobileDataOn == false) add("data")
            if (snap.airplane) add("airplane")
            if (!snap.online && !snap.airplane && snap.mobileDataOn != false) add("signal")
            if (!snap.gpsOn) add("gps")
            if (!snap.locationAllowed) add("permission")
            if (snap.autoTime == false) add("clock")
            if (MOCK in prev) add(MOCK)
        }

        fun diff(prev: Set<String>, now: Set<String>, at: Long): List<DeviceEventEntity> =
            TRANSITIONS.mapNotNull { (kind, types) ->
                when {
                    kind in now && kind !in prev -> DeviceEventEntity(type = types.first, at = at)
                    kind !in now && kind in prev -> DeviceEventEntity(type = types.second, at = at)
                    else -> null
                }
            }
    }
}
