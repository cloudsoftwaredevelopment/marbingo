package ph.marbingo.field.ui.stores

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import ph.marbingo.field.data.*
import ph.marbingo.field.ui.*
import ph.marbingo.field.ui.theme.Mb

@OptIn(ExperimentalCoroutinesApi::class)
class StoresViewModel(private val deps: UiDeps) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    val stores: StateFlow<List<Store>> = _query.flatMapLatest { deps.stores.search(it.trim()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val creator = StoreCreator(deps.stores, viewModelScope)

    private val _showAdd = MutableStateFlow(false)
    val showAdd: StateFlow<Boolean> = _showAdd.asStateFlow()

    fun setQuery(q: String) { _query.value = q }
    fun openAdd() { creator.clearError(); _showAdd.value = true }
    fun closeAdd() { _showAdd.value = false }
    fun add(d: StoreDraft, onCreated: (String) -> Unit) = creator.create(d) { _showAdd.value = false; onCreated(it) }
}

@Composable
fun StoresScreen(vm: StoresViewModel, nav: NavController) {
    val query by vm.query.collectAsState()
    val stores by vm.stores.collectAsState()
    val showAdd by vm.showAdd.collectAsState()
    val saving by vm.creator.saving.collectAsState()
    val error by vm.creator.error.collectAsState()

    MainScaffold(
        nav, Routes.STORES, "Stores",
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = vm::openAdd,
                containerColor = Mb.Accent, contentColor = Mb.Surface,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add store") },
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            MbField(
                query, vm::setQuery, "Search stores",
                modifier = Modifier.padding(16.dp),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            )
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (stores.isEmpty()) {
                    item {
                        EmptyState(
                            if (query.isBlank()) "No stores yet" else "No stores match \"$query\"",
                            "Tap Add store to save one. It works offline.",
                        )
                    }
                }
                items(stores, key = { it.clientUuid }) { s ->
                    StoreRow(s, onClick = { nav.navigate(Routes.store(s.clientUuid)) })
                }
            }
        }
    }
    if (showAdd) {
        AddStoreDialog(
            saving, error,
            onDismiss = vm::closeAdd,
            onSave = { d -> vm.add(d) { id -> nav.navigate(Routes.store(id)) } },
        )
    }
}

class StoreDetailViewModel(deps: UiDeps, val uuid: String) : ViewModel() {
    val store: StateFlow<Store?> = deps.stores.observe(uuid).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val orders: StateFlow<List<Order>> = deps.orders.forStore(uuid)
        .map { it.sortedByDescending { o -> o.orderedAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    /** Total bought across non-cancelled orders. */
    val totalBought: StateFlow<Long> = orders.map { l -> l.filter { it.status != OrderStatus.CANCELLED }.sumOf { it.total } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
}

@Composable
fun StoreDetailScreen(vm: StoreDetailViewModel, nav: NavController) {
    val store by vm.store.collectAsState()
    val orders by vm.orders.collectAsState()
    val total by vm.totalBought.collectAsState()

    DetailScaffold(store?.name ?: "Store", onBack = { nav.popBackStack() }) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            store?.let { s ->
                item {
                    MbCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            if (s.pendingSync) Chip("Not synced yet", Tone.Warn)
                        }
                        Spacer(Modifier.height(8.dp))
                        InfoLine("Owner", s.ownerName)
                        InfoLine("Phone", s.phone)
                        InfoLine("Address", s.address)
                        InfoLine("Notes", s.notes)
                    }
                }
                item {
                    PrimaryButton(
                        "New order for this store",
                        onClick = { nav.navigate(Routes.newOrder(s.clientUuid)) },
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    )
                }
            }
            item {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Bottom) {
                    ScreenTitle("Order history", Modifier.weight(1f))
                    if (orders.isNotEmpty()) Text("Bought ${Fmt.money(total)}", style = MaterialTheme.typography.bodyMedium, color = Mb.Muted)
                }
            }
            if (orders.isEmpty()) item { EmptyState("No orders for this store yet") }
            items(orders, key = { it.clientUuid }) { o ->
                OrderRow(o, onClick = { nav.navigate(Routes.order(o.clientUuid)) })
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium, color = Mb.Muted)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
