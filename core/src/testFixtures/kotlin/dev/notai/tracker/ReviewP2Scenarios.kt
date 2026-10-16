package dev.notai.tracker

import java.time.LocalDate
import kotlin.test.*

/** Precisely bounded, isolated fixtures; no production or owner data is touched. */
fun reviewP2HistoryFixture(now: Long,uncertain: Boolean): Dataset {
    val selection = Classification(name = "Reviewed history")
    val literal = Classification(name = "Only literal identity")
    return Dataset(sessions = listOf(Session("p2-recorded",SessionStatus.STOPPED,selection,"Original positive note"),
        Session("p2-later",SessionStatus.STOPPED,selection,"Later ineligible note"),
        Session("p2-literal",SessionStatus.STOPPED,literal,"Literal identity note")),
        segments = listOf(Segment("p2-positive","p2-recorded",now-6000,now-5000,1000,selection),
            Segment("p2-ineligible","p2-later",now-3000,if(uncertain) now-2000 else now-3000,if(uncertain) 1000 else 0,selection,uncertain = uncertain),
            Segment("p2-literal-segment","p2-literal",now-1000,now-1000,0,literal)))
}

fun reviewP2EmptyReportIndex(create: () -> StateStore,reopen: (StateStore) -> StateStore) {
    val recorded = Classification(name = "Recorded",tags = setOf("recorded"));val marker = Classification(name = "Identity",tags = setOf("marker"))
    val data = Dataset(sessions = listOf(Session("p2-long",SessionStatus.STOPPED,recorded,"Long fact"),Session("p2-empty",SessionStatus.STOPPED,marker,"Retain me"),
        Session("p2-short",SessionStatus.STOPPED,recorded),Session("p2-empty-two",SessionStatus.STOPPED,marker)),
        segments = listOf(Segment("p2-long-s","p2-long",1000,11000,10000,recorded),Segment("p2-empty-s","p2-empty",6000,6000,0,marker),
            Segment("p2-short-s","p2-short",12000,14000,2000,recorded),Segment("p2-empty-two-s","p2-empty-two",13000,13000,0,marker)))
    validate(data);val clock = FakeClock(20000)
    for(reverse in listOf(false,true)) {
        val store = create();val fixture = if(reverse) data.copy(segments = data.segments.reversed()) else data
        store.transaction { fixture to Unit };var t = Tracker(store,clock)
        repeat(2) {
            val before = t.data();val view = t.view()
            assertEquals(3000L,view.report(7000,10000).recordedMs)
            for((start,end) in listOf(0L to 15000L,1000L to 6000L,6000L to 11000L,7000L to 10000L,11000L to 12000L,12500L to 13500L,14000L to 15000L)) {
                for(filter in listOf(Filter(),Filter(tags = setOf("recorded")),Filter(tags = setOf("marker")))) {
                    val oracle = Reports.report(before,clock.now(),start,end,filter)
                    assertEquals(oracle,view.report(start,end,filter),"[$start,$end) $filter reverse=$reverse")
                }
            }
            assertEquals(Reports.calendar(before,clock.now(),LocalDate.ofEpochDay(0),1),view.calendar(LocalDate.ofEpochDay(0),1))
            assertEquals(before,t.data());assertEquals(4,t.data().segments.size)
            t = Tracker(reopen(t.store),clock)
        }
    }
}

fun reviewP2EmptyBackfill(create: () -> StateStore,reopen: (StateStore) -> StateStore) {
    val empty = Segment("p2-empty-s","p2-empty",6000,6000,0,Classification(name = "Keep identity"))
    for(obstacle in listOf("none","positive","uncertain")) {
        val clock = FakeClock(10000);val store = create()
        val sessions = listOf(Session("p2-empty",SessionStatus.STOPPED,empty.selection,"Keep note"))+
            if(obstacle == "none") emptyList() else listOf(Session("p2-obstacle",SessionStatus.STOPPED))
        val segments = listOf(empty)+if(obstacle == "none") emptyList() else listOf(Segment("p2-obstacle-s","p2-obstacle",6500,7000,500,uncertain = obstacle == "uncertain"))
        val initial = Dataset(sessions = sessions,segments = segments);validate(initial);store.transaction { initial to Unit }
        var t = Tracker(store,clock);t.start(t.snapshot().token,Classification(name = "Active"),"Active note");clock.advance(2000)
        val before = t.data();val target = t.snapshot().timerTarget;val session = t.snapshot().session!!.id
        if(obstacle != "none") {
            assertEquals(if(obstacle == "uncertain") ErrorCode.RECOVERY_REQUIRED else ErrorCode.OVERLAP,
                assertFailsWith<CoreError> { t.previewStartBackfill(t.snapshot().token,session,5000) }.code)
            assertEquals(before,t.data());continue
        }
        val preview = t.previewStartBackfill(t.snapshot().token,session,5000)
        assertEquals(5000L,preview.addedMs);assertEquals(before,t.data())
        assertEquals(ErrorCode.INVALID_INPUT,assertFailsWith<CoreError> { t.previewStartAtPreviousEnd(t.snapshot().token,session) }.code)
        t.applyStartBackfill(preview)
        assertEquals(empty,t.data().segments.single { it.id == empty.id });assertEquals(target,t.snapshot().timerTarget)
        assertEquals(7000L,t.snapshot().trustedDurationMs);assertEquals(7000L,t.view().report(0,clock.wall).recordedMs)
        assertEquals(before.sessions,t.data().sessions)
        t.undo(t.snapshot().token);assertEquals(before.sessions,t.data().sessions);assertEquals(before.segments,t.data().segments)
        t.applyStartBackfill(t.previewStartBackfill(t.snapshot().token,session,5000));t.stop(t.snapshot().token)
        val saved = t.data();t = Tracker(reopen(t.store),clock);assertEquals(saved,t.data())
        val restored = Tracker(create(),clock);val backups = Backups(restored,MemoryRollback())
        backups.apply(backups.prepare(Backups(t,MemoryRollback()).export()))
        assertEquals(saved.segments,restored.data().segments);assertEquals(saved.sessions,restored.data().sessions)
    }
}

fun reviewP2HistoryCanonical(create: () -> StateStore,reopen: (StateStore) -> StateStore,uncertain: Boolean) {
    val clock = FakeClock();val data = reviewP2HistoryFixture(clock.wall,uncertain);validate(data)
    val store = create();store.transaction { data to Unit };var t = Tracker(store,clock)
    repeat(2) {
        val before = t.data();val choices = t.view().choices()
        val recent = choices.suggestions(SuggestionQuery(enabled = true)).items.single().entry
        assertEquals("p2-positive",recent.sourceSegmentId);assertEquals("Original positive note",recent.note)
        assertEquals(recent,choices.resolve(choices.reference(recent)))
        val quick = choices.quick(HistoryWindow(clock.wall-10000,clock.wall+1)).single()
        assertEquals(recent,quick.entry);assertEquals(quick.entry,choices.resolve(quick.reference))
        assertEquals(recent,choices.latestRecorded())
        val search = choices.search(ChoiceQuery(kinds = setOf(ChoiceKind.HISTORY))).items
        assertEquals(2,search.size)
        assertEquals(recent,search.single { it.entry.selection == recent.selection }.entry)
        assertEquals("Literal identity note",search.single { it.entry.sourceSegmentId == "p2-literal-segment" }.entry.note)
        assertEquals(before,t.data())
        t = Tracker(reopen(t.store),clock)
    }
}
