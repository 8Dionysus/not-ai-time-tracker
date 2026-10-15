package dev.notai.tracker

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.Required
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class ScheduleReminderTicket(val datasetId: String,val controlBarrier: String,val key: OccurrenceKey,
    val seriesRevision: Long,val overrideRevision: Long?,val zone: String,val at: Long) {
    // Derived only from immutable constructor values. Not a supplied/stored authority; copies
    // and cold decode compute their own value. Avoid hashing the same receipt on every scan.
    @kotlinx.serialization.Transient @Volatile private var cachedIdentity: String? = null
    val identity get() = cachedIdentity ?: payloadHash(datasetId to key).also { cachedIdentity = it }
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
/** Operational file representation only. Reads old pretty JSON; no receipt/field is removed and
 * ticket/batch canonical hashes continue to use payloadHash's existing codec. No startup rewrite. */
object ScheduleDeliveryJson {
    private val json = Json(codec) { prettyPrint = false }
    fun encode(state: ScheduleDeliveryState): String = json.encodeToString(state)
    fun decode(text: String): ScheduleDeliveryState = json.decodeFromString(text)
}
enum class ScheduleDeliveryBlocker { PERMISSION, CHANNEL, RENDERER_UNAVAILABLE, PLATFORM_UNAVAILABLE }
enum class ScheduleQuietMode { UNKNOWN, ALL, PRIORITY, ALARMS_ONLY, NONE }
data class ScheduleDeliveryAvailability(val blockers: Set<ScheduleDeliveryBlocker> = emptySet(),val quietMode: ScheduleQuietMode = ScheduleQuietMode.UNKNOWN) {
    val ready get() = blockers.isEmpty()
}
/** Instances for the same private state file MUST share this gate. No portable Dataset mutations. */
data class ScheduleAttemptPage(val attempts: List<ScheduleReminderAttempt>,val nextAfterSequence: Long?)
/** Legacy implementations may use the snapshot defaults. Production indexed stores override
 * these operations: runtime control/lookup/reserve/finish never enumerate lifetime receipts. */
interface ScheduleDeliveryStore {
    fun <T> locked(block: ()->T): T
    fun read(): ScheduleDeliveryState
    fun writeDurably(state: ScheduleDeliveryState)
    fun control(): ScheduleDeliveryState = read().also(::validateScheduleDeliveryState).copy(attempts = emptyList())
    fun find(datasetId: String,key: OccurrenceKey): ScheduleReminderAttempt? = read().also(::validateScheduleDeliveryState).attempts
        .singleOrNull { it.ticket.datasetId == datasetId && it.ticket.key == key }
    fun setPending(expected: ScheduleDeliveryState,pending: ScheduleAlarmBatch?) {
        val current = read().also(::validateScheduleDeliveryState)
        ensure(current.copy(attempts = emptyList()) == expected,"Stale reminder control",ErrorCode.STORAGE)
        if(current.pending != pending) writeDurably(current.copy(revision = Math.addExact(current.revision,1),pending = pending))
    }
    fun reserve(expectedRevision: Long,value: ScheduleReminderAttempt): Boolean {
        val current = read().also(::validateScheduleDeliveryState)
        if(current.attempts.any { it.ticket.datasetId == value.ticket.datasetId && it.ticket.key == value.ticket.key }) return false
        ensure(current.revision == expectedRevision && value.status == ScheduleAttemptStatus.RESERVED,"Stale reminder reserve",ErrorCode.STORAGE)
        writeDurably(current.copy(revision = Math.addExact(current.revision,1),attempts = current.attempts+value));return true
    }
    fun complete(reserved: ScheduleReminderAttempt,result: ScheduleReminderAttempt) {
        val current = read().also(::validateScheduleDeliveryState)
        ensure(result.ticket == reserved.ticket && result.attemptedAt == reserved.attemptedAt && result.status != ScheduleAttemptStatus.RESERVED,
            "Invalid reminder completion",ErrorCode.STORAGE)
        ensure(current.attempts.singleOrNull { it.ticket.identity == reserved.ticket.identity } == reserved,"Changed reminder reservation",ErrorCode.STORAGE)
        writeDurably(current.copy(revision = Math.addExact(current.revision,1),attempts = current.attempts.map { if(it == reserved) result else it }))
    }
    fun page(afterSequence: Long,limit: Int): ScheduleAttemptPage {
        require(afterSequence >= 0 && limit in 1..1024)
        val rows = read().also(::validateScheduleDeliveryState).attempts.drop(afterSequence.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()).take(limit)
        return ScheduleAttemptPage(frozenList(rows),if(rows.size == limit) afterSequence+rows.size else null)
    }
}
/** Prepared content only. Terminal post must be short, must not render, mutate Tracker or
 * reenter delivery, and runs under the StateStore transaction gate after the final match. */
fun interface PreparedScheduleNotice { fun post(): Boolean }
interface ScheduleDeliveryPlatform {
    fun availability(): ScheduleDeliveryAvailability
    /** One replaceable platform alarm for the entire next tie batch, no exact-alarm permission. */
    fun schedule(batch: ScheduleAlarmBatch)
    fun cancelAlarm()
    /** Render outside the domain gate. Returning null is a known refusal, not a post. */
    fun prepare(ticket: ScheduleReminderTicket,projection: ScheduleProjection): PreparedScheduleNotice?
    fun visible(): List<ScheduleReminderTicket>
    fun cancelNotice(ticket: ScheduleReminderTicket)
    /** Optional platform boot/time-change hooks; enabled only while an opted-in alarm is usable. */
    fun configureWakeups(enabled: Boolean) {}
}
enum class ScheduleDeliveryOutcome { SYNCHRONIZED, STALE_CALLBACK, EARLY_CALLBACK, CALLBACK_HANDLED }
data class ScheduleDeliveryReport(val sourceDatasetId: String,val sourceBarrier: String,val sourceSchedule: ScheduleData,
    val deviceZone: String,val availability: ScheduleDeliveryAvailability,val next: ScheduleAlarmBatch?,val alarmAccepted: Boolean,
    val outcome: ScheduleDeliveryOutcome,val attempted: List<ScheduleReminderAttempt> = emptyList(),val sourcePreferences: ScheduleSettings = ScheduleSettings())

/** Schedule-specific best-effort delivery. All effects use a fresh Tracker read under one shared
 * delivery gate. No starts, completion facts, second timer, polling loop or history backfill. */
class ScheduleDelivery(private val tracker: Tracker,private val store: ScheduleDeliveryStore,
    private val platform: ScheduleDeliveryPlatform,private val deviceZone: ()->ZoneId) {
    private fun availability(): ScheduleDeliveryAvailability = try { platform.availability() }
    catch(e: Exception) {
        if(e is kotlinx.coroutines.CancellationException) throw e
        ScheduleDeliveryAvailability(setOf(ScheduleDeliveryBlocker.PLATFORM_UNAVAILABLE))
    }
    private fun wallNow() = tracker.clock.now().also(::validateMoment).wall
    private fun state(): ScheduleDeliveryState = store.control().also(::validateScheduleDeliveryState)
    private fun ticket(data: Dataset,p: ScheduleProjection) = ScheduleReminderTicket(data.datasetId,data.controlBarrier,p.key,
        p.seriesRevision,p.overrideRevision,p.zone,p.reminderInstant!!.toEpochMilli())
    private fun matching(data: Dataset,t: ScheduleReminderTicket,now: Instant,zone: ZoneId): ScheduleProjection? {
        if(data.datasetId != t.datasetId || data.controlBarrier != t.controlBarrier) return null
        return Schedules.occurrence(data,t.key,now,zone)?.takeIf { it.reminderInstant != null && ticket(data,it) == t }
    }
    private fun <T> operational(block: ()->T): T = storage { try { store.locked(block) } catch(e: Exception) {
        runCatching { platform.cancelAlarm() };runCatching { platform.configureWakeups(false) };throw e
    } }
    fun synchronize(): ScheduleDeliveryReport = operational { synchronizeLocked() }
    /** Explicit diagnostic enumeration; runtime synchronization uses indexed lookups instead. */
    fun attempts(): List<ScheduleReminderAttempt> = operational {
        val result = mutableListOf<ScheduleReminderAttempt>();var cursor = 0L
        do { val page = attemptsPageLocked(cursor,1024);result += page.attempts;cursor = page.nextAfterSequence ?: break } while(true)
        frozenList(result)
    }
    fun attemptsPage(afterSequence: Long = 0,limit: Int = 100): ScheduleAttemptPage {
        ensure(afterSequence >= 0 && limit in 1..1024,"Invalid reminder receipt page")
        return operational { attemptsPageLocked(afterSequence,limit) }
    }
    private fun attemptsPageLocked(after: Long,limit: Int): ScheduleAttemptPage = store.page(after,limit).let { page ->
        page.copy(attempts = frozenList(page.attempts.map { if(it.status == ScheduleAttemptStatus.RESERVED) it.copy(status = ScheduleAttemptStatus.UNKNOWN) else it }))
    }
    private fun synchronizeLocked(outcome: ScheduleDeliveryOutcome = ScheduleDeliveryOutcome.SYNCHRONIZED,
        attempted: List<ScheduleReminderAttempt> = emptyList()): ScheduleDeliveryReport {
        val data = tracker.data();val now = Instant.ofEpochMilli(wallNow());val zone = deviceZone()
        val current = state()
        val retained = current.pending?.tickets.orEmpty().filter { store.find(it.datasetId,it.key) == null && matching(data,it,now,zone) != null }
        val future = Schedules.nextReminders(data,now,zone,isExcluded = { store.find(data.datasetId,it) != null }).map { ticket(data,it) }
        fun batch(tickets: List<ScheduleReminderTicket>): ScheduleAlarmBatch? {
            val at = tickets.minOfOrNull { it.at } ?: return null
            return ScheduleAlarmBatch(at,frozenList(tickets.filter { it.at == at }.distinctBy { it.identity }.sortedBy { it.identity }))
        }
        val next = batch(retained+future);var available = availability()
        // A never-armed historical occurrence is not manufactured on startup. Preserve only a
        // previously durable pending intent while delivery is unavailable; plan/preferences stay.
        val durable = if(available.ready) next else batch(retained)
        if(current.pending != durable) store.setPending(current,durable)
        fun effect(block: ()->Unit): Boolean = try { block();true } catch(e: Exception) {
            if(e is kotlinx.coroutines.CancellationException) throw e
            available = available.copy(blockers = available.blockers+ScheduleDeliveryBlocker.PLATFORM_UNAVAILABLE);false
        }
        var visible = emptyList<ScheduleReminderTicket>()
        effect { visible = platform.visible() }
        visible.filter { !available.ready || matching(data,it,now,zone) == null }.forEach { t -> effect { platform.cancelNotice(t) } }
        // Notice inspection/cancellation failure must not skip alarm cancellation or hook disable.
        effect { platform.cancelAlarm() }
        val accepted = available.ready && next != null && effect { platform.schedule(next) }
        effect { platform.configureWakeups(accepted) }
        return ScheduleDeliveryReport(data.datasetId,data.controlBarrier,data.schedule,zone.id,available,next,accepted,outcome,frozenList(attempted),data.settings.schedule)
    }
    /** A callback hash is a wake-up, not authorization. Each item rechecks current opt-in, rule,
     * exception, link, zone, control barrier and capability immediately before its durable reserve. */
    fun onAlarm(expectedBatchHash: String): ScheduleDeliveryReport = operational {
        var current = state();val pending = current.pending
        if(pending == null || pending.hash != expectedBatchHash) return@operational synchronizeLocked(ScheduleDeliveryOutcome.STALE_CALLBACK)
        val dataBefore = tracker.data();val nowBefore = Instant.ofEpochMilli(wallNow());val zoneBefore = deviceZone()
        if(pending.tickets.none { t -> store.find(t.datasetId,t.key) == null && matching(dataBefore,t,nowBefore,zoneBefore) != null })
            return@operational synchronizeLocked(ScheduleDeliveryOutcome.STALE_CALLBACK)
        if(wallNow() < pending.at) return@operational synchronizeLocked(ScheduleDeliveryOutcome.EARLY_CALLBACK)
        val attempted = mutableListOf<ScheduleReminderAttempt>()
        for(t in pending.tickets) {
            current = state()
            if(store.find(t.datasetId,t.key) != null) continue
            val data = tracker.data();val now = Instant.ofEpochMilli(wallNow());val zone = deviceZone()
            matching(data,t,now,zone) ?: continue
            if(now.toEpochMilli() < t.at || !availability().ready) continue
            val reserved = ScheduleReminderAttempt(t,now.toEpochMilli(),ScheduleAttemptStatus.RESERVED)
            if(!store.reserve(current.revision,reserved)) continue // Durable BEFORE external post.
            val status = try {
                val freshNow = Instant.ofEpochMilli(wallNow())
                val fresh = matching(tracker.data(),t,freshNow,deviceZone())
                if(fresh == null || freshNow.toEpochMilli() < t.at || !availability().ready) ScheduleAttemptStatus.NOT_POSTED
                else {
                    val prepared = platform.prepare(t,fresh)
                    if(prepared == null) ScheduleAttemptStatus.NOT_POSTED
                    else tracker.store.checkedTransaction { checked ->
                        val finalNow = Instant.ofEpochMilli(wallNow())
                        val currentProjection = matching(checked.data,t,finalNow,deviceZone())
                        // Same gate as every domain command. Rendering has finished; only the
                        // short terminal platform effect occurs here, with no Dataset write.
                        checked.replace(checked.data) {
                            if(currentProjection != fresh || finalNow.toEpochMilli() < t.at || !availability().ready) ScheduleAttemptStatus.NOT_POSTED
                            else if(prepared.post()) ScheduleAttemptStatus.POSTED else ScheduleAttemptStatus.NOT_POSTED
                        }
                    }
                }
            }
                catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e;ScheduleAttemptStatus.UNKNOWN }
            val result = reserved.copy(status = status)
            store.complete(reserved,result) // Updates this exact reserved row, never another receipt.
            attempted += result
        }
        synchronizeLocked(ScheduleDeliveryOutcome.CALLBACK_HANDLED,attempted)
    }
}
