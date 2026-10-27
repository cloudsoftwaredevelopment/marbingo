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
        db.truck().waitingCount(),
    ) { meta, orders, stores, running, truckSales ->
        // Truck sales wait to sync just like orders, so they share the "waiting" count.
        SyncStatus(meta?.lastSyncedAt, orders + truckSales, stores, running, meta?.lastError)
    }

    override fun syncNow() = trigger.syncNow()
}
