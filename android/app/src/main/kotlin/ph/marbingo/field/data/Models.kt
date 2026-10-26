package ph.marbingo.field.data

// UI-facing models. Repositories (implemented in data/) map Room entities to these;
// screens and ViewModels depend only on these and on the interfaces in Repositories.kt.

data class Item(
    val id: Int,
    val name: String,
    val sku: String?,
    val unit: String,
    val category: String?,
    val fieldPrice: Long,      // centavos: wholesale ?? selling
    val sellingPrice: Long,
    val qtyOnHand: Int,
    val imageUrl: String?,
)

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
) {
    /** "#12" once synced, otherwise a short local reference like "L-3F9A". */
    val reference: String get() = serverId?.let { "#$it" } ?: "L-" + clientUuid.take(4).uppercase()
}

data class CartLine(val item: Item, val qty: Int)

data class SyncStatus(
    val lastSyncedAt: Long?,   // epoch millis of last successful pull
    val waitingOrders: Int,
    val waitingStores: Int,
    val running: Boolean,
    val lastError: String?,
)

data class Session(val userId: Int, val name: String, val role: String, val shopName: String, val shopMark: String)
