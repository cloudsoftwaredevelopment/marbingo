package ph.marbingo.field.ui.stores

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
                    StoreRow(s, onClick = { nav.navigate(Routes.store(s.clientUuid)) }, showLocationChip = true)
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

class StoreDetailViewModel(private val deps: UiDeps, val uuid: String) : ViewModel() {
    private val _locating = MutableStateFlow(false)
    val locating: StateFlow<Boolean> = _locating.asStateFlow()
    /** A fresh fix waiting for the agent's confirmation. */
    private val _pendingFix = MutableStateFlow<GeoFix?>(null)
    val pendingFix: StateFlow<GeoFix?> = _pendingFix.asStateFlow()
    private val _locationError = MutableStateFlow<String?>(null)
    val locationError: StateFlow<String?> = _locationError.asStateFlow()

    /** One-shot high-accuracy fix (20 s timeout), then the screen asks to confirm. */
    fun requestFix() {
        if (_locating.value) return
        _locating.value = true
        _locationError.value = null
        viewModelScope.launch {
            try {
                val fix = deps.locator.currentFix(20_000)
                if (fix == null) _locationError.value = "Couldn't get a GPS fix. Check that location is on, or try again outdoors."
                else _pendingFix.value = fix
            } finally {
                _locating.value = false
            }
        }
    }

    fun confirmFix() {
        val fix = _pendingFix.value ?: return
        _pendingFix.value = null
        viewModelScope.launch {
            try { deps.stores.setLocation(uuid, fix) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _locationError.value = e.message ?: "Couldn't save the location."
            }
        }
    }

    fun dismissFix() { _pendingFix.value = null }
    fun locationDenied() { _locationError.value = "Location permission is needed to set the store's location." }

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
    val locating by vm.locating.collectAsState()
    val pendingFix by vm.pendingFix.collectAsState()
    val locationError by vm.locationError.collectAsState()
    val requestLocation = rememberLocationPermission { granted -> if (granted) vm.requestFix() else vm.locationDenied() }

    pendingFix?.let { ConfirmLocationDialog(it, onConfirm = vm::confirmFix, onDismiss = vm::dismissFix) }

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
                            if (!s.hasLocation) { Chip("No location", Tone.Neutral); Spacer(Modifier.width(6.dp)) }
                            if (s.pendingSync) Chip("Not synced yet", Tone.Warn)
                        }
                        Spacer(Modifier.height(8.dp))
                        InfoLine("Owner", s.ownerName)
                        InfoLine("Phone", s.phone)
                        InfoLine("Address", s.address)
                        InfoLine("Notes", s.notes)
                        if (s.hasLocation) InfoLine("Location", "Pinned" + (Fmt.accuracy(s.locationAccuracy)?.let { " ($it)" } ?: ""))
                        Spacer(Modifier.height(10.dp))
                        SecondaryButton(
                            if (locating) "Getting location…" else if (s.hasLocation) "Update location here" else "Set location here",
                            { requestLocation() },
                            Modifier.fillMaxWidth(), enabled = !locating,
                            icon = {
                                if (locating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Mb.Accent)
                                else Icon(Icons.Filled.MyLocation, contentDescription = null)
                            },
                        )
                        locationError?.let { Spacer(Modifier.height(8.dp)); ErrorBanner(it) }
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
private fun ConfirmLocationDialog(fix: GeoFix, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Mb.Surface,
        title = { Text("Set location here?") },
        text = {
            Text(
                "Use your current position${Fmt.accuracy(fix.accuracy)?.let { " ($it)" } ?: ""} as this store's location. " +
                    "Make sure you are standing at the store.",
            )
        },
        confirmButton = { PrimaryButton("Set location", onConfirm) },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

@Composable
private fun InfoLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(label, Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium, color = Mb.Muted)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
