package dev.notai.tracker.android.ui

import dev.notai.tracker.ActivityIcons
import dev.notai.tracker.Classification
import dev.notai.tracker.CoreError
import dev.notai.tracker.Dataset
import dev.notai.tracker.ErrorCode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Formatting only. Durations always come from the core; the UI never derives them from timestamps. */
object Format {
    private val ru = Locale.forLanguageTag("ru")
    private val dayFormat = DateTimeFormatter.ofPattern("EEE, d MMMM", ru)
    private val shortDay = DateTimeFormatter.ofPattern("EEE d", ru)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMMM", ru)

    fun duration(ms: Long): String {
        if (ms <= 0L) return "0 мин"
        if (ms < 60_000L) return "${maxOf(1L, ms / 1000)} с"
        val minutes = ms / 60_000L // floor, like the timer face, so a stopped entry matches what was on screen
        val h = minutes / 60; val m = minutes % 60
        return when { h > 0 && m == 0L -> "$h ч"; h > 0 -> "$h ч ${m.toString().padStart(2, '0')} мин"; else -> "$m мин" }
    }
    /** Timer face: hours:minutes large, seconds separately. Uses the core's trusted duration. */
    fun timerMain(ms: Long): String { val s = ms / 1000; return "${s / 3600}:${((s / 60) % 60).toString().padStart(2, '0')}" }
    fun timerSeconds(ms: Long): String = ((ms / 1000) % 60).toString().padStart(2, '0')
    /** Focus countdown: minutes:seconds. */
    fun countdown(ms: Long): String { val s = (ms + 999) / 1000; return "${s / 60}:${(s % 60).toString().padStart(2, '0')}" }
    fun clock(epochMs: Long, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone)
        return "${t.hour}:${t.minute.toString().padStart(2, '0')}"
    }
    fun range(start: Long, end: Long, zone: ZoneId) = "${clock(start, zone)}–${clock(end, zone)}"
    fun dayTitle(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "Сегодня"
        today.minusDays(1) -> "Вчера"
        else -> date.format(dayFormat)
    }
    fun headerDate(date: LocalDate): String = date.format(dayFormat)
    fun shortDay(date: LocalDate): String = date.format(shortDay)
    fun dayMonth(date: LocalDate): String = date.format(dayMonth)
    fun period(first: LocalDate, last: LocalDate): String =
        if (first.month == last.month) "${first.dayOfMonth}–${last.format(dayMonth)}" else "${first.format(dayMonth)} – ${last.format(dayMonth)}"

    /** Russian plural: 1 запись, 2 записи, 5 записей. */
    fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10; val m100 = n % 100
        val word = when { m10 == 1 && m100 != 11 -> one; m10 in 2..4 && m100 !in 12..14 -> few; else -> many }
        return "$n $word"
    }

    fun error(e: Throwable): String = when ((e as? CoreError)?.code) {
        ErrorCode.STALE -> "Данные успели измениться. Проверь экран и повтори."
        ErrorCode.INVALID_INTERVAL -> "Такой интервал невозможен: проверь начало и конец."
        ErrorCode.OVERLAP -> "Интервал пересекается с другой записью."
        ErrorCode.RECOVERY_REQUIRED -> "Сначала уточни неизвестное время."
        ErrorCode.STORAGE -> "Не удалось сохранить. Данные не изменены."
        ErrorCode.UNSUPPORTED_IMPORT -> "Этот файл не подходит: ${e.message}"
        ErrorCode.INVALID_INPUT -> "Не получилось: ${e.message}"
        null -> "Не получилось: ${e.message ?: e.javaClass.simpleName}"
    }
}

/** Fixed colour slots for activity areas. Index into NotAiColors.areas; null means neutral. */
object Areas {
    const val WORK = 0; const val LANGUAGE = 1; const val MOVEMENT = 2; const val LEARNING = 3; const val READING = 4; const val LEISURE = 5

    fun slot(rootId: String?): Int? {
        val id = rootId ?: return null
        return when {
            id == "area.language_practice" || id == "area.language_learning" -> LANGUAGE
            id == "area.movement" || id == "area.outdoor_leisure" || id.startsWith("compound.") ||
                id.startsWith("activity.proposed_sport") || id.startsWith("proposed.movement") -> MOVEMENT
            id in setOf("area.learning", "area.education_formal", "area.education_self_directed", "area.knowledge_research", "area.teaching") -> LEARNING
            id in setOf("area.reading", "area.writing", "area.writing_editing_translation") -> READING
            id in setOf("area.media", "area.leisure", "area.unstructured_leisure", "area.games", "area.cultural_visits", "area.social",
                "area.creative", "area.self_directed_creation", "area.digital_visual_creation", "area.digital_audio_music",
                "area.digital_video_motion", "area.digital_3d_interactive") || id.startsWith("group.creative") || id.startsWith("proposed.craft") -> LEISURE
            id in setOf("area.household", "area.self_care", "area.sleep", "area.food", "area.mobility", "area.errands",
                "area.caregiving", "area.civic_spiritual", "area.custom") || id.startsWith("group.outdoor") -> null
            id.startsWith("area.") -> WORK
            else -> null
        }
    }

    /** Everyday areas first, in this order; professional areas follow alphabetically. */
    val everyday = listOf(
        "area.language_practice", "area.movement", "area.learning", "area.work", "area.reading", "area.writing", "area.media",
        "area.creative", "area.leisure", "area.social", "area.household", "area.self_care", "area.food", "area.sleep",
        "area.mobility", "area.errands", "area.caregiving", "area.civic_spiritual", "area.custom",
    )
}

/** How a saved classification reads on screen. Names are never used to classify anything. */
data class Label(val title: String, val detail: String?, val slot: Int?, val unclassified: Boolean, val glyph: Int = ActivityGlyphs.custom)

fun Dataset.label(c: Classification): Label {
    val type = c.activityTypeId?.let { id -> types.firstOrNull { it.id == id } }
    val title = c.name ?: type?.name
    val root = c.ancestorPathAtWrite.firstOrNull() ?: type?.let { rootOf(it.id) }
    val details = c.details.values.filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotEmpty() }
    val rootName = root?.takeIf { it != c.activityTypeId }?.let { r -> types.firstOrNull { it.id == r }?.name?.lowercase() }
    return Label(title ?: "Без занятия", details ?: rootName, Areas.slot(root), title == null && c.cardId == null, glyph(c))
}

/** Icon through the core's semantic keys; a named choice without a type is "custom", never "unclassified". */
fun Dataset.glyph(c: Classification): Int {
    val key = "${System.identityHashCode(types)}|${System.identityHashCode(cards)}|${c.cardId}|${c.activityTypeId}|${c.name != null}"
    synchronized(glyphCache) { glyphCache[key]?.let { return it } }
    val icon = ActivityIcons.resolve(this, c)
    val glyph = if (icon.keys.isEmpty() && c.name != null) ActivityGlyphs.custom else ActivityGlyphs.resolve(icon)
    synchronized(glyphCache) { glyphCache[key] = glyph }
    return glyph
}
private val glyphCache = object : LinkedHashMap<String, Int>(256, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>?) = size > 512
}

private fun Dataset.rootOf(typeId: String): String {
    var current = types.firstOrNull { it.id == typeId } ?: return typeId
    var guard = 0
    while (current.parentId != null && guard++ < 32) current = types.firstOrNull { it.id == current.parentId } ?: break
    return current.id
}
