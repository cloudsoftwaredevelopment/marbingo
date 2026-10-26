package ph.marbingo.field.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val sku: String?,
    val unit: String,
    val categoryId: Int?,
    val category: String?,
    val sellingPrice: Long,
    val qtyOnHand: Int,
    val imageUrl: String?,
    val updatedAt: String,
)

/** One wholesale bundle of an item (docs/pricing.md). Replaced wholesale whenever the item is upserted from the server. */
@Entity(tableName = "item_packs", primaryKeys = ["itemId", "packTypeId"], indices = [Index("itemId")])
data class ItemPackEntity(
    val itemId: Int,
    val packTypeId: Int,
    val name: String,
    val size: Int,
    val price: Long,
)

data class ItemWithPacks(
    @Embedded val item: ItemEntity,
    @Relation(parentColumn = "id", entityColumn = "itemId") val packs: List<ItemPackEntity>,
)

@Entity(tableName = "customers", indices = [Index("serverId")])
data class CustomerEntity(
    @PrimaryKey val clientUuid: String,
    val serverId: Int?,
    val name: String,
    val ownerName: String?,
    val phone: String?,
    val address: String?,
    val notes: String?,
    val active: Boolean,
    /** True while the store exists only on this device (or has unsent changes). */
    val dirty: Boolean,
    val updatedAt: Long,
    /** Store pin. locationSource is "device" while it was set on this tablet and may still need pushing, else the server's value (gps|manual). */
    val lat: Double? = null,
    val lng: Double? = null,
    val locationAccuracy: Double? = null,
    val locationSource: String? = null,
) {
    /** True only when the agent set the pin here: never push (and so never overwrite) a server-side pin otherwise. */
    val hasDeviceLocation: Boolean get() = locationSource == SOURCE_DEVICE && lat != null && lng != null
    companion object { const val SOURCE_DEVICE = "device" }
}

/** syncState is SyncState.name and status is OrderStatus.name (strings keep Room free of converters). */
@Entity(tableName = "orders", indices = [Index("customerClientUuid"), Index("syncState"), Index("orderedAt")])
data class OrderEntity(
    @PrimaryKey val clientUuid: String,
    val serverId: Int?,
    val customerClientUuid: String,
    val storeName: String,
    val status: String,
    val syncState: String,
    val syncError: String?,
    val attempts: Int,
    val orderedAt: Long,
    val total: Long,
    val notes: String?,
    val deliveredAt: Long?,
    val cancelledReason: String?,
    val agentName: String?,
    val updatedAt: Long,
)

@Entity(
    tableName = "order_lines",
    foreignKeys = [ForeignKey(OrderEntity::class, ["clientUuid"], ["orderClientUuid"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("orderClientUuid")],
)
data class OrderLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val orderClientUuid: String,
    val itemId: Int,
    val name: String,
    val unit: String?,
    val qty: Int,
    val unitPrice: Long,
    val lineTotal: Long,
    val deliveredQty: Int?,
    /** JSON list of price parts for the ordered qty / the delivered qty (PartsJson); null for legacy lines. */
    val breakdown: String? = null,
    val deliveredBreakdown: String? = null,
)

@Entity(tableName = "sync_meta")
data class SyncMetaEntity(
    @PrimaryKey val id: Int = 1,
    /** serverTime from the last successful pull, passed back as `since`. */
    val since: String? = null,
    val lastSyncedAt: Long? = null,
    val lastError: String? = null,
)

data class OrderWithLines(
    @Embedded val order: OrderEntity,
    @Relation(parentColumn = "clientUuid", entityColumn = "orderClientUuid") val lines: List<OrderLineEntity>,
)

/** GPS fixes recorded during work hours, waiting to be uploaded (docs/tracking-api.md). recordedAt is UNIQUE so a replayed fix is ignored. */
@Entity(
    tableName = "location_points",
    indices = [Index(value = ["recordedAt"], unique = true), Index("uploaded")],
)
data class LocationPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordedAt: Long, // epoch millis
    val lat: Double,
    val lng: Double,
    val accuracy: Double?,
    val speed: Double?,
    val bearing: Double?,
    val battery: Int?,
    val uploaded: Boolean = false,
)

/** Single row (id = 1): the work schedule, the consent state and tracking diagnostics. */
@Entity(tableName = "tracking_meta")
data class TrackingMetaEntity(
    @PrimaryKey val id: Int = 1,
    /** Comma-separated ISO weekdays, e.g. "1,2,3,4,5,6"; null until the first pull (default schedule applies). */
    val scheduleDays: String? = null,
    val scheduleStart: String? = null,
    val scheduleEnd: String? = null,
    val consentAt: Long? = null,
    /** False while the consent was given offline and POST /api/tracking/consent is still owed. */
    val consentSynced: Boolean = false,
    val lastFixAt: Long? = null,
    val lastUploadAt: Long? = null,
    val lastError: String? = null,
)
