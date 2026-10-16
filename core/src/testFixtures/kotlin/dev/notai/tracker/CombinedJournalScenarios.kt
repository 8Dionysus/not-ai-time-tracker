package dev.notai.tracker

import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import kotlin.test.*

fun quantityReportClassificationJoin(create: ()->StateStore,reopen: (StateStore)->StateStore) {
    fun time(s: String) = Instant.parse("2026-03-29T${s}:00Z").toEpochMilli()
    val clock = FakeClock(time("12:00"));var t = Tracker(create(),clock)
    val card = Card("joined-book","Book");val ref = SubjectRef(SubjectKind.CARD,card.id)
    val def = QuantityDefinition("joined-pages",1,"joined-page")
    t.saveCard(t.snapshot().token,card);t.defineSubject(t.snapshot().token,CardSubject(card.id,"edition"))
    t.saveUnit(t.snapshot().token,UnitDefinition(def.unitId,"page","page"))
    t.saveMetric(t.snapshot().token,Metric(def.metricId,"pages",numberKind=NumberKind.INTEGER,canonicalUnitId=def.unitId))
    t.settings(t.snapshot().token,t.data().settings.copy(reportZone="Europe/Berlin",reportDayStartMinute=240))
    val active = card.classification().copy(details=mapOf("method" to "active"))
    val passive = active.copy(details=mapOf("method" to "passive"))
    t.manual(t.snapshot().token,time("01:40"),time("02:20"),passive)
    val session = t.data().sessions.single().id;val segment = t.data().segments.single().id
    t.correctSession(t.snapshot().token,session,listOf(
        Tracker.SegmentEdit(segment,time("01:40"),time("02:05"),active),
        Tracker.SegmentEdit(null,time("02:05"),time("02:20"),passive)),null,true)
    t.linkSubject(t.snapshot().token,SubjectSessionLink(subject=ref,sessionId=session))
    fun quantity(id: String,value: String,at: Long?,sid: String?=session) = t.recordEventQuantity(t.snapshot().token,
        EventQuantity(id,ref,def,Decimal(value),at,clock.wall,"manual",sid))
    quantity("active-event","2",time("02:00"));quantity("passive-event","9",time("02:05"))
    quantity("unclassified-event","4",time("02:00"),null);quantity("unknown-time","20",null)
    t.measure(t.snapshot().token,Measurement("whole-pages",def.metricId,1,def.unitId,Scope.SESSION,session,50))
    val filter = Filter(details=mapOf("method" to "active"))
    val policy = ReportingDayPolicy("Europe/Berlin",240);val (a,b) = policy.bounds(LocalDate.parse("2026-03-29"),1)
    assertEquals(time("02:00"),a)
    val result = t.view().quantityReport(a,b,filter)
    assertEquals(listOf(time("02:00") to time("02:05")),result.report.rows.map { it.start to it.end })
    assertEquals(Decimal("2"),result.quantities.totals().getValue(ref to def))
    assertEquals(listOf("unknown-time"),result.quantities.unknownTime.map { it.id })
    assertTrue(result.quantities.wholeTargets.isEmpty())
    assertEquals(QuantityReportExclusion.MIXED_WHOLE_TARGET,result.excluded.single { it.id=="whole-pages" }.reason)
    assertEquals(QuantityReportExclusion.NO_SESSION_CLASSIFICATION,result.excluded.single { it.id=="unclassified-event" }.reason)
    assertEquals(QuantityReportExclusion.OUTSIDE_MATCHING_INTERVAL,result.excluded.single { it.id=="passive-event" }.reason)
    assertEquals(Decimal("15"),t.view().quantityReport(a,b).quantities.totals().getValue(ref to def))
    assertTrue(t.view().quantityReport(a,b,Filter(cardId="missing")).quantities.events.isEmpty())
    assertTrue(t.subjects().window(a,b,emptySet()).events.isEmpty())
    val query = SavedReportQuery("joined-query","Active pages",ReportWindow(ReportWindowKind.FIXED,firstDate="2026-03-29",endDateExclusive="2026-03-30"),filter,policy=policy)
    t.saveReportQuery(t.snapshot().token,query)
    assertEquals(result.quantities,t.view().savedQuantityReport(query.id).quantities)
    t=Tracker(reopen(t.store),clock)
    assertEquals(Decimal("2"),t.view().savedQuantityReport(query.id).quantities.totals().getValue(ref to def))
}

fun quantityArchiveAuditSnapshots() {
    val ref=SubjectRef(SubjectKind.CARD,"B");val def=QuantityDefinition("pages",1,"page")
    val subjects=SubjectData(cards=listOf(CardSubject("B","edition")),observations=listOf(StateObservation("o",ref,def,Decimal("100"),10,200,"manual")))
    val content=TransferContent(cards=listOf(Card("B","B")),metrics=listOf(Metric("pages","pages",numberKind=NumberKind.INTEGER,canonicalUnitId="page")),units=listOf(UnitDefinition("page","page","page")),subjects=subjects)
    val base=Dataset(datasetId="D",types=emptyList(),axes=emptyList(),metrics=emptyList(),units=emptyList())
    val group=SelectiveTransfer.group("G","O",0,content)
    val empty=SelectiveTransfer.group("empty","empty-origin",0,TransferContent())
    val origin=TransferOrigin("O",group.hash,content,group.hash)
    fun operation(g: TransferGroup,inserted: TransferContent?=null,previous: TransferOrigin?=null)=TransferOperation("op","0".repeat(128),"D","",0,listOf(TransferGroupProgress(g,TransferConflictPolicy.BLOCK,TransferGroupState.APPLIED,inserted=inserted,previousOrigin=previous)))
    val variants=listOf(base.copy(cards=content.cards,metrics=content.metrics,units=content.units,subjects=subjects),
        base.copy(transfer=TransferState(origins=listOf(origin))),
        base.copy(transfer=TransferState(operations=listOf(operation(group)))),
        base.copy(transfer=TransferState(operations=listOf(operation(empty,inserted=content)))),
        base.copy(transfer=TransferState(operations=listOf(operation(empty,previous=origin)))))
    variants.forEachIndexed { index,d ->
        val backup=Backup(formatVersion=14,backupId="audit-$index",asOf=100,data=d)
        val text=codec.encodeToString(backup)
        assertFailsWith<CoreError>("whole $index") { Backups.decode(text) }
        assertFailsWith<CoreError>("stream $index") { StreamingBackup.read { text.byteInputStream() } }
        assertFailsWith<CoreError>("writer $index") { StreamingBackup.write(backup,ByteArrayOutputStream()) }
        val valid=backup.copy(asOf=200);val bytes=ByteArrayOutputStream();StreamingBackup.write(valid,bytes)
        assertEquals(valid,StreamingBackup.read { bytes.toByteArray().inputStream() })
    }
    val observations=listOf(StateObservation("a",ref,def,Decimal("2300"),10,900,"manual"),StateObservation("b",ref,def,Decimal("2420"),20,950,"manual"))
    val early=SubjectChange("change","a","b",SignedDecimal("120"),1,"manual")
    val live=base.copy(cards=content.cards,metrics=content.metrics,units=content.units,subjects=subjects.copy(observations=observations,changes=listOf(early)))
    assertFailsWith<CoreError> { validate(live) }
    validate(live.copy(subjects=live.subjects.copy(changes=listOf(early.copy(recordedAt=950)))))
}
