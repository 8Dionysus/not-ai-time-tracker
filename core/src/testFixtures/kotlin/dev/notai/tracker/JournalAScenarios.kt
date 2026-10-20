package dev.notai.tracker

import org.junit.Test
import kotlin.test.*
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*

abstract class JournalAScenarioContract {
    protected abstract fun createJournalStore(): StateStore
    protected open fun reopenJournalStore(store: StateStore): StateStore = store
    @Test fun surfacePresentationPreferencesRoundTripAndLegacyDefaultsAreExplicit() {
        val clock = FakeClock(); val t = Tracker(createJournalStore(),clock)
        val legacy = Backups(t,MemoryRollback()).export().replace("\"formatVersion\": $CURRENT_BACKUP_FORMAT","\"formatVersion\": 14")
        val oldWhole = Backups.decode(legacy)
        assertEquals(oldWhole,StreamingBackup.read { legacy.byteInputStream() })
        assertNull(oldWhole.data.settings.surfaces.notificationPrivacy)
        assertEquals(100,oldWhole.data.settings.surfaces.widgetBackgroundAlphaPercent)
        val preference = SurfaceSettings(notificationPrivacy = NotificationPrivacy.FULL,
            widgetBackgroundAlphaPercent = 37,overlayChooserBackgroundAlphaPercent = 0)
        t.settings(t.snapshot().token,t.data().settings.copy(surfaces = preference))
        val before = t.data()
        val copy = Backups(t,MemoryRollback()); val json = copy.export()
        val decoded = Backups.decode(json)
        assertEquals(CURRENT_BACKUP_FORMAT,decoded.formatVersion)
        assertEquals(preference,decoded.data.settings.surfaces)
        assertEquals(decoded,StreamingBackup.read { json.byteInputStream() })
        val bytes = ByteArrayOutputStream();StreamingBackup.write(decoded,bytes)
        assertEquals(decoded,Backups.decode(bytes.toString("UTF-8")))
        val target = Tracker(createJournalStore(),clock);val restore = Backups(target,MemoryRollback())
        restore.apply(restore.prepare(json))
        assertEquals(preference,Tracker(reopenJournalStore(target.store),clock).data().settings.surfaces)
        // A future version and new settings mislabeled as old data are rejected by both readers.
        for(version in listOf(14,CURRENT_BACKUP_FORMAT+1)) {
            val bad = json.replace("\"formatVersion\": $CURRENT_BACKUP_FORMAT","\"formatVersion\": $version")
            assertFailsWith<CoreError> { copy.prepare(bad) }
            assertFailsWith<CoreError> { copy.prepare { bad.byteInputStream() } }
            assertEquals(before,t.data())
        }
        for(alpha in listOf(-1,101)) {
            assertFailsWith<CoreError> { t.settings(t.snapshot().token,t.data().settings.copy(surfaces = preference.copy(widgetBackgroundAlphaPercent = alpha))) }
            assertFailsWith<CoreError> { t.settings(t.snapshot().token,t.data().settings.copy(surfaces = preference.copy(overlayChooserBackgroundAlphaPercent = alpha))) }
            assertEquals(before,t.data())
        }
    }

    @Test fun sessionDayProjectionClipsMidnightPreservesMixedAndDoesNotInventUncertainPauses() {
        val midnight = java.time.Instant.parse("2026-10-20T00:00:00Z").toEpochMilli()
        val clock = FakeClock(midnight-2000);val t = Tracker(createJournalStore(),clock)
        val selection = Classification(name = "Same",details = mapOf("variant" to "first"))
        t.start(t.snapshot().token,selection);clock.advance(4000)
        t.changeDetails(t.snapshot().token,selection.copy(details = mapOf("variant" to "second")))
        clock.advance(1000);t.pause(t.snapshot().token);clock.advance(1000)
        t.resume(t.snapshot().token);clock.advance(1000);t.stop(t.snapshot().token)
        val calendar = t.view().calendar(java.time.LocalDate.of(2026,10,19),2)
        val before = t.data()
        val first = calendar.days[0].sessionRows(before).single()
        val second = calendar.days[1].sessionRows(before).single()
        assertEquals(midnight,first.end);assertEquals(midnight,second.start)
        assertFalse(first.mixedSelection);assertTrue(second.mixedSelection)
        assertEquals(6000L,first.recordedMs+second.recordedMs)
        assertEquals(1000L,second.pauses.single().let { it.end-it.start })
        assertEquals(calendar.window.recordedMs,first.recordedMs+second.recordedMs)
        assertEquals(before,t.data())
        val id = "uncertain-session"
        val own = listOf(
            Segment("certain-first",id,midnight,midnight+1000,1000,selection),
            Segment("uncertain-middle",id,midnight+1000,midnight+3000,0,selection,uncertain = true),
            Segment("certain-last",id,midnight+5000,midnight+6000,1000,selection))
        val data = Dataset(sessions = listOf(Session(id,SessionStatus.STOPPED,selection)),segments = own)
        val day = Reports.calendar(data,FakeClock(midnight+10000).now(),java.time.LocalDate.of(2026,10,20),1).days.single()
        val summary = day.sessionRows(data).single()
        assertEquals(2000L,summary.recordedMs)
        assertTrue(summary.pauses.isEmpty()) // Unknown work ending at 3000 cannot establish a certain pause.
        val unknownOnly = day.copy(rows = emptyList())
        assertTrue(unknownOnly.sessionRows(data).isEmpty())
    }
    @Test fun repeatedFavoriteAddPreservesVariantsLegacyIdsAndReopenedOrder() {
        val store = createJournalStore(); val clock = FakeClock(); var t = Tracker(store, clock)
        val selection = Classification(name = "Same", details = mapOf("variant" to "a"))
        t.addFavorite(t.snapshot().token, Favorite("a", "First", selection))
        t.addFavorite(t.snapshot().token, Favorite("repeat", "Other label", selection))
        assertEquals(listOf("a"), t.data().favorites.map { it.id })
        t.addFavorite(t.snapshot().token, Favorite("b", "First", selection.copy(details = mapOf("variant" to "b"))))
        t.addFavorite(t.snapshot().token, Favorite("note", "First", selection,
            ChoicePersonalization(notePolicy = ChoiceNotePolicy.COPY_SOURCE)))
        // Explicit save/import is allowed to retain old duplicate identities.
        t.saveFavorite(t.snapshot().token, Favorite("legacy", "Legacy", selection))
        t.addFavorite(t.snapshot().token, Favorite("again", "First", selection))
        assertEquals(setOf("a", "b", "note", "legacy"), t.data().favorites.map { it.id }.toSet())
        t.orderChoices(t.snapshot().token, ChoiceKind.FAVORITE, listOf("b", "a", "note", "legacy"))
        t = Tracker(reopenJournalStore(store), clock)
        val window = HistoryWindow(0, clock.wall + 1)
        assertEquals(listOf("b", "a"), t.choices().quick(window, 2).map { it.entry.sourceId })
        assertEquals(listOf("b"), t.choices().quick(window, 2, selection).map { it.entry.sourceId })
        val stale = t.snapshot().token
        t.removeFavorite(stale, "b")
        assertFailsWith<CoreError> { t.orderChoices(stale, ChoiceKind.FAVORITE, listOf("a")) }
        t.undo(t.snapshot().token)
        t = Tracker(reopenJournalStore(store), clock)
        assertEquals("b", t.choices().quick(window, 1).single().entry.sourceId)
    }

    @Test fun sessionDayProjectionKeepsPausesOutOfWorkAndDoesNotMergeNames() {
        val clock = FakeClock(); val t = Tracker(createJournalStore(), clock)
        val selection = Classification(name = "Same")
        t.start(t.snapshot().token, selection); clock.advance(60_000)
        t.pause(t.snapshot().token); clock.advance(30_000)
        t.resume(t.snapshot().token); clock.advance(60_000); t.stop(t.snapshot().token)
        t.start(t.snapshot().token, selection); clock.advance(10_000); t.stop(t.snapshot().token)
        val data = t.data(); val report = t.view().report(0, clock.wall + 1)
        val day = CalendarDay(java.time.LocalDate.of(2026,1,1), 0, clock.wall + 1, report.rows, report.recordedMs, false)
        val rows = day.sessionRows(data)
        assertEquals(2, rows.size)
        val paused = rows.single { it.rows.size == 2 }
        assertEquals(120_000L, paused.recordedMs)
        assertEquals(30_000L, paused.pauses.single().let { it.end - it.start })
        assertEquals(report.recordedMs, rows.sumOf { it.recordedMs })
        val boundary = paused.rows.last().start
        val secondDay = day.copy(start = boundary, rows = report.rows.filter { it.start >= boundary })
        assertTrue(secondDay.sessionRows(data).all { it.pauses.isEmpty() })
        assertEquals(data, t.data())
    }

    @Test fun categoryEditsKeepHistoricalTotalsAndRejectInvalidParents() {
        val clock = FakeClock(); val t = Tracker(createJournalStore(), clock)
        t.saveType(t.snapshot().token, ActivityType("root-a", "Root", selectable = false))
        t.saveType(t.snapshot().token, ActivityType("child-a", "Child", parentId = "root-a"))
        t.saveCard(t.snapshot().token, Card("own-a", "Own", activityTypeId = "child-a"))
        t.start(t.snapshot().token, t.selection("own-a")); clock.advance(60_000); t.stop(t.snapshot().token)
        val facts = t.data().segments
        val total = t.view().report(0, clock.wall + 1, Filter(activityTypeId = "root-a")).recordedMs
        t.saveType(t.snapshot().token, t.data().types.single { it.id == "child-a" }.copy(name = "Renamed"))
        assertEquals(total, t.view().report(0, clock.wall + 1, Filter(activityTypeId = "root-a")).recordedMs)
        assertEquals(facts, t.data().segments)
        assertFailsWith<CoreError> { t.saveType(t.snapshot().token, ActivityType("root-a", "Root", parentId = "child-a")) }
        assertFailsWith<CoreError> { t.saveType(t.snapshot().token, ActivityType("missing-child", "Child", parentId = "missing")) }
        t.saveType(t.snapshot().token, ActivityType("archived-parent", "Archived", archived = true))
        assertFailsWith<CoreError> { t.saveType(t.snapshot().token, ActivityType("bad-child", "Child", parentId = "archived-parent")) }
        assertFailsWith<CoreError> { t.saveCard(t.snapshot().token, Card("bad-card", "Own", activityTypeId = "archived-parent")) }
        assertEquals(facts, t.data().segments)
    }

    @Test fun personalProfilesPersistAndPinnedOrderDoesNotChangeWithSearchRank() {
        val clock=FakeClock();var t=Tracker(createJournalStore(),clock)
        val a=Card("a","Shared exact");val b=Card("b","Shared prefix suffix")
        t.saveCard(t.snapshot().token,a);t.saveCard(t.snapshot().token,b)
        val aliases=mutableListOf("Personal discovery")
        t.personalizeChoice(t.snapshot().token,ChoiceKind.CARD,a.id,ChoicePersonalization(aliases))
        aliases += "Should not leak"
        val invalid=assertFailsWith<CoreError> { t.personalizeChoice(t.snapshot().token,ChoiceKind.CARD,a.id,ChoicePersonalization(listOf(" x ","X"))) }
        assertEquals(ErrorReason.PERSONAL_CHOICE_INVALID,invalid.reason)
        assertEquals(ErrorArgument.Identifier("DUPLICATE_ALIAS"),invalid.arguments["rule"])
        t.saveFavorite(t.snapshot().token,Favorite("fa","Shared favorite A",a.classification()))
        t.saveFavorite(t.snapshot().token,Favorite("fb","Shared favorite B",b.classification()))
        t.personalizeChoice(t.snapshot().token,ChoiceKind.FAVORITE,"fa",ChoicePersonalization(listOf("Private favorite lookup")))
        t.orderChoices(t.snapshot().token,ChoiceKind.FAVORITE,listOf("fb","fa"))
        assertEquals(listOf("favorite:fb","favorite:fa"),t.choices().search(ChoiceQuery("Shared",setOf(ChoiceKind.FAVORITE))).items.map { it.entry.id })
        assertEquals("favorite:fa",t.choices().search(ChoiceQuery("Private favorite lookup",setOf(ChoiceKind.FAVORITE))).items.single().entry.id)
        t.orderChoices(t.snapshot().token,ChoiceKind.CARD,listOf(b.id,a.id))
        assertEquals(listOf("card:b","card:a"),t.choices().search(ChoiceQuery("Shared",setOf(ChoiceKind.CARD))).items.map { it.entry.id })
        assertEquals(listOf("card:a"),t.choices().search(ChoiceQuery("Personal discovery",setOf(ChoiceKind.CARD))).items.map { it.entry.id })
        t.saveCard(t.snapshot().token,a.copy(name="Renamed personal original"))
        assertEquals(listOf("Personal discovery"),t.data().cards.single { it.id==a.id }.personal.aliases)
        assertEquals(listOf("favorite:fb","favorite:fa"),t.choices().quick(HistoryWindow(clock.wall-1000,clock.wall)).take(2).map { it.entry.id })
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
        clock.advance(1000);t.stop(t.snapshot().token);clock.advance(1000)
        t.manual(t.snapshot().token,clock.wall-500,clock.wall-100,card.classification(),"Latest reviewed source")
        val favorite=t.choices().resolve("favorite:favorite")!!
        t.startChoice(t.snapshot().timerTarget!!,t.choices().reference(favorite))
        assertEquals("Latest reviewed source",t.snapshot().session!!.note);assertEquals(measurements,t.data().measurements)
    }
    @Test fun personalProfilesRoundTripFullAndSelectiveWithLegacyGuards() {
        val clock=FakeClock();val t=Tracker(createJournalStore(),clock)
        t.saveCard(t.snapshot().token,Card("portable","Portable"))
        t.personalizeChoice(t.snapshot().token,ChoiceKind.CARD,"portable",ChoicePersonalization(listOf("Own alias"),0,ChoiceNotePolicy.CLEAR))
        val backups=Backups(t,MemoryRollback());val text=backups.export();val decoded=Backups.decode(text)
        assertEquals(CURRENT_BACKUP_FORMAT,decoded.formatVersion);assertEquals(t.data().cards,decoded.data.cards)
        val bytes=ByteArrayOutputStream();StreamingBackup.write(decoded,bytes)
        assertEquals(decoded,StreamingBackup.read { bytes.toByteArray().inputStream() })
        val old=text.replace("\"formatVersion\": $CURRENT_BACKUP_FORMAT","\"formatVersion\": 13")
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
        val replay=importer.apply(importer.plan(selective,selective.groups.map { it.id }.toSet()),"personal-replay")
        assertTrue(replay.groups.all { it.state==TransferGroupState.UNCHANGED })
        val conflict=Tracker(createJournalStore(),clock)
        conflict.saveCard(conflict.snapshot().token,Card("portable","Conflicting original"))
        val copy=SelectiveTransfer(conflict)
        val copied=copy.apply(copy.plan(selective,selective.groups.map { it.id }.toSet(),selective.groups.associate { it.id to TransferConflictPolicy.COPY }),"profile-copy")
        assertTrue(copied.complete);assertEquals(2,conflict.data().cards.size)
        assertEquals(t.data().cards.single().personal,conflict.data().cards.single { it.id!="portable" }.personal)
        val legacy=Backups.encode(Dataset(),clock.now()).replace("\"formatVersion\": $CURRENT_BACKUP_FORMAT","\"formatVersion\": 13")
        assertEquals(CURRENT_BACKUP_FORMAT,Backups.decode(legacy).formatVersion)
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
        assertEquals("batch-type",t.data().sessions.last().selection.activityTypeId)
        val changedExisting=preview.changes.single { it.sessionId==ids.last() && it.segmentId==null }
        assertEquals(setOf(MetadataChangedField.ACTIVITY_TYPE,MetadataChangedField.HISTORICAL_PATH),changedExisting.fields)
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
    @Test fun gapWindowsPartitionAcrossMidnightAndUnknownTimeDoesNotBecomeZero() {
        val midnight=java.time.Instant.parse("2026-10-11T00:00:00Z").toEpochMilli()
        val clock=FakeClock(wall=midnight+5000);val t=Tracker(createJournalStore(),clock)
        t.manual(t.snapshot().token,midnight-2000,midnight+2000,Classification())
        val before=t.historyGaps(midnight-5000,midnight);val after=t.view().historyGaps(midnight,midnight+10000)
        assertEquals(listOf(midnight-5000 to midnight-2000),before.gaps.map { it.start to it.end })
        assertEquals(listOf(midnight+2000 to midnight+5000),after.gaps.map { it.start to it.end })
        val sums=before.gaps.sumOf { it.end-it.start }+after.gaps.sumOf { it.end-it.start }
        assertEquals(6000L,sums)
        assertEquals(4000L,t.view().report(midnight-5000,midnight+5000).rows.sumOf { it.durationMs })
    }

    @Test fun explicitRepeatPolicyAlsoWorksWithoutAnyCardProfile() {
        val clock=FakeClock();val t=Tracker(createJournalStore(),clock)
        t.manual(t.snapshot().token,clock.wall-2000,clock.wall-1000,Classification(),"Unclassified source note")
        val id=t.data().sessions.single().id
        t.repeat(t.snapshot().token,id,ChoiceNotePolicy.CLEAR);assertNull(t.snapshot().session!!.note)
        clock.advance(1000);t.stop(t.snapshot().token)
        t.repeat(t.snapshot().token,id,ChoiceNotePolicy.COPY_SOURCE);assertEquals("Unclassified source note",t.snapshot().session!!.note)
    }

    @Test fun personalProfilesSurviveCopiedOriginCompactionAndReplayWithoutFalseUndo() {
        val clock=FakeClock();val source=Tracker(createJournalStore(),clock)
        source.saveCard(source.snapshot().token,Card("profile-origin","Source"))
        source.personalizeChoice(source.snapshot().token,ChoiceKind.CARD,"profile-origin",ChoicePersonalization(listOf("Private original alias"),0,ChoiceNotePolicy.CLEAR))
        val pack=SelectiveTransfer(source).export(TransferSelection(cardIds=setOf("profile-origin")))
        var receiver=Tracker(createJournalStore(),clock)
        receiver.saveCard(receiver.snapshot().token,Card("profile-origin","Different local object"))
        var importer=SelectiveTransfer(receiver)
        val policies=pack.groups.associate { it.id to TransferConflictPolicy.COPY }
        val copied=importer.apply(importer.plan(pack,pack.groups.map { it.id }.toSet(),policies),"profile-copy-before-compaction")
        assertEquals(TransferGroupState.APPLIED,copied.groups.single().state)
        val id=receiver.data().cards.single { it.id!="profile-origin" }.id
        val profile=receiver.data().cards.single { it.id==id }.personal
        val before=receiver.data();val origin=receiver.data().transfer.origins.single()
        val preview=receiver.previewTransferRetention(TransferRetentionSelection(originIds=setOf(origin.originId)))
        assertTrue(preview.canApply)
        receiver.applyTransferRetention(preview,"profile-compact",preview.requiredAcknowledgements)
        assertEquals(before.cards,receiver.data().cards)
        assertTrue(receiver.data().transfer.operations.single().payloadsPruned)
        assertEquals(pack.groups,importer.export(TransferSelection(cardIds=setOf(id))).groups)
        val backup=Backups.decode(Backups(receiver,MemoryRollback()).export())
        assertEquals(profile,backup.data.cards.single { it.id==id }.personal)
        receiver=Tracker(reopenJournalStore(receiver.store),clock);importer=SelectiveTransfer(receiver)
        assertEquals(pack.groups,importer.export(TransferSelection(cardIds=setOf(id))).groups)
        assertTrue(importer.apply(importer.plan(pack,pack.groups.map { it.id }.toSet(),policies),"profile-copy-before-compaction").payloadsPruned)
        val afterReplay=receiver.data()
        val rejected=assertFailsWith<CoreError> { importer.undo("profile-copy-before-compaction",receiver.snapshot().token) }
        assertEquals(ErrorReason.TRANSFER_PAYLOAD_PRUNED,rejected.reason);assertEquals(afterReplay,receiver.data())
        val replay=importer.apply(importer.plan(pack,pack.groups.map { it.id }.toSet()),"profile-new-command-after-compaction")
        assertEquals(TransferGroupState.UNCHANGED,replay.groups.single().state)
        assertEquals(profile,receiver.data().cards.single { it.id==id }.personal);assertEquals(2,receiver.data().cards.size)
    }

}
