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

/** Read one user-selected recurring occurrence. Call on IO after an explicit user action.
 * Permission denial/revocation is an adapter result and never changes Tracker state.
 */
class CalendarOccurrences internal constructor(private val permitted: ()->Boolean,
    private val query: (Uri,Array<String>,String,Array<String>)->Cursor?) {
    constructor(context: Context): this(
        { context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED },
        { uri,columns,where,args -> context.contentResolver.query(uri,columns,where,args,null) })
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
            val cursor = query(uri,columns,where,args) ?: return CalendarReadResult(issue = CalendarReadIssue.PROVIDER_UNAVAILABLE)
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
