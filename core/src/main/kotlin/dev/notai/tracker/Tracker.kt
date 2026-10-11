package dev.notai.tracker

import kotlinx.coroutines.flow.map
import kotlin.math.abs

/** All calls are blocking: Android callers use Dispatchers.IO. No timer service or periodic writes. */
class Tracker(val store: StateStore, val clock: Clock) {
    private val choiceCache = ChoiceCache()
    private var undo: Pair<Token, Dataset>? = null
    val views get() = store.checkedChanges.map { TrackerView(it,clock,choiceCache) }
    fun view() = TrackerView(store.readChecked(),clock,choiceCache)
    val snapshots get() = store.checkedChanges.map { it.snapshot(clock.now()) }
    fun choices(): ChoiceView = view().choices()
    fun data(): Dataset = storage { store.read() }
    fun snapshot(): Snapshot = storage { store.readChecked().snapshot(clock.now()) }
    fun selection(cardId: String): Classification {
        val d = data()
        val card = d.cards.singleOrNull { it.id == cardId && !it.archived } ?: fail(ErrorCode.INVALID_INPUT,"Card missing or archived")
        ensure(card.activityTypeId == null || d.types.any { it.id == card.activityTypeId && !it.archived && it.selectable },"Type archived or not selectable")
        return d.classify(card.classification())
    }
    fun typeSelection(typeId: String): Classification {
        val d = data(); val type = d.types.find { it.id == typeId && !it.archived && it.selectable } ?: fail(ErrorCode.INVALID_INPUT,"Type missing or archived")
        return d.classify(Classification(name = type.name,activityTypeId = type.id))
    }

    data class Change(val state: Snapshot, val changedSessionIds: Set<String>, val undoAvailable: Boolean,
        val data: Dataset, val asOf: Moment, val catalogImport: CatalogImportReport? = null) {
        val changedSessions get() = data.sessions.filter { it.id in changedSessionIds }
        val changedSegments get() = data.segments.filter { it.sessionId in changedSessionIds }
        fun report(start: Long,end: Long,filter: Filter = Filter()) = Reports.report(data,asOf,start,end,filter)
        fun day(date: java.time.LocalDate,filter: Filter = Filter()) = Reports.day(data,asOf,date,filter)
    }
    @Synchronized private fun mutate(expected: Token, undoable: Boolean = false, edit: (Dataset, Moment) -> Dataset): Change = storage {
        store.checkedTransaction { checked ->
            val old = checked.data
            val now = clock.now()
            checkToken(old, expected, now)
            val next = edit(old, now).copy(revision = Math.addExact(old.revision, 1))
            checked.replace(next) { validated ->
                val changed = changedSessions(old,next)
                Triple(Change(validated.snapshot(now), changed, undoable, validated.data, now), old, next)
            }
        }.let { (change, previous, _) ->
            undo = if (undoable) change.state.token to previous else null
            change
        }
    }
    @Synchronized private fun timer(expected: Token,action: TimerAction,selection: Classification = Classification(),note: String? = null): Change = storage {
        store.checkedTransaction { checked ->
            val now = clock.now()
            if(checked.data.focus == null) checked.timer(expected,action,now,selection,note) { next,changed ->
                Change(next.snapshot(now),changed,action == TimerAction.SWITCH,next.data,now) to checked.data
            } else {
                val d = checked.data; checkToken(d,expected,now)
                val edited = when(action) {
                    TimerAction.START -> fail(ErrorCode.INVALID_INPUT,"Focus is already tracking")
                    TimerAction.PAUSE -> FocusTransitions.pause(d,now)
                    TimerAction.RESUME -> FocusTransitions.resume(d,now)
                    TimerAction.STOP -> FocusTransitions.cancel(d,now)
                    TimerAction.SWITCH -> { checkStartable(d,selection); append(FocusTransitions.cancel(d,now),selection,note,now) }
                }.copy(revision = Math.addExact(d.revision,1))
                checked.replace(edited) { next -> Change(next.snapshot(now),changedSessions(d,next.data),action == TimerAction.SWITCH,next.data,now) to d }
            }
        }.let { (change,old) ->
            undo = if(change.undoAvailable) change.state.token to old else null
            change
        }
    }
    fun start(expected: Token,selection: Classification = Classification(),note: String? = null) = timer(expected,TimerAction.START,selection,note)
    fun pause(expected: Token) = timer(expected,TimerAction.PAUSE)
    fun resume(expected: Token) = timer(expected,TimerAction.RESUME)
    fun stop(expected: Token) = timer(expected,TimerAction.STOP)
    fun switch(expected: Token,selection: Classification) = timer(expected,TimerAction.SWITCH,selection)
    /** Explicit late labeling applies to the entire current session, including its earlier segments. */
    fun labelCurrent(expected: Token, selection: Classification, note: String? = null) = mutate(expected, true) { d,_ ->
        val selected = d.classify(selection)
        val s = current(d) ?: fail(ErrorCode.INVALID_INPUT, "No current session")
        d.copy(sessions = d.sessions.replace(s.copy(selection = selected, note = note)),
            segments = d.segments.map { if (it.sessionId == s.id) it.copy(selection = selected) else it })
    }
    /** Change details from now; preserves session-scoped measurements without copying them. */
    fun changeDetails(expected: Token, selection: Classification) = mutate(expected, true) { d,n ->
        val selected = d.classify(selection)
        val s = current(d) ?: fail(ErrorCode.INVALID_INPUT, "No current session")
        ensure(s.selection.cardId == selection.cardId && (selection.cardId != null || s.selection.activityTypeId == selection.activityTypeId), "Use switch to change card/type")
        if(d.focus != null) FocusTransitions.details(d,n,selected)
        else if (s.status == SessionStatus.PAUSED) d.copy(sessions = d.sessions.replace(s.copy(selection = selected)))
        else {
            val closed = close(d,running(d,n),n,SessionStatus.PAUSED)
            closed.copy(sessions = closed.sessions.replace(s.copy(selection = selected)), segments = closed.segments + open(s.id,selected,n))
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
        d.types.find { it.id == type.id }?.let { old ->
            ensure(old.meaningVersion == type.meaningVersion && old.definition == type.definition,
                "A new type meaning/version/definition requires a new ID; existing history retains the old ID")
        }
        d.copy(types = d.types.filterNot { it.id == type.id } + type)
    }
    /** Install absent definitions only; report every added, identical and conflicting key. */
    fun addCatalog(expected: Token,catalog: VocabularyData): Change {
        lateinit var report: CatalogImportReport
        val change = mutate(expected) { d,_ ->
            val merged = d.mergeCatalog(catalog)
            report = merged.second
            merged.first
        }
        return change.copy(catalogImport = report)
    }
    fun saveAxis(expected: Token,axis: Axis) = mutate(expected) { d,_ ->
        d.axes.find { it.id == axis.id && it.meaningVersion == axis.meaningVersion }?.let { old ->
            ensure(old.valueKind == axis.valueKind && old.cardinality == axis.cardinality,"New axis semantics need a new version")
            ensure(old.values.all { v -> axis.values.any { it.id == v.id && it.meaningVersion == v.meaningVersion } },"Archive values instead of deleting them")
        }
        d.copy(axes = d.axes.filterNot { it.id == axis.id && it.meaningVersion == axis.meaningVersion } + axis)
    }
    fun saveMetric(expected: Token,metric: Metric) = mutate(expected) { d,_ ->
        d.metrics.find { it.id == metric.id && it.meaningVersion == metric.meaningVersion }?.let { old ->
            ensure(old.copy(name = metric.name) == metric,"New metric semantics need a new version")
        }
        d.copy(metrics = d.metrics.filterNot { it.id == metric.id && it.meaningVersion == metric.meaningVersion } + metric)
    }
    fun saveUnit(expected: Token,unit: UnitDefinition) = mutate(expected) { d,_ ->
        d.units.find { it.id == unit.id }?.let { old -> ensure(old.copy(name = unit.name) == unit,"New unit semantics need a new ID") }
        d.copy(units = d.units.filterNot { it.id == unit.id } + unit)
    }
    fun saveCard(expected: Token, card: Card) = mutate(expected) { d,_ ->
        d.copy(cards = d.cards.filterNot { it.id == card.id } + card)
    }
    fun copyCard(expected: Token, cardId: String, name: String) = mutate(expected) { d,_ ->
        val c = d.cards.find { it.id == cardId } ?: fail(ErrorCode.INVALID_INPUT, "Missing card")
        d.copy(cards = d.cards + c.copy(id = newId(), name = name, archived = false))
    }
    fun startFocus(expected: Token,selection: Classification = Classification(),plan: FocusPlan? = null) = mutate(expected) { d,n ->
        FocusTransitions.start(d,n,plan ?: d.settings.focusPlan,selection)
    }
    /** Changes defaults and the next phase only; the current phase keeps its explicit duration. */
    fun configureFocus(expected: Token,plan: FocusPlan) = mutate(expected) { d,_ ->
        validatePlan(plan); d.copy(settings = d.settings.copy(focusPlan = plan),focus = d.focus?.copy(plan = plan))
    }
    fun focusNext(expected: Token) = mutate(expected) { d,n -> FocusTransitions.next(d,n,true) }
    fun focusSkip(expected: Token) = mutate(expected) { d,n -> FocusTransitions.next(d,n,false) }
    fun pauseFocus(expected: Token) = mutate(expected) { d,n -> FocusTransitions.pause(d,n) }
    fun resumeFocus(expected: Token) = mutate(expected) { d,n -> FocusTransitions.resume(d,n) }
    fun cancelFocus(expected: Token) = mutate(expected) { d,n -> FocusTransitions.cancel(d,n) }
    /** Recovery ends the Focus run. Work boundaries are explicit; null/null excludes the tail.
     * An uncertain break has no work tail: null/null ends it without inventing elapsed work. */
    fun resolveFocus(expected: Token,start: Long?,end: Long?) = mutate(expected,true) { d,n ->
        val f = d.focus ?: fail(ErrorCode.INVALID_INPUT,"No Focus interval")
        ensure(Focus.snapshot(d,n)!!.mode == FocusMode.RECOVERY,"Focus does not need recovery")
        if(f.phase == FocusPhase.WORK) resolveTailData(d,n,start,end) else {
            ensure(start == null && end == null,"Break recovery has no work interval")
            d.copy(focus = null,sessions = d.sessions.map { if(it.id == f.sessionId) it.copy(status = SessionStatus.STOPPED) else it })
        }
    }
    fun saveGoal(expected: Token,goal: Goal) = mutate(expected,true) { d,_ ->
        d.copy(goals = if(d.goals.any { it.id == goal.id }) d.goals.map { if(it.id == goal.id) goal else it } else d.goals+goal)
    }
    fun deleteGoal(expected: Token,id: String) = mutate(expected,true) { d,_ ->
        ensure(d.goals.any { it.id == id },"Missing goal"); d.copy(goals = d.goals.filterNot { it.id == id })
    }
    fun goalProgress(id: String,date: java.time.LocalDate? = null): GoalProgress = view().goalProgress(id,date)
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
    fun correctSession(expected: Token,sessionId: String,edits: List<SegmentEdit>,note: String?,reclassify: Boolean = false) = mutate(expected,true) { d,n ->
        correctedSession(d,n,sessionId,edits,note,reclassify)
    }
    class CorrectionPreview internal constructor(val expected: Token,internal val sessionId: String,
        internal val edits: List<SegmentEdit>,internal val note: String?,internal val reclassify: Boolean,
        val before: Dataset,val after: Dataset,val asOf: Moment) {
        fun reports(start: Long,end: Long,filter: Filter = Filter()): Pair<Report,Report> =
            Reports.report(before,asOf,start,end,filter) to Reports.report(after,asOf,start,end,filter)
    }
    /** Read-only form preview. A later Save retains this token; cancel requires no command. */
    fun previewCorrection(sessionId: String,edits: List<SegmentEdit>,note: String?,reclassify: Boolean = false): CorrectionPreview = storage {
        store.transaction { d ->
            val now = clock.now(); validateMoment(now)
            val after = correctedSession(d,now,sessionId,edits,note,reclassify)
            validate(after)
            d to CorrectionPreview(token(d),sessionId,edits.toList(),note,reclassify,d,after,now)
        }
    }
    fun applyCorrection(preview: CorrectionPreview) = correctSession(preview.expected,preview.sessionId,
        preview.edits,preview.note,preview.reclassify)
    private fun correctedSession(d: Dataset,n: Moment,sessionId: String,edits: List<SegmentEdit>,note: String?,reclassify: Boolean): Dataset {
        val session = d.sessions.find { it.id == sessionId } ?: fail(ErrorCode.INVALID_INPUT,"Missing session")
        ensure(session.status == SessionStatus.STOPPED,"Use current-session commands while tracking")
        val own = d.segments.filter { it.sessionId == sessionId }
        ensure(edits.map { it.id }.toSet() == own.map { it.id }.toSet() && edits.size == own.size,"Supply each existing segment once")
        edits.forEach { interval(it.start,it.end,n) }
        val byId = edits.associate { e ->
            val old = own.single { it.id == e.id }
            e.id to e.copy(selection = historicalSelection(d,old.selection,e.selection,reclassify))
        }
        return d.copy(sessions = d.sessions.replace(session.copy(note = note,selection = byId.getValue(edits.maxBy { it.start }.id).selection)),
            segments = d.segments.map { segment -> byId[segment.id]?.let { e ->
                segment.copy(start = e.start,end = e.end,durationMs = e.end-e.start,selection = e.selection,anchor = null,uncertain = false,observedUntil = null)
            } ?: segment })
    }
    /** Start/end are explicit; calling this is the form's Save, not its preview. */
    internal fun importHistory(expected: Token,sessions: List<Session>,segments: List<Segment>) = mutate(expected,true) { d,n ->
        ensure(segments.all { it.end != null && it.end <= n.wall },"Imported interval is in the future",ErrorCode.INVALID_INTERVAL)
        d.copy(sessions = d.sessions+sessions,segments = d.segments+segments)
    }
    /** Called only after the caller's explicit confirmation. Event dates never backdate Start. */
    fun startDraft(expected: Token,draft: EntryDraft): Change {
        draft.validateForApply()
        return start(expected,draft.selection,draft.note)
    }
    fun recordDraft(expected: Token,draft: EntryDraft): Change {
        draft.validateForApply()
        val interval = draft.interval ?: fail(ErrorCode.INVALID_INTERVAL,"Confirm explicit start and end before recording")
        return manual(expected,interval.start,interval.end,draft.selection,draft.note)
    }
    fun manual(expected: Token, start: Long, end: Long, selection: Classification, note: String? = null) = mutate(expected, true) { d,n ->
        val selected = d.classify(selection)
        interval(start,end,n)
        val s = Session(status = SessionStatus.STOPPED, selection = selected, note = note)
        d.copy(sessions = d.sessions + s, segments = d.segments + Segment(sessionId = s.id,start = start,end = end,durationMs = end-start,selection = selected))
    }
    class BoundaryPreview internal constructor(val expected: Token,val left: Segment,val right: Segment,val boundary: Long)
    /** Read-only preview. Only an explicit apply changes the two stopped sessions. */
    fun previewBoundary(expected: Token,leftId: String,rightId: String,boundary: Long): BoundaryPreview {
        val d = data(); checkToken(d,expected,clock.now())
        val pair = boundarySegments(d,leftId,rightId,boundary)
        return BoundaryPreview(expected,pair.first,pair.second,boundary)
    }
    fun correctBoundary(preview: BoundaryPreview) = mutate(preview.expected,true) { d,_ ->
        val (left,right) = boundarySegments(d,preview.left.id,preview.right.id,preview.boundary)
        d.copy(segments = d.segments.map { when(it.id) {
            left.id -> it.copy(end = preview.boundary,durationMs = preview.boundary-it.start,observedUntil = null,anchor = null)
            right.id -> it.copy(start = preview.boundary,durationMs = it.end!!-preview.boundary,observedUntil = null,anchor = null)
            else -> it
        } })
    }
    private fun boundarySegments(d: Dataset,leftId: String,rightId: String,boundary: Long): Pair<Segment,Segment> {
        val left = d.segments.singleOrNull { it.id == leftId } ?: fail(ErrorCode.INVALID_INPUT,"Missing left segment")
        val right = d.segments.singleOrNull { it.id == rightId } ?: fail(ErrorCode.INVALID_INPUT,"Missing right segment")
        ensure(left.sessionId != right.sessionId && listOf(left,right).all { segment ->
            !segment.uncertain && segment.end != null && d.sessions.any { it.id == segment.sessionId && it.status == SessionStatus.STOPPED }
        },"Boundary correction requires two certain stopped sessions")
        ensure(left.end == right.start && boundary > left.start && boundary < right.end!!,"Invalid common boundary",ErrorCode.INVALID_INTERVAL)
        return left to right
    }
    fun correctSegment(expected: Token, segmentId: String, start: Long, end: Long, selection: Classification,reclassify: Boolean = false) = mutate(expected,true) { d,n ->
        interval(start,end,n)
        val seg = d.segments.find { it.id == segmentId } ?: fail(ErrorCode.INVALID_INPUT, "Missing segment")
        ensure(seg.end != null, "Resolve open tail instead")
        ensure(d.focus?.segmentIds?.contains(seg.id) != true,"Finish the current Focus phase before correcting its segments")
        val selected = historicalSelection(d,seg.selection,selection,reclassify)
        d.copy(segments = d.segments.map { if (it.id == segmentId) it.copy(start = start,end = end,durationMs = end-start,
            selection = selected,anchor = null,uncertain = false,observedUntil = null) else it })
    }
    /** Correct a forgotten switch inside the current uninterrupted segment in one transaction. */
    fun switchedEarlier(expected: Token, boundary: Long, selection: Classification) = mutate(expected,true) { d,n ->
        ensure(d.focus == null,"Finish Focus before a retrospective switch")
        checkStartable(d,selection)
        val s = running(d,n); val seg = d.segments.single { it.sessionId == s.id && it.end == null }
        val measured = materialize(seg,n)
        ensure(!measured.uncertain && boundary in seg.start..n.wall, "Boundary outside certain active interval", ErrorCode.INVALID_INTERVAL)
        val earlier = n.copy(wall = boundary, elapsed = n.elapsed - (n.wall-boundary))
        ensure(earlier.elapsed >= seg.anchor!!.elapsedMs, "Boundary before monotonic start", ErrorCode.INVALID_INTERVAL)
        append(close(d,s,earlier,SessionStatus.STOPPED),selection,null,earlier)
    }
    /** null interval excludes the unresolved tail. An explicit interval confirms/corrects it. */
    fun resolveTail(expected: Token, start: Long?, end: Long?) = mutate(expected,true) { d,n -> resolveTailData(d,n,start,end) }
    private fun resolveTailData(d: Dataset,n: Moment,start: Long?,end: Long?): Dataset {
        val s = current(d) ?: fail(ErrorCode.INVALID_INPUT, "No current session")
        val seg = d.segments.singleOrNull { it.sessionId == s.id && it.end == null } ?: fail(ErrorCode.INVALID_INPUT, "No open tail")
        ensure(snapshot(d,n).mode == Mode.RECOVERY, "Tail is not awaiting recovery")
        ensure((start == null) == (end == null), "Supply both boundaries or exclude")
        val segments = if (start == null) d.segments.filterNot { it.id == seg.id } else {
            interval(start,end!!,n)
            d.segments.map { if (it.id == seg.id) it.copy(start = start,end = end,durationMs = end-start,anchor = null,uncertain = false,observedUntil = null,limitMs = null) else it }
        }
        val keep = segments.any { it.sessionId == s.id }
        return d.copy(focus = null,segments = segments, sessions = if (keep) d.sessions.replace(s.copy(status = SessionStatus.STOPPED)) else d.sessions.filterNot { it.id == s.id },
            measurements = d.measurements.filterNot { (it.scope == Scope.SEGMENT && it.targetId == seg.id && start == null) || (!keep && it.scope == Scope.SESSION && it.targetId == s.id) })
    }
    @Synchronized fun canUndo(expected: Token): Boolean = undo?.first == expected
    @Synchronized fun undo(expected: Token): Change {
        val saved = undo
        if (saved == null || saved.first != expected) throw CoreError(ErrorCode.STALE,"No matching available undo",snapshot())
        return mutate(expected) { _,_ -> saved.second }
    }
    private fun changedSessions(old: Dataset,next: Dataset): Set<String> {
        val changed = mutableSetOf<String>()
        fun <T> compare(a: List<T>,b: List<T>,id: (T)->String,affected: (T,Boolean)->String?) {
            if(a === b) return
            val before = a.associateBy(id); val after = b.associateBy(id)
            for(key in before.keys+after.keys) if(before[key] != after[key]) {
                before[key]?.let { affected(it,false)?.let(changed::add) }
                after[key]?.let { affected(it,true)?.let(changed::add) }
            }
        }
        compare(old.sessions,next.sessions,{ it.id }) { s,_ -> s.id }
        compare(old.segments,next.segments,{ it.id }) { s,_ -> s.sessionId }
        val before = old.segments.associate { it.id to it.sessionId }; val after = next.segments.associate { it.id to it.sessionId }
        compare(old.measurements,next.measurements,{ it.id }) { m,isAfter ->
            if(m.scope == Scope.SESSION) m.targetId else (if(isAfter) after else before)[m.targetId]
        }
        return changed
    }
    private fun running(d: Dataset,n: Moment): Session {
        val s = current(d) ?: fail(ErrorCode.INVALID_INPUT, "No current session")
        ensure(snapshot(d,n).mode != Mode.RECOVERY, "Resolve uncertain time first", ErrorCode.RECOVERY_REQUIRED)
        ensure(s.status == SessionStatus.RUNNING, "Session is not running")
        return s
    }
    private fun close(d: Dataset,s: Session,n: Moment,status: SessionStatus): Dataset = d.copy(
        sessions = d.sessions.replace(s.copy(status = status)), segments = d.segments.map {
            if (it.sessionId == s.id && it.end == null) closeMeasured(it,n) else it
        })
    private fun historicalSelection(d: Dataset,old: Classification,chosen: Classification,reclassify: Boolean): Classification =
        if(reclassify || old.activityTypeId != chosen.activityTypeId) d.classify(chosen)
        else chosen.copy(ancestorPathAtWrite = old.ancestorPathAtWrite)
    private fun append(d: Dataset,c: Classification,note: String?,n: Moment): Dataset {
        val selected = d.classify(c)
        val s = Session(status = SessionStatus.RUNNING, selection = selected,note = note)
        return d.copy(sessions = d.sessions+s, segments = d.segments+open(s.id,selected,n))
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
    validateMoment(n)
    validateSegmentTime(s)
    if (s.end != null) return s
    val a = s.anchor
    if (a == null || a.bootId != n.bootId || n.elapsed < a.elapsedMs || (a.processId != null && a.processId != n.processId)) return s.copy(anchor = null,uncertain = true)
    val duration = n.elapsed-a.elapsedMs
    ensure(duration in 0..MAX_TIME,"Elapsed duration out of supported range",ErrorCode.INVALID_INTERVAL)
    val uncertain = s.uncertain || abs((n.wall-s.start)-duration) > 1000
    val bounded = minOf(duration,s.limitMs ?: duration)
    val observed = if(s.limitMs != null && duration >= s.limitMs && !uncertain) Math.addExact(s.start,bounded) else n.wall
    return s.copy(durationMs = bounded,observedUntil = observed,uncertain = uncertain)
}
internal fun closeMeasured(s: Segment,n: Moment): Segment {
    val measured = materialize(s,n)
    ensure(!measured.uncertain,"Resolve uncertain time first",ErrorCode.RECOVERY_REQUIRED)
    return measured.copy(end = measured.observedUntil ?: n.wall,anchor = null,limitMs = null)
}
fun snapshot(d: Dataset,n: Moment): Snapshot {
    validateMoment(n)
    val s = current(d)
    val segs = d.segments.filter { it.sessionId == s?.id }.map { materialize(it,n) }
    val active = segs.singleOrNull { it.end == null }
    val uncertain = segs.any { it.uncertain }
    val focus = Focus.snapshot(d,n)
    val mode = when { focus?.mode == FocusMode.RECOVERY -> Mode.RECOVERY; s == null -> Mode.IDLE; s.status == SessionStatus.RECOVERY || active?.uncertain == true -> Mode.RECOVERY
        s.status == SessionStatus.PAUSED -> Mode.PAUSED; else -> Mode.RUNNING }
    val actions = if(focus != null) when(focus.mode) {
        FocusMode.RUNNING -> setOf("pause","stop","switch","label","details","focusSkip","configureFocus")
        FocusMode.PAUSED -> setOf("resume","stop","switch","label","details","focusSkip","configureFocus")
        FocusMode.DUE -> setOf("stop","switch","label","details","focusNext","focusSkip","configureFocus")
        FocusMode.RECOVERY -> setOf("resolveFocus","label")
    } else when(mode) { Mode.IDLE -> setOf("start","repeat","favorite","manual")
        Mode.RUNNING -> setOf("pause","stop","switch","label","details","switchedEarlier")
        Mode.PAUSED -> setOf("resume","stop","switch","label","details")
        Mode.RECOVERY -> setOf("resolveTail","label") }
    return Snapshot(token(d),mode,s,active,segs.sumExact { it.durationMs },uncertain || focus?.mode == FocusMode.RECOVERY,
        focus?.safeLabel ?: when(mode) { Mode.IDLE -> "Таймер остановлен"; Mode.RUNNING -> "Идёт учёт времени"; Mode.PAUSED -> "Таймер на паузе"; Mode.RECOVERY -> "Требуется уточнить время" },actions,focus)
}
