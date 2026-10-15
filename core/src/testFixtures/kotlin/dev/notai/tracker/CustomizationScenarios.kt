package dev.notai.tracker

import java.time.*
import java.io.IOException
import kotlin.test.*

private fun rejected(code: ErrorCode,block: ()->Unit) = assertEquals(code,assertFailsWith<CoreError>(block = block).code)
private fun createPersonalCatalog(t: Tracker): Pair<List<ActivityType>,Card> {
    val parent = ActivityType("own-reading","Чтение",selectable = false)
    val leaf = ActivityType("own-philosophy","Читал философию",parentId = parent.id)
    t.saveType(t.snapshot().token,parent);t.saveType(t.snapshot().token,leaf)
    val card = Card("own-zarathustra","Заратустра",activityTypeId = leaf.id,details = mapOf("author" to "Фридрих Ницше"),tags = setOf("личное"))
    t.saveCard(t.snapshot().token,card)
    return listOf(parent,leaf) to card
}
internal fun personalObjectLifecycle(create: ()->StateStore,reopen: (StateStore)->StateStore) {
    val clock = FakeClock();var t = Tracker(create(),clock);val (types,card) = createPersonalCatalog(t)
    assertTrue(t.data().sessions.isEmpty())
    t.start(t.snapshot().token,t.selection(card.id));val session = t.snapshot().session!!
    assertNull(session.note);assertEquals(card.id,session.selection.cardId);assertEquals(types.last().id,session.selection.activityTypeId)
    assertEquals("Заратустра",session.selection.name);assertEquals(types.map { it.id },session.selection.ancestorPathAtWrite)
    clock.advance(1000);t.stop(t.snapshot().token);val history = t.data().segments
    val renamed = card.copy(name = "Мой экземпляр Заратустры",archived = true)
    t.saveCard(t.snapshot().token,renamed)
    val movedParent = ActivityType("own-other-reading","Другое чтение",selectable = false)
    t.saveType(t.snapshot().token,movedParent);t.saveType(t.snapshot().token,types.last().copy(name = "Моя философия",parentId = movedParent.id))
    assertEquals(history,t.data().segments);assertEquals(session.selection,t.data().sessions.single().selection)
    rejected(ErrorCode.INVALID_INPUT) { t.selection(card.id) }
    val before = t.data();rejected(ErrorCode.INVALID_INPUT) { t.saveType(t.snapshot().token,movedParent.copy(parentId = types.last().id)) };assertEquals(before,t.data())
    t.repeat(t.snapshot().token,session.id);clock.advance(1000);t.stop(t.snapshot().token)
    assertEquals("Заратустра",t.data().segments.first().selection.name)
    assertEquals(listOf(movedParent.id,types.last().id),t.data().segments.last().selection.ancestorPathAtWrite)
    t.saveCard(t.snapshot().token,renamed.copy(archived = false))
    val currentType = t.data().types.single { it.id == types.last().id }
    t.saveType(t.snapshot().token,currentType.copy(archived = true));rejected(ErrorCode.INVALID_INPUT) { t.selection(card.id) }
    t.saveType(t.snapshot().token,currentType)
    assertEquals(renamed.name,t.selection(card.id).name)
    t.copyCard(t.snapshot().token,card.id,"Другой экземпляр")
    assertEquals(2,t.data().cards.size);assertEquals(2,t.data().cards.map { it.id }.distinct().size)
    t = Tracker(reopen(t.store),clock);val source = t.data()
    val backup = Backups(t,MemoryRollback()).export();val destination = Tracker(create(),clock)
    val restore = Backups(destination,MemoryRollback());restore.apply(restore.prepare(backup))
    assertEquals(source,destination.data().copy(revision = source.revision,controlBarrier = source.controlBarrier))
    assertTrue(destination.data().sessions.all { it.note == null })
}
internal fun functionalSchedulePreferences(create: ()->StateStore,reopen: (StateStore)->StateStore) {
    val clock = FakeClock(wall = Instant.parse("2026-10-25T00:10:00Z").toEpochMilli());var t = Tracker(create(),clock)
    t.start(t.snapshot().token);val before = t.data();val timer = t.snapshot().timerTarget
    val requested = ScheduleSettings(ScheduleDayPolicy(ScheduleZonePolicy.FIXED,"Europe/Berlin","02:30:00"),true)
    t.configureSchedule(t.snapshot().token,requested)
    val saved = requested.copy(day = requested.day.copy(boundary = "02:30"))
    assertEquals(saved,t.data().settings.schedule);assertEquals(before.settings.reportZone,t.data().settings.reportZone)
    assertEquals(before.sessions,t.data().sessions);assertEquals(before.segments,t.data().segments);assertEquals(timer,t.snapshot().timerTarget)
    assertEquals(LocalDate.parse("2026-10-24"),t.scheduleDay(ZoneId.of("UTC")).date)
    assertEquals(LocalDate.parse("2026-10-24"),t.scheduleDay(ZoneId.of("Asia/Tokyo")).date) // Explicit home zone persists through travel.
    val rule = ScheduleRule(Classification(),"2026-10-24",extraDates = setOf("2026-10-24","2026-10-25"))
    t.scheduleCommand(ScheduleCommand("date-only",t.snapshot().token,ScheduleAction.CREATE,"day-series",rule = rule))
    fun rows() = t.listOccurrences(LocalDate.parse("2026-10-24"),LocalDate.parse("2026-10-25"),ZoneId.of("UTC"))
    assertEquals(listOf(OccurrenceState.TODAY,OccurrenceState.SCHEDULED),rows().map { it.state })
    assertTrue(rows().all { it.instant == null && it.reminderInstant == null })
    clock.advance(20*60_000);assertEquals(LocalDate.parse("2026-10-25"),t.scheduleDay(ZoneId.of("UTC")).date)
    clock.advance(30*60_000);assertEquals(LocalDate.parse("2026-10-25"),t.scheduleDay(ZoneId.of("UTC")).date) // Second 02:00 must not become yesterday.
    assertEquals(listOf(OccurrenceState.OVERDUE,OccurrenceState.TODAY),rows().map { it.state })
    val healthy = t.data()
    for(bad in listOf(ScheduleDayPolicy(ScheduleZonePolicy.FIXED),ScheduleDayPolicy(zoneId = "UTC"),ScheduleDayPolicy(boundary = "24:00"),ScheduleDayPolicy(boundary = "02:30:00.1"),ScheduleDayPolicy(ScheduleZonePolicy.FIXED,"not/a-zone"))) {
        rejected(ErrorCode.INVALID_INPUT) { t.configureSchedule(t.snapshot().token,ScheduleSettings(bad)) };assertEquals(healthy,t.data())
    }
    rejected(ErrorCode.STALE) { t.configureSchedule(Token(before.datasetId,before.revision,null),ScheduleSettings()) }
    t.settings(t.snapshot().token,t.data().settings.copy(reportZone = "Asia/Tokyo")) // Existing UI's copy preserves the new preferences.
    assertEquals(saved,t.data().settings.schedule)
    val quiet = ScheduleDeliveryFixturePlatform();assertNull(ScheduleDelivery(t,ScheduleDeliveryFixtureStore(),quiet) { ZoneId.of("UTC") }.synchronize().next)
    assertTrue(quiet.postCalls.isEmpty());assertNull(quiet.pending) // Quiet shade is not interrupting alert opt-in.
    t = Tracker(reopen(t.store),clock);assertEquals(saved,t.data().settings.schedule)
    val bytes = java.io.ByteArrayOutputStream();Backups(t,MemoryRollback()).write(bytes)
    val destination = Tracker(create(),clock);val restore = Backups(destination,MemoryRollback());val preview = restore.prepare { bytes.toByteArray().inputStream() }
    assertEquals(saved,preview.settings.schedule);assertEquals(1,preview.schedule.series.size)
    assertFails { (preview.schedule.series as MutableList<ScheduleSeries>).clear() }
    assertFails { (preview.schedule.series.single().revisions.single().rule.extraDates as MutableSet<String>).clear() }
    restore.apply(preview)
    assertEquals(saved,destination.data().settings.schedule);assertEquals("Asia/Tokyo",destination.data().settings.reportZone)
    assertEquals(Mode.RECOVERY,destination.snapshot().mode)
    val device = ScheduleSettings(ScheduleDayPolicy(boundary = "02:30"))
    destination.configureSchedule(destination.snapshot().token,device)
    assertEquals(LocalDate.parse("2026-10-24"),destination.scheduleDay(ZoneId.of("UTC")).date)
    assertEquals(LocalDate.parse("2026-10-25"),destination.scheduleDay(ZoneId.of("Asia/Tokyo")).date)
}
internal fun standaloneCatalogTransfer(create: ()->StateStore,reopen: (StateStore)->StateStore) {
    val clock = FakeClock();val source = Tracker(create(),clock);val (types,card) = createPersonalCatalog(source)
    val axis = Axis("own-format","Формат",valueKind = ValueKind.FREE_LABEL)
    val unit = UnitDefinition("own-page","Страница","pages")
    val metric = Metric("own-pages","Страницы",numberKind = NumberKind.DECIMAL,canonicalUnitId = unit.id,aggregation = Aggregation.SUM)
    source.saveAxis(source.snapshot().token,axis);source.saveUnit(source.snapshot().token,unit);source.saveMetric(source.snapshot().token,metric)
    source.saveType(source.snapshot().token,types.last().copy(suggestedAxisIds = setOf(axis.id),suggestedMetricIds = setOf(metric.id)))
    source.saveCard(source.snapshot().token,card.copy(facets = listOf(Facet(axis.id,text = "бумага"))))
    source.configureSchedule(source.snapshot().token,ScheduleSettings(ScheduleDayPolicy(boundary = "04:00"),true))
    val chosen = TransferSelection(cardIds = setOf(card.id),typeIds = types.map { it.id }.toSet())
    val value = SelectiveTransfer(source).export(chosen);assertEquals(2,value.formatVersion);assertEquals(2,value.minimumReader)
    val content = value.groups.single().content
    assertEquals(setOf(card.id),content.catalogRoots.cardIds);assertEquals(types.map { it.id }.toSet(),content.catalogRoots.typeIds)
    assertEquals(types.map { it.id }.toSet(),content.types.map { it.id }.toSet())
    assertEquals(listOf(axis),content.axes);assertEquals(listOf(metric),content.metrics);assertEquals(listOf(unit),content.units)
    assertTrue(content.sessions.isEmpty());assertTrue(source.data().sessions.isEmpty());assertTrue(content.favorites.isEmpty())
    assertEquals(value.groups.single().hash,SelectiveTransfer(source).export(chosen.copy(typeIds = types.asReversed().map { it.id }.toSet())).groups.single().hash)
    var target = Tracker(create(),clock);var importer = SelectiveTransfer(target);val initial = target.data()
    val plan = importer.plan(value,value.groups.map { it.id }.toSet());assertEquals(initial,target.data())
    val applied = importer.apply(plan,"own-catalog");assertEquals(TransferGroupState.APPLIED,applied.groups.single().state)
    assertEquals(initial.settings,target.data().settings);assertTrue(target.data().sessions.isEmpty())
    assertEquals(content.cards.single(),target.data().cards.single())
    assertEquals(applied,importer.apply(plan,"own-catalog"))
    importer.undo(applied.id,target.snapshot().token)
    assertEquals(initial.cards,target.data().cards);assertEquals(initial.types,target.data().types)
    importer.apply(importer.plan(value,value.groups.map { it.id }.toSet()),"own-catalog-again")
    target = Tracker(reopen(target.store),clock);importer = SelectiveTransfer(target)
    assertEquals(TransferGroupState.UNCHANGED,importer.apply(importer.plan(value,value.groups.map { it.id }.toSet()),"own-catalog-repeat").groups.single().state)
    // COPY + explicit compaction must retain roots and reconstruct the original source package.
    var copied = Tracker(create(),clock);copied.saveCard(copied.snapshot().token,Card(card.id,"Чужой объект с тем же ID"))
    var copyImporter = SelectiveTransfer(copied);val groupId = value.groups.single().id
    val copyPlan = copyImporter.plan(value,setOf(groupId),mapOf(groupId to TransferConflictPolicy.COPY));assertEquals(1,copyPlan.conflicts.size)
    copyImporter.apply(copyPlan,"own-copy")
    val local = copied.data().transfer.origins.single().localGroup.catalogRoots
    assertFalse(card.id in local.cardIds);assertEquals(2,copied.data().cards.size)
    val retention = copied.previewTransferRetention(TransferRetentionSelection(operationIds = setOf("own-copy")))
    assertTrue(retention.canApply);copied.applyTransferRetention(retention,"compact-own-copy",retention.requiredAcknowledgements)
    copied = Tracker(reopen(copied.store),clock);copyImporter = SelectiveTransfer(copied)
    assertEquals(local,copied.data().transfer.origins.single().compaction!!.catalogRoots)
    val forward = copyImporter.export(TransferSelection(cardIds = local.cardIds,typeIds = local.typeIds))
    assertEquals(value.groups.single(),forward.groups.single())
    assertEquals(TransferGroupState.UNCHANGED,copyImporter.apply(copyImporter.plan(forward,setOf(groupId)),"forwarded-repeat").groups.single().state)
    val changedCard = source.data().cards.single().copy(name = "Исправленное личное название")
    source.saveCard(source.snapshot().token,changedCard)
    val changed = SelectiveTransfer(source).export(chosen)
    assertEquals(value.groups.single().originId,changed.groups.single().originId);assertNotEquals(value.groups.single().hash,changed.groups.single().hash)
    val conflict = copyImporter.plan(changed,setOf(groupId));assertEquals(1,conflict.conflicts.size)
    assertEquals(TransferGroupState.REJECTED,copyImporter.apply(conflict,"changed-source-blocked").groups.single().state)
    // Preparation committed, group application interrupted: no partially installed catalog.
    val base = create();var writes = 0;var fail = true
    val fault = object: StateStore by base {
        override fun <T> transaction(block: (Dataset)->Pair<Dataset,T>): T {
            writes++;if(fail && writes == 2) throw IOException("fixture before catalog group commit")
            return base.transaction(block)
        }
    }
    val interrupted = Tracker(fault,clock);val transfer = SelectiveTransfer(interrupted)
    rejected(ErrorCode.STORAGE) { transfer.apply(transfer.plan(value,setOf(groupId)),"resume-own-catalog") }
    assertTrue(interrupted.data().cards.isEmpty());assertEquals(TransferGroupState.PENDING,interrupted.data().transfer.operations.single().groups.single().state)
    fail = false;val resumed = SelectiveTransfer(Tracker(reopen(base),clock)).resume("resume-own-catalog")
    assertEquals(TransferGroupState.APPLIED,resumed.groups.single().state)
}
