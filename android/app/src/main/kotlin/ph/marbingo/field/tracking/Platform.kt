package ph.marbingo.field.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import ph.marbingo.field.data.DeviceLocator
import ph.marbingo.field.data.GeoFix
import kotlin.coroutines.resume

class AndroidPermissionChecker(private val context: Context) : PermissionChecker {
    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    override fun snapshot(): PermissionState {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val notifications = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return PermissionState(
            fine = fine,
            background = background,
            notifications = notifications,
            batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName),
            gpsEnabled = LocationManagerCompat.isLocationEnabled(lm),
        )
    }
}

class AndroidServiceController(private val context: Context) : ServiceController {
    override val running: StateFlow<Boolean> get() = LocationTrackingService.running

    override fun start(): String? = try {
        if (!running.value) ContextCompat.startForegroundService(context, Intent(context, LocationTrackingService::class.java))
        null
    } catch (e: Exception) {
        // e.g. ForegroundServiceStartNotAllowedException when started from the background without the right exemption.
        "Couldn't start location sharing: ${e.message ?: e.javaClass.simpleName}"
    }

    override fun stop() {
        if (!running.value) return
        context.stopService(Intent(context, LocationTrackingService::class.java))
    }
}

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    private val am get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun intent() = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, TrackingAlarmReceiver::class.java).setAction(TrackingAlarmReceiver.ACTION_TICK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    @SuppressLint("ScheduleExactAlarm") // USE_EXACT_ALARM is declared; the inexact fallback covers a denied grant on 12L.
    override fun scheduleAt(millis: Long?) {
        val pi = intent()
        if (millis == null) { am.cancel(pi); return }
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (exactAllowed) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
    }
}

/** One-shot high-accuracy fix for "Use my current location" / "Set location here". */
class FusedDeviceLocator(private val context: Context) : DeviceLocator {
    override fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // guarded by hasPermission()
    override suspend fun currentFix(timeoutMs: Long): GeoFix? {
        if (!hasPermission()) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val cts = CancellationTokenSource()
                cont.invokeOnCancellation { cts.cancel() }
                val req = CurrentLocationRequest.Builder()
                    .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setDurationMillis(timeoutMs)
                    .setMaxUpdateAgeMillis(0)
                    .build()
                try {
                    client.getCurrentLocation(req, cts.token)
                        .addOnSuccessListener { if (cont.isActive) cont.resume(it?.toFix()) }
                        .addOnFailureListener { if (cont.isActive) cont.resume(null) }
                } catch (e: SecurityException) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private fun Location.toFix() = GeoFix(latitude, longitude, if (hasAccuracy()) accuracy.toDouble() else null)
}
