package ph.marbingo.field.data

import ph.marbingo.core.model.CustomerDto
import ph.marbingo.core.model.ItemDto
import ph.marbingo.core.model.OrderDto
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

internal fun ItemEntity.toModel() = Item(id, name, sku, unit, category, fieldPrice, sellingPrice, qtyOnHand, imageUrl)

internal fun ItemDto.toEntity() = ItemEntity(
    id, name, sku, unit, categoryId, category, sellingPrice, wholesalePrice, fieldPrice, qtyOnHand, imageUrl, updatedAt,
)

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
    lines = lines.sortedBy { it.id }.map { OrderLine(it.itemId, it.name, it.unit, it.qty, it.unitPrice, it.lineTotal, it.deliveredQty) },
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
        qty = it.qty, unitPrice = it.unitPrice, lineTotal = it.lineTotal, deliveredQty = it.deliveredQty) }
    return entity to rows
}
