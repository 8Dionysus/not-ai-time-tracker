package dev.notai.tracker

import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class FakeClock(var wall: Long = Instant.parse("2026-10-10T23:50:00Z").toEpochMilli(),var elapsed: Long = 100_000,var boot: String = "boot-1",var process: String = "process-1"): Clock {
    override fun now() = Moment(wall,elapsed,boot,process)
    fun advance(ms: Long) { wall += ms; elapsed += ms }
}
class MemoryRollback: RollbackStore {
    val saved = mutableMapOf<String,String>()
    override fun save(json: String) = newId().also { saved[it] = json }
    override fun read(reference: String) = saved.getValue(reference)
}
/** The SAME three scenarios run on in-memory domain storage and on disk-backed Room/SQLite. */
abstract class ScenarioContract {
    abstract fun createStore(): StateStore
    open fun reopen(store: StateStore): StateStore = store
    private fun expect(code: ErrorCode,block: ()->Unit) { assertEquals(code,assertFailsWith<CoreError>(block = block).code) }

    open fun storageWorkload() {}
    open fun surfaceActions() {}
    open fun assertTransferFailure(transfer: HistoryTransfer,preview: HistoryPreview,tracker: Tracker) {}
    open fun backupDocumentInput() {}

    @Test fun timerPath() {
        choiceLifecycle()
        focusLifecycle()
        goalsLifecycle()
        checkedTimerCombinations()
        surfaceActions()
        storageWorkload()
        historyTransfer()
        formatInspection()
        val clock = FakeClock(); var tracker = Tracker(createStore(),clock)
        fun token() = tracker.snapshot().token
        val card = Card(name = "Язык",activityTypeId = "language",details = mapOf("language" to "es"),tags = setOf("learning","health"))
        tracker.saveCard(token(),card)
        tracker.start(token()); val originalStart = tracker.snapshot().active!!.start
        clock.advance(60_000); tracker.labelCurrent(token(),card.classification())
        assertEquals(originalStart,tracker.snapshot().active!!.start)
        assertEquals(60_000,tracker.snapshot().trustedDurationMs)
        tracker.pause(token()); clock.advance(30_000)
        assertEquals(60_000,tracker.snapshot().trustedDurationMs)
        tracker.resume(token()); clock.advance(60_000)
        tracker = Tracker(reopen(tracker.store),clock) // close/reopen disk DB in Android contract
        assertEquals(120_000,tracker.snapshot().trustedDurationMs)
        val a = token(); tracker.switch(a,Classification()); val b = token()
        val staleStop = assertFailsWith<CoreError> { tracker.stop(a) }
        assertEquals(ErrorCode.STALE,staleStop.code); assertEquals(b,staleStop.currentState!!.token); assertEquals(b,token())
        clock.advance(60_000); tracker.switchedEarlier(token(),clock.wall-30_000,card.classification())
        assertEquals(30_000,tracker.snapshot().trustedDurationMs)
        val c = token(); val staleUndo = assertFailsWith<CoreError> { tracker.undo(b) }
        assertEquals(ErrorCode.STALE,staleUndo.code); assertEquals(c,staleUndo.currentState!!.token); assertEquals(c,token())
        tracker.stop(token()); expect(ErrorCode.STALE) { tracker.stop(c) }
        val stopped = tracker.data().sessions.last()
        tracker.repeat(token(),stopped.id); assertEquals(tracker.data().classify(card.classification()),tracker.snapshot().session!!.selection)
        assertNotEquals(stopped.id,tracker.snapshot().session!!.id)
        clock.advance(10_000)
        val detail = card.classification().copy(details = mapOf("language" to "es","activity" to "listening"))
        tracker.changeDetails(token(),detail); clock.advance(10_000); tracker.stop(token())
        val favorite = Favorite(label = "Испанский",selection = detail)
        tracker.saveFavorite(token(),favorite)
        val repeatId = tracker.data().sessions.last().id
        tracker.saveCard(token(),card.copy(name = "Renamed and archived",archived = true,details = mapOf("activity" to "reading")))
        expect(ErrorCode.INVALID_INPUT) { tracker.start(token(),card.classification()) }
        tracker.startFavorite(token(),favorite.id)
        assertEquals(tracker.data().classify(detail),tracker.snapshot().session!!.selection)
        clock.advance(1000); tracker.stop(token()); tracker.repeat(token(),repeatId)
        assertEquals(tracker.data().classify(detail),tracker.snapshot().session!!.selection) // archived card keeps saved parameters
        assertEquals("Язык",tracker.snapshot().session!!.selection.name)
        clock.advance(10_000)
        val beforeArchivedSwitch = tracker.data()
        expect(ErrorCode.INVALID_INPUT) { tracker.switchedEarlier(token(),clock.wall-1000,card.classification()) }
        assertEquals(beforeArchivedSwitch,tracker.data())
        // Competing commands share a revision: exactly one wins, the second is explicitly stale.
        val shared = token(); val gate = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val calls = (1..2).map { pool.submit<ErrorCode?> { gate.await(); try { tracker.switch(shared,Classification()); null } catch (e: CoreError) { e.code } } }
            gate.countDown(); val outcomes = calls.map { it.get() }
            assertEquals(1,outcomes.count { it == null }); assertEquals(1,outcomes.count { it == ErrorCode.STALE })
        } finally { pool.shutdownNow() }
        val beforeJump = tracker.snapshot().trustedDurationMs
        clock.advance(20_000); clock.wall += 3_600_000
        assertEquals(Mode.RECOVERY,tracker.snapshot().mode)
        assertEquals(beforeJump+20_000,tracker.snapshot().trustedDurationMs)
        expect(ErrorCode.RECOVERY_REQUIRED) { tracker.stop(token()) }
        tracker.resolveTail(token(),clock.wall-20_000,clock.wall)
        tracker.start(token()); clock.advance(10_000); clock.boot = "boot-2"; clock.elapsed = 50
        assertEquals(Mode.RECOVERY,tracker.snapshot().mode)
        tracker.resolveTail(token(),null,null); assertEquals(Mode.IDLE,tracker.snapshot().mode)
        validate(tracker.data())
        val beforeInvalidClock = tracker.data()
        clock.wall = Long.MIN_VALUE
        expect(ErrorCode.INVALID_INTERVAL) { tracker.snapshot() }
        assertEquals(beforeInvalidClock,tracker.data())
        expect(ErrorCode.INVALID_INTERVAL) { materialize(Segment(sessionId = "range",start = 1,anchor = Anchor("same",0)),Moment(1000,Long.MAX_VALUE,"same")) }
    }

    private fun formatInspection() {
        val clock = FakeClock(); val tracker = Tracker(createStore(),clock)
        val transfer = HistoryTransfer(tracker,MemoryRollback())
        val first = "id,activity,start,end,note\n1,Read,2026-10-08T08:00:00Z,2026-10-08T08:20:00Z,Chapter one\n"
        val second = "код;дело;начало;секунды;комментарий\n2;Walk;09.10.2026 10:00:00;600;Park\n"
        val a = HistoryFiles.inspect((first+"\n").toByteArray())
        assertEquals(ImportFormat.DELIMITED_TEXT,a.format); assertEquals(',',a.candidates.single().delimiter)
        assertEquals(listOf("activity"),a.candidates.single().suggestions[ImportField.ACTIVITY])
        assertEquals("Read",a.candidates.single().examples.single()[1])
        val fa = a.confirmCsv(',',CsvMapping("source-a","activity","start",end = "end",note = "note",id = "id"),CsvTimeChoice(null,"UTC"),null)
        val pa = transfer.preview(fa,fa.activities.associateWith { Classification(name = it.activity) })
        assertTrue(pa.canApply); transfer.apply(pa)
        val b = HistoryFiles.inspect(second.toByteArray())
        assertEquals(';',b.candidates.single().delimiter)
        val bm = CsvMapping("source-b","дело","начало",duration = "секунды",note = "комментарий",id = "код",delimiter = ';')
        expect(ErrorCode.INVALID_INPUT) { b.confirmCsv(';',bm,CsvTimeChoice("dd.MM.uuuu HH:mm:ss","Europe/Berlin"),null) }
        val fb = b.confirmCsv(';',bm,CsvTimeChoice("dd.MM.uuuu HH:mm:ss","Europe/Berlin"),DurationFormat.SECONDS)
        val pb = transfer.preview(fb,fb.activities.associateWith { Classification(name = it.activity) })
        assertTrue(pb.canApply); transfer.apply(pb)
        assertEquals(1_800_000L,tracker.data().segments.sumOf { it.durationMs })
        assertEquals(0,transfer.apply(transfer.preview(fb,emptyMap())).added)
        assertEquals(2,tracker.data().sessions.size)
        val ambiguous = HistoryFiles.inspect("activity,start;end\nRead,one;two\n".toByteArray())
        assertEquals(setOf(',', ';'),ambiguous.candidates.map { it.delimiter }.toSet())
        assertEquals(1,HistoryFiles.inspect("activity,start;end\nRead,one;two\n".toByteArray(),';').candidates.size)
        val columns = HistoryFiles.inspect("activity,start,started,duration\nRead,x,y,60\n".toByteArray())
        assertEquals(listOf("start","started"),columns.candidates.single().suggestions[ImportField.START])
        val dates = HistoryFiles.inspect("activity;start;duration\nRead;03/04/2026 10:00:00;60\n".toByteArray())
        val dm = CsvMapping("dates","activity","start",duration = "duration")
        val dayFirst = dates.confirmCsv(';',dm,CsvTimeChoice("dd/MM/uuuu HH:mm:ss","UTC"),DurationFormat.SECONDS)
        val monthFirst = dates.confirmCsv(';',dm,CsvTimeChoice("MM/dd/uuuu HH:mm:ss","UTC"),DurationFormat.SECONDS)
        assertEquals(Instant.parse("2026-04-03T10:00:00Z").toEpochMilli(),dayFirst.rows.single().start)
        assertEquals(Instant.parse("2026-03-04T10:00:00Z").toEpochMilli(),monthFirst.rows.single().start)
        expect(ErrorCode.INVALID_INPUT) { dates.confirmCsv(';',dm,CsvTimeChoice("dd/MM/uuuu HH:mm:ss",""),DurationFormat.SECONDS) }
        assertEquals(ImportFormat.UNSUPPORTED,HistoryFiles.inspect("{\"data\":[]}".toByteArray()).format)
        assertEquals(ImportFormat.UNSUPPORTED,HistoryFiles.inspect(byteArrayOf(0xFF.toByte())).format)
        val backup = Backups(tracker,MemoryRollback()).export()
        val json = HistoryFiles.inspect(backup.toByteArray())
        assertEquals(ImportFormat.BACKUP_JSON,json.format)
        val receiver = Tracker(createStore(),clock); val restoration = Backups(receiver,MemoryRollback())
        restoration.apply(json.prepareRestore(restoration))
        assertEquals(tracker.data().sessions,receiver.data().sessions)
        // Inspection is only a sample: a malformed later row is still rejected on confirmation.
        val damaged = first + (2..7).joinToString("") { "$it,Read,2026-10-08T08:00:00Z,2026-10-08T08:20:00Z,note\n" } + "\"broken"
        val sample = HistoryFiles.inspect(damaged.toByteArray())
        assertEquals(ImportFormat.DELIMITED_TEXT,sample.format)
        expect(ErrorCode.UNSUPPORTED_IMPORT) { sample.confirmCsv(',',CsvMapping("bad","activity","start",end = "end"),CsvTimeChoice(null,"UTC"),null) }
    }

    private fun choiceLifecycle() {
        val clock = FakeClock(); val t = Tracker(createStore(),clock)
        val root = java.io.File(requireNotNull(System.getProperty("tracker.samples"))).parentFile!!
        val full = codec.decodeFromString<VocabularyData>(root.resolve("evidence/full-projection/runtime-vocabulary.json").readText())
        t.addCatalog(t.snapshot().token,full)
        val allTypes = ChoiceQuery(kinds = setOf(ChoiceKind.TYPE),includeArchived = true)
        val view = t.choices()
        assertEquals(ChoiceIndexStats(0,0,0),view.indexStats())
        assertEquals(0,view.suggestions().total); assertEquals(ChoiceIndexStats(0,0,0),view.indexStats())
        val started = System.nanoTime()
        var page = view.search(allTypes,pageSize = 37); val firstCursor = page.next!!
        val ids = mutableListOf<String>(); val total = page.total
        while(true) { ids += page.items.map { it.entry.sourceId }; if(page.next == null) break; page = view.search(allTypes,page.next,37) }
        val indexingAndPaging = System.nanoTime()-started
        assertEquals(t.data().types.size,total); assertEquals(total,ids.distinct().size)
        assertEquals(t.data().types.map { it.id }.toSet(),ids.toSet()) // full 1918 imported types + seed, no top-N loss
        val stamp = view.indexStats(); val typed = System.nanoTime()
        repeat(50) { view.search(allTypes.copy(text = listOf("a","work","read","язык","sport")[it%5]),pageSize = 10) }
        val typingNs = System.nanoTime()-typed; assertEquals(stamp,view.indexStats())
        expect(ErrorCode.STALE) { view.search(allTypes.copy(text = "different"),firstCursor) }
        expect(ErrorCode.INVALID_INPUT) { view.search(allTypes,pageSize = 101) }
        val group = ActivityType("choice-group","Раздел",selectable = false)
        val child = ActivityType("choice-type","Кофе",parentId = group.id,aliases = listOf("CAFÉ","Coffee"))
        t.addCatalog(t.snapshot().token,VocabularyData(listOf(group,child,child.copy(id = "choice-sibling",parentId = null))))
        t.saveAxis(t.snapshot().token,Axis("choice-axis","Контекст",valueKind = ValueKind.FREE_LABEL))
        val card = Card(id = "choice-card",name = "Мой кофе",activityTypeId = child.id,details = mapOf("method" to "filter"),tags = setOf("study","home"),
            facets = listOf(Facet("choice-axis",text = "тихо")))
        t.saveCard(t.snapshot().token,card)
        val selection = t.selection(card.id)
        val favorite = Favorite(id = "choice-favorite",label = "Saved coffee",selection = selection)
        t.saveFavorite(t.snapshot().token,favorite)
        val index = t.choices()
        val alias = index.search(allTypes.copy(text = "cafe\u0301"))
        assertEquals(setOf(child.id,"choice-sibling"),alias.items.map { it.entry.sourceId }.toSet())
        assertTrue(alias.items.all { it.matches.single().field == MatchField.ALIAS && it.matches.single().strength == MatchStrength.EXACT })
        assertEquals("CAFÉ",alias.items.first().matches.single().text)
        val children = index.search(allTypes.copy(scope = CatalogScope.CHILDREN,parentId = group.id))
        assertEquals(listOf(child.id),children.items.map { it.entry.sourceId })
        assertEquals(listOf(group.id,child.id),children.items.single().entry.path.map { it.id })
        val groupRow = index.search(allTypes.copy(text = "Раздел")).items.single { it.entry.sourceId == group.id }.entry
        assertFalse(groupRow.canStart); assertEquals(ChoiceAvailability.GROUP,groupRow.availability)
        val a = t.start(t.snapshot().token,selection,"Private note"); clock.advance(100); t.pause(t.snapshot().token)
        t.resume(t.snapshot().token); clock.advance(100); t.stop(t.snapshot().token)
        t.repeat(t.snapshot().token,a.state.session!!.id); clock.advance(50); t.stop(t.snapshot().token)
        val last = t.data().segments.last()
        val window = HistoryWindow(clock.wall-10_000,clock.wall+1)
        val frequent = SuggestionQuery(true,SuggestionOrder.FREQUENT_RECORDED,window)
        var choices = t.choices(); var result = choices.suggestions(frequent)
        assertEquals(1,result.total); assertEquals(2,result.items.single().suggestion!!.sessionCount) // 3 segments, 2 sessions
        val stableVariant = result.items.single().entry.id
        assertEquals(selection,result.items.single().entry.selection); assertEquals("Private note",result.items.single().entry.note)
        val beforeRename = t.data().segments; val builds = choices.indexStats()
        t.saveCard(t.snapshot().token,card.copy(name = "Renamed card",details = mapOf("method" to "new")))
        choices = t.choices()
        val past = choices.search(ChoiceQuery("Renamed card",setOf(ChoiceKind.HISTORY))).items.single()
        assertEquals(stableVariant,past.entry.id); assertEquals(selection,past.entry.selection)
        assertEquals(MatchField.CURRENT_CARD_NAME,past.matches.single().field)
        assertEquals(builds.historyBuilds,choices.indexStats().historyBuilds)
        val saved = choices.search(ChoiceQuery(kinds = setOf(ChoiceKind.FAVORITE))).items.single().entry
        assertEquals(selection,saved.selection)
        val direct = choices.search(ChoiceQuery(kinds = setOf(ChoiceKind.CARD))).items.single().entry
        assertEquals("new",direct.selection.details["method"])
        t.saveType(t.snapshot().token,child.copy(name = "Новый вид",aliases = listOf("fresh-alias")))
        choices = t.choices()
        assertTrue(choices.search(ChoiceQuery("fresh-alias",setOf(ChoiceKind.HISTORY))).items.single().matches.any { it.field == MatchField.CURRENT_TYPE_ALIAS })
        assertEquals(beforeRename,t.data().segments)
        t.saveType(t.snapshot().token,child.copy(archived = true))
        choices = t.choices(); assertEquals(0,choices.suggestions(frequent).total)
        assertFalse(choices.search(ChoiceQuery("Кофе",setOf(ChoiceKind.CARD),includeArchived = true)).items.single().entry.canStart)
        t.saveType(t.snapshot().token,child.copy(selectable = false))
        assertEquals(0,t.choices().suggestions(frequent).total)
        t.saveType(t.snapshot().token,child)
        t.saveCard(t.snapshot().token,card.copy(archived = true)); assertEquals(0,t.choices().suggestions(frequent).total)
        t.saveCard(t.snapshot().token,card)
        t.correctSegment(t.snapshot().token,last.id,beforeRename.first().start-1_000,beforeRename.first().start-900,last.selection)
        result = t.choices().suggestions(SuggestionQuery(true)); assertEquals(stableVariant,result.items.single().entry.id)
        assertNotEquals(last.id,result.items.single().entry.sourceSegmentId)
        t.undo(t.snapshot().token); result = t.choices().suggestions(SuggestionQuery(true)); assertEquals(last.id,result.items.single().entry.sourceSegmentId)
        val beforeTick = t.data(); val reuse = t.choices(); reuse.search(allTypes); reuse.suggestions(frequent)
        val same = reuse.indexStats(); clock.advance(500)
        reuse.search(allTypes.copy(text = "coffee")); reuse.suggestions(frequent); assertEquals(same,reuse.indexStats()); assertEquals(beforeTick,t.data())
        t.start(t.snapshot().token); t.choices().suggestions(SuggestionQuery(true))
        assertEquals(same.historyBuilds,t.choices().indexStats().historyBuilds) // active session cannot invalidate completed-history index
        clock.advance(100); t.pause(t.snapshot().token); t.choices().suggestions(SuggestionQuery(true))
        assertEquals(same.historyBuilds,t.choices().indexStats().historyBuilds)
        t.stop(t.snapshot().token)
        assertEquals(2,t.choices().suggestions(SuggestionQuery(true)).total) // unclassified remains a valid option
        val json = Backups(t,MemoryRollback()).export(); val keep = t.data()
        val oldView = t.choices(); val oldCursor = oldView.search(allTypes,pageSize = 10).next!!
        t.startFavorite(t.snapshot().token,favorite.id); clock.advance(100); t.stop(t.snapshot().token)
        val backups = Backups(t,MemoryRollback()); backups.apply(backups.prepare(json))
        val restored = t.choices(); assertEquals(2,restored.suggestions(SuggestionQuery(true)).total)
        assertEquals(keep.segments,t.data().segments); assertEquals(keep.favorites,t.data().favorites)
        val error = assertFailsWith<CoreError> { restored.search(allTypes,oldCursor) }
        assertEquals(ErrorCode.STALE,error.code); assertEquals(restored.token,error.currentState!!.token)
        assertEquals(10,oldView.search(allTypes,oldCursor,10).items.size) // old immutable view can finish its own pages
        val row = restored.suggestions(frequent).items.first { it.entry.selection.cardId == card.id }.entry
        t.start(restored.token,row.selection,row.note)
        assertEquals(selection,t.snapshot().session!!.selection)
        assertEquals(selection.tags,t.snapshot().active!!.selection.tags); assertEquals(selection.facets,t.snapshot().active!!.selection.facets)
        assertEquals("Private note",t.snapshot().session!!.note)
        t.stop(t.snapshot().token)
        root.resolve("build/choices-summary.json").writeText("{\"catalogTypesPaged\":$total,\"catalogBuildsAcross50Queries\":${stamp.catalogBuilds},\"coldIndexAndAllPagesNs\":$indexingAndPaging,\"fiftyQueriesNs\":$typingNs,\"deviceTested\":false}")
    }

    private fun focusLifecycle() {
        val clock = FakeClock(); var t = Tracker(createStore(),clock)
        fun token() = t.snapshot().token
        fun focus() = t.snapshot().focus!!
        fun total() = t.goalProgress("focus-goal").actual
        t.saveGoal(token(),Goal(id = "focus-goal",name = "Focus work",metric = GoalMetric.DURATION_MS,target = 10_000,filter = Filter(activityTypeId = "language")))
        val selection = Classification(activityTypeId = "language")
        t.start(token()); expect(ErrorCode.INVALID_INPUT) { t.startFocus(token(),selection) }
        clock.advance(1_000); t.stop(token())
        t.startFocus(token(),selection,FocusPlan(10_000,5_000)); val originalSession = t.snapshot().session!!.id
        val stale = token(); clock.advance(4_000)
        assertEquals(6_000,focus().remainingMs); assertEquals(4_000,total())
        t.pause(token()); expect(ErrorCode.STALE) { t.focusSkip(stale) }
        clock.advance(3_000); assertEquals(4_000,total()); assertEquals(FocusMode.PAUSED,focus().mode)
        t = Tracker(reopen(t.store),clock); assertEquals(FocusMode.PAUSED,focus().mode)
        t.resumeFocus(token()); clock.advance(2_000)
        t.changeDetails(token(),selection.copy(details = mapOf("mode" to "listening")))
        val beforeTicks = t.data(); clock.advance(10_000)
        assertEquals(FocusMode.DUE,focus().mode); assertEquals(10_000,total())
        assertEquals(10_000,t.snapshot().trustedDurationMs); assertEquals(beforeTicks,t.data())
        val cue = focus().signalKey; clock.advance(5_000); assertEquals(cue,focus().signalKey)
        assertEquals(10_000,total()) // overdue idle is never work
        t.focusNext(token()); assertEquals(FocusPhase.BREAK,focus().phase); assertEquals(Mode.PAUSED,t.snapshot().mode)
        val firstWork = t.data().segments.first { it.sessionId == originalSession }
        t.settings(token(),t.data().settings.copy(focusPlan = FocusPlan(9_000,9_000)))
        assertEquals(FocusPlan(9_000,9_000),t.data().settings.focusPlan)
        assertEquals(FocusPlan(10_000,5_000),t.data().focus!!.plan) // generic settings changes defaults only
        t.configureFocus(token(),FocusPlan(3_000,2_000)); assertEquals(5_000,focus().durationMs)
        assertEquals(FocusPlan(3_000,2_000),t.data().focus!!.plan)
        clock.advance(1_000); t.pauseFocus(token()); clock.advance(5_000); assertEquals(1_000,focus().elapsedMs)
        t.resume(token()); clock.advance(4_000); assertEquals(FocusMode.DUE,focus().mode)
        assertEquals(10_000,total()); t.focusNext(token()); assertEquals(3_000,focus().durationMs)
        assertEquals(originalSession,t.snapshot().session!!.id)
        clock.advance(1_000); t.focusSkip(token()); assertEquals(11_000,total()); assertEquals(2_000,focus().durationMs)
        t.correctSegment(token(),firstWork.id,firstWork.start,firstWork.end!!-1_000,firstWork.selection)
        assertEquals(10_000,total()); t.undo(token()); assertEquals(11_000,total())
        clock.advance(500); t.focusSkip(token()); clock.advance(500)
        t.switch(token(),Classification()); assertNull(t.data().focus)
        clock.advance(1_000); t.undo(token()); assertNotNull(t.data().focus)
        t.cancelFocus(token()); assertNull(t.data().focus); assertEquals(12_500,total())
        t.start(token()); clock.process = "ordinary-reopened"; clock.advance(1_000)
        assertEquals(Mode.RUNNING,t.snapshot().mode); t.stop(token()) // ordinary timer keeps same-boot recovery semantics
        t.startFocus(token(),selection,FocusPlan(2_000,1_000)); val tail = t.data().segments.last()
        clock.advance(1_000); clock.process = "focus-reopened"; clock.advance(20_000)
        assertEquals(FocusMode.RECOVERY,focus().mode); assertEquals(12_500,total())
        expect(ErrorCode.RECOVERY_REQUIRED) { t.cancelFocus(token()) }
        expect(ErrorCode.RECOVERY_REQUIRED) { t.focusNext(token()) }
        t.resolveFocus(token(),null,null); assertEquals(12_500,total()); t.undo(token())
        assertEquals(FocusMode.RECOVERY,focus().mode)
        t.resolveFocus(token(),tail.start,tail.start+1_000); assertEquals(13_500,total())
        assertNull(t.data().focus)
        t.startFocus(token(),selection,FocusPlan(2_000,1_000)); clock.advance(2_000); t.focusNext(token())
        clock.process = "break-reopened"; clock.advance(500)
        assertEquals(Mode.RECOVERY,t.snapshot().mode); assertEquals(FocusMode.RECOVERY,focus().mode)
        assertEquals(15_500,total()); t.resolveFocus(token(),null,null); assertEquals(15_500,total())
        // Paused work is portable; unpaused work/break is explicitly unresolved after replacement.
        t.startFocus(token(),selection,FocusPlan(2_000,1_000)); clock.advance(1_000); t.pauseFocus(token())
        val paused = Backups(t,MemoryRollback()).export()
        var clean = Tracker(createStore(),clock); var backup = Backups(clean,MemoryRollback())
        backup.apply(backup.prepare(paused)); assertEquals(FocusMode.PAUSED,clean.snapshot().focus!!.mode)
        clean.resumeFocus(clean.snapshot().token); clock.advance(1_000); clean.cancelFocus(clean.snapshot().token)
        validate(clean.data())
        t.resumeFocus(token()); clock.advance(300)
        val active = Backups(t,MemoryRollback()).export()
        java.io.File(requireNotNull(System.getProperty("tracker.samples"))).parentFile.resolve("build/focus-example.json").writeText(active)
        clean = Tracker(createStore(),clock); backup = Backups(clean,MemoryRollback())
        val preview = backup.prepare(active); assertTrue(preview.unfinishedFocus); assertTrue(preview.unfinishedTail)
        backup.apply(preview); assertEquals(FocusMode.RECOVERY,clean.snapshot().focus!!.mode)
        clean.resolveFocus(clean.snapshot().token,null,null); validate(clean.data())
        clock.advance(1_000); t.focusNext(token()); clock.advance(300)
        val breakJson = Backups(t,MemoryRollback()).export()
        val breakPreview = backup.prepare(breakJson); assertTrue(breakPreview.unfinishedFocus); assertFalse(breakPreview.unfinishedTail)
        backup.apply(breakPreview); assertEquals(FocusMode.RECOVERY,clean.snapshot().focus!!.mode)
        val known = clean.data().segments.sumOf { it.durationMs }
        clean.resolveFocus(clean.snapshot().token,null,null)
        assertEquals(known,clean.data().segments.sumOf { it.durationMs })
        t.cancelFocus(token()); t.startFocus(token(),selection,FocusPlan(2_000,1_000))
        clock.wall += 60_000; assertEquals(FocusMode.RECOVERY,focus().mode)
        t.resolveFocus(token(),null,null)
        t.startFocus(token(),selection,FocusPlan(2_000,1_000)); clock.advance(2_000); t.focusNext(token())
        clock.boot = "new-boot"; assertEquals(FocusMode.RECOVERY,focus().mode); t.resolveFocus(token(),null,null)
        validate(t.data())
        val invalid = t.data(); expect(ErrorCode.INVALID_INPUT) { t.configureFocus(token(),FocusPlan(0,1)) }; assertEquals(invalid,t.data())
        val legacy = codec.parseToJsonElement(Backups(t,MemoryRollback()).export()).jsonObject.toMutableMap()
        legacy["formatVersion"] = JsonPrimitive(6)
        legacy["data"] = JsonObject(legacy.getValue("data").jsonObject.filterKeys { it != "focus" })
        assertNull(Backups.decode(JsonObject(legacy).toString()).data.focus)
        val v14 = java.io.File(requireNotNull(System.getProperty("tracker.samples"))).parentFile.resolve("evidence/goals-v14/goals-example.json").readText()
        val old = Backups.decode(v14); assertNull(old.data.focus); assertEquals(FocusPlan(),old.data.settings.focusPlan)
        val broken = codec.parseToJsonElement(active).jsonObject.toMutableMap()
        val brokenData = broken.getValue("data").jsonObject.toMutableMap()
        val brokenFocus = brokenData.getValue("focus").jsonObject.toMutableMap()
        brokenFocus["sessionId"] = JsonPrimitive("missing-session")
        brokenData["focus"] = JsonObject(brokenFocus); broken["data"] = JsonObject(brokenData)
        val unchanged = clean.data()
        expect(ErrorCode.UNSUPPORTED_IMPORT) { backup.prepare(JsonObject(broken).toString()) }
        assertEquals(unchanged,clean.data())
        val noProcessClock = Clock { clock.now().copy(processId = null) }
        val unsupported = Tracker(createStore(),noProcessClock)
        expect(ErrorCode.INVALID_INPUT) { unsupported.startFocus(unsupported.snapshot().token) }
        assertTrue(unsupported.data().sessions.isEmpty())
        t.startFocus(token(),selection,FocusPlan(2_000,1_000)); clock.advance(2_000); t.focusNext(token())
        val breakState = t.data(); val f = breakState.focus!!
        val zeroAnchor = breakState.copy(focus = f.copy(breakAnchor = f.breakAnchor!!.copy(elapsedMs = 0)))
        validate(zeroAnchor)
        assertEquals(FocusMode.RECOVERY,Focus.snapshot(zeroAnchor,clock.now().copy(wall = f.breakStartedAt!!-1,elapsed = Long.MAX_VALUE))!!.mode)
        t.cancelFocus(token())
    }

    private fun goalsLifecycle() {
        val clock = FakeClock(wall = Instant.parse("2026-03-28T22:59:00Z").toEpochMilli())
        var tracker = Tracker(createStore(),clock)
        val duration = Goal(name = "Recorded language",metric = GoalMetric.DURATION_MS,target = 120_000,
            zone = "Europe/Berlin",filter = Filter(activityTypeId = "language",tags = setOf("practice")))
        val count = duration.copy(id = newId(),name = "Sessions limit",metric = GoalMetric.SESSION_COUNT,target = 1,direction = GoalDirection.LIMIT)
        tracker.saveGoal(tracker.snapshot().token,duration); tracker.saveGoal(tracker.snapshot().token,count)
        val day = LocalDate.parse("2026-03-29")
        val initial = tracker.goalProgress(duration.id,day)
        assertEquals(23*3_600_000L,initial.window.end-initial.window.start) // spring DST, not 24 h
        assertEquals(0,initial.actual); assertFalse(initial.hasRecords); assertTrue(initial.incompletePeriod)
        tracker.start(tracker.snapshot().token,Classification(activityTypeId = "language",tags = setOf("practice","other")))
        val persistedBeforeTick = tracker.data()
        clock.advance(120_000) // one minute on each side of midnight
        val active = tracker.goalProgress(duration.id,day)
        assertEquals(60_000,active.actual); assertEquals(GoalPosition.BELOW,active.position)
        assertEquals(1,tracker.goalProgress(count.id,day).actual)
        assertEquals(1,active.unfinishedSessionIds.size); assertTrue(active.completedSessionIds.isEmpty())
        assertEquals(persistedBeforeTick,tracker.data())
        val previous = tracker.goalProgress(count.id,day.minusDays(1))
        assertEquals(1,previous.actual) // explicit participation-in-period policy
        tracker.pause(tracker.snapshot().token); clock.advance(60_000)
        tracker.resume(tracker.snapshot().token); clock.advance(60_000); tracker.stop(tracker.snapshot().token)
        val stopped = tracker.goalProgress(duration.id,day)
        assertEquals(120_000,stopped.actual); assertEquals(GoalPosition.AT,stopped.position)
        assertEquals(GoalStatus.MINIMUM_REACHED,stopped.recordedStatus)
        assertEquals(1,stopped.completedSessionIds.size); assertTrue(stopped.unfinishedSessionIds.isEmpty())
        assertEquals(1,tracker.goalProgress(count.id,day).actual) // two segments, one session
        val segment = tracker.data().segments.last()
        tracker.correctSegment(tracker.snapshot().token,segment.id,segment.start,segment.end!!-30_000,segment.selection)
        assertEquals(90_000,tracker.goalProgress(duration.id,day).actual)
        tracker.undo(tracker.snapshot().token); assertEquals(120_000,tracker.goalProgress(duration.id,day).actual)
        tracker.start(tracker.snapshot().token,Classification(activityTypeId = "language",tags = setOf("practice")))
        clock.advance(1_000)
        assertEquals(2,tracker.goalProgress(count.id,day).actual)
        assertEquals(GoalPosition.ABOVE,tracker.goalProgress(count.id,day).position)
        assertEquals(GoalStatus.LIMIT_EXCEEDED,tracker.goalProgress(count.id,day).recordedStatus)
        assertEquals(1,tracker.goalProgress(count.id,day).excess)
        val saved = tracker.data(); tracker = Tracker(reopen(tracker.store),clock)
        assertEquals(saved,tracker.data()); assertEquals(121_000,tracker.goalProgress(duration.id,day).actual)
        val backup = Backups(tracker,MemoryRollback()).export()
        val clean = Tracker(createStore(),clock); val backups = Backups(clean,MemoryRollback())
        val preview = backups.prepare(backup); assertEquals(2,preview.goals); backups.apply(preview)
        assertEquals(tracker.data().goals,clean.data().goals)
        val imported = clean.goalProgress(duration.id,day)
        assertEquals(120_000,imported.actual); assertTrue(imported.uncertain) // active imported tail is unresolved, not a fabricated finish
        clean.resolveTail(clean.snapshot().token,tracker.data().segments.last().start,clock.wall)
        assertEquals(121_000,clean.goalProgress(duration.id,day).actual)
        val last = clean.data().segments.last(); val earlier = clean.data().segments.first().start-2_000
        clean.correctSegment(clean.snapshot().token,last.id,earlier,earlier+1_000,last.selection)
        assertEquals(1,clean.goalProgress(count.id,day).actual) // moving a whole session out of the period recomputes count
        clean.undo(clean.snapshot().token); assertEquals(2,clean.goalProgress(count.id,day).actual)
        val closedBackup = Backups(clean,MemoryRollback()).export()
        java.io.File(requireNotNull(System.getProperty("tracker.samples"))).parentFile.resolve("build/goals-example.json").writeText(closedBackup)
        val legacy = codec.parseToJsonElement(closedBackup).jsonObject.toMutableMap()
        legacy["formatVersion"] = JsonPrimitive(5)
        legacy["data"] = JsonObject(legacy.getValue("data").jsonObject.filterKeys { it != "goals" })
        assertTrue(Backups.decode(JsonObject(legacy).toString()).data.goals.isEmpty())
        val malformed = closedBackup.replace("\"target\": 120000","\"target\": 0")
        val beforeBad = clean.data()
        expect(ErrorCode.UNSUPPORTED_IMPORT) { backups.prepare(malformed) }; assertEquals(beforeBad,clean.data())
        val disabled = duration.copy(enabled = false)
        clean.saveGoal(clean.snapshot().token,disabled)
        assertFalse(clean.goalProgress(duration.id,day).goal.enabled); assertEquals(121_000,clean.goalProgress(duration.id,day).actual)
        clean.deleteGoal(clean.snapshot().token,duration.id); clean.undo(clean.snapshot().token)
        assertEquals(disabled,clean.data().goals.single { it.id == duration.id })
        val week = Goals.window(duration.copy(period = GoalPeriod.WEEK),day)
        assertEquals(LocalDate.parse("2026-03-23").atStartOfDay(java.time.ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli(),week.start)
        val month = Goals.window(duration.copy(period = GoalPeriod.MONTH),day)
        assertEquals(LocalDate.parse("2026-04-01").atStartOfDay(java.time.ZoneId.of("Europe/Berlin")).toInstant().toEpochMilli(),month.end)
        val autumn = Goals.window(duration,LocalDate.parse("2026-10-25"))
        assertEquals(25*3_600_000L,autumn.end-autumn.start)
        val fixed = duration.copy(period = GoalPeriod.FIXED,startDate = "2026-03-29",endDateExclusive = "2026-03-30")
        assertEquals(initial.window,Goals.window(fixed,day.plusYears(1)))
        expect(ErrorCode.INVALID_INTERVAL) { clean.saveGoal(clean.snapshot().token,fixed.copy(endDateExclusive = fixed.startDate)) }
        assertEquals(beforeBad.segments,clean.data().segments)
        // Adjacent end is excluded; period totals come directly from the same report filter.
        val report = Reports.report(clean.data(),clock.now(),initial.window.start,initial.window.end,duration.filter,duration.zone)
        assertEquals(report.recordedMs,clean.goalProgress(duration.id,day).actual)
        assertEquals(0,clean.goalProgress(duration.id,day.plusDays(1)).actual)
        val beforeInvalid = clean.data()
        expect(ErrorCode.INVALID_INPUT) { clean.saveGoal(clean.snapshot().token,duration.copy(target = 0)) }
        expect(ErrorCode.INVALID_INPUT) { clean.saveGoal(clean.snapshot().token,duration.copy(zone = "Not/AZone")) }
        expect(ErrorCode.INVALID_INPUT) { clean.saveGoal(clean.snapshot().token,duration.copy(filter = Filter(cardId = "missing"))) }
        assertEquals(beforeInvalid,clean.data())
        val unmatched = duration.copy(id = newId(),filter = Filter(details = mapOf("never" to "recorded")))
        clean.saveGoal(clean.snapshot().token,unmatched)
        assertEquals(0,clean.goalProgress(unmatched.id,day).actual)
        clean.deleteGoal(clean.snapshot().token,unmatched.id)
        assertFailsWith<UnsupportedOperationException> { (clean.data().goals as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (clean.data().goals.first().filter.tags as MutableSet).clear() }
        val jsonWithoutGoals = codec.parseToJsonElement(closedBackup).jsonObject.toMutableMap()
        jsonWithoutGoals["data"] = JsonObject(jsonWithoutGoals.getValue("data").jsonObject.filterKeys { it != "goals" })
        expect(ErrorCode.UNSUPPORTED_IMPORT) { backups.prepare(JsonObject(jsonWithoutGoals).toString()) }
        assertEquals(beforeInvalid.goals,clean.data().goals)
        assertEquals(beforeInvalid.segments,clean.data().segments)
        val beforeZoneChange = clean.goalProgress(duration.id,day)
        clean.settings(clean.snapshot().token,clean.data().settings.copy(reportZone = "America/New_York"))
        assertEquals(beforeZoneChange,clean.goalProgress(duration.id,day)) // goal zone is explicit and independent
    }

    private fun checkedTimerCombinations() {
        val clock = FakeClock(); val store = createStore(); val tracker = Tracker(store,clock)
        val disk = MemoryRollback(); val transfer = HistoryTransfer(tracker,disk)
        val start = clock.wall-10_000
        val file = HistoryTransfer.parseCsv("activity,start,end,id\nImported,${Instant.ofEpochMilli(start)},${Instant.ofEpochMilli(clock.wall)},one\n",
            CsvMapping("combination","activity","start",end = "end",id = "id"))
        transfer.apply(transfer.preview(file,file.activities.associateWith { Classification(name = it.activity,activityTypeId = "language") }))
        val card = Card(name = "Private activity",activityTypeId = "language")
        tracker.saveCard(tracker.snapshot().token,card)
        tracker.start(tracker.snapshot().token,tracker.selection(card.id))
        fun checked() { validate(tracker.data()); assertEquals(snapshot(tracker.data(),clock.now()),tracker.snapshot()) }
        checked(); clock.advance(10_000); tracker.pause(tracker.snapshot().token); checked()
        tracker.saveCard(tracker.snapshot().token,card.copy(archived = true))
        val oldPath = tracker.data().segments.last().selection.ancestorPathAtWrite
        tracker.saveType(tracker.snapshot().token,ActivityType("parent-combination","Parent"))
        tracker.saveType(tracker.snapshot().token,tracker.data().types.single { it.id == "language" }.copy(parentId = "parent-combination"))
        assertEquals(oldPath,tracker.data().segments.last().selection.ancestorPathAtWrite)
        tracker.resume(tracker.snapshot().token); checked(); clock.advance(10_000)
        tracker.switch(tracker.snapshot().token,Classification()); checked(); clock.advance(1_000)
        tracker.undo(tracker.snapshot().token); checked(); tracker.stop(tracker.snapshot().token); checked()
        val before = tracker.data(); val first = before.segments[0]; val second = before.segments[1]
        val preview = tracker.previewBoundary(tracker.snapshot().token,first.id,second.id,first.end!!-1_000)
        assertEquals(before,tracker.data())
        val correction = tracker.correctBoundary(preview); checked()
        assertEquals(setOf(first.sessionId,second.sessionId),correction.changedSessionIds)
        assertEquals(before.segments.sumOf { it.durationMs },tracker.data().segments.sumOf { it.durationMs })
        assertEquals(before.sessions.map { it.importOrigin },tracker.data().sessions.map { it.importOrigin })
        tracker.undo(tracker.snapshot().token); checked(); assertEquals(before.segments,tracker.data().segments)
        expect(ErrorCode.STALE) { tracker.correctBoundary(preview) }
        tracker.settings(tracker.snapshot().token,Settings("Europe/Berlin",SurfaceSettings(true,true,true)))
        expect(ErrorCode.INVALID_INPUT) { tracker.start(tracker.snapshot().token,tracker.selection(card.id)) }
        val saved = tracker.data()
        assertFailsWith<CoreError> { store.transaction { it.copy(sessions = it.sessions+it.sessions.first()) to Unit } }
        assertEquals(saved,tracker.data())
        val wrongProof = CheckedState.validate(saved).replace(saved) { Unit }
        expect(ErrorCode.STALE) { store.checkedTransaction { wrongProof } }
        assertEquals(saved,tracker.data())
        // A cast or later edit of an input builder cannot invalidate a reused proof.
        assertFailsWith<UnsupportedOperationException> { (tracker.data().sessions as MutableList).clear() }
        assertFailsWith<UnsupportedOperationException> { (tracker.data().types.first().aliases as MutableList).add("changed") }
        val tags = mutableSetOf("original")
        tracker.start(tracker.snapshot().token,Classification(tags = tags)); tags.add("late mutation")
        assertEquals(setOf("original"),tracker.snapshot().session!!.selection.tags)
        assertFailsWith<UnsupportedOperationException> { (tracker.snapshot().session!!.selection.tags as MutableSet).add("cast mutation") }
        tracker.stop(tracker.snapshot().token)
        val frozenSaved = tracker.data()
        val json = Backups(tracker,disk).export()
        val clean = Tracker(createStore(),clock); val backups = Backups(clean,MemoryRollback())
        backups.apply(backups.prepare(json)); validate(clean.data())
        assertEquals(frozenSaved.settings,clean.data().settings); assertEquals(frozenSaved.segments,clean.data().segments)
        clean.start(clean.snapshot().token); clock.advance(1_000); clean.stop(clean.snapshot().token); validate(clean.data())
        // Full validation is still mandatory on an arbitrary store's default checked path.
        val fallback = object: StateStore {
            var data = saved
            override fun read() = data
            override val changes = kotlinx.coroutines.flow.flowOf(data)
            override fun <T> transaction(block: (Dataset)->Pair<Dataset,T>): T { val (next,result) = block(data); data = next; return result }
        }
        val fallbackTracker = Tracker(fallback,clock)
        fallbackTracker.start(fallbackTracker.snapshot().token); validate(fallback.read())
        val bad = saved.copy(segments = saved.segments+saved.segments.first())
        fallback.data = bad
        expect(ErrorCode.INVALID_INPUT) { fallbackTracker.stop(Token(bad.datasetId,bad.revision,null)) }
        assertEquals(bad,fallback.data)
    }

    private fun historyTransfer() {
        val clock = FakeClock(); val tracker = Tracker(createStore(),clock); val disk = MemoryRollback()
        val transfer = HistoryTransfer(tracker,disk)
        // UTF-8 exceeds the limit although character count does not. Malformed quoting
        // would fail first if Toggl inspected its header/body before checking the byte limit.
        val oversized = "\"x\"!" + "я".repeat(MAX_BACKUP_BYTES/2)
        val sizeError = assertFailsWith<CoreError> { HistoryTransfer.parseToggl(oversized,"toggl:large","UTC") }
        assertEquals(ErrorCode.UNSUPPORTED_IMPORT,sizeError.code)
        assertEquals("CSV exceeds 10 MiB",sizeError.message)
        val csv = "Project,Client,Description,Start date,Start time,End date,End time,Duration,Tags\r\n"+
            "Language,Personal,\"Note, with \"\"quotes\"\"\nand newline\",2026-10-09,10:00:00,2026-10-09,10:20:00,0:20:00,reading\r\n"
        val file = HistoryTransfer.parseToggl(csv,"toggl:personal-workspace","Europe/Berlin")
        assertTrue(file.errors.isEmpty()); assertEquals(1,file.rows.size)
        assertEquals(Instant.parse("2026-10-09T08:00:00Z").toEpochMilli(),file.rows.single().start)
        assertFalse(transfer.preview(file,emptyMap()).canApply)
        val mapping = file.activities.associateWith { Classification(name = it.activity,activityTypeId = "language") }
        val preview = transfer.preview(file,mapping); assertEquals(1,preview.added); assertTrue(preview.canApply)
        val repeatedRows = HistoryTransfer.parseToggl(csv+csv.substringAfter("\r\n"),"toggl:personal-workspace","Europe/Berlin")
        val repeatedPreview = transfer.preview(repeatedRows,mapping)
        assertEquals(1,repeatedPreview.added); assertEquals(1,repeatedPreview.duplicates)
        val before = tracker.data(); assertTransferFailure(transfer,preview,tracker)
        val result = transfer.apply(preview)
        assertEquals(before,Backups.decode(disk.read(result.rollbackReference!!)).data)
        val imported = tracker.data(); assertEquals("reading",imported.sessions.single().importOrigin!!.fields["Tags"])
        assertTrue(imported.sessions.single().note!!.contains("\n"))
        expect(ErrorCode.STALE) { transfer.apply(preview) }
        val replay = transfer.preview(file,mapping); assertEquals(1,replay.duplicates); assertEquals(0,replay.added)
        transfer.apply(replay); assertEquals(imported,tracker.data())
        assertEquals(imported,Backups.decode(Backups(tracker,disk).export()).data)
        val portable = HistoryTransfer.export(imported)
        val portableFile = HistoryTransfer.parseCsv(portable.csv,CsvMapping.portable("not-ai:transfer"))
        assertTrue(portableFile.errors.isEmpty()); assertEquals(file.rows.single().start,portableFile.rows.single().start)
        assertEquals(file.rows.single().end,portableFile.rows.single().end)
        val skewed = imported.copy(segments = imported.segments.map { it.copy(durationMs = it.durationMs-250) })
        val skewRoundTrip = HistoryTransfer.parseCsv(HistoryTransfer.export(skewed).csv,CsvMapping.portable("clock-skew"))
        assertTrue(skewRoundTrip.errors.isEmpty()); assertEquals(skewed.segments.single().durationMs,skewRoundTrip.rows.single().durationMs)
        // Same source row with another timezone is a conflict, not a silent replay.
        val wrongZone = HistoryTransfer.parseToggl(csv,"toggl:personal-workspace","UTC")
        assertFalse(transfer.preview(wrongZone,mapping).canApply)
        val overlap = HistoryTransfer.parseToggl(csv,"different-source","Europe/Berlin")
        val blocked = transfer.preview(overlap,mapping); assertFalse(blocked.canApply)
        expect(ErrorCode.UNSUPPORTED_IMPORT) { transfer.apply(blocked) }; assertEquals(imported,tracker.data())
        val dst = csv.replace("2026-10-09","2026-10-25").replace("10:00:00","02:10:00").replace("10:20:00","02:30:00")
        assertTrue(HistoryTransfer.parseToggl(dst,"dst","Europe/Berlin").errors.isNotEmpty())
        val wrongDuration = csv.replace(",0:20:00,",",0:21:00,")
        assertTrue(HistoryTransfer.parseToggl(wrongDuration,"invalid","Europe/Berlin").errors.isNotEmpty())
        expect(ErrorCode.UNSUPPORTED_IMPORT) { HistoryTransfer.parseCsv("a,b\n\"broken",CsvMapping.portable("broken")) }
        tracker.undo(tracker.snapshot().token); assertEquals(before.sessions,tracker.data().sessions)
        val fresh = transfer.preview(file,mapping)
        tracker.saveCard(tracker.snapshot().token,Card(name = "Changed since preview"))
        expect(ErrorCode.STALE) { transfer.apply(fresh) }
        // Retained view ticks never call storage; elapsed time and boot recovery remain core-owned.
        var reads = 0
        val counting = object: StateStore by tracker.store { override fun read(): Dataset { reads++; return tracker.store.read() } }
        val observed = Tracker(counting,clock)
        observed.start(observed.snapshot().token)
        val view = observed.view(); val readCount = reads
        repeat(20) { clock.advance(1000); assertEquals((it+1)*1000L,view.snapshot().trustedDurationMs) }
        assertEquals(readCount,reads)
        clock.boot = "after-reboot"; clock.elapsed = 1
        assertEquals(Mode.RECOVERY,view.snapshot().mode); assertEquals(readCount,reads)
    }

    private fun draftBoundaryValidation() {
        val clock = FakeClock(); val t = Tracker(createStore(),clock)
        val interval = DraftInterval(clock.wall-10_000,clock.wall-9000)
        val base = EntryDrafts.selectedText("x").copy(interval = interval)
        val invalid = listOf(
            base.copy(note = "a".repeat(16385)),
            EntryDraft(Classification(),"a".repeat(16385),interval,DraftSource.CALENDAR_EVENT),
            base.copy(note = ""),base.copy(note = " \t\n"),
            base.copy(sourceReference = "event-reference"),
            base.copy(source = DraftSource.CALENDAR_EVENT,sourceReference = "r".repeat(1025)),
            base.copy(source = DraftSource.CALENDAR_EVENT,sourceReference = ""),
            base.copy(source = DraftSource.CALENDAR_EVENT,sourceReference = "  "),
            base.copy(source = DraftSource.CALENDAR_EVENT,sourceReference = "ref\nvalue"),
            EntryDraft(Classification(),"valid",interval,DraftSource.CALENDAR_EVENT,"ref\u0000value")
        )
        invalid.forEach { draft ->
            val before = t.data(); val token = t.snapshot().token
            expect(ErrorCode.INVALID_INPUT) { t.startDraft(token,draft) }; assertEquals(before,t.data())
            expect(ErrorCode.INVALID_INPUT) { t.recordDraft(token,draft) }; assertEquals(before,t.data())
        }
        expect(ErrorCode.INVALID_INPUT) { EntryDrafts.event("title",reference = "bad\nref") }
        // Boundary-sized edited text is stored verbatim; null explicitly removes the note.
        val exact = "=текст\n" + "a".repeat(16377)
        assertEquals(16384,exact.length)
        listOf(exact,null).forEach { note ->
            val draft = EntryDraft(Classification(),note,DraftInterval(clock.wall-10_000,clock.wall-9000),DraftSource.CALENDAR_EVENT,"r".repeat(1024))
            val saved = t.recordDraft(t.snapshot().token,draft)
            assertEquals(note,saved.changedSessions.single().note)
            val started = t.startDraft(t.snapshot().token,draft)
            assertEquals(note,started.changedSessions.single().note)
            clock.advance(1000); t.stop(t.snapshot().token); clock.advance(20_000)
        }
    }

    private fun localEntryLifecycle() {
        draftBoundaryValidation()
        val clock = FakeClock(wall = Instant.parse("2026-03-30T00:00:00Z").toEpochMilli())
        val t = Tracker(createStore(),clock); fun token() = t.snapshot().token
        fun at(value: String) = Instant.parse(value).toEpochMilli()
        val card = Card(name = "Практика",activityTypeId = "language",details = mapOf("language" to "es"),tags = setOf("learning","health"))
        t.saveCard(token(),card)
        val original = t.data()
        val text = EntryDrafts.selectedText("=Лекция\n15 минут завтра",card.classification())
        assertEquals("=Лекция\n15 минут завтра",text.note); assertNull(text.interval)
        assertEquals(original,t.data()) // cancel/edit forms are never database mutations
        expect(ErrorCode.INVALID_INTERVAL) { t.recordDraft(token(),text) }
        expect(ErrorCode.INVALID_INPUT) { EntryDrafts.selectedText("   ") }
        expect(ErrorCode.INVALID_INPUT) { EntryDrafts.selectedText("a".repeat(16385)) }
        val event = EntryDrafts.event("=Календарная встреча",DraftInterval(at("2026-03-28T23:00:00Z"),at("2026-03-29T22:00:00Z")),card.classification(),"provider:opaque-instance")
        assertEquals(original,t.data())
        val copied = t.recordDraft(token(),event)
        val session = copied.changedSessionIds.single()
        assertEquals(SessionStatus.STOPPED,t.data().sessions.single().status)
        assertEquals(event.note,t.data().sessions.single().note)
        val unchanged = t.data()
        expect(ErrorCode.OVERLAP) { t.recordDraft(token(),event) }
        assertEquals(unchanged,t.data())
        expect(ErrorCode.INVALID_INTERVAL) { t.recordDraft(token(),EntryDrafts.event("All day")) }
        val future = event.copy(interval = DraftInterval(clock.wall+60_000,clock.wall+120_000))
        expect(ErrorCode.INVALID_INTERVAL) { t.recordDraft(token(),future) }
        t.measure(token(),Measurement(fieldId = "pages",unit = "page",scope = Scope.SESSION,targetId = session,value = 20))
        val extra = event.copy(interval = DraftInterval(at("2026-03-29T22:10:00Z"),at("2026-03-29T22:20:00Z")))
        t.recordDraft(token(),extra)
        val other = card.copy(id = newId()); t.saveCard(token(),other)
        t.recordDraft(token(),extra.copy(selection = other.classification(),interval = DraftInterval(at("2026-03-29T22:30:00Z"),at("2026-03-29T22:40:00Z"))))
        val view = t.view(); val calendar = view.calendar(LocalDate.parse("2026-03-28"),3,zone = "Europe/Berlin")
        assertEquals(23*60*60_000L,calendar.days[1].end-calendar.days[1].start)
        assertEquals(calendar.window.recordedMs,calendar.days.sumOf { it.recordedMs })
        calendar.days.forEach { day ->
            val direct = Reports.day(t.data(),clock.now(),day.date,zone = "Europe/Berlin")
            assertEquals(direct.rows,day.rows); assertEquals(direct.recordedMs,day.recordedMs)
        }
        assertEquals(Decimal("20"),calendar.window.quantityTotals().values.single())
        val groups = calendar.window.groupSimilar()
        assertEquals(2,groups.size) // identical display names, distinct card IDs
        assertEquals(2,groups.single { it.selection.cardId == card.id }.sessionCount)
        assertEquals(calendar.window.recordedMs,groups.sumOf { it.recordedMs })
        assertEquals(calendar.window.rows.map { it.segmentId }.toSet(),groups.flatMap { it.rows }.map { it.segmentId }.toSet())
        assertEquals(Csv.export(calendar.window),Csv.export(Reports.report(t.data(),clock.now(),calendar.window.start,calendar.window.end,zone = "Europe/Berlin")))
        expect(ErrorCode.INVALID_INTERVAL) { view.calendar(LocalDate.parse("2026-03-28"),63) }
        val first = t.data().segments.first()
        t.correctSession(token(),first.sessionId,listOf(Tracker.SegmentEdit(first.id,first.start+60_000,first.end!!,first.selection)),event.note)
        assertEquals(calendar.window.recordedMs-60_000,t.view().calendar(LocalDate.parse("2026-03-28"),3,zone = "Europe/Berlin").window.recordedMs)
        t.undo(token())
        assertEquals(groups,t.view().calendar(LocalDate.parse("2026-03-28"),3,zone = "Europe/Berlin").window.groupSimilar())
        val stale = token(); t.startDraft(stale,future) // Start uses now, not proposed calendar time
        assertEquals(clock.wall,t.snapshot().active!!.start)
        assertEquals(card.details,t.snapshot().active!!.selection.details)
        expect(ErrorCode.STALE) { t.startDraft(stale,text) }
        clock.advance(1000); t.pause(token()); clock.advance(1000); t.resume(token()); clock.advance(1000); t.stop(token())
        val after = t.view().calendar(LocalDate.parse("2026-03-28"),3,zone = "Europe/Berlin")
        assertEquals(3,after.window.groupSimilar().single { it.selection.cardId == card.id }.sessionCount)
        assertEquals(calendar.window.recordedMs+2000,after.window.recordedMs)
        // Closed history and notes survive ordinary JSON. Draft/provider context is not stored.
        val json = Backups(t,MemoryRollback()).export()
        assertFalse(json.contains("provider:opaque-instance"))
        val receiver = Tracker(createStore(),clock); val restore = Backups(receiver,MemoryRollback())
        restore.apply(restore.prepare(json)); val restored = receiver.data()
        assertEquals(after.window.rows,Reports.calendar(restored,clock.now(),LocalDate.parse("2026-03-28"),3,zone = "Europe/Berlin").window.rows)
        // A rebooted active interval has no exact calendar placement and appears only once, outside days.
        t.startDraft(token(),text); clock.advance(1000); clock.boot = "other-boot"
        val unresolved = t.view().calendar(LocalDate.parse("2026-03-28"),3,zone = "Europe/Berlin")
        assertEquals(1,unresolved.window.unresolved.size)
        assertEquals(after.window.recordedMs,unresolved.days.sumOf { it.recordedMs })
        // Apia skipped a whole civil date, unlike an ordinary 23/25-hour DST day.
        val skipped = LocalDate.parse("2011-12-30")
        expect(ErrorCode.INVALID_INTERVAL) { Reports.day(t.data(),clock.now(),skipped,zone = "Pacific/Apia") }
        expect(ErrorCode.INVALID_INTERVAL) { t.view().calendar(skipped,1,zone = "Pacific/Apia") }
        expect(ErrorCode.INVALID_INTERVAL) { t.view().calendar(skipped.minusDays(1),3,zone = "Pacific/Apia") }
        expect(ErrorCode.INVALID_INTERVAL) { t.view().calendar(skipped,2,zone = "Pacific/Apia") }
        listOf(skipped.minusDays(1),skipped.plusDays(1)).forEach { date ->
            val page = t.view().calendar(date,1,zone = "Pacific/Apia")
            val report = Reports.day(t.data(),clock.now(),date,zone = "Pacific/Apia")
            assertTrue(page.window.end > page.window.start)
            assertEquals(1L,page.window.calendarDays); assertEquals(report,page.window)
        }
        // Calendar boundaries in autumn are 25 hours, without inventing recorded activity.
        assertEquals(25*60*60_000L,t.view().calendar(LocalDate.parse("2026-10-25"),1,zone = "Europe/Berlin").days.single().let { it.end-it.start })
    }

    @Test fun smallHistoryAndExport() {
        localEntryLifecycle()
        researchPackageAdapter()
        hierarchyAndDecimals()
        val clock = FakeClock(); val tracker = Tracker(createStore(),clock)
        fun token() = tracker.snapshot().token
        val card = Card(name = "=Language",activityTypeId = "language",tags = setOf("learning","health"),details = mapOf("language" to "es"))
        tracker.saveCard(token(),card); tracker.start(token(),card.classification())
        clock.advance(20*60_000); tracker.pause(token()) // crosses UTC midnight, 10 min on each day
        val sessionId = token().sessionId!!
        val measuredChange = tracker.measure(token(),Measurement(fieldId = "pages",unit = "page",scope = Scope.SESSION,targetId = sessionId,value = 20))
        assertEquals(setOf(sessionId),measuredChange.changedSessionIds)
        clock.advance(5*60_000); tracker.resume(token()); clock.advance(20*60_000); tracker.stop(token())
        val d = tracker.data(); val windowStart = Instant.parse("2026-10-10T00:00:00Z").toEpochMilli()
        val all = Reports.report(d,clock.now(),windowStart,clock.wall)
        assertEquals(40*60_000,all.recordedMs); assertEquals(2,all.rows.size) // equal durations are not deduplicated
        val calendarPage = tracker.view().calendar(LocalDate.parse("2026-10-10"),2)
        assertEquals(listOf(10*60_000L,30*60_000L),calendarPage.days.map { it.recordedMs })
        assertEquals(3,calendarPage.days.sumOf { it.rows.size }) // one segment appears in two days, unchanged ID
        assertEquals(1,calendarPage.window.groupSimilar().single().sessionCount)
        assertEquals(all.recordedMs,calendarPage.window.recordedMs)
        val drift = d.copy(segments = d.segments.mapIndexed { index,s -> if(index == 0) s.copy(durationMs = s.durationMs+1) else s })
        validate(drift) // valid sub-second wall/elapsed discrepancy, not fabricated invalid input
        val rounded = Reports.calendar(drift,clock.now(),LocalDate.parse("2026-10-10"),2)
        assertEquals(all.recordedMs+1,rounded.days.sumOf { it.recordedMs })
        assertEquals(rounded.window.recordedMs,rounded.days.sumOf { it.recordedMs })
        assertEquals(Decimal("20"),all.measurements.single().value)
        val pages = QuantityKey("pages",1,"page",Scope.SESSION)
        assertEquals(Decimal("20"),all.quantityTotals()[pages])
        assertEquals(Decimal("20"),Reports.day(d,clock.now(),LocalDate.parse("2026-10-10")).quantityTotals()[pages])
        assertEquals(Decimal("20"),Reports.day(d,clock.now(),LocalDate.parse("2026-10-11")).quantityTotals()[pages])
        val quantities = all.copy(measurements = all.measurements + listOf(
            Measurement(id = "reps-a",fieldId = "reps",unit = "rep",scope = Scope.SEGMENT,targetId = d.segments[0].id,value = 8),
            Measurement(id = "reps-b",fieldId = "reps",unit = "rep",scope = Scope.SEGMENT,targetId = d.segments[1].id,value = 12),
            Measurement(id = "pages-zero",fieldId = "pages",meaningVersion = 2,unit = "page",scope = Scope.SESSION,targetId = sessionId,value = 0)))
        assertEquals(Decimal("20"),quantities.quantityTotals()[QuantityKey("reps",1,"rep",Scope.SEGMENT)])
        assertEquals(Decimal("0"),quantities.quantityTotals()[QuantityKey("pages",2,"page",Scope.SESSION)])
        assertNull(quantities.quantityTotals()[QuantityKey("missing",1,"page",Scope.SESSION)])
        val overflowQuantity = all.copy(measurements = listOf(all.measurements.single().copy(value = Decimal(Long.MAX_VALUE.toString())),
            all.measurements.single().copy(id = "other-target",targetId = "other-session",value = Decimal("1"))))
        assertEquals(Decimal("9223372036854775808"),overflowQuantity.quantityTotals()[pages])
        val overflowTime = all.copy(rows = listOf(all.rows.first().copy(durationMs = Long.MAX_VALUE),all.rows.last().copy(durationMs = 1)))
        expect(ErrorCode.INVALID_INTERVAL) { overflowTime.byCard() }
        assertEquals(10*60_000,Reports.day(d,clock.now(),LocalDate.parse("2026-10-10")).recordedMs)
        assertEquals(30*60_000,Reports.day(d,clock.now(),LocalDate.parse("2026-10-11")).recordedMs)
        for (tag in listOf("learning","health")) assertEquals(all.recordedMs,Reports.report(d,clock.now(),windowStart,clock.wall,Filter(tags = setOf(tag))).recordedMs)
        val csv = Csv.export(all)
        assertTrue(csv.contains("' =Language").not()); assertTrue(csv.contains("'=Language"))
        assertEquals(20,csv.lineSequence().filter { it.startsWith("\"measurement\"") }.sumOf { it.split(',')[15].trim('"').toLong() })
        assertEquals(1,csv.lineSequence().count { it.startsWith("\"measurement\"") })
        assertEquals(all.recordedMs,csv.lineSequence().filter { it.startsWith("\"segment\"") }.sumOf { it.split(',')[5].trim('"').toLong() })
        val first = d.segments.first()
        val corrected = tracker.correctSegment(token(),first.id,first.start+60_000,first.end!!,first.selection)
        assertEquals(39*60_000,corrected.report(windowStart,clock.wall).recordedMs)
        assertEquals(setOf(sessionId),corrected.changedSessions.map { it.id }.toSet())
        assertEquals(39*60_000,Reports.report(tracker.data(),clock.now(),windowStart,clock.wall).recordedMs)
        tracker.undo(token()); assertEquals(d.segments,tracker.data().segments)
        assertEquals(39*60_000,corrected.report(windowStart,clock.wall).recordedMs) // command result is a fixed snapshot
        tracker.saveType(token(),ActivityType("other","Другая область"))
        tracker.saveCard(token(),card.copy(name = "Renamed",activityTypeId = "other",archived = true))
        tracker.saveType(token(),ActivityType("language","Переименованный тип",archived = true))
        assertEquals(all.recordedMs,Reports.report(tracker.data(),clock.now(),windowStart,clock.wall,Filter(activityTypeId = "language")).recordedMs)
        val currentData = tracker.data()
        expect(ErrorCode.INVALID_INPUT) { tracker.saveCard(token(),Card(name = "Broken link",activityTypeId = "missing-type")) }
        assertEquals(currentData,tracker.data())
        assertEquals("=Language",tracker.data().segments.first().selection.name)
        tracker.copyCard(token(),card.id,"Copy")
        assertEquals(1,tracker.data().sessions.size); assertEquals(1,tracker.data().measurements.size)
        val quantityId = tracker.data().measurements.single().id
        val removed = tracker.removeMeasurement(token(),quantityId)
        assertEquals(setOf(sessionId),removed.changedSessionIds); assertTrue(removed.data.measurements.isEmpty())
        tracker.undo(token()); assertEquals(Decimal("20"),tracker.data().measurements.single().value)
        val whole = tracker.correctSession(token(),sessionId,tracker.data().segments.map { Tracker.SegmentEdit(it.id,it.start,it.end!!,it.selection) },"Исправленная заметка")
        assertEquals("Исправленная заметка",whole.changedSessions.single().note)
        assertEquals(40*60_000,whole.report(windowStart,clock.wall).recordedMs)
        val before = tracker.data()
        expect(ErrorCode.OVERLAP) { tracker.manual(token(),first.start,first.end,first.selection) }; assertEquals(before,tracker.data())
        // Calendar days use zone rules: Berlin spring DST day has 23 hours.
        val spring = Instant.parse("2026-03-29T22:00:00Z").toEpochMilli()
        val dstSession = Session(status = SessionStatus.STOPPED)
        val dstData = Dataset(sessions = listOf(dstSession),segments = listOf(Segment(sessionId = dstSession.id,start = spring-23*3_600_000,end = spring,durationMs = 23*3_600_000)),settings = Settings("Europe/Berlin"))
        assertEquals(23*3_600_000,Reports.day(dstData,clock.now(),LocalDate.parse("2026-03-29")).recordedMs)
        val calendar = Reports.compareCalendarDays(dstData,clock.now(),LocalDate.parse("2026-03-29"),LocalDate.parse("2026-03-28"),1)
        assertEquals(23*3_600_000,calendar.current.end-calendar.current.start)
        assertEquals(24*3_600_000,calendar.previous.end-calendar.previous.start)
        assertEquals(23*3_600_000,calendar.absoluteDifferenceMs); assertNull(calendar.percent)
        val autumn = Reports.compareCalendarDays(dstData,clock.now().copy(wall = Instant.parse("2026-10-27T00:00:00Z").toEpochMilli()),
            LocalDate.parse("2026-10-25"),LocalDate.parse("2026-10-24"),1)
        assertEquals(25*3_600_000,autumn.current.end-autumn.current.start)
        assertEquals(24*3_600_000,autumn.previous.end-autumn.previous.start)
        val incomplete = Reports.compareCalendarDays(dstData,clock.now().copy(wall = spring-12*3_600_000),LocalDate.parse("2026-03-29"),LocalDate.parse("2026-03-28"),1)
        assertTrue(incomplete.current.incomplete); assertFalse(incomplete.previous.incomplete)
        expect(ErrorCode.INVALID_INTERVAL) { Reports.day(dstData,clock.now(),LocalDate.MAX) }
        val huge = dstData.copy(segments = listOf(dstData.segments.single().copy(start = 0,end = MAX_TIME,durationMs = MAX_TIME)))
        assertEquals(3_600_000,Reports.report(huge,clock.now().copy(wall = MAX_TIME),MAX_TIME-3_600_000,MAX_TIME).recordedMs) // BigInteger intermediate, no multiplication overflow
    }

    private fun hierarchyAndDecimals() {
        val clock = FakeClock(); val t = Tracker(createStore(),clock)
        fun token() = t.snapshot().token
        val parent = ActivityType("area.test","Broad")
        val other = ActivityType("area.other","Other")
        val child = ActivityType("activity.test","Specific",parentId = parent.id)
        val language = Axis("axis.test","Language",valueKind = ValueKind.LOCAL_CHOICE,
            values = listOf(AxisValue("es","Spanish")))
        val source = Axis("axis.source","Source",valueKind = ValueKind.FREE_LABEL)
        val units = listOf(UnitDefinition("unit.m","Metres","length"),UnitDefinition("unit.mi","Miles","length",Decimal("1609.344")))
        val distance = Metric("measure.distance","Distance",numberKind = NumberKind.DECIMAL,canonicalUnitId = "unit.m",maxScale = 4,aggregation = Aggregation.SUM)
        t.addCatalog(token(),VocabularyData(listOf(parent,other,child),listOf(language,source),listOf(distance),units))
        val a = Facet(language.id,valueId = "es",valueMeaningVersion = 1)
        val b = Facet(source.id,text = "YouTube")
        val chosen = t.typeSelection(child.id).copy(facets = listOf(a,b))
        val from = clock.wall
        t.start(token(),chosen); val first = t.snapshot().session!!.id
        expect(ErrorCode.INVALID_INPUT) { t.changeDetails(token(),t.typeSelection(other.id)) }
        clock.advance(1000); t.pause(token()); clock.advance(1000); t.resume(token()); clock.advance(1000); t.stop(token())
        val converted = t.data().convert(Decimal("0.1"),"unit.mi","unit.m")
        assertEquals(Decimal("160.9344"),converted)
        t.measure(token(),Measurement(fieldId = distance.id,unit = "unit.m",scope = Scope.SESSION,targetId = first,value = converted))
        t.start(token(),t.typeSelection(parent.id)); clock.advance(1000); t.stop(token())
        val own = Card(name = "Unmapped"); t.saveCard(token(),own)
        t.start(token(),t.selection(own.id)); clock.advance(1000); t.stop(token())
        fun report(filter: Filter = Filter()) = Reports.report(t.data(),clock.now(),from,clock.wall,filter)
        assertEquals(4000,report().recordedMs)
        assertEquals(3000,report(Filter(activityTypeId = parent.id)).recordedMs)
        assertEquals(3000,report().byType()[parent.id])
        assertEquals(2000,report(Filter(facets = listOf(a,b))).recordedMs)
        assertEquals(2000,report(Filter(facets = listOf(a,b),facetMatch = FacetMatch.ANY)).recordedMs)
        assertEquals(converted,report().quantityTotals()[QuantityKey(distance.id,1,"unit.m",Scope.SESSION)])
        val oldSegments = t.data().segments
        t.saveType(token(),child.copy(parentId = other.id))
        assertEquals(oldSegments,t.data().segments)
        assertEquals(3000,report(Filter(activityTypeId = parent.id)).recordedMs)
        val edits = t.data().segments.filter { it.sessionId == first }.map { Tracker.SegmentEdit(it.id,it.start,it.end!!,it.selection) }
        t.correctSession(token(),first,edits,"Only a note")
        assertEquals(3000,report(Filter(activityTypeId = parent.id)).recordedMs)
        val beforePreview = t.data()
        val preview = t.previewCorrection(first,edits,"Explicit reclassification",reclassify = true)
        assertEquals(beforePreview,t.data())
        val (beforeReport,afterReport) = preview.reports(from,clock.wall,Filter(activityTypeId = parent.id))
        assertEquals(3000,beforeReport.recordedMs); assertEquals(1000,afterReport.recordedMs)
        t.applyCorrection(preview)
        assertEquals(1000,report(Filter(activityTypeId = parent.id)).recordedMs)
        assertEquals(2000,report(Filter(activityTypeId = other.id)).recordedMs)
        t.undo(token())
        assertEquals(3000,report(Filter(activityTypeId = parent.id)).recordedMs)
        t.repeat(token(),first); clock.advance(1000); t.stop(token())
        assertEquals(listOf(other.id,child.id),t.data().segments.last().selection.ancestorPathAtWrite)
        assertEquals(1000,report(Filter(activityTypeId = other.id)).recordedMs)
        expect(ErrorCode.INVALID_INPUT) { t.saveType(token(),other.copy(parentId = child.id)) }
        expect(ErrorCode.INVALID_INPUT) { t.start(token(),chosen.copy(facets = listOf(a.copy(text = "bad")))) }
        expect(ErrorCode.INVALID_INPUT) { t.saveMetric(token(),distance.copy(maxScale = 2)) }
        // Semantic identity is immutable; ordinary presentation/navigation edits stay legal.
        val stable = t.data()
        expect(ErrorCode.INVALID_INPUT) { t.saveType(token(),child.copy(meaningVersion = 2)) }
        expect(ErrorCode.INVALID_INPUT) { t.saveType(token(),child.copy(definition = "A different meaning")) }
        expect(ErrorCode.INVALID_INPUT) { t.saveType(token(),parent.copy(suggestedAxisIds = setOf("missing.axis"))) }
        expect(ErrorCode.INVALID_INPUT) { t.saveType(token(),parent.copy(suggestedMetricIds = setOf("missing.metric"))) }
        assertEquals(stable,t.data())
        t.saveType(token(),parent.copy(name = "Renamed",suggestedAxisIds = setOf(language.id),suggestedMetricIds = setOf(distance.id)))
        // Raw classifications cannot bypass the selected node's flags. Flags are node-local, not recursive.
        t.saveType(token(),parent.copy(archived = true))
        val rawParent = Classification(activityTypeId = parent.id)
        expect(ErrorCode.INVALID_INPUT) { t.start(token(),rawParent) }
        t.saveType(token(),child.copy(parentId = parent.id))
        t.start(token(),Classification(activityTypeId = child.id))
        val beforeSwitch = t.data()
        expect(ErrorCode.INVALID_INPUT) { t.switch(token(),rawParent) }
        assertEquals(beforeSwitch,t.data()); t.stop(token())
        val broadSession = t.data().sessions.first { it.selection.activityTypeId == parent.id }
        t.repeat(token(),broadSession.id); t.stop(token()) // explicit Repeat still permits archived history
        t.saveType(token(),parent.copy(selectable = false))
        expect(ErrorCode.INVALID_INPUT) { t.start(token(),rawParent) }
        t.start(token(),Classification(activityTypeId = child.id)); t.stop(token())

        val existingChild = t.data().types.single { it.id == child.id }
        val collision = t.addCatalog(token(),VocabularyData(listOf(existingChild.copy(name = "Incoming rename"),other)))
            .catalogImport!!
        assertEquals(listOf(child.id),collision.conflicts.map { it.id })
        assertEquals(listOf(other.id),collision.unchanged.map { it.id })
        assertTrue(collision.added.isEmpty())
        assertEquals(existingChild,t.data().types.single { it.id == child.id })
        val newNode = ActivityType("activity.new","New",parentId = other.id)
        assertEquals(listOf(newNode.id),t.addCatalog(token(),VocabularyData(listOf(newNode))).catalogImport!!.added.map { it.id })
        val beforeDuplicates = t.data()
        expect(ErrorCode.INVALID_INPUT) { t.addCatalog(token(),VocabularyData(listOf(existingChild,existingChild.copy(name = "Duplicate")))) }
        expect(ErrorCode.INVALID_INPUT) { t.addCatalog(token(),VocabularyData(emptyList(),axes = listOf(language,language))) }
        expect(ErrorCode.INVALID_INPUT) { t.addCatalog(token(),VocabularyData(emptyList(),metrics = listOf(distance,distance))) }
        expect(ErrorCode.INVALID_INPUT) { t.addCatalog(token(),VocabularyData(emptyList(),units = listOf(units[0],units[0]))) }
        expect(ErrorCode.INVALID_INPUT) { t.addCatalog(token(),VocabularyData(listOf(existingChild.copy(suggestedAxisIds = setOf("unknown"))))) }
        assertEquals(beforeDuplicates,t.data())

        // A failed registered-metric conversion retains the form input; saving in the wrong unit is rejected.
        t.saveUnit(token(),UnitDefinition("unit.three_m","Three metres","length",Decimal("3")))
        val thirds = distance.copy(id = "measure.thirds",canonicalUnitId = "unit.three_m")
        t.saveMetric(token(),thirds)
        val draft = MeasurementDraft(thirds.id,1,Decimal("1"),"unit.m",Scope.SESSION,first)
        val beforeConversion = t.data(); val prepared = t.data().prepareMeasurement(draft)
        assertEquals(draft,prepared.draft); assertNull(prepared.measurement)
        assertTrue(prepared.error!!.contains("unit.three_m"))
        expect(ErrorCode.INVALID_INPUT) { t.measure(token(),Measurement(fieldId = thirds.id,unit = draft.unitId,scope = draft.scope,targetId = first,value = draft.value)) }
        assertEquals(beforeConversion,t.data())
        val explicitCanonical = t.data().prepareMeasurement(draft.copy(value = Decimal("2"),unitId = "unit.three_m"))
        assertNull(explicitCanonical.error)
        t.measure(token(),explicitCanonical.measurement!!)
        assertEquals(Decimal("2"),t.data().measurements.last().value)
        val restored = Backups.decode(Backups(t,MemoryRollback()).export()).data
        assertEquals(t.data(),restored)
        assertTrue(Csv.export(report()).contains("160.9344"))
    }

    @Test fun backupReplacementAndFailure() {
        backupDocumentInput()
        val sampleDir = java.io.File(System.getProperty("tracker.samples"))
        val historySample = Backups.decode(java.io.File(sampleDir,"history-v1.json").readText())
        assertEquals(1,historySample.data.sessions.size)
        assertEquals(1_200_000,historySample.data.segments.single().durationMs)
        assertEquals(historySample.data,Backups.decode(java.io.File(sampleDir,"history-v2.json").readText()).data)
        val oldRoot = codec.parseToJsonElement(java.io.File(sampleDir,"history-v2.json").readText()).jsonObject
        for(version in 2..7) {
            val data = oldRoot.getValue("data").jsonObject.toMutableMap()
            if(version >= 6) data["goals"] = JsonArray(emptyList())
            if(version >= 7) data["focus"] = JsonNull
            val root = JsonObject(oldRoot.toMutableMap().apply {
                put("formatVersion",JsonPrimitive(version)); put("data",JsonObject(data))
            })
            assertEquals(historySample.data,Backups.decode(root.toString()).data)
        }
        repeatedRestoreAndReferences(java.io.File(sampleDir,"history-v2.json").readText())
        val legacyJson = java.io.File(sampleDir,"history-v1.json").readText()
        val migrated = migrateStoredV1(legacyJson)
        assertEquals(historySample.data,migrated)
        assertEquals(listOf("language"),migrated.segments.single().selection.ancestorPathAtWrite)
        val largest = Backups.decode(legacyJson.replace("\"value\": 20","\"value\": 9223372036854775807"))
        assertEquals(Decimal("9223372036854775807"),largest.data.measurements.single().value)
        val unknownLegacy = legacyJson.replace("listening","unknown-custom-detail").replace("\"value\": 20","\"value\": 9223372036854775807")
        val collisions = unknownLegacy.replace("\"activity\":", "\"partOf\": \"preserve-this-text\", \"value\": \"3\", \"activity\":")
        val unknownMigrated = migrateStoredV1(collisions)
        assertEquals("preserve-this-text",unknownMigrated.segments.single().selection.details["partOf"])
        assertEquals("unknown-custom-detail",unknownMigrated.segments.single().selection.details["activity"])
        assertEquals(Decimal("9223372036854775807"),unknownMigrated.measurements.single().value)
        val openSample = Backups.decode(java.io.File(sampleDir,"open-tail-v1.json").readText())
        assertNull(openSample.data.segments.single().end)
        val historical = Tracker(createStore(),FakeClock())
        val historicalBackup = Backups(historical,MemoryRollback())
        historicalBackup.apply(historicalBackup.prepare(java.io.File(sampleDir,"history-v1.json").readText()))
        assertEquals(historySample.data.copy(revision = 1),historical.data())
        val clock = FakeClock(); val tracker = Tracker(createStore(),clock)
        fun token() = tracker.snapshot().token
        val card = Card(name = "Своё",activityTypeId = "language")
        tracker.saveCard(token(),card); tracker.settings(token(),Settings("Europe/Berlin"))
        tracker.start(token(),card.classification()); clock.advance(42_000)
        tracker.saveType(token(),ActivityType("language","Новый заголовок",archived = true))
        val disk = MemoryRollback(); val backups = Backups(tracker,disk); val json = backups.export()
        val raw = Backups.decode(json)
        assertEquals(42_000,raw.data.segments.single().durationMs)
        assertNull(raw.data.segments.single().end); assertNull(raw.data.segments.single().anchor)
        val clean = Tracker(createStore(),clock); val restore = Backups(clean,disk)
        val preview = restore.prepare(json); assertTrue(preview.unfinishedTail); assertEquals(1,preview.cards)
        assertTrue(clean.data().sessions.isEmpty()) // cancellation is just abandoning preview
        val result = restore.apply(preview)
        assertEquals(Mode.RECOVERY,result.state.mode); assertEquals(42_000,result.state.trustedDurationMs)
        assertEquals(Settings("Europe/Berlin"),clean.data().settings)
        assertEquals(ActivityType("language","Новый заголовок",true),clean.data().types.single { it.id == "language" })
        assertEquals("language",clean.data().segments.single().selection.activityTypeId)
        assertTrue(Backups.decode(disk.read(result.rollbackReference)).data.sessions.isEmpty())
        val full = clean.data()
        expect(ErrorCode.UNSUPPORTED_IMPORT) { restore.prepare(json.dropLast(7)) }; assertEquals(full,clean.data())
        expect(ErrorCode.UNSUPPORTED_IMPORT) { restore.prepare(json.replace("\"formatVersion\": 7","\"formatVersion\": 999")) }; assertEquals(full,clean.data())
        expect(ErrorCode.UNSUPPORTED_IMPORT) { restore.prepare(json.replace("\"datasetId\": \"${raw.data.datasetId}\",", "")) }; assertEquals(full,clean.data())
        val broken = raw.copy(data = raw.data.copy(segments = raw.data.segments+raw.data.segments.first()))
        expect(ErrorCode.UNSUPPORTED_IMPORT) { restore.prepare(codec.encodeToString(broken)) }; assertEquals(full,clean.data())
        val stale = restore.prepare(json); clean.settings(clean.snapshot().token,Settings("UTC"))
        expect(ErrorCode.STALE) { restore.apply(stale) }
        val badDisk = object: RollbackStore { override fun save(json: String) = "bad"; override fun read(reference: String) = "damaged" }
        clean.saveCard(clean.snapshot().token,Card(name = "Local data that replacement would delete"))
        val guarded = Backups(clean,badDisk); val before = clean.data()
        expect(ErrorCode.STORAGE) { guarded.apply(guarded.prepare(json)) }; assertEquals(before,clean.data())
        assertImportRollback(clean,json,disk)
        assertEquals(before,clean.data())
        val impossibleTail = raw.copy(data = raw.data.copy(segments = raw.data.segments.map { it.copy(durationMs = MAX_TIME) }))
        expect(ErrorCode.UNSUPPORTED_IMPORT) { restore.prepare(codec.encodeToString(impossibleTail)) }; assertEquals(before,clean.data())
        clean.resolveTail(clean.snapshot().token,raw.data.segments.single().start,raw.asOf)
        assertEquals(42_000,Reports.report(clean.data(),clock.now(),clock.wall-60_000,clock.wall).recordedMs)
        validate(clean.data())
        val raceDisk = object: RollbackStore {
            override fun save(json: String): String {
                val reference = disk.save(json)
                clean.settings(clean.snapshot().token,Settings("Etc/UTC")) // legal concurrent change during external file IO
                return reference
            }
            override fun read(reference: String) = disk.read(reference)
        }
        val racing = Backups(clean,raceDisk)
        expect(ErrorCode.STALE) { racing.apply(racing.prepare(json)) }
        assertEquals(Settings("Etc/UTC"),clean.data().settings)
        assertTrue(clean.data().cards.any { it.name == "Local data that replacement would delete" })
        val restoredQuantities = Reports.report(historical.data(),clock.now().copy(wall = historySample.asOf),
            historySample.data.segments.single().start,historySample.asOf).quantityTotals()
        assertEquals(Decimal("20"),restoredQuantities[QuantityKey("pages",1,"page",Scope.SESSION)])
    }

    private fun researchPackageAdapter() {
        val path = java.io.File(System.getProperty("tracker.samples")).parentFile.resolve("research/catalog.research.json")
        val bytes = path.readBytes()
        val hash = "239faf8ccddf1102b5d5834fc3304d2a0e2abc013a008b1eb83e1a821dd4ab19"
        val supporting = path.parentFile.resolve("supporting").listFiles()!!.associate { it.name to it.readBytes() }
        val plan = ResearchCatalog.read(bytes,hash,supporting)
        assertEquals(5,plan.supportingHashes.size)
        assertEquals(357,plan.outcomes.count { it.pointer.startsWith("supporting/tracker_cross_domain_relations_v2_1.json/relations/") })
        assertContentEquals(bytes,plan.sourceBytes())
        assertEquals(1584,plan.sources.size); assertEquals(1587,plan.nodes.size)
        assertEquals(102,plan.outcomes.count { it.kind == "axis" })
        assertEquals(760,plan.outcomes.count { it.kind == "axis-value" })
        assertEquals(6,plan.outcomes.count { it.kind == "identity-conflict" })
        assertFalse("activity.caregiving.event_log" in plan.candidateIds)
        assertFalse("activity.caregiving.kinship_care_context" in plan.candidateIds)
        assertFalse("activity.caregiving.passive_supervision_concurrent" in plan.candidateIds)
        val split = plan.sources.single { it.key == SourceKey("tracker_work_learning_inventory.json","activity.writing.draft") }
        assertEquals("activity.writing.compose_draft",split.workingId)
        assertEquals("activity.writing.draft",plan.sources.single { it.key == SourceKey("tracker_catalog_proposal.json","activity.writing.draft") }.workingId)
        val explicitSix = plan.sources.filter { "seedDefinition" in it.original }.associate { it.key.id to it.workingId }
        assertEquals(mapOf("activity.work.job_search" to "activity.work.job_search",
            "activity.writing.draft" to "activity.writing.compose_draft",
            "activity.writing.correspondence" to "activity.writing.compose_correspondence",
            "activity.writing.journal" to "activity.writing.journal",
            "activity.language.lesson" to "activity.language.lesson",
            "activity.language.conversation" to "activity.language.conversation_exchange"),explicitSix)
        val vocabulary = plan.vocabulary(plan.candidateIds,plan.axisCandidateIds)
        assertEquals(vocabulary,plan.vocabulary(plan.candidateIds.reversed().toSet(),plan.axisCandidateIds.reversed().toSet()))
        assertEquals(plan.candidateIds.size,vocabulary.types.size)
        assertEquals(82,vocabulary.axes.size)
        assertTrue(vocabulary.axes.filter { it.valueKind == ValueKind.FREE_LABEL }.all { it.values.isEmpty() })
        assertTrue(vocabulary.metrics.isEmpty())
        assertEquals(Cardinality.MULTIPLE,vocabulary.axes.single { it.id == "research:leisure_sport:axis.game_genre" }.cardinality)
        val language = vocabulary.axes.single { it.id == "research:seed:axis.language" }
        assertEquals(listOf("en","es","ru"),language.values.map { it.id })
        assertFalse("research:life_care:axis.private_display" in plan.axisCandidateIds)
        assertFalse("research:leisure_sport:axis.observation_record_fields" in plan.axisCandidateIds)
        val scalar = plan.scalarVocabulary("qneed.work.length_quantity","cable-installed","Installed cable length",Scope.SESSION)
        assertEquals(Aggregation.BY_TARGET,scalar.metrics.single().aggregation)
        expect(ErrorCode.INVALID_INPUT) { plan.scalarVocabulary("qneed.writing.word_count","words","Words",Scope.SESSION) }
        expect(ErrorCode.UNSUPPORTED_IMPORT) { ResearchCatalog.read(bytes,hash,supporting + (supporting.keys.first() to byteArrayOf(0))) }
        val distance = plan.measureVocabulary("tracker_catalog_proposal.json","measure.distance","walk","Walk distance",Scope.SEGMENT)
        assertEquals("length",distance.units.single().dimension)
        val pages = plan.measureVocabulary("tracker_catalog_proposal.json","measure.pages","edition-a","Pages read of edition A (including rereads)",Scope.SESSION)
        assertEquals(NumberKind.INTEGER,pages.metrics.single().numberKind)
        expect(ErrorCode.INVALID_INPUT) { plan.measureVocabulary("tracker_leisure_sport_inventory.json","measure.media_progress","p","Progress",Scope.SESSION) }
        val projections = plan.quantityProjections()
        assertEquals(71,projections.size)
        val numeric = projections.flatMap { row -> row.variants.filter { it.kind == QuantityRepresentation.NUMBER }.map { row to it } }
        assertEquals(115,numeric.size)
        val projected = numeric.mapIndexed { i,(row,basis) ->
            val dimension = if(basis.dimension == "currency") "currency:USD" else basis.dimension
            val unit = UnitDefinition("review-unit-$i",basis.id,dimension)
            plan.quantityVocabulary(row.pointer,basis.id,"personal",basis.meaning,Scope.SESSION,unit)
        }
        val projectedCatalog = VocabularyData(emptyList(),metrics = projected.flatMap { it.metrics },units = projected.flatMap { it.units })
        assertEquals(115,projectedCatalog.metrics.map { it.id }.toSet().size)
        assertTrue(projectedCatalog.metrics.all { it.aggregation == Aggregation.BY_TARGET && it.researchOrigins.size == 1 })
        val planting = projections.single { it.sourceId == "qneed.garden.planting_and_growing_quantity" }
        assertFalse(planting.variants.single { it.id == "area" }.integer)
        assertFalse(planting.variants.single { it.id == "row_length" }.integer)
        val resources = projections.single { it.sourceId == "qneed.own_use.resource_quantity" }
        assertFalse(resources.variants.single { it.id == "volume" }.integer)
        val style = projections.single { it.sourceId == "measure.gait_style" }
        val block = projections.single { it.sourceId == "measure.training_block" }
        assertTrue((style.variants+block.variants).all { it.kind == QuantityRepresentation.FACET })
        val styleCatalog = plan.quantityFacetVocabulary(style.pointer,"style","local",ValueKind.FREE_LABEL,Cardinality.MULTIPLE)
        assertTrue(styleCatalog.metrics.isEmpty())
        expect(ErrorCode.INVALID_INPUT) { plan.quantityVocabulary(style.pointer,"style","x","Style",Scope.SESSION,UnitDefinition("u","u","count")) }
        expect(ErrorCode.INVALID_INPUT) { plan.quantityVocabulary(planting.pointer,"","x","Planting",Scope.SESSION,UnitDefinition("u","u","area")) }
        expect(ErrorCode.INVALID_INPUT) { plan.quantityVocabulary(planting.pointer,"area","x","Area",Scope.SESSION,UnitDefinition("u","u","mass")) }
        val words = projections.single { it.sourceId == "qneed.writing.word_count" }
        expect(ErrorCode.INVALID_INPUT) { plan.quantityVocabulary(words.pointer,"net_delta","x","Net words",Scope.SESSION,UnitDefinition("u","u","count")) }
        val configured = plan.configureAxis("work_learning","axis.phase","personal",ValueKind.FREE_LABEL,Cardinality.MULTIPLE)
        val primary = plan.primarySupervisionVocabulary()
        assertTrue(primary.types.single().id !in plan.candidateIds)
        expect(ErrorCode.INVALID_INPUT) { plan.configureAxis("seed","axis.topic","x",ValueKind.LOCAL_CHOICE,Cardinality.SINGLE) }
        val clock = FakeClock(); val t = Tracker(createStore(),clock)
        t.addCatalog(t.snapshot().token,projectedCatalog)
        assertTrue(t.addCatalog(t.snapshot().token,projectedCatalog).catalogImport!!.added.isEmpty())
        val edited = projectedCatalog.metrics.first().copy(name = "Personal retained label")
        t.saveMetric(t.snapshot().token,edited)
        assertEquals(listOf(edited.id),t.addCatalog(t.snapshot().token,projectedCatalog).catalogImport!!.conflicts.map { it.id })
        t.addCatalog(t.snapshot().token,styleCatalog)
        t.addCatalog(t.snapshot().token,configured)
        t.addCatalog(t.snapshot().token,primary)
        t.start(t.snapshot().token); clock.advance(1000); t.stop(t.snapshot().token)
        val oldHistory = t.data().segments
        for(metric in projectedCatalog.metrics) {
            t.measure(t.snapshot().token,Measurement(fieldId = metric.id,unit = metric.canonicalUnitId,scope = Scope.SESSION,
                targetId = t.data().sessions.single().id,value = Decimal(if(metric.numberKind == NumberKind.INTEGER) "2" else "1.25")))
        }
        t.addCatalog(t.snapshot().token,scalar)
        val metric = scalar.metrics.single()
        t.measure(t.snapshot().token,Measurement(fieldId = metric.id,unit = metric.canonicalUnitId,
            scope = Scope.SESSION,targetId = t.data().sessions.single().id,value = Decimal("1.25")))
        val selected = vocabulary.types.first { it.selectable }
        t.saveType(t.snapshot().token,selected.copy(name = "Local definition",definition = "Local meaning",researchOrigins = emptyList()))
        val archive = FileResearchArchive(java.io.File(System.getProperty("tracker.samples")).parentFile.resolve("build/research-source-archives/${newId()}"))
        val first = plan.apply(t,t.snapshot().token,plan.candidateIds,archive,plan.axisCandidateIds)
        assertEquals(listOf(selected.id),first.change.catalogImport!!.conflicts.map { it.id })
        assertEquals(oldHistory,t.data().segments)
        val again = plan.apply(t,t.snapshot().token,plan.candidateIds,archive,plan.axisCandidateIds)
        assertTrue(again.change.catalogImport!!.added.isEmpty())
        assertEquals(listOf(selected.id),again.change.catalogImport!!.conflicts.map { it.id })
        assertEquals(oldHistory,t.data().segments)
        assertEquals("Local meaning",t.data().types.single { it.id == selected.id }.definition)
        assertTrue(t.data().types.filter { it.id != selected.id && it.id in plan.candidateIds }.all { it.researchOrigins.isNotEmpty() })
        val quantityReport = Reports.report(t.data(),clock.now(),clock.wall-2000,clock.wall)
        assertEquals(116,quantityReport.measurements.size)
        assertTrue(quantityReport.quantityTotals().isEmpty())
        val backupJson = Backups(t,MemoryRollback()).export()
        assertEquals(7,Backups.decode(backupJson).formatVersion)
        val copy = Backups.decode(backupJson).data
        assertEquals(t.data(),copy)
        val receiver = Tracker(createStore(),clock)
        val restoration = Backups(receiver,MemoryRollback())
        restoration.apply(restoration.prepare(backupJson))
        assertEquals(copy.copy(revision = receiver.data().revision),receiver.data())
        receiver.start(receiver.snapshot().token); clock.advance(1000); receiver.stop(receiver.snapshot().token)
        assertNull(receiver.data().sessions.last().selection.activityTypeId)
        // Chosen source-local meanings survive the actual classification and backup boundary.
        val facets = listOf(Facet(language.id,valueId = "es",valueMeaningVersion = 1),
            Facet("research:leisure_sport:axis.game_genre",text = "co-op / puzzle"),
            Facet("research:leisure_sport:axis.game_genre",text = "local personal genre"),
            Facet(configured.axes.single().id,text = "publish/release"),
            Facet(styleCatalog.axes.single().id,text = "freestyle"))
        receiver.start(receiver.snapshot().token,Classification(activityTypeId = primary.types.single().id,facets = facets))
        clock.advance(1000); receiver.stop(receiver.snapshot().token)
        assertEquals(facets,receiver.data().sessions.last().selection.facets)
        receiver.repeat(receiver.snapshot().token,receiver.data().sessions.last().id)
        assertEquals(facets,receiver.data().sessions.last().selection.facets)
        clock.advance(1000); receiver.stop(receiver.snapshot().token)
        val priorMeasurements = receiver.data().measurements
        receiver.repeat(receiver.snapshot().token,copy.sessions.single().id)
        clock.advance(1000); receiver.stop(receiver.snapshot().token)
        assertEquals(priorMeasurements,receiver.data().measurements) // repeat never invents new measured output
        assertEquals(receiver.data(),Backups.decode(Backups(receiver,MemoryRollback()).export()).data)
        for((_,digest) in plan.supportingHashes) assertEquals(digest,researchHash(archive.read(digest)))
        val badArchive = object: ResearchArchive {
            override fun put(sha256: String,bytes: ByteArray) {}
            override fun read(sha256: String) = byteArrayOf(0)
        }
        val before = t.data()
        expect(ErrorCode.STORAGE) { plan.apply(t,t.snapshot().token,plan.candidateIds,badArchive) }
        assertEquals(before,t.data())
        expect(ErrorCode.UNSUPPORTED_IMPORT) { ResearchCatalog.read(bytes,"0".repeat(64)) }
        // Full fixture structural corruption is rejected even with a freshly computed transport hash.
        val wrongPath = bytes.toString(Charsets.UTF_8).replaceFirst("\"researchPath\": [","\"researchPath\": [\"missing-parent\",").toByteArray()
        expect(ErrorCode.INVALID_INPUT) { ResearchCatalog.read(wrongPath,researchHash(wrongPath)) }
        val reportDir = java.io.File(System.getProperty("tracker.samples")).parentFile.resolve("build/research-evidence")
        reportDir.mkdirs()
        reportDir.resolve("adapter-outcomes.json").writeText(codec.encodeToString(plan.outcomes))
        reportDir.resolve("runtime-types.json").writeText(codec.encodeToString(vocabulary))
        fullResearchProjection(plan,path.parentFile)
    }

    private fun fullResearchProjection(base: ResearchPlan,researchDir: java.io.File) {
        val isco = researchDir.resolve("projections/isco_full_projection_v2.json").readBytes()
        val sports = researchDir.resolve("projections/sports_additive_catalogue_projection.json").readBytes()
        val iscoHash = "7dd02e406bb2c34260a73179b2b6a94f19d0141aa1bb06815277eb5e23bc2498"
        val sportsHash = "6b5e4810622ff4af4af794b2ac8093d7135caad85f1ace51e99ced62a7373955"
        val supporting = researchDir.resolve("supporting").listFiles()!!.associate { it.name to it.readBytes() }
        // Both inputs are valid ResearchPlans; full projection has stricter evidence requirements.
        val otherBytes = base.sourceBytes() + byteArrayOf(10)
        val wrongBase = ResearchCatalog.read(otherBytes,researchHash(otherBytes),supporting)
        expect(ErrorCode.UNSUPPORTED_IMPORT) { FullResearchCatalog.read(wrongBase,isco,iscoHash,sports,sportsHash) }
        val missingCompanion = ResearchCatalog.read(base.sourceBytes(),base.sha256,
            supporting - "tracker_catalog_core_compatibility.md")
        assertEquals(4,missingCompanion.supportingHashes.size)
        expect(ErrorCode.UNSUPPORTED_IMPORT) { FullResearchCatalog.read(missingCompanion,isco,iscoHash,sports,sportsHash) }
        val full = FullResearchCatalog.read(base,isco,iscoHash,sports,sportsHash)
        explicitFullCatalogImport(full,researchDir)
        assertContentEquals(isco,full.sourceBytes(FullResearchCatalog.ISCO))
        assertContentEquals(sports,full.sourceBytes(FullResearchCatalog.SPORTS))
        assertEquals(353,full.outcomes.count { it.file == FullResearchCatalog.ISCO && it.kind == "source-disposition" })
        assertEquals(223,full.outcomes.count { it.file == FullResearchCatalog.SPORTS && it.kind == "source-disposition" })
        assertTrue(full.vocabulary.types.size > base.candidateIds.size)
        assertEquals(1918,full.vocabulary.types.size)
        assertEquals(337,full.vocabulary.axes.size)
        // Every new ISCO runtime ID must come from an explicit action/component proposal.
        // Frozen/no-mutation rows and residual research IDs must never become fallback types.
        val source = codec.parseToJsonElement(isco.toString(Charsets.UTF_8)).jsonObject
        val explicitIds = source.getValue("proposedActions").jsonArray.map { it.jsonObject.getValue("researchProposalRef").jsonPrimitive.content }.toSet() +
            source.getValue("componentActionProposals").jsonArray.map { it.jsonObject.getValue("componentProposalRef").jsonPrimitive.content }
        val iscoTypes = full.vocabulary.types.filter { type -> type.researchOrigins.any { it.sourceFile == FullResearchCatalog.ISCO } }
        assertEquals(explicitIds,iscoTypes.map { it.id }.toSet())
        val residualIds = source.getValue("residualResolutions").jsonArray.map { it.jsonObject.getValue("residualId").jsonPrimitive.content }.toSet()
        assertTrue(full.vocabulary.types.none { it.id in residualIds })
        val pendingResiduals = full.outcomes.filter { it.kind == "residual-resolution" && it.runtimeIds.isEmpty() }
        assertEquals(setOf("residual.engrave_etch_surface_marking", "residual.custom_orthopaedic_footwear_fit_repair",
            "residual.photosensitive_media_manufacture", "residual.promotional_sample_goods_distribution"),pendingResiduals.map { it.key }.toSet())
        val frozenRows = source.getValue("candidateDispositions").jsonArray.map { it.jsonObject }.filter {
            it.getValue("projection").jsonObject["status"]?.jsonPrimitive?.content == "use_frozen_source_decision_no_new_action_proposal"
        }
        assertTrue(frozenRows.isNotEmpty())
        val runtimeIds = full.vocabulary.types.map { it.id }.toSet()
        for(row in frozenRows) {
            val key = row.getValue("recordKey").jsonPrimitive.content
            assertFalse("isco.research.$key" in runtimeIds)
            assertEquals(1,full.outcomes.count { it.kind == "source-disposition" && it.key == key })
        }
        assertEquals(base.vocabulary(base.candidateIds,base.axisCandidateIds).types.toSet(),
            full.vocabulary.types.filter { it.id in base.candidateIds }.toSet())
        val held = full.outcomes.filter { it.file == FullResearchCatalog.SPORTS && it.kind == "source-disposition" && it.runtimeIds.isEmpty() }
        assertEquals(listOf("arisf:arISF_075"),held.map { it.key })
        val clock = FakeClock()
        val t = Tracker(createStore(),clock)
        val archive = FileResearchArchive(researchDir.parentFile.resolve("build/full-source-archives/${newId()}"))
        val initial = full.apply(t,t.snapshot().token,archive)
        assertTrue(initial.catalogImport!!.conflicts.isEmpty())
        assertTrue(full.apply(t,t.snapshot().token,archive).catalogImport!!.added.isEmpty())
        for((_,digest) in full.sourceHashes) assertEquals(digest,researchHash(archive.read(digest)))
        // All 28 sports wholes and nine occupational families work without mandatory detail.
        val wholes = full.outcomes.filter { it.kind == "whole-event" || (it.kind == "family-or-wrapper" && it.boundary.startsWith("Whole family")) }
            .flatMap { it.runtimeIds }.distinct()
        assertEquals(37,wholes.size)
        val begin = clock.wall
        for(id in wholes) {
            t.start(t.snapshot().token,t.typeSelection(id))
            clock.advance(1000); t.stop(t.snapshot().token)
        }
        val original = t.data().sessions.last()
        t.repeat(t.snapshot().token,original.id)
        clock.advance(1000); t.stop(t.snapshot().token)
        assertEquals(original.selection,t.data().sessions.last().selection)
        assertEquals(38,t.data().segments.size) // family/component links never manufacture time
        assertEquals(38_000L,Reports.report(t.data(),clock.now(),begin,clock.wall).recordedMs)
        val history = t.data().segments
        val selected = t.data().types.single { it.id == wholes.first() }
        t.saveType(t.snapshot().token,selected.copy(name = "My whole activity"))
        val stale = t.snapshot().token
        val conflict = full.apply(t,stale,archive)
        assertEquals(listOf(selected.id),conflict.catalogImport!!.conflicts.map { it.id })
        val after = t.data()
        expect(ErrorCode.STALE) { full.apply(t,stale,archive) }
        assertEquals(after,t.data())
        assertEquals(conflict.catalogImport,full.apply(t,t.snapshot().token,archive).catalogImport)
        assertEquals(history,t.data().segments)
        assertEquals("My whole activity",t.data().types.single { it.id == selected.id }.name)
        val json = Backups(t,MemoryRollback()).export()
        assertEquals(t.data(),Backups.decode(json).data)
        val receiver = Tracker(createStore(),clock)
        val restore = Backups(receiver,MemoryRollback())
        restore.apply(restore.prepare(json))
        assertEquals(t.data().copy(revision = receiver.data().revision),receiver.data())
        val before = receiver.data()
        expect(ErrorCode.UNSUPPORTED_IMPORT) { restore.prepare(json.dropLast(1)) }
        assertEquals(before,receiver.data())
        expect(ErrorCode.UNSUPPORTED_IMPORT) { FullResearchCatalog.read(base,isco,"0".repeat(64),sports,sportsHash) }
        val broken = sports.toString(Charsets.UTF_8).replace("\"parentId\": \"", "\"parentId\": \"missing.").toByteArray()
        expect(ErrorCode.INVALID_INPUT) { FullResearchCatalog.read(base,isco,iscoHash,broken,researchHash(broken)) }
        val badArchive = object: ResearchArchive {
            override fun put(sha256: String,bytes: ByteArray) {}
            override fun read(sha256: String) = byteArrayOf(0)
        }
        expect(ErrorCode.STORAGE) { full.apply(receiver,receiver.snapshot().token,badArchive) }
        assertEquals(before,receiver.data())
        val output = researchDir.parentFile.resolve("build/full-projection-evidence"); output.mkdirs()
        output.resolve("runtime-vocabulary.json").writeText(codec.encodeToString(full.vocabulary))
        output.resolve("outcomes.json").writeText(codec.encodeToString(full.outcomes))
    }

    private fun explicitFullCatalogImport(full: FullResearchPlan,researchDir: java.io.File) {
        val clock = FakeClock(); val tracker = Tracker(createStore(),clock)
        tracker.start(tracker.snapshot().token); clock.advance(1000); tracker.stop(tracker.snapshot().token)
        val before = tracker.data()
        val saved = mutableMapOf<String,ByteArray>()
        val archive = object: ResearchArchive {
            override fun put(sha256: String,bytes: ByteArray) { saved[sha256] = bytes.copyOf() }
            override fun read(sha256: String) = saved.getValue(sha256).copyOf()
        }
        val importer = FullCatalogImports(tracker,archive)
        fun open(path: String) = researchDir.resolve(path).inputStream()
        val preview = importer.prepare(::open)
        assertTrue(preview.canApply); assertEquals(1918,preview.types); assertEquals(337,preview.axes)
        assertEquals(8,preview.sourceHashes.size); assertEquals(before,tracker.data()); assertTrue(saved.isEmpty())
        expect(ErrorCode.UNSUPPORTED_IMPORT) { importer.prepare { path ->
            if(path == "supporting/tracker_catalog_core_compatibility.md") throw java.io.FileNotFoundException()
            open(path)
        } }
        expect(ErrorCode.UNSUPPORTED_IMPORT) { importer.prepare { path ->
            if(path == "catalog.research.json") java.io.ByteArrayInputStream(baseBadBytes(researchDir)) else open(path)
        } }
        assertEquals(before,tracker.data()); assertTrue(saved.isEmpty())
        tracker.settings(tracker.snapshot().token,Settings("Europe/Berlin"))
        val changed = tracker.data()
        expect(ErrorCode.STALE) { importer.apply(preview) }
        assertEquals(changed,tracker.data()); assertTrue(saved.isEmpty())
        val installed = importer.apply(importer.prepare(::open))
        assertTrue(installed.catalogImport!!.conflicts.isEmpty())
        assertEquals(before.sessions,tracker.data().sessions); assertEquals(before.segments,tracker.data().segments)
        for(source in FullCatalogImports.sources) assertEquals(source.sha256,researchHash(saved.getValue(source.sha256)))
        val count = tracker.data().types.size
        assertTrue(importer.apply(importer.prepare(::open)).catalogImport!!.added.isEmpty())
        assertEquals(count,tracker.data().types.size)
        val conflictTracker = Tracker(createStore(),clock)
        val item = full.vocabulary.types.first { it.parentId == null }
        conflictTracker.saveType(conflictTracker.snapshot().token,item.copy(name = "Local name",
            suggestedAxisIds = emptySet(),suggestedMetricIds = emptySet()))
        val conflicting = FullCatalogImports(conflictTracker,archive)
        val conflictPreview = conflicting.prepare(::open)
        assertFalse(conflictPreview.canApply); assertEquals(item.id,conflictPreview.report.conflicts.single().id)
        val retained = conflictTracker.data()
        expect(ErrorCode.INVALID_INPUT) { conflicting.apply(conflictPreview) }
        assertEquals(retained,conflictTracker.data())
        val installedBeforeConflict = tracker.data()
        expect(ErrorCode.INVALID_INPUT) { tracker.addCatalog(tracker.snapshot().token,
            VocabularyData(listOf(item.copy(name = "Different"),ActivityType("new-from-conflicting-package","New"))),rejectConflicts = true) }
        assertEquals(count,tracker.data().types.size)
        assertEquals(installedBeforeConflict,tracker.data())
        assertFailsWith<UnsupportedOperationException> { (full.vocabulary.types as MutableList<ActivityType>).clear() }
    }
    private fun baseBadBytes(researchDir: java.io.File) = researchDir.resolve("catalog.research.json").readBytes()+byteArrayOf(10)

    private fun repeatedRestoreAndReferences(sampleJson: String) {
        val fixture = Backups.decode(sampleJson)
        val clock = FakeClock(wall = fixture.asOf+60_000)
        val t = Tracker(createStore(),clock); val disk = MemoryRollback(); val b = Backups(t,disk)
        b.apply(b.prepare(sampleJson))
        val originalSegments = t.data().segments
        val originalType = t.data().types.single()
        val parent = ActivityType("local.parent","Local parent")
        t.saveType(t.snapshot().token,parent)
        t.saveType(t.snapshot().token,originalType.copy(name = "Renamed",parentId = parent.id))
        assertEquals(originalSegments,t.data().segments)
        val incoming = VocabularyData(listOf(originalType.copy(meaningVersion = 2,definition = "Different semantic meaning")))
        val conflict = t.addCatalog(t.snapshot().token,incoming)
        assertEquals(listOf(originalType.id),conflict.catalogImport!!.conflicts.map { it.id })
        val replay = t.addCatalog(t.snapshot().token,incoming)
        assertEquals(conflict.catalogImport,replay.catalogImport)
        assertEquals(originalSegments,t.data().segments)
        assertEquals(1,t.data().types.single { it.id == originalType.id }.meaningVersion)
        val exported = b.export(); val saved = Backups.decode(exported).data
        val clean = Tracker(createStore(),clock); val restore = Backups(clean,disk)
        restore.apply(restore.prepare(exported))
        assertEquals(saved.copy(revision = clean.data().revision),clean.data())
        val once = clean.data()
        restore.apply(restore.prepare(exported))
        assertEquals(once.copy(revision = once.revision+1),clean.data())
        val marker = Card(name = "New local record since backup")
        clean.saveCard(clean.snapshot().token,marker)
        val beforeReplacement = clean.data()
        val result = restore.apply(restore.prepare(exported))
        assertFalse(clean.data().cards.any { it.id == marker.id }) // full replacement is not merge
        val rollbackJson = disk.read(result.rollbackReference)
        assertEquals(beforeReplacement,Backups.decode(rollbackJson).data)
        restore.apply(restore.prepare(rollbackJson))
        assertEquals(beforeReplacement.copy(revision = clean.data().revision),clean.data())
        val unchanged = clean.data(); val diskCount = disk.saved.size
        val source = Backups.decode(exported)
        val invalidGraphs = listOf(
            saved.copy(types = saved.types.map { if(it.id == parent.id) it.copy(parentId = originalType.id) else it }),
            saved.copy(types = saved.types.filterNot { it.id == parent.id }),
            saved.copy(cards = emptyList()),
            saved.copy(measurements = saved.measurements.map { it.copy(targetId = "missing") }),
            saved.copy(types = saved.types.map { it.copy(suggestedAxisIds = setOf("missing")) }),
            saved.copy(segments = saved.segments.map { it.copy(selection = it.selection.copy(ancestorPathAtWrite = listOf("missing",originalType.id))) })
        )
        invalidGraphs.forEach { graph ->
            expect(ErrorCode.UNSUPPORTED_IMPORT) { restore.prepare(codec.encodeToString(source.copy(data = graph))) }
            assertEquals(unchanged,clean.data()); assertEquals(diskCount,disk.saved.size)
        }
        expect(ErrorCode.INVALID_INTERVAL) { Backups.encode(Dataset(),clock.now().copy(wall = -1)) }
        val oversized = Dataset(cards = listOf(Card(name = "я".repeat(MAX_BACKUP_BYTES/2))))
        expect(ErrorCode.INVALID_INPUT) { Backups.encode(oversized,clock.now()) }
        // A backwards wall clock must not create a successfully exported but unrestorable backup.
        expect(ErrorCode.UNSUPPORTED_IMPORT) { Backups.encode(saved,clock.now().copy(wall = 0)) }
        assertEquals(unchanged,clean.data())
    }

    protected open fun migrateStoredV1(json: String): Dataset = Backups.decode(json).data

    protected open fun assertImportRollback(tracker: Tracker,json: String,disk: RollbackStore) {
        val failingStore = object: StateStore {
            override val changes = tracker.store.changes
            override fun read() = tracker.store.read()
            override fun <T> transaction(block: (Dataset)->Pair<Dataset,T>): T = tracker.store.transaction { live ->
                block(live)
                throw IllegalStateException("simulated persistence failure")
            }
        }
        val failing = Backups(Tracker(failingStore,tracker.clock),disk)
        expect(ErrorCode.STORAGE) { failing.apply(failing.prepare(json)) }
    }
}
