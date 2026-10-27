package ph.marbingo.field.ui.preview

import kotlinx.coroutines.flow.*
import ph.marbingo.field.data.*
import ph.marbingo.field.tracking.TrackingRepository
import ph.marbingo.field.tracking.TrackingState
import ph.marbingo.field.ui.UiDeps
import java.util.UUID

/** In-memory repositories for previews and unit tests. */

object Sample {
    val session = Session(1, "Ramon Dela Cruz", "AGENT", "Dela Cruz Dry Goods", "DG")
    val items = listOf(
        Item(1, "Jasmine rice", "4800001", "kg", "Grains", 6200, 400, null, listOf(Pack(5, "Sack", 50, 290000))),
        Item(2, "Cooking oil 1L", "4800002", "btl", "Pantry", 10500, 12, null),
        Item(3, "Sugar 1kg", "4800003", "pack", "Pantry", 7500, 0, null),
        Item(4, "Instant noodles", null, "pc", "Snacks", 1300, 200, null, listOf(Pack(2, "Box", 24, 28000))),
    )
    val store = Store("store-1", 1, "Aling Nena Sari-Sari", "Nena Santos", "09171234567", "12 Mabini St", null, false)
}

class FakeAuth(initial: Session? = Sample.session, var refuseUnlessForced: Boolean = false) : AuthRepository {
    val sessionState = MutableStateFlow(initial)
    override val session: Flow<Session?> = sessionState
    var signInError: Exception? = null
    var forced: Boolean? = null
    override suspend fun signIn(email: String, password: String): Session {
        signInError?.let { throw it }
        return Sample.session.also { sessionState.value = it }
    }
    override suspend fun signOut(force: Boolean): Boolean {
        forced = force
        if (refuseUnlessForced && !force) return false
        sessionState.value = null
        return true
    }
}

class FakeCatalog(private val items: List<Item> = Sample.items) : CatalogRepository {
    override fun search(query: String): Flow<List<Item>> =
        flowOf(items.filter { query.isBlank() || it.name.contains(query, true) || it.sku?.contains(query) == true })
    override suspend fun findBySku(sku: String) = items.firstOrNull { it.sku == sku }
    override suspend fun get(id: Int) = items.firstOrNull { it.id == id }
}

class FakeStores(initial: List<Store> = listOf(Sample.store)) : StoreRepository {
    val all = MutableStateFlow(initial)
    override fun search(query: String): Flow<List<Store>> =
        all.map { l -> l.filter { query.isBlank() || it.name.contains(query, true) || it.ownerName?.contains(query, true) == true } }
    override fun observe(clientUuid: String): Flow<Store?> = all.map { l -> l.firstOrNull { it.clientUuid == clientUuid } }
    override suspend fun create(
        name: String, ownerName: String?, phone: String?, address: String?, notes: String?,
        lat: Double?, lng: Double?, locationAccuracy: Double?,
    ): String {
        val id = UUID.randomUUID().toString()
        all.value = all.value + Store(id, null, name, ownerName, phone, address, notes, true, lat, lng, locationAccuracy)
        return id
    }
    override suspend fun setLocation(clientUuid: String, fix: GeoFix) {
        all.value = all.value.map { if (it.clientUuid == clientUuid) it.copy(lat = fix.lat, lng = fix.lng, locationAccuracy = fix.accuracy, pendingSync = true) else it }
    }
}

class FakeOrders(private val stores: FakeStores = FakeStores()) : OrderRepository {
    val all = MutableStateFlow<List<Order>>(emptyList())
    var cancelError: Exception? = null
    override fun recent(limit: Int): Flow<List<Order>> = all
    override fun forStore(storeClientUuid: String) = all.map { l -> l.filter { it.storeClientUuid == storeClientUuid } }
    override fun observe(clientUuid: String) = all.map { l -> l.firstOrNull { it.clientUuid == clientUuid } }
    override suspend fun place(storeClientUuid: String, lines: List<CartLine>, notes: String?): String {
        val id = UUID.randomUUID().toString()
        val ls = lines.map { OrderLine(it.item.id, it.item.name, it.item.unit, it.qty, it.item.sellingPrice, it.total, null, it.priced.parts) }
        val storeName = stores.all.value.firstOrNull { it.clientUuid == storeClientUuid }?.name ?: "?"
        all.value = listOf(
            Order(id, null, storeClientUuid, storeName, Sample.session.name, System.currentTimeMillis(), OrderStatus.PENDING,
                SyncState.QUEUED, null, notes, ls.sumOf { it.lineTotal }, ls, null, null),
        ) + all.value
        return id
    }
    override suspend fun cancel(clientUuid: String, reason: String) {
        cancelError?.let { throw it }
        all.value = all.value.map { if (it.clientUuid == clientUuid) it.copy(status = OrderStatus.CANCELLED, cancelledReason = reason) else it }
    }
}

class FakeTruck(
    initial: TruckInfo? = null,
    stock: List<TruckStockLine> = emptyList(),
    private val catalog: List<Item> = Sample.items,
) : TruckRepository {
    val truck = MutableStateFlow(initial)
    val stockLines = MutableStateFlow(stock)
    val sales = MutableStateFlow<List<Order>>(emptyList())
    var placeError: Exception? = null
    override fun observeTruck(): Flow<TruckInfo?> = truck
    override fun observeAvailable(): Flow<Map<Int, Int>> = stockLines.map { l -> l.associate { it.itemId to it.available } }
    override fun observeStock(): Flow<List<TruckStockLine>> = stockLines
    override fun searchAvailable(query: String): Flow<List<Item>> = stockLines.map { l ->
        l.mapNotNull { sl -> catalog.firstOrNull { it.id == sl.itemId }?.copy(qtyOnHand = sl.available) }.sortedBy { it.qtyOnHand <= 0 }
            .filter { query.isBlank() || it.name.contains(query, true) }
    }
    override fun recent(limit: Int): Flow<List<Order>> = sales
    override fun observe(clientUuid: String): Flow<Order?> = sales.map { l -> l.firstOrNull { it.clientUuid == clientUuid } }
    override suspend fun placeSale(storeClientUuid: String?, lines: List<CartLine>): String {
        placeError?.let { throw it }
        val id = UUID.randomUUID().toString()
        val ls = lines.map { OrderLine(it.item.id, it.item.name, it.item.unit, it.qty, it.item.sellingPrice, it.total, null, it.priced.parts) }
        sales.value = listOf(
            Order(id, null, storeClientUuid.orEmpty(), WALK_IN, Sample.session.name, System.currentTimeMillis(), OrderStatus.DELIVERED,
                SyncState.QUEUED, null, null, ls.sumOf { it.lineTotal }, ls, null, null, isSale = true),
        ) + sales.value
        return id
    }
}

class FakeSync(initial: SyncStatus = SyncStatus(null, 0, 0, false, null)) : SyncRepository {
    val state = MutableStateFlow(initial)
    var syncCalls = 0
    override val status: Flow<SyncStatus> = state
    override fun syncNow() { syncCalls++ }
}

class FakePrinter : PrinterRepository {
    var devices = listOf("RPP02N" to "AA:BB:CC:DD:EE:FF", "MTP-II" to "11:22:33:44:55:66")
    override fun pairedDevices() = devices
    override var selectedAddress: String? = null
    var printed = 0
    var error: Exception? = null
    override suspend fun printOrder(order: Order, session: Session) { error?.let { throw it }; printed++ }
    override suspend fun printTest(session: Session) { error?.let { throw it }; printed++ }
}

class FakeTracking(initial: TrackingState = TrackingState.Ready) : TrackingRepository {
    val current = MutableStateFlow(initial)
    var consentCalls = 0
    var ensureCalls = 0
    var refreshCalls = 0
    override val state: Flow<TrackingState> = current
    override suspend fun giveConsent() { consentCalls++; current.value = current.value.copy(consentGiven = true) }
    override suspend fun refreshSchedule() { refreshCalls++ }
    override fun refreshPermissions() {}
    override suspend fun ensureScheduled() { ensureCalls++ }
}

class FakeLocator(var fix: GeoFix? = GeoFix(10.7202, 122.5621, 12.0), var permitted: Boolean = true) : DeviceLocator {
    var calls = 0
    override fun hasPermission() = permitted
    override suspend fun currentFix(timeoutMs: Long): GeoFix? { calls++; return if (permitted) fix else null }
}

class FakeDeps(
    override val auth: FakeAuth = FakeAuth(),
    override val catalog: FakeCatalog = FakeCatalog(),
    override val stores: FakeStores = FakeStores(),
    override val orders: FakeOrders = FakeOrders(stores),
    override val truck: FakeTruck = FakeTruck(),
    override val sync: FakeSync = FakeSync(),
    override val printer: FakePrinter = FakePrinter(),
    override val tracking: FakeTracking = FakeTracking(),
    override val locator: FakeLocator = FakeLocator(),
) : UiDeps
