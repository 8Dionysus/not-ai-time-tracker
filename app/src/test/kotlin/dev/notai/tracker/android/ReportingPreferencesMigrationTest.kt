package dev.notai.tracker.android

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import dev.notai.tracker.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.Assert.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReportingPreferencesMigrationTest {
    private fun open(name: String) = Room.databaseBuilder(RuntimeEnvironment.getApplication(),TrackerDatabase::class.java,name)
        .addMigrations(TrackerDatabase.MIGRATION_11_12).allowMainThreadQueries().build()
    @Test fun legacy11KeepsAllHistoryAndCPreferencesByteForByte() {
        val context = RuntimeEnvironment.getApplication()
        for(status in SessionStatus.entries) {
            val name = "reporting-v11-${newId()}.db";val clock = FakeClock()
            val first = open(name);val tracker = Tracker(RoomStore(first),clock)
            tracker.start(tracker.snapshot().token)
            tracker.applyStartBackfill(tracker.previewStartBackfill(tracker.snapshot().token,tracker.data().sessions.single().id,clock.wall-1000))
            clock.advance(1000)
            if(status == SessionStatus.PAUSED) tracker.pause(tracker.snapshot().token)
            if(status == SessionStatus.STOPPED) tracker.stop(tracker.snapshot().token)
            if(status == SessionStatus.RECOVERY) tracker.store.transaction { d ->
                d.copy(sessions = d.sessions.map { it.copy(status = status) },segments = d.segments.map { if(it.anchor == null) it else it.copy(anchor = null,uncertain = true) }) to Unit
            }
            tracker.settings(tracker.snapshot().token,tracker.data().settings.copy(longRecordReminder = LongRecordPreference(true,321,17)))
            tracker.saveGoal(tracker.snapshot().token,Goal("legacy","Legacy",GoalMetric.DURATION_MS,1000))
            val before = tracker.data();first.close()
            val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.version = 11
                db.rawQuery("SELECT payload FROM metadata WHERE id=1",null).use { c -> assertTrue(c.moveToFirst());c.getString(0) }
            }
            val migrated = open(name)
            try {
                val after = RoomStore(migrated).read();assertEquals(before,after)
                assertEquals(0,after.settings.reportDayStartMinute);assertTrue(after.savedReports.isEmpty());assertTrue(after.goals.single().revisions.isEmpty())
                migrated.openHelper.readableDatabase.query("SELECT payload FROM metadata WHERE id=1").use { c -> assertTrue(c.moveToFirst());assertEquals(raw,c.getString(0)) }
                assertEquals(TRACKER_DATABASE_VERSION,migrated.openHelper.readableDatabase.version)
            } finally { migrated.close();context.deleteDatabase(name) }
        }
    }
    @Test fun newReportingFieldsLabelled11FailWithoutMutatingMetadata() {
        val context = RuntimeEnvironment.getApplication();val name = "reporting-v11-invalid-${newId()}.db"
        val first = open(name);val tracker = Tracker(RoomStore(first),FakeClock())
        tracker.configureReportingDay(tracker.snapshot().token,ReportingDayPolicy("UTC",240));first.close()
        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.version = 11;db.rawQuery("SELECT payload FROM metadata WHERE id=1",null).use { c -> assertTrue(c.moveToFirst());c.getString(0) }
        }
        val refused = open(name)
        try {
            val error = assertThrows(Exception::class.java) { RoomStore(refused).read() }
            assertTrue(generateSequence(error as Throwable?) { it.cause }.any { it is CoreError && it.message == "Reporting fields in legacy metadata" })
        } finally { refused.close() }
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use { db ->
            assertEquals(11,db.version);db.rawQuery("SELECT payload FROM metadata WHERE id=1",null).use { c -> assertTrue(c.moveToFirst());assertEquals(raw,c.getString(0)) }
        }
        context.deleteDatabase(name)
    }
}
