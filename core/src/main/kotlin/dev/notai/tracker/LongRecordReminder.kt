package dev.notai.tracker

import kotlinx.serialization.Serializable

/** Device-local attention preference. It is neither an OS permission nor recorded activity.
 * Default durations are editable engineering defaults; this feature is OFF on first use. */
@Serializable data class LongRecordPreference(val enabled: Boolean = false,
    val afterMs: Long = 2*60*60_000L,val snoozeMs: Long = 15*60_000L)
@Serializable data class LongRecordTicket(val target: TimerTarget,val segmentId: String,
    val bootId: String,val dueElapsed: Long,val dueDurationMs: Long,val generation: Long) {
    val hash get() = payloadHash(this)
}
@Serializable data class LongRecordAttempt(val ticket: LongRecordTicket,val status: ScheduleAttemptStatus)
/** Bounded operational control, not a second history. Each timer subject has one current cycle.
 * RESERVED survives ambiguous post/save outcomes and must never be automatically replayed. */
@Serializable data class LongRecordControl(val format: Int = 1,val revision: Long = 0,
    val preference: LongRecordPreference = LongRecordPreference(),val generation: Long = 0,
    val target: TimerTarget? = null,val dueDurationMs: Long = 0,val pending: LongRecordTicket? = null,
    val attempt: LongRecordAttempt? = null)
interface LongRecordStore {
    /** All instances addressing the same file share the gate. Writes are atomic and durable. */
    fun <T> locked(block: ()->T): T
    fun read(): LongRecordControl
    fun writeDurably(value: LongRecordControl)
}
fun interface PreparedLongRecordNotice { fun post(): Boolean }
interface LongRecordPlatform {
    fun availability(): ScheduleDeliveryAvailability
    /** One replaceable, inexact elapsed alarm. No polling, exact-alarm permission or service. */
    fun schedule(ticket: LongRecordTicket)
    fun cancelAlarm()
    fun prepare(ticket: LongRecordTicket,snapshot: Snapshot): PreparedLongRecordNotice?
    fun cancelNotice()
}
enum class LongRecordAction { CONTINUE, SNOOZE, STOP, REVIEW, DISABLE }
enum class LongRecordOutcome { SYNCHRONIZED, EARLY, STALE, POST_ACCEPTED, NOT_POSTED, OUTCOME_UNKNOWN, PREVIOUS_ATTEMPT }
data class LongRecordState(val preference: LongRecordPreference,val token: Token,
    val snapshot: Snapshot,val ticket: LongRecordTicket?,val availability: ScheduleDeliveryAvailability,
    val alarmAccepted: Boolean,val outcome: LongRecordOutcome,val attempt: LongRecordAttempt?)
data class LongRecordResponse(val state: LongRecordState,val change: Tracker.Change? = null,
    /** REVIEW explicitly stops now, then opens existing correction preview for this session. */
    val reviewSessionId: String? = null)

/** Ordinary-timer check-in, completely separate from Schedule and Focus completion.
 * Domain commands use existing Tracker token/transactions. Operational preference/receipts
 * are device-local, kept outside portable Dataset/backup (like delivery receipts).
 * A callback is only a wake-up. Rendering is outside the domain gate, terminal post rechecks it.
 */
class LongRecordReminder(private val tracker: Tracker,private val store: LongRecordStore,
    private val platform: LongRecordPlatform) {
    private fun read() = store.read().also(::validateLongRecordControl)
    private fun save(old: LongRecordControl,next: LongRecordControl): LongRecordControl {
        if(old == next) return old
        ensure(read() == old,"Changed check-in control",ErrorCode.STORAGE)
        return next.copy(revision = Math.addExact(old.revision,1)).also {
            validateLongRecordControl(it);store.writeDurably(it)
        }
    }
    private fun availability() = try { platform.availability() } catch(e: Exception) {
        if(e is kotlinx.coroutines.CancellationException) throw e
        ScheduleDeliveryAvailability(setOf(ScheduleDeliveryBlocker.PLATFORM_UNAVAILABLE))
    }
    private fun <T> operational(block: ()->T): T = storage {
        try { store.locked(block) } catch(e: Exception) { runCatching { platform.cancelAlarm() };throw e }
    }
    private fun ordinary(data: Dataset,now: Moment): Snapshot? = snapshot(data,now).takeIf {
        data.focus == null && it.mode == Mode.RUNNING && !it.calendarUncertain && it.active?.anchor != null
    }
    private fun matching(data: Dataset,now: Moment,state: LongRecordControl,ticket: LongRecordTicket): Snapshot? {
        if(!state.preference.enabled || state.pending != ticket || state.generation != ticket.generation) return null
        return ordinary(data,now)?.takeIf { it.timerTarget == ticket.target && it.active?.id == ticket.segmentId &&
            now.bootId == ticket.bootId && state.target == ticket.target && state.dueDurationMs == ticket.dueDurationMs }
    }
    /** Preference is committed only after a current Token check, under the StateStore gate.
     * It is deliberately local to this device; restoring/importing history cannot enable alerts. */
    fun configure(expected: Token,value: LongRecordPreference): LongRecordState = operational {
        validateLongRecordPreference(value)
        tracker.store.checkedTransaction { checked ->
            val now = tracker.clock.now();checkToken(checked.data,expected,now)
            val old = read()
            if(old.preference != value) save(old,LongRecordControl(revision = old.revision,preference = value,
                generation = Math.addExact(old.generation,1)))
            checked.replace(checked.data) { Unit }
        }
        synchronizeLocked()
    }
    fun synchronize(): LongRecordState = operational { synchronizeLocked() }
    private fun synchronizeLocked(outcome: LongRecordOutcome = LongRecordOutcome.SYNCHRONIZED): LongRecordState {
        var control = read();val now = tracker.clock.now().also(::validateMoment)
        val data = tracker.data();val snap = snapshot(data,now);val ordinary = ordinary(data,now)
        val subject = ordinary?.timerTarget.takeIf { control.preference.enabled }
        if(subject != control.target) control = save(control,control.copy(target = subject,
            dueDurationMs = if(subject == null) 0 else maxOf(control.preference.afterMs,ordinary!!.trustedDurationMs),
            generation = Math.addExact(control.generation,1),pending = null,attempt = null))
        if(subject != null && control.pending == null) {
            val remaining = maxOf(0,control.dueDurationMs-ordinary!!.trustedDurationMs)
            val ticket = LongRecordTicket(subject,ordinary.active!!.id,now.bootId,Math.addExact(now.elapsed,remaining),
                control.dueDurationMs,control.generation)
            control = save(control,control.copy(pending = ticket))
        }
        // A timebase loss/reboot cannot turn an old elapsed alarm into an authorization.
        if(control.pending != null && matching(data,now,control,control.pending!!) == null)
            control = save(control,control.copy(pending = null,attempt = null))
        var available = availability()
        fun effect(action: ()->Unit): Boolean = try { action();true } catch(e: Exception) {
            if(e is kotlinx.coroutines.CancellationException) throw e
            available = available.copy(blockers = available.blockers+ScheduleDeliveryBlocker.PLATFORM_UNAVAILABLE);false
        }
        effect { platform.cancelAlarm() }
        if(!available.ready || control.pending == null) effect { platform.cancelNotice() }
        val next = control.pending
        val accepted = available.ready && next != null && control.attempt == null && effect { platform.schedule(next) }
        val observed = control.attempt?.let { if(it.status == ScheduleAttemptStatus.RESERVED) it.copy(status = ScheduleAttemptStatus.UNKNOWN) else it }
        return LongRecordState(control.preference,snap.token,snap,next,available,accepted,outcome,observed)
    }
    fun onAlarm(hash: String): LongRecordState = operational {
        var control = read();val ticket = control.pending
        if(ticket == null || ticket.hash != hash) return@operational synchronizeLocked(LongRecordOutcome.STALE)
        val now = tracker.clock.now().also(::validateMoment)
        if(matching(tracker.data(),now,control,ticket) == null) return@operational synchronizeLocked(LongRecordOutcome.STALE)
        if(control.attempt != null) return@operational synchronizeLocked(LongRecordOutcome.PREVIOUS_ATTEMPT)
        if(now.elapsed < ticket.dueElapsed) return@operational synchronizeLocked(LongRecordOutcome.EARLY)
        if(!availability().ready) return@operational synchronizeLocked(LongRecordOutcome.NOT_POSTED)
        control = save(control,control.copy(attempt = LongRecordAttempt(ticket,ScheduleAttemptStatus.RESERVED)))
        val status = try {
            val freshNow = tracker.clock.now();val fresh = matching(tracker.data(),freshNow,control,ticket)
            val prepared = fresh?.let { platform.prepare(ticket,it) }
            if(prepared == null) ScheduleAttemptStatus.NOT_POSTED else tracker.store.checkedTransaction { checked ->
                val finalNow = tracker.clock.now();val final = matching(checked.data,finalNow,read(),ticket)
                checked.replace(checked.data) {
                    if(final == null || final.token != fresh?.token || finalNow.elapsed < ticket.dueElapsed || final.trustedDurationMs < ticket.dueDurationMs || !availability().ready)
                        ScheduleAttemptStatus.NOT_POSTED
                    else if(prepared.post()) ScheduleAttemptStatus.POSTED else ScheduleAttemptStatus.NOT_POSTED
                }
            }
        } catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e;ScheduleAttemptStatus.UNKNOWN }
        save(control,control.copy(attempt = LongRecordAttempt(ticket,status)))
        synchronizeLocked(when(status) {
            ScheduleAttemptStatus.POSTED -> LongRecordOutcome.POST_ACCEPTED
            ScheduleAttemptStatus.NOT_POSTED -> LongRecordOutcome.NOT_POSTED
            else -> LongRecordOutcome.OUTCOME_UNKNOWN
        })
    }
    /** No notification action may manufacture a correction boundary. Stop means stop NOW.
     * REVIEW explicitly stops NOW and returns the session ID for previewCorrection/applyCorrection.
     * Do not retry a failed response using a freshly substituted Token.
     */
    fun respond(expected: Token,hash: String,action: LongRecordAction): LongRecordResponse = operational {
        var changed: Tracker.Change? = null;var review: String? = null
        val control = read();val ticket = control.pending ?: fail(ErrorCode.STALE,"Check-in no longer current")
        ensure(ticket.hash == hash,"Check-in changed",ErrorCode.STALE)
        if(action == LongRecordAction.STOP || action == LongRecordAction.REVIEW) {
            tracker.store.checkedTransaction { checked ->
                val now = tracker.clock.now();checkToken(checked.data,expected,now)
                ensure(matching(checked.data,now,control,ticket) != null && now.elapsed >= ticket.dueElapsed,"Timer changed or check-in not due",ErrorCode.STALE)
                checked.replace(checked.data) { Unit }
            }
            // Token checks and closeMeasured are already owned by Tracker's one transaction.
            changed = tracker.stop(expected)
            if(action == LongRecordAction.REVIEW) review = ticket.target.sessionId
        } else tracker.store.checkedTransaction { checked ->
            val now = tracker.clock.now();checkToken(checked.data,expected,now)
            val snap = matching(checked.data,now,control,ticket) ?: fail(ErrorCode.STALE,"Timer changed")
            ensure(now.elapsed >= ticket.dueElapsed,"Check-in is not due",ErrorCode.STALE)
            val next = when(action) {
                LongRecordAction.DISABLE -> control.copy(preference = control.preference.copy(enabled = false),target = null,
                    dueDurationMs = 0,pending = null,attempt = null,generation = Math.addExact(control.generation,1))
                else -> control.copy(pending = null,attempt = null,
                    dueDurationMs = Math.addExact(snap.trustedDurationMs,if(action == LongRecordAction.SNOOZE) control.preference.snoozeMs else control.preference.afterMs),
                    generation = Math.addExact(control.generation,1))
            }
            checked.replace(checked.data) { save(control,next);Unit }
        }
        platform.cancelNotice()
        LongRecordResponse(synchronizeLocked(),changed,review)
    }
}
internal fun validateLongRecordPreference(p: LongRecordPreference) {
    ensure(p.afterMs in 1..MAX_TIME && p.snoozeMs in 1..MAX_TIME,"Invalid check-in duration")
}
internal fun validateLongRecordControl(s: LongRecordControl) {
    validateLongRecordPreference(s.preference)
    ensure(s.format == 1 && s.revision >= 0 && s.generation >= 0 && s.dueDurationMs in 0..MAX_TIME,"Invalid check-in control",ErrorCode.STORAGE)
    s.pending?.let { t -> ensure(t.target == s.target && t.dueDurationMs == s.dueDurationMs && t.generation == s.generation &&
        t.segmentId.isNotBlank() && t.bootId.isNotBlank() && t.dueElapsed >= 0,"Invalid check-in ticket",ErrorCode.STORAGE) }
    ensure(s.attempt == null || s.attempt.ticket == s.pending,"Invalid check-in receipt",ErrorCode.STORAGE)
}
