package ph.marbingo.field.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import ph.marbingo.core.pricing.PartsJson
import ph.marbingo.core.pricing.Pricing
import ph.marbingo.field.data.db.*
import ph.marbingo.field.sync.SyncTrigger
import ph.marbingo.core.net.TokenStore
import java.util.UUID

class TruckRepositoryImpl(
    private val db: AppDatabase,
    private val tokens: TokenStore,
    private val trigger: SyncTrigger,
    private val now: () -> Long = System::currentTimeMillis,
) : TruckRepository {

    override fun observeTruck(): Flow<TruckInfo?> = combine(db.truck().observeMeta(), db.truck().observeStock()) { m, stock ->
        m?.let {
            TruckInfo(it.truckId, it.name, it.plate, it.driverName, it.periodStatus, it.periodMonth,
                unitsOnBoard = stock.sumOf { r -> r.available.coerceAtLeast(0) })
        }
    }

    override fun observeAvailable(): Flow<Map<Int, Int>> = db.truck().observeStock().map { rows -> rows.associate { it.itemId to it.available } }

    override fun observeStock(): Flow<List<TruckStockLine>> = db.truck().observeStock().map { rows ->
        rows.map { TruckStockLine(it.itemId, it.name, it.unit, it.qty, it.available) }
    }

    override fun searchAvailable(query: String): Flow<List<Item>> =
        combine(db.items().searchOnTruck(query), db.truck().observeStock()) { items, rows ->
            val avail = rows.associate { it.itemId to it.available }
            items.mapNotNull { iw -> (avail[iw.item.id] ?: 0).takeIf { it > 0 }?.let { iw.toModel().copy(qtyOnHand = it) } }
        }

    override fun recent(limit: Int): Flow<List<Order>> = db.truck().recent(limit).map { l -> l.map { it.toOrder() } }
    override fun observe(clientUuid: String): Flow<Order?> = db.truck().observe(clientUuid).map { it?.toOrder() }

    override suspend fun placeSale(storeClientUuid: String?, lines: List<CartLine>): String {
        if (lines.isEmpty()) throw UserFacingException("Add at least one item.")
        if (lines.any { it.qty < 1 }) throw UserFacingException("Quantities must be at least 1.")
        val meta = db.truck().meta() ?: throw UserFacingException("You are not assigned to a truck.")
        if (meta.periodStatus != "open" && meta.periodStatus != "counting") {
            throw UserFacingException("Waiting for manager approval. You can sell once the truck is opened.")
        }
        val store = storeClientUuid?.let { db.customers().get(it) ?: throw UserFacingException("That store is missing on this tablet.") }
        val uuid = UUID.randomUUID().toString()
        val t = now()
        val user = tokens.user
        db.withTransaction {
            val rows = lines.groupBy { it.item.id }.map { (id, group) ->
                val item = db.items().byId(id)?.toModel() ?: group.first().item
                val qty = group.sumOf { it.qty }
                val have = db.truck().stockFor(id)?.available ?: 0
                if (qty > have) {
                    throw UserFacingException(if (have <= 0) "${item.name} is not on the truck." else "Only $have of ${item.name} left on the truck.")
                }
                val priced = Pricing.priceQty(qty, item.sellingPrice, item.packDefs)
                TruckSaleLineEntity(saleClientUuid = uuid, itemId = id, name = item.name, unit = item.unit, qty = qty,
                    unitPrice = item.sellingPrice, lineTotal = priced.total, breakdown = PartsJson.encode(priced.parts))
            }
            val sale = TruckSaleEntity(
                clientUuid = uuid, serverId = null, customerClientUuid = store?.clientUuid, storeName = store?.name,
                syncState = SyncState.QUEUED.name, syncError = null, attempts = 0, soldAt = t, total = rows.sumOf { it.lineTotal },
                oversold = false, late = false,
                agentName = user?.let { "${it.firstName} ${it.lastName}".trim() }, updatedAt = t,
            )
            db.truck().replace(sale, rows)
        }
        trigger.syncNow()
        return uuid
    }
}
