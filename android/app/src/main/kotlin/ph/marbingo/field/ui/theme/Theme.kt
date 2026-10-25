package ph.marbingo.field.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ph.marbingo.field.R

/** Marbingo design tokens (HANDOFF.md section 7). */
object Mb {
    val Ground = Color(0xFFF4F2EC)
    val Surface = Color(0xFFFFFEFB)
    val Ink = Color(0xFF1C1B18)
    val Muted = Color(0xFF5E5A52)
    val Line = Color(0xFFDDD8CC)
    val LineSoft = Color(0xFFECE8DF)
    val InputBorder = Color(0xFFCFC9BB)
    val Accent = Color(0xFF1E5B43)
    val AccentTint = Color(0xFFE3EEE7)
    val Warn = Color(0xFF8A4209)
    val WarnBg = Color(0xFFF7E7D6)
    val Danger = Color(0xFFA12A1E)
    val DangerStrong = Color(0xFF8E2A20)
    val DangerBg = Color(0xFFF2DEDC)
    val Sidebar = Color(0xFF1C1B18)
    val SidebarPanel = Color(0xFF2A2925)
    val SidebarMuted = Color(0xFFA9A497)
    val SidebarText = Color(0xFFD9D5CB)
    val SidebarAccent = Color(0xFF7BC3A0)
}

object MbShapes {
    val Card = RoundedCornerShape(14.dp)
    val Control = RoundedCornerShape(10.dp)
    val Pill = RoundedCornerShape(percent = 50)
}


@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, w: Int) =
    Font(res, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Bricolage = FontFamily(
    variable(R.font.bricolage_grotesque, 600),
    variable(R.font.bricolage_grotesque, 700),
)
val PlexSans = FontFamily(
    variable(R.font.ibm_plex_sans, 400),
    variable(R.font.ibm_plex_sans, 500),
    variable(R.font.ibm_plex_sans, 600),
)
val PlexMono = FontFamily(
    Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
)

object MbType {
    val mono = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    val monoSmall = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Normal, fontSize = 12.sp)
    val bigNumber = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight(700), fontSize = 30.sp, lineHeight = 34.sp)
}

private val typography = Typography(
    displaySmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight(700), fontSize = 34.sp, lineHeight = 40.sp),
    headlineLarge = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight(700), fontSize = 30.sp, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight(700), fontSize = 26.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight(700), fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Bricolage, fontWeight = FontWeight(700), fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

private val scheme = lightColorScheme(
    primary = Mb.Accent,
    onPrimary = Mb.Surface,
    primaryContainer = Mb.AccentTint,
    onPrimaryContainer = Mb.Accent,
    secondary = Mb.Accent,
    onSecondary = Mb.Surface,
    secondaryContainer = Mb.AccentTint,
    onSecondaryContainer = Mb.Accent,
    tertiary = Mb.Warn,
    onTertiary = Mb.Surface,
    tertiaryContainer = Mb.WarnBg,
    onTertiaryContainer = Mb.Warn,
    background = Mb.Ground,
    onBackground = Mb.Ink,
    surface = Mb.Surface,
    onSurface = Mb.Ink,
    surfaceVariant = Mb.LineSoft,
    onSurfaceVariant = Mb.Muted,
    surfaceTint = Mb.Surface,
    outline = Mb.InputBorder,
    outlineVariant = Mb.Line,
    error = Mb.Danger,
    onError = Mb.Surface,
    errorContainer = Mb.DangerBg,
    onErrorContainer = Mb.DangerStrong,
    surfaceContainerLowest = Mb.Surface,
    surfaceContainerLow = Mb.Surface,
    surfaceContainer = Mb.Surface,
    surfaceContainerHigh = Mb.LineSoft,
    surfaceContainerHighest = Mb.LineSoft,
    inverseSurface = Mb.Ink,
    inverseOnSurface = Mb.Ground,
)

@Composable
fun MarbingoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(6.dp),
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(10.dp),
            large = RoundedCornerShape(14.dp),
            extraLarge = RoundedCornerShape(14.dp),
        ),
        content = content,
    )
}
