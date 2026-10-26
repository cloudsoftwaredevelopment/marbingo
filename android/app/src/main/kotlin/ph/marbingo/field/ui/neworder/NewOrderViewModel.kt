package ph.marbingo.field.ui.neworder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ph.marbingo.field.data.*
import ph.marbingo.field.ui.UiDeps
import ph.marbingo.field.ui.stores.StoreCreator
import ph.marbingo.field.ui.stores.StoreDraft

enum class OrderStep { STORE, ITEMS, REVIEW }

const val MAX_QTY = 99_999

fun clampQty(n: Int) = n.coerceIn(1, MAX_QTY)

data class NewOrderState(
    val step: OrderStep = OrderStep.STORE,
    val store: Store? = null,
    val cart: List<CartLine> = emptyList(),
    val notes: String = "",
    val saving: Boolean = false,
    val error: String? = null,
    val savedUuid: String? = null,
) {
    val total: Long get() = cart.sumOf { it.total }
    /** Units across all lines. */
    val count: Int get() = cart.sumOf { it.qty }
    val dirty: Boolean get() = cart.isNotEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
class NewOrderViewModel(private val deps: UiDeps) : ViewModel() {
    private val _state = MutableStateFlow(NewOrderState())
    val state: StateFlow<NewOrderState> = _state.asStateFlow()

    private val _storeQuery = MutableStateFlow("")
    val storeQuery: StateFlow<String> = _storeQuery.asStateFlow()
    private val _itemQuery = MutableStateFlow("")
    val itemQuery: StateFlow<String> = _itemQuery.asStateFlow()

    val stores: StateFlow<List<Store>> = _storeQuery.flatMapLatest { deps.stores.search(it.trim()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val items: StateFlow<List<Item>> = _itemQuery.flatMapLatest { deps.catalog.search(it.trim()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val creator = StoreCreator(deps.stores, viewModelScope)
    private val _showAddStore = MutableStateFlow(false)
    val showAddStore: StateFlow<Boolean> = _showAddStore.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var started = false

    /** Called once when the flow opens; a store uuid skips straight to adding items. */
    fun start(storeUuid: String?) {
        if (started) return
        started = true
        if (storeUuid == null) return
        viewModelScope.launch {
            val s = deps.stores.observe(storeUuid).first()
            if (s != null) selectStore(s)
        }
    }

    // --- steps ---
    fun setStoreQuery(q: String) { _storeQuery.value = q }
    fun setItemQuery(q: String) { _itemQuery.value = q }
    fun selectStore(s: Store) = _state.update { it.copy(store = s, step = OrderStep.ITEMS) }
    fun goTo(step: OrderStep) = _state.update { it.copy(step = step, error = null) }
    fun review() { if (_state.value.cart.isNotEmpty() && _state.value.store != null) goTo(OrderStep.REVIEW) }

    fun openAddStore() { creator.clearError(); _showAddStore.value = true }
    fun closeAddStore() { _showAddStore.value = false }
    fun addStore(d: StoreDraft) = creator.create(d) { id ->
        _showAddStore.value = false
        viewModelScope.launch { deps.stores.observe(id).first()?.let { selectStore(it) } }
    }

    // --- cart ---
    fun addItem(item: Item) = _state.update { s ->
        val existing = s.cart.indexOfFirst { it.item.id == item.id }
        val cart = if (existing >= 0) s.cart.mapIndexed { i, l -> if (i == existing) l.copy(qty = clampQty(l.qty + 1)) else l }
        else s.cart + CartLine(item, 1)
        s.copy(cart = cart)
    }

    fun setQty(itemId: Int, qty: Int) = _state.update { s ->
        s.copy(cart = s.cart.map { if (it.item.id == itemId) it.copy(qty = clampQty(qty)) else it })
    }

    /** Applies the cart line's "add N more and pay less" hint. */
    fun applyUpsell(itemId: Int) {
        val line = _state.value.cart.firstOrNull { it.item.id == itemId } ?: return
        val up = line.upsell ?: return
        setQty(itemId, line.qty + up.addQty)
    }

    fun increment(itemId: Int) = _state.value.cart.firstOrNull { it.item.id == itemId }?.let { setQty(itemId, it.qty + 1) }
    fun decrement(itemId: Int) = _state.value.cart.firstOrNull { it.item.id == itemId }?.let { setQty(itemId, it.qty - 1) }

    fun remove(itemId: Int) = _state.update { s ->
        val cart = s.cart.filterNot { it.item.id == itemId }
        s.copy(cart = cart, step = if (cart.isEmpty() && s.step == OrderStep.REVIEW) OrderStep.ITEMS else s.step)
    }

    fun setNotes(n: String) = _state.update { it.copy(notes = n) }

    fun onScanned(code: String) {
        val c = code.trim()
        if (c.isEmpty()) return
        viewModelScope.launch {
            val item = try { deps.catalog.findBySku(c) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (item != null) {
                addItem(item)
                _messages.tryEmit("Added ${item.name}")
            } else {
                _messages.tryEmit("No item with barcode $c")
            }
        }
    }

    fun save() {
        val s = _state.value
        val store = s.store ?: return
        if (s.cart.isEmpty() || s.saving || s.savedUuid != null) return
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val id = deps.orders.place(store.clientUuid, s.cart, s.notes.trim().ifEmpty { null })
                _state.update { it.copy(saving = false, savedUuid = id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.message ?: "Could not save the order.") }
            }
        }
    }
}
