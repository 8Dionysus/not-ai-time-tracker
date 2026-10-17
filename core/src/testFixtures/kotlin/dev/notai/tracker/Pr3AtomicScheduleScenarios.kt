package dev.notai.tracker

import java.io.IOException
import kotlin.test.*

/** Fail after the complete proposed graph exists, before the underlying store commits it. */
private class Pr3CommitFault(private val delegate: StateStore): StateStore by delegate {
    var reject = false
    var proposed: Dataset? = null
    override fun <T> checkedTransaction(block: (CheckedState) -> CheckedCommit<T>): T = delegate.checkedTransaction { old ->
        block(old).also {
            proposed = it.state.data
            if(reject) throw IOException("PR3 fixture: before-commit storage fault")
        }
    }
}
private fun pr3Plan(t: Tracker,weekly: Boolean = false) {
    val rule = ScheduleRule(Classification(name = "Planned"),"2026-10-10",
        weekdays = if(weekly) setOf(6) else emptySet(),extraDates = if(weekly) emptySet() else setOf("2026-10-10"))
    t.scheduleCommand(ScheduleCommand("create",t.snapshot().token,ScheduleAction.CREATE,"old",rule = rule))
}
private fun pr3Start(t: Tracker) = ScheduleCommand("planned-start",t.snapshot().token,ScheduleAction.START_OR_SWITCH,"old",1,
    occurrence = OccurrenceKey("old","2026-10-10"))
private fun pr3Replace(t: Tracker,weekly: Boolean) = ScheduleCommand("replace",t.snapshot().token,ScheduleAction.REPLACE,"old",1,
    rule = ScheduleRule(Classification(name = "Replacement"),"2026-10-10",weekdays = if(weekly) setOf(6) else emptySet(),
        extraDates = if(weekly) emptySet() else setOf("2026-10-10")),replacementSeriesId = "new")

fun pr3PlannedStartRefusals(create: () -> StateStore,reopen: (StateStore) -> StateStore) {
    for(reason in listOf("token","series","override","skipped","started","classification","focus","recovery")) {
        val clock = FakeClock();var t = Tracker(create(),clock);pr3Plan(t)
        t.start(t.snapshot().token,Classification(name = "Keep running"),"Original note");clock.advance(2000)
        var command = pr3Start(t)
        when(reason) {
            "token" -> t.settings(t.snapshot().token,t.data().settings.copy(reportDayStartMinute = 240))
            "series" -> command = command.copy(expectedSeriesRevision = 0)
            "override" -> command = command.copy(expectedOverrideRevision = 99)
            "skipped" -> t.scheduleCommand(ScheduleCommand("skip",t.snapshot().token,ScheduleAction.OVERRIDE,"old",1,
                occurrence = command.occurrence,override = OccurrenceOverride(command.occurrence!!,OverrideOperation.SKIP)))
            "started" -> { t.scheduleCommand(command);command = command.copy(key = "another-start") }
            "classification" -> {
                t.saveCard(t.snapshot().token,Card("planned-card","Card"))
                t.scheduleCommand(ScheduleCommand("edit",t.snapshot().token,ScheduleAction.UPDATE_FUTURE,"old",1,
                    rule = t.data().schedule.series.single().revisions.last().rule.copy(selection = t.selection("planned-card")),effectiveFrom = "2026-10-10"))
                t.saveCard(t.snapshot().token,t.data().cards.single { it.id == "planned-card" }.copy(archived = true))
                command = command.copy(expectedSeriesRevision = 2)
            }
            "focus" -> { t.stop(t.snapshot().token);t.startFocus(t.snapshot().token) }
            "recovery" -> clock.boot = "different-boot"
        }
        if(reason != "token") command = command.copy(expected = t.snapshot().token)
        val before = t.data();assertFailsWith<CoreError>(reason) { t.scheduleCommand(command) }
        assertEquals(before,t.data(),reason)
        t = Tracker(reopen(t.store),clock);assertEquals(before,t.data(),"$reason after reopen")
    }
}
fun pr3PlannedStartOneCommit(create: () -> StateStore,reopen: (StateStore) -> StateStore) {
    for(mode in listOf("idle","running","paused")) {
        val clock = FakeClock();var t = Tracker(create(),clock);pr3Plan(t)
        if(mode != "idle") { t.start(t.snapshot().token,Classification(name = "Old"));clock.advance(1500) }
        if(mode == "paused") { t.pause(t.snapshot().token);clock.advance(1000) }
        val before = t.data();val oldSession = t.snapshot().session?.id;val command = pr3Start(t)
        val result = t.scheduleCommand(command);val after = t.data()
        assertEquals(before.revision+1,after.revision,mode);assertEquals(before.timerEpoch+1,after.timerEpoch,mode)
        assertEquals("Planned",t.snapshot().session!!.selection.name)
        assertEquals(result.sessionId,after.schedule.links.single().sessionId)
        oldSession?.let { id ->
            assertEquals(SessionStatus.STOPPED,after.sessions.single { it.id == id }.status)
            assertEquals(1500L,after.segments.filter { it.sessionId == id }.sumOf { it.durationMs })
            if(mode == "running") assertEquals(clock.wall,after.segments.single { it.sessionId == id }.end)
        }
        assertEquals(clock.wall,after.segments.single { it.sessionId == result.sessionId }.start)
        t = Tracker(reopen(t.store),clock)
        assertEquals(result,t.scheduleCommand(command));assertEquals(after,t.data(),"Replay must not stop the new timer")
    }
}
fun pr3PlannedStartStorageFailure(create: () -> StateStore,reopen: (StateStore) -> StateStore) {
    val clock = FakeClock();val underlying = create();val fault = Pr3CommitFault(underlying);val t = Tracker(fault,clock)
    pr3Plan(t);t.start(t.snapshot().token,Classification(name = "Original"));clock.advance(1000)
    val before = t.data();val original = t.snapshot().session!!.id;val command = pr3Start(t);fault.reject = true
    assertEquals(ErrorCode.STORAGE,assertFailsWith<CoreError> { t.scheduleCommand(command) }.code)
    assertEquals(SessionStatus.STOPPED,fault.proposed!!.sessions.single { it.id == original }.status)
    assertEquals(2,fault.proposed!!.sessions.size)
    assertEquals(before,t.data());val resumed = Tracker(reopen(underlying),clock);assertEquals(before,resumed.data())
    resumed.scheduleCommand(command)
    assertEquals(before.revision+1,resumed.data().revision);assertEquals(2,resumed.data().sessions.size)
}
fun pr3PlanReplacementRefusals(create: () -> StateStore,reopen: (StateStore) -> StateStore) {
    for(weekly in listOf(false,true)) for(reason in listOf("token","series","classification","invalid-rule","existing-id")) {
        val clock = FakeClock();var t = Tracker(create(),clock);pr3Plan(t,!weekly)
        var command = pr3Replace(t,weekly)
        when(reason) {
            "token" -> t.settings(t.snapshot().token,t.data().settings.copy(reportDayStartMinute = 240))
            "series" -> command = command.copy(expectedSeriesRevision = 0)
            "classification" -> command = command.copy(rule = command.rule!!.copy(selection = Classification(activityTypeId = "missing")))
            "invalid-rule" -> command = command.copy(rule = command.rule!!.copy(weekdays = setOf(9)))
            "existing-id" -> command = command.copy(replacementSeriesId = "old")
        }
        val before = t.data();assertFailsWith<CoreError>("$weekly/$reason") { t.scheduleCommand(command) }
        assertEquals(before,t.data(),"$weekly/$reason")
        t = Tracker(reopen(t.store),clock);assertEquals(before,t.data());assertEquals(ScheduleState.ACTIVE,t.data().schedule.series.single().state)
    }
}
fun pr3PlanReplacementOneCommit(create: () -> StateStore,reopen: (StateStore) -> StateStore) {
    for(weekly in listOf(false,true)) {
        val clock = FakeClock();var t = Tracker(create(),clock);pr3Plan(t,!weekly)
        t.scheduleCommand(pr3Start(t));clock.advance(1000);t.stop(t.snapshot().token)
        val before = t.data();val command = pr3Replace(t,weekly);val result = t.scheduleCommand(command);val after = t.data()
        assertEquals(before.revision+1,after.revision);assertEquals(before.timerEpoch,after.timerEpoch)
        assertEquals(before.sessions,after.sessions);assertEquals(before.segments,after.segments)
        assertEquals(before.schedule.links,after.schedule.links)
        assertEquals(before.schedule.series.single().copy(state = ScheduleState.ARCHIVED,revision = 2),after.schedule.series.single { it.id == "old" })
        assertEquals(command.rule,after.schedule.series.single { it.id == "new" }.revisions.single().rule)
        assertEquals("new",result.seriesId)
        t = Tracker(reopen(t.store),clock);assertEquals(result,t.scheduleCommand(command));assertEquals(after,t.data())
    }
}
fun pr3PlanReplacementStorageFailure(create: () -> StateStore,reopen: (StateStore) -> StateStore) {
    for(weekly in listOf(false,true)) {
        val clock = FakeClock();val underlying = create();val fault = Pr3CommitFault(underlying);val t = Tracker(fault,clock)
        pr3Plan(t,!weekly);val before = t.data();val command = pr3Replace(t,weekly);fault.reject = true
        assertEquals(ErrorCode.STORAGE,assertFailsWith<CoreError> { t.scheduleCommand(command) }.code)
        assertEquals(ScheduleState.ARCHIVED,fault.proposed!!.schedule.series.single { it.id == "old" }.state)
        assertEquals(2,fault.proposed!!.schedule.series.size)
        assertEquals(before,t.data());val resumed = Tracker(reopen(underlying),clock);assertEquals(before,resumed.data())
        resumed.scheduleCommand(command)
        assertEquals(before.revision+1,resumed.data().revision);assertEquals(2,resumed.data().schedule.series.size)
    }
}
