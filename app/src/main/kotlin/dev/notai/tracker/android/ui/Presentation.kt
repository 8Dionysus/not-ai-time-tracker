package dev.notai.tracker.android.ui

import dev.notai.tracker.ActivityIcons
import dev.notai.tracker.Classification
import dev.notai.tracker.CoreError
import dev.notai.tracker.Dataset
import dev.notai.tracker.ErrorCode
import dev.notai.tracker.ErrorArgument
import dev.notai.tracker.ImportIssue
import dev.notai.tracker.ImportIssueCode
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

    /** Monday first, 1..7 like java.time. */
    val weekdays = listOf("пн", "вт", "ср", "чт", "пт", "сб", "вс")
    fun planRule(days: Set<Int>, date: LocalDate?, time: java.time.LocalTime?): String {
        val `when` = when {
            date != null -> date.format(dayMonth)
            days.size == 7 -> "каждый день"
            days == setOf(1, 2, 3, 4, 5) -> "по будням"
            days == setOf(6, 7) -> "по выходным"
            else -> days.sorted().joinToString(", ") { weekdays[it - 1] }
        }
        return `when` + (time?.let { ", ${it.hour}:${it.minute.toString().padStart(2, '0')}" } ?: "")
    }

    fun goalAmount(metric: dev.notai.tracker.GoalMetric, x: Long): String = when (metric) {
        dev.notai.tracker.GoalMetric.DURATION_MS -> duration(x)
        dev.notai.tracker.GoalMetric.SESSION_COUNT -> plural(x.toInt(), "раз", "раза", "раз")
        dev.notai.tracker.GoalMetric.DISTINCT_RECORDED_DAYS -> plural(x.toInt(), "день", "дня", "дней")
    }
    fun goalRule(metric: dev.notai.tracker.GoalMetric, direction: dev.notai.tracker.GoalDirection, period: dev.notai.tracker.GoalPeriod, target: Long): String {
        val per = when (period) { dev.notai.tracker.GoalPeriod.DAY -> " в день"; dev.notai.tracker.GoalPeriod.WEEK -> " в неделю"
            dev.notai.tracker.GoalPeriod.MONTH -> " в месяц"; dev.notai.tracker.GoalPeriod.FIXED -> "" }
        // "от"/"не больше" take the genitive: от 1 раза, от 3 раз, не больше 2 дней.
        val n = target.toInt(); val one = n % 10 == 1 && n % 100 != 11
        val amount = when (metric) {
            dev.notai.tracker.GoalMetric.DURATION_MS -> duration(target)
            dev.notai.tracker.GoalMetric.SESSION_COUNT -> "$n " + if (one) "раза" else "раз"
            dev.notai.tracker.GoalMetric.DISTINCT_RECORDED_DAYS -> "$n " + if (one) "дня" else "дней"
        }
        return (if (direction == dev.notai.tracker.GoalDirection.MINIMUM) "от " else "не больше ") + amount + per
    }

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
        ErrorCode.STORAGE -> "Ошибка хранения. Проверьте текущее состояние перед повтором."
        ErrorCode.UNSUPPORTED_IMPORT -> "Этот файл не подходит: ${e.message}"
        ErrorCode.INVALID_INPUT -> "Не получилось: ${e.message}"
        null -> "Не получилось: ${e.message ?: e.javaClass.simpleName}"
    }

    /** A CSV problem in plain words, with the row when there is one. The English diagnostic stays for logs only. */
    fun importIssue(i: ImportIssue): String {
        val col = (i.arguments["column"] as? ErrorArgument.Text)?.value
        val what = when (i.code) {
            ImportIssueCode.TIMESTAMP_INVALID, ImportIssueCode.TIMESTAMP_PATTERN_INVALID -> "дата или время не читаются в выбранном формате" + (col?.let { " (колонка «$it»)" } ?: "")
            ImportIssueCode.EXPLICIT_OFFSET_REQUIRED -> "у времени нет часового пояса: выбери формат без пояса"
            ImportIssueCode.TIMESTAMP_RANGE, ImportIssueCode.INTERVAL_OVERFLOW -> "время вне допустимого диапазона"
            ImportIssueCode.INTERVAL_INVALID -> "конец раньше начала"
            ImportIssueCode.END_OR_DURATION_REQUIRED -> "нужна колонка конца или длительности"
            ImportIssueCode.DURATION_ENDPOINT_MISMATCH -> "длительность не совпадает с началом и концом"
            ImportIssueCode.DURATION_HMS_REQUIRED, ImportIssueCode.DURATION_COMPONENT_RANGE, ImportIssueCode.DURATION_NUMBER_INVALID,
            ImportIssueCode.DURATION_PRECISION_OR_RANGE -> "длительность не читается в выбранном формате"
            ImportIssueCode.DURATION_POSITIVE_REQUIRED -> "длительность должна быть больше нуля"
            ImportIssueCode.LOCAL_TIME_GAP_OR_FOLD, ImportIssueCode.LOCAL_ZONE_DST_REJECTED -> "время попадает на перевод часов"
            ImportIssueCode.FUTURE_INTERVAL -> "запись в будущем"
            ImportIssueCode.ROW_COLUMN_COUNT -> "в строке другое число колонок"
            ImportIssueCode.CSV_COLUMNS_MISSING -> "в файле нет колонки" + (col?.let { " «$it»" } ?: "")
            ImportIssueCode.SOURCE_ID_CONFLICT, ImportIssueCode.SOURCE_ID_MISSING -> "повторяется или пропущен ID строки"
            ImportIssueCode.CSV_ROW_LIMIT, ImportIssueCode.CSV_COLUMN_LIMIT, ImportIssueCode.CSV_SIZE_LIMIT -> "файл слишком большой"
            ImportIssueCode.CSV_QUOTING_INVALID, ImportIssueCode.CSV_QUOTE_UNCLOSED -> "кавычки в строке не закрыты"
            ImportIssueCode.BATCH_VALIDATION_FAILED -> "записи пересекаются друг с другом или с историей"
            ImportIssueCode.APPLY_STALE -> "данные успели измениться: проверь ещё раз"
            else -> i.diagnostic.ifBlank { i.code.name.lowercase() }
        }
        return (i.rowNumber?.let { "Строка $it: " } ?: "") + what
    }
    fun importError(e: Throwable): String = (e as? CoreError)?.importIssue?.let(::importIssue) ?: error(e)

    /** One plain sentence per automatic-copy problem; the action next to it says what to do. */
    fun backupProblem(e: dev.notai.tracker.android.AutomaticBackupError): String = when (e) {
        dev.notai.tracker.android.AutomaticBackupError.REQUIRES_SELECTION -> "Нужно выбрать папку заново"
        dev.notai.tracker.android.AutomaticBackupError.UNSUPPORTED -> "В эту папку нельзя сохранять копии. Выбери другую"
        dev.notai.tracker.android.AutomaticBackupError.GRANT_REVOKED -> "Доступ к папке пропал. Выбери её снова"
        dev.notai.tracker.android.AutomaticBackupError.PROVIDER_UNAVAILABLE -> "Папка сейчас недоступна: нет сети или приложение облака не отвечает. Повторю сам"
        dev.notai.tracker.android.AutomaticBackupError.READBACK_UNAVAILABLE -> "Не удалось перечитать записанную копию. Повторю сам"
        dev.notai.tracker.android.AutomaticBackupError.CORRUPT_READBACK -> "Записанная копия не совпала с данными. Повторю сам"
        dev.notai.tracker.android.AutomaticBackupError.INTERRUPTED -> "Копия прервалась. Продолжу сам"
        dev.notai.tracker.android.AutomaticBackupError.CREATION_UNCERTAIN -> "Неясно, появился ли файл в папке. Повтори или пропусти эту копию"
        dev.notai.tracker.android.AutomaticBackupError.RETENTION_FAILED -> "Новая копия сохранена, а старые убрать не вышло"
        dev.notai.tracker.android.AutomaticBackupError.SCHEDULING_FAILED -> "Телефон не дал запланировать копию. Нажми «Сохранить сейчас»"
        dev.notai.tracker.android.AutomaticBackupError.STORAGE -> "Не хватило места или памяти на телефоне. Повторю сам"
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
        "area.language_practice", "area.movement", "area.learning", "area.work", "area.reading", "area.writing", "area.media", "area.games",
        "area.creative", "area.leisure", "area.outdoor_leisure", "area.social", "area.household", "area.self_care", "area.food", "area.sleep",
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
    val name = title ?: "Без занятия"
    return Label(name, (details ?: rootName)?.takeUnless { repeats(name, it) }, Areas.slot(root), title == null && c.cardId == null, glyph(c))
}

/** "Силовая тренировка" + "тренировка и спорт" says the same twice: the quiet detail is dropped when it repeats a word of the title. */
internal fun repeats(title: String, detail: String): Boolean {
    fun stems(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 }.map { it.take(5) }.toSet()
    val t = stems(title)
    return t.isNotEmpty() && stems(detail).any { it in t }
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
