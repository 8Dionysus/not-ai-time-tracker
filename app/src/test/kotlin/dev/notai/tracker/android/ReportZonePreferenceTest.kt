package dev.notai.tracker.android

import androidx.room.Room
import dev.notai.tracker.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.Assert.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReportZonePreferenceTest {
    private val app = RuntimeEnvironment.getApplication()
    private val database = Room.inMemoryDatabaseBuilder(app,TrackerDatabase::class.java).allowMainThreadQueries().build()
    private val tracker = Tracker(RoomStore(database),FakeClock())
    @After fun close() { database.close();app.getSharedPreferences("report-zone",0).edit().clear().commit() }
    @Test fun existingFixedSurvivesUntilExplicitAutoThenReconcilesWithoutChangingFactsDayStartOrGoals() {
        app.getSharedPreferences("report-zone",0).edit().clear().commit()
        tracker.configureReportingDay(tracker.snapshot().token,ReportingDayPolicy("UTC",240))
        tracker.manual(tracker.snapshot().token,tracker.clock.now().wall-1000,tracker.clock.now().wall,Classification())
        tracker.saveGoal(tracker.snapshot().token,Goal("zone-goal","Keep boundaries",GoalMetric.DURATION_MS,1000))
        var zone = "Europe/Berlin"
        val preference = ReportZonePreference(app,tracker,false) { zone }
        val before = tracker.data()
        assertFalse(preference.automatic);assertNull(preference.reconcile());assertEquals(before,tracker.data())
        preference.setAutomatic(tracker.snapshot().token,true)
        assertTrue(preference.automatic);assertEquals(zone,tracker.data().settings.reportZone)
        assertNull(preference.reconcile())
        zone = "America/New_York";preference.reconcile()
        assertEquals(zone,tracker.data().settings.reportZone);assertEquals(240,tracker.data().settings.reportDayStartMinute)
        assertEquals(before.sessions,tracker.data().sessions);assertEquals(before.segments,tracker.data().segments)
        assertEquals(before.goals,tracker.data().goals)
        val json = Backups(tracker,RollbackStoreForZoneTest()).export()
        val exported = StreamingBackup.read { json.byteInputStream() }
        assertEquals(15,exported.formatVersion);assertEquals(zone,exported.data.settings.reportZone)
        preference.setFixed(tracker.snapshot().token,"Asia/Tokyo")
        assertFalse(preference.automatic);zone = "UTC";assertNull(preference.reconcile())
        assertEquals("Asia/Tokyo",tracker.data().settings.reportZone)
        assertFalse(ReportZonePreference(app,tracker,false) { zone }.automatic)
    }
    @Test fun newInstallDefaultsAutoAndStaleModeChangeDoesNotOverwriteThePreference() {
        app.getSharedPreferences("report-zone",0).edit().clear().commit()
        val preference = ReportZonePreference(app,tracker,true) { "Asia/Tokyo" }
        assertTrue(preference.automatic);preference.reconcile()
        assertEquals("Asia/Tokyo",tracker.data().settings.reportZone)
        val old = tracker.snapshot().token
        tracker.saveCard(old,Card("concurrent-zone","Concurrent"))
        val before = tracker.data()
        assertEquals(ErrorCode.STALE,assertThrows(CoreError::class.java) { preference.setFixed(old,"UTC") }.code)
        assertTrue(preference.automatic);assertEquals(before,tracker.data())
    }
    @Test fun failedFinalPreferenceSaveRecoversOnStartupWithoutRevertingFixedZone() {
        app.getSharedPreferences("report-zone",0).edit().clear().commit()
        tracker.manual(tracker.snapshot().token,tracker.clock.now().wall-1000,tracker.clock.now().wall,Classification())
        tracker.configureReportingDay(tracker.snapshot().token,ReportingDayPolicy("UTC",275))
        tracker.saveGoal(tracker.snapshot().token,Goal("recovery-zone-goal","Preserve",GoalMetric.DURATION_MS,1000))
        for(throwing in listOf(false,true)) {
            ReportZonePreference(app,tracker,false) { "Europe/Berlin" }.setAutomatic(tracker.snapshot().token,true)
            val before = tracker.data()
            val preference = ReportZonePreference(app,tracker,false) { "Europe/Berlin" }
            var writes = 0
            preference.writePreference = { editor ->
                if(++writes == 2) { if(throwing) throw java.io.IOException("Synthetic preference write failure") else false }
                else editor.commit()
            }
            assertThrows(CoreError::class.java) { preference.setFixed(tracker.snapshot().token,"UTC") }
            assertEquals(2,writes);assertEquals("UTC",tracker.data().settings.reportZone)
            assertFalse(preference.automatic);assertNotNull(preference.recoveryProblem)
            val committed = tracker.data()
            val startup = ReportZonePreference(app,tracker,false) { "Europe/Berlin" }
            startup.reconcile();startup.reconcile()
            assertFalse(startup.automatic);assertNull(startup.recoveryProblem)
            assertEquals(committed,tracker.data()) // Acknowledgement recovery performs no second core mutation.
            assertEquals(before.sessions,committed.sessions);assertEquals(before.segments,committed.segments)
            assertEquals(before.goals,committed.goals);assertEquals(275,committed.settings.reportDayStartMinute)
        }
    }
    @Test fun pendingZoneConflictBlocksAutomaticRewriteUntilFreshExplicitChoice() {
        app.getSharedPreferences("report-zone",0).edit().clear().commit()
        val preference = ReportZonePreference(app,tracker,true) { "Europe/Berlin" };preference.reconcile()
        var writes = 0
        preference.writePreference = { editor -> if(++writes == 2) false else editor.commit() }
        assertThrows(CoreError::class.java) { preference.setFixed(tracker.snapshot().token,"UTC") }
        tracker.configureReportingDay(tracker.snapshot().token,ReportingDayPolicy("Asia/Tokyo",275))
        val startup = ReportZonePreference(app,tracker,false) { "Europe/Berlin" };val before = tracker.data()
        assertNull(startup.reconcile());assertNotNull(startup.recoveryProblem);assertEquals(before,tracker.data())
        startup.setFixed(tracker.snapshot().token,"Asia/Tokyo")
        assertFalse(startup.automatic);assertNull(startup.recoveryProblem);assertNull(startup.reconcile())
        assertEquals("Asia/Tokyo",tracker.data().settings.reportZone);assertEquals(275,tracker.data().settings.reportDayStartMinute)
    }
    private class RollbackStoreForZoneTest: RollbackStore {
        override fun save(json: String): String = error("Export does not save rollback")
        override fun read(reference: String): String = error("Export does not read rollback")
    }
}
