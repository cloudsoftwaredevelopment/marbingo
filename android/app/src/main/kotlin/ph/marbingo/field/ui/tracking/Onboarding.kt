package ph.marbingo.field.ui.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ph.marbingo.field.tracking.TrackingSchedule
import ph.marbingo.field.tracking.TrackingState
import ph.marbingo.field.ui.*
import ph.marbingo.field.ui.theme.Mb

enum class SetupStep(val title: String) {
    CONSENT("Location sharing notice"),
    PRECISE("Precise location"),
    BACKGROUND("Allow all the time"),
    NOTIFICATIONS("Notifications"),
    BATTERY("Keep running in the background"),
}

data class StepUi(
    val step: SetupStep,
    val number: Int,
    val done: Boolean,
    /** Steps 1-3 are required; 4-5 are recommended. */
    val required: Boolean,
    /** Earlier required steps are done, so this one can be acted on. */
    val enabled: Boolean,
)

data class OnboardingUi(
    val schedule: TrackingSchedule,
    val steps: List<StepUi>,
    val canContinue: Boolean,
    /** The step the agent should do next, or null when everything is done. */
    val current: SetupStep?,
    /** Android 11+ sends "all the time" through the app's settings page. */
    val backgroundViaSettings: Boolean,
    val brandTip: String?,
) {
    val allDone get() = steps.all { it.done }
}

private val AGGRESSIVE_BRANDS = setOf("xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "vivo", "huawei", "honor", "samsung")

class OnboardingViewModel(
    private val deps: UiDeps,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val manufacturer: String = Build.MANUFACTURER.orEmpty(),
) : ViewModel() {

    val ui: StateFlow<OnboardingUi> = deps.tracking.state
        .map { build(it, sdkInt, manufacturer) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), build(TrackingState(), sdkInt, manufacturer))

    fun giveConsent() {
        viewModelScope.launch {
            try { deps.tracking.giveConsent(); deps.tracking.ensureScheduled() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    /** Re-read permissions (after a dialog or the settings app) and start sharing right away when it is on-duty time. */
    fun refresh() {
        viewModelScope.launch {
            try { deps.tracking.ensureScheduled() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }
    }

    companion object {
        /** Pure step logic: which steps exist on this Android version, which are done, which can be acted on. */
        fun build(state: TrackingState, sdkInt: Int, manufacturer: String): OnboardingUi {
            val p = state.permissions
            val applicable = buildList {
                add(SetupStep.CONSENT to state.consentGiven)
                add(SetupStep.PRECISE to p.fine)
                if (sdkInt >= Build.VERSION_CODES.Q) add(SetupStep.BACKGROUND to p.background)
                if (sdkInt >= Build.VERSION_CODES.TIRAMISU) add(SetupStep.NOTIFICATIONS to p.notifications)
                add(SetupStep.BATTERY to p.batteryExempt)
            }
            val requiredSteps = setOf(SetupStep.CONSENT, SetupStep.PRECISE, SetupStep.BACKGROUND)
            val steps = applicable.mapIndexed { i, (step, done) ->
                val earlierRequiredDone = applicable.take(i).filter { it.first in requiredSteps }.all { it.second }
                StepUi(step, i + 1, done, step in requiredSteps, enabled = earlierRequiredDone)
            }
            val requiredDone = steps.filter { it.required }.all { it.done }
            val brand = manufacturer.lowercase()
            return OnboardingUi(
                schedule = state.schedule,
                steps = steps,
                canContinue = requiredDone,
                current = steps.firstOrNull { !it.done }?.step,
                backgroundViaSettings = sdkInt >= Build.VERSION_CODES.R,
                brandTip = if (brand in AGGRESSIVE_BRANDS)
                    "On this ${manufacturer.trim().replaceFirstChar { it.uppercase() }} device also open Settings → Apps → Marbingo Field → Battery and choose No restrictions / Allow background activity. Otherwise Android may stop location sharing."
                else null,
            )
        }

        fun noticeText(s: TrackingSchedule) =
            "While you're on duty (${s.fullLabel()}) this tablet shares its location with the shop so managers can see store visits and routes. " +
                "Location is not collected outside these hours. History is kept for 12 months."
    }
}

@Composable
fun TrackingSetupScreen(vm: OnboardingViewModel, nav: NavController, forced: Boolean) {
    val ui by vm.ui.collectAsState()
    val context = LocalContext.current

    // Coming back from a permission dialog or the system settings: re-check and start sharing if it's duty time.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.refresh() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val fineLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }
    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }

    if (forced) androidx.activity.compose.BackHandler {} // can't back out of the required steps

    fun done() { if (!nav.popBackStack()) nav.navigate(Routes.HOME) }

    DetailScaffold("Location sharing", onBack = if (forced) null else ::done) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Set up location sharing", style = MaterialTheme.typography.headlineSmall)
            Text(
                "A few quick steps so the shop can see your store visits. You only do this once.",
                style = MaterialTheme.typography.bodyMedium, color = Mb.Muted,
            )
            ui.steps.forEach { s ->
                StepCard(s, isCurrent = ui.current == s.step) {
                    when (s.step) {
                        SetupStep.CONSENT -> {
                            Text(OnboardingViewModel.noticeText(ui.schedule), style = MaterialTheme.typography.bodyMedium)
                            if (!s.done) {
                                Spacer(Modifier.height(10.dp))
                                PrimaryButton("I understand", vm::giveConsent, Modifier.fillMaxWidth())
                            }
                        }
                        SetupStep.PRECISE -> {
                            Text(
                                "Allow precise location so visits are matched to the right store. Choose \"While using the app\" or \"Precise\" if asked.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (!s.done) {
                                Spacer(Modifier.height(10.dp))
                                PrimaryButton(
                                    "Allow precise location",
                                    {
                                        fineLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                                    },
                                    Modifier.fillMaxWidth(), enabled = s.enabled,
                                )
                            }
                        }
                        SetupStep.BACKGROUND -> {
                            Text(
                                if (ui.backgroundViaSettings)
                                    "Sharing continues while the screen is off, so Android needs \"Allow all the time\". On the next screen open Permissions → Location and choose \"Allow all the time\", then come back."
                                else "Sharing continues while the screen is off, so choose \"Allow all the time\" when asked.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (!s.done) {
                                Spacer(Modifier.height(10.dp))
                                PrimaryButton(
                                    if (ui.backgroundViaSettings) "Open location settings" else "Allow all the time",
                                    {
                                        if (ui.backgroundViaSettings) openAppSettings(context)
                                        else bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                    },
                                    Modifier.fillMaxWidth(), enabled = s.enabled,
                                )
                            }
                        }
                        SetupStep.NOTIFICATIONS -> {
                            Text(
                                "Android shows a small \"Sharing location\" notice while you're on duty. Allow notifications so it can appear. Recommended.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (!s.done) {
                                Spacer(Modifier.height(10.dp))
                                SecondaryButton(
                                    "Allow notifications",
                                    { if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                                    Modifier.fillMaxWidth(), enabled = s.enabled,
                                )
                            }
                        }
                        SetupStep.BATTERY -> {
                            Text(
                                "Let Marbingo Field keep running while the tablet sleeps, so no part of your day is missed. Recommended.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            ui.brandTip?.let {
                                Spacer(Modifier.height(8.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = Mb.Warn)
                            }
                            if (!s.done) {
                                Spacer(Modifier.height(10.dp))
                                SecondaryButton("Allow background activity", { requestIgnoreBatteryOptimizations(context) }, Modifier.fillMaxWidth(), enabled = s.enabled)
                            }
                        }
                    }
                }
            }
            if (ui.canContinue) {
                PrimaryButton(
                    if (ui.allDone) "Done" else "Continue",
                    ::done,
                    Modifier.fillMaxWidth().heightIn(min = 56.dp),
                )
                if (!ui.allDone) {
                    Text(
                        "The remaining steps are recommended: they make sharing more reliable.",
                        style = MaterialTheme.typography.bodySmall, color = Mb.Muted,
                    )
                }
            } else {
                Text("Finish steps 1 to ${ui.steps.count { it.required }} to continue.", style = MaterialTheme.typography.bodySmall, color = Mb.Muted)
            }
        }
    }
}

@Composable
private fun StepCard(s: StepUi, isCurrent: Boolean, body: @Composable ColumnScope.() -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = ph.marbingo.field.ui.theme.MbShapes.Card,
        color = Mb.Surface,
        border = BorderStroke(if (isCurrent) 2.dp else 1.dp, if (isCurrent) Mb.Accent else Mb.Line),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(32.dp).background(if (s.done) Mb.Accent else Mb.LineSoft, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (s.done) Icon(Icons.Filled.Check, contentDescription = "Done", tint = Mb.Surface, modifier = Modifier.size(20.dp))
                else Text(s.number.toString(), style = MaterialTheme.typography.labelLarge, color = Mb.Muted)
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(s.step.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                    if (!s.required) Chip("Recommended", Tone.Neutral)
                    if (s.done) Chip("Done", Tone.Good)
                }
                Spacer(Modifier.height(6.dp))
                body()
            }
        }
    }
}

internal fun openAppSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { context.startActivity(intent) } catch (_: Exception) { }
}

@SuppressLint("BatteryLife") // a sideloaded business app whose whole job is to keep sharing location while on duty
internal fun requestIgnoreBatteryOptimizations(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(direct)
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { openAppSettings(context) }
    }
}
