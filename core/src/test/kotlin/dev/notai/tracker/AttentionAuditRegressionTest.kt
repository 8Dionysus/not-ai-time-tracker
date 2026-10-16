package dev.notai.tracker

import java.io.IOException
import org.junit.Test
import kotlin.test.*

/** Motivating audit defects: a receipt was erased by temporary clock loss; visible content
 * outlived its source; a local command could replay because the Dataset token stayed equal. */
class AttentionAuditRegressionTest {
    private class Fixture {
        val clock = FakeClock();val tracker = Tracker(MemoryStore(),clock)
        val store = AttentionReminderStore();val platform = AttentionReminderPlatform()
        val controller = LongRecordReminder(tracker,store,platform)
        init {
            tracker.start(tracker.snapshot().token)
            controller.configure(tracker.snapshot().token,LongRecordPreference(true,100,20),store.value.revision)
            clock.advance(100)
        }
        val ticket get() = store.value.pending!!
    }
    @Test fun changingTheTimerOrItsDisplayedLabelRevokesOldContentWithoutAnotherPost() {
        val f = Fixture();val ticket = f.ticket;f.controller.onAlarm(ticket.hash)
        assertNotNull(f.platform.notice)
        f.tracker.labelCurrent(f.tracker.snapshot().token,Classification(name = "new label"))
        f.controller.synchronize();assertNull(f.platform.notice)
        assertEquals(LongRecordOutcome.PREVIOUS_ATTEMPT,f.controller.onAlarm(ticket.hash).outcome)
        assertEquals(1,f.platform.posts)
        val g = Fixture();val old = g.ticket;g.controller.onAlarm(old.hash);assertNotNull(g.platform.notice)
        g.tracker.switch(g.tracker.snapshot().token,Classification())
        val next = g.controller.synchronize().ticket!!
        assertNull(g.platform.notice);assertNotEquals(old.target.sessionId,next.target.sessionId)
        assertEquals(next,g.store.value.pending);assertEquals(1,g.platform.posts)
    }
    @Test fun uncertainPostSurvivesColdDecodeAndTemporaryClockLoss() {
        val f = Fixture();val ticket = f.ticket
        f.platform.postAction = { throw IOException("reply lost after notify") }
        assertEquals(LongRecordOutcome.OUTCOME_UNKNOWN,f.controller.onAlarm(ticket.hash).outcome)
        val saved = codec.encodeToString(f.store.value)
        val cold = AttentionReminderStore().also { it.value = codec.decodeFromString(saved) }
        val controller = LongRecordReminder(f.tracker,cold,f.platform)
        f.clock.wall += 5000
        assertNull(controller.synchronize().ticket);assertNotNull(cold.value.attempt)
        f.clock.wall -= 5000
        assertEquals(LongRecordOutcome.PREVIOUS_ATTEMPT,controller.onAlarm(ticket.hash).outcome)
        assertEquals(ScheduleAttemptStatus.UNKNOWN,cold.value.attempt!!.status);assertEquals(1,f.platform.posts)
    }
    @Test fun localRevisionRejectsAStaleCommandBeforeChangingPortablePreferences() {
        val f = Fixture();val token = f.tracker.snapshot().token;val revision = f.store.value.revision
        val before = f.tracker.data()
        f.controller.onAlarm(f.ticket.hash)
        assertEquals(token,f.tracker.snapshot().token)
        assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> {
            f.controller.configure(token,LongRecordPreference(false),revision)
        }.code)
        assertTrue(f.tracker.data().settings.longRecordReminder.enabled);assertEquals(before,f.tracker.data())
    }
    @Test fun completedFocusCommandCannotReplayAndParkedRuleIsReusedOnlyExplicitly() {
        val clock = FakeClock();val tracker = Tracker(MemoryStore(),clock);tracker.startFocus(tracker.snapshot().token)
        val state = AttentionEnvironmentStore();val dnd = AttentionDistractions(state);val audio = AttentionMusic()
        val controller = FocusEnvironment(tracker,state,dnd,audio)
        val token = tracker.snapshot().token;val revision = state.value.revision;val id = newId()
        val choice = FocusEnvironmentChoice(distractions = true)
        controller.begin(token,id,choice,revision);controller.stop()
        assertEquals(token,tracker.snapshot().token);assertNotNull(state.value.lease);assertFalse(state.value.leaseActive)
        assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> { controller.begin(token,id,choice,revision) }.code)
        controller.synchronize();assertEquals(1,dnd.activations)
        controller.begin(token,newId(),choice,state.value.revision)
        assertEquals(2,dnd.activations);assertEquals(1,dnd.creations)
    }
    @Test fun closingDuringPreparationPreventsTheTerminalPostAndMusicStart() {
        val f = Fixture();var active = true
        val controller = LongRecordReminder(f.tracker,f.store,f.platform) { active }
        f.platform.prepareAction = { active = false }
        assertEquals(LongRecordOutcome.NOT_POSTED,controller.onAlarm(f.ticket.hash).outcome)
        controller.suspendDelivery();assertNull(f.platform.pending);assertNull(f.platform.notice);assertEquals(0,f.platform.posts)
        assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> {
            controller.configure(f.tracker.snapshot().token,LongRecordPreference(true),f.store.value.revision)
        }.code)
        val clock = FakeClock();val tracker = Tracker(MemoryStore(),clock);tracker.startFocus(tracker.snapshot().token)
        val state = AttentionEnvironmentStore();val dnd = AttentionDistractions(state);val music = AttentionMusic()
        active = true;music.prepareAction = { active = false }
        val effects = FocusEnvironment(tracker,state,dnd,music) { active }
        val result = effects.begin(tracker.snapshot().token,newId(),FocusEnvironmentChoice(true,true,"local"),state.value.revision)
        assertEquals(0,music.plays);assertNull(result.request);assertFalse(state.value.leaseActive)
    }
    @Test fun unreadableClockRevokesFocusEffectsWithoutPretendingTheDomainIsIdle() {
        val clock = FakeClock();val tracker = Tracker(MemoryStore(),clock);tracker.startFocus(tracker.snapshot().token)
        val state = AttentionEnvironmentStore();val dnd = AttentionDistractions(state);val music = AttentionMusic()
        val effects = FocusEnvironment(tracker,state,dnd,music)
        effects.begin(tracker.snapshot().token,newId(),FocusEnvironmentChoice(true,true,"local"),state.value.revision)
        val before = tracker.data();clock.wall = -1
        val result = effects.synchronize()
        assertFalse(result.sourceAvailable);assertNull(result.request);assertFalse(state.value.leaseActive)
        assertTrue(music.stops > 0);assertEquals(1,dnd.releases);assertEquals(before,tracker.data())
    }
    @Test fun lostDndCreationResultKeepsItsExactInactiveIntentAndNeverReplaysIt() {
        val clock = FakeClock();val tracker = Tracker(MemoryStore(),clock);tracker.startFocus(tracker.snapshot().token)
        val state = AttentionEnvironmentStore();val dnd = AttentionDistractions(state);val music = AttentionMusic()
        val effects = FocusEnvironment(tracker,state,dnd,music);val id = newId()
        dnd.prepareAction = { throw IOException("inactive rule created, OS rule ID reply lost") }
        val result = effects.begin(tracker.snapshot().token,id,FocusEnvironmentChoice(true,true,"local"),state.value.revision)
        assertTrue(result.cleanupPending);assertTrue(state.value.creationPending);assertEquals(id,state.value.request!!.id)
        assertEquals(FocusEffectStatus.OUTCOME_UNKNOWN,result.distractions);assertEquals(0,dnd.activations);assertEquals(0,music.plays)
        effects.synchronize();effects.onExpiry(id)
        assertEquals(1,dnd.creations);assertTrue(state.value.creationPending)
        assertEquals(ErrorCode.STORAGE,assertFailsWith<CoreError> {
            effects.begin(tracker.snapshot().token,newId(),FocusEnvironmentChoice(),state.value.revision)
        }.code)
    }
}
