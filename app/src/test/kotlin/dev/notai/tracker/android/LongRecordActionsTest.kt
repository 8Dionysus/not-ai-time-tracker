package dev.notai.tracker.android

import android.app.PendingIntent
import dev.notai.tracker.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LongRecordActionsTest {
    private class Store: StateStore {
        private var data = Dataset()
        override val changes = MutableStateFlow(data)
        override fun read() = data
        override fun <T> transaction(block: (Dataset) -> Pair<Dataset,T>): T {
            val (next,result) = block(data);validate(next);data = next;changes.value = next;return result
        }
    }
    @Test fun retainedActionsKeepTheirTicketAndTokenWhenNewActionsAreIssued() {
        val context = RuntimeEnvironment.getApplication();val token = Token("dataset",1,"session")
        val old = LongRecordActions.pending(context,"a".repeat(64),token,LongRecordAction.STOP)
        val same = LongRecordActions.pending(context,"a".repeat(64),token,LongRecordAction.STOP)
        val newer = LongRecordActions.pending(context,"b".repeat(64),token.copy(revision = 2),LongRecordAction.STOP)
        val rerendered = LongRecordActions.pending(context,"a".repeat(64),token.copy(revision = 3),LongRecordAction.STOP)
        val snooze = LongRecordActions.pending(context,"a".repeat(64),token,LongRecordAction.SNOOZE)
        assertEquals(old,same);assertEquals(4,setOf(old,newer,rerendered,snooze).size)
        assertEquals(Triple("a".repeat(64),token,LongRecordAction.STOP),LongRecordActions.read(shadowOf(old).savedIntent))
        assertEquals(Triple("b".repeat(64),token.copy(revision = 2),LongRecordAction.STOP),LongRecordActions.read(shadowOf(newer).savedIntent))
        assertEquals(Triple("a".repeat(64),token,LongRecordAction.SNOOZE),LongRecordActions.read(shadowOf(snooze).savedIntent))
        assertTrue(old.isImmutable)
    }
    @Test fun oldStopAndSnoozeAreRejectedAfterTheNextCheckinInsteadOfRetargetingIt() {
        val context = RuntimeEnvironment.getApplication();val clock = FakeClock();val t = Tracker(Store(),clock)
        t.start(t.snapshot().token);val local = AttentionReminderStore();val platform = AttentionReminderPlatform()
        val controller = LongRecordReminder(t,local,platform)
        val first = controller.configure(t.snapshot().token,LongRecordPreference(true,100,20),local.value.revision).ticket!!
        clock.advance(100);controller.onAlarm(first.hash)
        val token = t.snapshot().token
        val retained = listOf(LongRecordAction.STOP,LongRecordAction.SNOOZE).map { LongRecordActions.pending(context,first.hash,token,it) }
        val next = controller.respond(token,first.hash,LongRecordAction.SNOOZE).state.ticket!!
        clock.advance(20);controller.onAlarm(next.hash)
        for((i,answer) in listOf(LongRecordAction.STOP,LongRecordAction.SNOOZE).withIndex()) {
            LongRecordActions.pending(context,next.hash,t.snapshot().token,answer)
            val (hash,originalToken,action) = requireNotNull(LongRecordActions.read(shadowOf(retained[i]).savedIntent))
            val before = t.data();val control = local.value
            val failure = assertThrows(CoreError::class.java) { controller.respond(originalToken,hash,action) }
            assertEquals(ErrorCode.STALE,failure.code);assertEquals(before,t.data());assertEquals(control,local.value)
            assertEquals(Mode.RUNNING,t.snapshot().mode)
        }
    }
}
