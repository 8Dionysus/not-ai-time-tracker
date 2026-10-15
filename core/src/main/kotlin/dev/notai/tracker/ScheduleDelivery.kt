package dev.notai.tracker

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

@Serializable data class ScheduleReminderTicket(val datasetId: String,val controlBarrier: String,val key: OccurrenceKey,
    val seriesRevision: Long,val overrideRevision: Long?,val zone: String,val at: Long) {
    val identity get() = payloadHash(datasetId to key)
}
@Serializable data class ScheduleAlarmBatch(val at: Long,val tickets: List<ScheduleReminderTicket>) {
    val hash get() = payloadHash(this)
}
@Serializable enum class ScheduleAttemptStatus { RESERVED, POSTED, NOT_POSTED, UNKNOWN }
/** POSTED means the platform accepted the post, not that the OS/user displayed it. RESERVED after
 * a crash is ambiguous and is never replayed automatically. Receipt identity survives MOVE/edit. */
@Serializable data class ScheduleReminderAttempt(val ticket: ScheduleReminderTicket,val attemptedAt: Long,val status: ScheduleAttemptStatus)
@Serializable data class ScheduleDeliveryState(@Required val format: Int = 1,val revision: Long = 0,
    val pending: ScheduleAlarmBatch? = null,val attempts: List<ScheduleReminderAttempt> = emptyList())
enum class ScheduleDeliveryBlocker { PERMISSION, CHANNEL, RENDERER_UNAVAILABLE, PLATFORM_UNAVAILABLE }
enum class ScheduleQuietMode { UNKNOWN, ALL, PRIORITY, ALARMS_ONLY, NONE }
data class ScheduleDeliveryAvailability(val blockers: Set<ScheduleDeliveryBlocker> = emptySet(),val quietMode: ScheduleQuietMode = ScheduleQuietMode.UNKNOWN) {
    val ready get() = blockers.isEmpty()
}
/** Instances for the same private state file MUST share this gate. No portable Dataset mutations. */
interface ScheduleDeliveryStore {
    fun <T> locked(block: ()->T): T
    fun read(): ScheduleDeliveryState
    fun writeDurably(state: ScheduleDeliveryState)
}
interface ScheduleDeliveryPlatform {
    fun availability(): ScheduleDeliveryAvailability
    /** One replaceable platform alarm for the entire next tie batch, no exact-alarm permission. */
    fun schedule(batch: ScheduleAlarmBatch)
    fun cancelAlarm()
    fun post(ticket: ScheduleReminderTicket,projection: ScheduleProjection): Boolean
    fun visible(): List<ScheduleReminderTicket>
    fun cancelNotice(ticket: ScheduleReminderTicket)
    /** Optional platform boot/time-change hooks; enabled only while an opted-in alarm is usable. */
    fun configureWakeups(enabled: Boolean) {}
}
enum class ScheduleDeliveryOutcome { SYNCHRONIZED, STALE_CALLBACK, EARLY_CALLBACK, CALLBACK_HANDLED }
data class ScheduleDeliveryReport(val sourceDatasetId: String,val sourceBarrier: String,val sourceSchedule: ScheduleData,
    val deviceZone: String,val availability: ScheduleDeliveryAvailability,val next: ScheduleAlarmBatch?,val alarmAccepted: Boolean,
    val outcome: ScheduleDeliveryOutcome,val attempted: List<ScheduleReminderAttempt> = emptyList())

/** Schedule-specific best-effort delivery. All effects use a fresh Tracker read under one shared
 * delivery gate. No starts, completion facts, second timer, polling loop or history backfill. */
class ScheduleDelivery(private val tracker: Tracker,private val store: ScheduleDeliveryStore,
    private val platform: ScheduleDeliveryPlatform,private val deviceZone: ()->ZoneId) {
    private fun wallNow() = tracker.clock.now().also(::validateMoment).wall
    private fun state(): ScheduleDeliveryState = store.read().also { s ->
        ensure(s.format == 1 && s.revision >= 0,"Invalid schedule delivery state",ErrorCode.STORAGE)
        ensure(s.attempts.map { it.ticket.identity }.distinct().size == s.attempts.size,"Duplicate reminder receipt",ErrorCode.STORAGE)
        s.pending?.let { b -> ensure(b.tickets.isNotEmpty() && b.tickets.all { it.at == b.at } && b.tickets.map { it.identity }.distinct().size == b.tickets.size,"Invalid alarm batch",ErrorCode.STORAGE) }
        for(t in s.attempts.map { it.ticket }+s.pending?.tickets.orEmpty()) {
            ensure(t.datasetId.isNotBlank() && t.key.seriesId.isNotBlank() && t.seriesRevision > 0 &&
                (t.overrideRevision == null || t.overrideRevision > 0),"Invalid reminder identity",ErrorCode.STORAGE)
            try { scheduleDate(t.key.originalDate);scheduleZone(t.zone);Instant.ofEpochMilli(t.at) }
            catch(_: Exception) { fail(ErrorCode.STORAGE,"Invalid reminder ticket") }
        }
        ensure(s.attempts.all { it.attemptedAt in 0..MAX_TIME },"Invalid reminder receipt time",ErrorCode.STORAGE)
    }
    private fun write(old: ScheduleDeliveryState,next: ScheduleDeliveryState): ScheduleDeliveryState {
        if(old == next) return old
        return next.copy(revision = Math.addExact(old.revision,1)).also(store::writeDurably)
    }
    private fun ticket(data: Dataset,p: ScheduleProjection) = ScheduleReminderTicket(data.datasetId,data.controlBarrier,p.key,
        p.seriesRevision,p.overrideRevision,p.zone,p.reminderInstant!!.toEpochMilli())
    private fun matching(data: Dataset,t: ScheduleReminderTicket,now: Instant,zone: ZoneId): ScheduleProjection? {
        if(data.datasetId != t.datasetId || data.controlBarrier != t.controlBarrier) return null
        return Schedules.occurrence(data,t.key,now,zone)?.takeIf { it.reminderInstant != null && ticket(data,it) == t }
    }
    fun synchronize(): ScheduleDeliveryReport = storage { store.locked { synchronizeLocked() } }
    fun attempts(): List<ScheduleReminderAttempt> = storage { store.locked {
        frozenList(state().attempts.map { if(it.status == ScheduleAttemptStatus.RESERVED) it.copy(status = ScheduleAttemptStatus.UNKNOWN) else it })
    } }
    private fun synchronizeLocked(outcome: ScheduleDeliveryOutcome = ScheduleDeliveryOutcome.SYNCHRONIZED,
        attempted: List<ScheduleReminderAttempt> = emptyList()): ScheduleDeliveryReport {
        val data = tracker.data();val now = Instant.ofEpochMilli(wallNow());val zone = deviceZone()
        val current = state();val used = current.attempts.map { it.ticket.identity }.toSet()
        val retained = current.pending?.tickets.orEmpty().filter { it.identity !in used && matching(data,it,now,zone) != null }
        val excluded = current.attempts.filter { it.ticket.datasetId == data.datasetId }.map { it.ticket.key }.toSet()
        val future = Schedules.nextReminders(data,now,zone,excluded).map { ticket(data,it) }
        fun batch(tickets: List<ScheduleReminderTicket>): ScheduleAlarmBatch? {
            val at = tickets.minOfOrNull { it.at } ?: return null
            return ScheduleAlarmBatch(at,frozenList(tickets.filter { it.at == at }.distinctBy { it.identity }.sortedBy { it.identity }))
        }
        val next = batch(retained+future);var available = platform.availability()
        // A never-armed historical occurrence is not manufactured on startup. Preserve only a
        // previously durable pending intent while delivery is unavailable; plan/preferences stay.
        val durable = if(available.ready) next else batch(retained)
        write(current,current.copy(pending = durable))
        var accepted = false
        try {
            platform.visible().filter { !available.ready || matching(data,it,now,zone) == null }.forEach(platform::cancelNotice)
            platform.configureWakeups(available.ready && next != null)
            platform.cancelAlarm()
            if(available.ready && next != null) { platform.schedule(next);accepted = true }
        } catch(e: Exception) {
            if(e is kotlinx.coroutines.CancellationException) throw e
            available = available.copy(blockers = available.blockers+ScheduleDeliveryBlocker.PLATFORM_UNAVAILABLE)
        }
        return ScheduleDeliveryReport(data.datasetId,data.controlBarrier,data.schedule,zone.id,available,next,accepted,outcome,frozenList(attempted))
    }
    /** A callback hash is a wake-up, not authorization. Each item rechecks current opt-in, rule,
     * exception, link, zone, control barrier and capability immediately before its durable reserve. */
    fun onAlarm(expectedBatchHash: String): ScheduleDeliveryReport = storage { store.locked {
        var current = state();val pending = current.pending
        if(pending == null || pending.hash != expectedBatchHash) return@locked synchronizeLocked(ScheduleDeliveryOutcome.STALE_CALLBACK)
        val dataBefore = tracker.data();val nowBefore = Instant.ofEpochMilli(wallNow());val zoneBefore = deviceZone()
        if(pending.tickets.none { t -> current.attempts.none { it.ticket.identity == t.identity } && matching(dataBefore,t,nowBefore,zoneBefore) != null })
            return@locked synchronizeLocked(ScheduleDeliveryOutcome.STALE_CALLBACK)
        if(wallNow() < pending.at) return@locked synchronizeLocked(ScheduleDeliveryOutcome.EARLY_CALLBACK)
        val attempted = mutableListOf<ScheduleReminderAttempt>()
        for(t in pending.tickets) {
            current = state()
            if(current.attempts.any { it.ticket.identity == t.identity }) continue
            val data = tracker.data();val now = Instant.ofEpochMilli(wallNow());val zone = deviceZone()
            matching(data,t,now,zone) ?: continue
            if(now.toEpochMilli() < t.at || !platform.availability().ready) continue
            val reserved = ScheduleReminderAttempt(t,now.toEpochMilli(),ScheduleAttemptStatus.RESERVED)
            current = write(current,current.copy(attempts = current.attempts+reserved)) // Must be durable BEFORE any external post.
            val status = try {
                val freshNow = Instant.ofEpochMilli(wallNow())
                val fresh = matching(tracker.data(),t,freshNow,deviceZone())
                if(fresh == null || freshNow.toEpochMilli() < t.at || !platform.availability().ready) ScheduleAttemptStatus.NOT_POSTED
                else if(platform.post(t,fresh)) ScheduleAttemptStatus.POSTED else ScheduleAttemptStatus.NOT_POSTED
            }
                catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e;ScheduleAttemptStatus.UNKNOWN }
            val result = reserved.copy(status = status)
            current = state() // A platform/renderer callback must not overwrite another durable receipt.
            current = write(current,current.copy(attempts = current.attempts.map { if(it.ticket.identity == t.identity) result else it }))
            attempted += result
        }
        synchronizeLocked(ScheduleDeliveryOutcome.CALLBACK_HANDLED,attempted)
    } }
}
