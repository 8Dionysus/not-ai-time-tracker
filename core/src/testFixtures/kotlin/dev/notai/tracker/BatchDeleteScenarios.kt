package dev.notai.tracker

import kotlin.test.*

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
    assertEquals(15,backup.formatVersion);assertEquals(changed.data,backup.data)
    tracker.undo(changed.state.token)
    val undone = tracker.data()
    assertEquals(before.sessions,undone.sessions);assertEquals(before.segments,undone.segments)
    assertEquals(before.measurements,undone.measurements);assertEquals(before.subjects,undone.subjects)
    // Undo restores facts, but keeps the existing protection against resurrecting consumed picker nonces.
    assertTrue(undone.pendingAssignments.isEmpty())
    tracker = Tracker(reopen(tracker.store),clock);assertEquals(undone,tracker.data())
    val applied = tracker.applyDeleteSessions(tracker.previewDeleteSessions(listOf(first,second)))
    tracker = Tracker(reopen(tracker.store),clock);assertEquals(applied.data,tracker.data())
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
