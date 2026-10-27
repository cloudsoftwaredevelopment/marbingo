package ph.marbingo.field.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ph.marbingo.field.MarbingoApp

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = MarbingoApp.graph(applicationContext)
        return when (graph.syncEngine.run()) {
            is SyncEngine.Outcome.Success -> {
                // The pull may have brought a new work schedule: re-plan the alarms (and stop/start the service).
                try { graph.tracking.ensureScheduled() } catch (_: Exception) { }
                // Look for a newer app build too; a failure here must never fail the sync.
                try { graph.update.check() } catch (_: Exception) { }
                Result.success()
            }
            is SyncEngine.Outcome.Retry -> Result.retry()
            // Auth gone: the session Flow has gone null and the UI asks for sign-in; a permanent rejection won't improve on retry.
            is SyncEngine.Outcome.AuthFailed, is SyncEngine.Outcome.Failed -> Result.failure()
        }
    }
}
