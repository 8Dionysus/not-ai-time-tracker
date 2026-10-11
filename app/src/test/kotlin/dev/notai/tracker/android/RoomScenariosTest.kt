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
        val db = androidx.room.Room.databaseBuilder(RuntimeEnvironment.getApplication(),TrackerDatabase::class.java,name).addMigrations(TrackerDatabase.MIGRATION_1_2).allowMainThreadQueries().build()
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
        org.junit.Assert.assertEquals(2,databases.getValue(store).second.openHelper.readableDatabase.version)
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
    @After fun close() { databases.values.forEach { (name,db) -> db.close(); RuntimeEnvironment.getApplication().deleteDatabase(name) }; databases.clear() }
}
