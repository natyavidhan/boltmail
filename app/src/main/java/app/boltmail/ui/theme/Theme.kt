/*
 * THESIS: BoltMail's inbox is the notification shade that never clears. Mail arrives in the same grouped,
 *   rounded stacks Android uses for alerts, so it reads as part of the phone; it refuses the
 *   avatar-and-star list chrome of the category default.
 * OWN-WORLD: cool tonal ground (#ECEEF1 / #0A0B0D); white or graphite rows in stacks with 24dp outer and
 *   6dp inner corners and 2dp seams; one bolt-blue accent (#2F5BFF / #93A6FF) for unread state and the
 *   primary action only; account identity as an 8dp colored tag; Google Sans Flex, a touch of ROND in titles.
 * STORY: open the app, see what's new grouped by day, tap to read in a stacked card, reply from the pill
 *   bar; push status is visible and fixable in one tap.
 * FIRST VIEWPORT: "Inbox ▾" (mailbox switcher) top left, search and settings top right, "All accounts ·
 *   n unread" beneath, a day label, then the first stack; compose FAB bottom right.
 * FORM: Shade (Android notification-shade stacks), grounded candidate 4 of 7, seed bc40352f.
 * FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict,
 *   DESIGN.md, and every shipping raster carrying its provenance
 */
@file:OptIn(ExperimentalTextApi::class)

package app.boltmail.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.boltmail.R

@Immutable
data class BoltColors(
    val ground: Color,
    val surface: Color,
    val raised: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val line: Color,
    val danger: Color,
    val dangerSoft: Color,
    val onDangerSoft: Color,
    val dark: Boolean,
)

val LightBolt = BoltColors(
    ground = Color(0xFFECEEF1),
    surface = Color(0xFFFFFFFF),
    raised = Color(0xFFF5F6F8),
    ink = Color(0xFF16181B),
    ink2 = Color(0xFF545961),
    ink3 = Color(0xFF6A6F77),
    accent = Color(0xFF2F5BFF),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFDCE3FF),
    onAccentSoft = Color(0xFF0F2A8F),
    line = Color(0xFFD9DCE1),
    danger = Color(0xFFC5221F),
    dangerSoft = Color(0xFFFDE2E0),
    onDangerSoft = Color(0xFF8C1D18),
    dark = false,
)

val DarkBolt = BoltColors(
    ground = Color(0xFF0A0B0D),
    surface = Color(0xFF1A1C20),
    raised = Color(0xFF23262B),
    ink = Color(0xFFECEEF1),
    ink2 = Color(0xFFA3A8B0),
    ink3 = Color(0xFF8E939B),
    accent = Color(0xFF93A6FF),
    onAccent = Color(0xFF0B1A5C),
    accentSoft = Color(0xFF27336E),
    onAccentSoft = Color(0xFFDDE4FF),
    line = Color(0xFF34373D),
    danger = Color(0xFFFF8A80),
    dangerSoft = Color(0xFF4C1F1D),
    onDangerSoft = Color(0xFFFFDAD6),
    dark = true,
)

val LocalBolt = staticCompositionLocalOf { LightBolt }

object Bolt {
    val colors: BoltColors
        @Composable get() = LocalBolt.current
}

private fun gsf(weight: Int, rond: Float = 0f) = Font(
    R.font.google_sans_flex,
    weight = FontWeight(weight),
    style = FontStyle.Normal,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("ROND", rond)),
)

/** Google Sans Flex, subset to Latin; other scripts fall back to the system fonts. */
val Sans = FontFamily(gsf(400), gsf(450), gsf(500), gsf(560), gsf(600), gsf(680))

/** The same face with softened terminals, for titles only. */
val SansRound = FontFamily(gsf(500, 40f), gsf(600, 40f))

private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(size: Float, line: Float, weight: Int, family: FontFamily = Sans, tracking: Float = 0f) = TextStyle(
    fontFamily = family,
    fontSize = size.sp,
    lineHeight = line.sp,
    fontWeight = FontWeight(weight),
    letterSpacing = tracking.sp,
    lineHeightStyle = trim,
)

val BoltType = Typography(
    displaySmall = style(34f, 40f, 500, SansRound, -0.6f),
    headlineMedium = style(31f, 38f, 500, SansRound, -0.4f),
    headlineSmall = style(25f, 31f, 500, SansRound, -0.3f),
    titleLarge = style(21f, 27f, 500, SansRound, -0.2f),
    titleMedium = style(16f, 22f, 450),
    titleSmall = style(15f, 20f, 560),
    bodyLarge = style(16f, 25.6f, 400),
    bodyMedium = style(15f, 21f, 400),
    bodySmall = style(14f, 20f, 400),
    labelLarge = style(14.5f, 20f, 560),
    labelMedium = style(13f, 18f, 600, tracking = 0.15f),
    labelSmall = style(13f, 17f, 400),
)

private val BoltShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private fun BoltColors.scheme() = if (dark) {
    darkColorScheme(
        primary = accent, onPrimary = onAccent, primaryContainer = accentSoft, onPrimaryContainer = onAccentSoft,
        secondary = ink2, onSecondary = ground, secondaryContainer = raised, onSecondaryContainer = ink,
        tertiary = accent, onTertiary = onAccent,
        background = ground, onBackground = ink, surface = ground, onSurface = ink,
        surfaceVariant = raised, onSurfaceVariant = ink2,
        surfaceContainerLowest = surface, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = raised, surfaceContainerHighest = raised, surfaceBright = raised, surfaceDim = ground,
        outline = ink3, outlineVariant = line,
        error = danger, onError = Color(0xFF3A0B08), errorContainer = dangerSoft, onErrorContainer = onDangerSoft,
        inverseSurface = Color(0xFFE6E8EB), inverseOnSurface = Color(0xFF1A1C20), inversePrimary = Color(0xFF2F5BFF),
        scrim = Color.Black,
    )
} else {
    lightColorScheme(
        primary = accent, onPrimary = onAccent, primaryContainer = accentSoft, onPrimaryContainer = onAccentSoft,
        secondary = ink2, onSecondary = surface, secondaryContainer = raised, onSecondaryContainer = ink,
        tertiary = accent, onTertiary = onAccent,
        background = ground, onBackground = ink, surface = ground, onSurface = ink,
        surfaceVariant = raised, onSurfaceVariant = ink2,
        surfaceContainerLowest = surface, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = surface, surfaceContainerHighest = raised, surfaceBright = surface, surfaceDim = ground,
        outline = ink3, outlineVariant = line,
        error = danger, onError = Color.White, errorContainer = dangerSoft, onErrorContainer = onDangerSoft,
        inverseSurface = Color(0xFF2A2C30), inverseOnSurface = Color(0xFFF1F2F4), inversePrimary = Color(0xFF93A6FF),
        scrim = Color.Black,
    )
}

@Composable
fun BoltTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkBolt else LightBolt
    CompositionLocalProvider(LocalBolt provides colors) {
        MaterialTheme(colorScheme = colors.scheme(), typography = BoltType, shapes = BoltShapes, content = content)
    }
}

/** Soft colors for account tags; the user picks one per account. */
val AccountColors = listOf(
    0xFFF07A2E, 0xFF14A89A, 0xFF8B67F0, 0xFF2F8FE0, 0xFFE0457B, 0xFF6E9F2E, 0xFFC9A227, 0xFF7A8594,
).map { it.toInt() }
