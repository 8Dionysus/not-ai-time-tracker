package dev.notai.tracker.android

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
    @Test fun v9MetadataMigratesWithDisabledDefaultAndUnchangedHistory() {
        val context = RuntimeEnvironment.getApplication();val name = "attention-v9-${newId()}.db"
        fun open() = androidx.room.Room.databaseBuilder(context,TrackerDatabase::class.java,name)
            .addMigrations(TrackerDatabase.MIGRATION_9_10).allowMainThreadQueries().build()
        val clock = FakeClock();val first = open();val tracker = Tracker(RoomStore(first),clock)
        tracker.manual(tracker.snapshot().token,clock.wall-2000,clock.wall-1000,Classification())
        val before = tracker.data();first.close()
        // Entity SQL/hash are identical in Room9/10; only the metadata contract version changes.
        android.database.sqlite.SQLiteDatabase.openDatabase(context.getDatabasePath(name).absolutePath,null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("PRAGMA user_version=9") }
        val migrated = open()
        try {
            val after = RoomStore(migrated).read()
            assertEquals(before,after);assertEquals(LongRecordPreference(),after.settings.longRecordReminder)
            assertFalse(after.settings.longRecordReminder.enabled)
        } finally { migrated.close();context.deleteDatabase(name) }
    }
}
