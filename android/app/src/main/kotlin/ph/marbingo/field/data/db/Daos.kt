package ph.marbingo.field.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {
    @Query(
        "SELECT * FROM items WHERE name LIKE '%' || :q || '%' OR sku LIKE '%' || :q || '%' OR category LIKE '%' || :q || '%' " +
            "ORDER BY name COLLATE NOCASE LIMIT 300"
    )
    fun search(q: String): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE sku = :sku LIMIT 1") suspend fun bySku(sku: String): ItemEntity?
    @Query("SELECT * FROM items WHERE id = :id") suspend fun byId(id: Int): ItemEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAll(items: List<ItemEntity>)
}

@Dao
interface CustomerDao {
    @Query(
        "SELECT * FROM customers WHERE active = 1 AND (name LIKE '%' || :q || '%' OR ownerName LIKE '%' || :q || '%' " +
            "OR phone LIKE '%' || :q || '%' OR address LIKE '%' || :q || '%') ORDER BY name COLLATE NOCASE"
    )
    fun search(q: String): Flow<List<CustomerEntity>>

    @Query("SELECT * FROM customers WHERE clientUuid = :uuid") fun observe(uuid: String): Flow<CustomerEntity?>
    @Query("SELECT * FROM customers WHERE clientUuid = :uuid") suspend fun get(uuid: String): CustomerEntity?
    @Query("SELECT * FROM customers WHERE dirty = 1") suspend fun dirty(): List<CustomerEntity>
    @Query("SELECT COUNT(*) FROM customers WHERE dirty = 1") fun dirtyCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM customers WHERE dirty = 1") suspend fun dirtyCountNow(): Int
    @Query("UPDATE customers SET serverId = :serverId, dirty = 0 WHERE clientUuid = :uuid")
    suspend fun markSynced(uuid: String, serverId: Int)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(c: CustomerEntity)
    @Query("UPDATE customers SET lat = :lat, lng = :lng, locationAccuracy = :accuracy, locationSource = 'device', dirty = 1, updatedAt = :now WHERE clientUuid = :uuid")
    suspend fun setDeviceLocation(uuid: String, lat: Double, lng: Double, accuracy: Double?, now: Long)
}

@Dao
interface OrderDao {
    @Transaction @Query("SELECT * FROM orders ORDER BY orderedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<OrderWithLines>>

    @Transaction @Query("SELECT * FROM orders WHERE customerClientUuid = :uuid ORDER BY orderedAt DESC")
    fun forStore(uuid: String): Flow<List<OrderWithLines>>

    @Transaction @Query("SELECT * FROM orders WHERE clientUuid = :uuid")
    fun observe(uuid: String): Flow<OrderWithLines?>

    @Transaction @Query("SELECT * FROM orders WHERE syncState IN ('QUEUED','ERROR') ORDER BY orderedAt")
    suspend fun pendingForPush(): List<OrderWithLines>

    @Query("SELECT * FROM orders WHERE clientUuid = :uuid") suspend fun get(uuid: String): OrderEntity?
    @Query("SELECT COUNT(*) FROM orders WHERE syncState IN ('QUEUED','ERROR')") fun waitingCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM orders WHERE syncState IN ('QUEUED','ERROR')") suspend fun waitingCountNow(): Int

    @Query("UPDATE orders SET syncState = 'ERROR', syncError = :error, attempts = attempts + 1 WHERE clientUuid = :uuid")
    suspend fun markError(uuid: String, error: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(o: OrderEntity)
    @Insert suspend fun insertLines(lines: List<OrderLineEntity>)
    @Query("DELETE FROM order_lines WHERE orderClientUuid = :uuid") suspend fun deleteLines(uuid: String)
    @Query("DELETE FROM orders WHERE clientUuid = :uuid") suspend fun delete(uuid: String)

    /** Replaces an order and its lines. Call inside a transaction. */
    @Transaction
    suspend fun replace(o: OrderEntity, lines: List<OrderLineEntity>) {
        deleteLines(o.clientUuid)
        upsert(o)
        insertLines(lines)
    }
}

@Dao
interface SyncMetaDao {
    @Query("SELECT * FROM sync_meta WHERE id = 1") fun observe(): Flow<SyncMetaEntity?>
    @Query("SELECT * FROM sync_meta WHERE id = 1") suspend fun get(): SyncMetaEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(m: SyncMetaEntity)
}

@Dao
interface LocationPointDao {
    /** Returns -1 when a fix with the same recordedAt is already stored. */
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(p: LocationPointEntity): Long
    @Query("SELECT * FROM location_points WHERE uploaded = 0 ORDER BY recordedAt LIMIT :limit")
    suspend fun pending(limit: Int): List<LocationPointEntity>
    @Query("SELECT COUNT(*) FROM location_points WHERE uploaded = 0") fun pendingCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM location_points WHERE uploaded = 0") suspend fun pendingCountNow(): Int
    @Query("UPDATE location_points SET uploaded = 1 WHERE id IN (:ids)") suspend fun markUploaded(ids: List<Long>)
    @Query("DELETE FROM location_points WHERE uploaded = 1 AND recordedAt < :before") suspend fun pruneUploaded(before: Long): Int
    @Query("SELECT COUNT(*) FROM location_points") suspend fun total(): Int
}

@Dao
interface TrackingMetaDao {
    @Query("SELECT * FROM tracking_meta WHERE id = 1") fun observe(): Flow<TrackingMetaEntity?>
    @Query("SELECT * FROM tracking_meta WHERE id = 1") suspend fun get(): TrackingMetaEntity?
    @Query("INSERT OR IGNORE INTO tracking_meta (id, consentSynced) VALUES (1, 0)") suspend fun ensureRow()

    @Query("UPDATE tracking_meta SET scheduleDays = :days, scheduleStart = :start, scheduleEnd = :end WHERE id = 1")
    suspend fun updateSchedule(days: String, start: String, end: String)
    @Query("UPDATE tracking_meta SET consentAt = :at, consentSynced = :synced WHERE id = 1")
    suspend fun updateConsent(at: Long?, synced: Boolean)
    @Query("UPDATE tracking_meta SET consentSynced = 1 WHERE id = 1") suspend fun markConsentSynced()
    @Query("UPDATE tracking_meta SET lastFixAt = :at WHERE id = 1") suspend fun updateLastFix(at: Long)
    @Query("UPDATE tracking_meta SET lastUploadAt = :at, lastError = NULL WHERE id = 1") suspend fun updateLastUpload(at: Long)
    @Query("UPDATE tracking_meta SET lastError = :error WHERE id = 1") suspend fun updateError(error: String?)

    suspend fun saveSchedule(days: String, start: String, end: String) { ensureRow(); updateSchedule(days, start, end) }
    suspend fun saveConsent(at: Long?, synced: Boolean) { ensureRow(); updateConsent(at, synced) }
    suspend fun saveLastFix(at: Long) { ensureRow(); updateLastFix(at) }
    suspend fun saveLastUpload(at: Long) { ensureRow(); updateLastUpload(at) }
    suspend fun saveError(error: String?) { ensureRow(); updateError(error) }
}
