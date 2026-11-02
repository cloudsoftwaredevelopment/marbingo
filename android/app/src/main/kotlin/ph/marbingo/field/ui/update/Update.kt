package ph.marbingo.field.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ph.marbingo.field.ui.*
import ph.marbingo.field.ui.theme.Mb
import ph.marbingo.field.update.UpdateState

/** One-line status for the Settings card. */
fun updateStatusText(state: UpdateState): String = when (state) {
    UpdateState.Idle -> "Not checked yet."
    UpdateState.UpToDate -> "You have the latest version."
    is UpdateState.Available -> "Version ${state.info.versionName} is available."
    is UpdateState.Downloading -> "Downloading… ${(state.progress * 100).toInt()}%"
    UpdateState.ReadyToInstall -> "Installing… confirm on the screen if asked."
    UpdateState.NeedsPermission -> "Allow installs from Marbingo Field to continue."
    is UpdateState.Failed -> state.message
}

/** Update banner for Home and the sign-in screen. Nothing when there is no update on offer. */
@Composable
fun UpdateBannerHost(modifier: Modifier = Modifier) {
    val update = LocalDeps.current.update
    val offer by update.offer.collectAsState()
    val state by update.state.collectAsState()
    val scope = rememberCoroutineScope()
    offer?.takeIf { !it.required }?.let { // a required update is shown by UpdateGate instead
        UpdateBanner(it, state, onUpdate = { scope.launch { update.downloadAndInstall() } }, onOpenSettings = update::openInstallSettings, modifier)
    }
}

@Composable
fun UpdateBanner(
    offer: UpdateState.Available,
    state: UpdateState,
    onUpdate: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MbCard(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = Mb.Accent)
            Text("Update available — v${offer.info.versionName}", style = MaterialTheme.typography.titleMedium)
        }
        if (offer.info.notes.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(offer.info.notes, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(6.dp))
        Text("Your queued orders are kept.", style = MaterialTheme.typography.bodySmall, color = Mb.Muted)
        Spacer(Modifier.height(10.dp))
        UpdateAction(state, onUpdate, onOpenSettings)
    }
}

/** Progress / permission prompt / error / button, depending on where the update is. */
@Composable
fun UpdateAction(state: UpdateState, onUpdate: () -> Unit, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            is UpdateState.Downloading -> {
                LinearProgressIndicator(progress = { state.progress }, Modifier.fillMaxWidth(), color = Mb.Accent, trackColor = Mb.LineSoft)
                Text("Downloading… ${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = Mb.Muted)
            }
            UpdateState.ReadyToInstall -> {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = Mb.Accent, trackColor = Mb.LineSoft)
                Text("Installing… confirm on the screen if asked.", style = MaterialTheme.typography.bodySmall, color = Mb.Muted)
            }
            UpdateState.NeedsPermission -> {
                Text(
                    "Android needs your OK to install updates from this app. Allow it, then come back.",
                    style = MaterialTheme.typography.bodyMedium, color = Mb.Warn,
                )
                PrimaryButton("Open settings", onOpenSettings, Modifier.fillMaxWidth())
            }
            is UpdateState.Failed -> {
                ErrorBanner(state.message)
                PrimaryButton("Try again", onUpdate, Modifier.fillMaxWidth())
            }
            else -> PrimaryButton("Update", onUpdate, Modifier.fillMaxWidth())
        }
    }
}

/** Full-screen gate when this build is older than the server's minimum. Background sync keeps running behind it. */
@Composable
fun UpdateRequiredScreen(offer: UpdateState.Available, state: UpdateState, onUpdate: () -> Unit, onOpenSettings: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Mb.Ground).safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandLogo(72.dp)
            Text("Update required", style = MaterialTheme.typography.headlineMedium)
            Text(
                "This version of Marbingo Field is too old to keep working. Install v${offer.info.versionName} to continue.",
                style = MaterialTheme.typography.bodyMedium, color = Mb.Muted, textAlign = TextAlign.Center,
            )
            if (offer.info.notes.isNotBlank()) Text(offer.info.notes, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Text(
                "Orders you've already taken are kept and keep syncing in the background.",
                style = MaterialTheme.typography.bodySmall, color = Mb.Muted, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            UpdateAction(state, onUpdate, onOpenSettings)
        }
    }
}

/** Overlays [UpdateRequiredScreen] when the offered update is mandatory. */
@Composable
fun UpdateGate(content: @Composable () -> Unit) {
    val update = LocalDeps.current.update
    val offer by update.offer.collectAsState()
    val state by update.state.collectAsState()
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        content()
        offer?.takeIf { it.required }?.let {
            UpdateRequiredScreen(it, state, onUpdate = { scope.launch { update.downloadAndInstall() } }, onOpenSettings = update::openInstallSettings)
        }
    }
}
