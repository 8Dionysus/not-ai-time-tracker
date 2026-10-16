package dev.notai.tracker

import kotlin.test.*

private const val meaningCsv = "id,activity,alt_activity,category,alt_category,note,alt_note,start,end,duration\nx,A,B,Cat A,Cat B,Note A,Note B,2026-10-01T00:00:00Z,2026-10-01T00:00:01Z,1\n"
private val meaningMapping = CsvMapping("csv-meaning","activity","start",end = "end",duration = "duration",durationFormat = DurationFormat.SECONDS,
    category = "category",note = "note",id = "id",clockToleranceMs = 1000)
private const val legacyMeaningHash = "3816a92086798b087a7cbdc0f4fc72069cf08209beb9b298d0fe2075583863b2"

fun csvInterpretationConflict(create: () -> StateStore,reopen: (StateStore) -> StateStore,changed: String) {
    val t = Tracker(create(),FakeClock());t.saveType(t.snapshot().token,ActivityType("csv-type","CSV"))
    val rows = HistoryTransfer.parseCsv(meaningCsv,meaningMapping);assertTrue(rows.errors.isEmpty())
    val transfer = HistoryTransfer(t,MemoryRollback())
    transfer.apply(transfer.preview(rows,rows.activities.associateWith { Classification(name = it.activity,activityTypeId = "csv-type",details = mapOf("category" to it.category)) }))
    val opened = Tracker(reopen(t.store),t.clock);val before = opened.data()
    val mapping = when(changed) {
        "duration" -> meaningMapping.copy(durationFormat = DurationFormat.MILLISECONDS)
        "note" -> meaningMapping.copy(note = "alt_note")
        "activity" -> meaningMapping.copy(activity = "alt_activity")
        "category" -> meaningMapping.copy(category = "alt_category")
        else -> error(changed)
    }
    val reinterpreted = HistoryTransfer.parseCsv(meaningCsv,mapping);assertTrue(reinterpreted.errors.isEmpty())
    val preview = HistoryTransfer(opened,MemoryRollback()).preview(reinterpreted,emptyMap())
    assertFalse(preview.canApply,"Changed $changed interpretation must conflict instead of deduplicating")
    assertEquals(0,preview.duplicates);assertEquals(ImportIssueCode.SOURCE_ID_CONFLICT,preview.errorIssues.single().code)
    assertEquals(before,opened.data())
}

fun csvLegacyInterpretationReplay(create: () -> StateStore,reopen: (StateStore) -> StateStore) {
    val t = Tracker(create(),FakeClock());t.saveType(t.snapshot().token,ActivityType("csv-type","CSV"))
    val rows = HistoryTransfer.parseCsv(meaningCsv,meaningMapping);val transfer = HistoryTransfer(t,MemoryRollback())
    transfer.apply(transfer.preview(rows,rows.activities.associateWith { Classification(name = it.activity,activityTypeId = "csv-type",details = mapOf("category" to it.category)) }))
    // Isolated legacy fixture: the frozen receipt was produced by the old raw/start/end/zone algorithm.
    val legacy = t.data().let { it.copy(sessions = it.sessions.map { s -> s.copy(importOrigin = s.importOrigin!!.copy(rowHash = legacyMeaningHash)) }) }
    val store = create();store.transaction { legacy to Unit };var opened = Tracker(reopen(store),t.clock)
    fun replay() {
        val before = opened.data();val h = HistoryTransfer(opened,MemoryRollback())
        val preview = h.preview(HistoryTransfer.parseCsv(meaningCsv+"\n",meaningMapping),emptyMap())
        assertTrue(preview.canApply,preview.errors.toString());assertEquals(1,preview.duplicates);assertEquals(0,preview.added)
        assertNull(h.apply(preview).rollbackReference);assertEquals(before,opened.data())
        assertEquals(legacyMeaningHash,opened.data().sessions.single().importOrigin!!.rowHash)
        for(mapping in listOf(meaningMapping.copy(durationFormat = DurationFormat.MILLISECONDS),meaningMapping.copy(note = "alt_note"))) {
            val conflict = h.preview(HistoryTransfer.parseCsv(meaningCsv,mapping),emptyMap())
            assertFalse(conflict.canApply);assertEquals(ImportIssueCode.SOURCE_ID_CONFLICT,conflict.errorIssues.single().code)
        }
        assertEquals(before,opened.data())
    }
    replay()
    val pkg = SelectiveTransfer(opened).export(TransferSelection(sessionIds = opened.data().sessions.map { it.id }.toSet()))
    val target = Tracker(create(),t.clock);target.saveType(target.snapshot().token,ActivityType("csv-type","Conflicting local meaning"))
    val importer = SelectiveTransfer(target);val groups = pkg.groups.map { it.id }.toSet()
    assertTrue(importer.apply(importer.plan(pkg,groups,groups.associateWith { TransferConflictPolicy.COPY }),"legacy-csv-copy").complete)
    assertNotEquals(legacy.sessions.single().id,target.data().sessions.single().id)
    opened = Tracker(reopen(target.store),t.clock);replay()
    val backup = Backups(opened,MemoryRollback()).export();val restored = Tracker(create(),t.clock)
    Backups(restored,MemoryRollback()).let { it.apply(it.prepare(backup)) }
    opened = Tracker(reopen(restored.store),t.clock);replay()
}
