package dev.notai.tracker.android

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.room.Room
import dev.notai.tracker.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.junit.Assert.*

/** SDK simulation and synthetic stores only, never a physical notification/device acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FocusDeliveryRaceTest {
    private val originalFactory = FocusSignals.notificationFactory
    @After fun resetFactory() { FocusSignals.notificationFactory = originalFactory }
    private class CaptureStore(private val delegate: StateStore): StateStore by delegate {
        var afterCapture: (() -> Unit)? = null
        override fun readChecked(): CheckedState {
            val captured = delegate.readChecked();val hook = afterCapture;afterCapture = null;hook?.invoke();return captured
        }
    }
    private fun memory(): StateStore = object: StateStore {
        private var value = Dataset();override val changes = MutableStateFlow(value)
        override fun read() = value
        @Synchronized override fun <T> transaction(block: (Dataset) -> Pair<Dataset,T>): T {
            val (next,result) = block(value);validate(next);value = next;changes.value = next;return result
        }
    }
    private fun race(afterPreparation: Boolean,refresh: Boolean) {
        val context = RuntimeEnvironment.getApplication();val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        for(room in listOf(false,true)) for(action in listOf("stop","advance","opt-out","barrier","boot")) {
            manager.cancelAll();context.getSharedPreferences("focus-signals",0).edit().clear().commit()
            val db = if(room) Room.inMemoryDatabaseBuilder(context,TrackerDatabase::class.java).allowMainThreadQueries().build() else null
            try {
                val clock = FakeClock();val store = CaptureStore(db?.let(::RoomStore) ?: memory());val t = Tracker(store,clock)
                t.settings(t.snapshot().token,t.data().settings.copy(surfaces = SurfaceSettings(focusSignal = true)))
                t.startFocus(t.snapshot().token,plan = FocusPlan(1000,1000));clock.advance(1000)
                val key = t.snapshot().focus!!.signalKey!!;var mutated: Dataset? = null;var calls = 0
                val change = {
                    calls++
                    when(action) {
                        "stop" -> t.stop(t.snapshot().token)
                        "advance" -> t.focusNext(t.snapshot().token)
                        "opt-out" -> t.settings(t.snapshot().token,t.data().settings.copy(surfaces = t.data().settings.surfaces.copy(focusSignal = false)))
                        // Isolated replacement-barrier fixture with an otherwise identical due phase.
                        "barrier" -> store.transaction { it.copy(controlBarrier = newId()) to Unit }
                        else -> { clock.boot = "changed-boot" }
                    }
                    mutated = t.data()
                    Unit
                }
                if(afterPreparation) FocusSignals.notificationFactory = { c -> originalFactory(c).also { change() } }
                else { FocusSignals.notificationFactory = originalFactory;store.afterCapture = change }
                if(refresh) FocusSignals.refresh(context,t)
                else assertEquals("$room/$action/$afterPreparation",FocusDeliveryStatus.BLOCKED_BEFORE_POST,FocusSignals.deliverResult(context,t).status)
                assertEquals(1,calls);assertEquals(mutated,t.data())
                assertTrue("Stale cue: $room/$action/$afterPreparation/$refresh",manager.activeNotifications.none { it.id == 43 })
                // A reserved attempt remains consumed even when final validation blocks the post.
                assertTrue(key in context.getSharedPreferences("focus-signals",0).getStringSet("delivered",emptySet()).orEmpty())
                FocusSignals.notificationFactory = originalFactory
                if(action == "opt-out") {
                    t.settings(t.snapshot().token,t.data().settings.copy(surfaces = t.data().settings.surfaces.copy(focusSignal = true)))
                    assertEquals(FocusDeliveryStatus.PREVIOUS_RECEIPT,FocusSignals.deliverResult(context,t).status)
                    assertTrue(manager.activeNotifications.none { it.id == 43 })
                }
            } finally { FocusSignals.notificationFactory = originalFactory;db?.close() }
        }
    }
    @Test fun resultRejectsMutationsAfterCapture() = race(false,false)
    @Test fun resultRejectsMutationsAfterPreparation() = race(true,false)
    @Test fun refreshRejectsMutationsAfterCapture() = race(false,true)
    @Test fun refreshRejectsMutationsAfterPreparation() = race(true,true)
    @Test fun unchangedPhasePostsOnceAndFailedPreparationNeverReplays() {
        val context = RuntimeEnvironment.getApplication();val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        context.getSharedPreferences("focus-signals",0).edit().clear().commit();manager.cancelAll()
        val clock = FakeClock();val t = Tracker(memory(),clock)
        t.settings(t.snapshot().token,t.data().settings.copy(surfaces = SurfaceSettings(focusSignal = true)))
        t.startFocus(t.snapshot().token,plan = FocusPlan(1000,1000));clock.advance(1000)
        val before = t.data();assertEquals(FocusDeliveryStatus.POST_ACCEPTED,FocusSignals.deliverResult(context,t).status)
        assertEquals(FocusDeliveryStatus.PREVIOUS_RECEIPT,FocusSignals.deliverResult(context,t).status);assertEquals(before,t.data())
        manager.cancelAll();FocusSignals.notificationFactory = { error("Legacy receipt lookup must not prepare/post") }
        @Suppress("DEPRECATION") val delivered = FocusSignals.deliver(context,t.snapshot().focus!!)
        assertTrue(delivered);assertTrue(manager.activeNotifications.isEmpty())
        t.focusNext(t.snapshot().token);clock.advance(1000)
        assertEquals(FocusDeliveryStatus.OUTCOME_UNKNOWN,FocusSignals.deliverResult(context,t).status)
        FocusSignals.notificationFactory = originalFactory
        assertEquals(FocusDeliveryStatus.PREVIOUS_RECEIPT,FocusSignals.deliverResult(context,t).status);assertTrue(manager.activeNotifications.isEmpty())
    }
    @Test fun staleAlarmWithoutStoreDoesNotBootstrapAnEmptyDatabase() = runBlocking(Dispatchers.IO) {
        val context = RuntimeEnvironment.getApplication()
        TrackerRuntime.beginRecovery();TrackerRuntime.endRecovery()
        // These paths belong only to this Robolectric synthetic Application fixture.
        DatabaseFiles.paths(context).forEach { it.delete() }
        try {
            assertFalse(DatabaseFiles.exists(context))
            FocusAlarmReceiver().handleCurrent(context,"stale-focus-id",0)
            assertFalse("Stale background callback created a new tracker database",DatabaseFiles.exists(context))
        } finally {
            TrackerRuntime.beginRecovery();TrackerRuntime.endRecovery()
            DatabaseFiles.paths(context).forEach { it.delete() }
        }
    }
}
