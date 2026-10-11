package dev.notai.tracker

import org.junit.Test
import kotlin.test.*
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

class FakeClock(var wall: Long = Instant.parse("2026-10-10T23:50:00Z").toEpochMilli(),var elapsed: Long = 100_000,var boot: String = "boot-1"): Clock {
    override fun now() = Moment(wall,elapsed,boot)
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

    @Test fun timerPath() {
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

    @Test fun smallHistoryAndExport() {
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
        val restored = Backups.decode(Backups(t,MemoryRollback()).export()).data
        assertEquals(t.data(),restored)
        assertTrue(Csv.export(report()).contains("160.9344"))
    }

    @Test fun backupReplacementAndFailure() {
        val sampleDir = java.io.File(System.getProperty("tracker.samples"))
        val historySample = Backups.decode(java.io.File(sampleDir,"history-v1.json").readText())
        assertEquals(1,historySample.data.sessions.size)
        assertEquals(1_200_000,historySample.data.segments.single().durationMs)
        assertEquals(historySample.data,Backups.decode(java.io.File(sampleDir,"history-v2.json").readText()).data)
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
        expect(ErrorCode.UNSUPPORTED_IMPORT) { restore.prepare(json.replace("\"formatVersion\": 2","\"formatVersion\": 999")) }; assertEquals(full,clean.data())
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
