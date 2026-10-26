package ph.marbingo.field.ui.orders

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ph.marbingo.field.data.*
import ph.marbingo.field.ui.*
import ph.marbingo.field.ui.theme.Mb
import ph.marbingo.field.ui.theme.MbType

enum class OrderFilter(val label: String) {
    ALL("All"), WAITING("Waiting to sync"), PENDING("Pending"), DELIVERED("Delivered"), CANCELLED("Cancelled");

    fun matches(o: Order) = when (this) {
        ALL -> true
        WAITING -> o.syncState != SyncState.SYNCED
        PENDING -> o.status == OrderStatus.PENDING
        DELIVERED -> o.status == OrderStatus.DELIVERED
        CANCELLED -> o.status == OrderStatus.CANCELLED
    }
}

class OrdersViewModel(deps: UiDeps) : ViewModel() {
    private val _filter = MutableStateFlow(OrderFilter.ALL)
    val filter: StateFlow<OrderFilter> = _filter.asStateFlow()
    private val all = deps.orders.recent(200)
    val orders: StateFlow<List<Order>> = combine(all, _filter) { l, f -> l.filter(f::matches).sortedByDescending { it.orderedAt } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun setFilter(f: OrderFilter) { _filter.value = f }
}

@Composable
fun OrdersScreen(vm: OrdersViewModel, nav: NavController) {
    val filter by vm.filter.collectAsState()
    val orders by vm.orders.collectAsState()
    MainScaffold(nav, Routes.ORDERS, "Orders") {
        Column(Modifier.fillMaxSize()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(OrderFilter.entries) { f ->
                    FilterChip(
                        selected = f == filter,
                        onClick = { vm.setFilter(f) },
                        label = { Text(f.label) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Mb.Accent, selectedLabelColor = Mb.Surface,
                            containerColor = Mb.Surface, labelColor = Mb.Ink,
                        ),
                    )
                }
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (orders.isEmpty()) item { EmptyState("No orders here", "Orders you take show up in this list.") }
                items(orders, key = { it.clientUuid }) { o -> OrderRow(o, onClick = { nav.navigate(Routes.order(o.clientUuid)) }) }
            }
        }
    }
}

class OrderDetailViewModel(private val deps: UiDeps, val uuid: String) : ViewModel() {
    val order: StateFlow<Order?> = deps.orders.observe(uuid).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val _cancelling = MutableStateFlow(false)
    val cancelling: StateFlow<Boolean> = _cancelling.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun cancel(reason: String) {
        val r = reason.trim()
        if (r.isEmpty() || _cancelling.value) return
        _cancelling.value = true
        viewModelScope.launch {
            try {
                deps.orders.cancel(uuid, r)
                _messages.tryEmit("Order cancelled")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "Could not cancel the order")
            } finally {
                _cancelling.value = false
            }
        }
    }
}

@Composable
fun OrderDetailScreen(vm: OrderDetailViewModel, nav: NavController) {
    val order by vm.order.collectAsState()
    val cancelling by vm.cancelling.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var askCancel by remember { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    DetailScaffold(order?.reference?.let { "Order $it" } ?: "Order", onBack = { nav.popBackStack() }, snackbarHost = { SnackbarHost(snackbar) }) {
        val o = order
        if (o == null) {
            EmptyState("Order not found")
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MbCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(o.storeName, style = MaterialTheme.typography.titleLarge)
                            Text(Fmt.dateTime(o.orderedAt), style = MbType.monoSmall, color = Mb.Muted)
                            o.agentName?.let { Text("Agent: $it", style = MaterialTheme.typography.bodySmall, color = Mb.Muted) }
                        }
                        OrderChips(o)
                    }
                    if (o.syncState == SyncState.ERROR && !o.syncError.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        ErrorBanner(o.syncError)
                    }
                }
                MbCard(Modifier.fillMaxWidth()) {
                    val delivered = o.status == OrderStatus.DELIVERED
                    o.lines.forEachIndexed { i, l ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Mb.LineSoft)
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text(l.name, style = MaterialTheme.typography.titleSmall)
                                val unit = l.unit?.let { " $it" } ?: ""
                                val dParts = l.deliveredBreakdown?.takeIf { delivered && l.deliveredQty != null }
                                if (delivered && l.deliveredQty != null) {
                                    val short = l.deliveredQty < l.qty
                                    Text(
                                        "Ordered ${l.qty}$unit · Delivered ${l.deliveredQty}$unit",
                                        style = MbType.monoSmall, color = if (short) Mb.Warn else Mb.Muted,
                                    )
                                } else {
                                    Text("${l.qty}$unit", style = MbType.monoSmall, color = Mb.Muted)
                                }
                                // Delivered orders show how the delivered quantity was priced; otherwise the ordered quantity.
                                val parts = dParts ?: l.breakdown
                                if (parts != null) Text(Fmt.breakdown(parts, l.unit), style = MbType.monoSmall, color = Mb.Muted)
                                else Text("× ${Fmt.money(l.unitPrice)}", style = MbType.monoSmall, color = Mb.Muted)
                            }
                            Text(Fmt.money(l.deliveredBreakdown?.takeIf { delivered }?.sumOf { it.amount } ?: l.lineTotal), style = MbType.mono)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Mb.Line)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Total", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text(Fmt.money(o.total), style = MbType.bigNumber)
                    }
                }
                if (!o.notes.isNullOrBlank()) {
                    MbCard(Modifier.fillMaxWidth()) {
                        Text("Notes", style = MaterialTheme.typography.bodySmall, color = Mb.Muted)
                        Text(o.notes)
                    }
                }
                MbCard(Modifier.fillMaxWidth()) {
                    Text("Status", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    orderTimeline(o).forEach { step ->
                        Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.padding(top = 5.dp)) {
                                Dot(
                                    if (!step.done && step.tone == Tone.Neutral) Mb.InputBorder else when (step.tone) {
                                        Tone.Good -> Mb.Accent
                                        Tone.Warn -> Mb.Warn
                                        Tone.Danger -> Mb.Danger
                                        Tone.Neutral -> Mb.Muted
                                    },
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(step.label, style = MaterialTheme.typography.titleSmall)
                                step.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Mb.Muted) }
                            }
                        }
                    }
                }
                PrimaryButton("View slip", { nav.navigate(Routes.receipt(o.clientUuid)) }, Modifier.fillMaxWidth())
                if (o.status == OrderStatus.PENDING) {
                    OutlinedButton(
                        onClick = { askCancel = true },
                        enabled = !cancelling,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = ph.marbingo.field.ui.theme.MbShapes.Control,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Mb.Danger),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Mb.Danger),
                    ) { Text("Cancel order") }
                }
            }
        }
    }

    if (askCancel) {
        var reason by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { askCancel = false },
            containerColor = Mb.Surface,
            title = { Text("Cancel this order?") },
            text = {
                MbField(reason, { reason = it }, "Reason", singleLine = false, minLines = 2)
            },
            confirmButton = {
                TextButton(
                    onClick = { askCancel = false; vm.cancel(reason) },
                    enabled = reason.isNotBlank(),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text("Cancel order", color = if (reason.isNotBlank()) Mb.Danger else Mb.Muted) }
            },
            dismissButton = { TextButton(onClick = { askCancel = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep order") } },
        )
    }
}
