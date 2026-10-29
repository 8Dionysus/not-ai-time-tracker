package dev.notai.tracker.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.notai.tracker.*
import dev.notai.tracker.android.ui.label
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Serializable

/** One open occurrence of today's plan as every surface shows it. */
data class PlannedChoice(val key: OccurrenceKey,val seriesRevision: Long,val overrideRevision: Long?,val title: String,val glyph: Int,val slot: Int?,val time: String?)
@Serializable internal data class PlanTap(val key: OccurrenceKey,val seriesRevision: Long,val overrideRevision: Long?,val token: Token)

/**
 * Today's plan for the app, the shade and the widget. Showing it writes nothing; a tap is an ordinary Start linked to the
 * occurrence, made with the token (and, while tracking, the timer target) that was on screen, so a late tap is refused.
 */
object PlanSurfaces {
    fun open(view: TrackerView,zone: ZoneId = ZoneId.systemDefault()): List<PlannedChoice> {
        if(view.data.schedule.series.none { it.state == ScheduleState.ACTIVE }) return emptyList()
        val state = runCatching { view.scheduleState(zone) }.getOrNull() ?: return emptyList()
        return state.occurrences.filter { it.date == state.day.date && it.state in setOf(OccurrenceState.TODAY,OccurrenceState.MOVED) }.map { p ->
            val l = view.data.label(p.selection)
            PlannedChoice(p.key,p.seriesRevision,p.overrideRevision,l.title,l.glyph,l.slot,p.localTime?.let { "${it.hour}:${it.minute.toString().padStart(2,'0')}" })
        }
    }

    /** One atomic Start (idle) or Switch (tracking) with the token that was on screen: a late tap is refused, never re-targeted. */
    fun start(tracker: Tracker,workspace: ScheduleWorkspace?,key: OccurrenceKey,seriesRevision: Long,overrideRevision: Long?,
              shown: Token,zone: ZoneId = ZoneId.systemDefault()): ScheduleCommandResult {
        val data = tracker.data()
        val now = Instant.ofEpochMilli(tracker.clock.now().wall)
        val today = tracker.scheduleDay(zone).date
        val current = Schedules.occurrence(data,key,now,zone)
        // A shade or widget can outlive its day: yesterday's chip must not start today.
        ensure(current != null && current.date == today && current.state in setOf(OccurrenceState.TODAY,OccurrenceState.MOVED) &&
            current.seriesRevision == seriesRevision && current.overrideRevision == overrideRevision,"Plan changed",ErrorCode.STALE)
        val command = ScheduleCommand(newId(),shown,ScheduleAction.START_OR_SWITCH,key.seriesId,seriesRevision,
            occurrence = key,expectedOverrideRevision = overrideRevision,deviceZone = zone.id)
        if(workspace == null) return tracker.scheduleCommand(command)
        val draft = workspace.prepare(command)
        return workspace.execute(draft.id,draft.revision)
    }

    private const val TAP = "tap"
    fun pending(c: Context,choice: PlannedChoice,snapshot: Snapshot,index: Int): PendingIntent = pending(c,choice,snapshot.token,index)
    /** Extras do not take part in PendingIntent identity, and the shade or widget may still show an older rendering. So the
     * whole tap (occurrence, revisions and the token on screen) lives in the data URI: a newer rendering gets its own
     * PendingIntent and can never rewrite what an older, still visible button sends. */
    internal fun pending(c: Context,choice: PlannedChoice,token: Token,index: Int): PendingIntent {
        val tap = PlanTap(choice.key,choice.seriesRevision,choice.overrideRevision,token)
        val intent = Intent(c,PlanStartReceiver::class.java).setAction(ACTION)
            .setData(Uri.Builder().scheme("notai").authority("plan").appendPath(index.toString())
                .appendQueryParameter(TAP,codec.encodeToString(tap)).build())
        return PendingIntent.getBroadcast(c,0,intent,PendingIntent.FLAG_IMMUTABLE)
    }
    private const val ACTION = "dev.notai.tracker.PLAN_START"
    internal fun parse(intent: Intent): PlanTap? = intent.takeIf { it.action == ACTION }?.data?.getQueryParameter(TAP)?.takeIf { it.length <= 8192 }
        ?.let { runCatching { codec.decodeFromString<PlanTap>(it) }.getOrNull() }

    /** Each enabled surface keeps its own boundary subscription, independent of widget placement. */
    fun install(context: Context) {
        val app = context.applicationContext
        ScheduleProjectionBinding.install(listOf(object: ScheduleProjectionConsumer {
            override val id = "timer-widget-plan"
            override val surface = ScheduleDataSurface.WIDGET
            override fun available(context: Context) = true
            override fun invalidate(value: ScheduleSurfaceState,projectionKey: String) {
                runBlocking { TimerSurfaces.refresh(app,TrackerRuntime.get(app).tracker,forceWidget = true) }
            }
        },object: ScheduleProjectionConsumer {
            override val id = "timer-notification-plan"
            override val surface = ScheduleDataSurface.TIMER_NOTIFICATION
            override fun available(context: Context) = true
            override fun invalidate(value: ScheduleSurfaceState,projectionKey: String) {
                runBlocking { TimerSurfaces.refresh(app,TrackerRuntime.get(app).tracker) }
            }
        }))
    }
}

class PlanStartReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val tap = PlanSurfaces.parse(intent) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                if(!DatabaseFiles.exists(context)) return@launch
                val runtime = TrackerRuntime.get(context)
                try { PlanSurfaces.start(runtime.tracker,runtime.schedule,tap.key,tap.seriesRevision,tap.overrideRevision,tap.token) }
                catch(e: CoreError) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(context,L.s(R.string.t_b1dd9c744d),android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                TimerSurfaces.refresh(context,runtime.tracker)
            } catch(e: Exception) {
                if(e is CancellationException) throw e
                android.util.Log.w("PlanSurfaces","Planned start unavailable; history unchanged")
            } finally { pending.finish() }
        }
    }
}
