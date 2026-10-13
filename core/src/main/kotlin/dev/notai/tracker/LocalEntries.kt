package dev.notai.tracker

import java.time.LocalDate

data class CalendarDay(val date: LocalDate,val start: Long,val end: Long,val rows: List<ReportRow>,val recordedMs: Long,val incomplete: Boolean)
/** window is the same Report used by CSV: quantities and unresolved attribution occur once here. */
data class CalendarHistory(val token: Token,val window: Report,val days: List<CalendarDay>)
data class HistoryGroup(val id: String,val selection: Classification,val rows: List<ReportRow>,val recordedMs: Long,val sessionCount: Int)

/** Exact saved variant only. Names alone never imply identity; grouping does not edit/merge history.
 * Notes remain on individual rows. Quantities remain on Report, never copied into groups.
 */
fun Report.groupSimilar(): List<HistoryGroup> = frozenList(rows.distinctBy { it.segmentId }
    .groupBy { classificationKey(it.selection) }.map { (key,items) ->
        HistoryGroup("history:"+sha256(key.toByteArray(Charsets.UTF_8)),items.first().selection,
            frozenList(items.sortedWith(compareBy({it.start},{it.segmentId}))),items.sumExact { it.durationMs },items.map { it.sessionId }.distinct().size)
    }.sortedWith(compareByDescending<HistoryGroup> { it.rows.maxOf { r -> r.end } }.thenBy { it.id }))

data class DraftInterval(val start: Long,val end: Long) {
    init { ensure(start >= 0 && end > start && end <= MAX_TIME,"Invalid proposed interval",ErrorCode.INVALID_INTERVAL) }
}
enum class DraftSource { SELECTED_TEXT, CALENDAR_EVENT }
/** Ephemeral form data, not a stored event or Session. Editing/discarding never writes history.
 * No time parsing, name matching, completion inference or external-provider access.
 */
data class EntryDraft(val selection: Classification,val note: String? = null,val interval: DraftInterval? = null,
    val source: DraftSource,val sourceReference: String? = null)

/** Shared command boundary: null deliberately removes a note; blank strings are rejected.
 * Opaque event references must be nonblank, bounded and free of control characters.
 */
internal fun EntryDraft.validateForApply() {
    note?.let { ensure(it.isNotBlank() && it.length <= 16384,"Draft note must be null or contain 1..16384 characters") }
    sourceReference?.let {
        ensure(source == DraftSource.CALENDAR_EVENT,"Selected text cannot carry an event reference")
        ensure(it.isNotBlank() && it.length <= 1024 && it.none(Char::isISOControl),"Invalid event reference: expected 1..1024 characters without controls")
    }
}

object EntryDrafts {
    /** Keep text intact for review, including newlines. Caller explicitly selects classification. */
    fun selectedText(text: String,selection: Classification = Classification()): EntryDraft {
        checkText(text)
        return EntryDraft(selection.frozen(),text,source = DraftSource.SELECTED_TEXT)
    }
    /** A provider adapter passes an occurrence, not a recurring-series rule. Null interval is valid
     * for all-day/unknown time; recordDraft then requires an explicitly reviewed timed interval.
     * reference is opaque review context only, not fetched, exported or used for deduplication.
     */
    fun event(title: String,interval: DraftInterval? = null,selection: Classification = Classification(),reference: String? = null): EntryDraft {
        checkText(title)
        return EntryDraft(selection.frozen(),title,interval,DraftSource.CALENDAR_EVENT,reference).also { it.validateForApply() }
    }
    private fun checkText(text: String) { ensure(text.isNotBlank() && text.length <= 16384,"Input must contain 1..16384 characters") }
}
