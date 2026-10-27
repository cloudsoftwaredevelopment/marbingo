package ph.marbingo.field.data

import ph.marbingo.core.model.PricePartDto
import ph.marbingo.core.pricing.PackDef
import ph.marbingo.core.pricing.PriceResult
import ph.marbingo.core.pricing.Pricing
import ph.marbingo.core.pricing.Upsell

// UI-facing models. Repositories (implemented in data/) map Room entities to these;
// screens and ViewModels depend only on these and on the interfaces in Repositories.kt.

data class Item(
    val id: Int,
    val name: String,
    val sku: String?,
    val unit: String,
    val category: String?,
    val sellingPrice: Long,    // centavos per base unit (retail)
    val qtyOnHand: Int,
    val imageUrl: String?,
    val packs: List<Pack> = emptyList(),
) {
    val packDefs: List<PackDef> get() = packs.map { PackDef(it.packTypeId, it.name, it.size, it.price) }
}

/** A wholesale bundle: [size] base units for [price] centavos in total. */
data class Pack(val packTypeId: Int, val name: String, val size: Int, val price: Long)

data class Store(
    val clientUuid: String,    // stable key, created on device or server
    val serverId: Int?,        // null until synced
    val name: String,
    val ownerName: String?,
    val phone: String?,
    val address: String?,
    val notes: String?,
    val pendingSync: Boolean,
    val lat: Double? = null,
    val lng: Double? = null,
    val locationAccuracy: Double? = null,   // metres
) {
    val hasLocation: Boolean get() = lat != null && lng != null
}

/** A GPS fix taken on the tablet. */
data class GeoFix(val lat: Double, val lng: Double, val accuracy: Double?)

enum class SyncState { QUEUED, SYNCED, ERROR }
enum class OrderStatus { PENDING, DELIVERED, CANCELLED }

data class OrderLine(
    val itemId: Int,
    val name: String,
    val unit: String?,
    val qty: Int,
    val unitPrice: Long,
    val lineTotal: Long,
    val deliveredQty: Int?,
    /** Price parts for the ordered qty; null for lines saved before pack pricing. */
    val breakdown: List<PricePartDto>? = null,
    /** Price parts for the delivered qty; null until delivered. */
    val deliveredBreakdown: List<PricePartDto>? = null,
)

data class Order(
    val clientUuid: String,
    val serverId: Int?,
    val storeClientUuid: String,
    val storeName: String,
    val agentName: String?,
    val orderedAt: Long,       // epoch millis
    val status: OrderStatus,
    val syncState: SyncState,
    val syncError: String?,
    val notes: String?,
    val total: Long,
    val lines: List<OrderLine>,
    val deliveredAt: Long?,
    val cancelledReason: String?,
    /** True for a truck sale (a paid sales receipt) shown next to orders; false for a booked order. */
    val isSale: Boolean = false,
    /** Truck sale the server accepted with more than was on the truck (truck stock went negative). */
    val oversold: Boolean = false,
) {
    /** "#12" once synced, otherwise a short local reference like "L-3F9A". Truck sales are prefixed "S". */
    val reference: String get() = (if (isSale) "S" else "") + (serverId?.let { "#$it" } ?: "L-" + clientUuid.take(4).uppercase())
}

/** The truck an agent drives, with the selling state derived from the period. */
data class TruckInfo(
    val id: Int,
    val name: String,
    val plate: String?,
    val driverName: String?,
    /** draft | open | counting; null when the truck has no current period. */
    val periodStatus: String?,
    val periodMonth: String?,
    /** Units on the truck after subtracting sales still waiting to sync. */
    val unitsOnBoard: Int,
) {
    /** Sales are taken while the period is open (or being counted). A draft or missing period waits for the manager. */
    val canSell: Boolean get() = periodStatus == "open" || periodStatus == "counting"
    val waitingForApproval: Boolean get() = !canSell
}

/** One row of the Truck stock screen. */
data class TruckStockLine(val itemId: Int, val name: String, val unit: String?, val onBoard: Int, val available: Int)

/** An item in the cart. [priced] (cheapest packs + loose) is computed once per instance; copy() recomputes it. */
data class CartLine(val item: Item, val qty: Int) {
    val priced: PriceResult = Pricing.priceQty(qty, item.sellingPrice, item.packDefs)
    val total: Long get() = priced.total
    /** "Add 1 kg and pay less" hint, computed only when asked for. */
    val upsell: Upsell? by lazy(LazyThreadSafetyMode.NONE) { Pricing.suggestUpsell(qty, item.sellingPrice, item.packDefs) }
}

data class SyncStatus(
    val lastSyncedAt: Long?,   // epoch millis of last successful pull
    val waitingOrders: Int,
    val waitingStores: Int,
    val running: Boolean,
    val lastError: String?,
)

data class Session(val userId: Int, val name: String, val role: String, val shopName: String, val shopMark: String)
