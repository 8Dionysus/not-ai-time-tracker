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
    @Synchronized fun previewTransferRetention(selection: TransferRetentionSelection): TransferRetentionPreview = storage {
        val d = data();TransferRetention.preview(d,selection,canUndo(token(d)))
    }
    @Synchronized fun applyTransferRetention(preview: TransferRetentionPreview,commandId: String,acknowledged: Set<RetentionWarningKey>): TransferCompactionReceipt = storage {
        val captured = frozenSet(acknowledged)
        val (receipt,changed,currentToken) = store.transaction { d ->
            val (next,result) = TransferRetention.apply(d,preview,commandId,captured,canUndo(token(d)),clock.now())
            next to Triple(result.first,result.second,token(next))
        }
        if(changed || undo?.first != currentToken) undo = null // never retain a process-local Dataset that could resurrect pruned snapshots
        receipt
    }
    fun transferRetentionReceipt(commandId: String): TransferCompactionReceipt? = data().transfer.compactions.find { it.commandId == commandId }
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
        val data: Dataset, val asOf: Moment, val catalogImport: CatalogImportReport? = null, val assignment: StoppedAssignment? = null) {
        val changedSessions get() = data.sessions.filter { it.id in changedSessionIds }
        val changedSegments get() = data.segments.filter { it.sessionId in changedSessionIds }
        fun report(start: Long,end: Long,filter: Filter = Filter()) = Reports.report(data,asOf,start,end,filter)
        fun day(date: java.time.LocalDate,filter: Filter = Filter()) = Reports.day(data,asOf,date,filter)
    }
    @Synchronized private fun mutate(expected: Token?, undoable: Boolean = false, edit: (Dataset, Moment) -> Dataset): Change = storage {
        store.checkedTransaction { checked ->
            val old = checked.data
            val now = clock.now()
            if(expected != null) checkToken(old, expected, now)
            val proposed = edit(old,now)
            val removedMeasurements = old.measurements.map { it.id }.toSet()-proposed.measurements.map { it.id }.toSet()
            val edited = if(removedMeasurements.isEmpty()) proposed else proposed.copy(subjects = proposed.subjects.copy(
                distanceTimes = proposed.subjects.distanceTimes.filterNot { it.measurementId in removedMeasurements }))
            val identityChanged = timerIdentity(old) != timerIdentity(edited)
            val next = edited.copy(revision = Math.addExact(old.revision,1),timerEpoch = if(identityChanged) Math.addExact(old.timerEpoch,1) else old.timerEpoch)
            checked.replace(next) { validated ->
                val changed = changedSessions(old,next)
                Triple(Change(validated.snapshot(now), changed, undoable, validated.data, now), old, next)
            }
        }.let { (change, previous, _) ->
            undo = if (undoable) change.state.token to previous else null
            change
        }
    }
    @Synchronized private fun timer(expected: Token?,action: TimerAction,selection: Classification = Classification(),note: String? = null,target: TimerTarget? = null,
        allowed: (Dataset)->Boolean = { true },assignmentAfterStop: Boolean = false): Change = storage {
        store.checkedTransaction { checked ->
            val now = clock.now()
            ensure(allowed(checked.data),"Surface disabled or permission denied")
            val actualToken = if(target == null) expected!! else {
                val current = checked.snapshot(now)
                ensure(target == current.timerTarget,"Timer target changed; refresh controls",ErrorCode.STALE)
                current.token // original subject target was checked atomically; no catch/retry of a stale global token
            }
            val commit = if(checked.data.focus == null) checked.timer(actualToken,action,now,selection,note) { next,changed ->
                Change(next.snapshot(now),changed,action == TimerAction.SWITCH,next.data,now) to checked.data
            } else {
                val d = checked.data; checkToken(d,actualToken,now)
                val edited = when(action) {
                    TimerAction.START -> fail(ErrorCode.INVALID_INPUT,"Focus is already tracking")
                    TimerAction.PAUSE -> FocusTransitions.pause(d,now)
                    TimerAction.RESUME -> FocusTransitions.resume(d,now)
                    TimerAction.STOP -> FocusTransitions.cancel(d,now)
                    TimerAction.SWITCH -> { checkStartable(d,selection); append(FocusTransitions.cancel(d,now),selection,note,now) }
                }.copy(revision = Math.addExact(d.revision,1),timerEpoch = Math.addExact(d.timerEpoch,1))
                checked.replace(edited) { next -> Change(next.snapshot(now),changedSessions(d,next.data),action == TimerAction.SWITCH,next.data,now) to d }
            }
            if(assignmentAfterStop) {
                ensure(action == TimerAction.STOP && actualToken.sessionId != null,"Assignment requires Stop")
                checked.addAssignment(commit,actualToken.sessionId!!) { next,offer ->
                    commit.result.first.copy(data = next.data,assignment = offer) to checked.data
                }
            } else commit
        }.let { (change,old) ->
            undo = if(change.undoAvailable) change.state.token to old else null
            change
        }
    }
    fun subjects(): SubjectRead = SubjectRead(data())
    /** Snapshot, token, validation and Undo use the same transaction path as timer edits. */
    fun defineSubject(expected: Token,card: CardSubject) = mutate(expected,true) { d,_ ->
        ensure(d.cards.any { it.id == card.cardId && !it.archived },"Card missing/archived")
        d.subjects.cards.find { it.cardId == card.cardId }?.let { ensure(it == card,"Subject kind is immutable") }
        d.copy(subjects = d.subjects.copy(cards = d.subjects.cards.filterNot { it.cardId == card.cardId }+card))
    }
    fun saveSubjectEvent(expected: Token,event: SubjectEvent) = mutate(expected,true) { d,_ ->
        d.subjects.events.find { it.id == event.id }?.let { ensure(it.copy(archived = event.archived) == event,"Event identity/meaning is immutable") }
        d.copy(subjects = d.subjects.copy(events = d.subjects.events.filterNot { it.id == event.id }+event))
    }
    fun linkSubject(expected: Token,link: SubjectSessionLink) = mutate(expected,true) { d,_ ->
        ensure(when(link.subject.kind) { SubjectKind.CARD -> d.cards.any { it.id == link.subject.id && !it.archived };SubjectKind.EVENT -> d.subjects.events.any { it.id == link.subject.id && !it.archived } },"Subject archived/missing")
        ensure(d.subjects.links.none { it.id == link.id },"Link ID already exists")
        d.copy(subjects = d.subjects.copy(links = d.subjects.links+link))
    }
    fun unlinkSubject(expected: Token,id: String) = mutate(expected,true) { d,_ ->
        ensure(d.subjects.links.any { it.id == id },"Link missing")
        d.copy(subjects = d.subjects.copy(links = d.subjects.links.filterNot { it.id == id }))
    }
    fun observeSubject(expected: Token,observation: StateObservation) = mutate(expected,true) { d,n ->
        ensure(observation.recordedAt <= n.wall && d.subjects.observations.none { it.id == observation.id },"Observation ID exists or recorded in future")
        d.copy(subjects = d.subjects.copy(observations = d.subjects.observations+observation))
    }
    fun recordSubjectChange(expected: Token,change: SubjectChange) = mutate(expected,true) { d,n ->
        ensure(change.recordedAt <= n.wall && d.subjects.changes.none { it.id == change.id },"Change ID exists or recorded in future")
        d.copy(subjects = d.subjects.copy(changes = d.subjects.changes+change))
    }
    fun recordSet(expected: Token,set: ExerciseSet) = mutate(expected,true) { d,n ->
        ensure(set.recordedAt <= n.wall && d.subjects.sets.none { it.id == set.id },"Set ID exists or recorded in future")
        d.copy(subjects = d.subjects.copy(sets = d.subjects.sets+set))
    }
    fun recordEventQuantity(expected: Token,quantity: EventQuantity) = mutate(expected,true) { d,n ->
        ensure(quantity.recordedAt <= n.wall && d.subjects.quantities.none { it.id == quantity.id },"Quantity ID exists or recorded in future")
        d.copy(subjects = d.subjects.copy(quantities = d.subjects.quantities+quantity))
    }
    fun pairDistanceTime(expected: Token,input: DistanceTimeInput) = mutate(expected,true) { d,_ ->
        ensure(d.subjects.distanceTimes.none { it.id == input.id },"Rate input ID exists")
        ensure(distanceTimeMatches(d,input),"Rate input differs from current target")
        d.copy(subjects = d.subjects.copy(distanceTimes = d.subjects.distanceTimes+input))
    }
    fun timerCommand(target: TimerTarget,directive: TimerDirective,allowed: (Dataset)->Boolean = { true }): Change = timer(null,when(directive) {
        TimerDirective.START -> TimerAction.START;TimerDirective.PAUSE -> TimerAction.PAUSE
        TimerDirective.RESUME -> TimerAction.RESUME;TimerDirective.STOP -> TimerAction.STOP
    },target = target,allowed = allowed)
    fun start(expected: Token,selection: Classification = Classification(),note: String? = null) = timer(expected,TimerAction.START,selection,note)
    fun pause(expected: Token) = timer(expected,TimerAction.PAUSE)
    fun resume(expected: Token) = timer(expected,TimerAction.RESUME)
    fun stop(expected: Token) = timer(expected,TimerAction.STOP)
    fun switch(expected: Token,selection: Classification,note: String? = null) = timer(expected,TimerAction.SWITCH,selection,note)
    /** Stop commits elapsed time and its durable assignment receipt in the same transaction. */
    fun stopForAssignment(target: TimerTarget,allowed: (Dataset)->Boolean = { true }) =
        timer(null,TimerAction.STOP,target = target,allowed = allowed,assignmentAfterStop = true)
    fun pendingAssignments(): List<StoppedAssignment> = data().let { d -> d.pendingAssignments.filter { offer ->
        assignmentValid(d,offer)
    } }
    private fun checkedOffer(d: Dataset,id: String): StoppedAssignment {
        val offer = d.pendingAssignments.find { it.id == id } ?: fail(ErrorCode.STALE,"Assignment was handled or expired")
        ensure(assignmentValid(d,offer),"Completed record changed",ErrorCode.STALE)
        return offer
    }
    /** Explicit fresh request for a saved record. Rotate the nonce; don't rebind an old surface command. */
    fun reopenAssignment(expected: Token,sessionId: String,allowed: (Dataset)->Boolean = { true }): Change {
        lateinit var renewed: StoppedAssignment
        val change = mutate(expected) { d,_ ->
            ensure(allowed(d),"Surface disabled or permission denied")
            val previous = d.pendingAssignments.find { it.sessionId == sessionId }
                ?: fail(ErrorCode.STALE,"Saved assignment was handled, deleted or is unavailable")
            checkedOffer(d,previous.id)
            renewed = previous.copy(id = newId(),timerEpoch = d.timerEpoch)
            d.copy(pendingAssignments = d.pendingAssignments.map { if(it.id == previous.id) renewed else it })
        }
        return change.copy(assignment = renewed)
    }
    /** Explicit cancellation retains the completed time; receipt consumption is durable and replay-safe. */
    fun cancelAssignment(id: String) = mutate(null) { d,_ ->
        checkedOffer(d,id); d.copy(pendingAssignments = d.pendingAssignments.filterNot { it.id == id })
    }
    fun assignStopped(id: String,choice: ChoiceReference,note: String? = null,requestTarget: TimerTarget? = null,allowed: (Dataset)->Boolean = { true }) = mutate(null,true) { d,n ->
        ensure(allowed(d),"Surface disabled or permission denied")
        ensure(requestTarget == null || snapshot(d,n).timerTarget == requestTarget,"Picker timer target changed",ErrorCode.STALE)
        val offer = checkedOffer(d,id)
        val entry = ChoiceView(d,choiceCache) { snapshot(d,n) }.resolve(choice)
        assignResolvedChoice(d,offer,entry,note)
    }
    private fun assignResolvedChoice(d: Dataset,offer: StoppedAssignment,entry: ChoiceEntry,note: String?): Dataset {
        checkStartable(d,entry.selection)
        val selected = d.classify(entry.selection)
        return d.copy(sessions = d.sessions.map { if(it.id == offer.sessionId) it.copy(selection = selected,note = note ?: it.note) else it },
            segments = d.segments.map { if(it.sessionId == offer.sessionId) it.copy(selection = selected) else it },
            pendingAssignments = d.pendingAssignments.filterNot { it.id == offer.id })
    }
    /** Fresh resolution and timer subject validation occur inside the same transaction. */
    fun startChoice(target: TimerTarget,choice: ChoiceReference,allowed: (Dataset)->Boolean = { true }) = applyChoice(target,choice,ChoiceApplication.START,allowed)
    fun applyChoice(target: TimerTarget,choice: ChoiceReference,application: ChoiceApplication,allowed: (Dataset)->Boolean = { true }) = mutate(null,application == ChoiceApplication.SWITCH) { d,n ->
        ensure(allowed(d),"Surface disabled or permission denied")
        ensure(snapshot(d,n).timerTarget == target,"Timer target changed",ErrorCode.STALE)
        val entry = ChoiceView(d,choiceCache) { snapshot(d,n) }.resolve(choice)
        applyResolvedChoice(d,n,entry,application)
    }
    private fun applyResolvedChoice(d: Dataset,n: Moment,entry: ChoiceEntry,application: ChoiceApplication): Dataset {
        checkStartable(d,entry.selection)
        val base = when(application) {
            ChoiceApplication.START -> { ensure(current(d) == null && d.focus == null,"Stop current session first"); d }
            ChoiceApplication.SWITCH -> {
                ensure(snapshot(d,n).mode != Mode.RECOVERY,"Resolve uncertain time first",ErrorCode.RECOVERY_REQUIRED)
                if(d.focus != null) FocusTransitions.cancel(d,n) else close(d,current(d) ?: fail(ErrorCode.INVALID_INPUT,"No current session"),n,SessionStatus.STOPPED)
            }
        }
        return append(base,entry.selection,choiceStartNote(d,entry.kind,entry.sourceId,entry.selection,entry.note),n)
    }
    /** Create and apply under the original route/receipt in one transaction: failures leave no card. */
    fun createCardForChoice(expected: Token,target: TimerTarget,card: Card,application: ChoiceApplication,
        assignmentId: String? = null,allowed: (Dataset)->Boolean = { true }) = mutate(expected,assignmentId != null || application == ChoiceApplication.SWITCH) { d,n ->
        ensure(allowed(d),"Surface disabled or permission denied")
        ensure(snapshot(d,n).timerTarget == target,"Picker timer target changed",ErrorCode.STALE)
        ensure(d.cards.none { it.id == card.id },"Card ID already exists")
        validateCardParent(d,card)
        val next = d.copy(cards = d.cards+card)
        val entry = ChoiceView(next,choiceCache) { snapshot(next,n) }.resolve("card:${card.id}")
            ?: fail(ErrorCode.INVALID_INPUT,"Created card unavailable")
        if(assignmentId != null) assignResolvedChoice(next,checkedOffer(d,assignmentId),entry,null)
        else applyResolvedChoice(next,n,entry,application)
    }
    class DeletePreview internal constructor(val expected: Token,val sessionId: String,val before: Dataset,val after: Dataset,val asOf: Moment) {
        val segmentCount get() = before.segments.count { it.sessionId == sessionId }
        val measurementCount get() = before.measurements.size-after.measurements.size
        val subjectLinkCount get() = before.subjects.links.size-after.subjects.links.size
        val exerciseSetCount get() = before.subjects.sets.size-after.subjects.sets.size
        val eventQuantityCount get() = before.subjects.quantities.size-after.subjects.quantities.size
        val distanceTimeCount get() = before.subjects.distanceTimes.size-after.subjects.distanceTimes.size
        val subjectCorrectionCount get() = before.subjects.sets.count { it.sessionId == sessionId && it.replacesId != null }+
            before.subjects.quantities.count { it.sessionId == sessionId && it.replacesId != null }
        val hasDependentFacts get() = measurementCount > 0 || subjectLinkCount > 0 || exerciseSetCount > 0 || eventQuantityCount > 0 || distanceTimeCount > 0
        fun reports(start: Long,end: Long,filter: Filter = Filter()) = Reports.report(before,asOf,start,end,filter) to Reports.report(after,asOf,start,end,filter)
    }
    /** Opening/cancelling confirmation has no writes. Applying is the explicit confirmation. */
    fun previewDeleteSession(id: String): DeletePreview = storage { store.transaction { d ->
        val now = clock.now(); d to DeletePreview(token(d),id,d.frozen(),deletedSession(d,id).frozen(),now)
    } }
    fun applyDeleteSession(preview: DeletePreview) = deleteSession(preview.expected,preview.sessionId)
    fun deleteSession(expected: Token,id: String) = mutate(expected,true) { d,_ -> deletedSession(d,id) }
    private fun deletedSession(d: Dataset,id: String): Dataset {
        ensure(d.sessions.any { it.id == id && it.status == SessionStatus.STOPPED } && d.focus?.sessionId != id,"Only completed sessions can be deleted")
        d.schedule.links.firstOrNull { it.sessionId == id }?.let { link ->
            throw CoreError(ErrorCode.INVALID_INPUT,"Session is linked to a schedule occurrence",reason = ErrorReason.SCHEDULE_LINKED_SESSION,
                fieldPath = "session.id",arguments = mapOf("sessionId" to ErrorArgument.Identifier(id),
                    "seriesId" to ErrorArgument.Identifier(link.key.seriesId),"originalDate" to ErrorArgument.Text(link.key.originalDate)))
        }
        val segmentIds = d.segments.filter { it.sessionId == id }.map { it.id }.toSet()
        return d.copy(subjects = d.subjects.copy(links = d.subjects.links.filterNot { it.sessionId == id },sets = d.subjects.sets.filterNot { it.sessionId == id },quantities = d.subjects.quantities.filterNot { it.sessionId == id },distanceTimes = d.subjects.distanceTimes.filterNot { (it.scope == Scope.SESSION && it.targetId == id) || (it.scope == Scope.SEGMENT && it.targetId in segmentIds) }),sessions = d.sessions.filterNot { it.id == id },segments = d.segments.filterNot { it.sessionId == id },
            measurements = d.measurements.filterNot { (it.scope == Scope.SESSION && it.targetId == id) || (it.scope == Scope.SEGMENT && it.targetId in segmentIds) },
            pendingAssignments = d.pendingAssignments.filterNot { it.sessionId == id })
    }
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
    fun repeat(expected: Token, sessionId: String,notePolicy: ChoiceNotePolicy? = null) = mutate(expected) { d,n ->
        ensure(current(d) == null, "Stop or switch current session first")
        val s = d.sessions.find { it.id == sessionId } ?: fail(ErrorCode.INVALID_INPUT, "Missing session")
        val lastSelection = d.segments.filter { it.sessionId == sessionId }.maxByOrNull { it.start }?.selection ?: s.selection
        append(d,lastSelection,when(notePolicy) { ChoiceNotePolicy.CLEAR -> null;ChoiceNotePolicy.COPY_SOURCE -> s.note;null -> choiceStartNote(d,ChoiceKind.HISTORY,sessionId,lastSelection,s.note) },n)
    }
    /** Repeat the explicitly shown historical segment, preserving the existing history note policy.
     * The caller's revision and source IDs are checked within this single ordinary start/switch. */
    fun repeatSegment(expected: Token,sessionId: String,segmentId: String,application: ChoiceApplication = ChoiceApplication.START,
        notePolicy: ChoiceNotePolicy? = null) = mutate(expected,application == ChoiceApplication.SWITCH) { d,n ->
        val source = d.sessions.singleOrNull { it.id == sessionId } ?: fail(ErrorCode.STALE,"Source session no longer exists")
        val segment = d.segments.singleOrNull { it.id == segmentId && it.sessionId == sessionId } ?: fail(ErrorCode.STALE,"Source segment no longer exists")
        checkStartable(d,segment.selection)
        val note = when(notePolicy) {
            ChoiceNotePolicy.CLEAR -> null
            ChoiceNotePolicy.COPY_SOURCE -> source.note
            null -> choiceStartNote(d,ChoiceKind.HISTORY,sessionId,segment.selection,source.note)
        }
        val base = when(application) {
            ChoiceApplication.START -> { ensure(current(d) == null && d.focus == null,"Stop current session first");d }
            ChoiceApplication.SWITCH -> {
                ensure(snapshot(d,n).mode != Mode.RECOVERY,"Resolve uncertain time first",ErrorCode.RECOVERY_REQUIRED)
                if(d.focus != null) FocusTransitions.cancel(d,n) else close(d,current(d) ?: fail(ErrorCode.INVALID_INPUT,"No current session"),n,SessionStatus.STOPPED)
            }
        }
        append(base,segment.selection,note,n)
    }
    fun startFavorite(expected: Token, favoriteId: String) = mutate(expected) { d,n ->
        ensure(current(d) == null, "Stop or switch current session first")
        val f = d.favorites.find { it.id == favoriteId } ?: fail(ErrorCode.INVALID_INPUT, "Missing favorite")
        append(d,f.selection,choiceStartNote(d,ChoiceKind.FAVORITE,f.id,f.selection,null),n)
    }
    fun removeFavorite(expected: Token, favoriteId: String) = mutate(expected,true) { d,_ ->
        ensure(d.favorites.any { it.id == favoriteId }, "Missing favorite")
        d.copy(favorites = d.favorites.filterNot { it.id == favoriteId })
    }
    /** User add is idempotent by semantic selection and note policy. Explicit ID edits use saveFavorite.
     * Existing duplicates and their identities are retained; adding never renames an existing favorite. */
    fun addFavorite(expected: Token, favorite: Favorite) = mutate(expected,true) { d,_ ->
        val same = d.favorites.any { selectionIdentity(it.selection) == selectionIdentity(favorite.selection) &&
            (it.personal.notePolicy ?: ChoiceNotePolicy.CLEAR) == (favorite.personal.notePolicy ?: ChoiceNotePolicy.CLEAR) }
        if(same) d else {
            ensure(d.favorites.none { it.id == favorite.id },"Favorite ID already exists; use saveFavorite to edit")
            d.copy(favorites = d.favorites + favorite)
        }
    }
    fun saveFavorite(expected: Token, favorite: Favorite) = mutate(expected) { d,_ ->
        d.copy(favorites = d.favorites.filterNot { it.id == favorite.id } + favorite.copy(personal = d.favorites.find { it.id == favorite.id }?.personal ?: favorite.personal))
    }
    fun saveType(expected: Token, type: ActivityType) = mutate(expected) { d,_ ->
        d.types.find { it.id == type.id }?.let { old ->
            ensure(old.meaningVersion == type.meaningVersion && old.definition == type.definition,
                "A new type meaning/version/definition requires a new ID; existing history retains the old ID")
        }
        val old = d.types.find { it.id == type.id }
        if(type.parentId != null && (old == null || old.parentId != type.parentId))
            ensure(d.types.any { it.id == type.parentId && !it.archived },"Parent missing or archived")
        d.copy(types = d.types.filterNot { it.id == type.id } + type)
    }
    /** Install absent definitions only; report every added, identical and conflicting key. */
    fun addCatalog(expected: Token,catalog: VocabularyData,rejectConflicts: Boolean = false,
        policy: CatalogInstallPolicy = CatalogInstallPolicy.STRICT,catalogVersion: String? = null): Change {
        return installCatalog(expected,catalog,rejectConflicts,policy,catalogVersion)
    }
    internal fun addPreparedCatalog(expected: Token,catalog: ReadyCatalog): Change = installCatalog(expected,catalog.vocabulary,true,
        CatalogInstallPolicy.PRESERVE_LOCAL_PRESENTATION,catalog.manifest.catalogVersion,catalog.revisions)
    private fun installCatalog(expected: Token,catalog: VocabularyData,rejectConflicts: Boolean,policy: CatalogInstallPolicy,catalogVersion: String?,
        revisions: List<CatalogEditorialRevision> = emptyList()): Change {
        lateinit var report: CatalogImportReport
        val change = mutate(expected) { d,_ ->
            val merged = d.mergeCatalog(catalog,policy,revisions)
            report = merged.second
            ensure(!rejectConflicts || report.conflicts.isEmpty(),"Catalog conflicts require review; nothing was installed")
            merged.first.copy(catalogVersion = catalogVersion ?: d.catalogVersion)
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
        validateCardParent(d,card)
        d.copy(cards = d.cards.filterNot { it.id == card.id } + card.copy(personal = d.cards.find { it.id == card.id }?.personal ?: card.personal))
    }
    private fun validateCardParent(d: Dataset,card: Card) {
        val old = d.cards.find { it.id == card.id }
        if(card.activityTypeId != null && (old == null || old.activityTypeId != card.activityTypeId))
            ensure(d.types.any { it.id == card.activityTypeId && !it.archived },"Activity type missing or archived")
    }
    /** Profile edits have a dedicated boundary: ordinary card/catalog saves preserve them. */
    fun personalizeChoice(expected: Token,kind: ChoiceKind,id: String,value: ChoicePersonalization) = mutate(expected,true) { d,_ ->
        validatePersonalChoice(value)
        when(kind) {
            ChoiceKind.CARD -> { ensure(d.cards.any { it.id == id },"Missing card");d.copy(cards = d.cards.map { if(it.id == id) it.copy(personal = value) else it }) }
            ChoiceKind.FAVORITE -> { ensure(d.favorites.any { it.id == id },"Missing favorite");d.copy(favorites = d.favorites.map { if(it.id == id) it.copy(personal = value) else it }) }
            else -> fail(ErrorCode.INVALID_INPUT,"Personal profiles require Card/Favorite IDs")
        }
    }
    /** Supplied IDs are a pinned prefix; omitted IDs are explicitly unpinned. */
    fun orderChoices(expected: Token,kind: ChoiceKind,ids: List<String>): Change {
        val captured = frozenList(ids)
        return mutate(expected,true) { d,_ ->
            ensure(captured.size <= 1_000_000 && captured.distinct().size == captured.size,"Duplicate/oversize order")
            val ranks = captured.withIndex().associate { it.value to it.index }
            when(kind) {
                ChoiceKind.CARD -> { ensure(captured.all { id -> d.cards.any { it.id == id } },"Missing card");d.copy(cards = d.cards.map { it.copy(personal = it.personal.copy(order = ranks[it.id])) }) }
                ChoiceKind.FAVORITE -> { ensure(captured.all { id -> d.favorites.any { it.id == id } },"Missing favorite");d.copy(favorites = d.favorites.map { it.copy(personal = it.personal.copy(order = ranks[it.id])) }) }
                else -> fail(ErrorCode.INVALID_INPUT,"Ordering requires Card/Favorite IDs")
            }
        }
    }
    fun copyCard(expected: Token, cardId: String, name: String) = mutate(expected) { d,_ ->
        val c = d.cards.find { it.id == cardId } ?: fail(ErrorCode.INVALID_INPUT, "Missing card")
        d.copy(cards = d.cards + c.copy(id = newId(), name = name, archived = false,personal = c.personal.copy(order = null)))
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
    fun saveGoal(expected: Token,goal: Goal,mode: GoalEditMode = GoalEditMode.NEXT_PERIOD) = mutate(expected,true) { d,n ->
        Goals.save(d,n,goal,mode)
    }
    fun deleteGoal(expected: Token,id: String) = mutate(expected,true) { d,_ ->
        ensure(d.goals.any { it.id == id },"Missing goal"); d.copy(goals = d.goals.filterNot { it.id == id })
    }
    fun goalProgress(id: String,date: java.time.LocalDate? = null): GoalProgress = view().goalProgress(id,date)
    fun configureReportingDay(expected: Token,policy: ReportingDayPolicy) = mutate(expected,true) { d,_ ->
        policy.validate();d.copy(settings = d.settings.copy(reportZone = policy.zone,reportDayStartMinute = policy.startMinute))
    }
    fun saveReportQuery(expected: Token,query: SavedReportQuery) = mutate(expected,true) { d,_ ->
        d.copy(savedReports = d.savedReports.filterNot { it.id == query.id }+query)
    }
    fun deleteReportQuery(expected: Token,id: String) = mutate(expected,true) { d,_ ->
        ensure(d.savedReports.any { it.id == id },"Missing saved report")
        d.copy(savedReports = d.savedReports.filterNot { it.id == id })
    }
    fun settings(expected: Token, settings: Settings) = mutate(expected) { d,_ -> d.copy(settings = settings) }
    fun configureSchedule(expected: Token,value: ScheduleSettings) = mutate(expected) { d,_ ->
        d.copy(settings = d.settings.copy(schedule = value.canonical()))
    }
    /** Apply only the requested preference against the checked live settings. No
     * stale full Settings snapshot can be paired with a freshly read token. */
    fun configureFocusSignal(expected: Token,enabled: Boolean) = mutate(expected) { d,_ ->
        d.copy(settings = d.settings.copy(surfaces = d.settings.surfaces.copy(focusSignal = enabled)))
    }
    fun measure(expected: Token, measurement: Measurement) = mutate(expected, true) { d,_ ->
        d.copy(measurements = d.measurements.filterNot { it.id == measurement.id } + measurement)
    }
    fun removeMeasurement(expected: Token, measurementId: String) = mutate(expected,true) { d,_ ->
        ensure(d.measurements.any { it.id == measurementId }, "Missing measurement")
        d.copy(measurements = d.measurements.filterNot { it.id == measurementId })
    }
    data class SegmentEdit(val id: String,val start: Long,val end: Long,val selection: Classification)
    /** Save a completed session form atomically; retained segment IDs retain their measurements. */
    fun historyGaps(start: Long,end: Long): HistoryGapReview = storage { HistoryGaps.inspect(data(),clock.now(),start,end) }
    /** Form preparation only. Uncertainty must be resolved through recovery first. */
    fun gapDraft(review: HistoryGapReview,gapId: String,selection: Classification = Classification(),note: String? = null): EntryDraft = storage {
        val d=data();checkToken(d,review.token,clock.now())
        val actual=HistoryGaps.inspect(d,review.asOf,review.start,review.requestedEnd)
        val gap=actual.gaps.singleOrNull { it.id==gapId }
        ensureReason(gap!=null,ErrorReason.HISTORY_GAP_CHANGED,ErrorCode.STALE,mapOf("gapId" to ErrorArgument.Identifier(gapId)),"historyGaps.gapId")
        ensureReason(gap!!.kind!=HistoryGapKind.RECOVERY_UNCERTAIN,ErrorReason.HISTORY_GAP_UNCERTAIN,ErrorCode.RECOVERY_REQUIRED,mapOf("gapId" to ErrorArgument.Identifier(gapId)),"historyGaps.gapId")
        EntryDraft(selection.frozen(),note,DraftInterval(gap.start,gap.end),DraftSource.HISTORY_GAP).also { it.validateForApply() }
    }
    fun previewMetadata(input: BatchMetadataRequest): BatchMetadataPreview = storage { previewBatchMetadata(data(),input) }
    fun applyMetadata(preview: BatchMetadataPreview) = mutate(preview.expected,true) { d,_ ->
        val checked=previewBatchMetadata(d,preview.request)
        ensureReason(checked.changes==preview.changes,ErrorReason.METADATA_BATCH_CHANGED,ErrorCode.STALE,fieldPath="metadataBatch")
        ensureReason(checked.changes.isNotEmpty(),ErrorReason.METADATA_BATCH_INVALID,arguments=mapOf("rule" to ErrorArgument.Identifier("NO_CHANGES")),fieldPath="metadataBatch")
        checked.after
    }
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
            val captured = frozenList(edits.map { it.copy(selection = it.selection.frozen()) })
            val after = correctedSession(d,now,sessionId,captured,note,reclassify).frozen()
            validate(after)
            d to CorrectionPreview(token(d),sessionId,captured,note,reclassify,d.frozen(),after,now)
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
    /** Stop the old session, start the new one and attach reviewed input in one commit. */
    fun switchDraft(expected: Token,draft: EntryDraft): Change {
        draft.validateForApply()
        return switch(expected,draft.selection,draft.note)
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
    /** Preview only: no clock anchor, identity or interval is changed until explicit apply. */
    fun previewStartBackfill(expected: Token,sessionId: String,start: Long): StartBackfillPreview = storage {
        store.transaction { d ->
            val n = clock.now();checkToken(d,expected,n)
            val prefix = StartBackfill.segment(d,n,sessionId,start)
            validate(d.copy(segments = d.segments+prefix))
            d to StartBackfillPreview(this,expected,sessionId,prefix.end!!,prefix,null,n)
        }
    }
    /** Resolve the previous recorded end only as a visible, cancellable proposal. */
    fun previewStartAtPreviousEnd(expected: Token,sessionId: String): StartBackfillPreview = storage {
        store.transaction { d ->
            val n = clock.now();checkToken(d,expected,n)
            val previous = StartBackfill.previous(d,n,sessionId)
            val prefix = StartBackfill.segment(d,n,sessionId,previous.end)
            validate(d.copy(segments = d.segments+prefix))
            d to StartBackfillPreview(this,expected,sessionId,prefix.end!!,prefix,previous,n)
        }
    }
    fun applyStartBackfill(preview: StartBackfillPreview): Change {
        ensure(preview.owner === this,"Backfill preview belongs to another Tracker")
        return mutate(preview.expected,true) { d,n ->
            val prefix = StartBackfill.segment(d,n,preview.sessionId,preview.proposedSegment.start,preview.proposedSegment.id)
            ensure(prefix.end == preview.originalStart,"Session start changed",ErrorCode.STALE)
            d.copy(segments = d.segments+prefix)
        }
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
    /** null interval excludes unresolved time, not separately recorded facts. Referenced identities
     * can retain an empty segment at their known start; it contributes no measured/confirmed time. */
    fun resolveTail(expected: Token, start: Long?, end: Long?) = mutate(expected,true) { d,n -> resolveTailData(d,n,start,end) }
    private fun resolveTailData(d: Dataset,n: Moment,start: Long?,end: Long?): Dataset {
        val s = current(d) ?: fail(ErrorCode.INVALID_INPUT, "No current session")
        val seg = d.segments.singleOrNull { it.sessionId == s.id && it.end == null } ?: fail(ErrorCode.INVALID_INPUT, "No open tail")
        ensure(snapshot(d,n).mode == Mode.RECOVERY, "Tail is not awaiting recovery")
        ensure((start == null) == (end == null), "Supply both boundaries or exclude")
        val soleSegment = d.segments.none { it.sessionId == s.id && it.id != seg.id }
        val sessionReferenced = d.subjects.links.any { it.sessionId == s.id } ||
            d.subjects.sets.any { it.sessionId == s.id } || d.subjects.quantities.any { it.sessionId == s.id } ||
            d.schedule.links.any { it.sessionId == s.id } || d.measurements.any { it.scope == Scope.SESSION && it.targetId == s.id }
        // Distance/time bases depend on measurements as well. Keep the target and its facts;
        // their existing target-match guard, not a fabricated interval, decides rate availability.
        val segmentReferenced = d.measurements.any { it.scope == Scope.SEGMENT && it.targetId == seg.id }
        val retainEmpty = start == null && (segmentReferenced || (soleSegment && sessionReferenced))
        val segments = if (start == null) {
            if(retainEmpty) d.segments.map { if(it.id == seg.id) it.copy(end = it.start,durationMs = 0,
                anchor = null,uncertain = false,observedUntil = null,limitMs = null) else it }
            else d.segments.filterNot { it.id == seg.id }
        } else {
            interval(start,end!!,n)
            d.segments.map { if (it.id == seg.id) it.copy(start = start,end = end,durationMs = end-start,anchor = null,uncertain = false,observedUntil = null,limitMs = null) else it }
        }
        val keep = segments.any { it.sessionId == s.id }
        return d.copy(focus = null,segments = segments, sessions = if (keep) d.sessions.replace(s.copy(status = SessionStatus.STOPPED)) else d.sessions.filterNot { it.id == s.id },
            measurements = d.measurements.filterNot { (it.scope == Scope.SEGMENT && it.targetId == seg.id && start == null && !retainEmpty) || (!keep && it.scope == Scope.SESSION && it.targetId == s.id) })
    }
    @Synchronized fun canUndo(expected: Token): Boolean = undo?.first == expected
    @Synchronized fun undo(expected: Token): Change {
        val saved = undo
        if (saved == null || saved.first != expected) throw CoreError(ErrorCode.STALE,"No matching available undo",snapshot())
        return mutate(expected) { live,_ ->
            // Preserve still-live requests for unchanged saved records, never resurrect consumed nonces.
            saved.second.copy(pendingAssignments = live.pendingAssignments.filter { assignmentValid(saved.second,it) })
        }
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
private fun timerIdentity(d: Dataset): List<Any?> = listOf(current(d)?.id,current(d)?.status,
    d.segments.singleOrNull { it.end == null }?.id,d.focus?.id,d.focus?.phaseIndex,d.focus?.phase,d.focus?.paused,d.focus?.recovery)
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
    if (a == null || a.bootId != n.bootId || n.elapsed < a.elapsedMs) return s.copy(anchor = null,uncertain = true)
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
        Mode.RUNNING -> setOf("pause","stop","switch","label","details","switchedEarlier","startBackfill")
        Mode.PAUSED -> setOf("resume","stop","switch","label","details","startBackfill")
        Mode.RECOVERY -> setOf("resolveTail","label") }
    return Snapshot(token(d),mode,s,active,segs.sumExact { it.durationMs },uncertain || focus?.mode == FocusMode.RECOVERY,
        focus?.safeLabel ?: when(mode) { Mode.IDLE -> "Таймер остановлен"; Mode.RUNNING -> "Идёт учёт времени"; Mode.PAUSED -> "Таймер на паузе"; Mode.RECOVERY -> "Требуется уточнить время" },actions,focus,
        TimerTarget(d.datasetId,s?.id,d.timerEpoch,d.controlBarrier))
}
