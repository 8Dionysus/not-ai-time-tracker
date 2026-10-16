package dev.notai.tracker

import kotlin.test.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

fun reviewP3WindowFixture(): Dataset {
    val selection = Classification(name = "Same historical classification")
    return Dataset(sessions = listOf(Session("p3-s1",SessionStatus.STOPPED,selection,"Note A"),
        Session("p3-s2",SessionStatus.STOPPED,selection,"Note B")),segments = listOf(
        Segment("p3-g1","p3-s1",1000,2000,1000,selection),Segment("p3-g2","p3-s2",3000,4000,1000,selection)))
}

fun reviewP3Shown(choices: ChoiceView,path: String): ChoiceEntry = when(path) {
    "bounded-frequent" -> choices.suggestions(SuggestionQuery(true,SuggestionOrder.FREQUENT_RECORDED,HistoryWindow(1000,2500))).items.single().entry
    "bounded-recent" -> choices.suggestions(SuggestionQuery(true,SuggestionOrder.RECENT_RECORDED,HistoryWindow(1000,2500))).items.single().entry
    "bounded-quick" -> choices.quick(HistoryWindow(1000,2500)).single().entry
    "unbounded-search" -> choices.search(ChoiceQuery(kinds = setOf(ChoiceKind.HISTORY))).items.single().entry
    else -> choices.suggestions(SuggestionQuery(true)).items.single().entry
}

private val reviewP3Paths = listOf("bounded-frequent","bounded-recent","bounded-quick","unbounded-search","unbounded-recent")

fun reviewP3WindowReference(create: () -> StateStore,reopen: (StateStore) -> StateStore) {
    val clock = FakeClock(10000);val store = create();store.transaction { reviewP3WindowFixture() to Unit };var tracker = Tracker(store,clock)
    repeat(2) {
        val before = tracker.data();val choices = tracker.view().choices()
        for(path in reviewP3Paths) {
            val shown = reviewP3Shown(choices,path)
            assertEquals(if(path.startsWith("bounded")) "p3-g1" else "p3-g2",shown.sourceSegmentId,path)
            assertEquals(if(path.startsWith("bounded")) "Note A" else "Note B",shown.note,path)
            val reference = choices.reference(shown)
            val decoded = codec.decodeFromString(ChoiceReference.serializer(),codec.encodeToString(ChoiceReference.serializer(),reference))
            assertEquals(shown,choices.resolve(decoded),path)
            if(path == "bounded-quick") assertEquals(reference,choices.quick(HistoryWindow(1000,2500)).single().reference)
        }
        assertEquals(before,tracker.data());tracker = Tracker(reopen(tracker.store),clock)
    }
}

fun reviewP3WindowApply(create: () -> StateStore,reopen: (StateStore) -> StateStore) {
    for(path in reviewP3Paths) for(application in ChoiceApplication.entries) {
        val clock = FakeClock(10000);val store = create();store.transaction { reviewP3WindowFixture() to Unit };var tracker = Tracker(store,clock)
        if(application == ChoiceApplication.SWITCH) tracker.start(tracker.snapshot().token,Classification(name = "Current"),"Current note")
        val choices = tracker.view().choices();val shown = reviewP3Shown(choices,path);val reference = choices.reference(shown)
        val before = tracker.data();tracker = Tracker(reopen(tracker.store),clock)
        if(application == ChoiceApplication.START) tracker.startChoice(tracker.snapshot().timerTarget!!,reference)
        else tracker.applyChoice(tracker.snapshot().timerTarget!!,reference,application)
        assertEquals(shown.selection,tracker.snapshot().session!!.selection,path)
        assertEquals(shown.note,tracker.snapshot().session!!.note,"$path $application")
        assertEquals(before.sessions.filter { it.status == SessionStatus.STOPPED },tracker.data().sessions.filter { it.id in setOf("p3-s1","p3-s2") })
        assertEquals(before.segments.filter { it.sessionId in setOf("p3-s1","p3-s2") },tracker.data().segments.filter { it.sessionId in setOf("p3-s1","p3-s2") })
    }
}

fun reviewP3WindowSourceChange(create: () -> StateStore,reopen: (StateStore) -> StateStore) {
    for(change in listOf("note","selection","deleted")) {
        val clock = FakeClock(10000);val store = create();store.transaction { reviewP3WindowFixture() to Unit };var tracker = Tracker(store,clock)
        val choices = tracker.view().choices();val shown = reviewP3Shown(choices,"bounded-quick");val reference = choices.reference(shown)
        when(change) {
            "deleted" -> tracker.deleteSession(tracker.snapshot().token,"p3-s1")
            else -> tracker.correctSession(tracker.snapshot().token,"p3-s1",listOf(Tracker.SegmentEdit("p3-g1",1000,2000,
                if(change == "selection") Classification(name = "Changed selection") else shown.selection)),if(change == "note") "Unseen changed note" else shown.note)
        }
        tracker = Tracker(reopen(tracker.store),clock);val before = tracker.data()
        assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> { tracker.view().choices().resolve(reference) }.code,change)
        assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> { tracker.startChoice(tracker.snapshot().timerTarget!!,reference) }.code,change)
        assertEquals(before,tracker.data(),change)
    }
}

fun reviewP3ReferenceCompatibility(create: () -> StateStore,reopen: (StateStore) -> StateStore) {
    val clock = FakeClock(10000);val store = create();store.transaction { reviewP3WindowFixture() to Unit };var tracker = Tracker(store,clock)
    tracker.saveType(tracker.snapshot().token,ActivityType("p3-type","Own type"))
    tracker.saveCard(tracker.snapshot().token,Card("p3-card","Own card",activityTypeId = "p3-type"))
    tracker.saveFavorite(tracker.snapshot().token,Favorite("p3-favorite","Own favorite",Classification(name = "Favorite selection")))
    val choices = tracker.view().choices()
    for(id in listOf("type:p3-type","card:p3-card","favorite:p3-favorite")) {
        val shown = requireNotNull(choices.resolve(id));val reference = choices.reference(shown)
        val legacyWire = JsonObject(codec.encodeToJsonElement(ChoiceReference.serializer(),reference).jsonObject.filterKeys { it !in setOf("sourceSessionId","sourceSegmentId") })
        val legacy = codec.decodeFromString(ChoiceReference.serializer(),legacyWire.toString())
        assertEquals(shown,choices.resolve(legacy),id)
        assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> { choices.resolve(reference.copy(sourceSessionId = "p3-s1",sourceSegmentId = "p3-g1")) }.code)
    }
    val shown = reviewP3Shown(choices,"bounded-frequent");val reference = choices.reference(shown)
    val legacy = ChoiceReference(reference.datasetId,reference.barrier,reference.choiceId,sha256(classificationKey(shown.selection).toByteArray(Charsets.UTF_8)))
    assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> { choices.resolve(legacy) }.code)
    assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> { choices.resolve(reference.copy(sourceSessionId = "p3-s2",sourceSegmentId = "p3-g2")) }.code)
    tracker.manual(tracker.snapshot().token,5000,6000,shown.selection,"Even newer note C")
    tracker = Tracker(reopen(tracker.store),clock)
    assertEquals(shown,tracker.view().choices().resolve(reference))
    tracker.startChoice(tracker.snapshot().timerTarget!!,reference)
    assertEquals("Note A",tracker.snapshot().session!!.note)
}
