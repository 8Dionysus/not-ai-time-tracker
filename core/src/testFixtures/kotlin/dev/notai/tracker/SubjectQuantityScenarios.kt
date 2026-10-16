package dev.notai.tracker

import kotlin.test.*
import java.math.BigDecimal
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*

private class SubjectFixture(create: ()->StateStore,val reopen: (StateStore)->StateStore) {
    val clock = FakeClock();var t = Tracker(create(),clock)
    val card = Card("subject-book","Same title")
    val ref = SubjectRef(SubjectKind.CARD,card.id)
    val page = QuantityDefinition("subject-pages",1,"subject-page")
    init {
        t.saveCard(t.snapshot().token,card)
        t.defineSubject(t.snapshot().token,CardSubject(card.id,"edition"))
        t.saveUnit(t.snapshot().token,UnitDefinition(page.unitId,"page","page"))
        t.saveMetric(t.snapshot().token,Metric(page.metricId,"pages",numberKind = NumberKind.INTEGER,canonicalUnitId = page.unitId))
        t.saveUnit(t.snapshot().token,UnitDefinition("subject-kg","kg","mass"))
        t.saveUnit(t.snapshot().token,UnitDefinition("subject-km","km","distance",Decimal("1000")))
        t.saveMetric(t.snapshot().token,Metric("subject-distance","distance",numberKind = NumberKind.DECIMAL,canonicalUnitId = "subject-km",maxScale = 3,aggregation = Aggregation.SUM))
    }
    fun session(start: Long,end: Long,selection: Classification = card.classification()): String {
        t.manual(t.snapshot().token,start,end,selection);return t.data().sessions.last().id
    }
    fun observe(id: String,value: String,time: Long?,replaces: String? = null): StateObservation =
        StateObservation(id,ref,page,Decimal(value),time,clock.wall,"manual",replaces).also { t.observeSubject(t.snapshot().token,it) }
    fun quantity(id: String,value: String,time: Long?,session: String? = null) =
        EventQuantity(id,ref,page,Decimal(value),time,clock.wall,"manual",session).also { t.recordEventQuantity(t.snapshot().token,it) }
    fun reopen() { val d = t.data();t = Tracker(reopen(t.store),clock);assertEquals(d,t.data()) }
}
fun subjectBooks(create: ()->StateStore,reopen: (StateStore)->StateStore) {
    val f = SubjectFixture(create,reopen);val s1 = f.session(0,1_200_000);val s2 = f.session(1_200_000,3_000_000)
    val count = f.t.subjects().count(setOf(s1,s2));assertEquals(setOf(f.card.id),count.cardIds);assertEquals(2,count.sessionIds.size)
    assertEquals(3_000_000L,f.t.view().report(0,3_000_001).recordedMs)
    f.t.saveCard(f.t.snapshot().token,f.card.copy(name = "Renamed"));assertEquals(count,f.t.subjects().count(setOf(s1,s2)))
    val b2 = f.card.copy(id = "subject-b2");f.t.saveCard(f.t.snapshot().token,b2);f.t.defineSubject(f.t.snapshot().token,CardSubject(b2.id,"edition"))
    val s3 = f.session(3_000_000,3_100_000,b2.classification());assertEquals(2,f.t.subjects().count(setOf(s1,s2,s3)).cardIds.size)
    assertTrue(f.t.subjects().window(0,4_000_000).events.isEmpty())
    f.t.saveCard(f.t.snapshot().token,f.card.copy(archived = true));assertEquals(1,f.t.subjects().count(setOf(s1,s2)).cardIds.size)
    assertFailsWith<CoreError> { f.t.linkSubject(f.t.snapshot().token,SubjectSessionLink(subject = f.ref,sessionId = s1)) }
    f.reopen()
    // Plain tracking has no subject dependency at all.
    val plain = Tracker(create(),f.clock);plain.start(plain.snapshot().token);f.clock.advance(1000);plain.stop(plain.snapshot().token)
    assertTrue(plain.data().subjects.empty)
}
fun subjectState(create: ()->StateStore,reopen: (StateStore)->StateStore) {
    val f = SubjectFixture(create,reopen);f.observe("p100","100",100);f.observe("p120","120",200)
    assertEquals(Decimal("100"),f.t.subjects().stateAt(f.ref,f.page,199).value)
    assertEquals(Decimal("120"),f.t.subjects().stateAt(f.ref,f.page,200).value)
    assertNull(f.t.subjects().stateAt(f.ref,f.page,99).value)
    f.observe("p125","125",200);assertEquals(QuantityUnavailable.CONFLICT,f.t.subjects().stateAt(f.ref,f.page,201).reason)
    val peerPack = SelectiveTransfer(f.t).export(TransferSelection(subjectRecords = setOf(SubjectRecordKey(SubjectRecordKind.OBSERVATION,"p120"))))
    assertEquals(setOf("p120","p125"),peerPack.groups.single().content.subjects.observations.map { it.id }.toSet())
    f.observe("correction","120",200,"p125");assertEquals(Decimal("120"),f.t.subjects().stateAt(f.ref,f.page,201).value)
    assertEquals(4,f.t.subjects().history(f.ref,f.page).size)
    val late = f.quantity("q15","15",100);f.quantity("q25","25",200)
    assertEquals(Decimal("40"),f.t.subjects().window(0,300).totals().getValue(f.ref to f.page))
    assertEquals(f.clock.wall,late.recordedAt);assertEquals(100L,late.effectiveAt)
    f.observe("unknown","130",null);assertEquals(Decimal("120"),f.t.subjects().stateAt(f.ref,f.page,300).value)
    f.reopen()
}
fun subjectEvents(create: ()->StateStore,reopen: (StateStore)->StateStore) {
    val f = SubjectFixture(create,reopen);val s1 = f.session(0,1000);val s2 = f.session(1000,2000)
    val e1 = SubjectEvent("wash1","wash",f.card.id);f.t.saveSubjectEvent(f.t.snapshot().token,e1)
    val ref = SubjectRef(SubjectKind.EVENT,e1.id)
    for(id in listOf(s1,s2)) f.t.linkSubject(f.t.snapshot().token,SubjectSessionLink(subject = ref,sessionId = id))
    assertEquals(1,f.t.subjects().count(setOf(s1,s2)).eventIds.size)
    val pack = SelectiveTransfer(f.t).export(TransferSelection(sessionIds = setOf(s1)))
    assertEquals(listOf(s1),pack.groups.flatMap { it.content.sessions }.map { it.id })
    assertEquals(listOf(e1),pack.groups.flatMap { it.content.subjects.events })
    assertEquals(1,pack.groups.flatMap { it.content.subjects.links }.size)
    val e2 = e1.copy(id = "wash2");f.t.saveSubjectEvent(f.t.snapshot().token,e2)
    f.t.linkSubject(f.t.snapshot().token,SubjectSessionLink(subject = ref.copy(id = e2.id),sessionId = s2))
    assertEquals(2,f.t.subjects().count(setOf(s1,s2)).eventIds.size)
    val segment = f.t.data().segments.single { it.sessionId == s1 }
    f.t.correctSession(f.t.snapshot().token,s1,listOf(Tracker.SegmentEdit(segment.id,0,1000,segment.selection)),null)
    assertEquals(2,f.t.data().subjects.events.size);f.reopen()
}
fun subjectChanges(create: ()->StateStore,reopen: (StateStore)->StateStore) {
    val f = SubjectFixture(create,reopen);f.observe("d0","2300",100);f.observe("d1","2420",200);f.observe("d2","2380",300)
    val plus = SubjectChange("plus","d0","d1",SignedDecimal("120"),f.clock.wall,"manual")
    val minus = SubjectChange("minus","d1","d2",SignedDecimal("-40"),f.clock.wall,"manual")
    f.t.recordSubjectChange(f.t.snapshot().token,plus);f.t.recordSubjectChange(f.t.snapshot().token,minus)
    assertEquals(SignedDecimal("80"),f.t.subjects().changeTotal(setOf("plus","minus")).value)
    assertFailsWith<CoreError> { Decimal("-5") }
    val before = f.t.data();assertFailsWith<CoreError> { f.t.recordSubjectChange(f.t.snapshot().token,plus.copy(id = "invalid",value = SignedDecimal("119"))) };assertEquals(before,f.t.data())
    f.observe("d3","2400",500);f.observe("d4","2410",600)
    f.t.recordSubjectChange(f.t.snapshot().token,SubjectChange("gap","d3","d4",SignedDecimal("10"),f.clock.wall,"manual"))
    assertEquals(QuantityUnavailable.DISCONTINUOUS_BASIS,f.t.subjects().changeTotal(setOf("plus","gap")).reason)
    f.observe("correct-d2","2370",300,"d2")
    f.t.recordSubjectChange(f.t.snapshot().token,minus.copy(id = "correct-minus",toObservationId = "correct-d2",value = SignedDecimal("-50"),replacesId = "minus"))
    assertEquals(SignedDecimal("70"),f.t.subjects().changeTotal(setOf("plus","correct-minus")).value)
    assertEquals(QuantityUnavailable.SUPERSEDED_BASIS,f.t.subjects().changeTotal(setOf("plus","minus")).reason)
    assertTrue(Backups(f.t,MemoryRollback()).export().contains("-40"));f.reopen()
}
fun subjectSets(create: ()->StateStore,reopen: (StateStore)->StateStore) {
    val f = SubjectFixture(create,reopen);val session = f.session(0,1000)
    val a = ExerciseSet("set1",session,0,8,Decimal("40"),"subject-kg",LoadMeaning.TOTAL_EXTERNAL,recordedAt = f.clock.wall,source = "manual")
    val b = a.copy(id = "set2",ordinal = 1,repetitions = 6,load = Decimal("45"))
    f.t.recordSet(f.t.snapshot().token,a);f.t.recordSet(f.t.snapshot().token,b)
    val total = f.t.subjects().setTotal(setOf(a.id,b.id));assertEquals(14L,total.repetitions);assertEquals(Decimal("590"),total.volume)
    f.t.recordSet(f.t.snapshot().token,a.copy(id = "set1-new",loadMeaning = LoadMeaning.PER_IMPLEMENT,replacesId = a.id))
    assertEquals(QuantityUnavailable.INCOMPATIBLE_INPUTS,f.t.subjects().setTotal(setOf("set1-new",b.id)).reason)
    f.t.recordSet(f.t.snapshot().token,a.copy(id = "normalized",loadMeaning = LoadMeaning.PER_IMPLEMENT,implementCount = 2,replacesId = "set1-new"))
    assertEquals(Decimal("910"),f.t.subjects().setTotal(setOf("normalized",b.id)).volume)
    val metric = Metric("subject-scale","rating",numberKind = NumberKind.DECIMAL,canonicalUnitId = f.page.unitId,maxScale = 1,bounds = ScaleBounds(Decimal("1"),Decimal("5"),"low","high"))
    f.t.saveMetric(f.t.snapshot().token,metric)
    val m = Measurement("scale",metric.id,1,f.page.unitId,Scope.SESSION,session,Decimal("5"))
    f.t.measure(f.t.snapshot().token,m)
    assertFailsWith<CoreError> { f.t.measure(f.t.snapshot().token,m.copy(value = Decimal("5.1"))) }
    assertFailsWith<CoreError> { f.t.saveMetric(f.t.snapshot().token,metric.copy(bounds = metric.bounds!!.copy(max = Decimal("6")))) }
    assertEquals(2,f.t.subjects().sets(setOf(session)).size)
    f.t.deleteSession(f.t.snapshot().token,session);assertTrue(f.t.data().subjects.sets.isEmpty());f.t.undo(f.t.snapshot().token)
    assertEquals(4,f.t.data().subjects.sets.size);f.reopen()
}
fun subjectWindow(create: ()->StateStore,reopen: (StateStore)->StateStore) {
    val f = SubjectFixture(create,reopen);val midnight = 86_400_000L
    val session = f.session(midnight-600_000,midnight+600_000)
    f.t.linkSubject(f.t.snapshot().token,SubjectSessionLink(subject = f.ref,sessionId = session))
    f.t.measure(f.t.snapshot().token,Measurement("whole",f.page.metricId,1,f.page.unitId,Scope.SESSION,session,Decimal("10")))
    f.quantity("timed","10",midnight+300_000,session)
    val first = f.t.subjects().window(0,midnight);val second = f.t.subjects().window(midnight,2*midnight)
    assertEquals(1,first.wholeTargets.size);assertEquals(1,second.wholeTargets.size)
    assertTrue(first.events.isEmpty());assertEquals(Decimal("10"),second.totals().getValue(f.ref to f.page))
    assertEquals(1,first.combine(second).wholeTargets.size)
    assertEquals(Decimal("10"),first.combine(second).totals().getValue(f.ref to f.page))
    f.t.recordEventQuantity(f.t.snapshot().token,f.t.data().subjects.quantities.single().copy(id = "correct10",effectiveAt = midnight+300_001,replacesId = "timed"))
    f.quantity("unknown","5",null,session);assertEquals(1,f.t.subjects().window(0,midnight).unknownTime.size)
    assertFailsWith<CoreError> { first.combine(f.t.subjects().window(midnight,2*midnight)) }
    assertEquals(1,f.t.subjects().window(midnight,2*midnight).events.size)
    f.reopen()
}
fun subjectRates(create: ()->StateStore,reopen: (StateStore)->StateStore) {
    val f = SubjectFixture(create,reopen);val s1 = f.session(0,720_000);val s2 = f.session(720_000,2_160_000)
    fun input(id: String,session: String,distance: String): DistanceTimeInput {
        val seg = f.t.data().segments.single { it.sessionId == session }
        val m = Measurement(id,"subject-distance",1,"subject-km",Scope.SESSION,session,Decimal(distance));f.t.measure(f.t.snapshot().token,m)
        return DistanceTimeInput("input-$id",id,Scope.SESSION,session,seg.start,seg.end!!,seg.durationMs,TimeBasis.RECORDED,"recorded").also { f.t.pairDistanceTime(f.t.snapshot().token,it) }
    }
    val a = input("distance1",s1,"2");val b = input("distance2",s2,"3")
    val rate = f.t.subjects().rates(setOf(a.id,b.id),0,2_160_000)
    assertNull(rate.reason);assertEquals(BigDecimal("432"),rate.paceMsPerBaseUnit!!.numerator.divide(rate.paceMsPerBaseUnit!!.denominator)) // 432 ms/m = 7m12s/km
    assertEquals(setOf("distance1","distance2"),rate.measurementIds.toSet())
    assertEquals(QuantityUnavailable.CLIPPED_WINDOW,f.t.subjects().rates(setOf(a.id),1,720_000).reason)
    assertEquals(QuantityUnavailable.INCOMPATIBLE_INPUTS,f.t.subjects().rates(setOf(a.id),0,720_000,TimeBasis.MOVING).reason)
    val movingZero = a.copy(id = "zero-time",durationMs = 0,timeBasis = TimeBasis.MOVING,source = "explicit moving time")
    f.t.pairDistanceTime(f.t.snapshot().token,movingZero)
    val zeroTime = f.t.subjects().rates(setOf(movingZero.id),0,720_000,TimeBasis.MOVING)
    assertNull(zeroTime.speedBaseUnitsPerMs);assertEquals(QuantityUnavailable.ZERO_TIME,zeroTime.reason)
    val seg = f.t.data().segments.single { it.sessionId == s1 }
    val m = Measurement("child","subject-distance",1,"subject-km",Scope.SEGMENT,seg.id,Decimal("2"));f.t.measure(f.t.snapshot().token,m)
    val child = a.copy(id = "child-input",measurementId = m.id,scope = Scope.SEGMENT,targetId = seg.id);f.t.pairDistanceTime(f.t.snapshot().token,child)
    assertEquals(QuantityUnavailable.OVERLAPPING_TARGETS,f.t.subjects().rates(setOf(a.id,child.id),0,720_000).reason)
    f.t.measure(f.t.snapshot().token,m.copy(value = Decimal.ZERO));val zero = f.t.subjects().rates(setOf(child.id),0,720_000)
    assertNull(zero.paceMsPerBaseUnit);assertEquals(QuantityUnavailable.ZERO_DISTANCE,zero.reason)
    f.t.correctSession(f.t.snapshot().token,s1,listOf(Tracker.SegmentEdit(seg.id,0,719_000,seg.selection)),null)
    assertEquals(QuantityUnavailable.TARGET_CHANGED,f.t.subjects().rates(setOf(a.id),0,720_000).reason)
    f.reopen()
}
fun subjectTransport(create: ()->StateStore,reopen: (StateStore)->StateStore) {
    val f = SubjectFixture(create,reopen);val session = f.session(0,1000)
    f.t.linkSubject(f.t.snapshot().token,SubjectSessionLink("subject-link",f.ref,session))
    f.observe("o1","100",100);f.observe("o2","120",200);f.observe("o2-fix","119",200,"o2")
    f.t.recordSubjectChange(f.t.snapshot().token,SubjectChange("delta-old","o1","o2",SignedDecimal("20"),f.clock.wall,"manual"))
    f.t.recordSubjectChange(f.t.snapshot().token,SubjectChange("delta","o1","o2-fix",SignedDecimal("19"),f.clock.wall,"manual",replacesId = "delta-old"))
    f.observe("o3","109",400)
    f.t.recordSubjectChange(f.t.snapshot().token,SubjectChange("minus","o2-fix","o3",SignedDecimal("-10"),f.clock.wall,"manual"))
    val originalQuantity = f.quantity("quantity","15",300,session)
    f.t.recordEventQuantity(f.t.snapshot().token,originalQuantity.copy(id = "quantity-fix",value = Decimal("14"),replacesId = originalQuantity.id))
    f.quantity("unknown","25",null,session)
    f.t.recordSet(f.t.snapshot().token,ExerciseSet("set",session,0,8,Decimal("40"),"subject-kg",LoadMeaning.TOTAL_EXTERNAL,recordedAt = f.clock.wall,source = "manual"))
    f.t.recordSet(f.t.snapshot().token,f.t.data().subjects.sets.single().copy(id = "set-fix",load = Decimal("42"),replacesId = "set"))
    f.t.measure(f.t.snapshot().token,Measurement("distance","subject-distance",1,"subject-km",Scope.SESSION,session,Decimal("2")))
    f.t.pairDistanceTime(f.t.snapshot().token,DistanceTimeInput("rate","distance",Scope.SESSION,session,0,1000,1000,TimeBasis.RECORDED,"recorded"))
    f.t.saveSubjectEvent(f.t.snapshot().token,SubjectEvent("event","batch",f.card.id))
    f.t.linkSubject(f.t.snapshot().token,SubjectSessionLink("event-link",SubjectRef(SubjectKind.EVENT,"event"),session))
    f.t.saveSubjectEvent(f.t.snapshot().token,f.t.data().subjects.events.single().copy(archived = true))
    f.t.saveMetric(f.t.snapshot().token,Metric("bounded","bounded",numberKind = NumberKind.INTEGER,canonicalUnitId = f.page.unitId,bounds = ScaleBounds(Decimal.ZERO,Decimal("5"),"none","all")))
    f.t.measure(f.t.snapshot().token,Measurement("bounded-value","bounded",1,f.page.unitId,Scope.SESSION,session,Decimal("3")))
    f.reopen()
    val json = Backups(f.t,MemoryRollback()).export();val decoded = Backups.decode(json)
    assertEquals(14,decoded.formatVersion);assertEquals(f.t.data().subjects,decoded.data.subjects)
    val bytes = ByteArrayOutputStream();Backups(f.t,MemoryRollback()).write(bytes)
    assertEquals(decoded.data,StreamingBackup.read { bytes.toByteArray().inputStream() }.data)
    val destination = Tracker(create(),f.clock);val full = Backups(destination,MemoryRollback());full.apply(full.prepare(json));assertEquals(f.t.data().subjects,destination.data().subjects)
    val downgraded = JsonObject(codec.parseToJsonElement(json).jsonObject+("formatVersion" to JsonPrimitive(13))).toString()
    assertFailsWith<CoreError> { Backups.decode(downgraded) };assertFailsWith<CoreError> { StreamingBackup.read { downgraded.byteInputStream() } }
    val automatic = SelectiveTransfer(f.t).export(TransferSelection(sessionIds = setOf(session)))
    assertEquals(setOf("distance"),automatic.groups.flatMap { it.content.measurements }.map { it.id }.toSet())
    assertTrue("MEASUREMENTS_EXCLUDED:1" in automatic.losses)
    assertFalse("MEASUREMENTS_EXCLUDED:2" in automatic.losses)
    val roots = f.t.data().subjects.recordKeys()
    val pack = SelectiveTransfer(f.t).export(TransferSelection(sessionIds = setOf(session),measurementIds = f.t.data().measurements.map { it.id }.toSet(),subjectRecords = roots))
    assertEquals(4,pack.formatVersion);assertEquals(1,pack.groups.size)
    val content = pack.groups.single().content;assertEquals(f.t.data().subjects,content.subjects)
    assertEquals(Decimal("5"),content.metrics.single { it.id == "bounded" }.bounds!!.max)
    assertTrue(content.metrics.any { it.id == f.page.metricId });assertTrue(content.units.any { it.id == "subject-kg" })
    val target = Tracker(create(),f.clock);val transfer = SelectiveTransfer(target);val plan = transfer.plan(pack,pack.groups.map { it.id }.toSet())
    val applied = transfer.apply(plan,"subject-import");assertTrue(applied.groups.all { it.state == TransferGroupState.APPLIED })
    val imported = target.data();assertEquals(applied,transfer.apply(plan,"subject-import"));assertEquals(imported,target.data())
    assertEquals(f.t.data().subjects,target.data().subjects)
    transfer.undo("subject-import",target.snapshot().token)
    assertTrue(target.data().subjects.empty)
    target.saveCard(target.snapshot().token,f.card.copy(name = "Local edit"))
    val copyPlan = transfer.plan(pack,pack.groups.map { it.id }.toSet(),pack.groups.associate { it.id to TransferConflictPolicy.COPY })
    val copy = transfer.apply(copyPlan,"subject-copy");assertTrue(copy.groups.all { it.state == TransferGroupState.APPLIED })
    assertEquals(1,target.data().subjects.events.size);assertEquals(2,target.data().subjects.sets.size)
    val copied = copy.groups.single().inserted!!.subjects
    assertTrue(copied.observations.none { it.id in f.t.data().subjects.observations.map { it.id } })
    assertTrue(copied.changes.all { c -> copied.observations.any { it.id == c.fromObservationId } && copied.observations.any { it.id == c.toObservationId } })
    assertTrue(copied.links.all { l -> target.data().sessions.any { it.id == l.sessionId } })
    val afterCopy = target.data();val reopened = Tracker(reopen(target.store),f.clock);assertEquals(afterCopy,reopened.data())
    val reexport = SelectiveTransfer(reopened).export(TransferSelection(sessionIds = copied.sets.map { it.sessionId }.toSet(),measurementIds = copy.groups.single().inserted!!.measurements.map { it.id }.toSet(),subjectRecords = copied.recordKeys()))
    assertEquals(pack.groups.single().hash,reexport.groups.single().hash)
    val compacted = Tracker(create(),f.clock)
    val compactBackup = Backups(compacted,MemoryRollback())
    compactBackup.apply(compactBackup.prepare(Backups(reopened,MemoryRollback()).export()))
    val retention = compacted.previewTransferRetention(TransferRetentionSelection(operationIds = setOf("subject-copy"),originIds = setOf(copy.groups.single().group.originId)))
    compacted.applyTransferRetention(retention,"subject-retention",retention.requiredAcknowledgements)
    val compactReexport = SelectiveTransfer(compacted).export(TransferSelection(sessionIds = copied.sets.map { it.sessionId }.toSet(),measurementIds = copy.groups.single().inserted!!.measurements.map { it.id }.toSet(),subjectRecords = copied.recordKeys()))
    assertEquals(pack.groups.single().hash,compactReexport.groups.single().hash)
    SelectiveTransfer(reopened).undo("subject-copy",reopened.snapshot().token)
    assertTrue(reopened.data().subjects.empty)
    assertEquals("Local edit",reopened.data().cards.single { it.id == f.card.id }.name)
}
