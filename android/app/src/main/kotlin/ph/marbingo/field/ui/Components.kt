package ph.marbingo.field.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.marbingo.field.data.*
import ph.marbingo.field.ui.theme.Mb
import ph.marbingo.field.ui.theme.MbShapes
import ph.marbingo.field.ui.theme.MbType

@Composable
fun MbCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier,
        shape = MbShapes.Card,
        color = Mb.Surface,
        border = BorderStroke(1.dp, Mb.Line),
    ) { Column(Modifier.padding(16.dp), content = content) }
}

@Composable
fun Chip(text: String, tone: Tone, modifier: Modifier = Modifier) {
    val (bg, fg) = when (tone) {
        Tone.Neutral -> Mb.LineSoft to Mb.Muted
        Tone.Good -> Mb.AccentTint to Mb.Accent
        Tone.Warn -> Mb.WarnBg to Mb.Warn
        Tone.Danger -> Mb.DangerBg to Mb.DangerStrong
    }
    Surface(modifier = modifier, shape = MbShapes.Pill, color = bg, contentColor = fg) {
        Text(
            text,
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

fun statusTone(s: OrderStatus) = when (s) {
    OrderStatus.PENDING -> Tone.Warn
    OrderStatus.DELIVERED -> Tone.Good
    OrderStatus.CANCELLED -> Tone.Neutral
}

fun statusLabel(s: OrderStatus) = when (s) {
    OrderStatus.PENDING -> "Pending"
    OrderStatus.DELIVERED -> "Delivered"
    OrderStatus.CANCELLED -> "Cancelled"
}

@Composable
fun SyncChip(o: Order) {
    when (o.syncState) {
        SyncState.QUEUED -> Chip("Waiting to sync", Tone.Warn)
        SyncState.ERROR -> Chip("Sync error", Tone.Danger)
        SyncState.SYNCED -> Chip("Synced", Tone.Good)
    }
}

@Composable
fun OrderChips(o: Order, showSynced: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (o.isSale) Chip("Sold", Tone.Good) else Chip(statusLabel(o.status), statusTone(o.status))
        if (o.oversold) Chip("Oversold", Tone.Danger)
        if (o.syncState != SyncState.SYNCED || showSynced) SyncChip(o)
    }
}

/** A tappable order summary row (ref, store, date, total, chips). */
@Composable
fun OrderRow(o: Order, onClick: () -> Unit, modifier: Modifier = Modifier, showDate: Boolean = true) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MbShapes.Card,
        color = Mb.Surface,
        border = BorderStroke(1.dp, Mb.Line),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.reference, style = MbType.mono, color = Mb.Muted)
                Spacer(Modifier.width(10.dp))
                Text(
                    o.storeName,
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Text(Fmt.money(o.total), style = MbType.mono.copy(fontSize = 16.sp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (showDate) Fmt.dateTime(o.orderedAt) else Fmt.time(o.orderedAt),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = Mb.Muted,
                )
                OrderChips(o)
            }
            if (o.syncState == SyncState.ERROR && !o.syncError.isNullOrBlank()) {
                Text(o.syncError, style = MaterialTheme.typography.bodySmall, color = Mb.Danger)
            }
        }
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleLarge, color = Mb.Ink)
}

@Composable
fun EmptyState(title: String, hint: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = Mb.Muted, textAlign = TextAlign.Center)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        enabled = enabled && !loading,
        shape = MbShapes.Control,
        colors = ButtonDefaults.buttonColors(containerColor = Mb.Accent, contentColor = Mb.Surface),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Mb.Surface)
            Spacer(Modifier.width(10.dp))
        } else if (icon != null) {
            icon(); Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        enabled = enabled,
        shape = MbShapes.Control,
        border = BorderStroke(1.dp, Mb.InputBorder),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Mb.Ink),
    ) {
        if (icon != null) { icon(); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun ErrorBanner(text: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MbShapes.Control, color = Mb.DangerBg, contentColor = Mb.DangerStrong) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Mb.Surface,
    unfocusedContainerColor = Mb.Surface,
    focusedBorderColor = Mb.Accent,
    unfocusedBorderColor = Mb.InputBorder,
    focusedLabelColor = Mb.Accent,
)

@Composable
fun MbField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    singleLine: Boolean = true,
    minLines: Int = 1,
    isError: Boolean = false,
    supportingText: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp),
        shape = MbShapes.Control,
        singleLine = singleLine,
        minLines = minLines,
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        colors = fieldColors(),
    )
}

@Composable
fun ShopMark(mark: String, size: androidx.compose.ui.unit.Dp = 56.dp) {
    Box(
        Modifier.size(size).background(Mb.AccentTint, MbShapes.Control),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            mark,
            style = MaterialTheme.typography.titleLarge.copy(fontSize = (size.value * 0.4f).sp),
            color = Mb.Accent,
        )
    }
}

/** The official Marbingo logo in a white rounded tile. Used on the sign-in and update screens. */
@Composable
fun BrandLogo(size: androidx.compose.ui.unit.Dp = 72.dp) {
    Box(
        Modifier.size(size).background(Color.White, MbShapes.Control),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(ph.marbingo.field.R.drawable.brand_logo),
            contentDescription = "Marbingo",
            modifier = Modifier.size(size * 0.82f),
        )
    }
}

@Composable
fun Dot(color: Color, size: androidx.compose.ui.unit.Dp = 10.dp) {
    Box(Modifier.size(size).background(color, CircleShape))
}
