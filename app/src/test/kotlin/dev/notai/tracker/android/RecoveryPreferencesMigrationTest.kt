package dev.notai.tracker.android

import androidx.room.Room
import dev.notai.tracker.*
import dev.notai.tracker.android.ui.*
import java.io.File
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35],qualifiers = "ru-rRU")
class RecoveryPreferencesMigrationTest {
    private val app = RuntimeEnvironment.getApplication()
    private val oldSync = DirectoryDurability.sync;private val oldRename = DirectoryDurability.rename;private val oldLink = DirectoryDurability.link
    @org.junit.Before fun hostDurability() {
        DirectoryDurability.sync = { java.nio.channels.FileChannel.open(it.toPath(),java.nio.file.StandardOpenOption.READ).use { fd -> fd.force(true) } }
        DirectoryDurability.rename = { a,b -> java.nio.file.Files.move(a.toPath(),b.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);Unit }
        DirectoryDurability.link = { a,b -> java.nio.file.Files.createLink(b.toPath(),a.toPath());Unit }
    }
    @After fun reset() {
        DirectoryDurability.sync = oldSync;DirectoryDurability.rename = oldRename;DirectoryDurability.link = oldLink
        app.getSharedPreferences("app-locale",0).edit().clear().commit();app.getSharedPreferences("recovery-appearance-test",0).edit().clear().commit()
    }
    @Test fun recoveryLanguageDefaultsToSystemAndAllChoicesPersistWithoutTouchingData() {
        app.getSharedPreferences("app-locale",0).edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(app,TrackerDatabase::class.java).allowMainThreadQueries().build()
        try {
            val tracker = Tracker(RoomStore(db),FakeClock());tracker.manual(tracker.snapshot().token,tracker.clock.now().wall-1000,tracker.clock.now().wall,Classification(name = "我的名字"),"Literal user note")
            val before = tracker.data();val system = AppLanguage.state(app)
            assertNull(system.preference.explicitTag)
            val translated = mutableSetOf<String>()
            for(tag in AppLanguage.supported) {
                val chosen = AppLanguage.select(app,tag,AppLanguage.state(app).preference.revision)
                assertEquals(tag,chosen.resolvedLocaleTag)
                assertEquals(tag,AppLocalePreferences(app).read().explicitTag)
                val resources = AppLanguage.context(app).resources
                translated += resources.getString(R.string.deleted_title)
                assertTrue(resources.getString(R.string.undo_deletion).isNotBlank())
                assertTrue(resources.getString(R.string.restore_error).isNotBlank())
                assertTrue(resources.getString(R.string.delete_title,2).isNotBlank())
                val counts = resources.getString(R.string.delete_counts,1,2,3,4,5,6,7)
                assertTrue(counts.isNotBlank());assertFalse(counts.contains("%"))
                assertEquals(before,tracker.data())
            }
            assertEquals(12,translated.size)
            val reset = AppLanguage.select(app,null,AppLanguage.state(app).preference.revision)
            assertNull(AppLocalePreferences(app).read().explicitTag);assertEquals(system.resolvedLocaleTag,reset.resolvedLocaleTag)
            assertEquals(before,tracker.data())
            val prefs = app.getSharedPreferences("recovery-appearance-test",0)
            for(accent in Accent.entries) {
                val appearance = Appearance(accent = accent,pulse = false);appearance.write(prefs)
                assertTrue(prefs.edit().putString("theme",ThemeChoice.NEON.name).commit())
                assertEquals(appearance,Appearance.read(app.getSharedPreferences("recovery-appearance-test",0)))
                assertEquals("NEON",prefs.getString("theme",null))
            }
        } finally { db.close() }
    }
    @Test fun recoveryVersion12MigrationPreservesLiveGraphThenTrashSurvivesRoomReopen() = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val name = "recovery-v12-${newId()}.db"
        val schemas = File(requireNotNull(System.getProperty("tracker.samples"))).parentFile!!.resolve("app/schemas/dev.notai.tracker.android.TrackerDatabase")
        fun schema(version: Int) = codec.parseToJsonElement(schemas.resolve("$version.json").readText()).jsonObject.getValue("database").jsonObject
        assertEquals(schema(12).getValue("entities"),schema(13).getValue("entities")) // Actual v12 physical layout, not a guessed schema.
        var db = TrackerDatabase.open(app,name)
        try {
            val clock = FakeClock();var tracker = Tracker(RoomStore(db),clock)
            tracker.manual(tracker.snapshot().token,clock.wall-1000,clock.wall,Classification(name = "Exact identity"),"literal")
            val before = tracker.data();db.close()
            android.database.sqlite.SQLiteDatabase.openDatabase(app.getDatabasePath(name).path,null,0).use { old ->
                old.execSQL("UPDATE room_master_table SET identity_hash=? WHERE id=42",arrayOf(schema(12).getValue("identityHash").jsonPrimitive.content))
                old.version = 12
            }
            db = TrackerDatabase.open(app,name);tracker = Tracker(RoomStore(db),clock)
            assertEquals(before,tracker.data());assertTrue(tracker.data().deletedRecords.isEmpty())
            tracker.deleteSession(tracker.snapshot().token,before.sessions.single().id)
            val archived = tracker.data();db.close();db = TrackerDatabase.open(app,name);tracker = Tracker(RoomStore(db),clock)
            assertEquals(archived,tracker.data())
            tracker.restoreDeleted(tracker.snapshot().token,listOf(before.sessions.single().id))
            assertEquals(before.sessions,tracker.data().sessions);assertEquals(before.segments,tracker.data().segments)
        } finally { db.close();app.deleteDatabase(name) }
    }
}
