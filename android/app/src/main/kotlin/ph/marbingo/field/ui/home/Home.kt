package ph.marbingo.field.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ph.marbingo.field.data.*
import ph.marbingo.field.ui.*
import ph.marbingo.field.ui.theme.Mb
import ph.marbingo.field.tracking.TrackingState
import ph.marbingo.field.ui.tracking.TrackingStatusCard
import ph.marbingo.field.ui.truck.TruckCard
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

class HomeViewModel(private val deps: UiDeps) : ViewModel() {
    val session: StateFlow<Session?> = deps.auth.session
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val sync: StateFlow<SyncStatus> = deps.sync.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SyncStatus(null, 0, 0, false, null))
    /** Null for booking agents: the truck card and everything truck-related stay hidden. */
    val truck: StateFlow<TruckInfo?> = deps.truck.observeTruck()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val todayOrders: StateFlow<List<Order>> = combine(deps.orders.recent(200), deps.truck.recent(200)) { o, t -> o + t }
        .map { list -> list.filter { Fmt.isToday(it.orderedAt) }.sortedByDescending { it.orderedAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Null until the first emission, so the card never flashes a false "off" banner. */
    val tracking: StateFlow<TrackingState?> = deps.tracking.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** On return to the app: restart sharing if it died and re-read permissions changed in Settings. */
    fun onResume() {
        viewModelScope.launch { try { deps.tracking.ensureScheduled() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { } }
    }

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun syncNow() = deps.sync.syncNow()

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        deps.sync.syncNow()
        viewModelScope.launch {
            delay(800) // let the worker flip `running` before we look at it
            withTimeoutOrNullCompat { deps.sync.status.first { !it.running } }
            _refreshing.value = false
        }
    }

    private suspend fun <T> withTimeoutOrNullCompat(block: suspend () -> T): T? =
        kotlinx.coroutines.withTimeoutOrNull(15_000) { block() }
}

fun greeting(name: String?): String {
    val first = name?.trim()?.substringBefore(' ').orEmpty()
    return if (first.isEmpty()) "Hello" else "Hello, $first"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: HomeViewModel, nav: NavController) {
    val session by vm.session.collectAsState()
    val sync by vm.sync.collectAsState()
    val orders by vm.todayOrders.collectAsState()
    val refreshing by vm.refreshing.collectAsState()
    val tracking by vm.tracking.collectAsState()
    val truck by vm.truck.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.onResume() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    MainScaffold(nav, Routes.HOME, session?.shopName ?: "Marbingo Field") {
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = vm::refresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(greeting(session?.name), style = MaterialTheme.typography.headlineMedium)
                    Text("Take an order at a store, or check what's waiting.", style = MaterialTheme.typography.bodyMedium, color = Mb.Muted)
                }
                tracking?.let { t ->
                    item { TrackingStatusCard(t, onClick = { nav.navigate(Routes.trackingSetup()) }) }
                }
                truck?.let { t ->
                    item { TruckCard(t, onSell = { nav.navigate(Routes.TRUCK_SALE) }, onStock = { nav.navigate(Routes.TRUCK_STOCK) }) }
                }
                item { SyncCard(sync, onSync = vm::syncNow) }
                item {
                    PrimaryButton(
                        "New order",
                        onClick = { nav.navigate(Routes.newOrder()) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    )
                }
                item { ScreenTitle(if (truck != null) "Today's orders and sales" else "Today's orders", Modifier.padding(top = 8.dp)) }
                if (orders.isEmpty()) {
                    item { EmptyState("No orders today yet", "Orders you take today appear here.") }
                } else {
                    items(orders, key = { it.clientUuid }) { o ->
                        OrderRow(o, onClick = { nav.navigate(if (o.isSale) Routes.saleReceipt(o.clientUuid) else Routes.order(o.clientUuid)) }, showDate = false)
                    }
                }
            }
        }
    }
}

@Composable
fun SyncCard(status: SyncStatus, onSync: () -> Unit, modifier: Modifier = Modifier) {
    val sum = syncSummary(status)
    MbCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status.running) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Mb.Accent)
                    else Dot(
                        when (sum.tone) {
                            Tone.Good -> Mb.Accent
                            Tone.Warn -> Mb.Warn
                            Tone.Danger -> Mb.Danger
                            Tone.Neutral -> Mb.Muted
                        },
                    )
                    Text(sum.title, style = MaterialTheme.typography.titleMedium)
                }
                sum.detail?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodyMedium,
                        color = if (sum.tone == Tone.Danger) Mb.Danger else if (sum.tone == Tone.Warn) Mb.Warn else Mb.Muted,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            SecondaryButton(
                "Sync now", onSync, enabled = !status.running,
                icon = { Icon(Icons.Filled.Sync, contentDescription = null) },
            )
        }
    }
}
