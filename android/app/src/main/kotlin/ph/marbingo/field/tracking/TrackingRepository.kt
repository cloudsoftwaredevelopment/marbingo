package ph.marbingo.field.tracking

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import ph.marbingo.core.net.MarbingoApi
import ph.marbingo.core.net.TokenStore
import ph.marbingo.field.data.Iso
import ph.marbingo.field.data.db.AppDatabase
import ph.marbingo.field.data.db.TrackingMetaEntity

/** The UI's view of work-hours location sharing. Everything reads the local DB, so it works offline. */
interface TrackingRepository {
    val state: Flow<TrackingState>
    /** Records consent locally and tells the server (queued for the next sync when offline). */
    suspend fun giveConsent()
    /** Re-reads the schedule from the server, then reschedules. Failures keep the stored schedule. */
    suspend fun refreshSchedule()
    /** Re-reads the OS permissions; call when the app returns from a settings screen. */
    fun refreshPermissions()
    /** Idempotent: starts or stops the service for this moment and sets the alarm for the next change. */
    suspend fun ensureScheduled()
}

/** Seams to the OS so the repository logic is testable on the JVM. */
interface PermissionChecker { fun snapshot(): PermissionState }
interface ServiceController {
    val running: StateFlow<Boolean>
    /** Returns an error message when the OS refuses to start it. */
    fun start(): String?
    fun stop()
}
interface AlarmScheduler {
    /** null cancels. */
    fun scheduleAt(millis: Long?)
}

class TrackingRepositoryImpl(
    private val db: AppDatabase,
    private val api: MarbingoApi,
    private val tokens: TokenStore,
    private val permissions: PermissionChecker,
    private val service: ServiceController,
    private val alarms: AlarmScheduler,
    private val now: () -> Long = System::currentTimeMillis,
    private val ticker: Flow<Unit> = flow { while (true) { emit(Unit); delay(30_000) } },
    private val monitor: DeviceMonitor? = null,
) : TrackingRepository {

    private val permState = MutableStateFlow(permissions.snapshot())

    override val state: Flow<TrackingState> = combine(
        db.trackingMeta().observe(),
        db.locationPoints().pendingCount(),
        permState,
        service.running,
        ticker,
    ) { meta, pending, perms, running, _ ->
        TrackingState(
            schedule = meta.schedule(),
            now = now(),
            consentGiven = meta?.consentAt != null,
            permissions = perms,
            serviceRunning = running,
            pendingPoints = pending,
            lastFixAt = meta?.lastFixAt,
            lastUploadAt = meta?.lastUploadAt,
            lastError = meta?.lastError,
        )
    }

    override fun refreshPermissions() { permState.value = permissions.snapshot() }

    override suspend fun giveConsent() {
        val existing = db.trackingMeta().get()
        if (existing?.consentAt == null) db.trackingMeta().saveConsent(now(), false)
        try {
            val resp = api.giveTrackingConsent()
            db.trackingMeta().saveConsent(Iso.parse(resp.trackingConsentAt) ?: db.trackingMeta().get()?.consentAt ?: now(), true)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or server trouble: consentSynced stays false and the sync engine retries.
        }
        refreshPermissions()
    }

    override suspend fun refreshSchedule() {
        try {
            val dto = api.trackingSchedule()
            val s = TrackingSchedule.from(dto)
            if (s != null) db.trackingMeta().saveSchedule(s.toCsv(), dto.start, dto.end)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) { }
        ensureScheduled()
    }

    override suspend fun ensureScheduled() {
        refreshPermissions()
        if (tokens.accessToken.isNullOrBlank() && tokens.refreshToken.isNullOrBlank()) {
            alarms.scheduleAt(null)
            service.stop()
            return
        }
        val meta = db.trackingMeta().get()
        val schedule = meta.schedule()
        val p = permState.value
        val n = now()
        val inHours = schedule.isInHours(n)
        // On duty: log a revoked permission or GPS switched off even when the service can't run.
        if (inHours && meta?.consentAt != null) monitor?.checkStates()
        if (inHours && meta?.consentAt != null && p.fine && p.background) {
            service.start()?.let { db.trackingMeta().saveError(it) }
        } else {
            service.stop()
        }
        // While on duty also wake every WATCHDOG_MS: an exact alarm may start the service again if the OS killed it.
        val next = schedule.nextTransition(n)
        alarms.scheduleAt(if (inHours && next != null) minOf(next, n + WATCHDOG_MS) else next)
    }

    companion object { const val WATCHDOG_MS = 15L * 60 * 1000 }
}

internal fun TrackingMetaEntity?.schedule(): TrackingSchedule =
    this?.let { TrackingSchedule.fromCsv(it.scheduleDays, it.scheduleStart, it.scheduleEnd) } ?: TrackingSchedule.DEFAULT
