package ph.marbingo.field.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import ph.marbingo.field.MarbingoApp

/** Receives the PackageInstaller session result (not exported: only our own PendingIntent targets it). */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // The system wants the user to confirm: show its dialog.
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try { context.startActivity(confirm) } catch (_: Exception) {
                    MarbingoApp.graph(context).updateImpl.onInstallResult(PackageInstaller.STATUS_FAILURE, "Could not open the installer.")
                }
            }
            return
        }
        MarbingoApp.graph(context).updateImpl.onInstallResult(status, message)
    }

    companion object {
        /** Mutable on 31+ because the system fills in the status extras. */
        fun pendingIntent(context: Context, requestCode: Int): PendingIntent {
            val intent = Intent(context, InstallResultReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getBroadcast(context, requestCode, intent, flags)
        }
    }
}
