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
@Config(sdk = [36])
class AttentionPreferencesMigrationTest {
    private fun open(name: String) = Room.databaseBuilder(RuntimeEnvironment.getApplication(),TrackerDatabase::class.java,name)
        .addMigrations(TrackerDatabase.MIGRATION_10_11).allowMainThreadQueries().build()

    @Test fun v10MetadataMigratesWithDisabledDefaultAndUnchangedHistory() {
        val context = RuntimeEnvironment.getApplication()
        for(status in SessionStatus.entries) {
            val name = "attention-v10-${newId()}.db";val clock = FakeClock()
            val first = open(name);val store = RoomStore(first);val tracker = Tracker(store,clock)
            tracker.start(tracker.snapshot().token)
            tracker.applyStartBackfill(tracker.previewStartBackfill(tracker.snapshot().token,tracker.data().sessions.single().id,clock.wall-1000))
            clock.advance(1000)
            if(status == SessionStatus.PAUSED) tracker.pause(tracker.snapshot().token)
            if(status == SessionStatus.STOPPED) tracker.stop(tracker.snapshot().token)
            if(status == SessionStatus.RECOVERY) store.transaction { data ->
                data.copy(sessions = data.sessions.map { it.copy(status = status) },segments = data.segments.map {
                    if(it.anchor == null) it else it.copy(anchor = null,uncertain = true)
                }) to Unit
            }
            val before = tracker.data();assertEquals(status,before.sessions.single().status)
            assertEquals(1,before.segments.count { it.manualConfirmation != null });first.close()
            // Schema10/11 have identical entity SQL/hash. The old fixture omits the new
            // preference (default serialization omits it); existing manual provenance stays.
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use { it.version = 10 }
            val migrated = open(name)
            try {
                val after = RoomStore(migrated).read()
                assertEquals(before,after);assertEquals(LongRecordPreference(),after.settings.longRecordReminder)
                assertEquals(11,migrated.openHelper.readableDatabase.version)
            } finally { migrated.close() }
            val reopened = open(name)
            try { assertEquals(before,RoomStore(reopened).read()) }
            finally { reopened.close();context.deleteDatabase(name) }
        }
    }

    @Test fun futurePreferenceInV10IsRefusedAndMigrationTransactionKeepsOriginalBytes() {
        val context = RuntimeEnvironment.getApplication();val name = "attention-v10-future-${newId()}.db"
        val first = open(name);val clock = FakeClock();val tracker = Tracker(RoomStore(first),clock)
        tracker.manual(tracker.snapshot().token,clock.wall-2000,clock.wall-1000,Classification())
        tracker.settings(tracker.snapshot().token,tracker.data().settings.copy(longRecordReminder = LongRecordPreference(true,321,17)))
        val expected = tracker.data();first.close();val file = context.getDatabasePath(name)
        val prior = SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use { sql ->
            sql.version = 10
            sql.rawQuery("SELECT payload FROM metadata WHERE id=1",null).use { c -> assertTrue(c.moveToFirst());c.getString(0) }
        }
        val rejected = open(name)
        try {
            val failure = assertThrows(Exception::class.java) { RoomStore(rejected).read() }
            assertTrue(generateSequence(failure as Throwable?) { it.cause }.any {
                it is CoreError && it.code == ErrorCode.STORAGE && it.message == "Ordinary reminder preference in v10 metadata"
            }) // Proves this migration ran; a missing path/schema failure is insufficient.
        } finally { rejected.close() }
        SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use { sql ->
            assertEquals(10,sql.version)
            sql.rawQuery("SELECT payload FROM metadata WHERE id=1",null).use { c -> assertTrue(c.moveToFirst());assertEquals(prior,c.getString(0)) }
            sql.version = 11 // Restore only this isolated fixture to inspect exact domain bytes.
        }
        val restored = open(name)
        try { assertEquals(expected,RoomStore(restored).read()) }
        finally { restored.close();context.deleteDatabase(name) }
    }
}
