package dev.notai.tracker.android

import dev.notai.tracker.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the production adapter called by the technical Focus checkbox. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TechnicalFocusSettingsTest {
    private class Store: StateStore {
        private var value = Dataset();override val changes = MutableStateFlow(value)
        var afterRead: (() -> Unit)? = null
        var beforeWrite: (() -> Unit)? = null
        override fun read(): Dataset {
            val captured = value;val hook = afterRead;afterRead = null;hook?.invoke();return captured
        }
        @Synchronized override fun <T> transaction(block: (Dataset) -> Pair<Dataset,T>): T {
            val hook = beforeWrite;beforeWrite = null;hook?.invoke()
            val (next,result) = block(value);validate(next);value = next;changes.value = next;return result
        }
    }
    @Test fun reviewP3TechnicalFocusToggleCannotOverwriteIndependentSettingsAfterRead() {
        val store = Store();val tracker = Tracker(store,FakeClock());var independentlySaved: Settings? = null
        tracker.settings(tracker.snapshot().token,tracker.data().settings.copy(surfaces = SurfaceSettings(widget = true,notification = true,overlay = true,shortcut = true)))
        store.afterRead = {
            val old = tracker.data().settings
            val next = old.copy(reportZone = "Asia/Tokyo",reportDayStartMinute = 240,
                surfaces = old.surfaces.copy(widget = false,notification = false,overlay = false,shortcut = false),
                schedule = old.schedule.copy(quietShadeEnabled = true),
                longRecordReminder = old.longRecordReminder.copy(enabled = true))
            tracker.settings(tracker.snapshot().token,next);independentlySaved = tracker.data().settings
        }
        try { TechnicalActivity().configureTechnicalFocusSignal(tracker,true) }
        catch(e: CoreError) { assertEquals(ErrorCode.STALE,e.code) }
        val saved = requireNotNull(independentlySaved)
        assertEquals(saved.copy(surfaces = saved.surfaces.copy(focusSignal = tracker.data().settings.surfaces.focusSignal)),tracker.data().settings)
        assertEquals(Mode.IDLE,tracker.snapshot().mode)
        TechnicalActivity().configureTechnicalFocusSignal(tracker,true)
        assertTrue(tracker.data().settings.surfaces.focusSignal)
        assertEquals(saved.copy(surfaces = saved.surfaces.copy(focusSignal = true)),tracker.data().settings)
    }
    @Test fun reviewP3TechnicalFocusToggleRejectsInterleavingAfterExpectedToken() {
        val store = Store();val tracker = Tracker(store,FakeClock())
        store.beforeWrite = {
            val settings = tracker.data().settings
            tracker.settings(tracker.snapshot().token,settings.copy(reportDayStartMinute = 300))
        }
        try { TechnicalActivity().configureTechnicalFocusSignal(tracker,true) }
        catch(e: CoreError) { assertEquals(ErrorCode.STALE,e.code) }
        assertEquals(300,tracker.data().settings.reportDayStartMinute)
        tracker.configureFocusSignal(tracker.snapshot().token,true)
        assertTrue(tracker.data().settings.surfaces.focusSignal);assertEquals(300,tracker.data().settings.reportDayStartMinute)
    }
}
