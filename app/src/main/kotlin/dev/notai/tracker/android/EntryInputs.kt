package dev.notai.tracker.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract
import dev.notai.tracker.*

/** Only text explicitly sent by another app; no clipboard, background read or automatic command. */
object ProcessTextDrafts {
    fun read(intent: Intent): EntryDraft? {
        if(intent.action != Intent.ACTION_PROCESS_TEXT) return null
        if(intent.type != "text/plain") throw CoreError(ErrorCode.INVALID_INPUT,"Expected explicitly shared plain text")
        val text = try { intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() }
            catch(_: RuntimeException) { throw CoreError(ErrorCode.INVALID_INPUT,"Selected text is invalid") }
        if(text == null) throw CoreError(ErrorCode.INVALID_INPUT,"Selected text is missing")
        return EntryDrafts.selectedText(text)
    }
}
object CardInputs {
    fun selection(data: Dataset,id: String?): Classification = id?.let { selected ->
        data.cards.singleOrNull { it.id == selected && !it.archived }?.classification()
            ?: throw CoreError(ErrorCode.INVALID_INPUT,"Selected card is missing or archived")
    } ?: Classification()
}

data class CalendarOccurrenceRef(val eventId: Long,val begin: Long,val end: Long) {
    init {
        if(eventId <= 0 || begin < 0 || end <= begin || end > MAX_TIME)
            throw CoreError(ErrorCode.INVALID_INTERVAL,"Choose a concrete calendar occurrence")
    }
}
data class CalendarOccurrence(val reference: CalendarOccurrenceRef,val title: String?,val allDay: Boolean) {
    fun draft(reviewedTitle: String = title.orEmpty(),selection: Classification = Classification()): EntryDraft =
        EntryDrafts.event(reviewedTitle,if(allDay) null else DraftInterval(reference.begin,reference.end),selection,
            "calendar:${reference.eventId}:${reference.begin}:${reference.end}")
}
enum class CalendarReadIssue { DISABLED, PERMISSION_REQUIRED, NOT_FOUND, PROVIDER_UNAVAILABLE, INVALID_OCCURRENCE }
data class CalendarReadResult(val occurrence: CalendarOccurrence? = null,val issue: CalendarReadIssue? = null)
data class CalendarPageCursor(val from: Long,val until: Long,val last: CalendarOccurrenceRef)
data class CalendarPage(val occurrences: List<CalendarOccurrence> = emptyList(),val next: CalendarPageCursor? = null,
    val issue: CalendarReadIssue? = null,val skippedInvalid: Int = 0)

/** Read one user-selected recurring occurrence. Call on IO after an explicit user action.
 * Permission denial/revocation is an adapter result and never changes Tracker state.
 */
class CalendarOccurrences internal constructor(private val permitted: ()->Boolean,
    private val query: (Uri,Array<String>,String,Array<String>,String?)->Cursor?) {
    internal constructor(permitted: ()->Boolean,query: (Uri,Array<String>,String,Array<String>)->Cursor?): this(permitted,
        { uri,columns,where,args,_ -> query(uri,columns,where,args) })
    constructor(context: Context): this(
        { context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED },
        { uri,columns,where,args,order -> context.contentResolver.query(uri,columns,where,args,order) })
    /** Bounded, ordered discovery after explicit opt-in. Occurrences retain their exact dates;
     * all-day records can become text drafts but never inferred work intervals.
     */
    fun list(from: Long,until: Long,enabled: Boolean = false,limit: Int = 30,cursor: CalendarPageCursor? = null): CalendarPage {
        if(!enabled) return CalendarPage(issue = CalendarReadIssue.DISABLED)
        if(!permitted()) return CalendarPage(issue = CalendarReadIssue.PERMISSION_REQUIRED)
        if(from < 0 || until <= from || until > MAX_TIME || until-from > 366L*24*60*60*1000 || limit !in 1..100 ||
            (cursor != null && (cursor.from != from || cursor.until != until)))
            throw CoreError(ErrorCode.INVALID_INPUT,"Choose a bounded calendar range/page")
        val columns = arrayOf(CalendarContract.Instances.EVENT_ID,CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,CalendarContract.Instances.TITLE,CalendarContract.Instances.ALL_DAY)
        val begin = columns[1];val end = columns[2];val id = columns[0]
        val where = "$end > ? AND $begin < ?" + if(cursor == null) "" else
            " AND ($begin > ? OR ($begin = ? AND $id > ?) OR ($begin = ? AND $id = ? AND $end > ?))"
        val args = mutableListOf(from.toString(),until.toString())
        cursor?.last?.let { args += listOf(it.begin.toString(),it.begin.toString(),it.eventId.toString(),it.begin.toString(),it.eventId.toString(),it.end.toString()) }
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().appendPath(from.toString()).appendPath(until.toString()).build()
        return try {
            val result = query(uri,columns,where,args.toTypedArray(),"$begin ASC, $id ASC, $end ASC")
                ?: return CalendarPage(issue = CalendarReadIssue.PROVIDER_UNAVAILABLE)
            result.use { rows ->
                val entries = mutableListOf<CalendarOccurrence>();var invalid = 0;var scanned = 0
                val order = compareBy<CalendarOccurrenceRef> { it.begin }.thenBy { it.eventId }.thenBy { it.end }
                while(entries.size <= limit && scanned < limit*10 && rows.moveToNext()) {
                    scanned++
                    try {
                        val ref = CalendarOccurrenceRef(rows.getLong(0),rows.getLong(1),rows.getLong(2))
                        if(ref.end <= from || ref.begin >= until || (cursor != null && order.compare(ref,cursor.last) <= 0) ||
                            entries.any { it.reference == ref }) { invalid++;continue }
                        if(entries.isNotEmpty() && order.compare(ref,entries.last().reference) < 0)
                            return CalendarPage(issue = CalendarReadIssue.INVALID_OCCURRENCE,skippedInvalid = invalid+1)
                        entries += CalendarOccurrence(ref,if(rows.isNull(3)) null else rows.getString(3),rows.getInt(4)!=0)
                    } catch(_: CoreError) { invalid++ }
                }
                if(entries.size <= limit && scanned == limit*10 && rows.moveToNext())
                    return CalendarPage(issue = CalendarReadIssue.INVALID_OCCURRENCE,skippedInvalid = invalid)
                val shown = entries.take(limit)
                CalendarPage(java.util.Collections.unmodifiableList(shown),
                    if(entries.size > limit) CalendarPageCursor(from,until,shown.last().reference) else null,skippedInvalid = invalid)
            }
        } catch(_: SecurityException) { CalendarPage(issue = CalendarReadIssue.PERMISSION_REQUIRED) }
        catch(_: RuntimeException) { CalendarPage(issue = CalendarReadIssue.PROVIDER_UNAVAILABLE) }
    }
    fun nearest(now: Long,enabled: Boolean = false): CalendarReadResult {
        val page = list(now,minOf(MAX_TIME,now+7L*24*60*60*1000),enabled,limit = 1)
        return CalendarReadResult(page.occurrences.firstOrNull(),page.issue ?: if(page.occurrences.isEmpty()) CalendarReadIssue.NOT_FOUND else null)
    }
    fun readSelected(reference: CalendarOccurrenceRef,enabled: Boolean = false): CalendarReadResult {
        if(!enabled) return CalendarReadResult(issue = CalendarReadIssue.DISABLED)
        if(!permitted()) return CalendarReadResult(issue = CalendarReadIssue.PERMISSION_REQUIRED)
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(reference.begin.toString()).appendPath(reference.end.toString()).build()
        val columns = arrayOf(CalendarContract.Instances.EVENT_ID,CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,CalendarContract.Instances.TITLE,CalendarContract.Instances.ALL_DAY)
        val where = "${columns[0]} = ? AND ${columns[1]} = ? AND ${columns[2]} = ?"
        val args = arrayOf(reference.eventId.toString(),reference.begin.toString(),reference.end.toString())
        return try {
            val cursor = query(uri,columns,where,args,null) ?: return CalendarReadResult(issue = CalendarReadIssue.PROVIDER_UNAVAILABLE)
            cursor.use {
                if(!it.moveToFirst()) return CalendarReadResult(issue = CalendarReadIssue.NOT_FOUND)
                val returned = CalendarOccurrenceRef(it.getLong(0),it.getLong(1),it.getLong(2))
                if(returned != reference || it.count != 1) return CalendarReadResult(issue = CalendarReadIssue.INVALID_OCCURRENCE)
                CalendarReadResult(CalendarOccurrence(returned,if(it.isNull(3)) null else it.getString(3),it.getInt(4)!=0))
            }
        } catch(_: SecurityException) { CalendarReadResult(issue = CalendarReadIssue.PERMISSION_REQUIRED) }
        catch(_: CoreError) { CalendarReadResult(issue = CalendarReadIssue.INVALID_OCCURRENCE) }
        catch(_: IllegalArgumentException) { CalendarReadResult(issue = CalendarReadIssue.PROVIDER_UNAVAILABLE) }
        catch(_: RuntimeException) { CalendarReadResult(issue = CalendarReadIssue.PROVIDER_UNAVAILABLE) }
    }
}
