package dev.notai.tracker

import kotlinx.coroutines.flow.map
import kotlin.math.abs

/** All calls are blocking: Android callers use Dispatchers.IO. No timer service or periodic writes. */
class Tracker(val store: StateStore, val clock: Clock) {
    private var undo: Pair<Token, Dataset>? = null
    val snapshots get() = store.changes.map { snapshot(it, clock.now()) }
    fun data(): Dataset = storage { store.read() }
    fun snapshot(): Snapshot = snapshot(data(), clock.now())
    fun selection(cardId: String): Classification = data().cards.singleOrNull { it.id == cardId && !it.archived }
        ?.classification() ?: fail(ErrorCode.INVALID_INPUT, "Card missing or archived")

    data class Change(val state: Snapshot, val changedSessionIds: Set<String>, val undoAvailable: Boolean,
        val data: Dataset, val asOf: Moment) {
        val changedSessions get() = data.sessions.filter { it.id in changedSessionIds }
        val changedSegments get() = data.segments.filter { it.sessionId in changedSessionIds }
        fun report(start: Long,end: Long,filter: Filter = Filter()) = Reports.report(data,asOf,start,end,filter)
        fun day(date: java.time.LocalDate,filter: Filter = Filter()) = Reports.day(data,asOf,date,filter)
    }
    private fun mutate(expected: Token, undoable: Boolean = false, edit: (Dataset, Moment) -> Dataset): Change = storage {
        store.transaction { old ->
            val now = clock.now()
            checkToken(old, expected, now)
            val next = edit(old, now).copy(revision = Math.addExact(old.revision, 1))
            validate(next)
            val changed = (old.sessions + next.sessions).map { it.id }.filter { id ->
                old.sessions.find { it.id == id } != next.sessions.find { it.id == id } ||
                old.segments.filter { it.sessionId == id } != next.segments.filter { it.sessionId == id } ||
                measurementsFor(old,id) != measurementsFor(next,id)
            }.toSet()
            val state = snapshot(next, now)
            // Undo is issued only after a successful persistence commit (below).
            next to Triple(Change(state, changed, undoable, next, now), old, next)
        }.let { (change, previous, _) ->
            undo = if (undoable) change.state.token to previous else null
            change
        }
    }
    fun start(expected: Token, selection: Classification = Classification(), note: String? = null) = mutate(expected) { d,n ->
        ensure(current(d) == null, "Already tracking")
        append(d, selection, note, n)
    }
    fun pause(expected: Token) = mutate(expected) { d,n ->
        val s = running(d,n)
        close(d,s,n,SessionStatus.PAUSED)
    }
    fun resume(expected: Token) = mutate(expected) { d,n ->
        val s = current(d) ?: fail(ErrorCode.INVALID_INPUT, "No current session")
        ensure(s.status == SessionStatus.PAUSED, "Session is not paused")
        d.copy(sessions = d.sessions.replace(s.copy(status = SessionStatus.RUNNING)),
            segments = d.segments + open(s.id,s.selection,n))
    }
    fun stop(expected: Token) = mutate(expected) { d,n ->
        val s = current(d) ?: fail(ErrorCode.INVALID_INPUT, "No current session")
        if (s.status == SessionStatus.PAUSED) d.copy(sessions = d.sessions.replace(s.copy(status = SessionStatus.STOPPED)))
        else close(d,running(d,n),n,SessionStatus.STOPPED)
    }
    fun switch(expected: Token, selection: Classification) = mutate(expected, true) { d,n ->
        val s = current(d) ?: fail(ErrorCode.INVALID_INPUT, "No current session")
        val closed = if (s.status == SessionStatus.PAUSED) d.copy(sessions = d.sessions.replace(s.copy(status = SessionStatus.STOPPED)))
        else close(d,running(d,n),n,SessionStatus.STOPPED)
        append(closed, selection, null,n)
    }
    /** Explicit late labeling applies to the entire current session, including its earlier segments. */
    fun labelCurrent(expected: Token, selection: Classification, note: String? = null) = mutate(expected, true) { d,_ ->
        val s = current(d) ?: fail(ErrorCode.INVALID_INPUT, "No current session")
        d.copy(sessions = d.sessions.replace(s.copy(selection = selection, note = note)),
            segments = d.segments.map { if (it.sessionId == s.id) it.copy(selection = selection) else it })
    }
    /** Change details from now; preserves session-scoped measurements without copying them. */
    fun changeDetails(expected: Token, selection: Classification) = mutate(expected, true) { d,n ->
        val s = current(d) ?: fail(ErrorCode.INVALID_INPUT, "No current session")
        ensure(s.selection.cardId == selection.cardId, "Use switch to change card")
        if (s.status == SessionStatus.PAUSED) d.copy(sessions = d.sessions.replace(s.copy(selection = selection)))
        else {
            val closed = close(d,running(d,n),n,SessionStatus.PAUSED)
            closed.copy(sessions = closed.sessions.replace(s.copy(selection = selection)), segments = closed.segments + open(s.id,selection,n))
        }
    }
    fun repeat(expected: Token, sessionId: String) = mutate(expected) { d,n ->
        ensure(current(d) == null, "Stop or switch current session first")
        val s = d.sessions.find { it.id == sessionId } ?: fail(ErrorCode.INVALID_INPUT, "Missing session")
        val lastSelection = d.segments.filter { it.sessionId == sessionId }.maxByOrNull { it.start }?.selection ?: s.selection
        append(d,lastSelection,s.note,n)
    }
    fun startFavorite(expected: Token, favoriteId: String) = mutate(expected) { d,n ->
        ensure(current(d) == null, "Stop or switch current session first")
        val f = d.favorites.find { it.id == favoriteId } ?: fail(ErrorCode.INVALID_INPUT, "Missing favorite")
        append(d,f.selection,null,n)
    }
    fun removeFavorite(expected: Token, favoriteId: String) = mutate(expected) { d,_ ->
        ensure(d.favorites.any { it.id == favoriteId }, "Missing favorite")
        d.copy(favorites = d.favorites.filterNot { it.id == favoriteId })
    }
    fun saveFavorite(expected: Token, favorite: Favorite) = mutate(expected) { d,_ ->
        d.copy(favorites = d.favorites.filterNot { it.id == favorite.id } + favorite)
    }
    fun saveType(expected: Token, type: ActivityType) = mutate(expected) { d,_ ->
        d.copy(types = d.types.filterNot { it.id == type.id } + type)
    }
    fun saveCard(expected: Token, card: Card) = mutate(expected) { d,_ ->
        d.copy(cards = d.cards.filterNot { it.id == card.id } + card)
    }
    fun copyCard(expected: Token, cardId: String, name: String) = mutate(expected) { d,_ ->
        val c = d.cards.find { it.id == cardId } ?: fail(ErrorCode.INVALID_INPUT, "Missing card")
        d.copy(cards = d.cards + c.copy(id = newId(), name = name, archived = false))
    }
    fun settings(expected: Token, settings: Settings) = mutate(expected) { d,_ -> d.copy(settings = settings) }
    fun measure(expected: Token, measurement: Measurement) = mutate(expected, true) { d,_ ->
        d.copy(measurements = d.measurements.filterNot { it.id == measurement.id } + measurement)
    }
    fun removeMeasurement(expected: Token, measurementId: String) = mutate(expected,true) { d,_ ->
        ensure(d.measurements.any { it.id == measurementId }, "Missing measurement")
        d.copy(measurements = d.measurements.filterNot { it.id == measurementId })
    }
    data class SegmentEdit(val id: String,val start: Long,val end: Long,val selection: Classification)
    /** Save a completed session form atomically; retained segment IDs retain their measurements. */
    fun correctSession(expected: Token,sessionId: String,edits: List<SegmentEdit>,note: String?) = mutate(expected,true) { d,n ->
        val session = d.sessions.find { it.id == sessionId } ?: fail(ErrorCode.INVALID_INPUT,"Missing session")
        ensure(session.status == SessionStatus.STOPPED,"Use current-session commands while tracking")
        val own = d.segments.filter { it.sessionId == sessionId }
        ensure(edits.map { it.id }.toSet() == own.map { it.id }.toSet() && edits.size == own.size,"Supply each existing segment once")
        edits.forEach { interval(it.start,it.end,n) }
        val byId = edits.associateBy { it.id }
        d.copy(sessions = d.sessions.replace(session.copy(note = note,selection = edits.maxBy { it.start }.selection)),
            segments = d.segments.map { segment -> byId[segment.id]?.let { e ->
                segment.copy(start = e.start,end = e.end,durationMs = e.end-e.start,selection = e.selection,anchor = null,uncertain = false,observedUntil = null)
            } ?: segment })
    }
    /** Start/end are explicit; calling this is the form's Save, not its preview. */
    fun manual(expected: Token, start: Long, end: Long, selection: Classification, note: String? = null) = mutate(expected, true) { d,n ->
        interval(start,end,n)
        val s = Session(status = SessionStatus.STOPPED, selection = selection, note = note)
        d.copy(sessions = d.sessions + s, segments = d.segments + Segment(sessionId = s.id,start = start,end = end,durationMs = end-start,selection = selection))
    }
    fun correctSegment(expected: Token, segmentId: String, start: Long, end: Long, selection: Classification) = mutate(expected,true) { d,n ->
        interval(start,end,n)
        val seg = d.segments.find { it.id == segmentId } ?: fail(ErrorCode.INVALID_INPUT, "Missing segment")
        ensure(seg.end != null, "Resolve open tail instead")
        d.copy(segments = d.segments.map { if (it.id == segmentId) it.copy(start = start,end = end,durationMs = end-start,
            selection = selection,anchor = null,uncertain = false,observedUntil = null) else it })
    }
    /** Correct a forgotten switch inside the current uninterrupted segment in one transaction. */
    fun switchedEarlier(expected: Token, boundary: Long, selection: Classification) = mutate(expected,true) { d,n ->
        val s = running(d,n); val seg = d.segments.single { it.sessionId == s.id && it.end == null }
        val measured = materialize(seg,n)
        ensure(!measured.uncertain && boundary in seg.start..n.wall, "Boundary outside certain active interval", ErrorCode.INVALID_INTERVAL)
        val earlier = n.copy(wall = boundary, elapsed = n.elapsed - (n.wall-boundary))
        ensure(earlier.elapsed >= seg.anchor!!.elapsedMs, "Boundary before monotonic start", ErrorCode.INVALID_INTERVAL)
        append(close(d,s,earlier,SessionStatus.STOPPED),selection,null,earlier)
    }
    /** null interval excludes the unresolved tail. An explicit interval confirms/corrects it. */
    fun resolveTail(expected: Token, start: Long?, end: Long?) = mutate(expected,true) { d,n ->
        val s = current(d) ?: fail(ErrorCode.INVALID_INPUT, "No current session")
        val seg = d.segments.singleOrNull { it.sessionId == s.id && it.end == null } ?: fail(ErrorCode.INVALID_INPUT, "No open tail")
        ensure(snapshot(d,n).mode == Mode.RECOVERY, "Tail is not awaiting recovery")
        ensure((start == null) == (end == null), "Supply both boundaries or exclude")
        val segments = if (start == null) d.segments.filterNot { it.id == seg.id } else {
            interval(start,end!!,n)
            d.segments.map { if (it.id == seg.id) it.copy(start = start,end = end,durationMs = end-start,anchor = null,uncertain = false,observedUntil = null) else it }
        }
        val keep = segments.any { it.sessionId == s.id }
        d.copy(segments = segments, sessions = if (keep) d.sessions.replace(s.copy(status = SessionStatus.STOPPED)) else d.sessions.filterNot { it.id == s.id },
            measurements = d.measurements.filterNot { (it.scope == Scope.SEGMENT && it.targetId == seg.id && start == null) || (!keep && it.scope == Scope.SESSION && it.targetId == s.id) })
    }
    fun undo(expected: Token): Change {
        val saved = undo
        if (saved == null || saved.first != expected) throw CoreError(ErrorCode.STALE,"No matching available undo",snapshot())
        return mutate(expected) { _,_ -> saved.second }
    }
    private fun measurementsFor(d: Dataset,sessionId: String): List<Measurement> {
        val segmentIds = d.segments.filter { it.sessionId == sessionId }.map { it.id }.toSet()
        return d.measurements.filter { if (it.scope == Scope.SESSION) it.targetId == sessionId else it.targetId in segmentIds }
    }
    private fun running(d: Dataset,n: Moment): Session {
        val s = current(d) ?: fail(ErrorCode.INVALID_INPUT, "No current session")
        ensure(snapshot(d,n).mode != Mode.RECOVERY, "Resolve uncertain time first", ErrorCode.RECOVERY_REQUIRED)
        ensure(s.status == SessionStatus.RUNNING, "Session is not running")
        return s
    }
    private fun close(d: Dataset,s: Session,n: Moment,status: SessionStatus): Dataset = d.copy(
        sessions = d.sessions.replace(s.copy(status = status)), segments = d.segments.map {
            if (it.sessionId == s.id && it.end == null) materialize(it,n).copy(end = n.wall, anchor = null) else it
        })
    private fun append(d: Dataset,c: Classification,note: String?,n: Moment): Dataset {
        val s = Session(status = SessionStatus.RUNNING, selection = c,note = note)
        return d.copy(sessions = d.sessions+s, segments = d.segments+open(s.id,c,n))
    }
    private fun open(id: String,c: Classification,n: Moment) = Segment(sessionId = id,start = n.wall,selection = c,anchor = Anchor(n.bootId,n.elapsed))
    private fun interval(start: Long,end: Long,n: Moment) = ensure(start >= 0 && end > start && end <= n.wall && end <= MAX_TIME,"Invalid or future interval",ErrorCode.INVALID_INTERVAL)
}

internal fun current(d: Dataset) = d.sessions.singleOrNull { it.status != SessionStatus.STOPPED }
internal fun token(d: Dataset) = Token(d.datasetId,d.revision,current(d)?.id)
internal fun checkToken(d: Dataset,t: Token,n: Moment) {
    if (token(d) != t) throw CoreError(ErrorCode.STALE,"Stale action; refresh snapshot",snapshot(d,n))
}
private fun List<Session>.replace(s: Session) = map { if (it.id == s.id) s else it }
internal fun <T> storage(block: () -> T): T = try { block() } catch (e: CoreError) { throw e } catch (e: Exception) { throw CoreError(ErrorCode.STORAGE,"Storage operation failed: ${e.javaClass.simpleName}") }

/** Returns an observed copy; never writes clock ticks. An old boot provides no elapsed-time evidence. */
fun materialize(s: Segment,n: Moment): Segment {
    if (s.end != null) return s
    val a = s.anchor
    if (a == null || a.bootId != n.bootId || n.elapsed < a.elapsedMs) return s.copy(anchor = null,uncertain = true)
    val duration = n.elapsed-a.elapsedMs
    return s.copy(durationMs = duration,observedUntil = n.wall,
        uncertain = s.uncertain || abs((n.wall-s.start)-duration) > 1000)
}
fun snapshot(d: Dataset,n: Moment): Snapshot {
    val s = current(d)
    val segs = d.segments.filter { it.sessionId == s?.id }.map { materialize(it,n) }
    val active = segs.singleOrNull { it.end == null }
    val uncertain = segs.any { it.uncertain }
    val mode = when { s == null -> Mode.IDLE; s.status == SessionStatus.RECOVERY || active?.uncertain == true -> Mode.RECOVERY
        s.status == SessionStatus.PAUSED -> Mode.PAUSED; else -> Mode.RUNNING }
    val actions = when(mode) { Mode.IDLE -> setOf("start","repeat","favorite","manual")
        Mode.RUNNING -> setOf("pause","stop","switch","label","details","switchedEarlier")
        Mode.PAUSED -> setOf("resume","stop","switch","label","details")
        Mode.RECOVERY -> setOf("resolveTail","label") }
    return Snapshot(token(d),mode,s,active,segs.fold(0L) { sum,seg -> Math.addExact(sum,seg.durationMs) },uncertain,
        when(mode) { Mode.IDLE -> "Таймер остановлен"; Mode.RUNNING -> "Идёт учёт времени"; Mode.PAUSED -> "Таймер на паузе"; Mode.RECOVERY -> "Требуется уточнить время" },actions)
}
