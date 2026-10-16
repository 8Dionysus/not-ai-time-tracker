package dev.notai.tracker

import kotlin.test.*

fun deletePreviewHasExactSubjectConsequences(create: () -> StateStore,reopen: (StateStore) -> StateStore) {
    val clock = FakeClock();var t = Tracker(create(),clock)
    val card = Card("delete-review-card","Item");t.saveCard(t.snapshot().token,card)
    t.defineSubject(t.snapshot().token,CardSubject(card.id,"item"))
    t.saveSubjectEvent(t.snapshot().token,SubjectEvent("delete-review-event","stage",card.id))
    for(unit in listOf(UnitDefinition("delete-review-kg","kg","mass"),UnitDefinition("delete-review-page","page","page"),UnitDefinition("delete-review-m","m","distance"))) t.saveUnit(t.snapshot().token,unit)
    t.saveMetric(t.snapshot().token,Metric("delete-review-pages","Pages",numberKind = NumberKind.INTEGER,canonicalUnitId = "delete-review-page"))
    t.saveMetric(t.snapshot().token,Metric("delete-review-distance","Distance",numberKind = NumberKind.DECIMAL,canonicalUnitId = "delete-review-m"))
    t.manual(t.snapshot().token,clock.wall-1000,clock.wall,Classification())
    val session = t.data().sessions.single();val segment = t.data().segments.single();val ref = SubjectRef(SubjectKind.CARD,card.id)
    t.linkSubject(t.snapshot().token,SubjectSessionLink("delete-review-link",ref,session.id))
    val set = ExerciseSet("delete-review-set",session.id,0,8,Decimal("40"),"delete-review-kg",LoadMeaning.TOTAL_EXTERNAL,recordedAt = clock.wall,source = "manual")
    t.recordSet(t.snapshot().token,set);t.recordSet(t.snapshot().token,set.copy(id = "delete-review-set-correction",load = Decimal("42"),replacesId = set.id))
    val q = EventQuantity("delete-review-q",ref,QuantityDefinition("delete-review-pages",1,"delete-review-page"),Decimal("10"),clock.wall-500,clock.wall,"manual",session.id)
    t.recordEventQuantity(t.snapshot().token,q);t.recordEventQuantity(t.snapshot().token,q.copy(id = "delete-review-q-correction",value = Decimal("12"),replacesId = q.id))
    val m = Measurement("delete-review-measurement","delete-review-distance",1,"delete-review-m",Scope.SESSION,session.id,Decimal("10"))
    t.measure(t.snapshot().token,m)
    t.pairDistanceTime(t.snapshot().token,DistanceTimeInput("delete-review-rate",m.id,m.scope,m.targetId,segment.start,segment.end!!,segment.durationMs,TimeBasis.RECORDED,"recorded"))
    val before = t.data();val preview = t.previewDeleteSession(session.id)
    assertEquals(before,t.data()) // Cancelling/dropping this preview has no Dataset write.
    assertEquals(1,preview.segmentCount);assertEquals(1,preview.measurementCount);assertEquals(1,preview.subjectLinkCount)
    assertEquals(2,preview.exerciseSetCount);assertEquals(2,preview.eventQuantityCount);assertEquals(1,preview.distanceTimeCount)
    assertEquals(2,preview.subjectCorrectionCount);assertTrue(preview.hasDependentFacts)
    assertTrue(preview.after.subjects.links.isEmpty());assertTrue(preview.after.subjects.sets.isEmpty());assertTrue(preview.after.subjects.quantities.isEmpty());assertTrue(preview.after.subjects.distanceTimes.isEmpty())
    assertEquals(before.subjects.cards,preview.after.subjects.cards);assertEquals(before.subjects.events,preview.after.subjects.events)
    val applied = t.applyDeleteSession(preview);assertTrue(applied.undoAvailable)
    t.undo(applied.state.token)
    assertEquals(before.sessions,t.data().sessions);assertEquals(before.segments,t.data().segments)
    assertEquals(before.subjects,t.data().subjects);assertEquals(before.measurements,t.data().measurements)
    val saved = t.data();t = Tracker(reopen(t.store),clock);assertEquals(saved,t.data())
    val rule = ScheduleRule(Classification(),"2026-10-10",extraDates = setOf("2026-10-10"))
    t.scheduleCommand(ScheduleCommand("delete-create",t.snapshot().token,ScheduleAction.CREATE,"delete-series",rule = rule))
    t.scheduleCommand(ScheduleCommand("delete-start",t.snapshot().token,ScheduleAction.START,"delete-series",1,occurrence = OccurrenceKey("delete-series","2026-10-10")))
    val linked = t.snapshot().session!!.id;clock.advance(1000);t.stop(t.snapshot().token)
    val linkedBefore = t.data();val rejected = assertFailsWith<CoreError> { t.previewDeleteSession(linked) }
    assertEquals(ErrorReason.SCHEDULE_LINKED_SESSION,rejected.reason);assertEquals(linkedBefore,t.data())
}
