package dev.notai.tracker.android.ui


import dev.notai.tracker.android.R
import dev.notai.tracker.android.L
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
    /** Patterns follow the chosen language: «вс, 25 октября», «Sun, October 25», «10月25日(日)». */
    private fun pattern(skeleton: String, russian: String): DateTimeFormatter {
        val locale = dev.notai.tracker.android.L.locale
        val p = if (locale.language == "ru") russian else android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
        return DateTimeFormatter.ofPattern(p, locale)
    }
    private val dayFormat get() = pattern("EEEdMMMM", "EEE, d MMMM")
    private val shortDay get() = pattern("EEEd", "EEE d")
    private val dayMonth get() = pattern("dMMMM", "d MMMM")

    fun duration(ms: Long): String {
        if (ms <= 0L) return L.s(R.string.t_d6140e7bc5)
        if (ms < 60_000L) return L.s(R.string.t_d176283db1, maxOf(1L, ms / 1000))
        val minutes = ms / 60_000L // floor, like the timer face, so a stopped entry matches what was on screen
        val h = minutes / 60; val m = minutes % 60
        return when { h > 0 && m == 0L -> L.s(R.string.t_ec3bd8ce7a, h); h > 0 -> L.s(R.string.t_60e27c596a, h, m.toString().padStart(2, '0')); else -> L.s(R.string.t_455a50db4a, m) }
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
        today -> L.s(R.string.t_5d5d2ea20a)
        today.minusDays(1) -> L.s(R.string.t_51403c82de)
        else -> date.format(dayFormat)
    }
    fun headerDate(date: LocalDate): String = date.format(dayFormat)
    fun shortDay(date: LocalDate): String = date.format(shortDay)
    fun dayMonth(date: LocalDate): String = date.format(dayMonth)
    fun period(first: LocalDate, last: LocalDate): String =
        if (first.month == last.month && dev.notai.tracker.android.L.locale.language == "ru") "${first.dayOfMonth}–${last.format(dayMonth)}"
        else "${first.format(dayMonth)} – ${last.format(dayMonth)}"

    /** Monday first, 1..7 like java.time. */
    val weekdays get() = listOf(L.s(R.string.t_90078dfa30), L.s(R.string.t_5123c9c552), L.s(R.string.t_161fe56e51), L.s(R.string.t_7dfbff730f), L.s(R.string.t_e5ff32f6ae), L.s(R.string.t_1013a729a6), L.s(R.string.t_99dbea8209))
    fun planRule(days: Set<Int>, date: LocalDate?, time: java.time.LocalTime?): String {
        val `when` = when {
            date != null -> date.format(dayMonth)
            days.size == 7 -> L.s(R.string.t_6fb5a32ea5)
            days == setOf(1, 2, 3, 4, 5) -> L.s(R.string.t_2ea9f562ed)
            days == setOf(6, 7) -> L.s(R.string.t_c2fcd7ada4)
            else -> days.sorted().joinToString(", ") { weekdays[it - 1] }
        }
        return `when` + (time?.let { ", ${it.hour}:${it.minute.toString().padStart(2, '0')}" } ?: "")
    }

    fun goalAmount(metric: dev.notai.tracker.GoalMetric, x: Long): String = when (metric) {
        dev.notai.tracker.GoalMetric.DURATION_MS -> duration(x)
        dev.notai.tracker.GoalMetric.SESSION_COUNT -> L.p(R.plurals.p_299205cb6b, x.toInt())
        dev.notai.tracker.GoalMetric.DISTINCT_RECORDED_DAYS -> L.p(R.plurals.p_5e0f6cbad4, x.toInt())
    }
    fun goalRule(metric: dev.notai.tracker.GoalMetric, direction: dev.notai.tracker.GoalDirection, period: dev.notai.tracker.GoalPeriod, target: Long): String {
        // "от"/"не больше" take the genitive: от 1 раза, от 3 раз, не больше 2 дней.
        val n = target.toInt()
        val amount = when (metric) {
            dev.notai.tracker.GoalMetric.DURATION_MS -> duration(target)
            dev.notai.tracker.GoalMetric.SESSION_COUNT -> L.p(R.plurals.p_7504653931, n)
            dev.notai.tracker.GoalMetric.DISTINCT_RECORDED_DAYS -> L.p(R.plurals.p_032a085f42, n)
        }
        val min = direction == dev.notai.tracker.GoalDirection.MINIMUM
        return when (period) {
            dev.notai.tracker.GoalPeriod.DAY -> if (min) L.s(R.string.t_6d7bb5e932, amount) else L.s(R.string.t_afa3b81f25, amount)
            dev.notai.tracker.GoalPeriod.WEEK -> if (min) L.s(R.string.t_db922448f7, amount) else L.s(R.string.t_a5b1429034, amount)
            dev.notai.tracker.GoalPeriod.MONTH -> if (min) L.s(R.string.t_9e4e767ad0, amount) else L.s(R.string.t_ad7d8c2a5f, amount)
            dev.notai.tracker.GoalPeriod.FIXED -> if (min) L.s(R.string.t_2fc6caafc1, amount) else L.s(R.string.t_64eb81c370, amount)
        }
    }

    /** Russian plural: 1 запись, 2 записи, 5 записей. */
    fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10; val m100 = n % 100
        val word = when { m10 == 1 && m100 != 11 -> one; m10 in 2..4 && m100 !in 12..14 -> few; else -> many }
        return "$n $word"
    }

    fun error(e: Throwable): String = when ((e as? CoreError)?.code) {
        ErrorCode.STALE -> L.s(R.string.t_3af83d8850)
        ErrorCode.INVALID_INTERVAL -> L.s(R.string.t_2d5a644e5c)
        ErrorCode.OVERLAP -> L.s(R.string.t_3179b46b9d)
        ErrorCode.RECOVERY_REQUIRED -> L.s(R.string.t_8af28e7685)
        ErrorCode.STORAGE -> L.s(R.string.t_27113f7a1e)
        ErrorCode.UNSUPPORTED_IMPORT -> L.s(R.string.t_042dbd35f2, e.message)
        ErrorCode.INVALID_INPUT -> L.s(R.string.t_b932f7eea5, e.message)
        null -> L.s(R.string.t_b932f7eea5, e.message ?: e.javaClass.simpleName)
    }

    /** A CSV problem in plain words, with the row when there is one. The English diagnostic stays for logs only. */
    fun importIssue(i: ImportIssue): String {
        val col = (i.arguments["column"] as? ErrorArgument.Text)?.value
        val what = when (i.code) {
            ImportIssueCode.TIMESTAMP_INVALID, ImportIssueCode.TIMESTAMP_PATTERN_INVALID -> L.s(R.string.t_5d762fa56e) + (col?.let { L.s(R.string.t_817dfa0bfc, it) } ?: "")
            ImportIssueCode.EXPLICIT_OFFSET_REQUIRED -> L.s(R.string.t_8f44a068d2)
            ImportIssueCode.TIMESTAMP_RANGE, ImportIssueCode.INTERVAL_OVERFLOW -> L.s(R.string.t_adcdc4ad41)
            ImportIssueCode.INTERVAL_INVALID -> L.s(R.string.t_490aa0241e)
            ImportIssueCode.END_OR_DURATION_REQUIRED -> L.s(R.string.t_e8a76312ff)
            ImportIssueCode.DURATION_ENDPOINT_MISMATCH -> L.s(R.string.t_50a62e5c0f)
            ImportIssueCode.DURATION_HMS_REQUIRED, ImportIssueCode.DURATION_COMPONENT_RANGE, ImportIssueCode.DURATION_NUMBER_INVALID,
            ImportIssueCode.DURATION_PRECISION_OR_RANGE -> L.s(R.string.t_930489be30)
            ImportIssueCode.DURATION_POSITIVE_REQUIRED -> L.s(R.string.t_b6305bf26a)
            ImportIssueCode.LOCAL_TIME_GAP_OR_FOLD, ImportIssueCode.LOCAL_ZONE_DST_REJECTED -> L.s(R.string.t_fc60b6a6e0)
            ImportIssueCode.FUTURE_INTERVAL -> L.s(R.string.t_ace6b66d31)
            ImportIssueCode.ROW_COLUMN_COUNT -> L.s(R.string.t_85d33d64d6)
            ImportIssueCode.CSV_COLUMNS_MISSING -> L.s(R.string.t_64d9c34819) + (col?.let { " «$it»" } ?: "")
            ImportIssueCode.SOURCE_ID_CONFLICT, ImportIssueCode.SOURCE_ID_MISSING -> L.s(R.string.t_57792c3ae8)
            ImportIssueCode.CSV_ROW_LIMIT, ImportIssueCode.CSV_COLUMN_LIMIT, ImportIssueCode.CSV_SIZE_LIMIT -> L.s(R.string.t_83ae5f756d)
            ImportIssueCode.CSV_QUOTING_INVALID, ImportIssueCode.CSV_QUOTE_UNCLOSED -> L.s(R.string.t_c0ed5f4dda)
            ImportIssueCode.BATCH_VALIDATION_FAILED -> L.s(R.string.t_0d8700fb2a)
            ImportIssueCode.APPLY_STALE -> L.s(R.string.t_21a4608d93)
            else -> i.diagnostic.ifBlank { i.code.name.lowercase() }
        }
        return (i.rowNumber?.let { L.s(R.string.t_cabb0c6d9b, it) } ?: "") + what
    }
    fun importError(e: Throwable): String = (e as? CoreError)?.importIssue?.let(::importIssue) ?: error(e)

    /** The core's state line, shown in the chosen language. The core's own wording stays for diagnostics. */
    fun stateLine(s: dev.notai.tracker.Snapshot): String = s.focus?.let(::focusLine) ?: when (s.mode) {
        dev.notai.tracker.Mode.IDLE -> L.s(R.string.t_98e4a9f4dd)
        dev.notai.tracker.Mode.RUNNING -> L.s(R.string.t_18c99fb35b)
        dev.notai.tracker.Mode.PAUSED -> L.s(R.string.t_b71833f660)
        dev.notai.tracker.Mode.RECOVERY -> L.s(R.string.t_ed4b9d4acc)
    }
    fun focusLine(f: dev.notai.tracker.FocusSnapshot): String = when (f.mode) {
        dev.notai.tracker.FocusMode.RECOVERY -> L.s(R.string.t_b1a72b8b91)
        dev.notai.tracker.FocusMode.DUE -> L.s(R.string.t_f2f63c2213)
        dev.notai.tracker.FocusMode.PAUSED -> L.s(R.string.t_52da7c24f6)
        dev.notai.tracker.FocusMode.RUNNING -> if (f.phase == dev.notai.tracker.FocusPhase.WORK) L.s(R.string.t_b97a3fd699) else L.s(R.string.t_b62da03c51)
    }

    /** One plain sentence per automatic-copy problem; the action next to it says what to do. */
    fun backupProblem(e: dev.notai.tracker.android.AutomaticBackupError): String = when (e) {
        dev.notai.tracker.android.AutomaticBackupError.REQUIRES_SELECTION -> L.s(R.string.t_fa633fd3a2)
        dev.notai.tracker.android.AutomaticBackupError.UNSUPPORTED -> L.s(R.string.t_06d08263c2)
        dev.notai.tracker.android.AutomaticBackupError.GRANT_REVOKED -> L.s(R.string.t_8ba7dc1120)
        dev.notai.tracker.android.AutomaticBackupError.PROVIDER_UNAVAILABLE -> L.s(R.string.t_1fbd871837)
        dev.notai.tracker.android.AutomaticBackupError.READBACK_UNAVAILABLE -> L.s(R.string.t_fe63f1e035)
        dev.notai.tracker.android.AutomaticBackupError.CORRUPT_READBACK -> L.s(R.string.t_bd81e0c895)
        dev.notai.tracker.android.AutomaticBackupError.INTERRUPTED -> L.s(R.string.t_b79a6fe480)
        dev.notai.tracker.android.AutomaticBackupError.CREATION_UNCERTAIN -> L.s(R.string.t_50abb9831d)
        dev.notai.tracker.android.AutomaticBackupError.RETENTION_FAILED -> L.s(R.string.t_58847603b8)
        dev.notai.tracker.android.AutomaticBackupError.SCHEDULING_FAILED -> L.s(R.string.t_a083e020a0)
        dev.notai.tracker.android.AutomaticBackupError.STORAGE -> L.s(R.string.t_d1f9b48826)
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
data class Label(val title: String, val detail: String?, val slot: Int?, val unclassified: Boolean, val glyph: Int = ActivityGlyphs.custom,
                 /** Non-null only when detail was supplied by the category, never by the user. */
                 val fallbackRootId: String? = null)

/** The open category already supplies this context. Searches and recents keep it. */
internal fun Label.detailInCategory(rootId: String?): String? =
    detail.takeUnless { rootId != null && fallbackRootId == rootId }

fun Dataset.label(c: Classification): Label {
    val type = c.activityTypeId?.let { id -> types.firstOrNull { it.id == id } }
    val title = dev.notai.tracker.android.CatalogNames.title(this, c) ?: c.name ?: type?.name
    val root = c.ancestorPathAtWrite.firstOrNull() ?: type?.let { rootOf(it.id) }
    val details = c.details.values.filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotEmpty() }
    val rootName = root?.takeIf { it != c.activityTypeId }?.let { r -> types.firstOrNull { it.id == r }?.let { t -> dev.notai.tracker.android.CatalogNames.type(this, t.id, t.name) ?: t.name }?.lowercase(dev.notai.tracker.android.L.locale) }
    val name = title ?: L.s(R.string.t_aff922a637)
    return Label(name, details ?: rootName, Areas.slot(root), title == null && c.cardId == null, glyph(c),
        fallbackRootId = root.takeIf { details == null && rootName != null })
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
