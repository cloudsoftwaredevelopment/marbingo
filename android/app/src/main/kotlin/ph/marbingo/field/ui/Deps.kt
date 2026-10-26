package ph.marbingo.field.ui

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import ph.marbingo.field.MarbingoApp
import ph.marbingo.field.data.*
import ph.marbingo.field.tracking.TrackingRepository

/** What the UI needs from the data layer. Production: AppGraph. Tests and previews: fakes. */
interface UiDeps {
    val auth: AuthRepository
    val catalog: CatalogRepository
    val stores: StoreRepository
    val orders: OrderRepository
    val sync: SyncRepository
    val printer: PrinterRepository
    val tracking: TrackingRepository
    val locator: DeviceLocator
}

class GraphDeps(context: Context) : UiDeps {
    private val app = context.applicationContext
    private val graph get() = MarbingoApp.graph(app)
    override val auth get() = graph.auth
    override val catalog get() = graph.catalog
    override val stores get() = graph.stores
    override val orders get() = graph.orders
    override val sync get() = graph.sync
    override val printer get() = graph.printer
    override val tracking get() = graph.tracking
    override val locator get() = graph.locator
}

val LocalDeps = staticCompositionLocalOf<UiDeps> { error("UiDeps not provided") }

@Suppress("UNCHECKED_CAST")
fun <T : ViewModel> vmFactory(create: () -> T): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    override fun <V : ViewModel> create(modelClass: Class<V>): V = create() as V
}
