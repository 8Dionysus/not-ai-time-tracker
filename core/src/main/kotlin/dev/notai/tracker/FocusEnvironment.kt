package dev.notai.tracker

import kotlinx.serialization.Serializable

@Serializable data class FocusEnvironmentChoice(val distractions: Boolean = false,
    val music: Boolean = false,val localMusicId: String? = null)
@Serializable data class FocusEnvironmentRequest(val id: String,val target: TimerTarget,val focusId: String,
    val phaseIndex: Long,val bootId: String,val processId: String,val expiresElapsed: Long,
    val choice: FocusEnvironmentChoice)
@Serializable data class FocusDndLease(val requestId: String,val ruleId: String,val conditionId: String,val fingerprint: String,
    val ruleKey: String = requestId)
@Serializable data class FocusEnvironmentControl(val format: Int = 1,val revision: Long = 0,
    val request: FocusEnvironmentRequest? = null,val lease: FocusDndLease? = null,
    val dndAttempted: Boolean = false,val musicAttempted: Boolean = false,
    val dndStatus: FocusEffectStatus = FocusEffectStatus.OFF,val releasePending: Boolean = false,
    // Legacy active requests may already have issued STATE_TRUE. Missing flag is conservative.
    val leaseActive: Boolean = lease != null && request != null,val creationPending: Boolean = false)
interface FocusEnvironmentStore {
    /** Reentrant serialized gate, shared by every instance for the same device-local file. */
    fun <T> locked(block: ()->T): T
    fun read(): FocusEnvironmentControl
    fun writeDurably(value: FocusEnvironmentControl)
}
@Serializable enum class FocusEffectStatus {
    OFF, READY, ACTIVE, APPLY_ACCEPTED, UNSUPPORTED, ACCESS_REQUIRED, CONFIGURATION_REQUIRED,
    FOREGROUND_REQUIRED, SOURCE_REQUIRED, AUDIO_FOCUS_DENIED, INTERRUPTED,
    OTHER_AUDIO_ACTIVE, USER_CHANGED_RULE, RELEASED, FAILED, OUTCOME_UNKNOWN
}
interface FocusDistractionPort {
    fun availability(): FocusEffectStatus
    /** Create an INACTIVE own rule by request ID. Do not activate it in this method. */
    fun prepare(request: FocusEnvironmentRequest,previous: FocusDndLease?): FocusDndPreparation
    /** Only this lease's rule; no setInterruptionFilter or global policy save/restore. */
    fun activate(lease: FocusDndLease): FocusEffectStatus
    /** Revoke only our unchanged condition, park the rule, never delete configuration.
     * RELEASED acknowledges this contribution's API call, not global DND state. */
    fun release(lease: FocusDndLease): FocusEffectStatus
    /** Inexact one-shot safety cleanup, registered BEFORE activation. */
    fun scheduleRelease(request: FocusEnvironmentRequest)
    fun cancelRelease()
}
data class FocusDndPreparation(val lease: FocusDndLease?,val status: FocusEffectStatus)
fun interface PreparedFocusMusic { fun start(): FocusEffectStatus }
interface FocusMusicPort {
    /** Read-only capability check. Never starts another app, an account or network session. */
    fun availability(localId: String?): FocusEffectStatus
    /** Explicitly selected local media only. False audio-focus gain must never play. */
    /** Open/prepare potentially slow media outside the domain gate. Start is a short effect. */
    fun prepare(requestId: String,localId: String): PreparedFocusMusic?
    fun status(requestId: String): FocusEffectStatus
    fun stop(requestId: String): FocusEffectStatus
}
data class FocusEnvironmentState(val request: FocusEnvironmentRequest?,val focus: FocusSnapshot?,
    val distractions: FocusEffectStatus,val music: FocusEffectStatus,val cleanupPending: Boolean,val controlRevision: Long,
    val sourceAvailable: Boolean = true)
data class FocusEnvironmentCleanup(val request: FocusEnvironmentRequest?,val distractions: FocusEffectStatus,
    val music: FocusEffectStatus,val pending: Boolean)

/** Release requires only an exact owned lease, never an open or healthy history database.
 * Boot/expiry/recovery may revoke a contribution but cannot start one. */
fun releaseFocusEnvironment(store: FocusEnvironmentStore,distractions: FocusDistractionPort,
    music: FocusMusicPort,expectedRequestId: String? = null): FocusEnvironmentCleanup = store.locked {
    var control = store.read().also(::validateFocusEnvironmentControl)
    if(expectedRequestId != null && control.request?.id != expectedRequestId)
        return@locked FocusEnvironmentCleanup(control.request,FocusEffectStatus.OFF,FocusEffectStatus.OFF,control.releasePending)
    // An inactive parked rule is only inspected/reused on another explicit DND opt-in.
    if(control.request == null && !control.leaseActive)
        return@locked FocusEnvironmentCleanup(null,FocusEffectStatus.OFF,FocusEffectStatus.OFF,false)
    fun save(next: FocusEnvironmentControl) {
        ensure(store.read() == control,"Changed Focus cleanup control",ErrorCode.STORAGE)
        control = next.copy(revision = Math.addExact(control.revision,1)).also {
            validateFocusEnvironmentControl(it);store.writeDurably(it)
        }
    }
    fun effect(action: ()->FocusEffectStatus): FocusEffectStatus = try { action() }
        catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e;FocusEffectStatus.OUTCOME_UNKNOWN }
    if(control.request != null && !control.releasePending) save(control.copy(releasePending = true))
    val audio = control.request?.let { effect { music.stop(it.id) } } ?: FocusEffectStatus.OFF
    val dnd = if(control.creationPending) FocusEffectStatus.OUTCOME_UNKNOWN else
        control.lease?.takeIf { control.leaseActive }?.let { effect { distractions.release(it) } } ?: FocusEffectStatus.OFF
    val failures = setOf(FocusEffectStatus.ACCESS_REQUIRED,FocusEffectStatus.FAILED,FocusEffectStatus.OUTCOME_UNKNOWN)
    val pending = control.creationPending || dnd in failures || audio in failures
    if(!pending) {
        distractions.cancelRelease()
        val parked = if(dnd == FocusEffectStatus.USER_CHANGED_RULE) null else control.lease
        save(FocusEnvironmentControl(revision = control.revision,lease = parked,leaseActive = false))
    }
    FocusEnvironmentCleanup(control.request,dnd,audio,pending)
}

/** Optional per-work-interval effects around the EXISTING Focus, never a second countdown.
 * begin is explicit opt-in (idempotent caller UUID); synchronize only maintains/revokes a request.
 * No auto-restart of music or DND after process death, focus loss, a pause, or a denied request.
 */
class FocusEnvironment(private val tracker: Tracker,private val store: FocusEnvironmentStore,
    private val distractions: FocusDistractionPort,private val music: FocusMusicPort,
    private val active: ()->Boolean = { true }) {
    private fun read() = store.read().also(::validateFocusEnvironmentControl)
    private fun save(old: FocusEnvironmentControl,next: FocusEnvironmentControl): FocusEnvironmentControl {
        if(old == next) return old
        ensure(read() == old,"Changed Focus environment control",ErrorCode.STORAGE)
        return next.copy(revision = Math.addExact(old.revision,1)).also {
            validateFocusEnvironmentControl(it);store.writeDurably(it)
        }
    }
    private fun <T> operational(block: ()->T): T = storage { store.locked(block) }
    private fun current(data: Dataset,now: Moment,r: FocusEnvironmentRequest): FocusSnapshot? {
        if(!active() || now.bootId != r.bootId || now.processId != r.processId || now.elapsed >= r.expiresElapsed) return null
        val snapshot = snapshot(data,now)
        return snapshot.focus?.takeIf { snapshot.timerTarget == r.target && it.id == r.focusId &&
            it.phase == FocusPhase.WORK && it.phaseIndex == r.phaseIndex && it.mode == FocusMode.RUNNING }
    }
    private fun currentNow(data: Dataset,r: FocusEnvironmentRequest): FocusSnapshot? = try { current(data,tracker.clock.now(),r) }
        catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e;null }
    private fun readCurrent(r: FocusEnvironmentRequest): FocusSnapshot? = try { currentNow(tracker.data(),r) }
        catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e;null }
    /** Caller supplies a stable command UUID and current Token; no permission prompt is opened. */
    fun begin(expected: Token,id: String,choice: FocusEnvironmentChoice,controlRevision: Long): FocusEnvironmentState = operational {
        ensure(id.matches(Regex("[0-9a-fA-F-]{36}")),"Focus environment needs a command UUID")
        ensure(!choice.music || !choice.localMusicId.isNullOrBlank(),"Select local audio before enabling music")
        ensure(active(),"Attention runtime was closed",ErrorCode.STALE)
        val old = read()
        if(old.request?.id == id) {
            ensure(old.request.choice == choice,"Focus effect command changed",ErrorCode.STALE)
            return@operational synchronizeLocked()
        }
        ensure(old.revision == controlRevision,"Focus environment changed",ErrorCode.STALE)
        // Reject a stale/invalid request BEFORE revoking an existing effect.
        tracker.store.checkedTransaction { checked ->
            val now = tracker.clock.now();checkToken(checked.data,expected,now)
            val focus = checked.snapshot(now).focus
            ensure(focus != null && focus.phase == FocusPhase.WORK && focus.mode == FocusMode.RUNNING,"Effects require a running work interval")
            checked.replace(checked.data) { Unit }
        }
        val released = releaseLocked(old)
        ensure(!released.cleanupPending,"Release the previous Focus rule first",ErrorCode.STORAGE)
        tracker.store.checkedTransaction { checked ->
            val now = tracker.clock.now();checkToken(checked.data,expected,now)
            val snapshot = checked.snapshot(now);val focus = snapshot.focus
                ?: fail(ErrorCode.INVALID_INPUT,"No Focus interval")
            ensure(focus.phase == FocusPhase.WORK && focus.mode == FocusMode.RUNNING,"Effects require a running work interval")
            ensure(!now.processId.isNullOrBlank(),"Focus effects require process identity")
            val request = FocusEnvironmentRequest(id,snapshot.timerTarget!!,focus.id,focus.phaseIndex,now.bootId,now.processId!!,
                Math.addExact(now.elapsed,focus.remainingMs),choice)
            checked.replace(checked.data) {
                val state = read()
                save(state,FocusEnvironmentControl(request = request,lease = state.lease,leaseActive = false));Unit
            }
        }
        synchronizeLocked(allowStart = true)
    }
    fun synchronize(): FocusEnvironmentState = operational { synchronizeLocked() }
    fun stop(): FocusEnvironmentState = operational { releaseLocked(read()) }
    fun onExpiry(id: String): FocusEnvironmentState = operational {
        val control = read();val request = control.request
        if(request == null || request.id != id) return@operational synchronizeLocked()
        val now = try { tracker.clock.now().also(::validateMoment) }
            catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e;null }
        if(now != null && now.bootId == request.bootId && now.elapsed < request.expiresElapsed) {
            distractions.scheduleRelease(request);return@operational synchronizeLocked()
        }
        releaseLocked(control)
    }
    private fun effect(action: ()->FocusEffectStatus): FocusEffectStatus = try { action() }
        catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e;FocusEffectStatus.OUTCOME_UNKNOWN }
    private fun releaseLocked(control: FocusEnvironmentControl): FocusEnvironmentState {
        val result = releaseFocusEnvironment(store,distractions,music,control.request?.id)
        val source = try { tracker.snapshot() } catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e;null }
        return FocusEnvironmentState(result.request,source?.focus,result.distractions,result.music,result.pending,read().revision,source != null)
    }
    private fun synchronizeLocked(allowStart: Boolean = false): FocusEnvironmentState {
        var control = read();val r = control.request
            ?: return FocusEnvironmentState(null,tracker.snapshot().focus,FocusEffectStatus.OFF,FocusEffectStatus.OFF,false,control.revision)
        if(control.releasePending || control.creationPending) return releaseLocked(control)
        val focus = readCurrent(r) ?: return releaseLocked(control)
        // A cold process cannot restart effects. It can only revoke an earlier owned lease.
        if(!allowStart && (r.choice.distractions && !control.dndAttempted || r.choice.music && !control.musicAttempted))
            return releaseLocked(control)
        var dnd = if(r.choice.distractions) effect { distractions.availability() } else FocusEffectStatus.OFF
        var audio = if(r.choice.music) effect { music.status(r.id) } else FocusEffectStatus.OFF
        if(control.leaseActive && dnd != FocusEffectStatus.READY) return releaseLocked(control)
        if(allowStart && (r.choice.distractions || r.choice.music)) distractions.scheduleRelease(r)
        if(r.choice.distractions && !control.dndAttempted && allowStart && dnd == FocusEffectStatus.READY) {
            // A rule is inactive during preparation. Persist its ownership before activation.
            // Creation can succeed before a crash/lost response returns the OS rule ID. Keep
            // the exact request/condition key rather than forgetting a potentially orphaned
            // INACTIVE rule. Unknown preparation blocks reassertion and needs system review.
            control = save(control,control.copy(creationPending = true,dndAttempted = true,dndStatus = FocusEffectStatus.OUTCOME_UNKNOWN))
            val prepared = try { distractions.prepare(r,control.lease) }
                catch(e: Exception) { if(e is kotlinx.coroutines.CancellationException) throw e;return releaseLocked(control) }
            val lease = prepared.lease
            ensure(prepared.status != FocusEffectStatus.READY || lease != null,"Ready DND preparation needs a lease",ErrorCode.STORAGE)
            control = save(control,control.copy(lease = lease,dndAttempted = true,
                leaseActive = prepared.status == FocusEffectStatus.READY,
                creationPending = prepared.status == FocusEffectStatus.OUTCOME_UNKNOWN && lease == null,
                dndStatus = if(prepared.status == FocusEffectStatus.READY) FocusEffectStatus.OUTCOME_UNKNOWN else prepared.status))
            if(control.creationPending) return releaseLocked(control)
            dnd = prepared.status
            if(lease != null && prepared.status == FocusEffectStatus.READY) {
                dnd = tracker.store.checkedTransaction { checked ->
                    val valid = currentNow(checked.data,r) != null
                    checked.replace(checked.data) { if(valid) effect { distractions.activate(lease) } else FocusEffectStatus.OFF }
                }
            }
            control = save(control,control.copy(dndStatus = dnd))
        } else if(control.dndAttempted) dnd = control.dndStatus
        else if(r.choice.distractions && allowStart) control = save(control,control.copy(dndAttempted = true,dndStatus = dnd))
        if(r.choice.music && !control.musicAttempted && allowStart) {
            control = save(control,control.copy(musicAttempted = true)) // no replay after an ambiguous play result
            audio = effect { music.availability(r.choice.localMusicId) }
            if(audio == FocusEffectStatus.READY) {
                val prepared = music.prepare(r.id,r.choice.localMusicId!!)
                audio = if(prepared == null) FocusEffectStatus.FAILED else tracker.store.checkedTransaction { checked ->
                    val valid = currentNow(checked.data,r) != null
                    checked.replace(checked.data) { if(valid) effect { prepared.start() } else FocusEffectStatus.OFF }
                }
            }
        }
        if(readCurrent(r) == null) return releaseLocked(control)
        return FocusEnvironmentState(r,focus,dnd,audio,false,control.revision)
    }
}
internal fun validateFocusEnvironmentControl(s: FocusEnvironmentControl) {
    ensure(s.format == 1 && s.revision >= 0,"Invalid Focus environment control",ErrorCode.STORAGE)
    s.request?.let { r -> ensure(r.id.isNotBlank() && r.focusId.isNotBlank() && r.bootId.isNotBlank() && r.processId.isNotBlank() &&
        r.phaseIndex >= 0 && r.expiresElapsed >= 0 && (!r.choice.music || !r.choice.localMusicId.isNullOrBlank()),"Invalid Focus environment request",ErrorCode.STORAGE) }
    ensure(s.lease == null || s.lease.ruleId.isNotBlank() && s.lease.conditionId.isNotBlank() &&
        s.lease.fingerprint.isNotBlank() && s.lease.ruleKey.isNotBlank(),"Invalid owned Focus lease",ErrorCode.STORAGE)
    ensure(!s.leaseActive || s.lease != null && s.request != null && s.lease.requestId == s.request.id,
        "Active Focus lease needs its exact request",ErrorCode.STORAGE)
    ensure(!s.releasePending || s.request != null,"Pending Focus release needs a request",ErrorCode.STORAGE)
    ensure(!s.creationPending || s.request != null && !s.leaseActive && s.dndAttempted,
        "Unknown Focus creation needs its inactive request",ErrorCode.STORAGE)
}
