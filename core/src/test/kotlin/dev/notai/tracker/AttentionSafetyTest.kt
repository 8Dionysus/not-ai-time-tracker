package dev.notai.tracker

import java.io.IOException
import org.junit.Test
import kotlin.test.*

class AttentionSafetyTest {
    private class ReminderFixture {
        val clock = FakeClock();val tracker = Tracker(MemoryStore(),clock)
        val store = AttentionReminderStore();val platform = AttentionReminderPlatform()
        val controller = LongRecordReminder(tracker,store,platform)
        init { tracker.start(tracker.snapshot().token);controller.configure(tracker.snapshot().token,LongRecordPreference(true,100,20)) }
        val ticket get() = store.value.pending!!
    }
    @Test fun permissionsAreNotPreferenceOrDeliveryAndFocusNeverUsesThisReminder() {
        val f = ReminderFixture();val original = f.ticket;val data = f.tracker.data()
        f.platform.capability = ScheduleDeliveryAvailability(setOf(ScheduleDeliveryBlocker.PERMISSION))
        f.controller.synchronize();assertTrue(f.store.value.preference.enabled);assertNull(f.platform.pending)
        f.clock.advance(100);assertEquals(LongRecordOutcome.NOT_POSTED,f.controller.onAlarm(original.hash).outcome)
        assertNull(f.store.value.attempt);assertEquals(data,f.tracker.data())
        f.platform.capability = ScheduleDeliveryAvailability();f.controller.synchronize()
        assertEquals(LongRecordOutcome.POST_ACCEPTED,f.controller.onAlarm(original.hash).outcome)
        f.tracker.stop(f.tracker.snapshot().token);f.tracker.startFocus(f.tracker.snapshot().token)
        assertNull(f.controller.synchronize().ticket)
    }
    @Test fun postOutcomeLossAndFailedReceiptNeverReplay() {
        val f = ReminderFixture();val ticket = f.ticket;f.clock.advance(100)
        f.platform.postAction = { throw IOException("accepted, reply lost") }
        assertEquals(LongRecordOutcome.OUTCOME_UNKNOWN,f.controller.onAlarm(ticket.hash).outcome)
        val reopened = LongRecordReminder(f.tracker,f.store,f.platform)
        assertEquals(LongRecordOutcome.PREVIOUS_ATTEMPT,reopened.onAlarm(ticket.hash).outcome);assertEquals(1,f.platform.posts)
        val g = ReminderFixture();val other = g.ticket;g.clock.advance(100)
        g.store.beforeWrite = { if(it.attempt?.status == ScheduleAttemptStatus.RESERVED) throw IOException("no durable reserve") }
        assertEquals(ErrorCode.STORAGE,assertFailsWith<CoreError> { g.controller.onAlarm(other.hash) }.code)
        assertEquals(0,g.platform.posts)
    }
    @Test fun staleStopCannotStopAnotherSessionAndChangedRenderingNeverPosts() {
        val f = ReminderFixture();val old = f.ticket;f.clock.advance(100)
        f.tracker.stop(f.tracker.snapshot().token);f.tracker.start(f.tracker.snapshot().token)
        val before = f.tracker.data()
        assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> { f.controller.respond(f.tracker.snapshot().token,old.hash,LongRecordAction.STOP) }.code)
        assertEquals(before,f.tracker.data())
        val g = ReminderFixture();val ticket = g.ticket;g.clock.advance(100)
        g.platform.prepareAction = { g.tracker.stop(g.tracker.snapshot().token) }
        assertEquals(LongRecordOutcome.NOT_POSTED,g.controller.onAlarm(ticket.hash).outcome);assertEquals(0,g.platform.posts)
    }
    @Test fun rebootAndPauseInvalidateWakeupsWithoutInventingARecordedEnd() {
        val f = ReminderFixture();val ticket = f.ticket;val before = f.tracker.data()
        f.clock.boot = "other-boot";f.clock.advance(100)
        assertEquals(LongRecordOutcome.STALE,f.controller.onAlarm(ticket.hash).outcome)
        assertEquals(before,f.tracker.data());assertEquals(Mode.RECOVERY,f.tracker.snapshot().mode)
        val g = ReminderFixture();val old = g.ticket;g.clock.advance(50);g.tracker.pause(g.tracker.snapshot().token)
        g.clock.advance(100);assertEquals(LongRecordOutcome.STALE,g.controller.onAlarm(old.hash).outcome);assertEquals(0,g.platform.posts)
    }
    @Test fun disableIsExplicitAndNoDomainMutationWhileStaleSettingsAreRejected() {
        val f = ReminderFixture();val ticket = f.ticket;f.clock.advance(100)
        val before = f.tracker.data()
        f.controller.respond(f.tracker.snapshot().token,ticket.hash,LongRecordAction.DISABLE)
        assertFalse(f.store.value.preference.enabled);assertEquals(before,f.tracker.data())
        val token = f.tracker.snapshot().token;f.tracker.pause(token)
        assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> { f.controller.configure(token,LongRecordPreference(true)) }.code)
        assertFalse(f.store.value.preference.enabled)
    }
    @Test fun focusDenialAndProcessDeathNeverReassertAndUserRuleChangesArePreserved() {
        val clock = FakeClock();val tracker = Tracker(MemoryStore(),clock)
        tracker.startFocus(tracker.snapshot().token)
        val state = AttentionEnvironmentStore();val dnd = AttentionDistractions(state);val music = AttentionMusic()
        val controller = FocusEnvironment(tracker,state,dnd,music)
        dnd.capability = FocusEffectStatus.ACCESS_REQUIRED
        val denied = controller.begin(tracker.snapshot().token,newId(),FocusEnvironmentChoice(distractions = true))
        assertEquals(FocusEffectStatus.ACCESS_REQUIRED,denied.distractions);assertEquals(0,dnd.activations)
        dnd.capability = FocusEffectStatus.READY;controller.synchronize();assertEquals(0,dnd.activations)
        controller.begin(tracker.snapshot().token,newId(),FocusEnvironmentChoice(true,true,"chosen-local"))
        val stale = tracker.snapshot().token;tracker.labelCurrent(stale,Classification(name = "late label"))
        val releases = dnd.releases
        assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> { controller.begin(stale,newId(),FocusEnvironmentChoice()) }.code)
        assertEquals(releases,dnd.releases)
        clock.process = "new-process"
        val recovered = controller.synchronize();assertNull(recovered.request);assertEquals(1,dnd.activations)
        controller.begin(tracker.snapshot().token,newId(),FocusEnvironmentChoice(distractions = true))
        dnd.released = FocusEffectStatus.USER_CHANGED_RULE
        val preserved = controller.stop();assertEquals(FocusEffectStatus.USER_CHANGED_RULE,preserved.distractions)
        assertFalse(preserved.cleanupPending);assertNull(state.value.lease)
    }
    @Test fun revocationRetainsAnExactLeaseUntilCleanupCanSucceed() {
        val clock = FakeClock();val tracker = Tracker(MemoryStore(),clock);tracker.startFocus(tracker.snapshot().token)
        val state = AttentionEnvironmentStore();val dnd = AttentionDistractions(state)
        val controller = FocusEnvironment(tracker,state,dnd,AttentionMusic())
        controller.begin(tracker.snapshot().token,newId(),FocusEnvironmentChoice(distractions = true))
        val lease = state.value.lease;dnd.capability = FocusEffectStatus.ACCESS_REQUIRED;dnd.released = FocusEffectStatus.ACCESS_REQUIRED
        val blocked = controller.synchronize();assertTrue(blocked.cleanupPending);assertEquals(lease,state.value.lease)
        dnd.capability = FocusEffectStatus.READY;dnd.released = FocusEffectStatus.RELEASED
        // A denied cleanup is never converted into a fresh activation.
        controller.stop();assertNull(state.value.lease);assertEquals(1,dnd.activations)
    }
}
