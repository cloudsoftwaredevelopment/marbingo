package ph.marbingo.field.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import ph.marbingo.field.MainActivity
import ph.marbingo.field.MarbingoApp
import ph.marbingo.field.R
import ph.marbingo.field.data.db.LocationPointEntity

/**
 * Foreground service (type location) that records a fix every ~15 s while inside work hours and uploads them.
 * It stops itself when hours end; the exact alarm in [TrackingAlarmReceiver] starts it again at the next start.
 */
class LocationTrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val graph by lazy { MarbingoApp.graph(this) }
    private var client: FusedLocationProviderClient? = null
    private var loop: Job? = null
    private var tracking = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) { result.locations.forEach(::record) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Load the schedule first: the notification text comes from it.
        val schedule = runBlocking(Dispatchers.IO) { graph.db.trackingMeta().get().schedule() }
        ensureChannel()
        try {
            val notification = buildNotification(schedule)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Missing location permission on Android 14+ or a background start the OS refused.
            scope.launch { graph.db.trackingMeta().saveError("Couldn't start location sharing: ${e.message ?: e.javaClass.simpleName}") }
            stopSelf()
            return START_NOT_STICKY
        }
        running.value = true

        val signedIn = !graph.tokenStore.accessToken.isNullOrBlank() || !graph.tokenStore.refreshToken.isNullOrBlank()
        if (!signedIn || !schedule.isInHours(System.currentTimeMillis()) || !hasFineLocation()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!tracking) startTracking()
        return START_STICKY
    }

    @SuppressLint("MissingPermission") // checked in onStartCommand via hasFineLocation()
    private fun startTracking() {
        val c = LocationServices.getFusedLocationProviderClient(this)
        client = c
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, FIX_INTERVAL_MS)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
            .setMaxUpdateDelayMillis(MAX_DELAY_MS) // lets the OS batch fixes to save battery
            .build()
        try {
            c.requestLocationUpdates(request, callback, Looper.getMainLooper())
            tracking = true
        } catch (e: SecurityException) {
            scope.launch { graph.db.trackingMeta().saveError("Location permission is missing.") }
            stopSelf()
            return
        }
        loop = scope.launch { runLoop() }
    }

    /** Every 30 s: end of hours? heartbeat fix while standing still? upload every ~2 min. */
    private suspend fun runLoop() {
        var lastUploadTry = 0L
        while (scope.isActive) {
            delay(LOOP_MS)
            val now = System.currentTimeMillis()
            val meta = graph.db.trackingMeta().get()
            val signedIn = !graph.tokenStore.accessToken.isNullOrBlank() || !graph.tokenStore.refreshToken.isNullOrBlank()
            if (!signedIn || !meta.schedule().isInHours(now)) { stopSelf(); return }

            // With a 25 m minimum distance a stationary agent produces no fixes; ask for one so the map doesn't show "signal lost" during a long visit.
            if (now - (meta?.lastFixAt ?: 0L) > HEARTBEAT_MS) requestOneFix()

            if (now - lastUploadTry >= UPLOAD_EVERY_MS) {
                lastUploadTry = now
                val outcome = graph.uploader.uploadPending()
                if (outcome is LocationUploader.Outcome.AuthFailed && graph.tokenStore.refreshToken.isNullOrBlank()) {
                    stopSelf(); return // session cleared: same as the sync engine, the UI asks for sign-in
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestOneFix() {
        val c = client ?: return
        try {
            val req = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setDurationMillis(20_000)
                .build()
            c.getCurrentLocation(req, null).addOnSuccessListener { it?.let(::record) }
        } catch (_: SecurityException) { }
    }

    private fun record(loc: Location) {
        if (loc.hasAccuracy() && loc.accuracy > MAX_ACCURACY_M) return // the server rejects these anyway
        val battery = battery()
        scope.launch {
            val point = LocationPointEntity(
                recordedAt = loc.time,
                lat = loc.latitude, lng = loc.longitude,
                accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                speed = if (loc.hasSpeed()) loc.speed.toDouble() else null,
                bearing = if (loc.hasBearing()) loc.bearing.toDouble() else null,
                battery = battery,
            )
            graph.db.locationPoints().insert(point)
            graph.db.trackingMeta().saveLastFix(minOf(loc.time, System.currentTimeMillis()))
        }
    }

    private fun battery(): Int? {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (pct in 0..100) pct else null
    }

    private fun hasFineLocation() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        loop?.cancel()
        try { client?.removeLocationUpdates(callback) } catch (_: Exception) { }
        scope.cancel()
        running.value = false
        // Final upload outlives this service.
        val g = graph
        g.appScope.launch { withTimeoutOrNull(15_000) { g.uploader.uploadPending() } }
        super.onDestroy()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Location sharing", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the app shares this tablet's location during work hours."
                    setShowBadge(false)
                },
            )
        }
    }

    private fun buildNotification(schedule: TrackingSchedule): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle("Marbingo Field")
            .setContentText(notificationText(schedule))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "location_sharing"
        const val NOTIFICATION_ID = 4101
        const val FIX_INTERVAL_MS = 15_000L
        const val MIN_DISTANCE_M = 25f
        const val MAX_DELAY_MS = 60_000L
        const val MAX_ACCURACY_M = 500f
        private const val LOOP_MS = 30_000L
        private const val UPLOAD_EVERY_MS = 2 * 60_000L
        private const val HEARTBEAT_MS = 3 * 60_000L

        fun notificationText(schedule: TrackingSchedule) = "Sharing location during work hours (${schedule.hoursLabel()})"

        internal val running = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> get() = running
    }
}
