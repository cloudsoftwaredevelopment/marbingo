package ph.marbingo.field.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.concurrent.TimeUnit

/** What the data layer needs from the scheduler; tests substitute a fake. */
interface SyncTrigger {
    /** Schedule the 15-minute periodic sync (idempotent). */
    fun start()
    /** Run a sync as soon as there is a network. */
    fun syncNow()
    fun cancelAll()
    val running: Flow<Boolean>
}

class SyncScheduler(private val context: Context) : SyncTrigger {
    private val wm get() = WorkManager.getInstance(context)
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun start() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /**
     * Not expedited on purpose: expedited work on Android < 12 needs a foreground service and notification.
     * APPEND_OR_REPLACE so a change saved while a sync is mid-flight still gets its own run afterwards.
     */
    override fun syncNow() {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm.enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    override fun cancelAll() {
        wm.cancelUniqueWork(PERIODIC)
        wm.cancelUniqueWork(ONE_TIME)
    }

    override val running: Flow<Boolean> by lazy {
        combine(wm.getWorkInfosForUniqueWorkFlow(PERIODIC), wm.getWorkInfosForUniqueWorkFlow(ONE_TIME)) { a, b ->
            (a + b).any { it.state == WorkInfo.State.RUNNING }
        }
    }

    companion object {
        const val PERIODIC = "marbingo-sync-periodic"
        const val ONE_TIME = "marbingo-sync-now"
    }
}
