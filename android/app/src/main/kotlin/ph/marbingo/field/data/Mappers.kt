package ph.marbingo.field.data

import ph.marbingo.core.model.CustomerDto
import ph.marbingo.core.model.ItemDto
import ph.marbingo.core.model.OrderDto
import ph.marbingo.core.model.TruckDto
import ph.marbingo.core.pricing.PartsJson
import ph.marbingo.field.data.db.*
import java.time.Instant
import java.time.OffsetDateTime

/** Thrown with a message that is safe to show to the agent as-is. */
class UserFacingException(message: String) : Exception(message)

object Iso {
    fun parse(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        return runCatching { Instant.parse(s).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(s).toInstant().toEpochMilli() }
            .getOrNull()
    }
    fun format(millis: Long): String = Instant.ofEpochMilli(millis).toString()
}

internal fun ItemWithPacks.toModel() = Item(
    item.id, item.name, item.sku, item.unit, item.category, item.sellingPrice, item.qtyOnHand, item.imageUrl,
    packs.sortedWith(compareBy({ -it.size }, { it.price })).map { Pack(it.packTypeId, it.name, it.size, it.price) },
)

internal fun ItemDto.toEntity() = ItemEntity(id, name, sku, unit, categoryId, category, sellingPrice, qtyOnHand, imageUrl, updatedAt)

internal fun ItemDto.packEntities() = packs.map { ItemPackEntity(id, it.packTypeId, it.name, it.size, it.price) }

internal fun CustomerEntity.toModel() = Store(clientUuid, serverId, name, ownerName, phone, address, notes, dirty, lat, lng, locationAccuracy)

internal fun CustomerDto.toEntity(now: Long) = CustomerEntity(
    clientUuid, id, name, ownerName, phone, address, notes, active, dirty = false,
    updatedAt = Iso.parse(updatedAt) ?: now,
    lat = lat, lng = lng, locationAccuracy = locationAccuracy, locationSource = locationSource,
)

private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
    enumValues<E>().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: default

internal fun OrderWithLines.toModel() = Order(
    clientUuid = order.clientUuid,
    serverId = order.serverId,
    storeClientUuid = order.customerClientUuid,
    storeName = order.storeName,
    agentName = order.agentName,
    orderedAt = order.orderedAt,
    status = enumOr(order.status, OrderStatus.PENDING),
    syncState = enumOr(order.syncState, SyncState.QUEUED),
    syncError = order.syncError,
    notes = order.notes,
    total = order.total,
    lines = lines.sortedBy { it.id }.map { OrderLine(it.itemId, it.name, it.unit, it.qty, it.unitPrice, it.lineTotal, it.deliveredQty,
        PartsJson.decode(it.breakdown), PartsJson.decode(it.deliveredBreakdown)) },
    deliveredAt = order.deliveredAt,
    cancelledReason = order.cancelledReason,
)

/** Server copy of an order -> local rows (always SYNCED). */
internal fun OrderDto.toEntities(existing: OrderEntity?, now: Long): Pair<OrderEntity, List<OrderLineEntity>> {
    val agentName = agent?.let { "${it.firstName} ${it.lastName}".trim() } ?: existing?.agentName
    val entity = OrderEntity(
        clientUuid = clientUuid,
        serverId = id,
        customerClientUuid = customer.clientUuid,
        storeName = customer.name,
        status = status.uppercase(),
        syncState = SyncState.SYNCED.name,
        syncError = null,
        attempts = 0,
        orderedAt = Iso.parse(orderedAt) ?: existing?.orderedAt ?: now,
        total = total,
        notes = notes,
        deliveredAt = Iso.parse(deliveredAt),
        cancelledReason = cancelledReason,
        agentName = agentName,
        updatedAt = Iso.parse(updatedAt) ?: now,
    )
    val rows = lines.map { OrderLineEntity(orderClientUuid = clientUuid, itemId = it.itemId, name = it.name, unit = it.unit,
        qty = it.qty, unitPrice = it.unitPrice, lineTotal = it.lineTotal, deliveredQty = it.deliveredQty,
        breakdown = PartsJson.encode(it.breakdown), deliveredBreakdown = PartsJson.encode(it.deliveredBreakdown)) }
    return entity to rows
}

internal fun TruckSaleWithLines.toOrder() = Order(
    clientUuid = sale.clientUuid,
    serverId = sale.serverId,
    storeClientUuid = sale.customerClientUuid.orEmpty(),
    storeName = sale.storeName ?: WALK_IN,
    agentName = sale.agentName,
    orderedAt = sale.soldAt,
    status = OrderStatus.DELIVERED,
    syncState = enumOr(sale.syncState, SyncState.QUEUED),
    syncError = sale.syncError,
    notes = null,
    total = sale.total,
    lines = lines.sortedBy { it.id }.map { OrderLine(it.itemId, it.name, it.unit, it.qty, it.unitPrice, it.lineTotal, null, PartsJson.decode(it.breakdown)) },
    deliveredAt = sale.soldAt,
    cancelledReason = null,
    isSale = true,
    oversold = sale.oversold,
)

const val WALK_IN = "Walk-in customer"

internal fun TruckDto.toMeta() = TruckMetaEntity(
    truckId = id, name = name, plate = plate, driverName = driverName,
    periodId = period?.id, periodMonth = period?.month, periodStatus = period?.status,
)
