package ph.marbingo.field.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import ph.marbingo.field.data.db.AppDatabase
import ph.marbingo.field.sync.SyncTrigger

class SyncRepositoryImpl(private val db: AppDatabase, private val trigger: SyncTrigger) : SyncRepository {
    override val status: Flow<SyncStatus> = combine(
        db.syncMeta().observe(),
        db.orders().waitingCount(),
        db.customers().dirtyCount(),
        trigger.running.onStart { emit(false) },
    ) { meta, orders, stores, running ->
        SyncStatus(meta?.lastSyncedAt, orders, stores, running, meta?.lastError)
    }

    override fun syncNow() = trigger.syncNow()
}
