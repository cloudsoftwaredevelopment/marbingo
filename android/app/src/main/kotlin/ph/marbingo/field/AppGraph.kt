package ph.marbingo.field

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ph.marbingo.core.net.ApiClient
import ph.marbingo.field.data.*
import ph.marbingo.field.data.db.AppDatabase
import ph.marbingo.field.sync.SyncEngine
import ph.marbingo.field.sync.SyncScheduler
import ph.marbingo.field.sync.SyncTrigger
import ph.marbingo.field.tracking.*
import ph.marbingo.field.update.AndroidUpdatePlatform
import ph.marbingo.field.update.UpdateRepositoryImpl
import okhttp3.OkHttpClient
import java.io.File

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

    /** Outlives screens and services: final uploads and receiver work run here. */
    val appScope: CoroutineScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.IO) }
    internal val uploader: LocationUploader by lazy { LocationUploader(api, db) }
    internal val monitor: DeviceMonitor by lazy { DeviceMonitor(db, AndroidDeviceProbe(appContext), PrefsMonitorStore(appContext)) }

    val tracking: TrackingRepository by lazy {
        TrackingRepositoryImpl(
            db, api, tokenStore,
            AndroidPermissionChecker(appContext), AndroidServiceController(appContext), AndroidAlarmScheduler(appContext),
            monitor = monitor,
        )
    }
    val locator: DeviceLocator by lazy { FusedDeviceLocator(appContext) }

    private val authHooks = object : AuthHooks {
        override suspend fun afterSignIn() { monitor.reset(); tracking.ensureScheduled() }
        override suspend fun beforeSignOut() { uploader.uploadPending() } // best effort: the outbox is wiped next
        override suspend fun afterSignOut() { monitor.reset(); tracking.ensureScheduled() } // no session: stops the service, cancels the alarm
    }

    val auth: AuthRepository by lazy { AuthRepositoryImpl(api, tokenStore, db, syncEngine, scheduler, authHooks) }
    val catalog: CatalogRepository by lazy { CatalogRepositoryImpl(db.items()) }
    val stores: StoreRepository by lazy { StoreRepositoryImpl(db.customers(), scheduler) }
    val orders: OrderRepository by lazy { OrderRepositoryImpl(db, api, tokenStore, scheduler) }
    val truck: TruckRepository by lazy { TruckRepositoryImpl(db, tokenStore, scheduler) }
    internal val updateImpl: UpdateRepositoryImpl by lazy {
        UpdateRepositoryImpl(
            api, OkHttpClient(), BuildConfig.API_BASE_URL, File(appContext.cacheDir, "updates"),
            BuildConfig.VERSION_CODE, AndroidUpdatePlatform(appContext, BuildConfig.VERSION_CODE),
        )
    }
    val update: UpdateRepository get() = updateImpl
    val sync: SyncRepository by lazy { SyncRepositoryImpl(db, scheduler) }
    val printer: PrinterRepository by lazy { PrinterRepositoryImpl(appContext, db.customers()) }

    /** Called from Application.onCreate: check for updates, and resume periodic sync when already signed in. */
    fun onAppStart() {
        // Updates need no sign-in: drop old downloads, then look for a newer build (throttled, fails quietly).
        appScope.launch { updateImpl.cleanStale(); update.check() }
        if (!tokenStore.accessToken.isNullOrBlank()) {
            scheduler.start()
            scheduler.syncNow()
            appScope.launch { tracking.ensureScheduled() }
        }
    }
}
