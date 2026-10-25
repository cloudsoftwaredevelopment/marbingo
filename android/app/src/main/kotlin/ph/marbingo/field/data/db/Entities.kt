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
    val wholesalePrice: Long?,
    val fieldPrice: Long,
    val qtyOnHand: Int,
    val imageUrl: String?,
    val updatedAt: String,
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
)

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
