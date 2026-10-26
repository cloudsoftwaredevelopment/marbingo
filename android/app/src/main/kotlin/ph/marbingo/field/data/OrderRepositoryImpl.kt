package ph.marbingo.field.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ph.marbingo.core.model.CancelRequest
import ph.marbingo.core.net.ApiErrors
import ph.marbingo.core.net.MarbingoApi
import ph.marbingo.core.net.TokenStore
import ph.marbingo.core.pricing.PartsJson
import ph.marbingo.core.pricing.Pricing
import ph.marbingo.field.data.db.AppDatabase
import ph.marbingo.field.data.db.OrderEntity
import ph.marbingo.field.data.db.OrderLineEntity
import ph.marbingo.field.sync.SyncTrigger
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID

class OrderRepositoryImpl(
    private val db: AppDatabase,
    private val api: MarbingoApi,
    private val tokens: TokenStore,
    private val trigger: SyncTrigger,
    private val now: () -> Long = System::currentTimeMillis,
) : OrderRepository {

    override fun recent(limit: Int): Flow<List<Order>> = db.orders().recent(limit).map { l -> l.map { it.toModel() } }
    override fun forStore(storeClientUuid: String): Flow<List<Order>> =
        db.orders().forStore(storeClientUuid).map { l -> l.map { it.toModel() } }
    override fun observe(clientUuid: String): Flow<Order?> = db.orders().observe(clientUuid).map { it?.toModel() }

    override suspend fun place(storeClientUuid: String, lines: List<CartLine>, notes: String?): String {
        if (lines.isEmpty()) throw UserFacingException("Add at least one item.")
        if (lines.any { it.qty < 1 }) throw UserFacingException("Quantities must be at least 1.")
        val store = db.customers().get(storeClientUuid) ?: throw UserFacingException("Choose a store first.")
        val uuid = UUID.randomUUID().toString()
        val t = now()
        val user = tokens.user
        // Merge duplicate items and price from the cache with the cheapest pack mix. The server re-prices on
        // sync (from its own prices) and the synced order replaces this local copy.
        val rows = lines.groupBy { it.item.id }.map { (id, group) ->
            val item = db.items().byId(id)?.toModel() ?: group.first().item
            val qty = group.sumOf { it.qty }
            val priced = Pricing.priceQty(qty, item.sellingPrice, item.packDefs)
            OrderLineEntity(orderClientUuid = uuid, itemId = item.id, name = item.name, unit = item.unit,
                qty = qty, unitPrice = item.sellingPrice, lineTotal = priced.total, deliveredQty = null,
                breakdown = PartsJson.encode(priced.parts))
        }
        val order = OrderEntity(
            clientUuid = uuid, serverId = null, customerClientUuid = store.clientUuid, storeName = store.name,
            status = OrderStatus.PENDING.name, syncState = SyncState.QUEUED.name, syncError = null, attempts = 0,
            orderedAt = t, total = rows.sumOf { it.lineTotal }, notes = notes?.trim()?.ifEmpty { null },
            deliveredAt = null, cancelledReason = null,
            agentName = user?.let { "${it.firstName} ${it.lastName}".trim() }, updatedAt = t,
        )
        db.withTransaction { db.orders().replace(order, rows) }
        trigger.syncNow()
        return uuid
    }

    override suspend fun cancel(clientUuid: String, reason: String) {
        val o = db.orders().get(clientUuid) ?: return
        val serverId = o.serverId
        if (serverId == null || o.syncState != SyncState.SYNCED.name) {
            // The first upload may have reached the server even though we never got the reply, so a local
            // delete isn't enough. Queue it as cancelled: the batch push sends cancelReason and the server
            // either cancels its copy or records the order as cancelled so it can never be delivered.
            db.orders().upsert(o.copy(status = OrderStatus.CANCELLED.name, cancelledReason = reason.trim(),
                syncState = SyncState.QUEUED.name, syncError = null, updatedAt = now()))
            trigger.syncNow()
            return
        }
        if (!o.status.equals(OrderStatus.PENDING.name, ignoreCase = true)) {
            throw UserFacingException("Only pending orders can be cancelled.")
        }
        val dto = try {
            api.cancelOrder(serverId, CancelRequest(reason.trim()))
        } catch (e: IOException) {
            throw UserFacingException("You need a connection to cancel an order that was already sent.")
        } catch (e: HttpException) {
            throw UserFacingException(ApiErrors.message(e, "Couldn't cancel this order."))
        }.order
        db.withTransaction {
            val (e, rows) = dto.toEntities(db.orders().get(clientUuid), now())
            db.orders().replace(e, rows)
        }
    }
}
