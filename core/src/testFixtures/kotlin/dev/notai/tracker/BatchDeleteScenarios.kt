package dev.notai.tracker

import kotlin.test.*

fun batchDeleteCancellation(create: () -> StateStore) {
    val base = create();var cancel = false
    val cancelled = java.util.concurrent.CancellationException("Synthetic batch cancellation")
    val store = object: StateStore {
        override val changes get() = base.changes
        override val checkedChanges get() = base.checkedChanges
        override fun read() = base.read()
        override fun readChecked() = base.readChecked()
        override fun <T> transaction(block: (Dataset) -> Pair<Dataset,T>): T {
            if(cancel) throw cancelled
            return base.transaction(block)
        }
        override fun <T> checkedTransaction(block: (CheckedState) -> CheckedCommit<T>): T {
            if(cancel) throw cancelled
            return base.checkedTransaction(block)
        }
    }
    val clock = FakeClock();val tracker = Tracker(store,clock)
    tracker.manual(tracker.snapshot().token,clock.wall-1000,clock.wall,Classification())
    val before = tracker.data();val token = tracker.snapshot().token;val ids = before.sessions.map { it.id }
    cancel = true
    assertSame(cancelled,assertFailsWith<java.util.concurrent.CancellationException> { tracker.previewDeleteSessions(ids) })
    assertSame(cancelled,assertFailsWith<java.util.concurrent.CancellationException> { tracker.deleteSessions(token,ids) })
    assertEquals(before,base.read());assertTrue(tracker.canUndo(token))
    cancel = false;tracker.undo(token);assertTrue(tracker.data().sessions.isEmpty())
}

fun batchDeleteOneCommit(create: () -> StateStore,reopen: (StateStore) -> StateStore) {
    val clock = FakeClock();var tracker = Tracker(create(),clock)
    // First record crosses midnight: both day rows must identify the same deletion target.
    tracker.start(tracker.snapshot().token);clock.advance(20*60_000)
    val stopped = tracker.stopForAssignment(tracker.snapshot().timerTarget!!)
    val first = stopped.assignment!!.sessionId
    tracker.manual(tracker.snapshot().token,clock.wall-2_000_000,clock.wall-1_900_000,Classification(name = "Second"))
    val second = tracker.data().sessions.single { it.id != first }.id
    tracker.manual(tracker.snapshot().token,clock.wall-4_000_000,clock.wall-3_900_000,Classification(name = "Keep"))
    val kept = tracker.data().sessions.single { it.id !in setOf(first,second) }.id
    tracker.saveUnit(tracker.snapshot().token,UnitDefinition("batch-unit","m","distance"))
    tracker.saveUnit(tracker.snapshot().token,UnitDefinition("batch-kg","kg","mass"))
    tracker.saveMetric(tracker.snapshot().token,Metric("batch-distance","Distance",numberKind = NumberKind.DECIMAL,canonicalUnitId = "batch-unit"))
    val card = Card("batch-subject","Item")
    tracker.saveCard(tracker.snapshot().token,card)
    tracker.defineSubject(tracker.snapshot().token,CardSubject(card.id,"item"))
    val subject = SubjectRef(SubjectKind.CARD,card.id)
    for(id in listOf(first,second,kept)) {
        val segment = tracker.data().segments.single { it.sessionId == id }
        val measurement = Measurement("measure-$id","batch-distance",1,"batch-unit",Scope.SESSION,id,Decimal("10"))
        tracker.measure(tracker.snapshot().token,measurement)
        tracker.pairDistanceTime(tracker.snapshot().token,DistanceTimeInput("rate-$id",measurement.id,measurement.scope,id,
            segment.start,segment.end!!,segment.durationMs,TimeBasis.RECORDED,"recorded"))
        tracker.linkSubject(tracker.snapshot().token,SubjectSessionLink("link-$id",subject,id))
        val set = ExerciseSet("set-$id",id,0,8,Decimal("40"),"batch-kg",LoadMeaning.TOTAL_EXTERNAL,recordedAt = clock.wall,source = "manual")
        tracker.recordSet(tracker.snapshot().token,set)
        tracker.recordSet(tracker.snapshot().token,set.copy(id = "correction-$id",load = Decimal("42"),replacesId = set.id))
    }
    val before = tracker.data()
    val mutableIds = mutableListOf(first,second,first)
    val preview = tracker.previewDeleteSessions(mutableIds)
    mutableIds.clear()
    assertEquals(setOf(first,second),preview.sessionIds);assertEquals(2,preview.sessionCount)
    assertEquals(2,preview.segmentCount);assertEquals(2,preview.measurementCount)
    assertEquals(4,preview.exerciseSetCount);assertEquals(2,preview.distanceTimeCount)
    assertEquals(2,preview.subjectLinkCount);assertEquals(2,preview.subjectCorrectionCount)
    assertEquals(1,preview.pendingAssignmentCount);assertEquals(before,tracker.data())
    val reports = preview.reports(0,clock.wall)
    assertEquals(1_300_000L,reports.first.recordedMs-reports.second.recordedMs)
    val changed = tracker.applyDeleteSessions(preview)
    assertEquals(before.revision+1,changed.data.revision)
    assertEquals(setOf(first,second),changed.changedSessionIds);assertTrue(changed.undoAvailable)
    assertEquals(listOf(kept),changed.data.sessions.map { it.id })
    assertEquals(listOf("measure-$kept"),changed.data.measurements.map { it.id })
    assertEquals(listOf("set-$kept","correction-$kept"),changed.data.subjects.sets.map { it.id })
    assertEquals(listOf("link-$kept"),changed.data.subjects.links.map { it.id })
    assertEquals(before.subjects.cards,changed.data.subjects.cards)
    assertEquals(listOf("rate-$kept"),changed.data.subjects.distanceTimes.map { it.id })
    val json = Backups(tracker,MemoryRollback()).export()
    val backup = StreamingBackup.read { json.byteInputStream() }
    assertEquals(CURRENT_BACKUP_FORMAT,backup.formatVersion);assertEquals(changed.data,backup.data)
    tracker.undo(changed.state.token)
    val undone = tracker.data()
    assertEquals(before.sessions,undone.sessions);assertEquals(before.segments,undone.segments)
    assertEquals(before.measurements,undone.measurements);assertEquals(before.subjects,undone.subjects)
    // Undo restores facts, but keeps the existing protection against resurrecting consumed picker nonces.
    assertTrue(undone.pendingAssignments.isEmpty())
    tracker = Tracker(reopen(tracker.store),clock);assertEquals(undone,tracker.data())
    val applied = tracker.applyDeleteSessions(tracker.previewDeleteSessions(listOf(first,second)))
    tracker = Tracker(reopen(tracker.store),clock);assertEquals(applied.data,tracker.data())
    assertFalse(tracker.canUndo(tracker.snapshot().token)) // A fresh process has no snapshot Undo.
    tracker.restoreDeleted(tracker.snapshot().token,listOf(first))
    assertEquals(listOf(second),tracker.data().deletedRecords.map { it.sessionId })
    tracker = Tracker(reopen(tracker.store),clock)
    tracker.restoreDeleted(tracker.snapshot().token,listOf(second))
    val recovered = tracker.data()
    assertEquals(before.sessions.toSet(),recovered.sessions.toSet());assertEquals(before.segments.toSet(),recovered.segments.toSet())
    assertEquals(before.measurements.toSet(),recovered.measurements.toSet());assertEquals(before.subjects,recovered.subjects)
    assertTrue(recovered.deletedRecords.isEmpty());assertTrue(recovered.pendingAssignments.isEmpty())
}

fun recoverableDeletion(create: () -> StateStore,reopen: (StateStore) -> StateStore) {
    val clock = FakeClock();var tracker = Tracker(create(),clock)
    tracker.manual(tracker.snapshot().token,clock.wall-4000,clock.wall-3000,Classification(name = "First",details = mapOf("detail" to "literal")),"First note")
    val first = tracker.data().sessions.single().id
    tracker.manual(tracker.snapshot().token,clock.wall-2000,clock.wall-1000,Classification(name = "Second"),"Second note")
    val before = tracker.data()
    val second = before.sessions.single { it.id != first }.id
    val firstDelete = tracker.deleteSession(tracker.snapshot().token,first)
    tracker.deleteSession(tracker.snapshot().token,second)
    tracker.saveCard(tracker.snapshot().token,Card("independent","Keep this later edit"))
    tracker.restoreDeleted(tracker.snapshot().token,listOf(first))
    assertEquals(before.sessions.single { it.id == first },tracker.data().sessions.single())
    assertEquals(listOf(second),tracker.data().deletedRecords.map { it.sessionId })
    assertEquals("Keep this later edit",tracker.data().cards.single().name)
    val after = tracker.data()
    assertFailsWith<CoreError> { tracker.restoreDeleted(tracker.snapshot().token,listOf(first)) };assertEquals(after,tracker.data())
    assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> { tracker.restoreDeleted(firstDelete.state.token,listOf(second)) }.code)
    val again = tracker.deleteSession(tracker.snapshot().token,first)
    assertNotEquals(firstDelete.deletionBatchId,again.deletionBatchId)
    val json = Backups(tracker,MemoryRollback()).export()
    assertEquals(CURRENT_BACKUP_FORMAT,Backups.decode(json).formatVersion)
    val streamed = StreamingBackup.read { json.byteInputStream() };assertEquals(tracker.data(),streamed.data)
    val futureDeletion = json.replace("\"deletedAt\": ${clock.wall}","\"deletedAt\": ${clock.wall+1}")
    assertNotEquals(json,futureDeletion)
    assertFailsWith<CoreError> { Backups.decode(futureDeletion) }
    assertFailsWith<CoreError> { StreamingBackup.read { futureDeletion.byteInputStream() } }
    val legacySmuggle = json.replace("\"formatVersion\": 16","\"formatVersion\": 15")
    assertFailsWith<CoreError> { Backups.decode(legacySmuggle) }
    assertFailsWith<CoreError> { StreamingBackup.read { legacySmuggle.byteInputStream() } }
    val destination = Tracker(create(),clock);val restore = Backups(destination,MemoryRollback())
    restore.apply(restore.prepare(json));assertEquals(2,destination.data().deletedRecords.size)
    tracker = Tracker(reopen(tracker.store),clock)
    tracker.restoreDeleted(tracker.snapshot().token,listOf(first,second))
    assertEquals(before.sessions.toSet(),tracker.data().sessions.toSet());assertEquals(before.segments.toSet(),tracker.data().segments.toSet())
    assertTrue(tracker.data().deletedRecords.isEmpty());assertEquals("independent",tracker.data().cards.single().id)
    // Old JSON15 remains readable. The new field is omitted when empty.
    val legacy = Backups(tracker,MemoryRollback()).export().replace("\"formatVersion\": 16","\"formatVersion\": 15")
    assertEquals(tracker.data(),Backups.decode(legacy).data)
    assertEquals(tracker.data(),StreamingBackup.read { legacy.byteInputStream() }.data)
    tracker.deleteSession(tracker.snapshot().token,first)
    tracker.manual(tracker.snapshot().token,clock.wall-4000,clock.wall-3000,Classification(name = "Replacement interval"))
    val collision = tracker.data()
    assertEquals(ErrorCode.OVERLAP,assertFailsWith<CoreError> { tracker.restoreDeleted(tracker.snapshot().token,listOf(first)) }.code)
    assertEquals(collision,tracker.data()) // Refusal keeps the archived original and every live fact.
}

fun batchDeleteRefusals(create: () -> StateStore,reopen: (StateStore) -> StateStore) {
    val clock = FakeClock();var tracker = Tracker(create(),clock)
    tracker.manual(tracker.snapshot().token,clock.wall-2000,clock.wall-1000,Classification())
    val completed = tracker.data().sessions.single().id
    fun refused(ids: List<String>) {
        val before = tracker.data();val undoAvailable = tracker.canUndo(tracker.snapshot().token)
        assertFailsWith<CoreError> { tracker.previewDeleteSessions(ids) }
        assertFailsWith<CoreError> { tracker.deleteSessions(tracker.snapshot().token,ids) }
        assertEquals(before,tracker.data());assertEquals(undoAvailable,tracker.canUndo(tracker.snapshot().token))
    }
    refused(emptyList());refused(listOf(completed,"missing"))
    tracker.start(tracker.snapshot().token);val active = tracker.snapshot().session!!.id
    refused(listOf(completed,active))
    tracker.pause(tracker.snapshot().token);refused(listOf(completed,active))
    tracker.stop(tracker.snapshot().token)
    tracker.startFocus(tracker.snapshot().token,selection = Classification())
    refused(listOf(completed,tracker.snapshot().session!!.id))
    tracker.stop(tracker.snapshot().token)
    val preview = tracker.previewDeleteSessions(listOf(completed,active))
    tracker.saveCard(tracker.snapshot().token,Card("batch-concurrent","Concurrent"))
    val beforeStale = tracker.data()
    assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> { tracker.applyDeleteSessions(preview) }.code)
    assertEquals(beforeStale,tracker.data())
    val rule = ScheduleRule(Classification(),"2026-10-10",extraDates = setOf("2026-10-10"))
    tracker.scheduleCommand(ScheduleCommand("batch-plan",tracker.snapshot().token,ScheduleAction.CREATE,"batch-series",rule = rule))
    tracker.scheduleCommand(ScheduleCommand("batch-start",tracker.snapshot().token,ScheduleAction.START,"batch-series",1,
        occurrence = OccurrenceKey("batch-series","2026-10-10")))
    val linked = tracker.snapshot().session!!.id;clock.advance(1000);tracker.stop(tracker.snapshot().token)
    val beforeLinked = tracker.data()
    assertEquals(ErrorReason.SCHEDULE_LINKED_SESSION,assertFailsWith<CoreError> {
        tracker.deleteSessions(tracker.snapshot().token,listOf(completed,linked))
    }.reason)
    assertEquals(beforeLinked,tracker.data())
    tracker = Tracker(reopen(tracker.store),clock);assertEquals(beforeLinked,tracker.data())
}
