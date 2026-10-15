package dev.notai.tracker

import org.junit.Test
import kotlin.test.assertEquals

class ChoiceWindowTest {
    @Test fun boundedSuggestionsMatchFullScanIncludingReorderedHistory() {
        val clock = FakeClock();val first = clock.wall-3_000_000
        val sessions = (0 until 200).map { Session(id = "s$it",status = SessionStatus.STOPPED,selection = Classification(name = "Variant ${it%3}")) }
        val segments = sessions.mapIndexed { i,s -> Segment(id = "g$i",sessionId = s.id,start = first+i*10000,end = first+i*10000+1000,
            durationMs = 1000,selection = s.selection) }.reversed()
        val tracker = Tracker(MemoryStore(Dataset(sessions = sessions,segments = segments)),clock)
        val choices = tracker.view().choices()
        for(window in listOf(HistoryWindow(first,first+1),HistoryWindow(first+1000,first+10000),HistoryWindow(first+25500,first+80000),
            HistoryWindow(first+1_900_000,clock.wall),HistoryWindow(clock.wall,clock.wall+1))) {
            val expected = segments.filter { it.start < window.end && it.end!! > window.start }.groupBy { it.selection }.mapValues { (_,rows) ->
                rows.map { it.sessionId }.distinct().size to rows.maxOf { it.end!! }
            }
            val actual = choices.suggestions(SuggestionQuery(true,SuggestionOrder.FREQUENT_RECORDED,window)).items.associate { hit ->
                hit.entry.selection to (hit.suggestion!!.sessionCount to hit.suggestion.lastRecordedEnd)
            }
            assertEquals(expected,actual)
        }
    }
}
