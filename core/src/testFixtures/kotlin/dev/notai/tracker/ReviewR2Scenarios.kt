package dev.notai.tracker

import java.io.ByteArrayOutputStream
import java.time.Instant
import kotlin.test.*

/** Retained payloads are synthetic; no real archives or user data are imported here. */
fun retainedChangeCausality(create: ()->StateStore,reopen: (StateStore)->StateStore) {
    val ref=SubjectRef(SubjectKind.CARD,"retained-book");val def=QuantityDefinition("retained-pages",1,"retained-page")
    val a=StateObservation("basis-a",ref,def,Decimal("2300"),10,900,"manual")
    val b=StateObservation("basis-b",ref,def,Decimal("2420"),20,950,"manual")
    val valid=SubjectChange("change",a.id,b.id,SignedDecimal("120"),950,"manual")
    val early=valid.copy(recordedAt=1)
    fun content(change: SubjectChange)=TransferContent(cards=listOf(Card(ref.id,"Book")),
        metrics=listOf(Metric(def.metricId,"Pages",numberKind=NumberKind.INTEGER,canonicalUnitId=def.unitId)),units=listOf(UnitDefinition(def.unitId,"Page","page")),
        subjects=SubjectData(cards=listOf(CardSubject(ref.id,"edition")),observations=listOf(a,b),changes=listOf(change)))
    fun group(c: TransferContent)=SelectiveTransfer.group("retained-group","retained-origin",0,c)
    fun origin(c: TransferContent)=TransferOrigin("retained-origin",contentHash(c),c,contentHash(c))
    fun operation(g: TransferGroup,inserted: TransferContent?=null,previous: TransferOrigin?=null)=
        TransferOperation("retained-op","0".repeat(128),"retained-dataset","",0,listOf(TransferGroupProgress(g,TransferConflictPolicy.BLOCK,TransferGroupState.APPLIED,inserted=inserted,previousOrigin=previous)))
    val base=Dataset(datasetId="retained-dataset",types=emptyList(),axes=emptyList(),metrics=emptyList(),units=emptyList())
    val empty=group(TransferContent());val good=content(valid);val bad=content(early)
    fun journal(g: TransferGroup,inserted: TransferContent?=null,previous: TransferOrigin?=null)=
        base.copy(transfer=TransferState(operations=listOf(operation(g,inserted,previous))))
    fun delta(change: SubjectChange,observations: List<StateObservation> = emptyList())=
        TransferContent(subjects=SubjectData(observations=observations,changes=listOf(change)))
    val negative=linkedMapOf(
        "origin.localGroup" to base.copy(transfer=TransferState(origins=listOf(origin(bad)))),
        "operation.group.content" to journal(group(bad)),
        "operation.inserted.full" to journal(empty,inserted=bad),
        "operation.previousOrigin" to journal(empty,previous=origin(bad)),
        "operation.inserted.partial" to journal(group(good),inserted=delta(early)),
        "operation.inserted.conflictingBasis" to journal(group(good),inserted=delta(valid.copy(recordedAt=200),listOf(a.copy(recordedAt=100),b.copy(recordedAt=100))))
    )
    for((name,d) in negative) {
        val backup=Backup(formatVersion=14,backupId=name,asOf=1000,data=d);val text=codec.encodeToString(backup)
        assertFailsWith<CoreError>("whole $name") { Backups.decode(text) }
        assertFailsWith<CoreError>("stream $name") { StreamingBackup.read { text.byteInputStream() } }
        assertFailsWith<CoreError>("write $name") { StreamingBackup.write(backup,ByteArrayOutputStream()) }
        val store=create();val before=store.read()
        assertFailsWith<CoreError>("store $name") { store.transaction { d to Unit } }
        assertEquals(before,store.read(),name)
    }
    // A partial delta's basis belongs to the corresponding complete source, not live data.
    val positive=listOf(
        base.copy(transfer=TransferState(origins=listOf(origin(good)))),
        journal(group(good)),journal(empty,inserted=good),journal(empty,previous=origin(good)),
        journal(group(good),inserted=delta(valid))
    )
    for((index,d) in positive.withIndex()) {
        val backup=Backup(formatVersion=14,backupId="valid-$index",asOf=1000,data=d);val bytes=ByteArrayOutputStream()
        StreamingBackup.write(backup,bytes);assertEquals(backup,Backups.decode(bytes.toString("UTF-8")))
        assertEquals(backup,StreamingBackup.read { bytes.toByteArray().inputStream() })
        val store=create();store.transaction { d to Unit };assertEquals(d,reopen(store).read())
    }
    val live=base.copy(cards=good.cards,metrics=good.metrics,units=good.units,subjects=good.subjects.copy(changes=emptyList()))
    val missing=live.copy(transfer=journal(empty,inserted=delta(valid)).transfer)
    assertFailsWith<CoreError> { validate(missing) } // Never borrow an unrelated live basis.
    val store=create();store.transaction { positive.first() to Unit };val t=Tracker(store,FakeClock(1000))
    val retention=t.previewTransferRetention(TransferRetentionSelection(originIds=setOf("retained-origin")))
    t.applyTransferRetention(retention,"retained-hashes",retention.requiredAcknowledgements)
    assertEquals(TransferContent(),t.data().transfer.origins.single().localGroup)
    val compact=Backup(formatVersion=14,backupId="hash-only",asOf=100,data=t.data())
    val bytes=ByteArrayOutputStream();StreamingBackup.write(compact,bytes)
    assertEquals(compact,Backups.decode(bytes.toString("UTF-8")))
    assertEquals(compact,StreamingBackup.read { bytes.toByteArray().inputStream() })
}
private fun contentHash(c: TransferContent)=SelectiveTransfer.contentHash(c)

fun exactOriginPrecedesStaleCandidate(create: ()->StateStore,reopen: (StateStore)->StateStore) {
    val clock=FakeClock();var t=Tracker(create(),clock);val id="same-member"
    fun pack(name: String,origin: String,revision: Long): SelectivePackage {
        val c=TransferContent(cards=listOf(Card(id,name)),subjects=SubjectData(cards=listOf(CardSubject(id,"edition"))))
        return SelectivePackage(4,4,transferId="package-$origin",sourceDatasetId="source",groups=listOf(SelectiveTransfer.group("group-$origin",origin,revision,c)))
    }
    val a=pack("A","O1",1);val b=pack("B","O2",2)
    fun apply(p: SelectivePackage,command: String) { val i=SelectiveTransfer(t);assertTrue(i.apply(i.plan(p,p.groups.map { it.id }.toSet()),command).complete) }
    apply(a,"first-origin");t.saveCard(t.snapshot().token,Card(id,"B"));apply(b,"second-origin")
    assertEquals(listOf("O1","O2"),t.data().transfer.origins.map { it.originId })
    fun exported(): SelectivePackage = SelectiveTransfer(t).export(TransferSelection(subjectRecords=setOf(SubjectRecordKey(SubjectRecordKind.CARD,id)))).also {
        assertEquals(b.groups,it.groups);assertEquals("O2",it.groups.single().originId)
    }
    val p=exported();val importer=SelectiveTransfer(t)
    assertTrue(importer.apply(importer.plan(p,p.groups.map { it.id }.toSet()),"unchanged-exact").groups.all { it.state==TransferGroupState.UNCHANGED })
    t=Tracker(reopen(t.store),clock);exported()
    val retention=t.previewTransferRetention(TransferRetentionSelection(originIds=setOf("O1","O2")))
    t.applyTransferRetention(retention,"compact-origins",retention.requiredAcknowledgements)
    t=Tracker(reopen(t.store),clock);exported()
    val full=Backups(t,MemoryRollback()).export();val restored=Tracker(create(),clock);val backups=Backups(restored,MemoryRollback())
    backups.apply(backups.prepare(full));t=restored;exported()
    val again=SelectiveTransfer(t);assertTrue(again.apply(again.plan(b,b.groups.map { it.id }.toSet()),"second-origin").payloadsPruned)
    t.saveCard(t.snapshot().token,Card(id,"C"))
    val fallback=SelectiveTransfer(t).export(TransferSelection(subjectRecords=setOf(SubjectRecordKey(SubjectRecordKind.CARD,id))))
    assertEquals("O1",fallback.groups.single().originId);assertEquals("C",fallback.groups.single().content.cards.single().name)
}

fun sharedQueryDependenciesCloseWithoutExplicitRoot(create: ()->StateStore,reopen: (StateStore)->StateStore) {
    for(axis in listOf(false,true)) {
        val clock=FakeClock();val source=Tracker(create(),clock)
        source.saveType(source.snapshot().token,ActivityType("shared-type","Shared"));source.saveType(source.snapshot().token,ActivityType("unrelated-type","Independent"))
        source.saveAxis(source.snapshot().token,Axis("shared-axis","Shared axis",valueKind=ValueKind.FREE_LABEL))
        val filter=if(axis) Filter(facets=listOf(Facet("shared-axis",text="inside"))) else Filter(activityTypeId="shared-type")
        source.saveGoal(source.snapshot().token,Goal("G","Selected goal",GoalMetric.DISTINCT_RECORDED_DAYS,2,filter=filter))
        source.saveGoal(source.snapshot().token,Goal("H","Independent goal",GoalMetric.DISTINCT_RECORDED_DAYS,2,filter=Filter(activityTypeId="unrelated-type")))
        source.saveReportQuery(source.snapshot().token,SavedReportQuery("Q","Selected query",filter=if(axis) Filter() else filter,grouping=if(axis) ReportGrouping.FACET else ReportGrouping.NONE,groupKey=if(axis) "shared-axis" else null))
        source.manual(source.snapshot().token,clock.wall-1000,clock.wall-500,Classification(activityTypeId="shared-type"))
        val pack=SelectiveTransfer(source).export(TransferSelection(goalIds=setOf("G","H"),savedReportIds=setOf("Q")))
        assertEquals(2,pack.groups.size);assertTrue(pack.groups.all { it.content.sessions.isEmpty() })
        assertEquals(listOf("Q"),pack.groups.single { it.content.goals.any { g -> g.id=="G" } }.content.savedReports.map { it.id })
        val joint=pack.groups.single { it.content.savedReports.isNotEmpty() }
        val split=pack.copy(groups=listOf(
            SelectiveTransfer.group("split-goal","split-O1",0,joint.content.copy(savedReports=emptyList())),
            SelectiveTransfer.group("split-query","split-O2",0,joint.content.copy(goals=emptyList()))))
        val rejected=assertFailsWith<CoreError> { SelectiveTransfer(source).inspect(split) }
        assertEquals(ErrorCode.UNSUPPORTED_IMPORT,rejected.code)
        assertTrue(rejected.message!!.contains("dependency"))
        val target=Tracker(create(),clock)
        if(axis) target.saveAxis(target.snapshot().token,Axis("shared-axis","Local conflict",valueKind=ValueKind.FREE_LABEL))
        else target.saveType(target.snapshot().token,ActivityType("shared-type","Local conflict"))
        val importer=SelectiveTransfer(target);val policies=pack.groups.associate { it.id to TransferConflictPolicy.COPY }
        val receipt=importer.apply(importer.plan(pack,pack.groups.map { it.id }.toSet(),policies),"dependency-copy")
        assertTrue(receipt.complete);assertFalse(receipt.partial);assertTrue(target.data().sessions.isEmpty())
        val g=target.data().goals.single { it.name=="Selected goal" };val q=target.data().savedReports.single()
        if(axis) { assertNotEquals("shared-axis",q.groupKey);assertEquals(g.filter.facets.single().axisId,q.groupKey) }
        else { assertNotEquals("shared-type",g.filter.activityTypeId);assertEquals(g.filter.activityTypeId,q.filter.activityTypeId) }
        assertEquals("unrelated-type",target.data().goals.single { it.name=="Independent goal" }.filter.activityTypeId)
        val retention=target.previewTransferRetention(TransferRetentionSelection(operationIds=setOf("dependency-copy")))
        target.applyTransferRetention(retention,"dependency-prune",retention.requiredAcknowledgements)
        val opened=Tracker(reopen(target.store),clock);val repeat=SelectiveTransfer(opened)
        assertTrue(repeat.apply(repeat.plan(pack,pack.groups.map { it.id }.toSet(),policies),"dependency-copy").payloadsPruned)
        val reexport=repeat.export(TransferSelection(goalIds=opened.data().goals.map { it.id }.toSet(),savedReportIds=setOf(q.id)))
        assertEquals(pack.groups.map { it.hash }.toSet(),reexport.groups.map { it.hash }.toSet())
    }
    val legacyType=ActivityType("legacy-shared-type","Shared legacy type")
    fun legacy(id: String)=TransferContent(types=listOf(legacyType),cards=listOf(Card(id,"Same title",activityTypeId=legacyType.id)))
    val legacy=SelectivePackage(sourceDatasetId="legacy-source",groups=listOf(SelectiveTransfer.group("legacy-a","legacy-O1",0,legacy("a")),SelectiveTransfer.group("legacy-b","legacy-O2",0,legacy("b"))))
    assertEquals(2,SelectiveTransfer(Tracker(create(),FakeClock())).inspect(legacy).groups.size)

}

fun quantityWindowsRequireCompatibleObservation(create: ()->StateStore) {
    fun time(s: String)=Instant.parse(s).toEpochMilli()
    val monday=time("2026-10-12T00:00:00Z");val tuesday=monday+86_400_000;val wednesday=tuesday+86_400_000
    val cutoff=time("2026-10-13T11:00:00Z");val clock=FakeClock(cutoff);val t=Tracker(create(),clock)
    t.saveCard(t.snapshot().token,Card("window-book","Book"));t.defineSubject(t.snapshot().token,CardSubject("window-book","edition"))
    t.saveUnit(t.snapshot().token,UnitDefinition("window-page","Page","page"));t.saveMetric(t.snapshot().token,Metric("window-pages","Pages",numberKind=NumberKind.INTEGER,canonicalUnitId="window-page"))
    val ref=SubjectRef(SubjectKind.CARD,"window-book");val def=QuantityDefinition("window-pages",1,"window-page")
    val old=EventQuantity("E0",ref,def,Decimal("10"),monday+3_600_000,monday+7_200_000,"manual")
    t.recordEventQuantity(t.snapshot().token,old)
    t.recordEventQuantity(t.snapshot().token,old.copy(id="E1",value=Decimal("12"),effectiveAt=tuesday+3_600_000,recordedAt=cutoff,replacesId=old.id))
    clock.wall=cutoff-1_800_000
    val a=t.view().quantityReport(monday,tuesday).quantities;assertEquals(listOf("E0"),a.events.map { it.id })
    clock.wall=cutoff
    val b=t.view().quantityReport(tuesday,wednesday).quantities;assertEquals(a.token,b.token);assertEquals(listOf("E1"),b.events.map { it.id })
    assertFailsWith<CoreError> { a.combine(b) }
    val currentMonday=t.view().quantityReport(monday,tuesday).quantities
    val joined=currentMonday.combine(b);assertEquals(Decimal("12"),joined.totals().values.single())
    val narrowed=t.view().quantityReport(tuesday,wednesday,Filter(cardId="missing")).quantities
    assertFailsWith<CoreError> { b.combine(narrowed) }
    val stored=t.subjects().window(tuesday,wednesday)
    assertFailsWith<CoreError> { stored.combine(b) }
    assertFailsWith<CoreError> { stored.combine(t.subjects().window(monday,tuesday,emptySet())) }
    assertEquals(Decimal("12"),stored.combine(t.subjects().window(monday,tuesday)).totals().values.single())
}
