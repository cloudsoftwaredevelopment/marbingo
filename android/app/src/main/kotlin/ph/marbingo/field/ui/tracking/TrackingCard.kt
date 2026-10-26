package ph.marbingo.field.ui.tracking

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ph.marbingo.field.tracking.TrackingState
import ph.marbingo.field.ui.Dot
import ph.marbingo.field.ui.Fmt
import ph.marbingo.field.ui.Tone
import ph.marbingo.field.ui.theme.Mb
import ph.marbingo.field.ui.theme.MbShapes

data class TrackingSummary(val title: String, val detail: String?, val tone: Tone, val isBanner: Boolean)

/** Text for the Home card and the Settings section. */
fun trackingSummary(s: TrackingState): TrackingSummary {
    val points = if (s.pendingPoints > 0) Fmt.plural(s.pendingPoints, "point") + " waiting" else null
    return when {
        s.needsAttention -> TrackingSummary("Location sharing is off. Tap to fix.", null, Tone.Danger, isBanner = true)
        s.inHoursNow && s.serviceRunning -> TrackingSummary(
            "Sharing location",
            listOfNotNull(s.lastFixAt?.let { "last fix ${Fmt.relative(it, s.now)}" } ?: "waiting for first fix", points).joinToString(" · "),
            Tone.Good, false,
        )
        s.inHoursNow -> TrackingSummary("Starting location sharing…", points, Tone.Warn, false)
        else -> {
            val next = s.schedule.nextStartLabel(s.now)
            TrackingSummary(
                if (next != null) "Off duty · starts $next" else "Off duty",
                listOfNotNull(if (!s.setupComplete) "Setup needed. Tap to finish." else null, points).joinToString(" · ").ifEmpty { null },
                if (s.setupComplete) Tone.Neutral else Tone.Warn, false,
            )
        }
    }
}

/** Home status card; a red full-width banner when sharing should be on but isn't. */
@Composable
fun TrackingStatusCard(state: TrackingState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val sum = trackingSummary(state)
    if (sum.isBanner) {
        Surface(
            onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MbShapes.Card,
            color = Mb.DangerBg, contentColor = Mb.DangerStrong, border = BorderStroke(1.dp, Mb.Danger),
        ) {
            Row(Modifier.padding(16.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LocationOff, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(sum.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Icon(Icons.Filled.ChevronRight, contentDescription = null)
            }
        }
        return
    }
    Surface(
        onClick = onClick, modifier = modifier.fillMaxWidth(), shape = MbShapes.Card,
        color = Mb.Surface, border = BorderStroke(1.dp, Mb.Line),
    ) {
        Row(Modifier.padding(16.dp).heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(when (sum.tone) { Tone.Good -> Mb.Accent; Tone.Warn -> Mb.Warn; Tone.Danger -> Mb.Danger; Tone.Neutral -> Mb.Muted })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(sum.title, style = MaterialTheme.typography.titleMedium)
                sum.detail?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodyMedium,
                        color = if (sum.tone == Tone.Warn) Mb.Warn else Mb.Muted,
                    )
                }
            }
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Mb.Muted)
        }
    }
}
