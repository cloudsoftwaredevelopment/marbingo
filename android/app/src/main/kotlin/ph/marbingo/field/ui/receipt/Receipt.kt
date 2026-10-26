package ph.marbingo.field.ui.receipt

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ph.marbingo.field.data.*
import ph.marbingo.field.print.ReceiptPdf
import ph.marbingo.field.ui.*
import ph.marbingo.field.ui.theme.Mb
import ph.marbingo.field.ui.theme.MbShapes
import ph.marbingo.field.ui.theme.MbType
import java.io.File

sealed interface ReceiptEvent {
    data class Message(val text: String) : ReceiptEvent
    data object NeedPrinter : ReceiptEvent
    data class Share(val file: File, val order: Order) : ReceiptEvent
}

class ReceiptViewModel(private val deps: UiDeps, val uuid: String) : ViewModel() {
    val order: StateFlow<Order?> = deps.orders.observe(uuid).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val session: StateFlow<Session?> = deps.auth.session.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _events = MutableSharedFlow<ReceiptEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ReceiptEvent> = _events.asSharedFlow()

    private suspend fun current(): Pair<Order, Session>? {
        val o = deps.orders.observe(uuid).first() ?: return null
        val s = deps.auth.session.first() ?: return null
        return o to s
    }

    /** Call after BLUETOOTH_CONNECT is granted. */
    fun print() {
        if (_busy.value) return
        if (deps.printer.selectedAddress == null) { _events.tryEmit(ReceiptEvent.NeedPrinter); return }
        _busy.value = true
        viewModelScope.launch {
            try {
                val (o, s) = current() ?: return@launch
                deps.printer.printOrder(o, s)
                _events.tryEmit(ReceiptEvent.Message("Sent to printer"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(ReceiptEvent.Message(e.message ?: "Printing failed"))
            } finally {
                _busy.value = false
            }
        }
    }

    fun sharePdf(context: Context) {
        if (_busy.value) return
        _busy.value = true
        val app = context.applicationContext
        viewModelScope.launch {
            try {
                val (o, s) = current() ?: return@launch
                val file = withContext(Dispatchers.IO) { ReceiptPdf.render(app, o, s) }
                _events.tryEmit(ReceiptEvent.Share(file, o))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(ReceiptEvent.Message(e.message ?: "Could not create the PDF"))
            } finally {
                _busy.value = false
            }
        }
    }
}

@Composable
fun ReceiptScreen(vm: ReceiptViewModel, nav: NavController) {
    val order by vm.order.collectAsState()
    val session by vm.session.collectAsState()
    val busy by vm.busy.collectAsState()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var btDenied by remember { mutableStateOf(false) }
    val requestBt = rememberBluetoothPermission { granted -> if (granted) vm.print() else btDenied = true }

    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is ReceiptEvent.Message -> snackbar.showSnackbar(e.text)
                ReceiptEvent.NeedPrinter -> {
                    snackbar.showSnackbar("Choose a printer first")
                    nav.navigate(Routes.SETTINGS)
                }
                is ReceiptEvent.Share -> ReceiptPdf.share(context, e.file, e.order)
            }
        }
    }
    LaunchedEffect(btDenied) { if (btDenied) { snackbar.showSnackbar("Bluetooth permission is needed to print"); btDenied = false } }

    fun done() { if (!nav.popBackStack(Routes.HOME, false)) nav.navigate(Routes.HOME) }

    DetailScaffold("Order slip", onBack = ::done, maxWidth = 560, snackbarHost = { SnackbarHost(snackbar) }) {
        val o = order
        if (o == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Mb.Accent) }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Slip(o, session)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    PrimaryButton(
                        "Print",
                        onClick = { requestBt() },
                        modifier = Modifier.weight(1f), enabled = !busy,
                        icon = { Icon(Icons.Filled.Print, contentDescription = null) },
                    )
                    SecondaryButton(
                        "Share PDF",
                        onClick = { vm.sharePdf(context) },
                        modifier = Modifier.weight(1f), enabled = !busy,
                        icon = { Icon(Icons.Filled.Share, contentDescription = null) },
                    )
                }
                SecondaryButton("Done", ::done, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DashedLine() {
    Canvas(Modifier.fillMaxWidth().height(1.dp).padding(vertical = 0.dp)) {
        drawLine(
            color = Mb.Muted, start = androidx.compose.ui.geometry.Offset(0f, 0f),
            end = androidx.compose.ui.geometry.Offset(size.width, 0f), strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
        )
    }
}

/** Paper-like card with the same content as the printed and PDF slip. */
@Composable
fun Slip(o: Order, session: Session?) {
    Surface(
        shape = MbShapes.Control, color = Mb.Surface, border = BorderStroke(1.dp, Mb.Line),
        shadowElevation = 2.dp, modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(session?.shopName.orEmpty(), Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Text("ORDER SLIP", Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, color = Mb.Muted)
            DashedLine()
            SlipLine("Order", o.reference, mono = true)
            SlipLine("Store", o.storeName)
            SlipLine("Agent", o.agentName ?: session?.name.orEmpty())
            SlipLine("Date", Fmt.dateTime(o.orderedAt), mono = true)
            DashedLine()
            o.lines.forEach { l ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(l.name, style = MaterialTheme.typography.titleSmall)
                    val parts = l.breakdown
                    if (parts.isNullOrEmpty()) {
                        Row {
                            Text(
                                "${l.qty}${l.unit?.let { " $it" } ?: ""} × ${Fmt.money(l.unitPrice)}",
                                Modifier.weight(1f), style = MbType.monoSmall, color = Mb.Muted,
                            )
                            Text(Fmt.money(l.lineTotal), style = MbType.mono)
                        }
                    } else {
                        parts.forEach { p ->
                            Row {
                                Text(Fmt.partLabel(p, l.unit),
                                    Modifier.weight(1f).padding(start = 8.dp), style = MbType.monoSmall, color = Mb.Muted)
                                Text(Fmt.money(p.amount), style = MbType.monoSmall)
                            }
                        }
                        if (parts.size > 1) Row {
                            Spacer(Modifier.weight(1f))
                            Text(Fmt.money(l.lineTotal), style = MbType.mono)
                        }
                    }
                }
            }
            DashedLine()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TOTAL", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(Fmt.money(o.total), style = MbType.bigNumber)
            }
            if (!o.notes.isNullOrBlank()) {
                DashedLine()
                Text("Notes: ${o.notes}", style = MaterialTheme.typography.bodyMedium)
            }
            DashedLine()
            Text("Order only — not yet delivered", Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { OrderChips(o, showSynced = true) }
        }
    }
}

@Composable
private fun SlipLine(label: String, value: String, mono: Boolean = false) {
    Row {
        Text(label, Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium, color = Mb.Muted)
        Text(value, Modifier.weight(1f), style = if (mono) MbType.mono else MaterialTheme.typography.bodyMedium)
    }
}
