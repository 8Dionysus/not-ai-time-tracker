package dev.notai.tracker.android

import dev.notai.tracker.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomScenariosTest: ScenarioContract() {
    private val databases = mutableMapOf<StateStore,Pair<String,TrackerDatabase>>()
    override fun createStore(): StateStore = open("test-${newId()}.db")
    private fun open(name: String): StateStore {
        val db = androidx.room.Room.databaseBuilder(RuntimeEnvironment.getApplication(),TrackerDatabase::class.java,name).addMigrations(TrackerDatabase.MIGRATION_1_2,TrackerDatabase.MIGRATION_2_3).allowMainThreadQueries().build()
        return RoomStore(db).also { databases[it] = name to db }
    }
    override fun reopen(store: StateStore): StateStore {
        val (name,db) = databases.remove(store)!!; db.close(); return open(name)
    }
    override fun migrateStoredV1(json: String): Dataset {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-${newId()}.db"
        val path = context.getDatabasePath(name); path.parentFile!!.mkdirs()
        val legacy = codec.parseToJsonElement(json).jsonObject.getValue("data").jsonObject
        val schemaFile = java.io.File(requireNotNull(System.getProperty("tracker.samples"))).parentFile!!.resolve("app/schemas/dev.notai.tracker.android.TrackerDatabase/1.json")
        val schema = codec.parseToJsonElement(schemaFile.readText()).jsonObject.getValue("database").jsonObject
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(path,null).use { db ->
            schema.getValue("entities").jsonArray.forEach { entity ->
                val e = entity.jsonObject
                db.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}",e.getValue("tableName").jsonPrimitive.content))
                e["indices"]?.jsonArray?.forEach { index ->
                    db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}",e.getValue("tableName").jsonPrimitive.content))
                }
            }
            val arrays = mapOf("types" to "activity_types","cards" to "cards","sessions" to "sessions","segments" to "segments","favorites" to "favorites","measurements" to "measurements")
            arrays.forEach { (key,table) -> legacy.getValue(key).jsonArray.forEachIndexed { i,element ->
                val row = element.jsonObject; val values = android.content.ContentValues()
                values.put("id",row.getValue("id").jsonPrimitive.content); values.put("position",i); values.put("payload",row.toString())
                if(key == "segments") {
                    values.put("sessionId",row.getValue("sessionId").jsonPrimitive.content)
                    if(row["end"] is JsonNull) values.put("openSlot",1) else values.putNull("openSlot")
                }
                db.insertOrThrow(table,null,values)
            } }
            val meta = JsonObject(legacy.toMutableMap().apply { arrays.keys.forEach { put(it,JsonArray(emptyList())) } })
            db.execSQL("INSERT INTO metadata(id,payload) VALUES(1,?)",arrayOf(meta.toString()))
            db.version = 1
        }
        val store = open(name)
        org.junit.Assert.assertEquals(3,databases.getValue(store).second.openHelper.readableDatabase.version)
        return store.read().also { validate(it) }
    }
    override fun assertImportRollback(tracker: Tracker,json: String,disk: RollbackStore) {
        val db = databases.getValue(tracker.store).second
        // Metadata is written last, after child tables were replaced: force a real SQLite write failure.
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_import BEFORE INSERT ON metadata BEGIN SELECT RAISE(ABORT, 'injected write failure'); END")
        try {
            val backups = Backups(tracker,disk)
            val error = org.junit.Assert.assertThrows(CoreError::class.java) { backups.apply(backups.prepare(json)) }
            org.junit.Assert.assertEquals(ErrorCode.STORAGE,error.code)
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_import") }
    }
    override fun assertTransferFailure(transfer: HistoryTransfer,preview: HistoryPreview,tracker: Tracker) {
        val db = databases.getValue(tracker.store).second.openHelper.writableDatabase
        val before = tracker.data()
        db.execSQL("CREATE TRIGGER fail_transfer BEFORE INSERT ON metadata BEGIN SELECT RAISE(ABORT, 'injected transfer failure'); END")
        try {
            val e = org.junit.Assert.assertThrows(CoreError::class.java) { transfer.apply(preview) }
            org.junit.Assert.assertEquals(ErrorCode.STORAGE,e.code)
        } finally { db.execSQL("DROP TRIGGER fail_transfer") }
        org.junit.Assert.assertEquals(before,tracker.data())
    }
    override fun storageWorkload() {
        val store = createStore(); val db = databases.getValue(store).second
        val clock = FakeClock(); val tracker = Tracker(store,clock)
        val root = java.io.File(requireNotNull(System.getProperty("tracker.samples"))).parentFile!!
        val vocabulary = codec.decodeFromString<VocabularyData>(root.resolve("evidence/full-projection/runtime-vocabulary.json").readText())
        tracker.addCatalog(tracker.snapshot().token,vocabulary)
        val sql = db.openHelper.writableDatabase
        sql.execSQL("CREATE TABLE io_audit(table_name TEXT, operation TEXT)")
        for(table in listOf("activity_types","cards","sessions","segments","metadata","favorites","measurements","vocabulary","store_generation")) {
            for(op in listOf("INSERT","UPDATE","DELETE")) sql.execSQL("CREATE TRIGGER audit_${table}_${op} AFTER $op ON $table BEGIN INSERT INTO io_audit VALUES('$table','$op'); END")
        }
        val started = System.nanoTime()
        tracker.start(tracker.snapshot().token); clock.advance(1000); tracker.pause(tracker.snapshot().token)
        val elapsed = System.nanoTime()-started
        val rows = mutableListOf<String>()
        sql.query("SELECT table_name,operation,COUNT(*) FROM io_audit GROUP BY table_name,operation ORDER BY table_name,operation").use { c ->
            while(c.moveToNext()) rows += "${c.getString(0)} ${c.getString(1)} ${c.getLong(2)}"
        }
        root.resolve("build/storage-workload.txt").writeText("Start + Pause; ${tracker.data().types.size} stored types / ${tracker.data().axes.size} axes (1918 imported types plus existing seed); Robolectric JVM, not device\nElapsed ns: $elapsed\n"+rows.joinToString("\n")+"\n")
        for(table in listOf("activity_types","cards","sessions","segments","metadata","favorites","measurements","vocabulary","store_generation"))
            for(op in listOf("INSERT","UPDATE","DELETE")) sql.execSQL("DROP TRIGGER audit_${table}_${op}")
        org.junit.Assert.assertFalse(rows.any { it.startsWith("activity_types ") || it.startsWith("vocabulary ") })
        sql.execSQL("DROP TABLE io_audit")
        val another = Tracker(RoomStore(db),clock)
        another.saveCard(another.snapshot().token,Card(name = "Other store instance"))
        org.junit.Assert.assertEquals(another.data(),tracker.data()) // generation invalidates first cache
        val beforeFailure = tracker.data()
        sql.execSQL("CREATE TRIGGER fail_timer BEFORE INSERT ON metadata BEGIN SELECT RAISE(ABORT, 'injected timer failure'); END")
        try {
            val error = org.junit.Assert.assertThrows(CoreError::class.java) { tracker.resume(tracker.snapshot().token) }
            org.junit.Assert.assertEquals(ErrorCode.STORAGE,error.code)
        } finally { sql.execSQL("DROP TRIGGER fail_timer") }
        org.junit.Assert.assertEquals(beforeFailure,tracker.data())
        tracker.resume(tracker.snapshot().token); clock.advance(1000)
        val beforeSwitch = tracker.data(); tracker.switch(tracker.snapshot().token,Classification())
        clock.advance(1000); tracker.undo(tracker.snapshot().token)
        org.junit.Assert.assertEquals(beforeSwitch.sessions,tracker.data().sessions)
        org.junit.Assert.assertEquals(beforeSwitch.segments,tracker.data().segments)
        org.junit.Assert.assertEquals(3000L,tracker.snapshot().trustedDurationMs)
        assertV2Migration(tracker.data())
        val saved = tracker.data(); val reopened = reopen(store)
        org.junit.Assert.assertEquals(saved,reopened.read())
    }
    private fun assertV2Migration(data: Dataset) {
        val context = RuntimeEnvironment.getApplication(); val name = "migration-v2-${newId()}.db"
        val schemaFile = java.io.File(requireNotNull(System.getProperty("tracker.samples"))).parentFile!!.resolve("app/schemas/dev.notai.tracker.android.TrackerDatabase/2.json")
        val schema = codec.parseToJsonElement(schemaFile.readText()).jsonObject.getValue("database").jsonObject
        val graph = codec.parseToJsonElement(codec.encodeToString(data)).jsonObject
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null).use { sql ->
            schema.getValue("entities").jsonArray.forEach { entity ->
                val e = entity.jsonObject
                sql.execSQL(e.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}",e.getValue("tableName").jsonPrimitive.content))
                e["indices"]?.jsonArray?.forEach { index -> sql.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}",e.getValue("tableName").jsonPrimitive.content)) }
            }
            val tables = mapOf("types" to "activity_types","cards" to "cards","sessions" to "sessions","segments" to "segments","favorites" to "favorites","measurements" to "measurements")
            for((key,table) in tables) graph.getValue(key).jsonArray.forEachIndexed { i,item ->
                val row = item.jsonObject; val values = android.content.ContentValues()
                values.put("id",row.getValue("id").jsonPrimitive.content); values.put("position",i); values.put("payload",item.toString())
                if(key == "segments") {
                    values.put("sessionId",row.getValue("sessionId").jsonPrimitive.content)
                    if(row["end"] is JsonNull) values.put("openSlot",1) else values.putNull("openSlot")
                }
                sql.insertOrThrow(table,null,values)
            }
            val meta = JsonObject(graph.toMutableMap().apply { tables.keys.forEach { put(it,JsonArray(emptyList())) } })
            sql.execSQL("INSERT INTO metadata(id,payload) VALUES(1,?)",arrayOf(meta.toString())); sql.version = 2
        }
        val migrated = open(name)
        org.junit.Assert.assertEquals(data,migrated.read())
    }
    @After fun close() { databases.values.forEach { (name,db) -> db.close(); RuntimeEnvironment.getApplication().deleteDatabase(name) }; databases.clear() }
}
