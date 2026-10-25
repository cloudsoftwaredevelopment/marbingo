package ph.marbingo.field

import android.content.Context
import ph.marbingo.core.net.ApiClient
import ph.marbingo.field.data.*
import ph.marbingo.field.data.db.AppDatabase
import ph.marbingo.field.sync.SyncEngine
import ph.marbingo.field.sync.SyncScheduler
import ph.marbingo.field.sync.SyncTrigger

/**
 * Manual DI: builds the singletons lazily. The UI only sees the repository interfaces.
 */
class AppGraph(context: Context) {
    private val appContext = context.applicationContext

    internal val tokenStore: PrefsTokenStore by lazy { PrefsTokenStore(appContext) }
    internal val api by lazy { ApiClient.create(BuildConfig.API_BASE_URL, tokenStore) }
    internal val db: AppDatabase by lazy { AppDatabase.build(appContext) }
    internal val scheduler: SyncTrigger by lazy { SyncScheduler(appContext) }
    internal val syncEngine: SyncEngine by lazy { SyncEngine(api, db, tokenStore) }

    val auth: AuthRepository by lazy { AuthRepositoryImpl(api, tokenStore, db, syncEngine, scheduler) }
    val catalog: CatalogRepository by lazy { CatalogRepositoryImpl(db.items()) }
    val stores: StoreRepository by lazy { StoreRepositoryImpl(db.customers(), scheduler) }
    val orders: OrderRepository by lazy { OrderRepositoryImpl(db, api, tokenStore, scheduler) }
    val sync: SyncRepository by lazy { SyncRepositoryImpl(db, scheduler) }
    val printer: PrinterRepository by lazy { PrinterRepositoryImpl(appContext, db.customers()) }

    /** Called from Application.onCreate: resume periodic sync when already signed in. */
    fun onAppStart() {
        if (!tokenStore.accessToken.isNullOrBlank()) {
            scheduler.start()
            scheduler.syncNow()
        }
    }
}
