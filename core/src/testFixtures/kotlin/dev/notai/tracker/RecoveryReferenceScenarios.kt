package dev.notai.tracker

import kotlin.test.*

enum class RecoveryReferenceCase { LINK, SET, EVENT_QUANTITY, FOCUS, SCHEDULE, CLOSED_RATE, SESSION_MEASUREMENT, SEGMENT_MEASUREMENT }

/** All writes use ordinary commands on isolated stores; the same case runs in Memory and Room. */
fun recoveryExclusionKeepsReferences(create: () -> StateStore,reopen: (StateStore) -> StateStore,kind: RecoveryReferenceCase) {
    val clock = FakeClock();var t = Tracker(create(),clock)
    val card = Card("recovery-card","Original identity")
    t.saveCard(t.snapshot().token,card);t.defineSubject(t.snapshot().token,CardSubject(card.id,"item"))
    t.saveUnit(t.snapshot().token,UnitDefinition("recovery-kg","kg","mass"))
    t.saveUnit(t.snapshot().token,UnitDefinition("recovery-page","page","page"))
    t.saveMetric(t.snapshot().token,Metric("recovery-pages","pages",numberKind = NumberKind.INTEGER,canonicalUnitId = "recovery-page"))
    t.saveUnit(t.snapshot().token,UnitDefinition("recovery-m","m","distance"))
    t.saveMetric(t.snapshot().token,Metric("recovery-distance","distance",numberKind = NumberKind.DECIMAL,canonicalUnitId = "recovery-m"))
    val startCommand = if(kind == RecoveryReferenceCase.SCHEDULE) {
        val rule = ScheduleRule(card.classification(),"2026-10-10",extraDates = setOf("2026-10-10"))
        t.scheduleCommand(ScheduleCommand("create",t.snapshot().token,ScheduleAction.CREATE,"recovery-series",rule = rule))
        ScheduleCommand("start",t.snapshot().token,ScheduleAction.START,"recovery-series",1,occurrence = OccurrenceKey("recovery-series","2026-10-10"))
    } else null
    when(kind) {
        RecoveryReferenceCase.FOCUS -> t.startFocus(t.snapshot().token,card.classification())
        RecoveryReferenceCase.SCHEDULE -> t.scheduleCommand(startCommand!!)
        else -> t.start(t.snapshot().token,card.classification(),"Literal original note")
    }
    val session = t.snapshot().session!!
    val ref = if(kind == RecoveryReferenceCase.EVENT_QUANTITY) {
        t.saveSubjectEvent(t.snapshot().token,SubjectEvent("recovery-event","stage",card.id))
        SubjectRef(SubjectKind.EVENT,"recovery-event")
    } else SubjectRef(SubjectKind.CARD,card.id)
    when(kind) {
        RecoveryReferenceCase.LINK,RecoveryReferenceCase.FOCUS ->
            t.linkSubject(t.snapshot().token,SubjectSessionLink("recovery-link",ref,session.id))
        RecoveryReferenceCase.SET -> t.recordSet(t.snapshot().token,ExerciseSet("recovery-set",session.id,0,8,
            Decimal("40"),"recovery-kg",LoadMeaning.TOTAL_EXTERNAL,recordedAt = clock.wall,source = "explicit set"))
        RecoveryReferenceCase.EVENT_QUANTITY -> {
            t.linkSubject(t.snapshot().token,SubjectSessionLink("recovery-link",ref,session.id))
            t.recordEventQuantity(t.snapshot().token,EventQuantity("recovery-quantity",ref,QuantityDefinition("recovery-pages",1,"recovery-page"),
                Decimal("12"),clock.wall,clock.wall,"explicit quantity",session.id))
        }
        RecoveryReferenceCase.CLOSED_RATE -> {
            clock.advance(1000);t.pause(t.snapshot().token)
            val segment = t.data().segments.single()
            val m = Measurement("recovery-distance-measurement","recovery-distance",1,"recovery-m",Scope.SESSION,session.id,Decimal("10"))
            t.measure(t.snapshot().token,m)
            t.pairDistanceTime(t.snapshot().token,DistanceTimeInput("recovery-rate",m.id,m.scope,m.targetId,
                segment.start,segment.end!!,segment.durationMs,TimeBasis.RECORDED,"explicit pairing"))
            t.resume(t.snapshot().token)
        }
        RecoveryReferenceCase.SESSION_MEASUREMENT,RecoveryReferenceCase.SEGMENT_MEASUREMENT -> {
            val scope = if(kind == RecoveryReferenceCase.SESSION_MEASUREMENT) Scope.SESSION else Scope.SEGMENT
            val target = if(scope == Scope.SESSION) session.id else t.data().segments.single().id
            t.measure(t.snapshot().token,Measurement("recovery-measurement","recovery-distance",1,"recovery-m",scope,target,Decimal("10")))
        }
        RecoveryReferenceCase.SCHEDULE -> Unit
    }
    val before = t.data();val tail = before.segments.single { it.end == null }
    clock.advance(5000);clock.boot = "lost-anchor";clock.elapsed = 5
    assertEquals(Mode.RECOVERY,t.snapshot().mode)
    if(kind == RecoveryReferenceCase.FOCUS) assertEquals(FocusMode.RECOVERY,t.snapshot().focus!!.mode)
    assertFailsWith<CoreError> { t.stop(t.snapshot().token) }
    val resolved = if(kind == RecoveryReferenceCase.FOCUS) t.resolveFocus(t.snapshot().token,null,null)
        else t.resolveTail(t.snapshot().token,null,null)
    val after = t.data();validate(after)
    assertEquals(Mode.IDLE,t.snapshot().mode);assertNull(after.focus)
    assertEquals(session.copy(status = SessionStatus.STOPPED),after.sessions.single())
    assertEquals(before.subjects,after.subjects);assertEquals(before.measurements,after.measurements);assertEquals(before.schedule,after.schedule)
    val expectedTime = if(kind == RecoveryReferenceCase.CLOSED_RATE) 1000L else 0L
    if(expectedTime == 0L) {
        assertEquals(tail.copy(end = tail.start,durationMs = 0,anchor = null,observedUntil = null,uncertain = false,limitMs = null),after.segments.single())
        assertNull(after.segments.single().manualConfirmation)
    } else assertEquals(before.segments.filter { it.end != null },after.segments)
    val report = t.view().report(0,clock.wall+1)
    assertEquals(expectedTime,report.recordedMs);assertTrue(report.unresolved.isEmpty())
    if(expectedTime == 0L) { assertTrue(report.rows.isEmpty());assertTrue(report.recordedDays.isEmpty()) }
    if(kind == RecoveryReferenceCase.EVENT_QUANTITY) assertEquals(listOf("recovery-quantity"),t.view().quantityReport(0,clock.wall+1).quantities.events.map { it.id })
    if(kind == RecoveryReferenceCase.CLOSED_RATE) assertNull(t.subjects().rates(setOf("recovery-rate"),0,clock.wall+1).reason)
    assertTrue(resolved.undoAvailable);t.undo(t.snapshot().token)
    assertEquals(before.sessions,t.data().sessions);assertEquals(before.segments,t.data().segments)
    assertEquals(before.subjects,t.data().subjects);assertEquals(Mode.RECOVERY,t.snapshot().mode)
    if(kind == RecoveryReferenceCase.FOCUS) t.resolveFocus(t.snapshot().token,null,null) else t.resolveTail(t.snapshot().token,null,null)
    val persisted = t.data();t = Tracker(reopen(t.store),clock);assertEquals(persisted,t.data())
    val archive = Backups(t,MemoryRollback()).export();assertEquals(persisted,Backups.decode(archive).data)
    val clean = Tracker(create(),clock);val restore = Backups(clean,MemoryRollback());restore.apply(restore.prepare(archive))
    assertEquals(persisted.subjects,clean.data().subjects);assertEquals(persisted.segments,clean.data().segments)
    assertEquals(persisted.schedule,clean.data().schedule);assertEquals(expectedTime,clean.view().report(0,clock.wall+1).recordedMs)
    if(startCommand != null) {
        val replayBefore = t.data();assertEquals(session.id,t.scheduleCommand(startCommand).sessionId);assertEquals(replayBefore,t.data())
    }
    // Recovery released the current timer; a new explicit session contributes only its actual time.
    t.start(t.snapshot().token);clock.advance(1000);t.stop(t.snapshot().token)
    assertEquals(expectedTime+1000,t.view().report(0,clock.wall+1).recordedMs)
}

fun recoveryExclusionDropsUnreferencedEmptySession(create: () -> StateStore) {
    val clock = FakeClock();val t = Tracker(create(),clock);t.start(t.snapshot().token)
    clock.advance(5000);clock.boot = "lost-anchor";clock.elapsed = 5
    t.resolveTail(t.snapshot().token,null,null)
    assertEquals(Mode.IDLE,t.snapshot().mode);assertTrue(t.data().sessions.isEmpty());assertTrue(t.data().segments.isEmpty())
}

fun recoveryEmptyIdentityHasNoIntervalOverlap(create: () -> StateStore,reopen: (StateStore) -> StateStore) {
    val clock = FakeClock();val source = Tracker(create(),clock)
    val card = Card("overlap-card","Identity only");source.saveCard(source.snapshot().token,card)
    source.defineSubject(source.snapshot().token,CardSubject(card.id,"item"));source.start(source.snapshot().token)
    val sessionId = source.snapshot().session!!.id;val start = source.data().segments.single().start
    source.linkSubject(source.snapshot().token,SubjectSessionLink(subject = SubjectRef(SubjectKind.CARD,card.id),sessionId = sessionId))
    clock.advance(5000);clock.boot = "lost-anchor";clock.elapsed = 5
    // A portable unresolved snapshot has no live anchor; another manual fact may cover its start.
    val t = Tracker(create(),clock);val restore = Backups(t,MemoryRollback())
    restore.apply(restore.prepare(Backups(source,MemoryRollback()).export()))
    t.manual(t.snapshot().token,start-100,start+100,Classification(),"Separate explicitly measured interval")
    t.resolveTail(t.snapshot().token,null,null)
    validate(t.data());assertEquals(Mode.IDLE,t.snapshot().mode)
    assertEquals(2,t.data().sessions.size)
    val empty = t.data().segments.single { it.sessionId == sessionId }
    assertEquals(start,empty.end);assertEquals(0L,empty.durationMs);assertNull(empty.manualConfirmation)
    val report = t.view().report(0,clock.wall+1)
    assertEquals(200L,report.recordedMs);assertEquals(1,report.rows.size)
    assertTrue(report.rows.none { it.sessionId == sessionId })
    assertEquals(t.data(),Tracker(reopen(t.store),clock).data())
}

fun recoveryEmptyIdentityAllowsClockRollback(create: () -> StateStore,reopen: (StateStore) -> StateStore,focus: Boolean) {
    for(openAgain in listOf(false,true)) {
        val clock = FakeClock();var t = Tracker(create(),clock)
        val card = Card("rollback-identity","Original item");t.saveCard(t.snapshot().token,card)
        t.defineSubject(t.snapshot().token,CardSubject(card.id,"item"));t.start(t.snapshot().token)
        val original = t.snapshot().session!!.id
        t.linkSubject(t.snapshot().token,SubjectSessionLink(subject = SubjectRef(SubjectKind.CARD,card.id),sessionId = original))
        clock.boot = "new-boot";clock.wall -= 60_000;clock.elapsed = 5
        t.resolveTail(t.snapshot().token,null,null)
        if(openAgain) t = Tracker(reopen(t.store),clock)
        val facts = t.data().subjects
        // The old wall start is identity metadata, not future measured time. Both archive
        // readers and restore must preserve it, while positive future intervals still fail.
        val saved = t.data();val archive = Backups(t,MemoryRollback()).export()
        assertEquals(saved,Backups.decode(archive).data)
        assertEquals(saved,StreamingBackup.read { archive.byteInputStream() }.data)
        val restored = Tracker(create(),clock);val backups = Backups(restored,MemoryRollback())
        backups.apply(backups.prepare(archive));assertEquals(saved.segments,restored.data().segments);assertEquals(facts,restored.data().subjects)
        val positive = saved.copy(segments = saved.segments.map { it.copy(end = it.start+1000,durationMs = 1000) })
        assertFailsWith<CoreError> { Backups.encode(positive,clock.now()) }
        val pack = SelectiveTransfer(t).export(TransferSelection(sessionIds = setOf(original)))
        SelectiveTransfer(t).inspect(pack)
        fun fullValidationAllowsStart() {
            val d = t.data();val session = Session(id = "candidate",status = SessionStatus.RUNNING,selection = Classification())
            validate(d.copy(sessions = d.sessions+session,segments = d.segments+Segment(sessionId = session.id,start = clock.wall,anchor = Anchor(clock.boot,clock.elapsed))))
        }
        fullValidationAllowsStart()
        if(focus) t.startFocus(t.snapshot().token,plan = FocusPlan(1000,1000)) else t.start(t.snapshot().token)
        // An immediate stop is also empty. The incrementally maintained index must ignore it.
        t.stop(t.snapshot().token);clock.wall -= 30_000
        fullValidationAllowsStart();t.start(t.snapshot().token);t.stop(t.snapshot().token)
        // Empty identities must not become a proposed "previous recorded end" either.
        clock.wall += 120_000;t.start(t.snapshot().token)
        assertFailsWith<CoreError> { t.previewStartAtPreviousEnd(t.snapshot().token,t.snapshot().session!!.id) }
        clock.advance(1000);t.stop(t.snapshot().token)
        assertEquals(facts,t.data().subjects);validate(t.data())
        assertEquals(1000L,t.view().report(0,clock.wall+1).recordedMs)
        assertEquals(1,t.data().subjects.links.size);assertTrue(t.data().sessions.any { it.id == original })
    }
}
