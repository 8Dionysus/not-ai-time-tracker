package dev.notai.tracker.android.ui

import android.app.Application
import android.os.Looper
import androidx.lifecycle.viewModelScope
import dev.notai.tracker.*
import dev.notai.tracker.android.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class UiReviewRegressionTest {
    private val originalOpen = UiBackends.open
    private val fixtures = mutableListOf<Fixture>()
    private class Store(initial: Dataset): StateStore {
        @Volatile private var value = initial
        override val changes = MutableStateFlow(initial)
        val attempts = AtomicInteger();val commits = AtomicInteger()
        @Volatile var rejectWrite: Int? = null
        override fun read() = value
        @Synchronized override fun <T> transaction(block: (Dataset) -> Pair<Dataset,T>): T {
            val (next,result) = block(value);validate(next)
            if(next != value) {
                val attempt = attempts.incrementAndGet()
                if(attempt == rejectWrite) throw java.io.IOException("Synthetic before-commit fault")
                value = next;changes.value = next;commits.incrementAndGet()
            }
            return result
        }
    }
    private class Backend(override val tracker: Tracker): UiBackend {
        override val backups = Backups(tracker,MemoryRollback())
        override val focusDue = FocusDueSignals()
        override val runtime: TrackerRuntime? = null
        override fun prepareCatalog(): CatalogPreview = error("Not used by this fixture")
        override fun applyCatalog(preview: CatalogPreview): Tracker.Change = error("Not used by this fixture")
        override fun submitShortcut(action: SurfaceAction) = CompletableDeferred<Result<Tracker.Change>>(Result.failure(IllegalStateException("No fixture shortcuts")))
    }
    private data class Shown(val entry: ChoiceEntry,val reference: ChoiceReference,val token: Token)
    private class Fixture(val clock: FakeClock,val store: Store,val tracker: Tracker,val vm: TrackerViewModel,val scope: CoroutineScope) {
        val notices = java.util.concurrent.CopyOnWriteArrayList<Notice>()
        fun shown(id: String): Shown {
            val view = tracker.view();val choices = view.choices();val entry = requireNotNull(choices.resolve(id))
            return Shown(entry,choices.reference(entry),view.snapshot().token)
        }
        fun choose(shown: Shown,mode: PickMode) { vm.choose(shown.entry,mode,reference = shown.reference,expected = shown.token) }
    }
    private fun pump(until: () -> Boolean) {
        val deadline = System.nanoTime()+5_000_000_000L
        while(!until()) {
            shadowOf(Looper.getMainLooper()).idle()
            if(System.nanoTime() > deadline) throw AssertionError("Fixture did not settle")
            Thread.sleep(5)
        }
    }
    private fun fixture(clock: FakeClock,settings: Settings = Settings("UTC"),initial: Dataset? = null,waitBoundary: (suspend (Long) -> Unit)? = null): Fixture {
        val store = Store(initial ?: Dataset(settings = settings));val tracker = Tracker(store,clock)
        UiBackends.open = { Backend(tracker) }
        val vm = if(waitBoundary == null) TrackerViewModel(RuntimeEnvironment.getApplication()) else TrackerViewModel(RuntimeEnvironment.getApplication(),waitBoundary)
        val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val fixture = Fixture(clock,store,tracker,vm,scope);fixtures += fixture
        scope.launch(start = CoroutineStart.UNDISPATCHED) { vm.snapshot.collect { } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { vm.notices.collect { fixture.notices += it } }
        pump { vm.boot.value is TrackerViewModel.Boot.Ready && vm.view.value != null && vm.snapshot.value != null }
        return fixture
    }
    private fun Fixture.synced() = pump { vm.view.value?.snapshot()?.token == tracker.snapshot().token && vm.snapshot.value?.token == tracker.snapshot().token }
    @After fun close() {
        fixtures.forEach { it.scope.cancel();it.vm.viewModelScope.cancel() };shadowOf(Looper.getMainLooper()).idle();UiBackends.open = originalOpen
    }
    private fun at(text: String) = Instant.parse(text).toEpochMilli()

    @Test fun reportDayUsesConfiguredBoundaryBeforeAndAfterFour() {
        val f = fixture(FakeClock(at("2026-10-11T02:00:00Z")),Settings("UTC",reportDayStartMinute = 240))
        f.tracker.manual(f.tracker.snapshot().token,at("2026-10-10T22:00:00Z"),at("2026-10-10T23:00:00Z"),Classification())
        f.synced()
        val before = runBlocking { f.vm.overview(Period.DAY) }!!
        assertEquals(LocalDate.parse("2026-10-10"),before.last);assertEquals(3_600_000L,before.totalMs)
        f.clock.wall = at("2026-10-11T04:00:00Z")
        val after = runBlocking { f.vm.overview(Period.DAY) }!!
        assertEquals(LocalDate.parse("2026-10-11"),after.last);assertEquals(0L,after.totalMs)
    }
    @Test fun reportDayRetainsRealDstBoundaryLength() {
        val f = fixture(FakeClock(at("2026-11-01T07:30:00Z")),Settings("America/New_York",reportDayStartMinute = 240))
        val value = runBlocking { f.vm.overview(Period.DAY) }!!
        assertEquals(LocalDate.parse("2026-10-31"),value.last);assertEquals(25*3_600_000L,value.dayEnd-value.dayStart)
    }
    @Test fun nextPeriodEnabledUsesEffectiveGoalAndDayUnit() {
        val f = fixture(FakeClock(at("2026-10-10T12:00:00Z")))
        val disabledNext = Goal("disable-next","Active today",GoalMetric.DISTINCT_RECORDED_DAYS,2)
        val enabledNext = Goal("enable-next","Inactive today",GoalMetric.DISTINCT_RECORDED_DAYS,2,enabled = false)
        f.tracker.saveGoal(f.tracker.snapshot().token,disabledNext);f.tracker.saveGoal(f.tracker.snapshot().token,enabledNext)
        f.clock.advance(1000)
        f.tracker.saveGoal(f.tracker.snapshot().token,disabledNext.copy(enabled = false))
        f.tracker.saveGoal(f.tracker.snapshot().token,enabledNext.copy(enabled = true));f.synced()
        val today = runBlocking { f.vm.overview(Period.DAY) }!!.goals
        assertEquals(1,today.size);assertTrue(today.single().name,today.single().name.startsWith("Active today"))
        assertFalse(today.single().name,today.single().name.contains("раз"))
        assertTrue(today.single().name,today.single().name.contains("дн."))
        f.clock.wall = at("2026-10-11T12:00:00Z")
        val tomorrow = runBlocking { f.vm.overview(Period.DAY) }!!.goals
        assertEquals(1,tomorrow.size);assertTrue(tomorrow.single().name,tomorrow.single().name.startsWith("Inactive today"))
    }
    @Test fun oneSegmentWithSubjectFactsRequiresReviewAndCancelledPreviewHasNoWrites() {
        val f = fixture(FakeClock());val t = f.tracker
        t.saveCard(t.snapshot().token,Card("delete-card","Item"));t.defineSubject(t.snapshot().token,CardSubject("delete-card","item"))
        t.saveUnit(t.snapshot().token,UnitDefinition("kg","kg","mass"));t.saveUnit(t.snapshot().token,UnitDefinition("page","page","page"))
        t.saveMetric(t.snapshot().token,Metric("pages","Pages",numberKind = NumberKind.INTEGER,canonicalUnitId = "page"))
        t.manual(t.snapshot().token,f.clock.wall-1000,f.clock.wall,Classification())
        val session = t.data().sessions.single().id;val ref = SubjectRef(SubjectKind.CARD,"delete-card")
        t.linkSubject(t.snapshot().token,SubjectSessionLink(subject = ref,sessionId = session))
        t.recordSet(t.snapshot().token,ExerciseSet(sessionId = session,ordinal = 0,repetitions = 8,load = Decimal("40"),loadUnitId = "kg",loadMeaning = LoadMeaning.TOTAL_EXTERNAL,recordedAt = f.clock.wall,source = "manual"))
        t.recordEventQuantity(t.snapshot().token,EventQuantity(subject = ref,definition = QuantityDefinition("pages",1,"page"),value = Decimal("12"),effectiveAt = f.clock.wall-500,recordedAt = f.clock.wall,source = "manual",sessionId = session))
        f.synced();val before = t.data();val commits = f.store.commits.get()
        val result = runBlocking { f.vm.requestDelete(session) }
        assertTrue(result.toString(),result is DeleteRequest.Confirm);assertEquals(before,t.data());assertEquals(commits,f.store.commits.get())
        val review = result as DeleteRequest.Confirm
        assertEquals(1,review.subjectLinks);assertEquals(1,review.exerciseSets);assertEquals(1,review.eventQuantities);assertEquals(0,review.distanceTimes)
        assertTrue(review.consequences.any { it.contains("подход") });assertTrue(review.consequences.any { it.contains("величин") });assertTrue(review.consequences.any { it.contains("связ") })
        f.vm.applyDeleteSession(review.preview);pump { f.notices.any { it.text == "Запись удалена" } }
        assertTrue(t.data().sessions.none { it.id == session });f.synced();f.vm.undo(t.snapshot().token)
        pump { f.notices.any { it.text == "Отменено" } }
        assertEquals(before.subjects,t.data().subjects);assertEquals(before.sessions,t.data().sessions);assertEquals(before.segments,t.data().segments)
    }
    private fun staleFavorite(renameOnly: Boolean) {
        val f = fixture(FakeClock());val t = f.tracker
        val favorite = Favorite("chosen","Shown favorite",Classification(name = "Shown parameters"));t.saveFavorite(t.snapshot().token,favorite);f.synced()
        val shown = f.shown("favorite:chosen")
        t.saveFavorite(t.snapshot().token,if(renameOnly) favorite.copy(label = "Changed title") else favorite.copy(selection = Classification(name = "Unseen parameters")))
        f.synced();val before = t.data();val commits = f.store.commits.get()
        f.choose(shown,PickMode.START);pump { f.store.commits.get() > commits || f.notices.isNotEmpty() }
        assertEquals(before,t.data());assertTrue(f.notices.any { it.text == Format.error(CoreError(ErrorCode.STALE,"stale")) })
    }
    @Test fun shownFavoriteRenameCannotSilentlyRebase() = staleFavorite(true)
    @Test fun shownFavoriteClassificationCannotSilentlyRebase() = staleFavorite(false)
    private fun p2ShownSegment(picker: Boolean,policy: ChoiceNotePolicy? = null,active: Boolean = false) {
        val f=fixture(FakeClock());val t=f.tracker
        val card=Card("two-segment-card","Two segment",personal=ChoicePersonalization(notePolicy=policy));t.saveCard(t.snapshot().token,card)
        val a=card.classification().copy(details=mapOf("part" to "A"));val b=a.copy(details=mapOf("part" to "B"))
        t.start(t.snapshot().token,a,"Original session note");f.clock.advance(1000);t.changeDetails(t.snapshot().token,b);f.clock.advance(1000);t.stop(t.snapshot().token)
        val source=t.data().sessions.single();val segment=t.data().segments.first();f.clock.advance(1000)
        if(active) t.start(t.snapshot().token,Classification(name="Other current"),"Other note")
        f.synced();val before=t.data()
        if(picker) {
            val shown=runBlocking { f.vm.pickerData() }.recents.single { it.entry.selection.details["part"]=="A" }
            f.vm.choose(shown.entry,if(active) PickMode.SWITCH else PickMode.START,reference=shown.reference,expected=shown.expected)
        } else f.vm.repeat(EntryUi(segment.id,source.id,segment.start,segment.end!!,segment.durationMs,t.data().label(a),source.note,a))
        pump { t.data().sessions.size>before.sessions.size || f.notices.isNotEmpty() }
        assertEquals(a,t.snapshot().session!!.selection);assertEquals(if(policy==ChoiceNotePolicy.CLEAR) null else "Original session note",t.snapshot().session!!.note)
        assertEquals(before.segments.filter { it.sessionId==source.id },t.data().segments.filter { it.sessionId==source.id })
    }
    @Test fun reviewP2HistoryPickerStartsShownEarlySegment()=p2ShownSegment(true)
    @Test fun reviewP2RowRepeatStartsShownEarlySegment()=p2ShownSegment(false)
    @Test fun reviewP2HistoryPickerPreservesClearNotePolicy()=p2ShownSegment(true,ChoiceNotePolicy.CLEAR)
    @Test fun reviewP2RowSwitchPreservesShownSegmentAndSourceNote()=p2ShownSegment(false,active=true)

    @Test fun reviewP2RowRepeatRejectsUnseenSourceNoteAtFreshRevision() {
        val f=fixture(FakeClock());val t=f.tracker
        t.manual(t.snapshot().token,f.clock.wall-1000,f.clock.wall-500,Classification(name="Shown original"),"Shown note");f.synced()
        val source=t.data().sessions.single();val segment=t.data().segments.single()
        val entry=EntryUi(segment.id,source.id,segment.start,segment.end!!,segment.durationMs,t.data().label(segment.selection),source.note,segment.selection)
        t.correctSession(t.snapshot().token,source.id,listOf(Tracker.SegmentEdit(segment.id,segment.start,segment.end!!,segment.selection)),"Unseen changed note");f.synced()
        val before=t.data();f.vm.repeat(entry);pump { f.notices.isNotEmpty() }
        assertEquals(before,t.data());assertTrue(f.notices.any { it.text==Format.error(CoreError(ErrorCode.STALE,"stale")) })
    }
    @Test fun reviewP2IncomingTextsQueueAndStaleDecisionsPreserveAllValuesUntilChoice() {
        val f=fixture(FakeClock());val before=f.tracker.data()
        f.vm.receiveDraft(EntryDrafts.selectedText("First"));f.vm.receiveDraft(EntryDrafts.selectedText("Second"));f.vm.receiveDraft(EntryDrafts.selectedText("Third"))
        assertEquals("First",f.vm.draft.value!!.note);assertEquals(listOf("Second","Third"),f.vm.incomingDrafts.value.map { it.draft.note })
        val second=f.vm.incomingDrafts.value.first();f.vm.resolveIncomingDraft("not-the-shown-request",true)
        assertEquals("First",f.vm.draft.value!!.note);assertEquals(2,f.vm.incomingDrafts.value.size)
        f.vm.resolveIncomingDraft(second.id,false);assertEquals("First",f.vm.draft.value!!.note)
        val third=f.vm.incomingDrafts.value.single();f.vm.resolveIncomingDraft(second.id,true);assertEquals(third,f.vm.incomingDrafts.value.single())
        f.vm.resolveIncomingDraft(third.id,true);assertEquals("Third",f.vm.draft.value!!.note);assertTrue(f.vm.incomingDrafts.value.isEmpty())
        assertEquals(before,f.tracker.data())
    }
    private fun p2HistoryChoice(uncertain: Boolean,quick: Boolean) {
        val clock = FakeClock();val f = fixture(clock,initial = reviewP2HistoryFixture(clock.wall,uncertain));val t = f.tracker
        val before = t.data()
        val shown = if(quick) {
            f.scope.launch(start = CoroutineStart.UNDISPATCHED) { f.vm.home.collect { } }
            pump { f.vm.home.value?.quick?.isNotEmpty() == true }
            val q = f.vm.home.value!!.quick.single();Shown(q.entry,q.reference,q.expected)
        } else {
            val r = runBlocking { f.vm.pickerData() }.recents.single()
            Shown(r.entry,requireNotNull(r.reference),requireNotNull(r.expected))
        }
        assertEquals(before,t.data());assertEquals(t.snapshot().token,shown.token)
        assertEquals("p2-positive",shown.entry.sourceSegmentId);assertEquals("Original positive note",shown.entry.note)
        f.choose(shown,PickMode.START);pump { t.snapshot().session != null || f.notices.isNotEmpty() }
        assertNotNull(t.snapshot().session);assertEquals("Original positive note",t.snapshot().session!!.note)
        assertEquals(shown.entry.selection,t.snapshot().session!!.selection)
        assertEquals(before.sessions,t.data().sessions.filter { it.id in before.sessions.map { old -> old.id } })
        assertEquals(before.segments,t.data().segments.filter { it.id in before.segments.map { old -> old.id } })
        assertTrue(f.notices.none { it.text == Format.error(CoreError(ErrorCode.STALE,"stale")) })
    }
    @Test fun reviewP2QuickIgnoresLaterEmptyCanonical() = p2HistoryChoice(false,true)
    private fun p3BoundedChoice(path: String,staleNote: Boolean = false) {
        val f = fixture(FakeClock(10000),initial = reviewP3WindowFixture());val t = f.tracker
        val view = t.view();val choices = view.choices();val entry = reviewP3Shown(choices,path)
        val shown = Shown(entry,choices.reference(entry),view.snapshot().token)
        assertEquals("p3-g1",entry.sourceSegmentId);assertEquals("Note A",entry.note)
        if(staleNote) {
            t.correctSession(t.snapshot().token,"p3-s1",listOf(Tracker.SegmentEdit("p3-g1",1000,2000,entry.selection)),"Unseen note")
            f.synced()
        }
        val before = t.data();f.choose(shown,PickMode.START)
        pump { t.snapshot().session != null || f.notices.isNotEmpty() }
        if(staleNote) {
            assertEquals(before,t.data());assertTrue(f.notices.any { it.text == Format.error(CoreError(ErrorCode.STALE,"stale")) })
        } else {
            assertNotNull(t.snapshot().session);assertEquals("Note A",t.snapshot().session!!.note)
            assertEquals(entry.selection,t.snapshot().session!!.selection)
            assertTrue(f.notices.none { it.text == Format.error(CoreError(ErrorCode.STALE,"stale")) })
        }
    }
    @Test fun reviewP3BoundedFrequencyStartsShownSource() = p3BoundedChoice("bounded-frequent")
    @Test fun reviewP3BoundedQuickStartsShownSource() = p3BoundedChoice("bounded-quick")
    @Test fun reviewP3BoundedQuickKeepsStrictRevisionGuard() = p3BoundedChoice("bounded-quick",true)
    private fun p3FocusDelivery(outcome: String) {
        val app = RuntimeEnvironment.getApplication()
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        val prefs = app.getSharedPreferences("focus-signals",0);prefs.edit().clear().commit();manager.cancelAll()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val originalFactory = FocusSignals.notificationFactory
        val clock = FakeClock();val seed = Tracker(Store(Dataset()),clock)
        seed.configureFocusSignal(seed.snapshot().token,true);seed.startFocus(seed.snapshot().token,plan = FocusPlan(1000,1000));clock.advance(1000)
        val due = seed.snapshot().focus!!
        if(outcome == "previous") prefs.edit().putStringSet("delivered",setOf(due.signalKey!!)).commit()
        if(outcome == "blocked") shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        if(outcome == "unknown") FocusSignals.notificationFactory = { throw IllegalStateException("Synthetic post preparation uncertainty") }
        try {
            val f = fixture(clock,initial = seed.data())
            pump { f.vm.snapshot.value?.focus?.mode == FocusMode.DUE }
            pump { due.signalKey in prefs.getStringSet("delivered",emptySet()).orEmpty() || f.notices.any { it.text == due.safeLabel } }
            if(outcome == "fresh") assertTrue("The VM itself must post the first cue",manager.activeNotifications.any { it.id == 43 })
            // The runtime's later asynchronous refresh used to post after the VM had
            // already emitted a local fallback. Exercise the same platform delivery.
            FocusSignals.refresh(app,f.tracker)
            if(outcome == "fresh") assertTrue(manager.activeNotifications.any { it.id == 43 })
            if(outcome == "blocked") pump { f.notices.any { it.text == due.safeLabel } }
            else {
                pump { due.signalKey in prefs.getStringSet("delivered",emptySet()).orEmpty() }
                assertTrue("Automatic duplicate fallback for $outcome: ${f.notices}",f.notices.none { it.text == due.safeLabel })
            }
            assertEquals(seed.data(),f.tracker.data())
        } finally { FocusSignals.notificationFactory = originalFactory }
    }
    @Test fun reviewP3FocusFirstDuePostsWithoutLocalDuplicate() = p3FocusDelivery("fresh")
    @Test fun reviewP3FocusPreviousReceiptHasNoLocalDuplicate() = p3FocusDelivery("previous")
    @Test fun reviewP3FocusUnknownPostHasNoLocalDuplicate() = p3FocusDelivery("unknown")
    @Test fun reviewP3FocusBlockedBeforePostKeepsLocalCue() = p3FocusDelivery("blocked")
    @Test fun reviewP2RecentIgnoresLaterEmptyCanonical() = p2HistoryChoice(false,false)
    @Test fun reviewP2QuickIgnoresLaterUncertainCanonical() = p2HistoryChoice(true,true)
    @Test fun reviewP2RecentIgnoresLaterUncertainCanonical() = p2HistoryChoice(true,false)
    @Test fun wholeAreaKeepsShownGuardsAndStartsWithTheReviewedParent() {
        val f = fixture(FakeClock());val t = f.tracker
        t.saveType(t.snapshot().token,ActivityType("whole-parent","Parent"))
        t.saveType(t.snapshot().token,ActivityType("whole-child","Child",parentId = "whole-parent"));f.synced()
        val parent = runBlocking { f.vm.pickerData() }.let { it.areas+it.moreAreas }.single { it.entry.sourceId == "whole-parent" }
        val whole = parent.wholeArea()
        assertEquals(parent.reference,whole.reference);assertNotNull(whole.reference)
        assertEquals(parent.expected,whole.expected);assertNotNull(whole.expected);assertTrue(whole.whole)
        f.vm.choose(whole.entry,PickMode.START,reference = whole.reference,expected = whole.expected)
        pump { t.data().sessions.isNotEmpty() || f.notices.isNotEmpty() }
        assertEquals("whole-parent",t.snapshot().session!!.selection.activityTypeId)
        assertTrue(f.notices.none { it.text == Format.error(CoreError(ErrorCode.STALE,"stale")) })
    }
    @Test fun draftSwitchHasOneCommitWithNoteAndOneUndo() {
        val f = fixture(FakeClock());val t = f.tracker
        t.start(t.snapshot().token,Classification(name = "Before"),"Before note");f.clock.advance(1000)
        t.saveFavorite(t.snapshot().token,Favorite("draft-choice","Choice",Classification(name = "After")));f.synced()
        val before = t.data();val shown = f.shown("favorite:draft-choice")
        f.vm.receiveDraft(EntryDraft(Classification(),"Draft note",source = DraftSource.SELECTED_TEXT))
        val attempts = f.store.attempts.get();f.store.rejectWrite = attempts+2
        f.choose(shown,PickMode.SWITCH);pump { f.vm.draft.value == null || f.notices.isNotEmpty() }
        assertEquals(attempts+1,f.store.attempts.get());assertNull(f.vm.draft.value);assertEquals("Draft note",t.snapshot().session!!.note)
        f.store.rejectWrite = null;f.synced();f.vm.undo(t.snapshot().token)
        pump { t.data().sessions == before.sessions };assertEquals(before.sessions,t.data().sessions);assertEquals(before.segments,t.data().segments)
    }
    @Test fun failedDraftSwitchKeepsInputAndOriginalDataset() {
        val f = fixture(FakeClock());val t = f.tracker
        t.start(t.snapshot().token);t.saveFavorite(t.snapshot().token,Favorite("draft-choice","Choice",Classification(name = "After")));f.synced()
        val shown = f.shown("favorite:draft-choice");val draft = EntryDraft(Classification(),"Keep this",source = DraftSource.SELECTED_TEXT)
        f.vm.receiveDraft(draft);val before = t.data();f.store.rejectWrite = f.store.attempts.get()+1
        f.choose(shown,PickMode.SWITCH);pump { f.notices.isNotEmpty() }
        assertEquals(before,t.data());assertEquals(draft,f.vm.draft.value)
    }
    @Test fun idleHomeAndOverviewAdvanceAtReportBoundaryWithoutDatasetRevision() {
        val boundary = at("2026-10-11T04:00:00Z");val wake = Channel<Unit>(Channel.UNLIMITED)
        val f = fixture(FakeClock(boundary-1),Settings("UTC",reportDayStartMinute = 240)) { wake.receive() }
        f.scope.launch(start = CoroutineStart.UNDISPATCHED) { f.vm.home.collect { } }
        pump { f.vm.home.value?.today == LocalDate.parse("2026-10-10") && f.vm.reportingDate.value == LocalDate.parse("2026-10-10") }
        val before = f.tracker.data();assertEquals(LocalDate.parse("2026-10-10"),runBlocking { f.vm.overview(Period.DAY) }!!.last)
        f.clock.wall = boundary;f.clock.elapsed++;wake.trySend(Unit)
        pump { f.vm.home.value?.today == LocalDate.parse("2026-10-11") && f.vm.reportingDate.value == LocalDate.parse("2026-10-11") }
        assertEquals(before,f.tracker.data());assertEquals(LocalDate.parse("2026-10-11"),runBlocking { f.vm.overview(Period.DAY) }!!.last)
    }
    @Test fun ribbonLabelsUseActualElapsedFractionsInExplicitReportZone() {
        val zone = ZoneId.of("America/New_York");val policy = ReportingDayPolicy(zone.id,240)
        val fall = LocalDate.parse("2026-10-31");val spring = LocalDate.parse("2026-03-07")
        assertEquals(listOf("4:00","10:15","16:30","22:45","4:00"),ribbonAxisLabels(policy.boundary(fall),policy.boundary(fall.plusDays(1)),zone))
        assertEquals(listOf("4:00","9:45","15:30","21:15","4:00"),ribbonAxisLabels(policy.boundary(spring),policy.boundary(spring.plusDays(1)),zone))
    }
}
