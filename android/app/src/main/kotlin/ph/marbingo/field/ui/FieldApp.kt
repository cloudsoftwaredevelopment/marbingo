package ph.marbingo.field.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.map
import ph.marbingo.field.data.Session
import ph.marbingo.field.ui.auth.SignInScreen
import ph.marbingo.field.ui.auth.SignInViewModel
import ph.marbingo.field.ui.home.HomeScreen
import ph.marbingo.field.ui.home.HomeViewModel
import ph.marbingo.field.ui.neworder.NewOrderScreen
import ph.marbingo.field.ui.neworder.NewOrderViewModel
import ph.marbingo.field.ui.orders.*
import ph.marbingo.field.ui.receipt.ReceiptScreen
import ph.marbingo.field.ui.receipt.ReceiptViewModel
import ph.marbingo.field.ui.scanner.ScannerScreen
import ph.marbingo.field.ui.settings.SettingsScreen
import ph.marbingo.field.ui.settings.SettingsViewModel
import ph.marbingo.field.ui.stores.*
import ph.marbingo.field.ui.theme.Mb
import ph.marbingo.field.ui.theme.MarbingoTheme

private sealed interface SessionState {
    data object Loading : SessionState
    data class Known(val session: Session?) : SessionState
}

@Composable
fun FieldApp(depsOverride: UiDeps? = null) {
    val context = LocalContext.current
    val deps = remember(depsOverride) { depsOverride ?: GraphDeps(context) }
    MarbingoTheme {
        CompositionLocalProvider(LocalDeps provides deps) {
            val sessionFlow = remember(deps) { deps.auth.session.map<Session?, SessionState> { SessionState.Known(it) } }
            val state by sessionFlow.collectAsState(initial = SessionState.Loading)
            when (val s = state) {
                SessionState.Loading -> Box(Modifier.fillMaxSize().background(Mb.Ground))
                is SessionState.Known -> AppNav(s.session)
            }
        }
    }
}

@Composable
private fun AppNav(session: Session?) {
    val nav = rememberNavController()
    val deps = LocalDeps.current
    val start = remember { if (session == null) Routes.SIGN_IN else Routes.HOME }
    val backEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backEntry?.destination?.route

    // Signing in or out must move the user even if the NavHost was already showing.
    LaunchedEffect(session?.userId, currentRoute) {
        if (currentRoute == null) return@LaunchedEffect
        if (session == null && currentRoute != Routes.SIGN_IN) {
            nav.navigate(Routes.SIGN_IN) { popUpTo(0) { inclusive = true }; launchSingleTop = true }
        } else if (session != null && currentRoute == Routes.SIGN_IN) {
            nav.navigate(Routes.HOME) { popUpTo(0) { inclusive = true }; launchSingleTop = true }
        }
    }

    NavHost(nav, startDestination = start) {
        composable(Routes.SIGN_IN) {
            val vm: SignInViewModel = viewModel(factory = vmFactory { SignInViewModel(deps) })
            SignInScreen(vm)
        }
        composable(Routes.HOME) {
            val vm: HomeViewModel = viewModel(factory = vmFactory { HomeViewModel(deps) })
            HomeScreen(vm, nav)
        }
        composable(Routes.STORES) {
            val vm: StoresViewModel = viewModel(factory = vmFactory { StoresViewModel(deps) })
            StoresScreen(vm, nav)
        }
        composable(Routes.ORDERS) {
            val vm: OrdersViewModel = viewModel(factory = vmFactory { OrdersViewModel(deps) })
            OrdersScreen(vm, nav)
        }
        composable(Routes.SETTINGS) {
            val vm: SettingsViewModel = viewModel(factory = vmFactory { SettingsViewModel(deps) })
            SettingsScreen(vm, nav)
        }
        composable(Routes.STORE, arguments = listOf(navArgument("uuid") { type = NavType.StringType })) { e ->
            val uuid = e.arguments?.getString("uuid").orEmpty()
            val vm: StoreDetailViewModel = viewModel(factory = vmFactory { StoreDetailViewModel(deps, uuid) })
            StoreDetailScreen(vm, nav)
        }
        composable(Routes.ORDER, arguments = listOf(navArgument("uuid") { type = NavType.StringType })) { e ->
            val uuid = e.arguments?.getString("uuid").orEmpty()
            val vm: OrderDetailViewModel = viewModel(factory = vmFactory { OrderDetailViewModel(deps, uuid) })
            OrderDetailScreen(vm, nav)
        }
        composable(Routes.RECEIPT, arguments = listOf(navArgument("uuid") { type = NavType.StringType })) { e ->
            val uuid = e.arguments?.getString("uuid").orEmpty()
            val vm: ReceiptViewModel = viewModel(factory = vmFactory { ReceiptViewModel(deps, uuid) })
            ReceiptScreen(vm, nav)
        }
        composable(
            Routes.NEW_ORDER,
            arguments = listOf(navArgument("store") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { e ->
            val storeUuid = e.arguments?.getString("store")
            // Scoped to this back stack entry: survives the trip to the scanner and back.
            val vm: NewOrderViewModel = viewModel(e, factory = vmFactory { NewOrderViewModel(deps) })
            NewOrderScreen(vm, nav, e, storeUuid)
        }
        composable(Routes.SCAN) { ScannerScreen(nav) }
    }
}
