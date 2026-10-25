package ph.marbingo.field.ui.neworder

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import ph.marbingo.field.data.*
import ph.marbingo.field.ui.*
import ph.marbingo.field.ui.stores.AddStoreDialog
import ph.marbingo.field.ui.stores.StoreRow
import ph.marbingo.field.ui.theme.Mb
import ph.marbingo.field.ui.theme.MbShapes
import ph.marbingo.field.ui.theme.MbType

@Composable
fun NewOrderScreen(vm: NewOrderViewModel, nav: NavController, entry: NavBackStackEntry, storeUuid: String?) {
    val s by vm.state.collectAsState()
    val storeQuery by vm.storeQuery.collectAsState()
    val itemQuery by vm.itemQuery.collectAsState()
    val stores by vm.stores.collectAsState()
    val items by vm.items.collectAsState()
    val showAdd by vm.showAddStore.collectAsState()
    val saving by vm.creator.saving.collectAsState()
    val storeError by vm.creator.error.collectAsState()
    var confirmLeave by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val wide = isWide()

    LaunchedEffect(storeUuid) { vm.start(storeUuid) }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(s.savedUuid) {
        s.savedUuid?.let { id -> nav.navigate(Routes.receipt(id)) { popUpTo(Routes.NEW_ORDER) { inclusive = true } } }
    }
    // Barcode from the scanner screen comes back through the saved state handle.
    LaunchedEffect(entry) {
        entry.savedStateHandle.getStateFlow<String?>(Routes.SCAN_RESULT_KEY, null).collect { code ->
            if (code != null) {
                entry.savedStateHandle[Routes.SCAN_RESULT_KEY] = null
                vm.onScanned(code)
            }
        }
    }

    fun leave() { if (s.dirty) confirmLeave = true else nav.popBackStack() }
    fun back() = when (s.step) {
        OrderStep.REVIEW -> vm.goTo(OrderStep.ITEMS)
        OrderStep.ITEMS -> vm.goTo(OrderStep.STORE)
        OrderStep.STORE -> leave()
    }
    BackHandler(enabled = !s.saving) { back() }

    DetailScaffold(
        title = "New order",
        onBack = { if (!s.saving) back() },
        maxWidth = if (s.step == OrderStep.ITEMS) 1100 else MAX_CONTENT_DP,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            when (s.step) {
                OrderStep.ITEMS -> OrderFooter(s, "Review order", enabled = s.cart.isNotEmpty(), onClick = vm::review)
                OrderStep.REVIEW -> OrderFooter(s, "Save order", enabled = !s.saving, loading = s.saving, onClick = vm::save)
                OrderStep.STORE -> {}
            }
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            StepBar(s.step, s.store)
            when (s.step) {
                OrderStep.STORE -> StoreStep(storeQuery, stores, vm::setStoreQuery, vm::selectStore, vm::openAddStore)
                OrderStep.ITEMS -> ItemsStep(
                    s, itemQuery, items, wide,
                    onQuery = vm::setItemQuery,
                    onAdd = vm::addItem,
                    onScan = { nav.navigate(Routes.SCAN) },
                    onQty = vm::setQty, onInc = vm::increment, onDec = vm::decrement, onRemove = vm::remove,
                    onNotes = vm::setNotes,
                    onChangeStore = { vm.goTo(OrderStep.STORE) },
                )
                OrderStep.REVIEW -> ReviewStep(s, onEditItems = { vm.goTo(OrderStep.ITEMS) })
            }
        }
    }

    if (showAdd) {
        AddStoreDialog(saving, storeError, onDismiss = vm::closeAddStore, onSave = vm::addStore, initialName = storeQuery)
    }
    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            containerColor = Mb.Surface,
            title = { Text("Discard this order?") },
            text = { Text("The items you added haven't been saved.") },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; nav.popBackStack() }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Discard", color = Mb.Danger)
                }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun StepBar(step: OrderStep, store: Store?) {
    val labels = listOf("1  Store", "2  Items", "3  Review")
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l ->
            val active = i == step.ordinal
            Surface(
                shape = MbShapes.Pill,
                color = if (active) Mb.Accent else Mb.LineSoft,
                contentColor = if (active) Mb.Surface else Mb.Muted,
            ) { Text(l, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium) }
        }
        if (store != null && step != OrderStep.STORE) {
            Spacer(Modifier.weight(1f))
            Text(
                store.name, style = MaterialTheme.typography.labelMedium, color = Mb.Muted,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.CenterVertically).widthIn(max = 200.dp),
            )
        }
    }
}

@Composable
private fun StoreStep(
    query: String, stores: List<Store>,
    onQuery: (String) -> Unit, onPick: (Store) -> Unit, onAdd: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MbField(
                query, onQuery, "Search stores", modifier = Modifier.weight(1f),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            )
            SecondaryButton("Add store", onAdd, icon = { Icon(Icons.Filled.Add, contentDescription = null) })
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (stores.isEmpty()) item { EmptyState("No stores found", "Add the store you are visiting.") }
            items(stores, key = { it.clientUuid }) { s -> StoreRow(s, onClick = { onPick(s) }) }
        }
    }
}

@Composable
private fun ItemsStep(
    s: NewOrderState, query: String, results: List<Item>, wide: Boolean,
    onQuery: (String) -> Unit, onAdd: (Item) -> Unit, onScan: () -> Unit,
    onQty: (Int, Int) -> Unit, onInc: (Int) -> Unit, onDec: (Int) -> Unit, onRemove: (Int) -> Unit,
    onNotes: (String) -> Unit, onChangeStore: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val searchPane: @Composable (Modifier) -> Unit = { m ->
        SearchPane(m, s, query, results, onQuery, onAdd, onScan)
    }
    val cartPane: @Composable (Modifier) -> Unit = { m ->
        CartPane(m, s, onQty, onInc, onDec, onRemove, onNotes)
    }
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            searchPane(Modifier.weight(1f).fillMaxHeight())
            VerticalDivider(color = Mb.Line)
            cartPane(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = tab, containerColor = Mb.Ground, contentColor = Mb.Accent) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Items") }
                Tab(selected = tab == 1, onClick = { tab = 1 }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Cart (${s.count})")
                }
            }
            if (tab == 0) searchPane(Modifier.weight(1f).fillMaxWidth()) else cartPane(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun SearchPane(
    modifier: Modifier, s: NewOrderState, query: String, results: List<Item>,
    onQuery: (String) -> Unit, onAdd: (Item) -> Unit, onScan: () -> Unit,
) {
    Column(modifier) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MbField(
                query, onQuery, "Search items", modifier = Modifier.weight(1f),
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            )
            SecondaryButton("Scan barcode", onScan, icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) })
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (results.isEmpty()) item { EmptyState("No items found") }
            items(results, key = { it.id }) { item ->
                ItemRow(item, inCart = s.cart.firstOrNull { it.item.id == item.id }?.qty ?: 0, onClick = { onAdd(item) })
            }
        }
    }
}

@Composable
private fun ItemRow(item: Item, inCart: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MbShapes.Card,
        color = Mb.Surface,
        border = BorderStroke(1.dp, Mb.Line),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                val sub = listOfNotNull(item.category, item.sku).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Mb.Muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Fmt.money(item.fieldPrice), style = MbType.mono.copy(fontSize = 16.sp))
                Text("≈ ${item.qtyOnHand} on hand", style = MaterialTheme.typography.bodySmall, color = Mb.Muted)
            }
            Spacer(Modifier.width(10.dp))
            if (inCart > 0) Chip("× $inCart", Tone.Good) else Icon(Icons.Filled.AddCircleOutline, contentDescription = "Add ${item.name}", tint = Mb.Accent)
        }
    }
}

@Composable
private fun CartPane(
    modifier: Modifier, s: NewOrderState,
    onQty: (Int, Int) -> Unit, onInc: (Int) -> Unit, onDec: (Int) -> Unit, onRemove: (Int) -> Unit,
    onNotes: (String) -> Unit,
) {
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ScreenTitle("Cart") }
        if (s.cart.isEmpty()) item { EmptyState("Nothing in the cart yet", "Tap an item to add it, or scan its barcode.") }
        items(s.cart, key = { it.item.id }) { line ->
            MbCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(line.item.name, style = MaterialTheme.typography.titleMedium)
                        Text("${Fmt.money(line.item.fieldPrice)} / ${line.item.unit}", style = MbType.monoSmall, color = Mb.Muted)
                    }
                    IconButton(onClick = { onRemove(line.item.id) }, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.DeleteOutline, contentDescription = "Remove ${line.item.name}", tint = Mb.Danger)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QtyStepper(line.qty, line.item.name, { onQty(line.item.id, it) }, { onDec(line.item.id) }, { onInc(line.item.id) })
                    Spacer(Modifier.weight(1f))
                    Text(Fmt.money(line.item.fieldPrice * line.qty), style = MbType.mono.copy(fontSize = 16.sp))
                }
            }
        }
        item {
            MbField(
                s.notes, onNotes, "Notes (optional)", singleLine = false, minLines = 2,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
fun QtyStepper(qty: Int, name: String, onQty: (Int) -> Unit, onDec: () -> Unit, onInc: () -> Unit) {
    // The text can be empty while typing; the cart quantity itself never drops below 1.
    var text by remember(qty) { mutableStateOf(qty.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        IconButton(onClick = onDec, enabled = qty > 1, modifier = Modifier.size(48.dp).border(1.dp, Mb.InputBorder, MbShapes.Control)) {
            Icon(Icons.Filled.Remove, contentDescription = "Decrease quantity of $name")
        }
        OutlinedTextField(
            value = text,
            onValueChange = { v ->
                val digits = v.filter { it.isDigit() }.take(5)
                text = digits
                digits.toIntOrNull()?.let { onQty(it) }
            },
            modifier = Modifier.width(84.dp).onFocusChanged { if (!it.isFocused && text.isBlank()) text = qty.toString() },
            singleLine = true,
            shape = MbShapes.Control,
            textStyle = MbType.mono.copy(fontSize = 18.sp, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = fieldColors(),
        )
        IconButton(onClick = onInc, modifier = Modifier.size(48.dp).border(1.dp, Mb.InputBorder, MbShapes.Control)) {
            Icon(Icons.Filled.Add, contentDescription = "Increase quantity of $name")
        }
    }
}

@Composable
private fun OrderFooter(s: NewOrderState, action: String, enabled: Boolean, onClick: () -> Unit, loading: Boolean = false) {
    Surface(color = Mb.Surface, border = BorderStroke(1.dp, Mb.Line), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(Fmt.plural(s.count, "item"), style = MaterialTheme.typography.bodySmall, color = Mb.Muted)
                Text(Fmt.money(s.total), style = MbType.bigNumber)
            }
            PrimaryButton(action, onClick, enabled = enabled, loading = loading)
        }
    }
}

@Composable
private fun ReviewStep(s: NewOrderState, onEditItems: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            MbCard(Modifier.fillMaxWidth()) {
                Text("Store", style = MaterialTheme.typography.bodySmall, color = Mb.Muted)
                Text(s.store?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
                s.store?.address?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Mb.Muted) }
            }
        }
        item {
            MbCard(Modifier.fillMaxWidth()) {
                s.cart.forEachIndexed { i, l ->
                    if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Mb.LineSoft)
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(l.item.name, style = MaterialTheme.typography.titleSmall)
                            Text("${l.qty} × ${Fmt.money(l.item.fieldPrice)}", style = MbType.monoSmall, color = Mb.Muted)
                        }
                        Text(Fmt.money(l.item.fieldPrice * l.qty), style = MbType.mono)
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = Mb.Line)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Total", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(Fmt.money(s.total), style = MbType.bigNumber)
                }
            }
        }
        if (s.notes.isNotBlank()) {
            item {
                MbCard(Modifier.fillMaxWidth()) {
                    Text("Notes", style = MaterialTheme.typography.bodySmall, color = Mb.Muted)
                    Text(s.notes)
                }
            }
        }
        item {
            Text(
                "Saved on this tablet first, then sent to the shop when you're online. Nothing is delivered until the shop confirms.",
                style = MaterialTheme.typography.bodySmall, color = Mb.Muted,
            )
        }
        s.error?.let { item { ErrorBanner(it) } }
        item { SecondaryButton("Edit items", onEditItems, Modifier.fillMaxWidth()) }
    }
}
