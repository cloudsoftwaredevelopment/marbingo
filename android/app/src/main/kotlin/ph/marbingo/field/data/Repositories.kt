package ph.marbingo.field.data

import kotlinx.coroutines.flow.Flow

// Contracts between the data layer (Agent C) and the UI (Agent D).
// Everything reads from the local Room cache so the app works offline.

interface AuthRepository {
    val session: Flow<Session?>
    /** Online sign-in. Only AGENT and MANAGER may use the app. Throws with a user-facing message. */
    suspend fun signIn(email: String, password: String): Session
    /** Fails (returns false) while unsynced orders/stores exist, unless force = true. */
    suspend fun signOut(force: Boolean = false): Boolean
}

interface CatalogRepository {
    fun search(query: String): Flow<List<Item>>
    suspend fun findBySku(sku: String): Item?
    suspend fun get(id: Int): Item?
}

interface StoreRepository {
    fun search(query: String): Flow<List<Store>>
    fun observe(clientUuid: String): Flow<Store?>
    /** Saves locally (queued for sync) and returns the new store's clientUuid. */
    suspend fun create(
        name: String, ownerName: String?, phone: String?, address: String?, notes: String?,
        lat: Double? = null, lng: Double? = null, locationAccuracy: Double? = null,
    ): String
    /** Pins the store at a fix taken on this device; queued for sync. */
    suspend fun setLocation(clientUuid: String, fix: GeoFix)
}

interface DeviceLocator {
    /** True when precise location permission is held. */
    fun hasPermission(): Boolean
    /** One-shot high-accuracy fix, or null when none arrives within [timeoutMs] (or permission is missing). */
    suspend fun currentFix(timeoutMs: Long = 20_000): GeoFix?
}

interface OrderRepository {
    fun recent(limit: Int = 100): Flow<List<Order>>
    fun forStore(storeClientUuid: String): Flow<List<Order>>
    fun observe(clientUuid: String): Flow<Order?>
    /** Prices lines at Item.fieldPrice, saves locally as QUEUED, schedules a sync, returns clientUuid. */
    suspend fun place(storeClientUuid: String, lines: List<CartLine>, notes: String?): String
    /** Cancels a pending order (online if synced; a queued order is just removed). */
    suspend fun cancel(clientUuid: String, reason: String)
}

interface SyncRepository {
    val status: Flow<SyncStatus>
    /** Enqueue an immediate one-off sync (WorkManager). */
    fun syncNow()
}

interface PrinterRepository {
    /** Paired Bluetooth devices: (name, mac address). Requires BLUETOOTH_CONNECT on API 31+. */
    fun pairedDevices(): List<Pair<String, String>>
    var selectedAddress: String?
    /** Sends the ESC/POS receipt for the order to the selected printer. Throws with a user-facing message. */
    suspend fun printOrder(order: Order, session: Session)
    suspend fun printTest(session: Session)
}
