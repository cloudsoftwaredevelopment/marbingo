package ph.marbingo.field.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import ph.marbingo.field.ui.theme.Mb

private data class Dest(val route: String, val label: String, val icon: ImageVector)

private val dests = listOf(
    Dest(Routes.HOME, "Home", Icons.Outlined.Home),
    Dest(Routes.newOrder(), "New order", Icons.Outlined.AddShoppingCart),
    Dest(Routes.STORES, "Stores", Icons.Outlined.Storefront),
    Dest(Routes.ORDERS, "Orders", Icons.Outlined.Receipt),
    Dest(Routes.SETTINGS, "Settings", Icons.Outlined.Settings),
)

const val MAX_CONTENT_DP = 840

@Composable
fun isWide(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

fun NavController.goTopLevel(route: String) {
    if (route == "neworder") { navigate(Routes.newOrder()); return }
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Chrome for the five top-level screens: dark top bar + rail (tablet) or bottom bar (phone). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    nav: NavController,
    current: String,
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val wide = isWide()
    Scaffold(
        modifier = modifier,
        containerColor = Mb.Ground,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Mb.Sidebar,
                    titleContentColor = Mb.Ground,
                    actionIconContentColor = Mb.Ground,
                ),
            )
        },
        bottomBar = {
            if (!wide) {
                NavigationBar(containerColor = Mb.Surface) {
                    dests.forEach { d ->
                        NavigationBarItem(
                            selected = d.route == current,
                            onClick = { nav.goTopLevel(d.route) },
                            icon = { Icon(d.icon, contentDescription = null) },
                            label = { Text(d.label, maxLines = 1) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Mb.Accent,
                                selectedTextColor = Mb.Accent,
                                indicatorColor = Mb.AccentTint,
                                unselectedIconColor = Mb.Muted,
                                unselectedTextColor = Mb.Muted,
                            ),
                        )
                    }
                }
            }
        },
        snackbarHost = snackbarHost,
        floatingActionButton = floatingActionButton,
    ) { pad ->
        Row(Modifier.padding(pad).fillMaxSize()) {
            if (wide) {
                NavigationRail(containerColor = Mb.Sidebar, contentColor = Mb.SidebarText) {
                    Spacer(Modifier.height(8.dp))
                    dests.forEach { d ->
                        NavigationRailItem(
                            selected = d.route == current,
                            onClick = { nav.goTopLevel(d.route) },
                            icon = { Icon(d.icon, contentDescription = null) },
                            label = { Text(d.label, maxLines = 1) },
                            alwaysShowLabel = true,
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = Mb.Ink,
                                selectedTextColor = Mb.Ground,
                                indicatorColor = Mb.AccentTint,
                                unselectedIconColor = Mb.SidebarText,
                                unselectedTextColor = Mb.SidebarText,
                            ),
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = MAX_CONTENT_DP.dp).fillMaxSize()) { content() }
            }
        }
    }
}

/** Dark top bar with a back arrow, for detail screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    maxWidth: Int = MAX_CONTENT_DP,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier,
        containerColor = Mb.Ground,
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Mb.Sidebar,
                    titleContentColor = Mb.Ground,
                    navigationIconContentColor = Mb.Ground,
                    actionIconContentColor = Mb.Ground,
                ),
            )
        },
        bottomBar = bottomBar,
        snackbarHost = snackbarHost,
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.widthIn(max = maxWidth.dp).fillMaxSize()) { content() }
        }
    }
}
