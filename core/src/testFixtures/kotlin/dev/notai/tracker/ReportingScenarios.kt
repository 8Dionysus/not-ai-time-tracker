package dev.notai.tracker

import java.time.*
import java.io.ByteArrayOutputStream
import kotlin.test.*
import kotlinx.serialization.json.*

private fun ms(text: String) = Instant.parse(text).toEpochMilli()
private fun expectReporting(code: ErrorCode,block: ()->Unit) = assertEquals(code,assertFailsWith<CoreError>(block = block).code)

fun reportingDaysAndGoalVersions(create: ()->StateStore,reopen: (StateStore)->StateStore) {
    val clock = FakeClock(ms("2026-10-16T12:00:00Z"));var t = Tracker(create(),clock)
    t.configureReportingDay(t.snapshot().token,ReportingDayPolicy("UTC",240))
    val g = Goal("days","Reading days",GoalMetric.DISTINCT_RECORDED_DAYS,4,period = GoalPeriod.WEEK,
        dayStartMinute = 240,filter = Filter(tags = setOf("read")))
    t.saveGoal(t.snapshot().token,g)
    fun add(a: String,b: String,read: Boolean = true) = t.manual(t.snapshot().token,ms(a),ms(b),Classification(tags = if(read) setOf("read") else emptySet()),"Hidden note")
    add("2026-10-13T23:55:00Z","2026-10-14T00:05:00Z")
    add("2026-10-14T01:00:00Z","2026-10-14T01:10:00Z")
    add("2026-10-14T03:55:00Z","2026-10-14T04:05:00Z")
    add("2026-10-15T12:00:00Z","2026-10-15T13:00:00Z",false)
    assertEquals(2,t.goalProgress(g.id).actual)
    val calendar = t.view().calendar(LocalDate.parse("2026-10-12"),7,g.filter)
    assertEquals(calendar.days.filter { it.recordedMs > 0 }.map { it.date }.toSet(),calendar.window.recordedDays)
    assertEquals(25*60_000L,t.view().day(LocalDate.parse("2026-10-13"),g.filter).recordedMs)
    val cross = t.data().segments[2]
    t.correctSegment(t.snapshot().token,cross.id,cross.start,ms("2026-10-14T04:00:00Z"),cross.selection)
    assertEquals(1,t.goalProgress(g.id).actual)
    t.undo(t.snapshot().token);assertEquals(2,t.goalProgress(g.id).actual)
    t.deleteSession(t.snapshot().token,cross.sessionId);assertEquals(1,t.goalProgress(g.id).actual)
    t.undo(t.snapshot().token)
    val oldToken = t.snapshot().token
    t.saveGoal(oldToken,g.copy(target = 6))
    expectReporting(ErrorCode.STALE) { t.saveGoal(oldToken,g.copy(target = 7)) }
    assertEquals(4,t.goalProgress(g.id,LocalDate.parse("2026-10-16")).goal.target)
    assertEquals(6,t.goalProgress(g.id,LocalDate.parse("2026-10-19")).goal.target)
    t.saveGoal(t.snapshot().token,g.copy(target = 5),GoalEditMode.CORRECT_CURRENT)
    assertEquals(4,t.goalProgress(g.id,LocalDate.parse("2026-10-09")).goal.target)
    assertEquals(5,t.goalProgress(g.id,LocalDate.parse("2026-10-16")).goal.target)
    assertEquals(6,t.goalProgress(g.id,LocalDate.parse("2026-10-19")).goal.target)
    assertEquals(3,t.data().goals.single().revisions.size)
    val saved = t.data();t = Tracker(reopen(t.store),clock);assertEquals(saved,t.data())
    val observation = ObservationFacts.week(t.data(),clock.now(),LocalDate.parse("2026-10-16"),"UTC",ObservationRequest(enabled = true,filter = g.filter))!!
    assertEquals(2,observation.positiveDays);assertEquals(calendar.window.recordedMs,observation.recordedMs)
    val backup = StreamingBackup.portable(t.data(),clock.now());val bytes = ByteArrayOutputStream();StreamingBackup.write(backup,bytes)
    assertEquals(backup,StreamingBackup.read { bytes.toByteArray().inputStream() })
    assertEquals(backup,Backups.decode(bytes.toString("UTF-8")))
    val imported = Tracker(create(),clock);val backups = Backups(imported,MemoryRollback());backups.apply(backups.prepare(bytes.toString("UTF-8")))
    assertEquals(t.data().goals,imported.data().goals);assertEquals(2,imported.goalProgress(g.id).actual)
    t.start(t.snapshot().token,Classification(tags = setOf("read")));clock.advance(1000);clock.boot = "unknown-boot"
    assertTrue(t.goalProgress(g.id).uncertain);assertEquals(2,t.goalProgress(g.id).actual)
    assertFalse(ReportText.build(t.view().day(LocalDate.parse("2026-10-16"))).contains("Hidden note"))
    val before = t.data()
    expectReporting(ErrorCode.INVALID_INPUT) { t.configureReportingDay(t.snapshot().token,ReportingDayPolicy("UTC",1440)) }
    assertEquals(before,t.data())
}

fun savedReportsAndPortableHistory(create: ()->StateStore,reopen: (StateStore)->StateStore) {
    val clock = FakeClock(ms("2026-10-16T12:00:00Z"));var source = Tracker(create(),clock)
    source.saveType(source.snapshot().token,ActivityType("old-parent","Old branch",selectable = false))
    source.saveType(source.snapshot().token,ActivityType("new-parent","New branch",selectable = false))
    val type = ActivityType("reading-own","Reading",parentId = "old-parent");source.saveType(source.snapshot().token,type)
    val card = Card("book-own","Same name",activityTypeId = type.id);source.saveCard(source.snapshot().token,card)
    source.manual(source.snapshot().token,ms("2026-10-15T23:50:00Z"),ms("2026-10-16T00:10:00Z"),card.classification(),"Secret private note")
    val q = SavedReportQuery("reading-report","Reading",ReportWindow(ReportWindowKind.CURRENT_MONTH),Filter(cardId = card.id),policy = ReportingDayPolicy("UTC",240))
    source.saveReportQuery(source.snapshot().token,q)
    val first = source.view().savedReport(q.id);assertEquals(20*60_000L,first.report.recordedMs)
    assertEquals(setOf(LocalDate.parse("2026-10-15")),first.report.recordedDays)
    val text = ReportText.build(first.report);assertFalse(text.contains("Secret private note"));assertTrue(text.contains("1200000 ms"))
    assertTrue(ReportText.build(first.report,true).contains("Secret private note"))
    assertEquals(text,ReportText.build(first.report.copy(rows = first.report.rows.reversed())))
    source.saveCard(source.snapshot().token,card.copy(name = "Changed",archived = true))
    source = Tracker(reopen(source.store),clock)
    assertEquals(ReportReferenceStatus.ARCHIVED,source.view().savedReport(q.id).references.single().status)
    assertEquals(first.report.rows,source.view().savedReport(q.id).report.rows)
    val branch = q.copy(id = "old-branch",filter = Filter(activityTypeId = "old-parent"))
    source.saveReportQuery(source.snapshot().token,branch)
    source.saveType(source.snapshot().token,type.copy(parentId = "new-parent"))
    assertEquals(20*60_000L,source.view().savedReport(branch.id).report.recordedMs)
    source.deleteReportQuery(source.snapshot().token,branch.id)
    val g = Goal("portable-goal","Old",GoalMetric.DURATION_MS,100,period = GoalPeriod.WEEK,filter = Filter(cardId = card.id))
    source.saveGoal(source.snapshot().token,g);source.saveGoal(source.snapshot().token,g.copy(name = "New",filter = Filter(activityTypeId = type.id)))
    val transfer = SelectiveTransfer(source)
    val pack = transfer.export(TransferSelection(goalIds = setOf(g.id),savedReportIds = setOf(q.id)))
    assertEquals(4,pack.minimumReader);assertTrue(pack.groups.any { it.content.cards.any { c -> c.id == card.id } })
    val parsed = codec.decodeFromString<SelectivePackage>(codec.encodeToString(pack));transfer.inspect(parsed)
    val target = Tracker(create(),clock);val importer = SelectiveTransfer(target)
    val applied = importer.apply(importer.plan(parsed,parsed.groups.map { it.id }.toSet()),"report-import")
    assertFalse(applied.partial);assertEquals(source.data().goals,target.data().goals);assertEquals(listOf(q),target.data().savedReports)
    val repeat = importer.apply(importer.plan(parsed,parsed.groups.map { it.id }.toSet()),"report-repeat")
    assertTrue(repeat.groups.all { it.state == TransferGroupState.UNCHANGED })
    target.saveReportQuery(target.snapshot().token,q.copy(name = "Local edit"))
    val policy = parsed.groups.associate { it.id to TransferConflictPolicy.COPY }
    val copied = importer.apply(importer.plan(parsed,parsed.groups.map { it.id }.toSet(),policy),"report-copy")
    assertTrue(copied.complete);assertEquals(2,target.data().savedReports.size)
    val copy = target.data().savedReports.single { it.id != q.id };assertNotEquals(card.id,copy.filter.cardId)
    assertTrue(target.data().cards.any { it.id == copy.filter.cardId })
    importer.undo("report-copy",target.snapshot().token);assertEquals(1,target.data().savedReports.size)
    expectReporting(ErrorCode.UNSUPPORTED_IMPORT) { transfer.inspect(parsed.copy(formatVersion = 3,minimumReader = 3)) }
    val compactTarget = Tracker(create(),clock);val compactImporter = SelectiveTransfer(compactTarget)
    compactImporter.apply(compactImporter.plan(parsed,parsed.groups.map { it.id }.toSet()),"compact-import")
    val compactPreview = compactTarget.previewTransferRetention(TransferRetentionSelection(operationIds = setOf("compact-import")))
    assertTrue(compactPreview.canApply)
    compactTarget.applyTransferRetention(compactPreview,"compact-reports",compactPreview.requiredAcknowledgements)
    assertEquals(parsed.groups,compactImporter.export(TransferSelection(goalIds = setOf(g.id),savedReportIds = setOf(q.id))).groups)
    assertTrue(compactImporter.apply(compactImporter.plan(parsed,parsed.groups.map { it.id }.toSet()),"compact-replay").groups.all { it.state == TransferGroupState.UNCHANGED })
    val compactJson = Backups.encode(compactTarget.data(),clock.now());assertEquals(compactTarget.data().transfer,Backups.decode(compactJson).data.transfer)
    val undoneTarget = Tracker(create(),clock);val undoneImporter = SelectiveTransfer(undoneTarget)
    undoneImporter.apply(undoneImporter.plan(parsed,parsed.groups.map { it.id }.toSet()),"undo-prune-import")
    undoneImporter.undo("undo-prune-import",undoneTarget.snapshot().token)
    val undoPrune = undoneTarget.previewTransferRetention(TransferRetentionSelection(operationIds = setOf("undo-prune-import")))
    assertTrue(undoPrune.canApply);undoneTarget.applyTransferRetention(undoPrune,"undo-prune",undoPrune.requiredAcknowledgements)
    assertTrue(undoneTarget.data().savedReports.isEmpty());assertTrue(undoneTarget.data().transfer.origins.isEmpty())
    val undoneArchive = codec.parseToJsonElement(Backups.encode(undoneTarget.data(),clock.now())).jsonObject
    val wrongLegacy = JsonObject(undoneArchive+("formatVersion" to JsonPrimitive(13))).toString()
    expectReporting(ErrorCode.UNSUPPORTED_IMPORT) { Backups.decode(wrongLegacy) }
    expectReporting(ErrorCode.UNSUPPORTED_IMPORT) { StreamingBackup.read { wrongLegacy.byteInputStream() } }
    val stale = source.snapshot().token;source.deleteReportQuery(stale,q.id)
    expectReporting(ErrorCode.STALE) { source.saveReportQuery(stale,q) }
    source.undo(source.snapshot().token);assertEquals(listOf(q),source.data().savedReports)
    val session = source.data().sessions.single();source.deleteSession(source.snapshot().token,session.id)
    assertEquals(0,source.view().savedReport(q.id).report.recordedMs)
    val missing = q.copy(id = "missing-report",filter = Filter(cardId = "deleted-card"))
    source.saveReportQuery(source.snapshot().token,missing)
    assertEquals(ReportReferenceStatus.MISSING,source.view().savedReport(missing.id).references.single().status)
    assertEquals(0,source.view().savedReport(missing.id).report.recordedMs)
    assertEquals(1,SelectiveTransfer(source).export(TransferSelection(savedReportIds = setOf(missing.id))).groups.size)
    val legacy = Backups.encode(Dataset(),clock.now());val root = codec.parseToJsonElement(legacy).jsonObject
    val v13 = JsonObject(root+("formatVersion" to JsonPrimitive(13)))
    assertEquals(Dataset::class,Backups.decode(v13.toString()).data::class)
    val newArchive = codec.parseToJsonElement(Backups.encode(source.data(),clock.now())).jsonObject
    expectReporting(ErrorCode.UNSUPPORTED_IMPORT) { Backups.decode(JsonObject(newArchive+("formatVersion" to JsonPrimitive(13))).toString()) }
}
