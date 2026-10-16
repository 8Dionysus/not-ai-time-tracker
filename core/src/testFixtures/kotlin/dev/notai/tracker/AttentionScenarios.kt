package dev.notai.tracker

import java.time.Instant
import java.time.LocalDate
import org.junit.Test
import kotlin.test.*

class AttentionReminderStore: LongRecordStore {
    var value = LongRecordControl()
    var beforeWrite: ((LongRecordControl)->Unit)? = null
    override fun <T> locked(block: ()->T): T = synchronized(this) { block() }
    override fun read() = value
    override fun writeDurably(value: LongRecordControl) { beforeWrite?.invoke(value);this.value = value }
}
class AttentionReminderPlatform: LongRecordPlatform {
    var capability = ScheduleDeliveryAvailability()
    var pending: LongRecordTicket? = null
    var posts = 0
    var prepareAction: (()->Unit)? = null
    var postAction: (()->Unit)? = null
    override fun availability() = capability
    override fun schedule(ticket: LongRecordTicket) { pending = ticket }
    override fun cancelAlarm() { pending = null }
    override fun prepare(ticket: LongRecordTicket,snapshot: Snapshot): PreparedLongRecordNotice {
        prepareAction?.invoke()
        return PreparedLongRecordNotice { posts++;postAction?.invoke();true }
    }
    override fun cancelNotice() {}
}
class AttentionEnvironmentStore: FocusEnvironmentStore {
    var value = FocusEnvironmentControl()
    override fun <T> locked(block: ()->T): T = synchronized(this) { block() }
    override fun read() = value
    override fun writeDurably(value: FocusEnvironmentControl) { this.value = value }
}
class AttentionDistractions(private val state: AttentionEnvironmentStore): FocusDistractionPort {
    var capability = FocusEffectStatus.READY
    var released = FocusEffectStatus.RELEASED
    var activations = 0
    var releases = 0
    var alarm: FocusEnvironmentRequest? = null
    override fun availability() = capability
    override fun prepare(request: FocusEnvironmentRequest) = FocusDndLease(request.id,"own-rule",request.id,"fingerprint")
    override fun activate(lease: FocusDndLease): FocusEffectStatus {
        assertEquals(lease,state.value.lease);assertNotNull(alarm);activations++;return FocusEffectStatus.APPLY_ACCEPTED
    }
    override fun release(lease: FocusDndLease): FocusEffectStatus { releases++;return released }
    override fun scheduleRelease(request: FocusEnvironmentRequest) { alarm = request }
    override fun cancelRelease() { alarm = null }
}
class AttentionMusic: FocusMusicPort {
    var plays = 0
    var stops = 0
    var current = FocusEffectStatus.OFF
    override fun availability(localId: String?) = FocusEffectStatus.READY
    override fun prepare(requestId: String,localId: String) = PreparedFocusMusic { plays++;current = FocusEffectStatus.ACTIVE;current }
    override fun status(requestId: String) = current
    override fun stop(requestId: String): FocusEffectStatus { stops++;current = FocusEffectStatus.RELEASED;return current }
}
/** The same three consumer scenarios run through Memory and Room stores. Platform fakes are
 * intentional: these tests constrain domain effects and receipts, not Android delivery or sound. */
abstract class AttentionScenarioContract {
    abstract fun createAttentionStore(): StateStore
    @Test fun ordinaryCheckinNeverInventsTimeAndReviewUsesExistingCorrection() {
        val clock = FakeClock();val t = Tracker(createAttentionStore(),clock)
        val receipts = AttentionReminderStore();val platform = AttentionReminderPlatform()
        val checkin = LongRecordReminder(t,receipts,platform)
        t.start(t.snapshot().token)
        assertNull(checkin.synchronize().ticket)
        val before = t.data()
        val ticket = checkin.configure(t.snapshot().token,LongRecordPreference(true,100,20)).ticket!!
        assertEquals(before,t.data());assertEquals(LongRecordOutcome.EARLY,checkin.onAlarm(ticket.hash).outcome)
        clock.advance(100)
        assertEquals(LongRecordOutcome.POST_ACCEPTED,checkin.onAlarm(ticket.hash).outcome)
        assertEquals(before,t.data());checkin.onAlarm(ticket.hash);assertEquals(1,platform.posts)
        val next = checkin.respond(t.snapshot().token,ticket.hash,LongRecordAction.CONTINUE).state.ticket!!
        assertEquals(before,t.data());assertEquals(200L,next.dueDurationMs)
        assertEquals(LongRecordOutcome.STALE,checkin.onAlarm(ticket.hash).outcome)
        clock.advance(100)
        val snoozed = checkin.respond(t.snapshot().token,next.hash,LongRecordAction.SNOOZE).state.ticket!!
        assertEquals(220L,snoozed.dueDurationMs);clock.advance(20)
        val result = checkin.respond(t.snapshot().token,snoozed.hash,LongRecordAction.REVIEW)
        assertEquals(Mode.IDLE,result.state.snapshot.mode);assertNotNull(result.reviewSessionId)
        val session = result.reviewSessionId!!;val segment = t.data().segments.single()
        assertEquals(clock.wall,segment.end);assertEquals(220L,segment.durationMs)
        val preview = t.previewCorrection(session,listOf(Tracker.SegmentEdit(segment.id,segment.start,segment.start+150,segment.selection)),null)
        assertEquals(220L,t.data().segments.single().durationMs)
        t.applyCorrection(preview);assertEquals(150L,t.data().segments.single().durationMs)
    }
    @Test fun focusEffectsAreExplicitAndLeaseRestorationDoesNotAdvanceTheTimer() {
        val clock = FakeClock();val t = Tracker(createAttentionStore(),clock)
        t.startFocus(t.snapshot().token,plan = FocusPlan(100,20))
        val state = AttentionEnvironmentStore();val dnd = AttentionDistractions(state);val audio = AttentionMusic()
        val effects = FocusEnvironment(t,state,dnd,audio);val before = t.data()
        effects.synchronize();assertEquals(0,dnd.activations);assertEquals(0,audio.plays)
        val id = newId();val choice = FocusEnvironmentChoice(true,true,"chosen-local")
        val started = effects.begin(t.snapshot().token,id,choice)
        assertEquals(FocusEffectStatus.APPLY_ACCEPTED,started.distractions);assertEquals(1,audio.plays)
        effects.begin(t.snapshot().token,id,choice);assertEquals(1,dnd.activations);assertEquals(1,audio.plays)
        assertEquals(before,t.data());clock.advance(100)
        effects.onExpiry(id);assertEquals(1,dnd.releases);assertTrue(audio.stops > 0)
        assertEquals(FocusMode.DUE,t.snapshot().focus!!.mode);assertEquals(before,t.data())
        t.focusNext(t.snapshot().token)
        assertEquals(ErrorCode.INVALID_INPUT,assertFailsWith<CoreError> { effects.begin(t.snapshot().token,newId(),choice) }.code)
        assertEquals(1,dnd.activations)
    }
    @Test fun observationsAreDerivedRetractableAndUnaffectedByFragmentCount() {
        val clock = FakeClock(wall = Instant.parse("2026-10-10T12:00:00Z").toEpochMilli())
        val t = Tracker(createAttentionStore(),clock);val start = clock.wall-1000
        t.manual(t.snapshot().token,start,start+600,Classification())
        val request = ObservationRequest(true,recordedMilestonesMs = setOf(500))
        val date = LocalDate.parse("2026-10-10")
        val before = t.data();val facts = ObservationFacts.week(before,clock.now(),date,"UTC",request)!!
        assertEquals(600L,facts.recordedMs);assertEquals(1,facts.positiveDays);assertTrue(facts.milestones.single().reached)
        assertNull(ObservationFacts.week(before,clock.now(),date,"UTC",request.copy(enabled = false)))
        assertEquals(before,t.data())
        val segment = t.data().segments.single()
        val correction = t.previewCorrection(segment.sessionId,listOf(Tracker.SegmentEdit(segment.id,start,start+400,segment.selection)),null)
        t.applyCorrection(correction)
        val corrected = ObservationFacts.week(t.data(),clock.now(),date,"UTC",request)!!
        assertEquals(400L,corrected.recordedMs);assertFalse(corrected.milestones.single().reached)
        assertNotEquals(facts.source,corrected.source)
        val split = Tracker(createAttentionStore(),clock)
        split.manual(split.snapshot().token,start,start+200,Classification())
        split.manual(split.snapshot().token,start+200,start+400,Classification())
        val fragmented = ObservationFacts.week(split.data(),clock.now(),date,"UTC",request)!!
        assertEquals(corrected.recordedMs,fragmented.recordedMs);assertEquals(corrected.days,fragmented.days)
        val durationGoal = Goal("time-goal","Chosen time",GoalMetric.DURATION_MS,300,period = GoalPeriod.WEEK)
        val countGoal = Goal("count-goal","Existing count",GoalMetric.SESSION_COUNT,1,period = GoalPeriod.WEEK)
        t.saveGoal(t.snapshot().token,durationGoal);t.saveGoal(t.snapshot().token,countGoal)
        val goals = ObservationFacts.week(t.data(),clock.now(),date,"UTC",request.copy(goalIds = setOf(durationGoal.id,countGoal.id)))!!.goals
        assertTrue(goals.single { it.id == durationGoal.id }.acknowledgementEligible)
        assertFalse(goals.single { it.id == countGoal.id }.acknowledgementEligible)
    }
}
