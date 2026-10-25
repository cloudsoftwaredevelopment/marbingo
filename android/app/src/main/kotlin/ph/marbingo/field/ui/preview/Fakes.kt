package ph.marbingo.field.ui.preview

import kotlinx.coroutines.flow.*
import ph.marbingo.field.data.*
import ph.marbingo.field.ui.UiDeps
import java.util.UUID

/** In-memory repositories for previews and unit tests. */

object Sample {
    val session = Session(1, "Ramon Dela Cruz", "AGENT", "Dela Cruz Dry Goods", "DG")
    val items = listOf(
        Item(1, "Rice 5kg", "4800001", "bag", "Grains", 25000, 27000, 40, null),
        Item(2, "Cooking oil 1L", "4800002", "btl", "Pantry", 9500, 10500, 12, null),
        Item(3, "Sugar 1kg", "4800003", "pack", "Pantry", 6800, 7500, 0, null),
        Item(4, "Instant noodles", null, "pc", "Snacks", 1100, 1300, 200, null),
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
    override suspend fun create(name: String, ownerName: String?, phone: String?, address: String?, notes: String?): String {
        val id = UUID.randomUUID().toString()
        all.value = all.value + Store(id, null, name, ownerName, phone, address, notes, true)
        return id
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
        val ls = lines.map { OrderLine(it.item.id, it.item.name, it.item.unit, it.qty, it.item.fieldPrice, it.item.fieldPrice * it.qty, null) }
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

class FakeDeps(
    override val auth: FakeAuth = FakeAuth(),
    override val catalog: FakeCatalog = FakeCatalog(),
    override val stores: FakeStores = FakeStores(),
    override val orders: FakeOrders = FakeOrders(stores),
    override val sync: FakeSync = FakeSync(),
    override val printer: FakePrinter = FakePrinter(),
) : UiDeps
