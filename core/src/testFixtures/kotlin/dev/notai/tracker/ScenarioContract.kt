package dev.notai.tracker

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class FakeClock(var wall: Long = Instant.parse("2026-10-10T23:50:00Z").toEpochMilli(),var elapsed: Long = 100_000,var boot: String = "boot-1",var process: String = "process-1"): Clock {
    override fun now() = Moment(wall,elapsed,boot,process)
    fun advance(ms: Long) { wall += ms; elapsed += ms }
}
class MemoryRollback: RollbackStore {
    val saved = mutableMapOf<String,String>()
    override fun save(json: String) = newId().also { saved[it] = json }
    override fun read(reference: String) = saved.getValue(reference)
}
/** The SAME three scenarios run on in-memory domain storage and on disk-backed Room/SQLite. */
abstract class ScenarioContract {
    abstract fun createStore(): StateStore
    open fun reopen(store: StateStore): StateStore = store
    private fun expect(code: ErrorCode,block: ()->Unit) { assertEquals(code,assertFailsWith<CoreError>(block = block).code) }

    @Test fun typedImportIssuesPreserveAtomicityAndProvenance() {
        val clock = FakeClock();var t = Tracker(createStore(),clock)
        val transfer = HistoryTransfer(t,MemoryRollback())
        val csv = "id,activity,category,note,start,end\nentry,=Исходное,Категория,Пользовательское,2026-10-08T08:00:00Z,2026-10-08T08:01:00Z\n"
        val mapping = CsvMapping("original-space","activity","start",end = "end",category = "category",note = "note",id = "id")
        val file = HistoryTransfer.parseCsv(csv,mapping);val before = t.data()
        fun issue(preview: HistoryPreview,code: ImportIssueCode): ImportIssue {
            assertEquals(preview.errors,preview.errorIssues.map { it.diagnostic })
            assertEquals(preview.warnings,preview.warningIssues.map { it.diagnostic })
            assertFalse(preview.canApply);assertEquals(before,t.data())
            return preview.errorIssues.single().also { assertEquals(code,it.code) }
        }
        val unmapped = transfer.preview(file,emptyMap())
        val missing = issue(unmapped,ImportIssueCode.MAPPING_REQUIRED)
        assertEquals(2,missing.rowNumber);assertEquals(ErrorArgument.Text("=Исходное"),missing.arguments["activity"])
        val rejected = assertFailsWith<CoreError> { transfer.apply(unmapped) }
        assertEquals(ImportIssueCode.PREVIEW_HAS_ERRORS,rejected.importIssue!!.code);assertEquals(before,t.data())
        val invalid = transfer.preview(file,file.activities.associateWith { Classification(activityTypeId = "missing-fixture") })
        assertEquals(ErrorCode.INVALID_INPUT,issue(invalid,ImportIssueCode.MAPPING_INVALID).cause!!.code)
        val future = HistoryTransfer.parseCsv(csv.replace("2026-10-08","2027-10-08"),mapping)
        issue(transfer.preview(future,emptyMap()),ImportIssueCode.FUTURE_INTERVAL)
        val selections = file.activities.associateWith { Classification(name = it.activity,details = mapOf("category" to it.category)) }
        val preview = transfer.preview(file,selections);assertTrue(preview.canApply)
        val broken = object: RollbackStore {
            private var saved = ""
            override fun save(json: String): String {
                saved = codec.encodeToString(codec.decodeFromString<Backup>(json).copy(backupId = "different-fixture-backup"))
                return "synthetic"
            }
            override fun read(reference: String): String = saved // streaming reader reopens the same bytes twice
        }
        val rollbackError = assertFailsWith<CoreError> { HistoryTransfer(t,broken).apply(preview) }
        assertEquals(ImportIssueCode.ROLLBACK_VERIFICATION_FAILED,rollbackError.importIssue!!.code);assertEquals(before,t.data())
        val unreadable = object: RollbackStore { override fun save(json: String): String = "synthetic";override fun read(reference: String): String = "damaged synthetic bytes" }
        val invalidRollback = assertFailsWith<CoreError> { HistoryTransfer(t,unreadable).apply(preview) }
        assertEquals(ImportIssueCode.APPLY_FAILED,invalidRollback.importIssue!!.code);assertEquals(before,t.data())
        val ioFailure = object: RollbackStore { override fun save(json: String): String = throw java.io.IOException("fixture");override fun read(reference: String): String = error("unreachable") }
        val ioError = assertFailsWith<CoreError> { HistoryTransfer(t,ioFailure).apply(preview) }
        assertEquals(ImportIssueCode.APPLY_STORAGE_FAILED,ioError.importIssue!!.code);assertEquals(before,t.data())
        transfer.apply(preview);val imported = t.data()
        assertEquals(file.rows.single().origin,imported.sessions.single().importOrigin)
        assertEquals("=Исходное",imported.sessions.single().selection.name)
        assertEquals("Пользовательское",imported.sessions.single().note)
        val conflict = transfer.preview(HistoryTransfer.parseCsv(csv.replace("Пользовательское","Другой оригинал"),mapping),selections)
        assertEquals(ImportIssueCode.SOURCE_ID_CONFLICT,conflict.errorIssues.single().code)
        assertEquals(ErrorArgument.Identifier("entry"),conflict.errorIssues.single().arguments["recordKey"])
        val overlap = transfer.preview(HistoryTransfer.parseCsv(csv,mapping.copy(namespace = "another-space")),selections)
        assertEquals(ImportIssueCode.BATCH_VALIDATION_FAILED,overlap.errorIssues.single().code)
        assertEquals(ErrorCode.OVERLAP,overlap.errorIssues.single().cause!!.code)
        val stale = assertFailsWith<CoreError> { transfer.apply(preview) }
        assertEquals(ImportIssueCode.APPLY_STALE,stale.importIssue!!.code);assertEquals(imported,t.data())
        t = Tracker(reopen(t.store),clock);assertEquals(imported,t.data())
        val replay = HistoryTransfer(t,MemoryRollback());val dedupe = replay.preview(file,emptyMap())
        assertTrue(dedupe.canApply);assertEquals(1,dedupe.duplicates);assertEquals(0,replay.apply(dedupe).added)
        assertEquals(imported,t.data())
    }

    @Test fun scheduleLocalizationAndArchiveContract() {
        val clock = FakeClock();var t = Tracker(createStore(),clock)
        val beforeLocale = t.data();val labels = CatalogLabels(emptyList())
        assertEquals("User original",labels.label("catalog","language",1,"Source","es-MX","User original").displayLabel)
        assertEquals(listOf("es-MX","es-419","es"),localeFallbacks("es-MX"))
        assertEquals(listOf("zh-Hant"),localeFallbacks("zh-Hant"))
        assertEquals(beforeLocale,t.data())
        val rule = ScheduleRule(t.typeSelection("language"),"2026-10-10",weekdays = setOf(2,4),extraDates = setOf("2026-10-10"))
        val create = ScheduleCommand("create",t.snapshot().token,ScheduleAction.CREATE,"weekly",rule = rule)
        val receipt = t.scheduleCommand(create);assertEquals(receipt,t.scheduleCommand(create));assertTrue(t.data().sessions.isEmpty())
        expect(ErrorCode.STALE) { t.scheduleCommand(create.copy(rule = rule.copy(localTime = "19:00"))) }
        val key = OccurrenceKey("weekly","2026-10-13")
        val move = ScheduleCommand("move",t.snapshot().token,ScheduleAction.OVERRIDE,"weekly",1,occurrence = key,
            override = OccurrenceOverride(key,OverrideOperation.MOVE,date = "2026-10-15"))
        expect(ErrorCode.INVALID_INPUT) { t.scheduleCommand(move) }
        t.scheduleCommand(move.copy(key = "allowed-move",override = move.override!!.copy(allowCollision = true)))
        val projections = t.listOccurrences(LocalDate.parse("2026-10-15"),LocalDate.parse("2026-10-15"),java.time.ZoneId.of("UTC"))
        assertEquals(2,projections.size);assertTrue(projections.all { it.instant == null && it.reminderInstant == null });assertTrue(projections.any { it.key == key })
        val start = ScheduleCommand("start",t.snapshot().token,ScheduleAction.START,"weekly",1,occurrence = key,expectedOverrideRevision = 1)
        val result = t.scheduleCommand(start);assertEquals(result,t.scheduleCommand(start));assertEquals(1,t.data().sessions.size)
        t = Tracker(reopen(t.store),clock);assertEquals(result,t.scheduleCommand(start));assertEquals(result.sessionId,t.data().schedule.links.single().sessionId)
        clock.advance(1000);t.stop(t.snapshot().token)
        val backups = Backups(t,MemoryRollback());val backup = backups.export();assertEquals(8,Backups.decode(backup).formatVersion)
        val clean = Tracker(createStore(),clock);val restore = Backups(clean,MemoryRollback());val preview = restore.prepare(backup)
        assertEquals(1,preview.scheduleSeries);assertEquals(1,preview.scheduleLinks);restore.apply(preview)
        assertEquals(t.data().schedule,clean.data().schedule);assertEquals(t.data().sessions,clean.data().sessions)
        val broken = Backups.decode(backup).let { it.copy(data = it.data.copy(schedule = it.data.schedule.copy(links = it.data.schedule.links.map { l -> l.copy(sessionId = "missing") }))) }
        expect(ErrorCode.UNSUPPORTED_IMPORT) { restore.prepare(codec.encodeToString(broken)) }
        val immutable = t.data().schedule
        assertFails { (immutable.series as MutableList<ScheduleSeries>).clear() }
    }

    @Test fun scheduleDstFutureAndNoAutomaticFacts() {
        val clock = FakeClock();val t = Tracker(createStore(),clock)
        val rule = ScheduleRule(Classification(),"2026-01-01",weekdays = setOf(7),localTime = "02:30",zonePolicy = ScheduleZonePolicy.FIXED,zoneId = "Europe/Berlin",alertsEnabled = true,reminderTime = "02:20")
        t.scheduleCommand(ScheduleCommand("dst",t.snapshot().token,ScheduleAction.CREATE,"dst",rule = rule))
        val gap = t.listOccurrences(LocalDate.parse("2026-03-29"),LocalDate.parse("2026-03-29"),java.time.ZoneId.of("UTC")).single()
        assertEquals(Instant.parse("2026-03-29T01:00:00Z"),gap.instant);assertEquals(DstResolution.GAP_FIRST_VALID,gap.dstResolution)
        assertEquals(gap.instant,gap.reminderInstant)
        val overlap = t.listOccurrences(LocalDate.parse("2026-10-25"),LocalDate.parse("2026-10-25"),java.time.ZoneId.of("UTC")).single()
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"),overlap.instant);assertEquals(DstResolution.OVERLAP_EARLIER,overlap.dstResolution)
        t.scheduleCommand(ScheduleCommand("future",t.snapshot().token,ScheduleAction.UPDATE_FUTURE,"dst",1,rule = rule.copy(localTime = "19:00"),effectiveFrom = "2026-10-20"))
        assertEquals(gap.instant,t.listOccurrences(gap.date,gap.date,java.time.ZoneId.of("UTC")).single().instant)
        assertEquals(java.time.LocalTime.of(19,0),t.listOccurrences(overlap.date,overlap.date,java.time.ZoneId.of("UTC")).single().localTime)
        t.scheduleCommand(ScheduleCommand("archive",t.snapshot().token,ScheduleAction.SET_STATE,"dst",2,state = ScheduleState.ARCHIVED))
        assertTrue(t.listOccurrences(overlap.date,overlap.date,java.time.ZoneId.of("UTC")).isEmpty())
        t.scheduleCommand(ScheduleCommand("restore",t.snapshot().token,ScheduleAction.SET_STATE,"dst",3,state = ScheduleState.ACTIVE))
        assertEquals(1,t.listOccurrences(overlap.date,overlap.date,java.time.ZoneId.of("UTC")).size)
        assertTrue(t.data().sessions.isEmpty());assertTrue(t.data().segments.isEmpty())
    }

    @Test fun selectiveGroupsRepeatConflictAndStale() {
        val clock = FakeClock();val source = Tracker(createStore(),clock)
        source.manual(source.snapshot().token,clock.wall-3000,clock.wall-2000,source.typeSelection("language"),"private")
        val session = source.data().sessions.single()
        val transfer = SelectiveTransfer(source);val pack = transfer.export(TransferSelection(sessionIds = setOf(session.id)))
        assertEquals(1,pack.groups.size);assertTrue(pack.groups.single().content.types.size < source.data().types.size)
        var target = Tracker(createStore(),clock);var importer = SelectiveTransfer(target)
        val plan = importer.plan(pack,pack.groups.map { it.id }.toSet());assertTrue(plan.conflicts.isEmpty())
        assertFails { (plan.groups as MutableList<TransferGroup>).clear() }
        assertFails { (plan.groups.single().content.sessions as MutableList<Session>).clear() }
        val done = importer.apply(plan,"op");assertTrue(done.complete);assertFalse(done.partial);assertEquals(session,target.data().sessions.single())
        assertEquals(done,importer.apply(plan,"op"));target = Tracker(reopen(target.store),clock);importer = SelectiveTransfer(target)
        assertEquals(done,importer.resume("op"))
        val again = importer.plan(pack,pack.groups.map { it.id }.toSet());assertEquals(TransferGroupState.UNCHANGED,importer.apply(again,"again").groups.single().state)
        val stale = importer.plan(pack,pack.groups.map { it.id }.toSet());target.settings(target.snapshot().token,Settings("Europe/Berlin"))
        expect(ErrorCode.STALE) { importer.apply(stale,"stale") }
        val changedContent = pack.groups.single().content.let { it.copy(sessions = it.sessions.map { s -> s.copy(note = "source changed") },segments = it.segments.map { seg -> seg.copy(start = clock.wall-1000,end = clock.wall) }) }
        val changed = pack.copy(groups = listOf(SelectiveTransfer.group(pack.groups.single().id,pack.groups.single().originId,pack.groups.single().sourceRevision,changedContent)))
        // Hash computation uses the public inspection round-trip below, not locale/display names.
        val blocked = importer.plan(changed,changed.groups.map { it.id }.toSet());assertTrue(blocked.conflicts.isNotEmpty())
        assertEquals(TransferGroupState.REJECTED,importer.apply(blocked,"blocked").groups.single().state)
        val copy = importer.plan(changed,changed.groups.map { it.id }.toSet(),mapOf(changed.groups.single().id to TransferConflictPolicy.COPY))
        assertEquals(TransferGroupState.APPLIED,importer.apply(copy,"copy").groups.single().state)
        assertEquals(2,target.data().sessions.size);assertEquals(2,target.data().sessions.map { it.id }.distinct().size)
    }

    @Test fun selectiveScheduleMeasurementClosureAndResume() {
        val clock = FakeClock();val source = Tracker(createStore(),clock)
        val rule = ScheduleRule(source.typeSelection("language"),"2026-10-10",extraDates = setOf("2026-10-10"))
        source.scheduleCommand(ScheduleCommand("create",source.snapshot().token,ScheduleAction.CREATE,"linked",rule = rule))
        val key = OccurrenceKey("linked","2026-10-10")
        source.scheduleCommand(ScheduleCommand("start",source.snapshot().token,ScheduleAction.START,"linked",1,occurrence = key))
        clock.advance(1000);source.stop(source.snapshot().token)
        val session = source.data().sessions.single();val segment = source.data().segments.single()
        source.measure(source.snapshot().token,Measurement(id = "selected",fieldId = "own-field",unit = "own-unit",scope = Scope.SESSION,targetId = session.id,value = Decimal("1.25")))
        source.measure(source.snapshot().token,Measurement(id = "excluded",fieldId = "other-own-field",unit = "own-unit",scope = Scope.SEGMENT,targetId = segment.id,value = Decimal("2.5")))
        val exporter = SelectiveTransfer(source)
        expect(ErrorCode.INVALID_INPUT) { exporter.export(TransferSelection(sessionIds = setOf(session.id))) }
        val oneSided = exporter.export(TransferSelection(sessionIds = setOf(session.id),discardLinks = setOf(key)))
        assertTrue(oneSided.groups.single().content.links.isEmpty())
        val pack = exporter.export(TransferSelection(sessionIds = setOf(session.id),seriesIds = setOf("linked"),measurementIds = setOf("selected")))
        assertEquals(1,pack.groups.size);assertEquals(setOf("selected"),pack.groups.single().content.measurements.map { it.id }.toSet())
        val destination = Tracker(createStore(),clock);val importer = SelectiveTransfer(destination)
        val plan = importer.plan(pack,pack.groups.map { it.id }.toSet());val result = importer.apply(plan,"linked-op")
        assertEquals(TransferGroupState.APPLIED,result.groups.single().state)
        assertEquals(1,destination.data().schedule.links.size);assertEquals(1,destination.data().measurements.size)
        assertEquals(destination.data().sessions.single().id,destination.data().schedule.links.single().sessionId)

        // Two independent atomic groups: fail before the second group's commit, then reopen/resume.
        val plain = Tracker(createStore(),clock)
        plain.manual(plain.snapshot().token,clock.wall-6000,clock.wall-5000,Classification())
        plain.manual(plain.snapshot().token,clock.wall-4000,clock.wall-3000,Classification())
        val multiple = SelectiveTransfer(plain).export(TransferSelection(sessionIds = plain.data().sessions.map { it.id }.toSet()))
        var backing = createStore();var calls = 0
        val faulty = object: StateStore by backing {
            override fun <T> transaction(block: (Dataset)->Pair<Dataset,T>): T {
                calls++;if(calls == 3) throw java.io.IOException("injected interruption between groups")
                return backing.transaction(block)
            }
        }
        val interrupted = SelectiveTransfer(Tracker(faulty,clock));val interruptedPlan = interrupted.plan(multiple,multiple.groups.map { it.id }.toSet())
        expect(ErrorCode.STORAGE) { interrupted.apply(interruptedPlan,"resume-op") }
        assertEquals(1,backing.read().sessions.size)
        assertEquals(listOf(TransferGroupState.APPLIED,TransferGroupState.PENDING),backing.read().transfer.operations.single().groups.map { it.state })
        backing = reopen(backing)
        val resumed = SelectiveTransfer(Tracker(backing,clock)).resume("resume-op")
        assertTrue(resumed.complete);assertFalse(resumed.partial);assertEquals(2,backing.read().sessions.size)
    }

    @Test fun selectiveGoalsFavoritesFocusAndUndo() {
        val clock = FakeClock();val source = Tracker(createStore(),clock)
        source.saveGoal(source.snapshot().token,Goal(id = "selected-goal",name = "Original goal",metric = GoalMetric.DURATION_MS,target = 1000,filter = Filter(activityTypeId = "language")))
        source.saveFavorite(source.snapshot().token,Favorite(id = "selected-favorite",label = "Original personal label",selection = source.typeSelection("language")))
        val packageValue = SelectiveTransfer(source).export(TransferSelection(goalIds = setOf("selected-goal"),favoriteIds = setOf("selected-favorite")))
        assertEquals(2,packageValue.groups.size);assertTrue(packageValue.groups.all { it.content.sessions.isEmpty() })
        val target = Tracker(createStore(),clock);val before = target.data();val importer = SelectiveTransfer(target)
        val plan = importer.plan(packageValue,packageValue.groups.map { it.id }.toSet());importer.apply(plan,"goals")
        assertEquals(source.data().goals,target.data().goals);assertEquals(source.data().favorites,target.data().favorites)
        assertTrue(importer.undo("goals",target.snapshot().token).undone)
        assertEquals(before.goals,target.data().goals);assertEquals(before.favorites,target.data().favorites);assertEquals(before.types,target.data().types)
        val again = importer.plan(packageValue,packageValue.groups.map { it.id }.toSet());importer.apply(again,"goals-again")
        target.settings(target.snapshot().token,Settings("Europe/Berlin"))
        expect(ErrorCode.STALE) { importer.undo("goals-again",target.snapshot().token) }
        source.startFocus(source.snapshot().token,source.typeSelection("language"),FocusPlan(10000,1000));clock.advance(1000)
        val focusSession = source.data().focus!!.sessionId
        expect(ErrorCode.INVALID_INPUT) { SelectiveTransfer(source).export(TransferSelection(sessionIds = setOf(focusSession))) }
        val focusPackage = SelectiveTransfer(source).export(TransferSelection(includeFocus = true))
        assertNotNull(focusPackage.groups.single().content.focus)
        val focusTarget = Tracker(createStore(),clock);val focusImporter = SelectiveTransfer(focusTarget)
        val focusPlan = focusImporter.plan(focusPackage,focusPackage.groups.map { it.id }.toSet())
        assertEquals(TransferGroupState.APPLIED,focusImporter.apply(focusPlan,"focus").groups.single().state)
        assertEquals(Mode.RECOVERY,focusTarget.snapshot().mode)
        assertTrue(focusImporter.undo("focus",focusTarget.snapshot().token).undone);assertEquals(Mode.IDLE,focusTarget.snapshot().mode)
    }

    @Test fun scheduleCanonicalReceiptsAndSameDayRuleEdits() {
        val clock = FakeClock();var t = Tracker(createStore(),clock)
        t.start(t.snapshot().token);val facts = t.data().sessions;val epoch = t.data().timerEpoch
        val date = "2026-10-10";val days = linkedSetOf(1,2)
        val rule = ScheduleRule(Classification(),date,weekdays = days,extraDates = setOf(date),localTime = "19:00:00")
        val command = ScheduleCommand("canonical",t.snapshot().token,ScheduleAction.CREATE,"canonical",rule = rule)
        val receipt = t.scheduleCommand(command);days.clear()
        assertEquals(setOf(1,2),t.data().schedule.series.single().revisions.single().rule.weekdays)
        assertEquals(receipt,t.scheduleCommand(command.copy(rule = rule.copy(weekdays = setOf(2,1),localTime = "19:00"))))
        t.scheduleCommand(ScheduleCommand("same-day",t.snapshot().token,ScheduleAction.UPDATE_FUTURE,"canonical",1,
            rule = rule.copy(weekdays = setOf(1,2),localTime = "20:00"),effectiveFrom = date))
        val before = t.data();t = Tracker(reopen(t.store),clock);assertEquals(before,t.data())
        assertEquals(java.time.LocalTime.of(20,0),t.listOccurrences(LocalDate.parse(date),LocalDate.parse(date),java.time.ZoneId.of("UTC")).single().localTime)
        assertEquals(facts,t.data().sessions);assertEquals(epoch,t.data().timerEpoch)
        assertEquals(2,t.data().schedule.series.single().revisions.size)
    }

    open fun storageWorkload() {}
    open fun surfaceActions() {}
    open fun assertTransferFailure(transfer: HistoryTransfer,preview: HistoryPreview,tracker: Tracker) {}
    open fun backupDocumentInput() {}

    private fun surfaceChoiceAndAssignmentLifecycle() {
        val clock = FakeClock(); var t = Tracker(createStore(),clock)
        fun token() = t.snapshot().token
        val a = Card(id = "surface-a",name = "Same",activityTypeId = "language")
        val b = a.copy(id = "surface-b")
        t.saveCard(token(),a); t.saveCard(token(),b)
        t.saveFavorite(token(),Favorite(id = "surface-f1",label = "Same",selection = a.classification()))
        t.saveFavorite(token(),Favorite(id = "surface-f2",label = "Same",selection = b.classification()))
        t.saveFavorite(token(),Favorite(id = "surface-f3",label = "Duplicate",selection = a.classification()))
        fun quick() = t.choices().quick(HistoryWindow(clock.wall-100_000,clock.wall+1))
        val q = quick(); assertEquals(2,q.size); assertEquals(setOf(a.id,b.id),q.map { it.entry.selection.cardId }.toSet())
        assertNotEquals(q[0].reference,q[1].reference)
        assertEquals("card:${q[0].entry.selection.cardId}",q[0].icon.keys.first())
        assertEquals("activity.unclassified",ActivityIcons.resolve(t.data(),Classification()).fallback)
        val ref = q.first { it.entry.selection.cardId == a.id }.reference
        val idle = t.snapshot().timerTarget!!
        t.startChoice(idle,ref); expect(ErrorCode.STALE) { t.startChoice(idle,ref) }
        clock.advance(1_000); val result = t.stopForAssignment(t.snapshot().timerTarget!!)
        val offer = result.assignment!!; val stopped = offer.sessionId
        assertEquals(1_000,t.data().segments.single().durationMs)
        assertEquals(offer,t.pendingAssignments().single())
        val serialized = codec.encodeToString(t.data()); assertEquals(t.data(),codec.decodeFromString<Dataset>(serialized))
        t = Tracker(reopen(t.store),clock)
        assertEquals(offer,t.pendingAssignments().single())
        t.assignStopped(offer.id,quick().first { it.entry.selection.cardId == b.id }.reference,"assigned")
        assertEquals(b.id,t.data().sessions.single { it.id == stopped }.selection.cardId)
        assertEquals(1_000,t.data().segments.single().durationMs)
        expect(ErrorCode.STALE) { t.assignStopped(offer.id,ref) }
        t.undo(token()); assertTrue(t.pendingAssignments().isEmpty())
        expect(ErrorCode.STALE) { t.assignStopped(offer.id,ref) }
        t.start(token()); clock.advance(1_000); val cancelled = t.stopForAssignment(t.snapshot().timerTarget!!).assignment!!
        t.cancelAssignment(cancelled.id); expect(ErrorCode.STALE) { t.cancelAssignment(cancelled.id) }
        assertEquals(2,t.data().sessions.size)
        t.start(token()); clock.advance(1_000); val oldChange = t.stopForAssignment(t.snapshot().timerTarget!!)
        val old = oldChange.assignment!!
        t.start(token()); val current = token().sessionId
        expect(ErrorCode.STALE) { t.assignStopped(old.id,ref,requestTarget = oldChange.state.timerTarget) }
        assertEquals(current,token().sessionId); clock.advance(1_000); t.stop(token())
        t.start(token()); clock.advance(1_000)
        val changedOffer = t.stopForAssignment(t.snapshot().timerTarget!!).assignment!!
        t.measure(token(),Measurement(fieldId = "pages",unit = "page",scope = Scope.SESSION,targetId = changedOffer.sessionId,value = 1))
        assertFalse(t.pendingAssignments().any { it.id == changedOffer.id })
        assertTrue(t.pendingAssignments().any { it.id == old.id })
        expect(ErrorCode.STALE) { t.assignStopped(changedOffer.id,ref) }
        val beforeDenied = t.data()
        expect(ErrorCode.INVALID_INPUT) { t.startChoice(t.snapshot().timerTarget!!,ref) { false } }
        assertEquals(beforeDenied,t.data())
        val stale = t.choices().reference(t.choices().resolve("card:${a.id}")!!)
        t.saveCard(token(),a.copy(archived = true))
        expect(ErrorCode.STALE) { t.startChoice(t.snapshot().timerTarget!!,stale) }
        t.saveCard(token(),a); val renamed = t.choices().reference(t.choices().resolve("card:${a.id}")!!)
        t.saveCard(token(),a.copy(name = "Changed"))
        expect(ErrorCode.STALE) { t.startChoice(t.snapshot().timerTarget!!,renamed) }
        t.startFocus(token(),plan = FocusPlan(workMs = 2_000)); clock.advance(1_000)
        val focusOffer = t.stopForAssignment(t.snapshot().timerTarget!!).assignment!!
        assertNull(t.data().focus); assertEquals(SessionStatus.STOPPED,t.data().sessions.find { it.id == focusOffer.sessionId }!!.status)
        t.deleteSession(token(),focusOffer.sessionId)
        expect(ErrorCode.STALE) { t.assignStopped(focusOffer.id,ref) }
    }
    private fun savedAssignmentAcrossNextSession() {
        val clock = FakeClock();var t = Tracker(createStore(),clock)
        fun token() = t.snapshot().token
        val card = Card(id = "saved-record-choice",name = "Saved record",activityTypeId = "language")
        t.saveCard(token(),card)
        t.start(token());val oldId = token().sessionId!!
        val oldSegment = t.snapshot().active!!.id
        t.measure(token(),Measurement(id = "saved-old-pages",fieldId = "pages",unit = "page",scope = Scope.SESSION,targetId = oldId,value = 3))
        t.measure(token(),Measurement(id = "saved-old-reps",fieldId = "reps",unit = "rep",scope = Scope.SEGMENT,targetId = oldSegment,value = 2))
        clock.advance(1_000)
        val stopped = t.stopForAssignment(t.snapshot().timerTarget!!)
        val original = stopped.assignment!!
        t.start(token(),Classification(name = "Current must stay unchanged"));val currentId = token().sessionId!!
        t.measure(token(),Measurement(id = "saved-current-pages",fieldId = "pages",unit = "page",scope = Scope.SESSION,targetId = currentId,value = 7))
        t.measure(token(),Measurement(id = "saved-current-reps",fieldId = "reps",unit = "rep",scope = Scope.SEGMENT,targetId = t.snapshot().active!!.id,value = 9))
        clock.advance(2_000)
        assertEquals(original,t.pendingAssignments().single())
        val beforeReopen = t.data()
        t = Tracker(reopen(t.store),clock);clock.process = "after-process-death"
        assertEquals(beforeReopen,t.data());assertEquals(original,t.pendingAssignments().single())
        val shown = t.snapshot();assertEquals(currentId,shown.session!!.id)
        val beforeStale = t.data();val reference = t.choices().reference(t.choices().resolve("card:${card.id}")!!)
        expect(ErrorCode.STALE) { t.timerCommand(stopped.state.timerTarget!!,TimerDirective.START) }
        expect(ErrorCode.STALE) { t.assignStopped(original.id,reference,requestTarget = stopped.state.timerTarget) }
        assertEquals(beforeStale,t.data())
        val fresh = t.reopenAssignment(token(),oldId).assignment!!
        assertNotEquals(original.id,fresh.id);assertEquals(oldId,fresh.sessionId)
        expect(ErrorCode.STALE) { t.assignStopped(original.id,reference) }
        val before = t.data();val beforeSnapshot = t.snapshot()
        val change = t.assignStopped(fresh.id,reference,"Past record only",requestTarget = beforeSnapshot.timerTarget)
        assertEquals(setOf(oldId),change.changedSessionIds)
        assertEquals(card.id,t.data().sessions.single { it.id == oldId }.selection.cardId)
        assertEquals(before.sessions.single { it.id == currentId },t.data().sessions.single { it.id == currentId })
        assertEquals(before.segments.filter { it.sessionId == currentId },t.data().segments.filter { it.sessionId == currentId })
        assertEquals(before.segments.map { listOf(it.id,it.start,it.end,it.durationMs,it.anchor,it.uncertain,it.observedUntil,it.limitMs) },
            t.data().segments.map { listOf(it.id,it.start,it.end,it.durationMs,it.anchor,it.uncertain,it.observedUntil,it.limitMs) })
        assertEquals(before.measurements,t.data().measurements)
        assertEquals(beforeSnapshot.session,t.snapshot().session);assertEquals(beforeSnapshot.timerTarget,t.snapshot().timerTarget)
        assertEquals(beforeSnapshot.trustedDurationMs,t.snapshot().trustedDurationMs)
        expect(ErrorCode.STALE) { t.assignStopped(fresh.id,reference) }
        expect(ErrorCode.STALE) { t.reopenAssignment(token(),oldId) }
        t.undo(token());expect(ErrorCode.STALE) { t.reopenAssignment(token(),oldId) }
        assertEquals(beforeSnapshot.session,t.snapshot().session)
        clock.advance(1_000);t.stop(token())
        // Unrelated Undo retains an outstanding request; a record edit invalidates it.
        t.start(token());clock.advance(1_000);val edited = t.stopForAssignment(t.snapshot().timerTarget!!).assignment!!
        t.saveGoal(token(),Goal(id = "saved-unrelated-goal",name = "Goal",metric = GoalMetric.DURATION_MS,target = 1_000))
        t.undo(token());assertTrue(t.pendingAssignments().any { it.id == edited.id })
        val segment = t.data().segments.single { it.sessionId == edited.sessionId }
        t.correctSession(token(),edited.sessionId,listOf(Tracker.SegmentEdit(segment.id,segment.start,segment.end!!,segment.selection)),"Edited")
        expect(ErrorCode.STALE) { t.reopenAssignment(token(),edited.sessionId) }
        // Deleted request does not resurrect with Undo; restore and dataset switches also reject.
        t.start(token());clock.advance(1_000);val deleted = t.stopForAssignment(t.snapshot().timerTarget!!).assignment!!
        t.deleteSession(token(),deleted.sessionId);t.undo(token())
        expect(ErrorCode.STALE) { t.reopenAssignment(token(),deleted.sessionId) }
        t.start(token());clock.advance(1_000);val restoreOffer = t.stopForAssignment(t.snapshot().timerTarget!!).assignment!!
        val oldToken = token();val backups = Backups(t,MemoryRollback());backups.apply(backups.prepare(backups.export()))
        expect(ErrorCode.STALE) { t.assignStopped(restoreOffer.id,reference) }
        expect(ErrorCode.STALE) { t.reopenAssignment(oldToken,restoreOffer.sessionId) }
        expect(ErrorCode.STALE) { t.reopenAssignment(token(),restoreOffer.sessionId) }
        val beforeDatasetSwitch = token()
        t.store.transaction { d -> d.copy(datasetId = newId(),pendingAssignments = emptyList()) to Unit }
        expect(ErrorCode.STALE) { t.reopenAssignment(beforeDatasetSwitch,restoreOffer.sessionId) }
        expect(ErrorCode.STALE) { t.assignStopped(restoreOffer.id,reference) }
    }
    private fun completedDeletionLifecycle() {
        val clock = FakeClock();var t = Tracker(createStore(),clock)
        fun token() = t.snapshot().token
        val goal = Goal(id = "delete-goal",name = "Time",metric = GoalMetric.DURATION_MS,target = 2_000)
        t.saveGoal(token(),goal); t.start(token()); val id = token().sessionId!!
        expect(ErrorCode.INVALID_INPUT) { t.previewDeleteSession(id) }
        clock.advance(1_000); t.pause(token());t.resume(token());clock.advance(1_000);t.stop(token())
        val segments = t.data().segments.filter { it.sessionId == id }
        t.measure(token(),Measurement(id = "delete-pages",fieldId = "pages",unit = "page",scope = Scope.SESSION,targetId = id,value = 3))
        t.measure(token(),Measurement(id = "delete-reps",fieldId = "reps",unit = "rep",scope = Scope.SEGMENT,targetId = segments[0].id,value = 2))
        val before = t.data();val preview = t.previewDeleteSession(id)
        assertEquals(before,t.data());assertEquals(2,preview.segmentCount);assertEquals(2,preview.measurementCount)
        assertEquals(2_000,preview.reports(clock.wall-10_000,clock.wall+1).first.recordedMs)
        assertEquals(0,preview.reports(clock.wall-10_000,clock.wall+1).second.recordedMs)
        val change = t.applyDeleteSession(preview)
        assertEquals(setOf(id),change.changedSessionIds);assertTrue(change.undoAvailable)
        assertTrue(t.data().sessions.isEmpty());assertTrue(t.data().segments.isEmpty());assertTrue(t.data().measurements.isEmpty())
        assertEquals(0,t.goalProgress(goal.id).actual)
        expect(ErrorCode.STALE) { t.applyDeleteSession(preview) }
        t.undo(token());assertEquals(before.sessions,t.data().sessions);assertEquals(before.segments,t.data().segments);assertEquals(before.measurements,t.data().measurements)
        assertEquals(2_000,t.goalProgress(goal.id).actual)
        t = Tracker(reopen(t.store),clock);assertEquals(before.measurements,t.data().measurements)
        val old = t.previewDeleteSession(id);t.settings(token(),t.data().settings.copy(reportZone = "Europe/Paris"))
        expect(ErrorCode.STALE) { t.applyDeleteSession(old) }
        val deleted = t.deleteSession(token(),id);assertTrue(deleted.undoAvailable)
        t = Tracker(reopen(t.store),clock);assertTrue(t.data().sessions.isEmpty());assertTrue(t.data().measurements.isEmpty())
        assertFalse(t.canUndo(token())) // existing process-local undo, never promised after process death
    }
    private fun assignmentRestoreBoundary() {
        val clock = FakeClock();val t = Tracker(createStore(),clock)
        t.start(t.snapshot().token);clock.advance(1_000)
        val offer = t.stopForAssignment(t.snapshot().timerTarget!!).assignment!!
        val card = Card(id = "restore-choice",name = "Restore")
        t.saveCard(t.snapshot().token,card)
        val ref = t.choices().reference(t.choices().resolve("card:${card.id}")!!)
        val json = Backups.encode(t.data(),clock.now());assertFalse(json.contains("pendingAssignments"))
        val bytes = java.io.ByteArrayOutputStream();StreamingBackup.write(StreamingBackup.portable(t.data(),clock.now()),bytes)
        assertFalse(bytes.toString("UTF-8").contains("pendingAssignments"))
        val decoded = StreamingBackup.read { bytes.toByteArray().inputStream() };assertTrue(decoded.data.pendingAssignments.isEmpty())
        val backups = Backups(t,MemoryRollback());backups.apply(backups.prepare(json))
        expect(ErrorCode.STALE) { t.assignStopped(offer.id,ref) }
        expect(ErrorCode.STALE) { t.startChoice(t.snapshot().timerTarget!!,ref) }
        assertEquals(1_000,t.data().segments.single().durationMs)
    }

    @Test fun timerPath() {
        savedAssignmentAcrossNextSession()
        surfaceChoiceAndAssignmentLifecycle()
        indexedReportOracle()
        subjectCommands()
        choiceLifecycle()
        focusLifecycle()
        goalsLifecycle()
        checkedTimerCombinations()
        surfaceActions()
        storageWorkload()
        historyTransfer()
        formatInspection()
        val clock = FakeClock(); var tracker = Tracker(createStore(),clock)
        fun token() = tracker.snapshot().token
        val card = Card(name = "Язык",activityTypeId = "language",details = mapOf("language" to "es"),tags = setOf("learning","health"))
        tracker.saveCard(token(),card)
        tracker.start(token()); val originalStart = tracker.snapshot().active!!.start
        clock.advance(60_000); tracker.labelCurrent(token(),card.classification())
        assertEquals(originalStart,tracker.snapshot().active!!.start)
        assertEquals(60_000,tracker.snapshot().trustedDurationMs)
        tracker.pause(token()); clock.advance(30_000)
        assertEquals(60_000,tracker.snapshot().trustedDurationMs)
        tracker.resume(token()); clock.advance(60_000)
        tracker = Tracker(reopen(tracker.store),clock) // close/reopen disk DB in Android contract
        assertEquals(120_000,tracker.snapshot().trustedDurationMs)
        val a = token(); tracker.switch(a,Classification()); val b = token()
        val staleStop = assertFailsWith<CoreError> { tracker.stop(a) }
        assertEquals(ErrorCode.STALE,staleStop.code); assertEquals(b,staleStop.currentState!!.token); assertEquals(b,token())
        clock.advance(60_000); tracker.switchedEarlier(token(),clock.wall-30_000,card.classification())
        assertEquals(30_000,tracker.snapshot().trustedDurationMs)
        val c = token(); val staleUndo = assertFailsWith<CoreError> { tracker.undo(b) }
        assertEquals(ErrorCode.STALE,staleUndo.code); assertEquals(c,staleUndo.currentState!!.token); assertEquals(c,token())
        tracker.stop(token()); expect(ErrorCode.STALE) { tracker.stop(c) }
        val stopped = tracker.data().sessions.last()
        tracker.repeat(token(),stopped.id); assertEquals(tracker.data().classify(card.classification()),tracker.snapshot().session!!.selection)
        assertNotEquals(stopped.id,tracker.snapshot().session!!.id)
        clock.advance(10_000)
        val detail = card.classification().copy(details = mapOf("language" to "es","activity" to "listening"))
        tracker.changeDetails(token(),detail); clock.advance(10_000); tracker.stop(token())
        val favorite = Favorite(label = "Испанский",selection = detail)
        tracker.saveFavorite(token(),favorite)
        val repeatId = tracker.data().sessions.last().id
        tracker.saveCard(token(),card.copy(name = "Renamed and archived",archived = true,details = mapOf("activity" to "reading")))
        expect(ErrorCode.INVALID_INPUT) { tracker.start(token(),card.classification()) }
        tracker.startFavorite(token(),favorite.id)
        assertEquals(tracker.data().classify(detail),tracker.snapshot().session!!.selection)
        clock.advance(1000); tracker.stop(token()); tracker.repeat(token(),repeatId)
        assertEquals(tracker.data().classify(detail),tracker.snapshot().session!!.selection) // archived card keeps saved parameters
        assertEquals("Язык",tracker.snapshot().session!!.selection.name)
        clock.advance(10_000)
        val beforeArchivedSwitch = tracker.data()
        expect(ErrorCode.INVALID_INPUT) { tracker.switchedEarlier(token(),clock.wall-1000,card.classification()) }
        assertEquals(beforeArchivedSwitch,tracker.data())
        // Competing commands share a revision: exactly one wins, the second is explicitly stale.
        val shared = token(); val gate = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val calls = (1..2).map { pool.submit<ErrorCode?> { gate.await(); try { tracker.switch(shared,Classification()); null } catch (e: CoreError) { e.code } } }
            gate.countDown(); val outcomes = calls.map { it.get() }
            assertEquals(1,outcomes.count { it == null }); assertEquals(1,outcomes.count { it == ErrorCode.STALE })
        } finally { pool.shutdownNow() }
        val beforeJump = tracker.snapshot().trustedDurationMs
        clock.advance(20_000); clock.wall += 3_600_000
        assertEquals(Mode.RECOVERY,tracker.snapshot().mode)
        assertEquals(beforeJump+20_000,tracker.snapshot().trustedDurationMs)
        expect(ErrorCode.RECOVERY_REQUIRED) { tracker.stop(token()) }
        tracker.resolveTail(token(),clock.wall-20_000,clock.wall)
        tracker.start(token()); clock.advance(10_000); clock.boot = "boot-2"; clock.elapsed = 50
        assertEquals(Mode.RECOVERY,tracker.snapshot().mode)
        tracker.resolveTail(token(),null,null); assertEquals(Mode.IDLE,tracker.snapshot().mode)
        validate(tracker.data())
        val beforeInvalidClock = tracker.data()
        clock.wall = Long.MIN_VALUE
        expect(ErrorCode.INVALID_INTERVAL) { tracker.snapshot() }
        assertEquals(beforeInvalidClock,tracker.data())
        expect(ErrorCode.INVALID_INTERVAL) { materialize(Segment(sessionId = "range",start = 1,anchor = Anchor("same",0)),Moment(1000,Long.MAX_VALUE,"same")) }
    }

    private fun subjectCommands() {
        val clock = FakeClock();val tracker = Tracker(createStore(),clock)
        val idle = tracker.snapshot().timerTarget!!
        tracker.settings(tracker.snapshot().token,Settings("Europe/Berlin"))
        tracker.timerCommand(idle,TimerDirective.START)
        val stop = tracker.snapshot().timerTarget!!;val oldGlobal = tracker.snapshot().token
        tracker.settings(tracker.snapshot().token,tracker.data().settings.copy(reportZone = "Asia/Tokyo"))
        expect(ErrorCode.STALE) { tracker.stop(oldGlobal) } // explicit form contract remains strict
        clock.advance(1000);tracker.timerCommand(stop,TimerDirective.STOP)
        tracker.start(tracker.snapshot().token);val newSession = tracker.snapshot().session!!.id
        expect(ErrorCode.STALE) { tracker.timerCommand(stop,TimerDirective.STOP) }
        assertEquals(newSession,tracker.snapshot().session!!.id)
        val pause = tracker.snapshot().timerTarget!!;tracker.timerCommand(pause,TimerDirective.PAUSE)
        val resume = tracker.snapshot().timerTarget!!;val backups = Backups(tracker,MemoryRollback());val snapshot = backups.export()
        backups.apply(backups.prepare(snapshot))
        expect(ErrorCode.STALE) { tracker.timerCommand(resume,TimerDirective.RESUME) }
        assertEquals(Mode.PAUSED,tracker.snapshot().mode)
        val unchanged = tracker.data()
        expect(ErrorCode.INVALID_INPUT) { tracker.timerCommand(tracker.snapshot().timerTarget!!,TimerDirective.RESUME) { false } }
        assertEquals(unchanged,tracker.data())
    }
    private fun formatInspection() {
        val clock = FakeClock(); val tracker = Tracker(createStore(),clock)
        val transfer = HistoryTransfer(tracker,MemoryRollback())
        val first = "id,activity,start,end,note\n1,Read,2026-10-08T08:00:00Z,2026-10-08T08:20:00Z,Chapter one\n"
        val second = "код;дело;начало;секунды;комментарий\n2;Walk;09.10.2026 10:00:00;600;Park\n"
        val a = HistoryFiles.inspect((first+"\n").toByteArray())
        assertEquals(ImportFormat.DELIMITED_TEXT,a.format); assertEquals(',',a.candidates.single().delimiter)
        assertEquals(listOf("activity"),a.candidates.single().suggestions[ImportField.ACTIVITY])
        assertEquals("Read",a.candidates.single().examples.single()[1])
        assertFailsWith<UnsupportedOperationException> { (a.candidates as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (a.candidates.single().columns as MutableList)[0] = "changed" }
        assertFailsWith<UnsupportedOperationException> { (a.candidates.single().examples.single() as MutableList)[0] = "changed" }
        assertFailsWith<UnsupportedOperationException> { (a.candidates.single().suggestions as MutableMap).clear() }
        assertFailsWith<UnsupportedOperationException> { (a.warnings as MutableList).clear() }

        val fa = a.confirmCsv(',',CsvMapping("source-a","activity","start",end = "end",note = "note",id = "id"),CsvTimeChoice(null,"UTC"),null)
        val pa = transfer.preview(fa,fa.activities.associateWith { Classification(name = it.activity) })
        assertTrue(pa.canApply); transfer.apply(pa)
        val b = HistoryFiles.inspect(second.toByteArray())
        assertEquals(';',b.candidates.single().delimiter)
        val bm = CsvMapping("source-b","дело","начало",duration = "секунды",note = "комментарий",id = "код",delimiter = ';')
        expect(ErrorCode.INVALID_INPUT) { b.confirmCsv(';',bm,CsvTimeChoice("dd.MM.uuuu HH:mm:ss","Europe/Berlin"),null) }
        val fb = b.confirmCsv(';',bm,CsvTimeChoice("dd.MM.uuuu HH:mm:ss","Europe/Berlin"),DurationFormat.SECONDS)
        val pb = transfer.preview(fb,fb.activities.associateWith { Classification(name = it.activity) })
        assertTrue(pb.canApply); transfer.apply(pb)
        assertEquals(1_800_000L,tracker.data().segments.sumOf { it.durationMs })
        assertEquals(0,transfer.apply(transfer.preview(fb,emptyMap())).added)
        assertEquals(2,tracker.data().sessions.size)
        val ambiguous = HistoryFiles.inspect("activity,start;end\nRead,one;two\n".toByteArray())
        assertEquals(setOf(',', ';'),ambiguous.candidates.map { it.delimiter }.toSet())
        assertEquals(1,HistoryFiles.inspect("activity,start;end\nRead,one;two\n".toByteArray(),';').candidates.size)
        val columns = HistoryFiles.inspect("activity,start,started,duration\nRead,x,y,60\n".toByteArray())
        assertEquals(listOf("start","started"),columns.candidates.single().suggestions[ImportField.START])
        val dates = HistoryFiles.inspect("activity;start;duration\nRead;03/04/2026 10:00:00;60\n".toByteArray())
        val dm = CsvMapping("dates","activity","start",duration = "duration")
        val dayFirst = dates.confirmCsv(';',dm,CsvTimeChoice("dd/MM/uuuu HH:mm:ss","UTC"),DurationFormat.SECONDS)
        val monthFirst = dates.confirmCsv(';',dm,CsvTimeChoice("MM/dd/uuuu HH:mm:ss","UTC"),DurationFormat.SECONDS)
        assertEquals(Instant.parse("2026-04-03T10:00:00Z").toEpochMilli(),dayFirst.rows.single().start)
        assertEquals(Instant.parse("2026-03-04T10:00:00Z").toEpochMilli(),monthFirst.rows.single().start)
        expect(ErrorCode.INVALID_INPUT) { dates.confirmCsv(';',dm,CsvTimeChoice("dd/MM/uuuu HH:mm:ss",""),DurationFormat.SECONDS) }
        assertEquals(ImportFormat.UNSUPPORTED,HistoryFiles.inspect("{\"data\":[]}".toByteArray()).format)
        assertEquals(ImportFormat.UNSUPPORTED,HistoryFiles.inspect(byteArrayOf(0xFF.toByte())).format)
        val backup = Backups(tracker,MemoryRollback()).export()
        val json = HistoryFiles.inspect(backup.toByteArray())
        assertEquals(ImportFormat.BACKUP_JSON,json.format)
        val receiver = Tracker(createStore(),clock); val restoration = Backups(receiver,MemoryRollback())
        restoration.apply(json.prepareRestore(restoration))
        assertEquals(tracker.data().sessions,receiver.data().sessions)
        // Inspection is only a sample: a malformed later row is still rejected on confirmation.
        val damaged = first + (2..7).joinToString("") { "$it,Read,2026-10-08T08:00:00Z,2026-10-08T08:20:00Z,note\n" } + "\"broken"
        val sample = HistoryFiles.inspect(damaged.toByteArray())
        assertEquals(ImportFormat.DELIMITED_TEXT,sample.format)
        expect(ErrorCode.UNSUPPORTED_IMPORT) { sample.confirmCsv(',',CsvMapping("bad","activity","start",end = "end"),CsvTimeChoice(null,"UTC"),null) }
    }

    private fun choiceLifecycle() {
        val clock = FakeClock(); val t = Tracker(createStore(),clock)
        val root = java.io.File(requireNotNull(System.getProperty("tracker.samples"))).parentFile!!
        val full = codec.decodeFromString<VocabularyData>(root.resolve("catalog/runtime-catalog.json").readText())
        t.addCatalog(t.snapshot().token,full)
        val allTypes = ChoiceQuery(kinds = setOf(ChoiceKind.TYPE),includeArchived = true)
        val view = t.choices()
        assertEquals(ChoiceIndexStats(0,0,0),view.indexStats())
        assertEquals(0,view.suggestions().total); assertEquals(ChoiceIndexStats(0,0,0),view.indexStats())
        val started = System.nanoTime()
        var page = view.search(allTypes,pageSize = 37); val firstCursor = page.next!!
        val ids = mutableListOf<String>(); val total = page.total
        while(true) { ids += page.items.map { it.entry.sourceId }; if(page.next == null) break; page = view.search(allTypes,page.next,37) }
        val indexingAndPaging = System.nanoTime()-started
        assertEquals(t.data().types.size,total); assertEquals(total,ids.distinct().size)
        assertEquals(t.data().types.map { it.id }.toSet(),ids.toSet()) // full 1918 imported types + seed, no top-N loss
        val stamp = view.indexStats(); val typed = System.nanoTime()
        repeat(50) { view.search(allTypes.copy(text = listOf("a","work","read","язык","sport")[it%5]),pageSize = 10) }
        val typingNs = System.nanoTime()-typed; assertEquals(stamp,view.indexStats())
        expect(ErrorCode.STALE) { view.search(allTypes.copy(text = "different"),firstCursor) }
        expect(ErrorCode.INVALID_INPUT) { view.search(allTypes,pageSize = 101) }
        val group = ActivityType("choice-group","Раздел",selectable = false)
        val child = ActivityType("choice-type","Кофе",parentId = group.id,aliases = listOf("CAFÉ","Coffee"))
        t.addCatalog(t.snapshot().token,VocabularyData(listOf(group,child,child.copy(id = "choice-sibling",parentId = null))))
        t.saveAxis(t.snapshot().token,Axis("choice-axis","Контекст",valueKind = ValueKind.FREE_LABEL))
        val card = Card(id = "choice-card",name = "Мой кофе",activityTypeId = child.id,details = mapOf("method" to "filter"),tags = setOf("study","home"),
            facets = listOf(Facet("choice-axis",text = "тихо")))
        t.saveCard(t.snapshot().token,card)
        val selection = t.selection(card.id)
        val favorite = Favorite(id = "choice-favorite",label = "Saved coffee",selection = selection)
        t.saveFavorite(t.snapshot().token,favorite)
        val index = t.choices()
        val alias = index.search(allTypes.copy(text = "cafe\u0301"))
        assertEquals(setOf(child.id,"choice-sibling"),alias.items.map { it.entry.sourceId }.toSet())
        assertTrue(alias.items.all { it.matches.single().field == MatchField.ALIAS && it.matches.single().strength == MatchStrength.EXACT })
        assertEquals("CAFÉ",alias.items.first().matches.single().text)
        val children = index.search(allTypes.copy(scope = CatalogScope.CHILDREN,parentId = group.id))
        assertEquals(listOf(child.id),children.items.map { it.entry.sourceId })
        assertEquals(listOf(group.id,child.id),children.items.single().entry.path.map { it.id })
        val groupRow = index.search(allTypes.copy(text = "Раздел")).items.single { it.entry.sourceId == group.id }.entry
        assertFalse(groupRow.canStart); assertEquals(ChoiceAvailability.GROUP,groupRow.availability)
        val a = t.start(t.snapshot().token,selection,"Private note"); clock.advance(100); t.pause(t.snapshot().token)
        t.resume(t.snapshot().token); clock.advance(100); t.stop(t.snapshot().token)
        t.repeat(t.snapshot().token,a.state.session!!.id); clock.advance(50); t.stop(t.snapshot().token)
        val last = t.data().segments.last()
        val window = HistoryWindow(clock.wall-10_000,clock.wall+1)
        val frequent = SuggestionQuery(true,SuggestionOrder.FREQUENT_RECORDED,window)
        var choices = t.choices(); var result = choices.suggestions(frequent)
        assertEquals(1,result.total); assertEquals(2,result.items.single().suggestion!!.sessionCount) // 3 segments, 2 sessions
        val stableVariant = result.items.single().entry.id
        assertEquals(selection,result.items.single().entry.selection); assertEquals("Private note",result.items.single().entry.note)
        val beforeRename = t.data().segments; val builds = choices.indexStats()
        t.saveCard(t.snapshot().token,card.copy(name = "Renamed card",details = mapOf("method" to "new")))
        choices = t.choices()
        val past = choices.search(ChoiceQuery("Renamed card",setOf(ChoiceKind.HISTORY))).items.single()
        assertEquals(stableVariant,past.entry.id); assertEquals(selection,past.entry.selection)
        assertEquals(MatchField.CURRENT_CARD_NAME,past.matches.single().field)
        assertEquals(builds.historyBuilds,choices.indexStats().historyBuilds)
        val saved = choices.search(ChoiceQuery(kinds = setOf(ChoiceKind.FAVORITE))).items.single().entry
        assertEquals(selection,saved.selection)
        val direct = choices.search(ChoiceQuery(kinds = setOf(ChoiceKind.CARD))).items.single().entry
        assertEquals("new",direct.selection.details["method"])
        t.saveType(t.snapshot().token,child.copy(name = "Новый вид",aliases = listOf("fresh-alias")))
        choices = t.choices()
        assertTrue(choices.search(ChoiceQuery("fresh-alias",setOf(ChoiceKind.HISTORY))).items.single().matches.any { it.field == MatchField.CURRENT_TYPE_ALIAS })
        assertEquals(beforeRename,t.data().segments)
        t.saveType(t.snapshot().token,child.copy(archived = true))
        choices = t.choices(); assertEquals(0,choices.suggestions(frequent).total)
        assertFalse(choices.search(ChoiceQuery("Кофе",setOf(ChoiceKind.CARD),includeArchived = true)).items.single().entry.canStart)
        t.saveType(t.snapshot().token,child.copy(selectable = false))
        assertEquals(0,t.choices().suggestions(frequent).total)
        t.saveType(t.snapshot().token,child)
        t.saveCard(t.snapshot().token,card.copy(archived = true)); assertEquals(0,t.choices().suggestions(frequent).total)
        t.saveCard(t.snapshot().token,card)
        t.correctSegment(t.snapshot().token,last.id,beforeRename.first().start-1_000,beforeRename.first().start-900,last.selection)
        result = t.choices().suggestions(SuggestionQuery(true)); assertEquals(stableVariant,result.items.single().entry.id)
        assertNotEquals(last.id,result.items.single().entry.sourceSegmentId)
        t.undo(t.snapshot().token); result = t.choices().suggestions(SuggestionQuery(true)); assertEquals(last.id,result.items.single().entry.sourceSegmentId)
        val beforeTick = t.data(); val reuse = t.choices(); reuse.search(allTypes); reuse.suggestions(frequent)
        val same = reuse.indexStats(); clock.advance(500)
        reuse.search(allTypes.copy(text = "coffee")); reuse.suggestions(frequent); assertEquals(same,reuse.indexStats()); assertEquals(beforeTick,t.data())
        t.start(t.snapshot().token); t.choices().suggestions(SuggestionQuery(true))
        assertEquals(same.historyBuilds,t.choices().indexStats().historyBuilds) // active session cannot invalidate completed-history index
        clock.advance(100); t.pause(t.snapshot().token); t.choices().suggestions(SuggestionQuery(true))
        assertEquals(same.historyBuilds,t.choices().indexStats().historyBuilds)
        t.stop(t.snapshot().token)
        assertEquals(2,t.choices().suggestions(SuggestionQuery(true)).total) // unclassified remains a valid option
        val json = Backups(t,MemoryRollback()).export(); val keep = t.data()
        val oldView = t.choices(); val oldCursor = oldView.search(allTypes,pageSize = 10).next!!
        t.startFavorite(t.snapshot().token,favorite.id); clock.advance(100); t.stop(t.snapshot().token)
        val backups = Backups(t,MemoryRollback()); backups.apply(backups.prepare(json))
        val restored = t.choices(); assertEquals(2,restored.suggestions(SuggestionQuery(true)).total)
        assertEquals(keep.segments,t.data().segments); assertEquals(keep.favorites,t.data().favorites)
        val error = assertFailsWith<CoreError> { restored.search(allTypes,oldCursor) }
        assertEquals(ErrorCode.STALE,error.code); assertEquals(restored.token,error.currentState!!.token)
        assertEquals(10,oldView.search(allTypes,oldCursor,10).items.size) // old immutable view can finish its own pages
        val row = restored.suggestions(frequent).items.first { it.entry.selection.cardId == card.id }.entry
        t.start(restored.token,row.selection,row.note)
        assertEquals(selection,t.snapshot().session!!.selection)
        assertEquals(selection.tags,t.snapshot().active!!.selection.tags); assertEquals(selection.facets,t.snapshot().active!!.selection.facets)
        assertEquals("Private note",t.snapshot().session!!.note)
        t.stop(t.snapshot().token)
        root.resolve("build/choices-summary.json").writeText("{\"catalogTypesPaged\":$total,\"catalogBuildsAcross50Queries\":${stamp.catalogBuilds},\"coldIndexAndAllPagesNs\":$indexingAndPaging,\"fiftyQueriesNs\":$typingNs,\"deviceTested\":false}")
    }

    private fun focusLifecycle() {
        focusProcessContinuation()
        val clock = FakeClock(); var t = Tracker(createStore(),clock)
        fun token() = t.snapshot().token
        fun focus() = t.snapshot().focus!!
        fun total() = t.goalProgress("focus-goal").actual
        t.saveGoal(token(),Goal(id = "focus-goal",name = "Focus work",metric = GoalMetric.DURATION_MS,target = 10_000,filter = Filter(activityTypeId = "language")))
        val selection = Classification(activityTypeId = "language")
        t.start(token()); expect(ErrorCode.INVALID_INPUT) { t.startFocus(token(),selection) }
        clock.advance(1_000); t.stop(token())
        t.startFocus(token(),selection,FocusPlan(10_000,5_000)); val originalSession = t.snapshot().session!!.id
        val stale = token(); clock.advance(4_000)
        assertEquals(6_000,focus().remainingMs); assertEquals(4_000,total())
        t.pause(token()); expect(ErrorCode.STALE) { t.focusSkip(stale) }
        clock.advance(3_000); assertEquals(4_000,total()); assertEquals(FocusMode.PAUSED,focus().mode)
        t = Tracker(reopen(t.store),clock); assertEquals(FocusMode.PAUSED,focus().mode)
        t.resumeFocus(token()); clock.advance(2_000)
        t.changeDetails(token(),selection.copy(details = mapOf("mode" to "listening")))
        val beforeTicks = t.data(); clock.advance(10_000)
        assertEquals(FocusMode.DUE,focus().mode); assertEquals(10_000,total())
        assertEquals(10_000,t.snapshot().trustedDurationMs); assertEquals(beforeTicks,t.data())
        val cue = focus().signalKey; clock.advance(5_000); assertEquals(cue,focus().signalKey)
        assertEquals(10_000,total()) // overdue idle is never work
        t.focusNext(token()); assertEquals(FocusPhase.BREAK,focus().phase); assertEquals(Mode.PAUSED,t.snapshot().mode)
        val firstWork = t.data().segments.first { it.sessionId == originalSession }
        t.settings(token(),t.data().settings.copy(focusPlan = FocusPlan(9_000,9_000)))
        assertEquals(FocusPlan(9_000,9_000),t.data().settings.focusPlan)
        assertEquals(FocusPlan(10_000,5_000),t.data().focus!!.plan) // generic settings changes defaults only
        t.configureFocus(token(),FocusPlan(3_000,2_000)); assertEquals(5_000,focus().durationMs)
        assertEquals(FocusPlan(3_000,2_000),t.data().focus!!.plan)
        clock.advance(1_000); t.pauseFocus(token()); clock.advance(5_000); assertEquals(1_000,focus().elapsedMs)
        t.resume(token()); clock.advance(4_000); assertEquals(FocusMode.DUE,focus().mode)
        assertEquals(10_000,total()); t.focusNext(token()); assertEquals(3_000,focus().durationMs)
        assertEquals(originalSession,t.snapshot().session!!.id)
        clock.advance(1_000); t.focusSkip(token()); assertEquals(11_000,total()); assertEquals(2_000,focus().durationMs)
        t.correctSegment(token(),firstWork.id,firstWork.start,firstWork.end!!-1_000,firstWork.selection)
        assertEquals(10_000,total()); t.undo(token()); assertEquals(11_000,total())
        clock.advance(500); t.focusSkip(token()); clock.advance(500)
        t.switch(token(),Classification()); assertNull(t.data().focus)
        clock.advance(1_000); t.undo(token()); assertNotNull(t.data().focus)
        t.cancelFocus(token()); assertNull(t.data().focus); assertEquals(12_500,total())
        t.start(token()); clock.process = "ordinary-reopened"; clock.advance(1_000)
        assertEquals(Mode.RUNNING,t.snapshot().mode); t.stop(token()) // ordinary timer keeps same-boot recovery semantics
        t.startFocus(token(),selection,FocusPlan(2_000,1_000)); val tail = t.data().segments.last()
        clock.advance(1_000); clock.process = "focus-reopened"; clock.boot = "focus-new-boot"; clock.advance(20_000)
        assertEquals(FocusMode.RECOVERY,focus().mode); assertEquals(12_500,total())
        expect(ErrorCode.RECOVERY_REQUIRED) { t.cancelFocus(token()) }
        expect(ErrorCode.RECOVERY_REQUIRED) { t.focusNext(token()) }
        t.resolveFocus(token(),null,null); assertEquals(12_500,total()); t.undo(token())
        assertEquals(FocusMode.RECOVERY,focus().mode)
        t.resolveFocus(token(),tail.start,tail.start+1_000); assertEquals(13_500,total())
        assertNull(t.data().focus)
        t.startFocus(token(),selection,FocusPlan(2_000,1_000)); clock.advance(2_000); t.focusNext(token())
        clock.process = "break-reopened"; clock.boot = "break-new-boot"; clock.advance(500)
        assertEquals(Mode.RECOVERY,t.snapshot().mode); assertEquals(FocusMode.RECOVERY,focus().mode)
        assertEquals(15_500,total()); t.resolveFocus(token(),null,null); assertEquals(15_500,total())
        // Paused work is portable; unpaused work/break is explicitly unresolved after replacement.
        t.startFocus(token(),selection,FocusPlan(2_000,1_000)); clock.advance(1_000); t.pauseFocus(token())
        val paused = Backups(t,MemoryRollback()).export()
        var clean = Tracker(createStore(),clock); var backup = Backups(clean,MemoryRollback())
        backup.apply(backup.prepare(paused)); assertEquals(FocusMode.PAUSED,clean.snapshot().focus!!.mode)
        clean.resumeFocus(clean.snapshot().token); clock.advance(1_000); clean.cancelFocus(clean.snapshot().token)
        validate(clean.data())
        t.resumeFocus(token()); clock.advance(300)
        val active = Backups(t,MemoryRollback()).export()
        java.io.File(requireNotNull(System.getProperty("tracker.samples"))).parentFile.resolve("build/focus-example.json").writeText(active)
        clean = Tracker(createStore(),clock); backup = Backups(clean,MemoryRollback())
        val preview = backup.prepare(active); assertTrue(preview.unfinishedFocus); assertTrue(preview.unfinishedTail)
        backup.apply(preview); assertEquals(FocusMode.RECOVERY,clean.snapshot().focus!!.mode)
        clean.resolveFocus(clean.snapshot().token,null,null); validate(clean.data())
        clock.advance(1_000); t.focusNext(token()); clock.advance(300)
        val breakJson = Backups(t,MemoryRollback()).export()
        val breakPreview = backup.prepare(breakJson); assertTrue(breakPreview.unfinishedFocus); assertFalse(breakPreview.unfinishedTail)
        backup.apply(breakPreview); assertEquals(FocusMode.RECOVERY,clean.snapshot().focus!!.mode)
        val known = clean.data().segments.sumOf { it.durationMs }
        clean.resolveFocus(clean.snapshot().token,null,null)
        assertEquals(known,clean.data().segments.sumOf { it.durationMs })
        t.cancelFocus(token()); t.startFocus(token(),selection,FocusPlan(2_000,1_000))
        clock.wall += 60_000; assertEquals(FocusMode.RECOVERY,focus().mode)
        t.resolveFocus(token(),null,null)
        t.startFocus(token(),selection,FocusPlan(2_000,1_000)); clock.advance(2_000); t.focusNext(token())
        clock.boot = "new-boot"; assertEquals(FocusMode.RECOVERY,focus().mode); t.resolveFocus(token(),null,null)
        validate(t.data())
        val invalid = t.data(); expect(ErrorCode.INVALID_INPUT) { t.configureFocus(token(),FocusPlan(0,1)) }; assertEquals(invalid,t.data())
        val legacy = codec.parseToJsonElement(Backups(t,MemoryRollback()).export()).jsonObject.toMutableMap()
        legacy["formatVersion"] = JsonPrimitive(6)
        legacy["data"] = JsonObject(legacy.getValue("data").jsonObject.filterKeys { it !in setOf("focus","schedule","transfer") })
        assertNull(Backups.decode(JsonObject(legacy).toString()).data.focus)
        val v14 = java.io.File(requireNotNull(System.getProperty("tracker.samples"))).parentFile.resolve("evidence/goals-v14/goals-example.json").readText()
        val old = Backups.decode(v14); assertNull(old.data.focus); assertEquals(FocusPlan(),old.data.settings.focusPlan)
        val broken = codec.parseToJsonElement(active).jsonObject.toMutableMap()
        val brokenData = broken.getValue("data").jsonObject.toMutableMap()
        val brokenFocus = brokenData.getValue("focus").jsonObject.toMutableMap()
        brokenFocus["sessionId"] = JsonPrimitive("missing-session")
        brokenData["focus"] = JsonObject(brokenFocus); broken["data"] = JsonObject(brokenData)
        val unchanged = clean.data()
        expect(ErrorCode.UNSUPPORTED_IMPORT) { backup.prepare(JsonObject(broken).toString()) }
        assertEquals(unchanged,clean.data())
        val noProcessClock = Clock { clock.now().copy(processId = null) }
        val unsupported = Tracker(createStore(),noProcessClock)
        expect(ErrorCode.INVALID_INPUT) { unsupported.startFocus(unsupported.snapshot().token) }
        assertTrue(unsupported.data().sessions.isEmpty())
        t.startFocus(token(),selection,FocusPlan(2_000,1_000)); clock.advance(2_000); t.focusNext(token())
        val breakState = t.data(); val f = breakState.focus!!
        val zeroAnchor = breakState.copy(focus = f.copy(breakAnchor = f.breakAnchor!!.copy(elapsedMs = 0)))
        validate(zeroAnchor)
        assertEquals(FocusMode.RECOVERY,Focus.snapshot(zeroAnchor,clock.now().copy(wall = f.breakStartedAt!!-1,elapsed = Long.MAX_VALUE))!!.mode)
        t.cancelFocus(token())
    }

    private fun focusProcessContinuation() {
        val clock = FakeClock();var tracker = Tracker(createStore(),clock)
        tracker.startFocus(tracker.snapshot().token,plan = FocusPlan(2_000,1_000))
        clock.advance(1000);clock.process = "new-work-process"
        tracker = Tracker(reopen(tracker.store),clock)
        assertEquals(FocusMode.RUNNING,tracker.snapshot().focus!!.mode)
        assertEquals(1000,tracker.snapshot().focus!!.remainingMs)
        val before = tracker.data();clock.advance(20_000)
        assertEquals(FocusMode.DUE,tracker.snapshot().focus!!.mode)
        assertEquals(2000,tracker.snapshot().trustedDurationMs)
        assertEquals(before,tracker.data());assertEquals(1,tracker.data().sessions.size);assertEquals(1,tracker.data().segments.size)
        tracker.focusNext(tracker.snapshot().token)
        clock.process = "new-break-process";clock.advance(500)
        tracker = Tracker(reopen(tracker.store),clock)
        assertEquals(FocusMode.RUNNING,tracker.snapshot().focus!!.mode)
        assertEquals(500,tracker.snapshot().focus!!.remainingMs)
        clock.advance(20_000);assertEquals(FocusMode.DUE,tracker.snapshot().focus!!.mode)
        assertEquals(1,tracker.data().segments.size)
        tracker.cancelFocus(tracker.snapshot().token)
        assertEquals(2000,tracker.data().segments.single().durationMs)
    }
    private fun goalsLifecycle() {
        val clock = FakeClock(wall = Instant.parse("2026-03-28T22:59:00Z").toEpochMilli())
        var tracker = Tracker(createStore(),clock)
        val duration = Goal(name = "Recorded language",metric = GoalMetric.DURATION_MS,target = 120_000,
            zone = "Europe/Berlin",filter = Filter(activityTypeId = "language",tags = setOf("practice")))
        val count = duration.copy(id = newId(),name = "Sessions limit",metric = GoalMetric.SESSION_COUNT,target = 1,direction = GoalDirection.LIMIT)
        tracker.saveGoal(tracker.snapshot().token,duration); tracker.saveGoal(tracker.snapshot().token,count)
        val day = LocalDate.parse("2026-03-29")
        val initial = tracker.goalProgress(duration.id,day)
        assertEquals(23*3_600_000L,initial.window.end-initial.window.start) // spring DST, not 24 h
        assertEquals(0,initial.actual); assertFalse(initial.hasRecords); assertTrue(initial.incompletePeriod)
        tracker.start(tracker.snapshot().token,Classification(activityTypeId = "language",tags = setOf("practice","other")))
        val persistedBeforeTick = tracker.data()
        clock.advance(120_000) // one minute on each side of midnight
        val active = tracker.goalProgress(duration.id,day)
        assertEquals(60_000,active.actual); assertEquals(GoalPosition.BELOW,active.position)
        assertEquals(1,tracker.goalProgress(count.id,day).actual)
        assertEquals(1,active.unfinishedSessionIds.size); assertTrue(active.completedSessionIds.isEmpty())
        assertEquals(persistedBeforeTick,tracker.data())
        val previous = tracker.goalProgress(count.id,day.minusDays(1))
        assertEquals(1,previous.actual) // explicit participation-in-period policy
        tracker.pause(tracker.snapshot().token); clock.advance(60_000)
        tracker.resume(tracker.snapshot().token); clock.advance(60_000); tracker.stop(tracker.snapshot().token)
        val stopped = tracker.goalProgress(duration.id,day)
        assertEquals(120_000,stopped.actual); assertEquals(GoalPosition.AT,stopped.position)
        assertEquals(GoalStatus.MINIMUM_REACHED,stopped.recordedStatus)
        assertEquals(1,stopped.completedSessionIds.size); assertTrue(stopped.unfinishedSessionIds.isEmpty())
        assertEquals(1,tracker.goalProgress(count.id,day).actual) // two segments, one session
        val segment = tracker.data().segments.last()
        tracker.correctSegment(tracker.snapshot().token,segment.id,segment.start,segment.end!!-30_000,segment.selection)
        assertEquals(90_000,tracker.goalProgress(duration.id,day).actual)
        tracker.undo(tracker.snapshot().token); assertEquals(120_000,tracker.goalProgress(duration.id,day).actual)
        tracker.start(tracker.snapshot().token,Classification(activityTypeId = "language",tags = setOf("practice")))
        clock.advance(1_000)
        assertEquals(2,tracker.goalProgress(count.id,day).actual)
        assertEquals(GoalPosition.ABOVE,tracker.goalProgress(count.id,day).position)
        assertEquals(GoalStatus.LIMIT_EXCEEDED,tracker.goalProgress(count.id,day).recordedStatus)
        assertEquals(1,tracker.goalProgress(count.id,day).excess)
        val saved = tracker.data(); tracker = Tracker(reopen(tracker.store),clock)
        assertEquals(saved,tracker.data()); assertEquals(121_000,tracker.goalProgress(duration.id,day).actual)
        val backup = Backups(tracker,MemoryRollback()).export()
        val clean = Tracker(createStore(),clock); val backups = Backups(clean,MemoryRollback())
        val preview = backups.prepare(backup); assertEquals(2,preview.goals); backups.apply(preview)
        assertEquals(tracker.data().goals,clean.data().goals)
        val imported = clean.goalProgress(duration.id,day)
        assertEquals(120_000,imported.actual); assertTrue(imported.uncertain) // active imported tail is unresolved, not a fabricated finish
        clean.resolveTail(clean.snapshot().token,tracker.data().segments.last().start,clock.wall)
        assertEquals(121_000,clean.goalProgress(duration.id,day).actual)
        val last = clean.data().segments.last(); val earlier = clean.data().segments.first().start-2_000
        clean.correctSegment(clean.snapshot().token,last.id,earlier,earlier+1_000,last.selection)
        assertEquals(1,clean.goalProgress(count.id,day).actual) // moving a whole session out of the period recomputes count
        clean.undo(clean.snapshot().token); assertEquals(2,clean.goalProgress(count.id,day).actual)
        val closedBackup = Backups(clean,MemoryRollback()).export()
        java.io.File(requireNotNull(System.getProperty("tracker.samples"))).parentFile.resolve("build/goals-example.json").writeText(closedBackup)
        val legacy = codec.parseToJsonElement(closedBackup).jsonObject.toMutableMap()
        legacy["formatVersion"] = JsonPrimitive(5)
        legacy["data"] = JsonObject(legacy.getValue("data").jsonObject.filterKeys { it !in setOf("goals","schedule","transfer") })
        assertTrue(Backups.decode(JsonObject(legacy).toString()).data.goals.isEmpty())
        val malformed = closedBackup.replace("\"target\": 120000","\"target\": 0")
        val beforeBad = clean.data()
        expect(ErrorCode.UNSUPPORTED_IMPORT) { backups.prepare(malformed) }; assertEquals(beforeBad,clean.data())
        val disabled = duration.copy(enabled = false)
        clean.saveGoal(clean.snapshot().token,disabled)
        assertFalse(clean.goalProgress(duration.id,day).goal.enabled); assertEquals(121_000,clean.goalProgress(duration.id,day).actual)
        clean.deleteGoal(clean.snapshot().token,duration.id); clean.undo(clean.snapshot().token)
        assertEquals(disabled,clean.data().goals.single { it.id == duration.id })
        val week = Goals.window(duration.copy(period = GoalPeriod.WEEK),day)
        assertEquals(LocalDate.parse("2026-03-23").atStartOfDay(java.time.ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli(),week.start)
        val month = Goals.window(duration.copy(period = GoalPeriod.MONTH),day)
        assertEquals(LocalDate.parse("2026-04-01").atStartOfDay(java.time.ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli(),month.end)
        val autumn = Goals.window(duration,LocalDate.parse("2026-10-25"))
        assertEquals(25*3_600_000L,autumn.end-autumn.start)
        val fixed = duration.copy(period = GoalPeriod.FIXED,startDate = "2026-03-29",endDateExclusive = "2026-03-30")
        assertEquals(initial.window,Goals.window(fixed,day.plusYears(1)))
        expect(ErrorCode.INVALID_INTERVAL) { clean.saveGoal(clean.snapshot().token,fixed.copy(endDateExclusive = fixed.startDate)) }
        assertEquals(beforeBad.segments,clean.data().segments)
        // Adjacent end is excluded; period totals come directly from the same report filter.
        val report = Reports.report(clean.data(),clock.now(),initial.window.start,initial.window.end,duration.filter,duration.zone)
        assertEquals(report.recordedMs,clean.goalProgress(duration.id,day).actual)
        assertEquals(0,clean.goalProgress(duration.id,day.plusDays(1)).actual)
        val beforeInvalid = clean.data()
        expect(ErrorCode.INVALID_INPUT) { clean.saveGoal(clean.snapshot().token,duration.copy(target = 0)) }
        expect(ErrorCode.INVALID_INPUT) { clean.saveGoal(clean.snapshot().token,duration.copy(zone = "Not/AZone")) }
        expect(ErrorCode.INVALID_INPUT) { clean.saveGoal(clean.snapshot().token,duration.copy(filter = Filter(cardId = "missing"))) }
        assertEquals(beforeInvalid,clean.data())
        val unmatched = duration.copy(id = newId(),filter = Filter(details = mapOf("never" to "recorded")))
        clean.saveGoal(clean.snapshot().token,unmatched)
        assertEquals(0,clean.goalProgress(unmatched.id,day).actual)
        clean.deleteGoal(clean.snapshot().token,unmatched.id)
        assertFailsWith<UnsupportedOperationException> { (clean.data().goals as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (clean.data().goals.first().filter.tags as MutableSet).clear() }
        val jsonWithoutGoals = codec.parseToJsonElement(closedBackup).jsonObject.toMutableMap()
        jsonWithoutGoals["data"] = JsonObject(jsonWithoutGoals.getValue("data").jsonObject.filterKeys { it !in setOf("goals","schedule","transfer") })
        expect(ErrorCode.UNSUPPORTED_IMPORT) { backups.prepare(JsonObject(jsonWithoutGoals).toString()) }
        assertEquals(beforeInvalid.goals,clean.data().goals)
        assertEquals(beforeInvalid.segments,clean.data().segments)
        val beforeZoneChange = clean.goalProgress(duration.id,day)
        clean.settings(clean.snapshot().token,clean.data().settings.copy(reportZone = "America/New_York"))
        assertEquals(beforeZoneChange,clean.goalProgress(duration.id,day)) // goal zone is explicit and independent
    }

    private fun checkedTimerCombinations() {
        val clock = FakeClock(); val store = createStore(); val tracker = Tracker(store,clock)
        val disk = MemoryRollback(); val transfer = HistoryTransfer(tracker,disk)
        val start = clock.wall-10_000
        val file = HistoryTransfer.parseCsv("activity,start,end,id\nImported,${Instant.ofEpochMilli(start)},${Instant.ofEpochMilli(clock.wall)},one\n",
            CsvMapping("combination","activity","start",end = "end",id = "id"))
        transfer.apply(transfer.preview(file,file.activities.associateWith { Classification(name = it.activity,activityTypeId = "language") }))
        val card = Card(name = "Private activity",activityTypeId = "language")
        tracker.saveCard(tracker.snapshot().token,card)
        tracker.start(tracker.snapshot().token,tracker.selection(card.id))
        fun checked() { validate(tracker.data()); assertEquals(snapshot(tracker.data(),clock.now()),tracker.snapshot()) }
        checked(); clock.advance(10_000); tracker.pause(tracker.snapshot().token); checked()
        tracker.saveCard(tracker.snapshot().token,card.copy(archived = true))
        val oldPath = tracker.data().segments.last().selection.ancestorPathAtWrite
        tracker.saveType(tracker.snapshot().token,ActivityType("parent-combination","Parent"))
        tracker.saveType(tracker.snapshot().token,tracker.data().types.single { it.id == "language" }.copy(parentId = "parent-combination"))
        assertEquals(oldPath,tracker.data().segments.last().selection.ancestorPathAtWrite)
        tracker.resume(tracker.snapshot().token); checked(); clock.advance(10_000)
        tracker.switch(tracker.snapshot().token,Classification()); checked(); clock.advance(1_000)
        tracker.undo(tracker.snapshot().token); checked(); tracker.stop(tracker.snapshot().token); checked()
        val before = tracker.data(); val first = before.segments[0]; val second = before.segments[1]
        val preview = tracker.previewBoundary(tracker.snapshot().token,first.id,second.id,first.end!!-1_000)
        assertEquals(before,tracker.data())
        val correction = tracker.correctBoundary(preview); checked()
        assertEquals(setOf(first.sessionId,second.sessionId),correction.changedSessionIds)
        assertEquals(before.segments.sumOf { it.durationMs },tracker.data().segments.sumOf { it.durationMs })
        assertEquals(before.sessions.map { it.importOrigin },tracker.data().sessions.map { it.importOrigin })
        tracker.undo(tracker.snapshot().token); checked(); assertEquals(before.segments,tracker.data().segments)
        expect(ErrorCode.STALE) { tracker.correctBoundary(preview) }
        tracker.settings(tracker.snapshot().token,Settings("Europe/Berlin",SurfaceSettings(true,true,true)))
        expect(ErrorCode.INVALID_INPUT) { tracker.start(tracker.snapshot().token,tracker.selection(card.id)) }
        val saved = tracker.data()
        assertFailsWith<CoreError> { store.transaction { it.copy(sessions = it.sessions+it.sessions.first()) to Unit } }
        assertEquals(saved,tracker.data())
        val wrongProof = CheckedState.validate(saved).replace(saved) { Unit }
        expect(ErrorCode.STALE) { store.checkedTransaction { wrongProof } }
        assertEquals(saved,tracker.data())
        // A cast or later edit of an input builder cannot invalidate a reused proof.
        assertFailsWith<UnsupportedOperationException> { (tracker.data().sessions as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (tracker.data().types.first().aliases as MutableList).add("changed") }
        val tags = mutableSetOf("original")
        tracker.start(tracker.snapshot().token,Classification(tags = tags)); tags.add("late mutation")
        assertEquals(setOf("original"),tracker.snapshot().session!!.selection.tags)
        assertFailsWith<UnsupportedOperationException> { (tracker.snapshot().session!!.selection.tags as MutableSet).add("cast mutation") }
        tracker.stop(tracker.snapshot().token)
        val frozenSaved = tracker.data()
        val json = Backups(tracker,disk).export()
        val clean = Tracker(createStore(),clock); val backups = Backups(clean,MemoryRollback())
        backups.apply(backups.prepare(json)); validate(clean.data())
        assertEquals(frozenSaved.settings,clean.data().settings); assertEquals(frozenSaved.segments,clean.data().segments)
        clean.start(clean.snapshot().token); clock.advance(1_000); clean.stop(clean.snapshot().token); validate(clean.data())
        // Full validation is still mandatory on an arbitrary store's default checked path.
        val fallback = object: StateStore {
            var data = saved
            override fun read() = data
            override val changes = kotlinx.coroutines.flow.flowOf(data)
            override fun <T> transaction(block: (Dataset)->Pair<Dataset,T>): T { val (next,result) = block(data); data = next; return result }
        }
        val fallbackTracker = Tracker(fallback,clock)
        fallbackTracker.start(fallbackTracker.snapshot().token); validate(fallback.read())
        val bad = saved.copy(segments = saved.segments+saved.segments.first())
        fallback.data = bad
        expect(ErrorCode.INVALID_INPUT) { fallbackTracker.stop(Token(bad.datasetId,bad.revision,null)) }
        assertEquals(bad,fallback.data)
    }

    private fun historyTransfer() {
        val clock = FakeClock(); val tracker = Tracker(createStore(),clock); val disk = MemoryRollback()
        val transfer = HistoryTransfer(tracker,disk)
        // UTF-8 exceeds the limit although character count does not. Malformed quoting
        // would fail first if Toggl inspected its header/body before checking the byte limit.
        val oversized = "\"x\"!" + "я".repeat(MAX_BACKUP_BYTES/2)
        val sizeError = assertFailsWith<CoreError> { HistoryTransfer.parseToggl(oversized,"toggl:large","UTC") }
        assertEquals(ErrorCode.UNSUPPORTED_IMPORT,sizeError.code)
        assertEquals("CSV exceeds 10 MiB",sizeError.message)
        val csv = "Project,Client,Description,Start date,Start time,End date,End time,Duration,Tags\r\n"+
            "Language,Personal,\"Note, with \"\"quotes\"\"\nand newline\",2026-10-09,10:00:00,2026-10-09,10:20:00,0:20:00,reading\r\n"
        val file = HistoryTransfer.parseToggl(csv,"toggl:personal-workspace","Europe/Berlin")
        assertTrue(file.errors.isEmpty()); assertEquals(1,file.rows.size)
        assertEquals(Instant.parse("2026-10-09T08:00:00Z").toEpochMilli(),file.rows.single().start)
        assertFalse(transfer.preview(file,emptyMap()).canApply)
        val mapping = file.activities.associateWith { Classification(name = it.activity,activityTypeId = "language") }
        val preview = transfer.preview(file,mapping); assertEquals(1,preview.added); assertTrue(preview.canApply)
        val repeatedRows = HistoryTransfer.parseToggl(csv+csv.substringAfter("\r\n"),"toggl:personal-workspace","Europe/Berlin")
        val repeatedPreview = transfer.preview(repeatedRows,mapping)
        assertEquals(1,repeatedPreview.added); assertEquals(1,repeatedPreview.duplicates)
        val before = tracker.data(); assertTransferFailure(transfer,preview,tracker)
        val result = transfer.apply(preview)
        assertEquals(before,Backups.decode(disk.read(result.rollbackReference!!)).data)
        val imported = tracker.data(); assertEquals("reading",imported.sessions.single().importOrigin!!.fields["Tags"])
        assertTrue(imported.sessions.single().note!!.contains("\n"))
        expect(ErrorCode.STALE) { transfer.apply(preview) }
        val replay = transfer.preview(file,mapping); assertEquals(1,replay.duplicates); assertEquals(0,replay.added)
        transfer.apply(replay); assertEquals(imported,tracker.data())
        assertEquals(imported,Backups.decode(Backups(tracker,disk).export()).data)
        val portable = HistoryTransfer.export(imported)
        val portableFile = HistoryTransfer.parseCsv(portable.csv,CsvMapping.portable("not-ai:transfer"))
        assertTrue(portableFile.errors.isEmpty()); assertEquals(file.rows.single().start,portableFile.rows.single().start)
        assertEquals(file.rows.single().end,portableFile.rows.single().end)
        val skewed = imported.copy(segments = imported.segments.map { it.copy(durationMs = it.durationMs-250) })
        val skewRoundTrip = HistoryTransfer.parseCsv(HistoryTransfer.export(skewed).csv,CsvMapping.portable("clock-skew"))
        assertTrue(skewRoundTrip.errors.isEmpty()); assertEquals(skewed.segments.single().durationMs,skewRoundTrip.rows.single().durationMs)
        // Same source row with another timezone is a conflict, not a silent replay.
        val wrongZone = HistoryTransfer.parseToggl(csv,"toggl:personal-workspace","UTC")
        assertFalse(transfer.preview(wrongZone,mapping).canApply)
        val overlap = HistoryTransfer.parseToggl(csv,"different-source","Europe/Berlin")
        val blocked = transfer.preview(overlap,mapping); assertFalse(blocked.canApply)
        expect(ErrorCode.UNSUPPORTED_IMPORT) { transfer.apply(blocked) }; assertEquals(imported,tracker.data())
        val dst = csv.replace("2026-10-09","2026-10-25").replace("10:00:00","02:10:00").replace("10:20:00","02:30:00")
        assertTrue(HistoryTransfer.parseToggl(dst,"dst","Europe/Berlin").errors.isNotEmpty())
        val wrongDuration = csv.replace(",0:20:00,",",0:21:00,")
        assertTrue(HistoryTransfer.parseToggl(wrongDuration,"invalid","Europe/Berlin").errors.isNotEmpty())
        expect(ErrorCode.UNSUPPORTED_IMPORT) { HistoryTransfer.parseCsv("a,b\n\"broken",CsvMapping.portable("broken")) }
        tracker.undo(tracker.snapshot().token); assertEquals(before.sessions,tracker.data().sessions)
        val fresh = transfer.preview(file,mapping)
        tracker.saveCard(tracker.snapshot().token,Card(name = "Changed since preview"))
        expect(ErrorCode.STALE) { transfer.apply(fresh) }
        // Retained view ticks never call storage; elapsed time and boot recovery remain core-owned.
        var reads = 0
        val counting = object: StateStore by tracker.store { override fun read(): Dataset { reads++; return tracker.store.read() } }
        val observed = Tracker(counting,clock)
        observed.start(observed.snapshot().token)
        val view = observed.view(); val readCount = reads
        repeat(20) { clock.advance(1000); assertEquals((it+1)*1000L,view.snapshot().trustedDurationMs) }
        assertEquals(readCount,reads)
        clock.boot = "after-reboot"; clock.elapsed = 1
        assertEquals(Mode.RECOVERY,view.snapshot().mode); assertEquals(readCount,reads)
    }

    private fun draftBoundaryValidation() {
        val clock = FakeClock(); val t = Tracker(createStore(),clock)
        val interval = DraftInterval(clock.wall-10_000,clock.wall-9000)
        val base = EntryDrafts.selectedText("x").copy(interval = interval)
        val invalid = listOf(
            base.copy(note = "a".repeat(16385)),
            EntryDraft(Classification(),"a".repeat(16385),interval,DraftSource.CALENDAR_EVENT),
            base.copy(note = ""),base.copy(note = " \t\n"),
            base.copy(sourceReference = "event-reference"),
            base.copy(source = DraftSource.CALENDAR_EVENT,sourceReference = "r".repeat(1025)),
            base.copy(source = DraftSource.CALENDAR_EVENT,sourceReference = ""),
            base.copy(source = DraftSource.CALENDAR_EVENT,sourceReference = "  "),
            base.copy(source = DraftSource.CALENDAR_EVENT,sourceReference = "ref\nvalue"),
            EntryDraft(Classification(),"valid",interval,DraftSource.CALENDAR_EVENT,"ref\u0000value")
        )
        invalid.forEach { draft ->
            val before = t.data(); val token = t.snapshot().token
            expect(ErrorCode.INVALID_INPUT) { t.startDraft(token,draft) }; assertEquals(before,t.data())
            expect(ErrorCode.INVALID_INPUT) { t.recordDraft(token,draft) }; assertEquals(before,t.data())
        }
        expect(ErrorCode.INVALID_INPUT) { EntryDrafts.event("title",reference = "bad\nref") }
        // Boundary-sized edited text is stored verbatim; null explicitly removes the note.
        val exact = "=текст\n" + "a".repeat(16377)
        assertEquals(16384,exact.length)
        listOf(exact,null).forEach { note ->
            val draft = EntryDraft(Classification(),note,DraftInterval(clock.wall-10_000,clock.wall-9000),DraftSource.CALENDAR_EVENT,"r".repeat(1024))
            val saved = t.recordDraft(t.snapshot().token,draft)
            assertEquals(note,saved.changedSessions.single().note)
            val started = t.startDraft(t.snapshot().token,draft)
            assertEquals(note,started.changedSessions.single().note)
            clock.advance(1000); t.stop(t.snapshot().token); clock.advance(20_000)
        }
    }

    private fun localEntryLifecycle() {
        draftBoundaryValidation()
        val clock = FakeClock(wall = Instant.parse("2026-03-30T00:00:00Z").toEpochMilli())
        val t = Tracker(createStore(),clock); fun token() = t.snapshot().token
        fun at(value: String) = Instant.parse(value).toEpochMilli()
        val card = Card(name = "Практика",activityTypeId = "language",details = mapOf("language" to "es"),tags = setOf("learning","health"))
        t.saveCard(token(),card)
        val original = t.data()
        val text = EntryDrafts.selectedText("=Лекция\n15 минут завтра",card.classification())
        assertEquals("=Лекция\n15 минут завтра",text.note); assertNull(text.interval)
        assertEquals(original,t.data()) // cancel/edit forms are never database mutations
        expect(ErrorCode.INVALID_INTERVAL) { t.recordDraft(token(),text) }
        expect(ErrorCode.INVALID_INPUT) { EntryDrafts.selectedText("   ") }
        expect(ErrorCode.INVALID_INPUT) { EntryDrafts.selectedText("a".repeat(16385)) }
        val event = EntryDrafts.event("=Календарная встреча",DraftInterval(at("2026-03-28T23:00:00Z"),at("2026-03-29T22:00:00Z")),card.classification(),"provider:opaque-instance")
        assertEquals(original,t.data())
        val copied = t.recordDraft(token(),event)
        val session = copied.changedSessionIds.single()
        assertEquals(SessionStatus.STOPPED,t.data().sessions.single().status)
        assertEquals(event.note,t.data().sessions.single().note)
        val unchanged = t.data()
        expect(ErrorCode.OVERLAP) { t.recordDraft(token(),event) }
        assertEquals(unchanged,t.data())
        expect(ErrorCode.INVALID_INTERVAL) { t.recordDraft(token(),EntryDrafts.event("All day")) }
        val future = event.copy(interval = DraftInterval(clock.wall+60_000,clock.wall+120_000))
        expect(ErrorCode.INVALID_INTERVAL) { t.recordDraft(token(),future) }
        t.measure(token(),Measurement(fieldId = "pages",unit = "page",scope = Scope.SESSION,targetId = session,value = 20))
        val extra = event.copy(interval = DraftInterval(at("2026-03-29T22:10:00Z"),at("2026-03-29T22:20:00Z")))
        t.recordDraft(token(),extra)
        val other = card.copy(id = newId()); t.saveCard(token(),other)
        t.recordDraft(token(),extra.copy(selection = other.classification(),interval = DraftInterval(at("2026-03-29T22:30:00Z"),at("2026-03-29T22:40:00Z"))))
        val view = t.view(); val calendar = view.calendar(LocalDate.parse("2026-03-28"),3,zone = "Europe/Berlin")
        assertEquals(23*60*60_000L,calendar.days[1].end-calendar.days[1].start)
        assertEquals(calendar.window.recordedMs,calendar.days.sumOf { it.recordedMs })
        calendar.days.forEach { day ->
            val direct = Reports.day(t.data(),clock.now(),day.date,zone = "Europe/Berlin")
            assertEquals(direct.rows,day.rows); assertEquals(direct.recordedMs,day.recordedMs)
        }
        assertEquals(Decimal("20"),calendar.window.quantityTotals().values.single())
        val groups = calendar.window.groupSimilar()
        assertEquals(2,groups.size) // identical display names, distinct card IDs
        assertEquals(2,groups.single { it.selection.cardId == card.id }.sessionCount)
        assertEquals(calendar.window.recordedMs,groups.sumOf { it.recordedMs })
        assertEquals(calendar.window.rows.map { it.segmentId }.toSet(),groups.flatMap { it.rows }.map { it.segmentId }.toSet())
        assertEquals(Csv.export(calendar.window),Csv.export(Reports.report(t.data(),clock.now(),calendar.window.start,calendar.window.end,zone = "Europe/Berlin")))
        expect(ErrorCode.INVALID_INTERVAL) { view.calendar(LocalDate.parse("2026-03-28"),63) }
        val first = t.data().segments.first()
        t.correctSession(token(),first.sessionId,listOf(Tracker.SegmentEdit(first.id,first.start+60_000,first.end!!,first.selection)),event.note)
        assertEquals(calendar.window.recordedMs-60_000,t.view().calendar(LocalDate.parse("2026-03-28"),3,zone = "Europe/Berlin").window.recordedMs)
        t.undo(token())
        assertEquals(groups,t.view().calendar(LocalDate.parse("2026-03-28"),3,zone = "Europe/Berlin").window.groupSimilar())
        val stale = token(); t.startDraft(stale,future) // Start uses now, not proposed calendar time
        assertEquals(clock.wall,t.snapshot().active!!.start)
        assertEquals(card.details,t.snapshot().active!!.selection.details)
        expect(ErrorCode.STALE) { t.startDraft(stale,text) }
        clock.advance(1000); t.pause(token()); clock.advance(1000); t.resume(token()); clock.advance(1000); t.stop(token())
        val after = t.view().calendar(LocalDate.parse("2026-03-28"),3,zone = "Europe/Berlin")
        assertEquals(3,after.window.groupSimilar().single { it.selection.cardId == card.id }.sessionCount)
        assertEquals(calendar.window.recordedMs+2000,after.window.recordedMs)
        // Closed history and notes survive ordinary JSON. Draft/provider context is not stored.
        val json = Backups(t,MemoryRollback()).export()
        assertFalse(json.contains("provider:opaque-instance"))
        val receiver = Tracker(createStore(),clock); val restore = Backups(receiver,MemoryRollback())
        restore.apply(restore.prepare(json)); val restored = receiver.data()
        assertEquals(after.window.rows,Reports.calendar(restored,clock.now(),LocalDate.parse("2026-03-28"),3,zone = "Europe/Berlin").window.rows)
        // A rebooted active interval has no exact calendar placement and appears only once, outside days.
        t.startDraft(token(),text); clock.advance(1000); clock.boot = "other-boot"
        val unresolved = t.view().calendar(LocalDate.parse("2026-03-28"),3,zone = "Europe/Berlin")
        assertEquals(1,unresolved.window.unresolved.size)
        assertEquals(after.window.recordedMs,unresolved.days.sumOf { it.recordedMs })
        // Apia skipped a whole civil date, unlike an ordinary 23/25-hour DST day.
        val skipped = LocalDate.parse("2011-12-30")
        expect(ErrorCode.INVALID_INTERVAL) { Reports.day(t.data(),clock.now(),skipped,zone = "Pacific/Apia") }
        expect(ErrorCode.INVALID_INTERVAL) { t.view().calendar(skipped,1,zone = "Pacific/Apia") }
        expect(ErrorCode.INVALID_INTERVAL) { t.view().calendar(skipped.minusDays(1),3,zone = "Pacific/Apia") }
        expect(ErrorCode.INVALID_INTERVAL) { t.view().calendar(skipped,2,zone = "Pacific/Apia") }
        listOf(skipped.minusDays(1),skipped.plusDays(1)).forEach { date ->
            val page = t.view().calendar(date,1,zone = "Pacific/Apia")
            val report = Reports.day(t.data(),clock.now(),date,zone = "Pacific/Apia")
            assertTrue(page.window.end > page.window.start)
            assertEquals(1L,page.window.calendarDays); assertEquals(report,page.window)
        }
        // Calendar boundaries in autumn are 25 hours, without inventing recorded activity.
        assertEquals(25*60*60_000L,t.view().calendar(LocalDate.parse("2026-10-25"),1,zone = "Europe/Berlin").days.single().let { it.end-it.start })
    }

    @Test fun smallHistoryAndExport() {
        completedDeletionLifecycle()
        chronologicalHistory()
        previewSnapshots()
        localEntryLifecycle()
        readyCatalogLifecycle()
        hierarchyAndDecimals()
        val clock = FakeClock(); val tracker = Tracker(createStore(),clock)
        fun token() = tracker.snapshot().token
        val card = Card(name = "=Language",activityTypeId = "language",tags = setOf("learning","health"),details = mapOf("language" to "es"))
        tracker.saveCard(token(),card); tracker.start(token(),card.classification())
        clock.advance(20*60_000); tracker.pause(token()) // crosses UTC midnight, 10 min on each day
        val sessionId = token().sessionId!!
        val measuredChange = tracker.measure(token(),Measurement(fieldId = "pages",unit = "page",scope = Scope.SESSION,targetId = sessionId,value = 20))
        assertEquals(setOf(sessionId),measuredChange.changedSessionIds)
        clock.advance(5*60_000); tracker.resume(token()); clock.advance(20*60_000); tracker.stop(token())
        val d = tracker.data(); val windowStart = Instant.parse("2026-10-10T00:00:00Z").toEpochMilli()
        val all = Reports.report(d,clock.now(),windowStart,clock.wall)
        assertEquals(40*60_000,all.recordedMs); assertEquals(2,all.rows.size) // equal durations are not deduplicated
        val calendarPage = tracker.view().calendar(LocalDate.parse("2026-10-10"),2)
        assertEquals(listOf(10*60_000L,30*60_000L),calendarPage.days.map { it.recordedMs })
        assertEquals(3,calendarPage.days.sumOf { it.rows.size }) // one segment appears in two days, unchanged ID
        assertEquals(1,calendarPage.window.groupSimilar().single().sessionCount)
        assertEquals(all.recordedMs,calendarPage.window.recordedMs)
        val drift = d.copy(segments = d.segments.mapIndexed { index,s -> if(index == 0) s.copy(durationMs = s.durationMs+1) else s })
        validate(drift) // valid sub-second wall/elapsed discrepancy, not fabricated invalid input
        val rounded = Reports.calendar(drift,clock.now(),LocalDate.parse("2026-10-10"),2)
        assertEquals(all.recordedMs+1,rounded.days.sumOf { it.recordedMs })
        assertEquals(rounded.window.recordedMs,rounded.days.sumOf { it.recordedMs })
        assertEquals(Decimal("20"),all.measurements.single().value)
        val pages = QuantityKey("pages",1,"page",Scope.SESSION)
        assertEquals(Decimal("20"),all.quantityTotals()[pages])
        assertEquals(Decimal("20"),Reports.day(d,clock.now(),LocalDate.parse("2026-10-10")).quantityTotals()[pages])
        assertEquals(Decimal("20"),Reports.day(d,clock.now(),LocalDate.parse("2026-10-11")).quantityTotals()[pages])
        val quantities = all.copy(measurements = all.measurements + listOf(
            Measurement(id = "reps-a",fieldId = "reps",unit = "rep",scope = Scope.SEGMENT,targetId = d.segments[0].id,value = 8),
            Measurement(id = "reps-b",fieldId = "reps",unit = "rep",scope = Scope.SEGMENT,targetId = d.segments[1].id,value = 12),
            Measurement(id = "pages-zero",fieldId = "pages",meaningVersion = 2,unit = "page",scope = Scope.SESSION,targetId = sessionId,value = 0)))
        assertEquals(Decimal("20"),quantities.quantityTotals()[QuantityKey("reps",1,"rep",Scope.SEGMENT)])
        assertEquals(Decimal("0"),quantities.quantityTotals()[QuantityKey("pages",2,"page",Scope.SESSION)])
        assertNull(quantities.quantityTotals()[QuantityKey("missing",1,"page",Scope.SESSION)])
        val overflowQuantity = all.copy(measurements = listOf(all.measurements.single().copy(value = Decimal(Long.MAX_VALUE.toString())),
            all.measurements.single().copy(id = "other-target",targetId = "other-session",value = Decimal("1"))))
        assertEquals(Decimal("9223372036854775808"),overflowQuantity.quantityTotals()[pages])
        val overflowTime = all.copy(rows = listOf(all.rows.first().copy(durationMs = Long.MAX_VALUE),all.rows.last().copy(durationMs = 1)))
        expect(ErrorCode.INVALID_INTERVAL) { overflowTime.byCard() }
        assertEquals(10*60_000,Reports.day(d,clock.now(),LocalDate.parse("2026-10-10")).recordedMs)
        assertEquals(30*60_000,Reports.day(d,clock.now(),LocalDate.parse("2026-10-11")).recordedMs)
        for (tag in listOf("learning","health")) assertEquals(all.recordedMs,Reports.report(d,clock.now(),windowStart,clock.wall,Filter(tags = setOf(tag))).recordedMs)
        val csv = Csv.export(all)
        val csvRows = all.copy(unresolved = all.unresolved + d.segments.first().copy(
            id = "csv-unresolved",end = null,durationMs = 0,anchor = null,uncertain = true,observedUntil = clock.wall))
        for(mode in CsvMode.entries) {
            val streamed = StringBuilder();Csv.write(csvRows,streamed,mode)
            assertEquals(MaterializedCsvReference.export(csvRows,mode),streamed.toString())
            assertEquals(streamed.toString(),Csv.export(csvRows,mode))
            assertTrue(streamed.contains("\"unresolved\",\"csv-unresolved\""))
        }
        assertTrue(csv.contains("' =Language").not()); assertTrue(csv.contains("'=Language"))
        assertEquals(20,csv.lineSequence().filter { it.startsWith("\"measurement\"") }.sumOf { it.split(',')[15].trim('"').toLong() })
        assertEquals(1,csv.lineSequence().count { it.startsWith("\"measurement\"") })
        assertEquals(all.recordedMs,csv.lineSequence().filter { it.startsWith("\"segment\"") }.sumOf { it.split(',')[5].trim('"').toLong() })
        val first = d.segments.first()
        val corrected = tracker.correctSegment(token(),first.id,first.start+60_000,first.end!!,first.selection)
        assertEquals(39*60_000,corrected.report(windowStart,clock.wall).recordedMs)
        assertEquals(setOf(sessionId),corrected.changedSessions.map { it.id }.toSet())
        assertEquals(39*60_000,Reports.report(tracker.data(),clock.now(),windowStart,clock.wall).recordedMs)
        tracker.undo(token()); assertEquals(d.segments,tracker.data().segments)
        assertEquals(39*60_000,corrected.report(windowStart,clock.wall).recordedMs) // command result is a fixed snapshot
        tracker.saveType(token(),ActivityType("other","Другая область"))
        tracker.saveCard(token(),card.copy(name = "Renamed",activityTypeId = "other",archived = true))
        tracker.saveType(token(),ActivityType("language","Переименованный тип",archived = true))
        assertEquals(all.recordedMs,Reports.report(tracker.data(),clock.now(),windowStart,clock.wall,Filter(activityTypeId = "language")).recordedMs)
        val currentData = tracker.data()
        expect(ErrorCode.INVALID_INPUT) { tracker.saveCard(token(),Card(name = "Broken link",activityTypeId = "missing-type")) }
        assertEquals(currentData,tracker.data())
        assertEquals("=Language",tracker.data().segments.first().selection.name)
        tracker.copyCard(token(),card.id,"Copy")
        assertEquals(1,tracker.data().sessions.size); assertEquals(1,tracker.data().measurements.size)
        val quantityId = tracker.data().measurements.single().id
        val removed = tracker.removeMeasurement(token(),quantityId)
        assertEquals(setOf(sessionId),removed.changedSessionIds); assertTrue(removed.data.measurements.isEmpty())
        tracker.undo(token()); assertEquals(Decimal("20"),tracker.data().measurements.single().value)
        val whole = tracker.correctSession(token(),sessionId,tracker.data().segments.map { Tracker.SegmentEdit(it.id,it.start,it.end!!,it.selection) },"Исправленная заметка")
        assertEquals("Исправленная заметка",whole.changedSessions.single().note)
        assertEquals(40*60_000,whole.report(windowStart,clock.wall).recordedMs)
        val before = tracker.data()
        expect(ErrorCode.OVERLAP) { tracker.manual(token(),first.start,first.end,first.selection) }; assertEquals(before,tracker.data())
        // Calendar days use zone rules: Berlin spring DST day has 23 hours.
        val spring = Instant.parse("2026-03-29T22:00:00Z").toEpochMilli()
        val dstSession = Session(status = SessionStatus.STOPPED)
        val dstData = Dataset(sessions = listOf(dstSession),segments = listOf(Segment(sessionId = dstSession.id,start = spring-23*3_600_000,end = spring,durationMs = 23*3_600_000)),settings = Settings("Europe/Berlin"))
        assertEquals(23*3_600_000,Reports.day(dstData,clock.now(),LocalDate.parse("2026-03-29")).recordedMs)
        val calendar = Reports.compareCalendarDays(dstData,clock.now(),LocalDate.parse("2026-03-29"),LocalDate.parse("2026-03-28"),1)
        assertEquals(23*3_600_000,calendar.current.end-calendar.current.start)
        assertEquals(24*3_600_000,calendar.previous.end-calendar.previous.start)
        assertEquals(23*3_600_000,calendar.absoluteDifferenceMs); assertNull(calendar.percent)
        val autumn = Reports.compareCalendarDays(dstData,clock.now().copy(wall = Instant.parse("2026-10-27T00:00:00Z").toEpochMilli()),
            LocalDate.parse("2026-10-25"),LocalDate.parse("2026-10-24"),1)
        assertEquals(25*3_600_000,autumn.current.end-autumn.current.start)
        assertEquals(24*3_600_000,autumn.previous.end-autumn.previous.start)
        val incomplete = Reports.compareCalendarDays(dstData,clock.now().copy(wall = spring-12*3_600_000),LocalDate.parse("2026-03-29"),LocalDate.parse("2026-03-28"),1)
        assertTrue(incomplete.current.incomplete); assertFalse(incomplete.previous.incomplete)
        expect(ErrorCode.INVALID_INTERVAL) { Reports.day(dstData,clock.now(),LocalDate.MAX) }
        val huge = dstData.copy(segments = listOf(dstData.segments.single().copy(start = 0,end = MAX_TIME,durationMs = MAX_TIME)))
        assertEquals(3_600_000,Reports.report(huge,clock.now().copy(wall = MAX_TIME),MAX_TIME-3_600_000,MAX_TIME).recordedMs) // BigInteger intermediate, no multiplication overflow
    }

    private fun indexedReportOracle() {
        val clock = FakeClock();val t = Tracker(createStore(),clock);val now = clock.wall
        t.manual(t.snapshot().token,now-86_400_000,now-86_399_000,Classification(tags = setOf("match")))
        t.manual(t.snapshot().token,now-2000,now-1000,Classification())
        t.start(t.snapshot().token,Classification(tags = setOf("match")));clock.advance(500)
        fun compare() {
            val d = t.data();val view = t.view();val moment = clock.now()
            for(f in listOf(Filter(),Filter(tags = setOf("match")),Filter(tags = setOf("missing")))) {
                for((a,b) in listOf(now-86_400_000 to now-86_399_500,now-3000 to now-1500,now-1000 to now+1000))
                    assertEquals(Reports.report(d,moment,a,b,f),view.report(a,b,f))
                for(zone in listOf("UTC","Europe/Berlin","America/Los_Angeles")) {
                    val day = Instant.ofEpochMilli(now).atZone(java.time.ZoneId.of(zone)).toLocalDate()
                    assertEquals(Reports.calendar(d,moment,day.minusDays(2),4,f,zone),view.calendar(day.minusDays(2),4,f,zone))
                }
            }
        }
        compare();clock.boot = "another-boot";compare() // unresolved segments remain global, not guessed into a date
    }

    private fun hierarchyAndDecimals() {
        val clock = FakeClock(); val t = Tracker(createStore(),clock)
        fun token() = t.snapshot().token
        val parent = ActivityType("area.test","Broad")
        val other = ActivityType("area.other","Other")
        val child = ActivityType("activity.test","Specific",parentId = parent.id)
        val language = Axis("axis.test","Language",valueKind = ValueKind.LOCAL_CHOICE,
            values = listOf(AxisValue("es","Spanish")))
        val source = Axis("axis.source","Source",valueKind = ValueKind.FREE_LABEL)
        val units = listOf(UnitDefinition("unit.m","Metres","length"),UnitDefinition("unit.mi","Miles","length",Decimal("1609.344")))
        val distance = Metric("measure.distance","Distance",numberKind = NumberKind.DECIMAL,canonicalUnitId = "unit.m",maxScale = 4,aggregation = Aggregation.SUM)
        t.addCatalog(token(),VocabularyData(listOf(parent,other,child),listOf(language,source),listOf(distance),units))
        val a = Facet(language.id,valueId = "es",valueMeaningVersion = 1)
        val b = Facet(source.id,text = "YouTube")
        val chosen = t.typeSelection(child.id).copy(facets = listOf(a,b))
        val from = clock.wall
        t.start(token(),chosen); val first = t.snapshot().session!!.id
        expect(ErrorCode.INVALID_INPUT) { t.changeDetails(token(),t.typeSelection(other.id)) }
        clock.advance(1000); t.pause(token()); clock.advance(1000); t.resume(token()); clock.advance(1000); t.stop(token())
        val converted = t.data().convert(Decimal("0.1"),"unit.mi","unit.m")
        assertEquals(Decimal("160.9344"),converted)
        t.measure(token(),Measurement(fieldId = distance.id,unit = "unit.m",scope = Scope.SESSION,targetId = first,value = converted))
        t.start(token(),t.typeSelection(parent.id)); clock.advance(1000); t.stop(token())
        val own = Card(name = "Unmapped"); t.saveCard(token(),own)
        t.start(token(),t.selection(own.id)); clock.advance(1000); t.stop(token())
        fun report(filter: Filter = Filter()) = Reports.report(t.data(),clock.now(),from,clock.wall,filter)
        assertEquals(4000,report().recordedMs)
        assertEquals(3000,report(Filter(activityTypeId = parent.id)).recordedMs)
        assertEquals(3000,report().byType()[parent.id])
        assertEquals(2000,report(Filter(facets = listOf(a,b))).recordedMs)
        assertEquals(2000,report(Filter(facets = listOf(a,b),facetMatch = FacetMatch.ANY)).recordedMs)
        assertEquals(converted,report().quantityTotals()[QuantityKey(distance.id,1,"unit.m",Scope.SESSION)])
        val oldSegments = t.data().segments
        t.saveType(token(),child.copy(parentId = other.id))
        assertEquals(oldSegments,t.data().segments)
        assertEquals(3000,report(Filter(activityTypeId = parent.id)).recordedMs)
        val edits = t.data().segments.filter { it.sessionId == first }.map { Tracker.SegmentEdit(it.id,it.start,it.end!!,it.selection) }
        t.correctSession(token(),first,edits,"Only a note")
        assertEquals(3000,report(Filter(activityTypeId = parent.id)).recordedMs)
        val beforePreview = t.data()
        val preview = t.previewCorrection(first,edits,"Explicit reclassification",reclassify = true)
        assertEquals(beforePreview,t.data())
        val (beforeReport,afterReport) = preview.reports(from,clock.wall,Filter(activityTypeId = parent.id))
        assertEquals(3000,beforeReport.recordedMs); assertEquals(1000,afterReport.recordedMs)
        t.applyCorrection(preview)
        assertEquals(1000,report(Filter(activityTypeId = parent.id)).recordedMs)
        assertEquals(2000,report(Filter(activityTypeId = other.id)).recordedMs)
        t.undo(token())
        assertEquals(3000,report(Filter(activityTypeId = parent.id)).recordedMs)
        t.repeat(token(),first); clock.advance(1000); t.stop(token())
        assertEquals(listOf(other.id,child.id),t.data().segments.last().selection.ancestorPathAtWrite)
        assertEquals(1000,report(Filter(activityTypeId = other.id)).recordedMs)
        expect(ErrorCode.INVALID_INPUT) { t.saveType(token(),other.copy(parentId = child.id)) }
        expect(ErrorCode.INVALID_INPUT) { t.start(token(),chosen.copy(facets = listOf(a.copy(text = "bad")))) }
        expect(ErrorCode.INVALID_INPUT) { t.saveMetric(token(),distance.copy(maxScale = 2)) }
        // Semantic identity is immutable; ordinary presentation/navigation edits stay legal.
        val stable = t.data()
        expect(ErrorCode.INVALID_INPUT) { t.saveType(token(),child.copy(meaningVersion = 2)) }
        expect(ErrorCode.INVALID_INPUT) { t.saveType(token(),child.copy(definition = "A different meaning")) }
        expect(ErrorCode.INVALID_INPUT) { t.saveType(token(),parent.copy(suggestedAxisIds = setOf("missing.axis"))) }
        expect(ErrorCode.INVALID_INPUT) { t.saveType(token(),parent.copy(suggestedMetricIds = setOf("missing.metric"))) }
        assertEquals(stable,t.data())
        t.saveType(token(),parent.copy(name = "Renamed",suggestedAxisIds = setOf(language.id),suggestedMetricIds = setOf(distance.id)))
        // Raw classifications cannot bypass the selected node's flags. Flags are node-local, not recursive.
        t.saveType(token(),parent.copy(archived = true))
        val rawParent = Classification(activityTypeId = parent.id)
        expect(ErrorCode.INVALID_INPUT) { t.start(token(),rawParent) }
        t.saveType(token(),child.copy(parentId = parent.id))
        t.start(token(),Classification(activityTypeId = child.id))
        val beforeSwitch = t.data()
        expect(ErrorCode.INVALID_INPUT) { t.switch(token(),rawParent) }
        assertEquals(beforeSwitch,t.data()); t.stop(token())
        val broadSession = t.data().sessions.first { it.selection.activityTypeId == parent.id }
        t.repeat(token(),broadSession.id); t.stop(token()) // explicit Repeat still permits archived history
        t.saveType(token(),parent.copy(selectable = false))
        expect(ErrorCode.INVALID_INPUT) { t.start(token(),rawParent) }
        t.start(token(),Classification(activityTypeId = child.id)); t.stop(token())

        val existingChild = t.data().types.single { it.id == child.id }
        val collision = t.addCatalog(token(),VocabularyData(listOf(existingChild.copy(name = "Incoming rename"),other)))
            .catalogImport!!
        assertEquals(listOf(child.id),collision.conflicts.map { it.id })
        assertEquals(listOf(other.id),collision.unchanged.map { it.id })
        assertTrue(collision.added.isEmpty())
        assertEquals(existingChild,t.data().types.single { it.id == child.id })
        val newNode = ActivityType("activity.new","New",parentId = other.id)
        assertEquals(listOf(newNode.id),t.addCatalog(token(),VocabularyData(listOf(newNode))).catalogImport!!.added.map { it.id })
        val beforeDuplicates = t.data()
        expect(ErrorCode.INVALID_INPUT) { t.addCatalog(token(),VocabularyData(listOf(existingChild,existingChild.copy(name = "Duplicate")))) }
        expect(ErrorCode.INVALID_INPUT) { t.addCatalog(token(),VocabularyData(emptyList(),axes = listOf(language,language))) }
        expect(ErrorCode.INVALID_INPUT) { t.addCatalog(token(),VocabularyData(emptyList(),metrics = listOf(distance,distance))) }
        expect(ErrorCode.INVALID_INPUT) { t.addCatalog(token(),VocabularyData(emptyList(),units = listOf(units[0],units[0]))) }
        expect(ErrorCode.INVALID_INPUT) { t.addCatalog(token(),VocabularyData(listOf(existingChild.copy(suggestedAxisIds = setOf("unknown"))))) }
        assertEquals(beforeDuplicates,t.data())

        // A failed registered-metric conversion retains the form input; saving in the wrong unit is rejected.
        t.saveUnit(token(),UnitDefinition("unit.three_m","Three metres","length",Decimal("3")))
        val thirds = distance.copy(id = "measure.thirds",canonicalUnitId = "unit.three_m")
        t.saveMetric(token(),thirds)
        val draft = MeasurementDraft(thirds.id,1,Decimal("1"),"unit.m",Scope.SESSION,first)
        val beforeConversion = t.data(); val prepared = t.data().prepareMeasurement(draft)
        assertEquals(draft,prepared.draft); assertNull(prepared.measurement)
        assertTrue(prepared.error!!.contains("unit.three_m"))
        expect(ErrorCode.INVALID_INPUT) { t.measure(token(),Measurement(fieldId = thirds.id,unit = draft.unitId,scope = draft.scope,targetId = first,value = draft.value)) }
        assertEquals(beforeConversion,t.data())
        val explicitCanonical = t.data().prepareMeasurement(draft.copy(value = Decimal("2"),unitId = "unit.three_m"))
        assertNull(explicitCanonical.error)
        t.measure(token(),explicitCanonical.measurement!!)
        assertEquals(Decimal("2"),t.data().measurements.last().value)
        val restored = Backups.decode(Backups(t,MemoryRollback()).export()).data
        assertEquals(t.data(),restored)
        assertTrue(Csv.export(report()).contains("160.9344"))
    }

    @Test fun backupReplacementAndFailure() {
        assignmentRestoreBoundary()
        streamingBackupFormats()
        backupDocumentInput()
        val sampleDir = java.io.File(System.getProperty("tracker.samples"))
        val historySample = Backups.decode(java.io.File(sampleDir,"history-v1.json").readText())
        assertEquals(1,historySample.data.sessions.size)
        assertEquals(1_200_000,historySample.data.segments.single().durationMs)
        assertEquals(historySample.data,Backups.decode(java.io.File(sampleDir,"history-v2.json").readText()).data)
        val oldRoot = codec.parseToJsonElement(java.io.File(sampleDir,"history-v2.json").readText()).jsonObject
        for(version in 2..7) {
            val data = oldRoot.getValue("data").jsonObject.toMutableMap()
            if(version >= 6) data["goals"] = JsonArray(emptyList())
            if(version >= 7) data["focus"] = JsonNull
            val root = JsonObject(oldRoot.toMutableMap().apply {
                put("formatVersion",JsonPrimitive(version)); put("data",JsonObject(data))
            })
            assertEquals(historySample.data,Backups.decode(root.toString()).data)
        }
        repeatedRestoreAndReferences(java.io.File(sampleDir,"history-v2.json").readText())
        val legacyJson = java.io.File(sampleDir,"history-v1.json").readText()
        val migrated = migrateStoredV1(legacyJson)
        assertEquals(historySample.data,migrated)
        assertEquals(listOf("language"),migrated.segments.single().selection.ancestorPathAtWrite)
        val largest = Backups.decode(legacyJson.replace("\"value\": 20","\"value\": 9223372036854775807"))
        assertEquals(Decimal("9223372036854775807"),largest.data.measurements.single().value)
        val unknownLegacy = legacyJson.replace("listening","unknown-custom-detail").replace("\"value\": 20","\"value\": 9223372036854775807")
        val collisions = unknownLegacy.replace("\"activity\":", "\"partOf\": \"preserve-this-text\", \"value\": \"3\", \"activity\":")
        val unknownMigrated = migrateStoredV1(collisions)
        assertEquals("preserve-this-text",unknownMigrated.segments.single().selection.details["partOf"])
        assertEquals("unknown-custom-detail",unknownMigrated.segments.single().selection.details["activity"])
        assertEquals(Decimal("9223372036854775807"),unknownMigrated.measurements.single().value)
        val openSample = Backups.decode(java.io.File(sampleDir,"open-tail-v1.json").readText())
        assertNull(openSample.data.segments.single().end)
        val historical = Tracker(createStore(),FakeClock())
        val historicalBackup = Backups(historical,MemoryRollback())
        historicalBackup.apply(historicalBackup.prepare(java.io.File(sampleDir,"history-v1.json").readText()))
        assertEquals(historySample.data.copy(revision = 1,controlBarrier = historical.data().controlBarrier),historical.data())
        val clock = FakeClock(); val tracker = Tracker(createStore(),clock)
        fun token() = tracker.snapshot().token
        val card = Card(name = "Своё",activityTypeId = "language")
        tracker.saveCard(token(),card); tracker.settings(token(),Settings("Europe/Berlin"))
        tracker.start(token(),card.classification()); clock.advance(42_000)
        tracker.saveType(token(),ActivityType("language","Новый заголовок",archived = true))
        val disk = MemoryRollback(); val backups = Backups(tracker,disk); val json = backups.export()
        val raw = Backups.decode(json)
        assertEquals(42_000,raw.data.segments.single().durationMs)
        assertNull(raw.data.segments.single().end); assertNull(raw.data.segments.single().anchor)
        val clean = Tracker(createStore(),clock); val restore = Backups(clean,disk)
        val preview = restore.prepare(json); assertTrue(preview.unfinishedTail); assertEquals(1,preview.cards)
        assertTrue(clean.data().sessions.isEmpty()) // cancellation is just abandoning preview
        val result = restore.apply(preview)
        assertEquals(Mode.RECOVERY,result.state.mode); assertEquals(42_000,result.state.trustedDurationMs)
        assertEquals(Settings("Europe/Berlin"),clean.data().settings)
        assertEquals(ActivityType("language","Новый заголовок",true),clean.data().types.single { it.id == "language" })
        assertEquals("language",clean.data().segments.single().selection.activityTypeId)
        assertTrue(Backups.decode(disk.read(result.rollbackReference)).data.sessions.isEmpty())
        val full = clean.data()
        expect(ErrorCode.UNSUPPORTED_IMPORT) { restore.prepare(json.dropLast(7)) }; assertEquals(full,clean.data())
        expect(ErrorCode.UNSUPPORTED_IMPORT) { restore.prepare(json.replace("\"formatVersion\": 8","\"formatVersion\": 999")) }; assertEquals(full,clean.data())
        expect(ErrorCode.UNSUPPORTED_IMPORT) { restore.prepare(json.replace("\"datasetId\": \"${raw.data.datasetId}\",", "")) }; assertEquals(full,clean.data())
        val broken = raw.copy(data = raw.data.copy(segments = raw.data.segments+raw.data.segments.first()))
        expect(ErrorCode.UNSUPPORTED_IMPORT) { restore.prepare(codec.encodeToString(broken)) }; assertEquals(full,clean.data())
        val stale = restore.prepare(json); clean.settings(clean.snapshot().token,Settings("UTC"))
        expect(ErrorCode.STALE) { restore.apply(stale) }
        val badDisk = object: RollbackStore { override fun save(json: String) = "bad"; override fun read(reference: String) = "damaged" }
        clean.saveCard(clean.snapshot().token,Card(name = "Local data that replacement would delete"))
        val guarded = Backups(clean,badDisk); val before = clean.data()
        expect(ErrorCode.STORAGE) { guarded.apply(guarded.prepare(json)) }; assertEquals(before,clean.data())
        assertImportRollback(clean,json,disk)
        assertEquals(before,clean.data())
        val impossibleTail = raw.copy(data = raw.data.copy(segments = raw.data.segments.map { it.copy(durationMs = MAX_TIME) }))
        expect(ErrorCode.UNSUPPORTED_IMPORT) { restore.prepare(codec.encodeToString(impossibleTail)) }; assertEquals(before,clean.data())
        clean.resolveTail(clean.snapshot().token,raw.data.segments.single().start,raw.asOf)
        assertEquals(42_000,Reports.report(clean.data(),clock.now(),clock.wall-60_000,clock.wall).recordedMs)
        validate(clean.data())
        val raceDisk = object: RollbackStore {
            override fun save(json: String): String {
                val reference = disk.save(json)
                clean.settings(clean.snapshot().token,Settings("Etc/UTC")) // legal concurrent change during external file IO
                return reference
            }
            override fun read(reference: String) = disk.read(reference)
        }
        val racing = Backups(clean,raceDisk)
        expect(ErrorCode.STALE) { racing.apply(racing.prepare(json)) }
        assertEquals(Settings("Etc/UTC"),clean.data().settings)
        assertTrue(clean.data().cards.any { it.name == "Local data that replacement would delete" })
        val restoredQuantities = Reports.report(historical.data(),clock.now().copy(wall = historySample.asOf),
            historySample.data.segments.single().start,historySample.asOf).quantityTotals()
        assertEquals(Decimal("20"),restoredQuantities[QuantityKey("pages",1,"page",Scope.SESSION)])
    }

    private fun streamingBackupFormats() {
        val duplicateNested = Backups.encode(Dataset(),FakeClock().now()).replace(Regex("\"reportZone\"\\s*:\\s*\"[^\"]*\"")) { "${it.value},${it.value}" }
        expect(ErrorCode.UNSUPPORTED_IMPORT) { StreamingBackup.read { duplicateNested.byteInputStream() } }

        largeStreamingBackup()
        val root = java.io.File(System.getProperty("tracker.samples"))
        for(name in listOf("history-v1.json","history-v2.json","open-tail-v1.json")) {
            val file = root.resolve(name);val streamed = StreamingBackup.read { file.inputStream() }
            assertEquals(Backups.decode(file.readText()),streamed)
        }
        val clock = FakeClock();val tracker = Tracker(createStore(),clock)
        tracker.manual(tracker.snapshot().token,clock.wall-2000,clock.wall-1000,Classification(),"Streamed note")
        val portable = StreamingBackup.portable(tracker.data(),clock.now())
        val bytes = java.io.ByteArrayOutputStream().also { StreamingBackup.write(portable,it) }.toByteArray()
        assertEquals(portable,StreamingBackup.read { bytes.inputStream() })
        assertEquals(portable,Backups.decode(bytes.toString(Charsets.UTF_8)))
        val bom = byteArrayOf(0xEF.toByte(),0xBB.toByte(),0xBF.toByte())+bytes
        assertEquals(portable,StreamingBackup.read { bom.inputStream() })
        val text = bytes.toString(Charsets.UTF_8)
        val malformed = text.substringBefore("Streamed note").toByteArray() + byteArrayOf(0xC3.toByte(),0x28) + text.substringAfter("Streamed note").toByteArray()
        for(bad in listOf(bytes.copyOf(bytes.size-2),malformed,
            text.replaceFirst("\"formatVersion\":8","\"formatVersion\":8,\"formatVersion\":8").toByteArray(),bytes+byteArrayOf(1))) {
            expect(ErrorCode.UNSUPPORTED_IMPORT) { StreamingBackup.read { bad.inputStream() } }
        }
        expect(ErrorCode.UNSUPPORTED_IMPORT) { StreamingBackup.read { (byteArrayOf(0xFF.toByte())+bytes).inputStream() } }
        var reads = 0
        expect(ErrorCode.UNSUPPORTED_IMPORT) { StreamingBackup.read { reads++;if(reads==1) bytes.inputStream() else text.replace("Streamed note","Changed note").byteInputStream() } }
    }
    private fun largeStreamingBackup() {
        val directory = java.io.File(requireNotNull(System.getProperty("tracker.artifacts")),"large-backup-${newId()}").apply { mkdirs() }
        val clock = FakeClock();val source = Tracker(createStore(),clock)
        val count = 10_000;val start = clock.wall-count*2000L
        source.store.transaction { old ->
            val sessions = (0 until count).map { Session(id = "large-$it",status = SessionStatus.STOPPED,note = "Запись $it: "+"n".repeat(1200)) }
            val segments = sessions.mapIndexed { i,s -> Segment(id = "large-segment-$i",sessionId = s.id,start = start+i*2000L,
                end = start+i*2000L+1000,durationMs = 1000) }
            old.copy(revision = old.revision+1,sessions = sessions,segments = segments) to Unit
        }
        val original = source.data();val file = java.io.File(directory,"large.json")
        file.outputStream().use { Backups(source,MemoryRollback()).write(it) }
        assertTrue(file.length() > MAX_BACKUP_BYTES)
        val files = object: RollbackStore {
            override fun save(json: String): String = newId().also { java.io.File(directory,it).writeText(json) }
            override fun read(reference: String) = java.io.File(directory,reference).readText()
            override fun saveSnapshot(backup: Backup): String = newId().also { reference ->
                java.io.FileOutputStream(java.io.File(directory,reference)).use { StreamingBackup.write(backup,it);it.fd.sync() }
            }
            override fun readSnapshot(reference: String) = StreamingBackup.read { java.io.File(directory,reference).inputStream() }
        }
        var target = Tracker(createStore(),clock);val restore = Backups(target,files)
        val before = target.data();val preview = restore.prepare { file.inputStream() }
        assertEquals(count,preview.sessions);assertEquals(before,target.data())
        restore.apply(preview)
        assertEquals(original.copy(revision = target.data().revision,controlBarrier = target.data().controlBarrier),target.data())
        val largeBefore = target.data()
        val repeated = restore.apply(restore.prepare { file.inputStream() })
        assertEquals(largeBefore,files.readSnapshot(repeated.rollbackReference).data)
        target = Tracker(reopen(target.store),clock);assertEquals(count,target.data().sessions.size)
        assertEquals(count*1000L,Reports.report(target.data(),clock.now(),start,clock.wall).recordedMs)
    }
    private fun chronologicalHistory() {
        val clock = FakeClock();val t = Tracker(createStore(),clock)
        t.manual(t.snapshot().token,clock.wall-2000,clock.wall-1000,Classification(activityTypeId = "language",name = "Today"))
        val today = t.data().segments.single()
        t.manual(t.snapshot().token,clock.wall-86_400_000,clock.wall-86_399_000,Classification(name = "Yesterday"))
        assertEquals(today.id,t.choices().latestRecorded()!!.sourceSegmentId)
        val transfer = HistoryTransfer(t,MemoryRollback())
        val start = Instant.ofEpochMilli(clock.wall-259_200_000).toString();val end = Instant.ofEpochMilli(clock.wall-259_199_000).toString()
        val rows = HistoryTransfer.parseCsv("id,activity,start,end\nold,Older CSV,$start,$end\n",CsvMapping("chronological","activity","start",end = "end",id = "id"))
        transfer.apply(transfer.preview(rows,rows.activities.associateWith { Classification(name = it.activity) }))
        assertEquals(today.id,t.choices().latestRecorded()!!.sourceSegmentId)

        t.correctSegment(t.snapshot().token,today.id,clock.wall-172_800_000,clock.wall-172_799_000,today.selection)
        val latest = t.choices().latestRecorded()!!
        assertEquals("Yesterday",latest.selection.name)
        t.repeat(t.snapshot().token,latest.sourceSessionId!!);assertEquals("Yesterday",t.snapshot().session!!.selection.name)
        clock.advance(1000);t.stop(t.snapshot().token)
        val before = t.data();val view = t.view()
        assertEquals(Reports.day(before,clock.now(),java.time.Instant.ofEpochMilli(clock.wall).atZone(java.time.ZoneId.of("UTC")).toLocalDate()),
            view.day(java.time.Instant.ofEpochMilli(clock.wall).atZone(java.time.ZoneId.of("UTC")).toLocalDate()))
    }
    private fun previewSnapshots() {
        val clock = FakeClock();val tracker = Tracker(createStore(),clock)
        val axis = Axis("preview-axis","Preview",valueKind = ValueKind.FREE_LABEL,cardinality = Cardinality.MULTIPLE)
        tracker.saveAxis(tracker.snapshot().token,axis)
        tracker.manual(tracker.snapshot().token,clock.wall-2000,clock.wall-1000,Classification())
        val session = tracker.data().sessions.single();val segment = tracker.data().segments.single()
        val details = mutableMapOf("form" to "viewed");val tags = mutableSetOf("viewed")
        val facets = mutableListOf(Facet(axis.id,text = "viewed"));val path = mutableListOf("language")
        val selection = Classification(name = "Viewed",activityTypeId = "language",details = details,tags = tags,
            facets = facets,ancestorPathAtWrite = path)
        val correction = tracker.previewCorrection(session.id,listOf(Tracker.SegmentEdit(segment.id,segment.start,segment.end!!,selection)),"Viewed note",true)
        val viewed = correction.after.segments.single().selection
        details["form"] = "changed";tags += "changed";facets.clear();path.clear()
        assertEquals("viewed",viewed.details["form"])
        assertFailsWith<UnsupportedOperationException> { (viewed.details as MutableMap)["form"] = "hidden change" }
        assertFailsWith<UnsupportedOperationException> { (correction.after.segments as MutableList).clear() }
        tracker.applyCorrection(correction);assertEquals(viewed,tracker.data().segments.single().selection)
        val transfer = HistoryTransfer(tracker,MemoryRollback())
        val file = HistoryTransfer.parseCsv("id,activity,category,note,start,end,duration_ms\nold,Task,,Note,${Instant.ofEpochMilli(clock.wall-5000)},${Instant.ofEpochMilli(clock.wall-4000)},1000\n",
            CsvMapping("preview-forms",activity = "activity",category = "category",note = "note",id = "id",start = "start",end = "end",duration = "duration_ms",durationFormat = DurationFormat.MILLISECONDS))
        val csvDetails = mutableMapOf("form" to "viewed-csv");val csvTags = mutableSetOf("viewed-csv")
        val csvFacets = mutableListOf(Facet(axis.id,text = "viewed-csv"))
        val mapping = file.activities.associateWith { Classification(name = "CSV",activityTypeId = "language",details = csvDetails,tags = csvTags,facets = csvFacets) }
        val preview = transfer.preview(file,mapping);assertTrue(preview.canApply)
        val viewedCsv = preview.intervals.single().selection
        csvDetails.clear();csvTags.clear();csvFacets.clear()
        assertFailsWith<UnsupportedOperationException> { (preview.intervals as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (preview.errors as MutableList).add("hidden error") }
        assertFailsWith<UnsupportedOperationException> { (file.rows.single().origin.fields as MutableMap).clear() }
        transfer.apply(preview)
        assertEquals(viewedCsv,tracker.data().segments.single { it.sessionId != session.id }.selection)
    }
    private fun readyPackage() = ReadyCatalog.read { path ->
        java.io.File(System.getProperty("tracker.samples")).parentFile.resolve("catalog/$path").inputStream()
    }
    private fun readyCatalogLifecycle() {
        val ready = readyPackage()
        val empty = ready.vocabulary.types.single { it.id == "area.self_directed_creation" }
        assertFalse(empty.selectable);assertFalse(empty.archived)
        assertTrue(ready.vocabulary.types.none { it.parentId == empty.id })
        val groupTracker = Tracker(createStore(),FakeClock())
        groupTracker.addCatalog(groupTracker.snapshot().token,ready.vocabulary)
        val groupChoices = groupTracker.choices()
        assertTrue(groupChoices.search(ChoiceQuery(text = empty.name,kinds = setOf(ChoiceKind.TYPE))).items.none { it.entry.sourceId == empty.id })
        assertEquals(ChoiceAvailability.EMPTY_GROUP,groupChoices.search(ChoiceQuery(text = empty.name,kinds = setOf(ChoiceKind.TYPE),includeArchived = true)).items.single { it.entry.sourceId == empty.id }.entry.availability)

        val clock = FakeClock();var tracker = Tracker(createStore(),clock)
        val previous = java.util.zip.GZIPInputStream(requireNotNull(ScenarioContract::class.java.getResourceAsStream("/previous-runtime-catalog.json.gz")))
            .bufferedReader().use { codec.decodeFromString<VocabularyData>(it.readText()) }
        assertEquals(178,previous.types.zip(ready.vocabulary.types).count { (old,next) -> old.definition != next.definition })
        assertEquals(3,previous.types.zip(ready.vocabulary.types).count { (old,next) -> old.name != next.name } +
            previous.axes.zip(ready.vocabulary.axes).count { (old,next) -> old.name != next.name })
        for((old,next) in previous.types.zip(ready.vocabulary.types))
            assertEquals(old,next.copy(name = old.name,definition = old.definition,researchOrigins = old.researchOrigins))
        for((old,next) in previous.axes.zip(ready.vocabulary.axes))
            assertEquals(old,next.copy(name = old.name,researchOrigins = old.researchOrigins))
        val legacy = Tracker(createStore(),clock)
        legacy.addCatalog(legacy.snapshot().token,previous,rejectConflicts = true)
        val legacyType = previous.types.first { it.selectable }
        legacy.start(legacy.snapshot().token,legacy.typeSelection(legacyType.id));clock.advance(1000);legacy.stop(legacy.snapshot().token)
        legacy.saveFavorite(legacy.snapshot().token,Favorite(label = "Old favourite",selection = legacy.data().sessions.last().selection))
        legacy.saveType(legacy.snapshot().token,legacyType.copy(name = "My old title",archived = true,aliases = listOf("old local alias")))
        val oldData = legacy.data();val upgrade = CatalogImports(legacy) { ready }
        val oldPreview = upgrade.prepare();assertTrue(oldPreview.canApply);assertTrue(oldPreview.report.added.isEmpty())
        assertEquals(179,oldPreview.report.updated.size)
        upgrade.apply(oldPreview)
        val expectedTypes = oldData.types.map { old ->
            val baseline = previous.types.find { it.id == old.id };val next = ready.vocabulary.types.find { it.id == old.id }
            if(baseline == null || next == null) old else old.copy(definition = next.definition,
                name = if(old.name == baseline.name) next.name else old.name)
        }
        val expectedAxes = oldData.axes.map { old ->
            val baseline = previous.axes.find { it.id == old.id };val next = ready.vocabulary.axes.find { it.id == old.id }
            if(baseline == null || next == null) old else old.copy(name = if(old.name == baseline.name) next.name else old.name)
        }
        assertEquals(oldData.copy(revision = legacy.data().revision,catalogVersion = ready.manifest.catalogVersion,
            types = expectedTypes,axes = expectedAxes),legacy.data())
        assertEquals(legacy.data(),reopen(legacy.store).read())
        val importer = CatalogImports(tracker) { ready }
        val before = tracker.data();val preview = importer.prepare()
        assertTrue(preview.canApply);assertEquals(before,tracker.data())
        importer.apply(preview);val installed = tracker.data()
        assertEquals(ready.manifest.catalogVersion,installed.catalogVersion)
        assertTrue(ready.vocabulary.types.all { type -> installed.types.any { it == type } })
        assertEquals(ready.vocabulary.axes,installed.axes)
        val selected = ready.vocabulary.types.first { it.selectable }
        tracker.start(tracker.snapshot().token,tracker.typeSelection(selected.id));clock.advance(1000);tracker.stop(tracker.snapshot().token)
        tracker.saveFavorite(tracker.snapshot().token,Favorite(label = "Local favourite",selection = tracker.data().sessions.last().selection))
        val local = selected.copy(name = "My title",archived = true,aliases = listOf("local alias"))
        tracker.saveType(tracker.snapshot().token,local)
        val history = tracker.data().sessions;val segments = tracker.data().segments;val favorites = tracker.data().favorites
        val update = importer.prepare();assertTrue(update.canApply)
        importer.apply(update);assertEquals(local,tracker.data().types.single { it.id == local.id })
        assertEquals(history,tracker.data().sessions);assertEquals(segments,tracker.data().segments);assertEquals(favorites,tracker.data().favorites)
        expect(ErrorCode.STALE) { importer.apply(update) }
        tracker = Tracker(reopen(tracker.store),clock);assertEquals(local,tracker.data().types.single { it.id == local.id })
        // Provenance is technical metadata, not meaning. The trusted package bytes are checked separately.
        assertTrue(catalogMeaningEquivalent(selected.copy(researchOrigins = emptyList()),selected))
        assertFalse(catalogMeaningEquivalent(selected.copy(definition = "Different meaning"),selected))
        val axis = ready.vocabulary.axes.first()
        assertTrue(catalogMeaningEquivalent(axis.copy(name = "My axis",archived = true,researchOrigins = emptyList()),axis))
        assertFalse(catalogMeaningEquivalent(axis.copy(cardinality = if(axis.cardinality == Cardinality.SINGLE) Cardinality.MULTIPLE else Cardinality.SINGLE),axis))
        val altered = ready.vocabulary.copy(types = ready.vocabulary.types.map { if(it.id == selected.id) it.copy(definition = "Changed catalogue meaning") else it })
        val unchanged = tracker.data()
        expect(ErrorCode.INVALID_INPUT) { tracker.addCatalog(tracker.snapshot().token,altered,true,CatalogInstallPolicy.PRESERVE_LOCAL_PRESENTATION) }
        assertEquals(unchanged,tracker.data())
        val root = java.io.File(System.getProperty("tracker.samples")).parentFile.resolve("catalog")
        expect(ErrorCode.UNSUPPORTED_IMPORT) { ReadyCatalog.read { path ->
            if(path == "runtime-catalog.json") java.io.ByteArrayInputStream(root.resolve(path).readBytes()+byteArrayOf(10)) else root.resolve(path).inputStream()
        } }
    }

    private fun repeatedRestoreAndReferences(sampleJson: String) {
        val fixture = Backups.decode(sampleJson)
        val clock = FakeClock(wall = fixture.asOf+60_000)
        val t = Tracker(createStore(),clock); val disk = MemoryRollback(); val b = Backups(t,disk)
        b.apply(b.prepare(sampleJson))
        val originalSegments = t.data().segments
        val originalType = t.data().types.single()
        val parent = ActivityType("local.parent","Local parent")
        t.saveType(t.snapshot().token,parent)
        t.saveType(t.snapshot().token,originalType.copy(name = "Renamed",parentId = parent.id))
        assertEquals(originalSegments,t.data().segments)
        val incoming = VocabularyData(listOf(originalType.copy(meaningVersion = 2,definition = "Different semantic meaning")))
        val conflict = t.addCatalog(t.snapshot().token,incoming)
        assertEquals(listOf(originalType.id),conflict.catalogImport!!.conflicts.map { it.id })
        val replay = t.addCatalog(t.snapshot().token,incoming)
        assertEquals(conflict.catalogImport,replay.catalogImport)
        assertEquals(originalSegments,t.data().segments)
        assertEquals(1,t.data().types.single { it.id == originalType.id }.meaningVersion)
        val exported = b.export(); val saved = Backups.decode(exported).data
        val clean = Tracker(createStore(),clock); val restore = Backups(clean,disk)
        restore.apply(restore.prepare(exported))
        assertEquals(saved.copy(revision = clean.data().revision,controlBarrier = clean.data().controlBarrier),clean.data())
        val once = clean.data()
        restore.apply(restore.prepare(exported))
        assertEquals(once.copy(revision = once.revision+1,controlBarrier = clean.data().controlBarrier),clean.data())
        val marker = Card(name = "New local record since backup")
        clean.saveCard(clean.snapshot().token,marker)
        val beforeReplacement = clean.data()
        val result = restore.apply(restore.prepare(exported))
        assertFalse(clean.data().cards.any { it.id == marker.id }) // full replacement is not merge
        val rollbackJson = disk.read(result.rollbackReference)
        assertEquals(beforeReplacement,Backups.decode(rollbackJson).data)
        restore.apply(restore.prepare(rollbackJson))
        assertEquals(beforeReplacement.copy(revision = clean.data().revision,controlBarrier = clean.data().controlBarrier),clean.data())
        val unchanged = clean.data(); val diskCount = disk.saved.size
        val source = Backups.decode(exported)
        val invalidGraphs = listOf(
            saved.copy(types = saved.types.map { if(it.id == parent.id) it.copy(parentId = originalType.id) else it }),
            saved.copy(types = saved.types.filterNot { it.id == parent.id }),
            saved.copy(cards = emptyList()),
            saved.copy(measurements = saved.measurements.map { it.copy(targetId = "missing") }),
            saved.copy(types = saved.types.map { it.copy(suggestedAxisIds = setOf("missing")) }),
            saved.copy(segments = saved.segments.map { it.copy(selection = it.selection.copy(ancestorPathAtWrite = listOf("missing",originalType.id))) })
        )
        invalidGraphs.forEach { graph ->
            expect(ErrorCode.UNSUPPORTED_IMPORT) { restore.prepare(codec.encodeToString(source.copy(data = graph))) }
            assertEquals(unchanged,clean.data()); assertEquals(diskCount,disk.saved.size)
        }
        expect(ErrorCode.INVALID_INTERVAL) { Backups.encode(Dataset(),clock.now().copy(wall = -1)) }
        val oversized = Dataset(cards = listOf(Card(name = "я".repeat(MAX_BACKUP_BYTES/2))))
        expect(ErrorCode.INVALID_INPUT) { Backups.encode(oversized,clock.now()) }
        // A backwards wall clock must not create a successfully exported but unrestorable backup.
        expect(ErrorCode.UNSUPPORTED_IMPORT) { Backups.encode(saved,clock.now().copy(wall = 0)) }
        assertEquals(unchanged,clean.data())
    }

    protected open fun migrateStoredV1(json: String): Dataset = Backups.decode(json).data

    protected open fun assertImportRollback(tracker: Tracker,json: String,disk: RollbackStore) {
        val failingStore = object: StateStore {
            override val changes = tracker.store.changes
            override fun read() = tracker.store.read()
            override fun <T> transaction(block: (Dataset)->Pair<Dataset,T>): T = tracker.store.transaction { live ->
                block(live)
                throw IllegalStateException("simulated persistence failure")
            }
        }
        val failing = Backups(Tracker(failingStore,tracker.clock),disk)
        expect(ErrorCode.STORAGE) { failing.apply(failing.prepare(json)) }
    }
}

// Prior materializing algorithm, kept only as a byte-for-byte streaming regression oracle.
private object MaterializedCsvReference {
    /** Segment rows use exactly Report's totals. Quantities get separate rows, once per target, never per day/minute. */
    fun export(report: Report,mode: CsvMode = CsvMode.SPREADSHEET_SAFE): String {
        fun cell(value: Any?): String {
            var s = value?.toString() ?: ""
            if (mode == CsvMode.SPREADSHEET_SAFE && s.trimStart().firstOrNull() in listOf('=','+','-','@')) s = "'"+s
            return "\"" + s.replace("\"","\"\"") + "\""
        }
        val rows = mutableListOf(listOf("type","id","session_or_target_id","start_ms","end_ms","duration_ms","card_id","name","activity_type_id","details","tags","note","field_id","meaning_version","unit","value","scope","zone","ancestor_path","facets"))
        report.rows.forEach { r -> rows += listOf("segment",r.segmentId,r.sessionId,r.start.toString(),r.end.toString(),r.durationMs.toString(),
            r.selection.cardId ?: "",r.selection.name ?: "",r.selection.activityTypeId ?: "",codec.encodeToString(r.selection.details),
            codec.encodeToString(r.selection.tags),r.note ?: "","","","","","",report.zone,codec.encodeToString(r.selection.ancestorPathAtWrite),codec.encodeToString(r.selection.facets)) }
        report.measurements.forEach { m -> rows += listOf("measurement",m.id,m.targetId,"","","","","","","","","",m.fieldId,m.meaningVersion.toString(),m.unit,m.value.toString(),m.scope.name,report.zone,"","") }
        // Explicitly expose excluded uncertain data without inventing period attribution or adding it to totals.
        report.unresolved.forEach { s -> rows += listOf("unresolved",s.id,s.sessionId,"","","","",s.selection.name ?: "","","","","","","","","","",report.zone,codec.encodeToString(s.selection.ancestorPathAtWrite),codec.encodeToString(s.selection.facets)) }
        return rows.joinToString("\r\n",postfix = "\r\n") { row -> row.joinToString(",") { cell(it) } }
    }
}
