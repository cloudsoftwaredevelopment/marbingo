package ph.marbingo.field.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ph.marbingo.field.MarbingoApp

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (MarbingoApp.graph(applicationContext).syncEngine.run()) {
            is SyncEngine.Outcome.Success -> Result.success()
            is SyncEngine.Outcome.Retry -> Result.retry()
            // Auth gone: the session Flow has gone null and the UI asks for sign-in; a permanent rejection won't improve on retry.
            is SyncEngine.Outcome.AuthFailed, is SyncEngine.Outcome.Failed -> Result.failure()
        }
}
