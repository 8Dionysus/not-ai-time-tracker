package dev.notai.tracker

import kotlin.test.*

fun selectiveExclusionsScenario(store: StateStore) {
    val clock = FakeClock();val tracker = Tracker(store,clock)
    val rule = ScheduleRule(Classification(name = "Original"),"2026-10-10",extraDates = setOf("2026-10-10"))
    for(id in listOf("selected","unselected")) {
        tracker.scheduleCommand(ScheduleCommand("create-$id",tracker.snapshot().token,ScheduleAction.CREATE,id,rule = rule))
        val key = OccurrenceKey(id,"2026-10-10")
        tracker.scheduleCommand(ScheduleCommand("skip-$id",tracker.snapshot().token,ScheduleAction.OVERRIDE,id,1,occurrence = key,
            override = OccurrenceOverride(key,OverrideOperation.SKIP)))
    }
    val engine = SelectiveTransfer(tracker);val before = tracker.data()
    for(key in listOf(OccurrenceKey("selected","2026-10-11"),OccurrenceKey("unselected","2026-10-10"))) {
        val error = assertFailsWith<CoreError> {
            engine.export(TransferSelection(seriesIds = setOf("selected"),excludedOverrides = setOf(key)))
        }
        assertEquals(ErrorCode.INVALID_INPUT,error.code);assertEquals(before,tracker.data())
    }
    val exported = engine.export(TransferSelection(seriesIds = setOf("selected"),excludedOverrides = setOf(OccurrenceKey("selected","2026-10-10"))))
    assertTrue(exported.groups.single().content.overrides.isEmpty())
    assertTrue("OVERRIDES_EXCLUDED:1" in exported.losses)
    assertEquals(before,tracker.data())
}
