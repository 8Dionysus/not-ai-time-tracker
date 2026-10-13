package dev.notai.tracker.android.ui

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.notai.tracker.android.R

/** (not)AI palette. Amber means one thing only: time is being recorded right now. */
@Immutable
data class NotAiColors(
    val paper: Color, val raised: Color, val sunk: Color, val tonal: Color,
    val ink: Color, val ink2: Color, val ink3: Color, val rule: Color,
    val amber: Color, val amberInk: Color, val amberWash: Color, val onAmber: Color,
    val danger: Color, val scrim: Color,
    /** Activity areas in fixed order: work, language, movement, learning, reading, leisure. */
    val areas: List<Color>, val none: Color, val isDark: Boolean,
)

val LightColors = NotAiColors(
    paper = Color(0xFFF2F3EF), raised = Color(0xFFFBFBF8), sunk = Color(0xFFE7E9E3), tonal = Color(0xFFE3E6DF),
    ink = Color(0xFF1D2024), ink2 = Color(0xFF5E646E), ink3 = Color(0xFF8B9098), rule = Color(0xFFDCDFD8),
    amber = Color(0xFFE39508), amberInk = Color(0xFF8A5200), amberWash = Color(0xFFF6E6C3), onAmber = Color(0xFF1D2024),
    danger = Color(0xFFB4382A), scrim = Color(0x521D2024),
    areas = listOf(Color(0xFF2F8F6A), Color(0xFF2479B5), Color(0xFFD0573A), Color(0xFF8C56C0), Color(0xFF9C8420), Color(0xFFC84B7E)),
    none = Color(0xFF9AA0A8), isDark = false,
)

val DarkColors = NotAiColors(
    paper = Color(0xFF11161E), raised = Color(0xFF19212C), sunk = Color(0xFF0B0F15), tonal = Color(0xFF232C38),
    ink = Color(0xFFECEAE4), ink2 = Color(0xFFA3ABB7), ink3 = Color(0xFF6F7987), rule = Color(0xFF27303D),
    amber = Color(0xFFFFB23C), amberInk = Color(0xFFFFC266), amberWash = Color(0x24FFB23C), onAmber = Color(0xFF1D2024),
    danger = Color(0xFFFF7B66), scrim = Color(0x99030508),
    areas = listOf(Color(0xFF35A078), Color(0xFF3D8FD1), Color(0xFFDE6A45), Color(0xFF9C70DA), Color(0xFFA88B2C), Color(0xFFDA5F92)),
    none = Color(0xFF5D6673), isDark = true,
)

val Onest = FontFamily(
    Font(R.font.onest_200, FontWeight.ExtraLight),
    Font(R.font.onest_300, FontWeight.Light),
    Font(R.font.onest_400, FontWeight.Normal),
    Font(R.font.onest_500, FontWeight.Medium),
    Font(R.font.onest_600, FontWeight.SemiBold),
)

/** One family, tabular figures everywhere so time never jitters. Sizes are sp, equal to the design px. */
@Immutable
data class NotAiType(
    val timer: TextStyle, val timerSeconds: TextStyle, val big: TextStyle, val title: TextStyle, val sheetTitle: TextStyle,
    val start: TextStyle, val section: TextStyle, val label: TextStyle, val body: TextStyle, val bodySmall: TextStyle,
    val caption: TextStyle, val micro: TextStyle, val button: TextStyle,
)

private fun style(size: Int, weight: FontWeight, line: Int? = null, tracking: Double = 0.0) = TextStyle(
    fontFamily = Onest, fontWeight = weight, fontSize = size.sp, lineHeight = (line ?: (size * 1.3).toInt()).sp,
    letterSpacing = tracking.em, fontFeatureSettings = "tnum",
)

val NotAiTypography = NotAiType(
    timer = style(76, FontWeight.ExtraLight, 80, -0.03),
    timerSeconds = style(24, FontWeight.Normal, 28),
    big = style(44, FontWeight.Light, 50, -0.02),
    title = style(28, FontWeight.Medium, 34, -0.01),
    sheetTitle = style(20, FontWeight.Medium, 26),
    start = style(34, FontWeight.Normal, 40, -0.02),
    section = style(15, FontWeight.SemiBold, 20),
    label = style(17, FontWeight.Normal, 22),
    body = style(16, FontWeight.Normal, 22),
    bodySmall = style(14, FontWeight.Normal, 20),
    caption = style(13, FontWeight.Normal, 18),
    micro = style(11, FontWeight.Normal, 14),
    button = style(16, FontWeight.Medium, 20),
)

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

val LocalColors = staticCompositionLocalOf { LightColors }
val LocalType = staticCompositionLocalOf { NotAiTypography }
/** True when the system animator scale is 0: no breathing, no entrance motion. */
val LocalReducedMotion = staticCompositionLocalOf { false }

object NotAi {
    val colors: NotAiColors @Composable get() = LocalColors.current
    val type: NotAiType @Composable get() = LocalType.current
    val reducedMotion: Boolean @Composable get() = LocalReducedMotion.current
}

@Composable
fun NotAiTheme(choice: ThemeChoice = ThemeChoice.SYSTEM, reducedMotion: Boolean? = null, content: @Composable () -> Unit) {
    val dark = when (choice) { ThemeChoice.SYSTEM -> isSystemInDarkTheme(); ThemeChoice.LIGHT -> false; ThemeChoice.DARK -> true }
    val colors = if (dark) DarkColors else LightColors
    val context = LocalContext.current
    val reduced = reducedMotion ?: remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val scheme = if (dark) darkColorScheme(
        primary = colors.ink, onPrimary = colors.paper, secondary = colors.amber, onSecondary = colors.onAmber,
        background = colors.paper, onBackground = colors.ink, surface = colors.raised, onSurface = colors.ink,
        surfaceVariant = colors.sunk, onSurfaceVariant = colors.ink2, outline = colors.rule, error = colors.danger,
        surfaceContainerHigh = colors.raised, surfaceContainer = colors.raised, surfaceContainerHighest = colors.tonal,
    ) else lightColorScheme(
        primary = colors.ink, onPrimary = colors.paper, secondary = colors.amber, onSecondary = colors.onAmber,
        background = colors.paper, onBackground = colors.ink, surface = colors.raised, onSurface = colors.ink,
        surfaceVariant = colors.sunk, onSurfaceVariant = colors.ink2, outline = colors.rule, error = colors.danger,
        surfaceContainerHigh = colors.raised, surfaceContainer = colors.raised, surfaceContainerHighest = colors.tonal,
    )
    CompositionLocalProvider(LocalColors provides colors, LocalType provides NotAiTypography, LocalReducedMotion provides reduced) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
