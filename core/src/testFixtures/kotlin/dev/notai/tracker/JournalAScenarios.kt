package dev.notai.tracker

import org.junit.Test
import kotlin.test.*
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*

abstract class JournalAScenarioContract {
    protected abstract fun createJournalStore(): StateStore
    protected open fun reopenJournalStore(store: StateStore): StateStore = store
    @Test fun personalProfilesPersistAndPinnedOrderDoesNotChangeWithSearchRank() {
        val clock=FakeClock();var t=Tracker(createJournalStore(),clock)
        val a=Card("a","Shared exact");val b=Card("b","Shared prefix suffix")
        t.saveCard(t.snapshot().token,a);t.saveCard(t.snapshot().token,b)
        val aliases=mutableListOf("Personal discovery")
        t.personalizeChoice(t.snapshot().token,ChoiceKind.CARD,a.id,ChoicePersonalization(aliases))
        aliases += "Should not leak"
        t.orderChoices(t.snapshot().token,ChoiceKind.CARD,listOf(b.id,a.id))
        assertEquals(listOf("card:b","card:a"),t.choices().search(ChoiceQuery("Shared",setOf(ChoiceKind.CARD))).items.map { it.entry.id })
        assertEquals(listOf("card:a"),t.choices().search(ChoiceQuery("Personal discovery",setOf(ChoiceKind.CARD))).items.map { it.entry.id })
        t.saveCard(t.snapshot().token,a.copy(name="Renamed personal original"))
        assertEquals(listOf("Personal discovery"),t.data().cards.single { it.id==a.id }.personal.aliases)
        val before=t.data();val old=t.snapshot().token
        assertFailsWith<CoreError> { t.orderChoices(old,ChoiceKind.CARD,listOf(a.id,a.id)) };assertEquals(before,t.data())
        t=Tracker(reopenJournalStore(t.store),clock)
        assertEquals(before,t.data());assertEquals("Renamed personal original",t.choices().resolve("card:a")!!.title)
        t.orderChoices(t.snapshot().token,ChoiceKind.CARD,listOf(a.id,b.id));t.undo(t.snapshot().token)
        assertEquals(before.cards,t.data().cards)
    }
    @Test fun repeatPolicyPreservesDefaultsAndNeverCopiesMeasurements() {
        val clock=FakeClock();val t=Tracker(createJournalStore(),clock)
        val card=Card("repeat-card","Personal")
        t.saveCard(t.snapshot().token,card)
        t.manual(t.snapshot().token,clock.wall-2000,clock.wall-1000,card.classification(),"Source note")
        val session=t.data().sessions.single().id
        t.saveUnit(t.snapshot().token,UnitDefinition("counter-unit","count","count"))
        t.saveMetric(t.snapshot().token,Metric("counter","count",numberKind=NumberKind.INTEGER,canonicalUnitId="counter-unit"))
        t.measure(t.snapshot().token,Measurement("measure","counter",1,"counter-unit",Scope.SESSION,session,7))
        val measurements=t.data().measurements
        t.repeat(t.snapshot().token,session);assertEquals("Source note",t.snapshot().session!!.note)
        clock.advance(1000);t.stop(t.snapshot().token)
        t.saveFavorite(t.snapshot().token,Favorite("favorite","Personal favorite",card.classification()))
        t.startFavorite(t.snapshot().token,"favorite");assertNull(t.snapshot().session!!.note)
        clock.advance(1000);t.stop(t.snapshot().token)
        t.personalizeChoice(t.snapshot().token,ChoiceKind.CARD,card.id,ChoicePersonalization(notePolicy=ChoiceNotePolicy.CLEAR))
        t.repeat(t.snapshot().token,session);assertNull(t.snapshot().session!!.note)
        clock.advance(1000);t.stop(t.snapshot().token)
        t.personalizeChoice(t.snapshot().token,ChoiceKind.FAVORITE,"favorite",ChoicePersonalization(notePolicy=ChoiceNotePolicy.COPY_SOURCE))
        t.startFavorite(t.snapshot().token,"favorite")
        // Latest exact recorded identity can itself deliberately contain no note.
        assertNull(t.snapshot().session!!.note);assertEquals(measurements,t.data().measurements)
    }
    @Test fun personalProfilesRoundTripFullAndSelectiveWithLegacyGuards() {
        val clock=FakeClock();val t=Tracker(createJournalStore(),clock)
        t.saveCard(t.snapshot().token,Card("portable","Portable"))
        t.personalizeChoice(t.snapshot().token,ChoiceKind.CARD,"portable",ChoicePersonalization(listOf("Own alias"),0,ChoiceNotePolicy.CLEAR))
        val backups=Backups(t,MemoryRollback());val text=backups.export();val decoded=Backups.decode(text)
        assertEquals(14,decoded.formatVersion);assertEquals(t.data().cards,decoded.data.cards)
        val bytes=ByteArrayOutputStream();StreamingBackup.write(decoded,bytes)
        assertEquals(decoded,StreamingBackup.read { bytes.toByteArray().inputStream() })
        val old=text.replace("\"formatVersion\": 14","\"formatVersion\": 13")
        assertEquals(ErrorCode.UNSUPPORTED_IMPORT,assertFailsWith<CoreError> { Backups.decode(old) }.code)
        assertFailsWith<CoreError> { StreamingBackup.read { old.byteInputStream() } }
        val destination=Tracker(createJournalStore(),clock);val restore=Backups(destination,MemoryRollback())
        restore.apply(restore.prepare(text));assertEquals(t.data().cards,destination.data().cards)
        val selective=SelectiveTransfer(t).export(TransferSelection(cardIds=setOf("portable")))
        assertEquals(4,selective.formatVersion)
        val target=Tracker(createJournalStore(),clock);val importer=SelectiveTransfer(target)
        val operation=importer.apply(importer.preview(importer.plan(selective,selective.groups.map { it.id }.toSet())),"personal-import")
        assertTrue(operation.complete);assertEquals(t.data().cards,target.data().cards)
        assertFailsWith<CoreError> { importer.inspect(selective.copy(formatVersion=3,minimumReader=3)) }
        val legacy=Backups.encode(Dataset(),clock.now()).replace("\"formatVersion\": 14","\"formatVersion\": 13")
        assertEquals(14,Backups.decode(legacy).formatVersion)
    }
    @Test fun gapsDistinguishPausesAndOnlyPrepareExplicitManualDrafts() {
        val clock=FakeClock(wall=10000);val t=Tracker(createJournalStore(),clock)
        t.start(t.snapshot().token);clock.advance(1000);t.pause(t.snapshot().token)
        clock.advance(2000);t.resume(t.snapshot().token);clock.advance(1000);t.stop(t.snapshot().token)
        clock.advance(6000);t.manual(t.snapshot().token,16000,17000,Classification())
        val before=t.data();val review=t.historyGaps(0,25000)
        assertEquals(20000L,review.observedEnd)
        assertEquals(listOf(0L to 10000L,11000L to 13000L,14000L to 16000L,17000L to 20000L),review.gaps.map { it.start to it.end })
        assertEquals(HistoryGapKind.PAUSE,review.gaps[1].kind)
        val gap=review.gaps[2];val draft=t.gapDraft(review,gap.id,note="Explicit fill")
        assertEquals(DraftSource.HISTORY_GAP,draft.source);assertEquals(before,t.data())
        t.recordDraft(t.snapshot().token,draft)
        assertFalse(t.historyGaps(0,25000).gaps.any { it.start==14000L && it.end==16000L })
        assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> { t.gapDraft(review,gap.id) }.code)
        assertFailsWith<CoreError> { t.recordDraft(t.snapshot().token,draft) }
        val restored=Backups.decode(Backups(t,MemoryRollback()).export()).data
        assertEquals(t.data().segments,restored.segments)
    }
    @Test fun recoveryUncertaintyIsNotAnUnrecordedDraftAndFutureIsExcluded() {
        val clock=FakeClock(wall=10000);val t=Tracker(createJournalStore(),clock)
        t.start(t.snapshot().token);clock.advance(2000);clock.boot="new-boot"
        val before=t.data();val review=t.historyGaps(0,20000)
        val unknown=review.gaps.single { it.kind==HistoryGapKind.RECOVERY_UNCERTAIN }
        assertEquals(10000L,unknown.start);assertEquals(12000L,unknown.end)
        assertEquals(ErrorCode.RECOVERY_REQUIRED,assertFailsWith<CoreError> { t.gapDraft(review,unknown.id) }.code)
        assertTrue(t.historyGaps(13000,20000).gaps.isEmpty());assertEquals(before,t.data())
    }
    @Test fun batchMetadataIsPreviewedAtomicVersionCheckedAndUndoable() {
        val clock=FakeClock();val t=Tracker(createJournalStore(),clock)
        t.saveType(t.snapshot().token,ActivityType("batch-type","Corrected"))
        t.manual(t.snapshot().token,clock.wall-6000,clock.wall-5000,Classification())
        t.manual(t.snapshot().token,clock.wall-4000,clock.wall-3000,Classification(name="Existing"),"Keep note")
        val ids=t.data().sessions.map { it.id };val before=t.data();val durations=before.segments.map { listOf(it.id,it.start,it.end,it.durationMs) }
        val request=BatchMetadataRequest(ids,Classification(name="Corrected",activityTypeId="batch-type"),note=MetadataNoteEdit("Fill note"))
        val preview=t.previewMetadata(request);assertEquals(before,t.data())
        t.applyMetadata(preview)
        assertEquals("Keep note",t.data().sessions.last().note);assertEquals("Existing",t.data().sessions.last().selection.name)
        assertEquals("Fill note",t.data().sessions.first().note)
        assertEquals(durations,t.data().segments.map { listOf(it.id,it.start,it.end,it.durationMs) })
        assertEquals(before.measurements,t.data().measurements)
        t.undo(t.snapshot().token);assertEquals(before.sessions,t.data().sessions);assertEquals(before.segments,t.data().segments)
        val stale=t.previewMetadata(request);t.saveCard(t.snapshot().token,Card("concurrent","Concurrent"))
        val changed=t.data();assertEquals(ErrorCode.STALE,assertFailsWith<CoreError> { t.applyMetadata(stale) }.code);assertEquals(changed,t.data())
        assertFailsWith<CoreError> { t.previewMetadata(request.copy(sessionIds=ids+"missing")) };assertEquals(changed,t.data())
        t.start(t.snapshot().token);val active=t.data();assertFailsWith<CoreError> { t.previewMetadata(request.copy(sessionIds=ids+active.sessions.last().id)) };assertEquals(active,t.data())
        t.stop(t.snapshot().token)
        val replacement=t.previewMetadata(BatchMetadataRequest(ids,Classification(),MetadataFillPolicy.REPLACE,MetadataNoteEdit(null,MetadataFillPolicy.REPLACE)))
        t.applyMetadata(replacement);assertTrue(t.data().sessions.filter { it.id in ids }.all { it.selection==Classification() && it.note==null })
    }
}
