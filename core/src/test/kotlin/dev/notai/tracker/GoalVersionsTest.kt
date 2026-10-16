package dev.notai.tracker

import java.time.*
import org.junit.Test
import kotlin.test.*

class GoalVersionsTest {
    @Test fun dailyAndMonthlyChangesKeepEarlierPeriodsAndCurrentCorrectionIsExplicit() {
        for(period in listOf(GoalPeriod.DAY,GoalPeriod.MONTH)) {
            val clock = FakeClock(Instant.parse("2026-10-16T02:00:00Z").toEpochMilli())
            val t = Tracker(MemoryStore(),clock)
            val g = Goal("target","Recorded time",GoalMetric.DURATION_MS,100,period = period,dayStartMinute = 240)
            t.saveGoal(t.snapshot().token,g)
            t.saveGoal(t.snapshot().token,g.copy(target = 200))
            val current = LocalDate.parse("2026-10-15")
            val next = if(period == GoalPeriod.DAY) current.plusDays(1) else LocalDate.parse("2026-11-01")
            assertEquals(100,t.goalProgress(g.id,current).goal.target)
            assertEquals(200,t.goalProgress(g.id,next).goal.target)
            t.saveGoal(t.snapshot().token,g.copy(target = 150),GoalEditMode.CORRECT_CURRENT)
            assertEquals(150,t.goalProgress(g.id,current).goal.target)
            assertEquals(200,t.goalProgress(g.id,next).goal.target)
            val before = if(period == GoalPeriod.DAY) current.minusDays(1) else LocalDate.parse("2026-09-30")
            assertEquals(100,t.goalProgress(g.id,before).goal.target)
        }
    }
    @Test fun fixedWindowRequiresCurrentExplicitCorrectionAndCalendarChangeIsAtomicRefusal() {
        val clock = FakeClock(Instant.parse("2026-10-16T12:00:00Z").toEpochMilli());val t = Tracker(MemoryStore(),clock)
        val g = Goal("fixed","Fixed",GoalMetric.SESSION_COUNT,3,period = GoalPeriod.FIXED,startDate = "2026-10-01",endDateExclusive = "2026-11-01")
        t.saveGoal(t.snapshot().token,g);val before = t.data()
        assertFailsWith<CoreError> { t.saveGoal(t.snapshot().token,g.copy(target = 4)) };assertEquals(before,t.data())
        t.saveGoal(t.snapshot().token,g.copy(target = 4),GoalEditMode.CORRECT_CURRENT)
        assertEquals(4,t.goalProgress(g.id).goal.target)
        val stable = t.data()
        assertFailsWith<CoreError> { t.saveGoal(t.snapshot().token,g.copy(zone = "Europe/Berlin"),GoalEditMode.CORRECT_CURRENT) }
        assertEquals(stable,t.data())
        clock.wall = Instant.parse("2026-12-01T12:00:00Z").toEpochMilli()
        assertFailsWith<CoreError> { t.saveGoal(t.snapshot().token,g.copy(target = 5),GoalEditMode.CORRECT_CURRENT) };assertEquals(stable,t.data())
    }
    @Test fun relativeQueriesMoveByCivilCalendarAndKeepTheirOwnPolicyAfterSettingsChange() {
        val clock = FakeClock(Instant.parse("2026-03-30T02:30:00Z").toEpochMilli());val t = Tracker(MemoryStore(),clock)
        val q = SavedReportQuery("last-week","Last week",ReportWindow(ReportWindowKind.PREVIOUS_WEEK),policy = ReportingDayPolicy("Europe/Berlin",240))
        t.saveReportQuery(t.snapshot().token,q)
        val a = t.view().savedReport(q.id).report
        assertEquals(7*24*3_600_000L-3_600_000L,a.end-a.start)
        t.configureReportingDay(t.snapshot().token,ReportingDayPolicy("Pacific/Auckland",600))
        assertEquals(a,t.view().savedReport(q.id).report)
        clock.wall = Instant.parse("2026-04-06T02:30:00Z").toEpochMilli()
        val b = t.view().savedReport(q.id).report
        assertEquals(a.end,b.start);assertEquals(7*24*3_600_000L,b.end-b.start)
    }
    @Test fun goalHistoryAndSavedFilterSnapshotsCannotBeRewrittenThroughInputCollections() {
        val clock = FakeClock();val t = Tracker(MemoryStore(),clock)
        val tags = mutableSetOf("reading")
        val g = Goal("immutable","Immutable",GoalMetric.DISTINCT_RECORDED_DAYS,2,filter = Filter(tags = tags))
        t.saveGoal(t.snapshot().token,g);t.saveGoal(t.snapshot().token,g.copy(target = 3));tags.clear()
        val stored = t.data().goals.single()
        assertTrue(stored.filter.tags.contains("reading"));assertTrue(stored.revisions.all { it.definition.filter.tags.contains("reading") })
        assertFailsWith<UnsupportedOperationException> { (stored.revisions as MutableList).clear() }
        val queryTags = mutableSetOf("quiet")
        t.saveReportQuery(t.snapshot().token,SavedReportQuery("immutable-query","Immutable query",filter = Filter(tags = queryTags)))
        queryTags.clear();assertEquals(setOf("quiet"),t.view().savedReports().single().filter.tags)
        val future = t.data().copy(goals = listOf(stored.copy(revisions = stored.revisions.map { it.copy(recordedAt = clock.wall+1) })))
        assertFailsWith<CoreError> { Backups.encode(future,clock.now()) }
    }
}
