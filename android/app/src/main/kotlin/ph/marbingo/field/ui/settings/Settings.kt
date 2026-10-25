package ph.marbingo.field.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ph.marbingo.field.BuildConfig
import ph.marbingo.field.data.*
import ph.marbingo.field.ui.*
import ph.marbingo.field.ui.home.SyncCard
import ph.marbingo.field.ui.theme.Mb
import ph.marbingo.field.ui.theme.MbShapes
import ph.marbingo.field.ui.theme.MbType

class SettingsViewModel(private val deps: UiDeps) : ViewModel() {
    val session: StateFlow<Session?> = deps.auth.session.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val sync: StateFlow<SyncStatus> = deps.sync.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SyncStatus(null, 0, 0, false, null))

    private val _devices = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val devices: StateFlow<List<Pair<String, String>>> = _devices.asStateFlow()
    private val _selected = MutableStateFlow(deps.printer.selectedAddress)
    val selected: StateFlow<String?> = _selected.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _forceSignOutText = MutableStateFlow<String?>(null)
    /** Non-null while the "sign out anyway?" dialog is showing. */
    val forceSignOutText: StateFlow<String?> = _forceSignOutText.asStateFlow()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Call once BLUETOOTH_CONNECT is granted. */
    fun refreshDevices() {
        try {
            _devices.value = deps.printer.pairedDevices()
            _selected.value = deps.printer.selectedAddress
        } catch (e: SecurityException) {
            _messages.tryEmit("Bluetooth permission is needed to list printers")
        } catch (e: Exception) {
            _messages.tryEmit(e.message ?: "Could not list paired devices")
        }
    }

    fun selectPrinter(address: String) {
        deps.printer.selectedAddress = address
        _selected.value = address
    }

    fun testPrint() {
        val s = session.value ?: return
        if (_busy.value) return
        if (deps.printer.selectedAddress == null) { _messages.tryEmit("Select a printer first"); return }
        _busy.value = true
        viewModelScope.launch {
            try {
                deps.printer.printTest(s)
                _messages.tryEmit("Test page sent")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "Test print failed")
            } finally {
                _busy.value = false
            }
        }
    }

    fun syncNow() = deps.sync.syncNow()

    fun signOut() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                if (!deps.auth.signOut(false)) {
                    val st = deps.sync.status.first()
                    _forceSignOutText.value = refusalText(st)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "Could not sign out")
            } finally {
                _busy.value = false
            }
        }
    }

    fun forceSignOut() {
        _forceSignOutText.value = null
        viewModelScope.launch {
            try { deps.auth.signOut(true) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                _messages.tryEmit(e.message ?: "Could not sign out")
            }
        }
    }

    fun dismissForceSignOut() { _forceSignOutText.value = null }

    companion object {
        fun refusalText(st: SyncStatus): String {
            val what = when {
                st.waitingOrders > 0 && st.waitingStores > 0 ->
                    "${Fmt.plural(st.waitingOrders, "order")} and ${Fmt.plural(st.waitingStores, "store")} haven't"
                st.waitingOrders > 0 -> "${Fmt.plural(st.waitingOrders, "order")} ${if (st.waitingOrders == 1) "hasn't" else "haven't"}"
                st.waitingStores > 0 -> "${Fmt.plural(st.waitingStores, "store")} ${if (st.waitingStores == 1) "hasn't" else "haven't"}"
                else -> "Some data hasn't"
            }
            return "$what synced yet. Sign out anyway and lose them?"
        }
    }
}

@Composable
fun SettingsScreen(vm: SettingsViewModel, nav: NavController) {
    val session by vm.session.collectAsState()
    val sync by vm.sync.collectAsState()
    val devices by vm.devices.collectAsState()
    val selected by vm.selected.collectAsState()
    val busy by vm.busy.collectAsState()
    val forceText by vm.forceSignOutText.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var btGranted by remember { mutableStateOf(false) }
    val requestBt = rememberBluetoothPermission { granted ->
        btGranted = granted
        if (granted) vm.refreshDevices()
    }
    val requestBtForTest = rememberBluetoothPermission { granted -> if (granted) vm.testPrint() }

    LaunchedEffect(Unit) { requestBt() }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it) } }

    MainScaffold(nav, Routes.SETTINGS, "Settings", snackbarHost = { SnackbarHost(snackbar) }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            session?.let { s ->
                MbCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ShopMark(s.shopMark, 48.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium)
                            Text(s.shopName, style = MaterialTheme.typography.bodyMedium, color = Mb.Muted)
                        }
                        Chip(s.role.lowercase().replaceFirstChar { it.uppercase() }, Tone.Good)
                    }
                }
            }

            ScreenTitle("Printer")
            MbCard(Modifier.fillMaxWidth()) {
                Text(
                    "Pair the printer in Android Bluetooth settings first.",
                    style = MaterialTheme.typography.bodyMedium, color = Mb.Muted,
                )
                Spacer(Modifier.height(8.dp))
                if (devices.isEmpty()) {
                    Text(
                        if (btGranted) "No paired Bluetooth devices found." else "Allow Bluetooth access to see paired printers.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Column(Modifier.selectableGroup()) {
                    devices.forEach { (name, address) ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .selectable(selected = address == selected, onClick = { vm.selectPrinter(address) }, role = Role.RadioButton),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = address == selected, onClick = null,
                                colors = RadioButtonDefaults.colors(selectedColor = Mb.Accent),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(name, style = MaterialTheme.typography.titleSmall)
                                Text(address, style = MbType.monoSmall, color = Mb.Muted)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Refresh list", { requestBt() })
                    PrimaryButton(
                        "Test print", { requestBtForTest() },
                        enabled = selected != null && !busy,
                        icon = { Icon(Icons.Filled.Print, contentDescription = null) },
                    )
                }
            }

            ScreenTitle("Sync")
            SyncCard(sync, onSync = vm::syncNow)

            ScreenTitle("About")
            MbCard(Modifier.fillMaxWidth()) {
                Row {
                    Text("App version", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(BuildConfig.VERSION_NAME, style = MbType.mono)
                }
            }

            OutlinedButton(
                onClick = vm::signOut,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = MbShapes.Control,
                border = androidx.compose.foundation.BorderStroke(1.dp, Mb.Danger),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Mb.Danger),
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Sign out")
            }
        }
    }

    forceText?.let { text ->
        AlertDialog(
            onDismissRequest = vm::dismissForceSignOut,
            containerColor = Mb.Surface,
            title = { Text("Sign out?") },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = vm::forceSignOut, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Sign out anyway", color = Mb.Danger)
                }
            },
            dismissButton = { TextButton(onClick = vm::dismissForceSignOut, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stay signed in") } },
        )
    }
}

