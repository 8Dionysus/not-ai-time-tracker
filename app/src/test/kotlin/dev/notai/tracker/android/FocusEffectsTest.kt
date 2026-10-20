package dev.notai.tracker.android

import android.app.NotificationManager
import dev.notai.tracker.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** SDK simulation only: the opt-in begins one request per work phase, never during a break, and DND access is honest. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FocusEffectsTest {
    private fun memory(): StateStore = object: StateStore {
        private var value = Dataset();override val changes = MutableStateFlow(value)
        override fun read() = value
        @Synchronized override fun <T> transaction(block: (Dataset) -> Pair<Dataset,T>): T {
            val (next,result) = block(value);validate(next);value = next;changes.value = next;return result
        }
    }
    @Test fun oneBeginPerWorkPhaseAndNothingWhenOff() {
        val c = RuntimeEnvironment.getApplication()
        FocusEffects.install(c)
        val clock = FakeClock(); val t = Tracker(memory(), clock)
        val control = object: FocusEnvironmentStore {
            var v = FocusEnvironmentControl()
            override fun <T> locked(block: () -> T): T = synchronized(this) { block() }
            override fun read() = v
            override fun writeDurably(value: FocusEnvironmentControl) { v = value }
        }
        val dnd = object: FocusDistractionPort {
            override fun availability() = if (FocusEffects.dndAccess(c)) FocusEffectStatus.READY else FocusEffectStatus.ACCESS_REQUIRED
            override fun prepare(request: FocusEnvironmentRequest, previous: FocusDndLease?) = FocusDndPreparation(previous, availability())
            override fun activate(lease: FocusDndLease) = FocusEffectStatus.APPLY_ACCEPTED
            override fun release(lease: FocusDndLease) = FocusEffectStatus.RELEASED
            override fun scheduleRelease(request: FocusEnvironmentRequest) {}
            override fun cancelRelease() {}
        }
        val music = object: FocusMusicPort {
            override fun availability(localId: String?) = FocusEffectStatus.SOURCE_REQUIRED
            override fun prepare(requestId: String, localId: String): PreparedFocusMusic? = null
            override fun status(requestId: String) = FocusEffectStatus.OFF
            override fun stop(requestId: String) = FocusEffectStatus.RELEASED
        }
        val focus = FocusEnvironment(t, control, dnd, music)
        FocusEffects.write(c, FocusEffects.Choice())
        t.startFocus(t.snapshot().token, plan = FocusPlan(60_000, 60_000))
        FocusEffects.maybeBegin(c, t, focus, focus.synchronize())
        assertNull("off means no request", focus.synchronize().request)

        FocusEffects.write(c, FocusEffects.Choice(dnd = true))
        shadowOf(c.getSystemService(NotificationManager::class.java)).setNotificationPolicyAccessGranted(false)
        FocusEffects.maybeBegin(c, t, focus, focus.synchronize())
        val first = focus.synchronize()
        assertNotNull(first.request)
        assertEquals(FocusEffectStatus.ACCESS_REQUIRED, first.distractions)
        assertFalse(FocusEffects.dndAccess(c))
        FocusEffects.maybeBegin(c, t, focus, first)
        assertEquals("same phase is not begun again", first.request!!.id, focus.synchronize().request!!.id)
    }
}
