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

class HomeViewModel(private val deps: UiDeps) : ViewModel() {
    val session: StateFlow<Session?> = deps.auth.session
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val sync: StateFlow<SyncStatus> = deps.sync.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SyncStatus(null, 0, 0, false, null))
    val todayOrders: StateFlow<List<Order>> = deps.orders.recent(200)
        .map { list -> list.filter { Fmt.isToday(it.orderedAt) }.sortedByDescending { it.orderedAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
                item { SyncCard(sync, onSync = vm::syncNow) }
                item {
                    PrimaryButton(
                        "New order",
                        onClick = { nav.navigate(Routes.newOrder()) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    )
                }
                item { ScreenTitle("Today's orders", Modifier.padding(top = 8.dp)) }
                if (orders.isEmpty()) {
                    item { EmptyState("No orders today yet", "Orders you take today appear here.") }
                } else {
                    items(orders, key = { it.clientUuid }) { o ->
                        OrderRow(o, onClick = { nav.navigate(Routes.order(o.clientUuid)) }, showDate = false)
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
