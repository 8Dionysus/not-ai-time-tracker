package dev.notai.tracker

import kotlin.test.*

/** Exercise real CSV append → selective COPY → reopen → CSV replay on each store backend. */
fun csvCopiedOriginScenario(createStore: ()->StateStore,reopen: (StateStore)->StateStore) {
    val clock = FakeClock();val source = Tracker(createStore(),clock)
    source.saveType(source.snapshot().token,ActivityType("copy-csv-type","Source original",definition = "Source meaning"))
    val csv = "id,activity,start,end,note\nrecord-1,Foreign original,2026-10-09T10:00:00Z,2026-10-09T10:01:00Z,Literal source note\n"
    val mapping = CsvMapping("source-workspace","activity","start",end = "end",note = "note",id = "id")
    val rows = HistoryTransfer.parseCsv(csv,mapping)
    val history = HistoryTransfer(source,MemoryRollback())
    history.apply(history.preview(rows,rows.activities.associateWith { source.typeSelection("copy-csv-type") }))
    val originalSession = source.data().sessions.single()
    val pkg = SelectiveTransfer(source).export(TransferSelection(sessionIds = setOf(originalSession.id)))
    var target = Tracker(createStore(),clock)
    // This semantic type conflict requires COPY even though the target has no matching session ID.
    target.saveType(target.snapshot().token,ActivityType("copy-csv-type","Target original",definition = "Different target meaning"))
    val selective = SelectiveTransfer(target);val groups = pkg.groups.map { it.id }.toSet()
    val plan = selective.plan(pkg,groups,groups.associateWith { TransferConflictPolicy.COPY })
    assertTrue(plan.conflicts.isNotEmpty())
    assertEquals(TransferGroupState.APPLIED,selective.apply(plan,"copy-csv-origin").groups.single().state)
    val copied = target.data().sessions.single()
    assertNotEquals(originalSession.id,copied.id)
    assertEquals(originalSession.importOrigin,copied.importOrigin)
    target = Tracker(reopen(target.store),clock)
    val before = target.data();val reimport = HistoryTransfer(target,MemoryRollback())
    val replayRows = HistoryTransfer.parseCsv(csv+"\n",mapping)
    assertNotEquals(copied.importOrigin!!.fileHash,replayRows.rows.single().origin.fileHash)
    val replay = reimport.preview(replayRows,emptyMap()) // A known replay needs no new classification choice.
    assertTrue(replay.canApply);assertEquals(1,replay.duplicates);assertEquals(0,replay.added)
    val result = reimport.apply(replay)
    assertEquals(0,result.added);assertEquals(1,result.duplicates);assertNull(result.rollbackReference)
    assertEquals(before,target.data())
    val changed = reimport.preview(HistoryTransfer.parseCsv(csv.replace("Literal source note","Changed source note"),mapping),emptyMap())
    assertFalse(changed.canApply);assertEquals(ImportIssueCode.SOURCE_ID_CONFLICT,changed.errorIssues.single().code)
    val rejected = assertFailsWith<CoreError> { reimport.apply(changed) }
    assertEquals(ImportIssueCode.PREVIEW_HAS_ERRORS,rejected.importIssue!!.code)
    val otherNamespace = reimport.preview(HistoryTransfer.parseCsv(csv,mapping.copy(namespace = "other-workspace")),emptyMap())
    assertEquals(0,otherNamespace.duplicates)
    assertEquals(ImportIssueCode.MAPPING_REQUIRED,otherNamespace.errorIssues.single().code)
    assertEquals(before,target.data())
    assertEquals("Target original",target.data().types.single { it.id == "copy-csv-type" }.name)
    // Explicitly copied source versions can coexist; replay must not silently choose one hash.
    val revisedSource = Tracker(createStore(),clock)
    revisedSource.saveType(revisedSource.snapshot().token,ActivityType("copy-csv-type","Source original",definition = "Source meaning"))
    val revisedRows = HistoryTransfer.parseCsv(csv.replace("10:00:00","11:00:00").replace("10:01:00","11:01:00").replace("Literal source note","Revised source note"),mapping)
    val revisedHistory = HistoryTransfer(revisedSource,MemoryRollback())
    revisedHistory.apply(revisedHistory.preview(revisedRows,revisedRows.activities.associateWith { revisedSource.typeSelection("copy-csv-type") }))
    val revisedPackage = SelectiveTransfer(revisedSource).export(TransferSelection(sessionIds = revisedSource.data().sessions.map { it.id }.toSet()))
    val revisedGroups = revisedPackage.groups.map { it.id }.toSet();val revisedSelective = SelectiveTransfer(target)
    val revised = revisedSelective.apply(revisedSelective.plan(revisedPackage,revisedGroups,revisedGroups.associateWith { TransferConflictPolicy.COPY }),"copy-revised-csv-origin")
    assertEquals(TransferGroupState.APPLIED,revised.groups.single().state)
    assertEquals(2,target.data().sessions.size)
    val versions = target.data();val ambiguous = HistoryTransfer(target,MemoryRollback()).preview(rows,emptyMap())
    assertFalse(ambiguous.canApply);assertEquals(ImportIssueCode.SOURCE_ID_CONFLICT,ambiguous.errorIssues.single().code)
    assertEquals(versions,target.data())
}
