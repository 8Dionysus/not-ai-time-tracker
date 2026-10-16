package dev.notai.tracker

/** Evidence created only by core validation/construction, never by a caller-supplied trusted flag. */
class CheckedCommit<T> internal constructor(private val base: CheckedState,val state: CheckedState,
    val result: T,val timerRows: TimerRows? = null) {
    fun requireBase(actual: CheckedState) = ensure(actual === base,"Checked change belongs to another state",ErrorCode.STALE)
}
data class Positioned<T>(val position: Int,val value: T)
class TimerRows internal constructor(val sessions: List<Positioned<Session>>,val segments: List<Positioned<Segment>>)
internal enum class TimerAction { START, PAUSE, RESUME, STOP, SWITCH }

/** A validated immutable graph and small reusable timer index. No mutable global validation cache.
 * Full replacement rebuilds the index; a timer transition proves exactly its small set of changes.
 * Collections are defensively copied/wrapped at trust boundaries, including nested classifications.
 */
class CheckedState private constructor(val data: Dataset,private val sessionPosition: Int?,private val segmentPosition: Int?,
    private val ownSegments: List<Segment>,private val lastCertainEnd: Long,private val totalDuration: Long,
    private val typeIds: Set<String>,private val cardIds: Set<String>,internal val historyKey: Any = Any()) {
    private val reportIndex by lazy { ReportIndex(data) }
    internal fun calendar(now: Moment,first: java.time.LocalDate,days: Int,filter: Filter,zone: String) = Reports.indexedCalendar(data,reportIndex,now,first,days,filter,zone)
    internal fun report(now: Moment,start: Long,end: Long,filter: Filter,zone: String) = Reports.indexed(data,reportIndex,now,start,end,filter,zone)
    fun snapshot(now: Moment): Snapshot = snapshot(data.copy(sessions = listOfNotNull(sessionPosition?.let { data.sessions[it] }),segments = ownSegments),now)
    fun <T> replace(next: Dataset,result: (CheckedState)->T): CheckedCommit<T> {
        val checked = if(next === data) this else validated(next.frozen(data),historyKey =
            if(next.sessions === data.sessions && next.segments === data.segments) historyKey else Any())
        return CheckedCommit(this,checked,result(checked))
    }
    internal fun <T> timer(expected: Token,action: TimerAction,now: Moment,selection: Classification,note: String?,
        result: (CheckedState,Set<String>)->T): CheckedCommit<T> {
        ensure(data.focus == null,"Focus requires a bounded transition")
        val before = snapshot(now)
        if(before.token != expected) throw CoreError(ErrorCode.STALE,"Stale action; refresh snapshot",before)
        val session = sessionPosition?.let { data.sessions[it] }
        val active = segmentPosition?.let { data.segments[it] }
        val sessionRows = mutableListOf<Positioned<Session>>()
        val segmentRows = mutableListOf<Positioned<Segment>>()
        var nextSession = sessionPosition; var nextSegment = segmentPosition
        var own = ownSegments; var end = lastCertainEnd; var total = totalDuration
        fun historical(c: Classification): Classification = data.classify(c).frozen().also {
            validateSelection(data,it,typeIds,cardIds)
            ensure(it.activityTypeId == null || it.ancestorPathAtWrite.isNotEmpty(),"Historical classification requires a path snapshot")
        }
        fun close(status: SessionStatus) {
            ensure(session != null,"No current session")
            if(session!!.status == SessionStatus.PAUSED) {
                sessionRows += Positioned(sessionPosition!!,session.copy(status = status))
            } else {
                ensure(before.mode != Mode.RECOVERY,"Resolve uncertain time first",ErrorCode.RECOVERY_REQUIRED)
                ensure(session.status == SessionStatus.RUNNING && active != null,"Session is not running")
                val closed = closeMeasured(active!!,now)
                validateSegmentTime(closed)
                total = listOf(total-active.durationMs,closed.durationMs).sumExact { it }
                if(!closed.isEmptyInterval()) end = maxOf(end,closed.end!!)
                segmentRows += Positioned(segmentPosition!!,closed)
                sessionRows += Positioned(sessionPosition!!,session.copy(status = status))
                own = own.map { if(it.id == closed.id) closed else it }
            }
            nextSegment = null
            if(status == SessionStatus.STOPPED) { nextSession = null; own = emptyList() }
        }
        fun append(selected: Classification,newSession: Boolean) {
            ensure(now.wall >= end,"Intervals overlap",ErrorCode.OVERLAP)
            val target = if(newSession) Session(status = SessionStatus.RUNNING,selection = selected,note = note) else
                session!!.copy(status = SessionStatus.RUNNING,selection = selected)
            if(newSession) ensure(data.sessions.none { it.id == target.id },"Duplicate session ID")
            val segment = Segment(sessionId = target.id,start = now.wall,selection = selected,anchor = Anchor(now.bootId,now.elapsed))
            ensure(data.segments.none { it.id == segment.id },"Duplicate segment ID")
            validateSegmentTime(segment)
            nextSession = if(newSession) data.sessions.size else sessionPosition!!
            nextSegment = data.segments.size
            sessionRows += Positioned(nextSession!!,target); segmentRows += Positioned(nextSegment!!,segment)
            own = if(newSession) listOf(segment) else own+segment
        }
        when(action) {
            TimerAction.START -> { ensure(session == null,"Already tracking"); checkStartable(data,selection); append(historical(selection),true) }
            TimerAction.PAUSE -> { ensure(session?.status == SessionStatus.RUNNING,"Session is not running"); close(SessionStatus.PAUSED) }
            TimerAction.RESUME -> { ensure(session?.status == SessionStatus.PAUSED,"Session is not paused"); append(historical(session!!.selection),false) }
            TimerAction.STOP -> close(SessionStatus.STOPPED)
            TimerAction.SWITCH -> { close(SessionStatus.STOPPED); checkStartable(data,selection); append(historical(selection),true) }
        }
        fun <E> edited(old: List<E>,rows: List<Positioned<E>>): List<E> = if(rows.isEmpty()) old else frozenList(old.toMutableList().apply {
            for(row in rows) if(row.position == size) add(row.value) else set(row.position,row.value)
        })
        val next = data.copy(revision = Math.addExact(data.revision,1),timerEpoch = Math.addExact(data.timerEpoch,1),sessions = edited(data.sessions,sessionRows),segments = edited(data.segments,segmentRows))
        val checked = CheckedState(next,nextSession,nextSegment,own,end,total,typeIds,cardIds,
            if(action in setOf(TimerAction.START,TimerAction.PAUSE,TimerAction.RESUME)) historyKey else Any())
        val changed = sessionRows.map { it.value.id }.toSet()
        return CheckedCommit(this,checked,result(checked,changed),TimerRows(frozenList(sessionRows),frozenList(segmentRows)))
    }
    /** Only a core-created stopped-record receipt can extend the small checked timer commit. */
    internal fun withAssignment(sessionId: String): Pair<CheckedState,StoppedAssignment> {
        ensure(data.sessions.any { it.id == sessionId && it.status == SessionStatus.STOPPED },"Assignment requires a completed session")
        val offer = StoppedAssignment(newId(),data.datasetId,data.controlBarrier,data.timerEpoch,sessionId,recordFingerprint(data,sessionId))
        val next = data.copy(pendingAssignments = frozenList((data.pendingAssignments+offer).takeLast(32)))
        validateAssignments(next)
        return CheckedState(next,sessionPosition,segmentPosition,ownSegments,lastCertainEnd,totalDuration,typeIds,cardIds,historyKey) to offer
    }
    internal fun <T> addAssignment(commit: CheckedCommit<T>,sessionId: String,result: (CheckedState,StoppedAssignment)->T): CheckedCommit<T> {
        commit.requireBase(this)
        val (next,offer) = commit.state.withAssignment(sessionId)
        return CheckedCommit(this,next,result(next,offer),commit.timerRows)
    }
    companion object {
        fun validate(data: Dataset,onPhase: ((String,Long)->Unit)? = null): CheckedState {
            val start = if(onPhase != null) System.nanoTime() else 0
            val frozen = data.frozen()
            onPhase?.invoke("freeze",System.nanoTime()-start)
            return validated(frozen,onPhase)
        }
        private fun validated(data: Dataset,onPhase: ((String,Long)->Unit)? = null,historyKey: Any = Any()): CheckedState {
            var start = if(onPhase != null) System.nanoTime() else 0
            dev.notai.tracker.validate(data)
            onPhase?.invoke("validate",System.nanoTime()-start)
            if(onPhase != null) start = System.nanoTime()
            val session = data.sessions.indexOfFirst { it.status != SessionStatus.STOPPED }.takeIf { it >= 0 }
            val segment = data.segments.indexOfFirst { it.end == null }.takeIf { it >= 0 }
            val id = session?.let { data.sessions[it].id }
            val checked = CheckedState(data,session,segment,if(id == null) emptyList() else data.segments.filter { it.sessionId == id },
                data.segments.filter { !it.uncertain && it.end != null && !it.isEmptyInterval() }.maxOfOrNull { it.end!! } ?: 0,
                data.segments.sumExact { it.durationMs },data.types.map { it.id }.toSet(),data.cards.map { it.id }.toSet(),historyKey)
            onPhase?.invoke("checked-index",System.nanoTime()-start)
            return checked
        }
    }
}
internal fun checkStartable(d: Dataset,c: Classification) {
    ensure(c.cardId == null || d.cards.any { it.id == c.cardId && !it.archived },"Card missing or archived")
    ensure(c.activityTypeId == null || d.types.any { it.id == c.activityTypeId && !it.archived && it.selectable },"Type missing, archived or not selectable")
}
