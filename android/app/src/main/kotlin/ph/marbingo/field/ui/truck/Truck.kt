package ph.marbingo.field.ui.truck

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import ph.marbingo.field.data.TruckInfo
import ph.marbingo.field.data.TruckStockLine
import ph.marbingo.field.ui.*
import ph.marbingo.field.ui.theme.Mb
import ph.marbingo.field.ui.theme.MbType

/** "Waiting for manager approval" while the period is a draft or missing; otherwise the period state. */
fun periodLabel(t: TruckInfo): Pair<String, Tone> = when (t.periodStatus) {
    "open" -> "Open" to Tone.Good
    "counting" -> "Being counted" to Tone.Warn
    "draft" -> "Waiting for manager approval" to Tone.Warn
    else -> "Waiting for manager approval" to Tone.Warn
}

/** Home card for truck agents: name, plate, units on board, period status and the two truck actions. */
@Composable
fun TruckCard(truck: TruckInfo, onSell: () -> Unit, onStock: () -> Unit, modifier: Modifier = Modifier) {
    val (status, tone) = periodLabel(truck)
    MbCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocalShipping, contentDescription = null, tint = Mb.Accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(truck.name, style = MaterialTheme.typography.titleMedium)
                val sub = listOfNotNull(truck.plate, truck.driverName).joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Mb.Muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${truck.unitsOnBoard}", style = MbType.bigNumber)
                Text("units on board", style = MaterialTheme.typography.bodySmall, color = Mb.Muted)
            }
        }
        Spacer(Modifier.height(10.dp))
        Chip(status, tone)
        if (truck.waitingForApproval) {
            Text(
                "You can sell from the truck once the manager approves the starting stock.",
                style = MaterialTheme.typography.bodySmall, color = Mb.Muted, modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            PrimaryButton("Sell from truck", onSell, Modifier.weight(1f), enabled = truck.canSell,
                icon = { Icon(Icons.Filled.LocalShipping, contentDescription = null) })
            SecondaryButton("Truck stock", onStock, Modifier.weight(1f),
                icon = { Icon(Icons.Filled.Inventory2, contentDescription = null) })
        }
    }
}

class TruckStockViewModel(deps: UiDeps) : ViewModel() {
    val truck: StateFlow<TruckInfo?> = deps.truck.observeTruck().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val stock: StateFlow<List<TruckStockLine>> = deps.truck.observeStock().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@Composable
fun TruckStockScreen(vm: TruckStockViewModel, nav: NavController) {
    val truck by vm.truck.collectAsState()
    val stock by vm.stock.collectAsState()
    DetailScaffold("Truck stock", onBack = { nav.popBackStack() }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            truck?.let { t ->
                item {
                    MbCard(Modifier.fillMaxWidth()) {
                        Text(t.name, style = MaterialTheme.typography.titleMedium)
                        Text("${t.unitsOnBoard} units on board", style = MaterialTheme.typography.bodyMedium, color = Mb.Muted)
                        Text("Counts include sales waiting to sync.", style = MaterialTheme.typography.bodySmall, color = Mb.Muted)
                    }
                }
            }
            if (stock.isEmpty()) item { EmptyState("Nothing on the truck", "Stock appears here once the manager loads the truck.") }
            else item {
                MbCard(Modifier.fillMaxWidth()) {
                    stock.forEachIndexed { i, l ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 8.dp), color = Mb.LineSoft)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(l.name, style = MaterialTheme.typography.titleSmall)
                                if (l.available != l.onBoard) {
                                    Text("${l.onBoard - l.available} sold, waiting to sync", style = MaterialTheme.typography.bodySmall, color = Mb.Warn)
                                }
                            }
                            Text(
                                "${l.available}${l.unit?.let { " $it" } ?: ""}", style = MbType.mono,
                                color = if (l.available < 0) Mb.Danger else Mb.Ink,
                            )
                        }
                    }
                }
            }
        }
    }
}
