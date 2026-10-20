package dev.notai.tracker.android.ui

import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color

/** The running colour. Amber stays the default; every accent keeps one meaning: time is being recorded now. */
enum class Accent(val title: String) { AMBER("Янтарь"), TEAL("Бирюза"), LIME("Лайм"), GRAPHITE("Графит") }
enum class FontChoice(val title: String) { ONEST("Onest"), SYSTEM("Системный"), SERIF("С засечками") }
enum class TimerDigits(val title: String) { THIN("Тонкие"), REGULAR("Обычные") }

/**
 * How this phone shows the app. Device-local on purpose (like the theme before it): it is not part of the
 * records, so it is kept in the UI preferences and does not travel in backups.
 */
data class Appearance(
    val accent: Accent = Accent.AMBER,
    val font: FontChoice = FontChoice.ONEST,
    /** Multiplies the system font size; the system setting still applies on top. */
    val textScale: Float = 1f,
    val digits: TimerDigits = TimerDigits.THIN,
    val compact: Boolean = false,
    /** A slow, quiet invitation on the idle «( Начать )». Off whenever system animations are off. */
    val pulse: Boolean = true,
    val showFocus: Boolean = true,
    val showRibbon: Boolean = true,
) {
    companion object {
        val textScales = listOf(0.9f to "Мельче", 1f to "Обычный", 1.1f to "Крупнее", 1.2f to "Крупный")
        fun read(p: SharedPreferences) = Appearance(
            accent = runCatching { Accent.valueOf(p.getString("accent", null)!!) }.getOrDefault(Accent.AMBER),
            font = runCatching { FontChoice.valueOf(p.getString("font", null)!!) }.getOrDefault(FontChoice.ONEST),
            textScale = p.getFloat("textScale", 1f).takeIf { s -> textScales.any { it.first == s } } ?: 1f,
            digits = runCatching { TimerDigits.valueOf(p.getString("digits", null)!!) }.getOrDefault(TimerDigits.THIN),
            compact = p.getBoolean("compact", false),
            pulse = p.getBoolean("pulse", true),
            showFocus = p.getBoolean("showFocus", true),
            showRibbon = p.getBoolean("showRibbon", true),
        )
    }
    fun write(p: SharedPreferences) = p.edit().putString("accent", accent.name).putString("font", font.name).putFloat("textScale", textScale)
        .putString("digits", digits.name).putBoolean("compact", compact).putBoolean("pulse", pulse)
        .putBoolean("showFocus", showFocus).putBoolean("showRibbon", showRibbon).apply()
}

/** Accent sets for light and dark grounds: the colour itself, readable ink on paper, a soft wash and the text on top of it. */
internal data class AccentSet(val accent: Color, val ink: Color, val wash: Color, val on: Color)

internal fun accentSet(accent: Accent, dark: Boolean): AccentSet = when (accent) {
    Accent.AMBER -> if (dark) AccentSet(Color(0xFFFFB23C), Color(0xFFFFC266), Color(0x24FFB23C), Color(0xFF1D2024))
        else AccentSet(Color(0xFFE39508), Color(0xFF8A5200), Color(0xFFF6E6C3), Color(0xFF1D2024))
    Accent.TEAL -> if (dark) AccentSet(Color(0xFF3CC8D2), Color(0xFF7ADDE4), Color(0x243CC8D2), Color(0xFF0B1A1C))
        else AccentSet(Color(0xFF0F9DA8), Color(0xFF0A6970), Color(0xFFD3EEF0), Color(0xFFFFFFFF))
    Accent.LIME -> if (dark) AccentSet(Color(0xFFB5E24A), Color(0xFFC9EC78), Color(0x24B5E24A), Color(0xFF141A08))
        else AccentSet(Color(0xFF7FAE12), Color(0xFF4D6B05), Color(0xFFE6F1CC), Color(0xFF1D2024))
    Accent.GRAPHITE -> if (dark) AccentSet(Color(0xFFECEAE4), Color(0xFFECEAE4), Color(0x1FECEAE4), Color(0xFF11161E))
        else AccentSet(Color(0xFF2A2E34), Color(0xFF1D2024), Color(0xFFE2E4DF), Color(0xFFFBFBF8))
}
