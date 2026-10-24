package dev.notai.tracker.android

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.migration.Migration
import dev.notai.tracker.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.Assert.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36])
class JournalAMigrationTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private fun open(name: String,migration: Migration=TrackerDatabase.MIGRATION_11_12)=Room.databaseBuilder(context,TrackerDatabase::class.java,name)
        .addMigrations(migration,TrackerDatabase.MIGRATION_12_13).allowMainThreadQueries().build()
    private fun sql(name: String,block: (SQLiteDatabase)->Unit)=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READWRITE).use(block)
    private fun contents(db: SQLiteDatabase): Map<String,List<List<String?>>> = listOf("metadata","cards","favorites","activity_types","sessions","segments","measurements","vocabulary","store_generation","history_classifications").associateWith { table ->
        db.rawQuery("SELECT * FROM $table ORDER BY 1",null).use { c -> buildList {
            while(c.moveToNext()) add((0 until c.columnCount).map { if(c.isNull(it)) null else c.getString(it) })
        } }
    }
    @Test fun actualV11DefaultRowsKeepAllSessionStatesAndExportRestoreComparison() {
        for(status in SessionStatus.entries) {
            val name="journal-v11-${newId()}.db";val clock=FakeClock();val first=open(name);val store=RoomStore(first);val t=Tracker(store,clock)
            t.saveCard(t.snapshot().token,Card("kept","Kept"));t.start(t.snapshot().token,t.selection("kept"));clock.advance(1000)
            if(status==SessionStatus.PAUSED)t.pause(t.snapshot().token)
            if(status==SessionStatus.STOPPED)t.stop(t.snapshot().token)
            if(status==SessionStatus.RECOVERY)store.transaction { d -> d.copy(sessions=d.sessions.map { it.copy(status=status) },segments=d.segments.map { it.copy(anchor=null,uncertain=true) }) to Unit }
            val before=t.data();first.close();sql(name) { it.version=11 }
            val migrated=open(name)
            try {
                val next=Tracker(RoomStore(migrated),clock);assertEquals(before,next.data())
                assertEquals(TRACKER_DATABASE_VERSION,migrated.openHelper.readableDatabase.version)
                val backup=Backups(next,MemoryRollback()).export()
                val destinationDb=Room.inMemoryDatabaseBuilder(context,TrackerDatabase::class.java).allowMainThreadQueries().build()
                try {
                    val destination=Tracker(RoomStore(destinationDb),clock)
                    val restore=Backups(destination,MemoryRollback());restore.apply(restore.prepare(backup))
                    val expected=Backups.restoredData(Backups.decode(backup),destination.data().revision)
                    assertEquals(expected.copy(controlBarrier=destination.data().controlBarrier),destination.data())
                } finally { destinationDb.close() }
            } finally { migrated.close();context.deleteDatabase(name) }
        }
    }
    @Test fun futureProfileInV11ReachesGuardAndKeepsEveryRowAndVersion() {
        val name="journal-v11-future-${newId()}.db";val first=open(name);val t=Tracker(RoomStore(first),FakeClock())
        t.saveCard(t.snapshot().token,Card("future","Future"));t.personalizeChoice(t.snapshot().token,ChoiceKind.CARD,"future",ChoicePersonalization(listOf("Future alias")))
        first.close();lateinit var before: Map<String,List<List<String?>>>
        sql(name) { it.version=11;before=contents(it) }
        val candidate=open(name)
        try {
            val failure=assertThrows(Exception::class.java) { RoomStore(candidate).read() }
            assertTrue(generateSequence(failure as Throwable?) { it.cause }.any { it is CoreError && it.code==ErrorCode.STORAGE && it.message=="Personal choices in v11 rows" })
        } finally { candidate.close() }
        sql(name) { assertEquals(11,it.version);assertEquals(before,contents(it)) }
        context.deleteDatabase(name)
    }
    @Test fun interruptionAfterMigrationWriteRollsBackWholeIsolatedDatabase() {
        val name="journal-v11-interrupt-${newId()}.db";val first=open(name);val t=Tracker(RoomStore(first),FakeClock())
        t.saveCard(t.snapshot().token,Card("intact","Intact"));val expected=t.data();first.close()
        lateinit var before: Map<String,List<List<String?>>>
        sql(name) { it.version=11;before=contents(it) }
        val fault=object: Migration(11,12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                TrackerDatabase.MIGRATION_11_12.migrate(db)
                db.execSQL("UPDATE cards SET payload='isolated interrupted test write'")
                error("JOURNAL_MIGRATION_INTERRUPTION_FIXTURE")
            }
        }
        val interrupted=open(name,fault)
        try {
            val failure=assertThrows(Exception::class.java) { RoomStore(interrupted).read() }
            assertTrue(generateSequence(failure as Throwable?) { it.cause }.any { it.message=="JOURNAL_MIGRATION_INTERRUPTION_FIXTURE" })
        } finally { interrupted.close() }
        sql(name) { assertEquals(11,it.version);assertEquals(before,contents(it)) }
        val recovered=open(name)
        try { assertEquals(expected,RoomStore(recovered).read()) } finally { recovered.close();context.deleteDatabase(name) }
    }
    @Test fun futureSubjectAndBoundsEachReachTheirV11GuardWithoutChangingRows() {
        for(kind in listOf("subject","bounds")) {
            val name="journal-v11-$kind-${newId()}.db";val first=open(name);val t=Tracker(RoomStore(first),FakeClock())
            t.saveCard(t.snapshot().token,Card("future-card","Future"))
            val expected=if(kind=="subject") {
                t.defineSubject(t.snapshot().token,CardSubject("future-card","edition"))
                "Subject quantities in v11 metadata"
            } else {
                t.saveUnit(t.snapshot().token,UnitDefinition("future-score","score","score"))
                t.saveMetric(t.snapshot().token,Metric("future-scale","Scale",numberKind=NumberKind.INTEGER,canonicalUnitId="future-score",bounds=ScaleBounds(Decimal.ZERO,Decimal("5"),"none","all")))
                "Subject quantities in v11 vocabulary"
            }
            first.close();lateinit var before: Map<String,List<List<String?>>>
            sql(name) { it.version=11;before=contents(it) }
            val candidate=open(name)
            try {
                val failure=assertThrows(Exception::class.java) { RoomStore(candidate).read() }
                assertTrue(generateSequence(failure as Throwable?) { it.cause }.any { it is CoreError && it.code==ErrorCode.STORAGE && it.message==expected })
            } finally { candidate.close() }
            sql(name) { assertEquals(11,it.version);assertEquals(before,contents(it)) }
            context.deleteDatabase(name)
        }
    }

}
