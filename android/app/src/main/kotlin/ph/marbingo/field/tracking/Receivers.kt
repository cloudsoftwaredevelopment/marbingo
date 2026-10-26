package ph.marbingo.field.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import ph.marbingo.field.MarbingoApp

/** Runs [TrackingRepository.ensureScheduled] off the main thread while keeping the receiver alive. */
private fun BroadcastReceiver.reschedule(context: Context) {
    val pending = goAsync()
    val graph = MarbingoApp.graph(context)
    graph.appScope.launch {
        try { graph.tracking.ensureScheduled() } catch (_: Exception) { } finally { pending.finish() }
    }
}

/** The exact alarm: work hours start or end (or the on-duty watchdog fires). Starts/stops the service and sets the next alarm. */
class TrackingAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TICK) reschedule(context)
    }
    companion object { const val ACTION_TICK = "ph.marbingo.field.TRACKING_TICK" }
}

/** Alarms don't survive a reboot or an app update, and a clock/timezone change moves the window. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> reschedule(context)
        }
    }
}
