package dev.notai.tracker

import kotlinx.serialization.json.*
import kotlin.test.*

fun startBackfillPreservesObservedTime(createStore: ()->StateStore,reopen: (StateStore)->StateStore) {
    val clock = FakeClock();var t = Tracker(createStore(),clock)
    t.start(t.snapshot().token);clock.advance(10*60_000)
    t.stop(t.snapshot().token);val previous = t.data().segments.single()
    clock.advance(10*60_000)
    t.saveType(t.snapshot().token,ActivityType("backfill-type","Original classification"))
    t.start(t.snapshot().token,t.typeSelection("backfill-type"),"Literal note")
    clock.advance(5*60_000)
    val before = t.data();val target = t.snapshot().timerTarget
    val own = before.sessions.last();val observed = before.segments.last()
    val preview = t.previewStartAtPreviousEnd(t.snapshot().token,own.id)
    assertEquals(before,t.data());assertEquals(previous.id,preview.previousStoppedEnd!!.segmentId)
    assertEquals(10*60_000L,preview.addedMs)
    assertEquals(StartBackfillWarning.USER_CONFIRMED_TIME_ADDED,preview.requiredWarning)
    assertEquals(previous.end,preview.proposedSegment.start);assertEquals(observed.start,preview.proposedSegment.end)
    clock.advance(1000) // confirmation time is the apply time; no elapsed-anchor rebasing.
    val applied = t.applyStartBackfill(preview)
    assertTrue(applied.undoAvailable);assertEquals(setOf(own.id),applied.changedSessionIds)
    val after = t.data();val prefix = after.segments.single { it.id == preview.proposedSegment.id }
    assertEquals(before.sessions,after.sessions);assertEquals(before.segments,after.segments.filter { it.id != prefix.id })
    assertEquals(own.id,prefix.sessionId);assertEquals(observed.selection,prefix.selection)
    assertEquals(ManualTimeConfirmation(clock.wall),prefix.manualConfirmation)
    assertNull(prefix.anchor);assertEquals(target,t.snapshot().timerTarget)
    assertEquals(15*60_000L+1000,t.snapshot().trustedDurationMs)
    val report = t.view().report(previous.start,clock.wall+1)
    assertEquals(prefix.manualConfirmation,report.rows.single { it.segmentId == prefix.id }.manualConfirmation)
    assertNull(report.rows.single { it.segmentId == observed.id }.manualConfirmation)
    assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> { t.applyStartBackfill(preview) }.code)
    t.undo(t.snapshot().token)
    assertEquals(before.sessions,t.data().sessions);assertEquals(before.segments,t.data().segments)
    assertEquals(5*60_000L+1000,t.snapshot().trustedDurationMs);assertEquals(target,t.snapshot().timerTarget)
    t.applyStartBackfill(t.previewStartAtPreviousEnd(t.snapshot().token,own.id))
    val persisted = t.data();t = Tracker(reopen(t.store),clock);assertEquals(persisted,t.data())
    t.pause(t.snapshot().token)
    assertEquals(Mode.PAUSED,t.snapshot().mode)
    // A paused ordinary session can be extended explicitly without changing its status.
    assertEquals(ErrorCode.OVERLAP,assertFailsWith<CoreError> {
        t.previewStartBackfill(t.snapshot().token,own.id,previous.start-1000)
    }.code)
}

fun startBackfillRefusalsAreAtomic(createStore: ()->StateStore) {
    val clock = FakeClock();val t = Tracker(createStore(),clock)
    t.start(t.snapshot().token);val id = t.data().sessions.single().id;val start = clock.wall
    val before = t.data()
    assertEquals(ErrorCode.INVALID_INPUT,assertFailsWith<CoreError> { t.previewStartAtPreviousEnd(t.snapshot().token,id) }.code)
    for(invalid in listOf(-1L,start,start+1)) {
        assertEquals(ErrorCode.INVALID_INTERVAL,assertFailsWith<CoreError> { t.previewStartBackfill(t.snapshot().token,id,invalid) }.code)
        assertEquals(before,t.data())
    }
    val preview = t.previewStartBackfill(t.snapshot().token,id,start-1000)
    val other = Tracker(t.store,clock)
    assertEquals(ErrorCode.INVALID_INPUT,assertFailsWith<CoreError> { other.applyStartBackfill(preview) }.code)
    clock.advance(1000);t.pause(t.snapshot().token)
    val paused = t.data()
    assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> { t.applyStartBackfill(preview) }.code)
    assertEquals(paused,t.data())
    t.applyStartBackfill(t.previewStartBackfill(t.snapshot().token,id,start-1000))
    assertEquals(Mode.PAUSED,t.snapshot().mode)
    assertEquals(2000L,t.snapshot().trustedDurationMs)
    val focus = Tracker(createStore(),clock)
    focus.startFocus(focus.snapshot().token,plan = FocusPlan(1000,1000))
    val focusBefore = focus.data()
    assertFailsWith<CoreError> { focus.previewStartBackfill(focus.snapshot().token,focusBefore.sessions.single().id,clock.wall-1000) }
    assertEquals(focusBefore,focus.data())
    val recovery = Tracker(createStore(),clock)
    recovery.start(recovery.snapshot().token);clock.boot = "new-boot"
    val recoveryBefore = recovery.data()
    assertEquals(ErrorCode.RECOVERY_REQUIRED,assertFailsWith<CoreError> {
        recovery.previewStartBackfill(recovery.snapshot().token,recoveryBefore.sessions.single().id,clock.wall-1000)
    }.code)
    assertEquals(recoveryBefore,recovery.data())
}

fun startBackfillArchiveRoundTrip(createStore: ()->StateStore,reopen: (StateStore)->StateStore) {
    val clock = FakeClock();val source = Tracker(createStore(),clock)
    source.start(source.snapshot().token);val id = source.data().sessions.single().id
    source.applyStartBackfill(source.previewStartBackfill(source.snapshot().token,id,clock.wall-1000))
    clock.advance(1000);source.stop(source.snapshot().token)
    source.settings(source.snapshot().token,source.data().settings.copy(longRecordReminder = LongRecordPreference(true,321,17)))
    val expected = source.data();val backups = Backups(source,MemoryRollback());val json = backups.export()
    val decoded = Backups.decode(json);assertEquals(13,decoded.formatVersion)
    assertEquals(expected.segments,decoded.data.segments)
    assertEquals(LongRecordPreference(true,321,17),decoded.data.settings.longRecordReminder)
    val bytes = java.io.ByteArrayOutputStream();backups.write(bytes)
    assertEquals(decoded.data,StreamingBackup.read { bytes.toByteArray().inputStream() }.data)
    val target = Tracker(createStore(),clock);val restore = Backups(target,MemoryRollback())
    val preview = restore.prepare(json);assertTrue(target.data().sessions.isEmpty())
    restore.apply(preview);assertEquals(expected.segments,target.data().segments)
    assertEquals(expected.segments,reopen(target.store).read().segments)
    for(v in 1..12) {
        val root = codec.parseToJsonElement(json).jsonObject
        val downgraded = JsonObject(root+("formatVersion" to JsonPrimitive(v))).toString()
        assertEquals(ErrorCode.UNSUPPORTED_IMPORT,assertFailsWith<CoreError> { Backups.decode(downgraded) }.code)
        assertEquals(ErrorCode.UNSUPPORTED_IMPORT,assertFailsWith<CoreError> { StreamingBackup.read { downgraded.byteInputStream() } }.code)
    }
    val legacyRoot = codec.parseToJsonElement(json).jsonObject
    val legacyData = legacyRoot.getValue("data").jsonObject
    val legacySettings = legacyData.getValue("settings").jsonObject.filterKeys { it != "longRecordReminder" }
    val legacy12 = JsonObject(legacyRoot+("formatVersion" to JsonPrimitive(12))+
        ("data" to JsonObject(legacyData+("settings" to JsonObject(legacySettings))))).toString()
    assertEquals(expected.segments,Backups.decode(legacy12).data.segments)
    assertEquals(expected.segments,StreamingBackup.read { legacy12.byteInputStream() }.data.segments)
    assertEquals(LongRecordPreference(),Backups.decode(legacy12).data.settings.longRecordReminder)
    val future = expected.copy(segments = expected.segments.map { segment ->
        if(segment.manualConfirmation == null) segment else segment.copy(manualConfirmation = ManualTimeConfirmation(clock.wall+1))
    })
    val futureJson = codec.encodeToString(Backup(asOf = clock.wall,data = future))
    assertEquals(ErrorCode.UNSUPPORTED_IMPORT,assertFailsWith<CoreError> { Backups.decode(futureJson) }.code)
    assertEquals(ErrorCode.UNSUPPORTED_IMPORT,assertFailsWith<CoreError> { StreamingBackup.read { futureJson.byteInputStream() } }.code)
    val csv = HistoryTransfer.export(expected)
    assertEquals(ErrorArgument.Count(1),csv.issues.single { it.code == ImportIssueCode.SESSION_GROUPING_LOSS }.arguments["manuallyConfirmedIntervalCount"])
    val selective = SelectiveTransfer(source);val pack = selective.export(TransferSelection(sessionIds = setOf(id)))
    assertEquals(3,pack.formatVersion);assertEquals(3,pack.minimumReader)
    val imported = Tracker(createStore(),clock);val transfer = SelectiveTransfer(imported)
    transfer.apply(transfer.plan(pack,pack.groups.map { it.id }.toSet()),"backfill-transfer")
    assertEquals(expected.segments,imported.data().segments)
    assertEquals(expected.segments,reopen(imported.store).read().segments)
    for(v in 1..2) assertEquals(ErrorCode.UNSUPPORTED_IMPORT,assertFailsWith<CoreError> {
        transfer.inspect(pack.copy(formatVersion = v,minimumReader = v))
    }.code)
    // Retained origin/journal payloads also cannot be smuggled into an old full archive.
    imported.deleteSession(imported.snapshot().token,id)
    assertTrue(imported.data().segments.isEmpty());assertTrue(imported.data().hasManualTimeConfirmation())
    val retained = Backups(imported,MemoryRollback()).export()
    val downgraded = JsonObject(codec.parseToJsonElement(retained).jsonObject+("formatVersion" to JsonPrimitive(11))).toString()
    assertEquals(ErrorCode.UNSUPPORTED_IMPORT,assertFailsWith<CoreError> { Backups.decode(downgraded) }.code)
    assertEquals(ErrorCode.UNSUPPORTED_IMPORT,assertFailsWith<CoreError> { StreamingBackup.read { downgraded.byteInputStream() } }.code)
}
