package dev.notai.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.Required

@Serializable data class FocusPlan(val workMs: Long = 25*60_000L,val breakMs: Long = 5*60_000L)
@Serializable enum class FocusPhase { WORK, BREAK }
@Serializable data class FocusRun(@Required val id: String = newId(),val sessionId: String,val plan: FocusPlan,
    val phase: FocusPhase = FocusPhase.WORK,val phaseIndex: Long = 0,val durationMs: Long = plan.workMs,
    val paused: Boolean = false,val segmentIds: List<String> = emptyList(),val breakElapsedMs: Long = 0,
    val breakStartedAt: Long? = null,val breakAnchor: Anchor? = null,val recovery: Boolean = false)
enum class FocusMode { RUNNING, PAUSED, DUE, RECOVERY }
data class FocusSnapshot(val id: String,val token: Token,val phase: FocusPhase,val phaseIndex: Long,
    val mode: FocusMode,val durationMs: Long,val elapsedMs: Long,val remainingMs: Long,
    val signalKey: String?,val safeLabel: String,val actions: Set<String>)

/** Countdown is derived from the same work segments used by history/Goals. Break time is never work. */
object Focus {
    fun snapshot(d: Dataset,n: Moment): FocusSnapshot? {
        validateMoment(n)
        val f = d.focus ?: return null
        var uncertain = f.recovery
        val elapsed = if(f.phase == FocusPhase.WORK) {
            val segments = d.segments.filter { it.id in f.segmentIds }.map { materialize(it,n) }
            uncertain = uncertain || segments.any { it.uncertain }
            segments.sumExact { it.durationMs }
        } else if(f.paused) f.breakElapsedMs else {
            val a = f.breakAnchor
            if(a == null || a.bootId != n.bootId || a.processId != n.processId || n.elapsed < a.elapsedMs || n.elapsed-a.elapsedMs > MAX_TIME ||
                kotlin.math.abs((n.wall-f.breakStartedAt!!)-(n.elapsed-a.elapsedMs)) > 1000) {
                uncertain = true; f.breakElapsedMs
            } else f.breakElapsedMs + minOf(f.durationMs-f.breakElapsedMs,n.elapsed-a.elapsedMs)
        }
        val mode = when { uncertain -> FocusMode.RECOVERY; elapsed >= f.durationMs -> FocusMode.DUE
            f.paused -> FocusMode.PAUSED; else -> FocusMode.RUNNING }
        return FocusSnapshot(f.id,token(d),f.phase,f.phaseIndex,mode,f.durationMs,elapsed,maxOf(0,f.durationMs-elapsed),
            if(mode == FocusMode.DUE) "${f.id}:${f.phaseIndex}" else null,
            when(mode) { FocusMode.RECOVERY -> "Focus: требуется уточнить время"; FocusMode.DUE -> "Focus: интервал завершён, выберите следующий этап"
                FocusMode.PAUSED -> "Focus на паузе"; FocusMode.RUNNING -> if(f.phase == FocusPhase.WORK) "Focus: рабочий интервал" else "Focus: перерыв" },
            when(mode) { FocusMode.RECOVERY -> setOf("resolveFocus"); FocusMode.DUE -> setOf("next","skip","cancel")
                FocusMode.PAUSED -> setOf("resume","skip","cancel"); FocusMode.RUNNING -> setOf("pause","skip","cancel") })
    }
    /** Snapshot for transfer: portable elapsed evidence, never portable live anchors. */
    internal fun freeze(d: Dataset,n: Moment): FocusRun? {
        val f = d.focus ?: return null
        val s = snapshot(d,n)!!
        return f.copy(breakElapsedMs = if(f.phase == FocusPhase.BREAK) s.elapsedMs else 0,
            breakAnchor = null,breakStartedAt = null,recovery = f.recovery || !f.paused || s.mode == FocusMode.RECOVERY)
    }
}
internal fun validatePlan(p: FocusPlan) = ensure(p.workMs in 1..MAX_TIME && p.breakMs in 1..MAX_TIME,"Focus durations must be positive and in range")
internal fun validateFocus(d: Dataset) {
    validatePlan(d.settings.focusPlan)
    val f = d.focus
    ensure(d.segments.filter { it.limitMs != null }.all { s -> f != null && f.phase == FocusPhase.WORK && s.sessionId == f.sessionId && s.id in f.segmentIds },"Bounded segment without Focus")
    if(f == null) return
    validatePlan(f.plan)
    ensure(!f.recovery || !f.paused,"Paused Focus cannot require live-tail recovery")
    ensure(f.id.isNotBlank() && f.phaseIndex >= 0 && f.durationMs in 1..MAX_TIME,"Invalid Focus metadata")
    val session = d.sessions.singleOrNull { it.id == f.sessionId && it.status != SessionStatus.STOPPED }
        ?: fail(ErrorCode.INVALID_INPUT,"Focus needs the current session")
    ensure(f.segmentIds.distinct().size == f.segmentIds.size,"Duplicate Focus segment")
    if(f.phase == FocusPhase.WORK) {
        ensure(f.segmentIds.isNotEmpty() && f.breakElapsedMs == 0L && f.breakAnchor == null && f.breakStartedAt == null,"Invalid work state")
        val own = f.segmentIds.map { id -> d.segments.singleOrNull { it.id == id && it.sessionId == session.id }
            ?: fail(ErrorCode.INVALID_INPUT,"Missing Focus work segment") }
        ensure(own.count { it.end == null } == if(f.paused) 0 else 1,"Focus work/open segment mismatch")
        ensure(own.none { it.uncertain } || f.recovery || session.status == SessionStatus.RECOVERY,"Unresolved Focus work")
        ensure(f.paused == (session.status == SessionStatus.PAUSED),"Focus pause differs from session")
        val closed = own.filter { it.end != null }.sumExact { it.durationMs }
        ensure(closed <= f.durationMs,"Focus phase exceeds planned duration")
        own.singleOrNull { it.end == null }?.let {
            ensure(it.limitMs == f.durationMs-closed && it.limitMs!! > 0,"Focus remaining budget mismatch")
            ensure(it.anchor == null || !it.anchor.processId.isNullOrBlank(),"Focus anchor needs process identity")
        }
    } else {
        ensure(session.status == SessionStatus.PAUSED && f.segmentIds.isEmpty() && f.breakElapsedMs in 0..f.durationMs,"Invalid break state")
        if(f.paused || f.recovery) ensure(f.breakAnchor == null && f.breakStartedAt == null,"Paused/recovery break has live anchor")
        else ensure(f.breakAnchor != null && !f.breakAnchor.processId.isNullOrBlank() && f.breakAnchor.bootId.isNotBlank() &&
            f.breakAnchor.elapsedMs >= 0 && f.breakStartedAt != null && f.breakStartedAt in 0..MAX_TIME &&
            f.durationMs-f.breakElapsedMs <= MAX_TIME-f.breakStartedAt,"Invalid break anchor")
    }
}
internal object FocusTransitions {
    private fun run(d: Dataset,n: Moment): Pair<FocusRun,FocusSnapshot> {
        val f = d.focus ?: fail(ErrorCode.INVALID_INPUT,"No Focus interval")
        val s = Focus.snapshot(d,n)!!
        ensure(s.mode != FocusMode.RECOVERY,"Resolve Focus time first",ErrorCode.RECOVERY_REQUIRED)
        return f to s
    }
    fun start(d: Dataset,n: Moment,plan: FocusPlan,selection: Classification): Dataset {
        validatePlan(plan); ensure(d.focus == null && current(d) == null,"Stop current tracking before starting Focus")
        checkStartable(d,selection)
        val chosen = d.classify(selection)
        val session = Session(status = SessionStatus.RUNNING,selection = chosen)
        val segment = workSegment(session,n,plan.workMs)
        return d.copy(sessions = d.sessions+session,segments = d.segments+segment,
            focus = FocusRun(sessionId = session.id,plan = plan,segmentIds = listOf(segment.id)))
    }
    fun pause(d: Dataset,n: Moment): Dataset {
        val (f,s) = run(d,n); ensure(!f.paused,"Focus is already paused")
        return if(f.phase == FocusPhase.WORK) closeWork(d,n,SessionStatus.PAUSED).copy(focus = f.copy(paused = true))
        else d.copy(focus = f.copy(paused = true,breakElapsedMs = s.elapsedMs,breakAnchor = null,breakStartedAt = null))
    }
    fun resume(d: Dataset,n: Moment): Dataset {
        val (f,s) = run(d,n); ensure(f.paused && s.remainingMs > 0,"Focus cannot resume; choose next phase")
        return if(f.phase == FocusPhase.WORK) {
            val session = current(d)!!.copy(status = SessionStatus.RUNNING)
            val segment = workSegment(session,n,s.remainingMs)
            d.copy(sessions = d.sessions.map { if(it.id == session.id) session else it },segments = d.segments+segment,
                focus = f.copy(paused = false,segmentIds = f.segmentIds+segment.id))
        } else d.copy(focus = f.copy(paused = false,breakStartedAt = n.wall,breakAnchor = anchor(n)))
    }
    fun next(d: Dataset,n: Moment,requireDue: Boolean): Dataset {
        val (f,s) = run(d,n)
        ensure(!requireDue || s.mode == FocusMode.DUE,"Focus interval is not due; use explicit skip")
        val index = Math.addExact(f.phaseIndex,1)
        return if(f.phase == FocusPhase.WORK) {
            val closed = if(f.paused) d else closeWork(d,n,SessionStatus.PAUSED)
            closed.copy(focus = f.copy(phase = FocusPhase.BREAK,phaseIndex = index,durationMs = f.plan.breakMs,paused = false,
                segmentIds = emptyList(),breakElapsedMs = 0,breakStartedAt = n.wall,breakAnchor = anchor(n)))
        } else {
            val session = current(d)!!.copy(status = SessionStatus.RUNNING)
            val segment = workSegment(session,n,f.plan.workMs)
            d.copy(sessions = d.sessions.map { if(it.id == session.id) session else it },segments = d.segments+segment,
                focus = f.copy(phase = FocusPhase.WORK,phaseIndex = index,durationMs = f.plan.workMs,paused = false,
                    segmentIds = listOf(segment.id),breakElapsedMs = 0,breakStartedAt = null,breakAnchor = null))
        }
    }
    fun cancel(d: Dataset,n: Moment): Dataset {
        val (f,_) = run(d,n)
        val closed = if(f.phase == FocusPhase.WORK && !f.paused) closeWork(d,n,SessionStatus.STOPPED) else
            d.copy(sessions = d.sessions.map { if(it.id == f.sessionId) it.copy(status = SessionStatus.STOPPED) else it })
        return closed.copy(focus = null)
    }
    fun details(d: Dataset,n: Moment,selection: Classification): Dataset {
        val (f,_) = run(d,n)
        val wasRunning = f.phase == FocusPhase.WORK && !f.paused
        val paused = if(wasRunning) pause(d,n) else d
        val chosen = paused.copy(sessions = paused.sessions.map { if(it.id == f.sessionId) it.copy(selection = selection) else it })
        return if(wasRunning && Focus.snapshot(chosen,n)!!.remainingMs > 0) resume(chosen,n) else chosen
    }
    private fun closeWork(d: Dataset,n: Moment,status: SessionStatus) = d.copy(
        sessions = d.sessions.map { if(it.id == d.focus!!.sessionId) it.copy(status = status) else it },
        segments = d.segments.map { if(it.sessionId == d.focus!!.sessionId && it.end == null) closeMeasured(it,n) else it })
    private fun anchor(n: Moment): Anchor {
        ensure(!n.processId.isNullOrBlank(),"Focus requires a process identity from Clock")
        return Anchor(n.bootId,n.elapsed,n.processId)
    }
    private fun workSegment(s: Session,n: Moment,budget: Long) = Segment(sessionId = s.id,start = n.wall,
        selection = s.selection,anchor = anchor(n),limitMs = budget)
}
