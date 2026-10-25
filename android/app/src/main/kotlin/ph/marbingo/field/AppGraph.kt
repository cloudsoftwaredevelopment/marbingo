package ph.marbingo.field

import android.content.Context
import ph.marbingo.field.data.*

/**
 * Manual DI: builds the singletons lazily. The UI only sees the repository interfaces.
 * Implemented by the data layer (Agent C).
 */
class AppGraph(private val context: Context) {
    val auth: AuthRepository get() = TODO("data layer")
    val catalog: CatalogRepository get() = TODO("data layer")
    val stores: StoreRepository get() = TODO("data layer")
    val orders: OrderRepository get() = TODO("data layer")
    val sync: SyncRepository get() = TODO("data layer")
    val printer: PrinterRepository get() = TODO("data layer")

    /** Called from Application.onCreate — schedule periodic sync etc. */
    fun onAppStart() {}
}
