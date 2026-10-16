package dev.notai.tracker.android

import android.content.Context
import androidx.room.*
import dev.notai.tracker.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.Callable

@Entity(tableName = "metadata") data class MetaRow(@PrimaryKey val id: Int = 1, val payload: String)
@Entity(tableName = "activity_types") data class TypeRow(@PrimaryKey val id: String,val position: Int,val payload: String)
@Entity(tableName = "cards") data class CardRow(@PrimaryKey val id: String,val position: Int,val payload: String)
@Entity(tableName = "history_classifications") data class ClassificationRow(@PrimaryKey val id: String,val payload: String)
@Entity(tableName = "sessions",foreignKeys = [ForeignKey(entity = ClassificationRow::class,parentColumns = ["id"],childColumns = ["selectionId"])],
    indices = [Index(value = ["position","id"]),Index("selectionId")])
data class SessionRow(@PrimaryKey val id: String,val position: Int,val status: String,val selectionId: String,val note: String?,val importOrigin: String?)
@Entity(tableName = "segments", foreignKeys = [ForeignKey(entity = SessionRow::class,parentColumns = ["id"],childColumns = ["sessionId"],onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = ClassificationRow::class,parentColumns = ["id"],childColumns = ["selectionId"])],
    indices = [Index("sessionId"),Index(value = ["openSlot"],unique = true),Index(value = ["position","id"]),Index("selectionId")])
data class SegmentRow(@PrimaryKey val id: String,val position: Int,val sessionId: String,val openSlot: Int?,val start: Long,val end: Long?,val durationMs: Long,
    val selectionId: String,val anchor: String?,val uncertain: Boolean,val observedUntil: Long?,val limitMs: Long?,val manualConfirmation: String?)
@Entity(tableName = "favorites") data class FavoriteRow(@PrimaryKey val id: String,val position: Int,val payload: String)
@Entity(tableName = "measurements") data class MeasurementRow(@PrimaryKey val id: String,val position: Int,val payload: String)
@Entity(tableName = "store_generation") data class GenerationRow(@PrimaryKey val id: Int = 1,val generation: Long)
@Entity(tableName = "vocabulary") data class VocabularyRow(@PrimaryKey val id: Int = 1,val payload: String)
@Dao interface TrackerDao {
    @Query("SELECT * FROM history_classifications") fun classifications(): List<ClassificationRow>
    @Query("SELECT * FROM history_classifications WHERE id=:id") fun classification(id: String): ClassificationRow?
    @Insert fun classification(row: ClassificationRow)
    @Query("SELECT generation FROM store_generation WHERE id=1") fun generation(): Long?
    @Query("SELECT generation FROM store_generation WHERE id=1") fun generations(): Flow<Long?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun generation(row: GenerationRow)
    @Query("SELECT * FROM vocabulary WHERE id=1") fun vocabulary(): VocabularyRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun vocabulary(row: VocabularyRow)
    @Query("SELECT * FROM metadata WHERE id=1") fun metadata(): MetaRow?
    @Query("SELECT EXISTS(SELECT 1 FROM metadata WHERE id=1)") fun hasMetadata(): Boolean
    @Query("SELECT * FROM activity_types ORDER BY position") fun types(): List<TypeRow>
    @Query("SELECT * FROM cards ORDER BY position") fun cards(): List<CardRow>
    @Query("SELECT * FROM sessions ORDER BY position") fun sessions(): List<SessionRow>
    @Query("SELECT * FROM segments ORDER BY position") fun segments(): List<SegmentRow>
    @Query("SELECT * FROM favorites ORDER BY position") fun favorites(): List<FavoriteRow>
    @Query("SELECT * FROM measurements ORDER BY position") fun measurements(): List<MeasurementRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun metadata(row: MetaRow)
    @Upsert fun types(rows: List<TypeRow>)
    @Query("DELETE FROM activity_types WHERE id IN (:ids)") fun deleteTypes(ids: List<String>)
    @Query("SELECT * FROM activity_types WHERE id IN (:ids)") fun readTypes(ids: List<String>): List<TypeRow>
    @Upsert fun cards(rows: List<CardRow>)
    @Query("DELETE FROM cards WHERE id IN (:ids)") fun deleteCards(ids: List<String>)
    @Query("SELECT * FROM cards WHERE id IN (:ids)") fun readCards(ids: List<String>): List<CardRow>
    @Upsert fun sessions(rows: List<SessionRow>)
    @Query("DELETE FROM sessions WHERE id IN (:ids)") fun deleteSessions(ids: List<String>)
    @Query("SELECT * FROM sessions WHERE id IN (:ids)") fun readSessions(ids: List<String>): List<SessionRow>
    @Upsert fun segments(rows: List<SegmentRow>)
    @Query("DELETE FROM segments WHERE id IN (:ids)") fun deleteSegments(ids: List<String>)
    @Query("SELECT * FROM segments WHERE id IN (:ids)") fun readSegments(ids: List<String>): List<SegmentRow>
    @Upsert fun favorites(rows: List<FavoriteRow>)
    @Query("DELETE FROM favorites WHERE id IN (:ids)") fun deleteFavorites(ids: List<String>)
    @Query("SELECT * FROM favorites WHERE id IN (:ids)") fun readFavorites(ids: List<String>): List<FavoriteRow>
    @Upsert fun measurements(rows: List<MeasurementRow>)
    @Query("DELETE FROM measurements WHERE id IN (:ids)") fun deleteMeasurements(ids: List<String>)
    @Query("SELECT * FROM measurements WHERE id IN (:ids)") fun readMeasurements(ids: List<String>): List<MeasurementRow>
}
const val TRACKER_DATABASE_VERSION = 12
@Database(entities = [TypeRow::class,MetaRow::class,CardRow::class,SessionRow::class,SegmentRow::class,FavoriteRow::class,MeasurementRow::class,GenerationRow::class,VocabularyRow::class,ClassificationRow::class],version = TRACKER_DATABASE_VERSION,exportSchema = true)
abstract class TrackerDatabase: RoomDatabase() {
    abstract fun dao(): TrackerDao
    companion object {
        val MIGRATION_1_2 = object: androidx.room.migration.Migration(1,2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Room wraps the migration in a transaction. No row deletion, no guessed type mapping.
                val tables = listOf("metadata","activity_types","cards","sessions","segments","favorites","measurements")
                val migrated = tables.associateWith { table ->
                    val rows = mutableListOf<Pair<String,String>>()
                    db.query("SELECT id,payload FROM $table" + if(table == "metadata") "" else " ORDER BY position").use { cursor ->
                        while (cursor.moveToNext()) rows += cursor.getString(0) to migrateV1Payload(cursor.getString(1))
                    }
                    rows
                }
                if (migrated.getValue("metadata").isNotEmpty()) {
                    val graph = codec.parseToJsonElement(migrated.getValue("metadata").single().second).jsonObject.toMutableMap()
                    for(table in tables.drop(1)) graph[if(table == "activity_types") "types" else table] =
                        JsonArray(migrated.getValue(table).map { codec.parseToJsonElement(it.second) })
                    validate(codec.decodeFromJsonElement<Dataset>(JsonObject(graph)))
                }
                for ((table,rows) in migrated) for ((id,payload) in rows)
                    db.execSQL("UPDATE $table SET payload=? WHERE id=?",arrayOf(payload,id))
            }
        }

        val MIGRATION_2_3 = object: androidx.room.migration.Migration(2,3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS store_generation (id INTEGER NOT NULL, generation INTEGER NOT NULL, PRIMARY KEY(id))")
                db.execSQL("CREATE TABLE IF NOT EXISTS vocabulary (id INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(id))")
                db.query("SELECT payload FROM metadata WHERE id=1").use { c -> if(c.moveToFirst()) {
                    val d = codec.decodeFromString<Dataset>(c.getString(0))
                    db.execSQL("INSERT INTO vocabulary(id,payload) VALUES(1,?)",arrayOf(codec.encodeToString(VocabularyData(emptyList(),d.axes,d.metrics,d.units))))
                    db.execSQL("UPDATE metadata SET payload=? WHERE id=1",arrayOf(codec.encodeToString(d.copy(axes = emptyList(),metrics = emptyList(),units = emptyList()))))
                    db.execSQL("INSERT INTO store_generation(id,generation) VALUES(1,0)")
                } }
            }
        }

        val MIGRATION_3_4 = object: androidx.room.migration.Migration(3,4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_position_id ON sessions(position,id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_segments_position_id ON segments(position,id)")
            }
        }
        val MIGRATION_4_5 = object: androidx.room.migration.Migration(4,5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) = migrateNormalizedHistory(db)
        }

        // Domain additions live in the existing transactional metadata payload. No history rows change.
        val MIGRATION_5_6 = object: androidx.room.migration.Migration(5,6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                readMetadataPayload(db)?.let { payload ->
                    val old = codec.parseToJsonElement(payload).jsonObject
                    ensure(old["schedule"] == null || codec.decodeFromJsonElement<ScheduleData>(old.getValue("schedule")) == ScheduleData(),"Unexpected schedule in v5 metadata",ErrorCode.STORAGE)
                    ensure(old["transfer"] == null || codec.decodeFromJsonElement<TransferState>(old.getValue("transfer")) == TransferState(),"Unexpected transfer in v5 metadata",ErrorCode.STORAGE)
                    val next = JsonObject(old + mapOf("schedule" to codec.encodeToJsonElement(ScheduleData()),"transfer" to codec.encodeToJsonElement(TransferState())))
                    db.execSQL("UPDATE metadata SET payload=? WHERE id=1",arrayOf(next.toString()))
                }
            }
        }

        // Add receipt/manifest defaults only. No payload is pruned during migration.
        val MIGRATION_6_7 = object: androidx.room.migration.Migration(6,7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                readMetadataPayload(db)?.let { payload ->
                    val old = codec.parseToJsonElement(payload).jsonObject
                    val prior = old["transfer"]?.let { codec.decodeFromJsonElement<TransferState>(it) } ?: TransferState()
                    ensure(prior.compactions.isEmpty() && prior.origins.none { it.compaction != null } && prior.operations.none { it.payloadsPruned || it.groups.any { g -> g.previousOrigin?.compaction != null } },"Compaction state in v6 metadata",ErrorCode.STORAGE)
                    val next = JsonObject(old + ("transfer" to codec.encodeToJsonElement(prior)))
                    db.execSQL("UPDATE metadata SET payload=? WHERE id=1",arrayOf(next.toString()))
                }
            }
        }

        val MIGRATION_7_8 = object: androidx.room.migration.Migration(7,8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                readMetadataPayload(db)?.let { payload ->
                    val old = codec.parseToJsonElement(payload).jsonObject
                    val settings = codec.decodeFromJsonElement<Settings>(old["settings"] ?: fail(ErrorCode.STORAGE,"Missing legacy settings"))
                    val transfer = codec.decodeFromJsonElement<TransferState>(old["transfer"] ?: fail(ErrorCode.STORAGE,"Missing legacy transfer state"))
                    ensure(settings.schedule == ScheduleSettings() && !transfer.hasCatalogRoots(),"Functional additions in v7 metadata",ErrorCode.STORAGE)
                    // Only metadata defaults; facts, IDs, active anchors and journals are preserved.
                    val next = JsonObject(old + ("settings" to codec.encodeToJsonElement(settings)))
                    db.execSQL("UPDATE metadata SET payload=? WHERE id=1",arrayOf(next.toString()))
                }
            }
        }

        val MIGRATION_8_9 = object: androidx.room.migration.Migration(8,9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                readMetadataPayload(db)?.let { payload ->
                    val old = codec.parseToJsonElement(payload).jsonObject
                    val settings = codec.decodeFromJsonElement<Settings>(old["settings"] ?: fail(ErrorCode.STORAGE,"Missing legacy settings"))
                    ensure(settings.surfaces.shortcut,"Shortcut preference in v8 metadata",ErrorCode.STORAGE)
                    // Prior releases always enabled shortcuts. Add that explicit default only;
                    // active anchors, uncertainty, timer tokens and all facts/journals stay exact.
                    val next = JsonObject(old + ("settings" to codec.encodeToJsonElement(settings)))
                    db.execSQL("UPDATE metadata SET payload=? WHERE id=1",arrayOf(next.toString()))
                }
            }
        }

        val MIGRATION_9_10 = object: androidx.room.migration.Migration(9,10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                readMetadataPayload(db)?.let { payload ->
                    ensure(!codec.decodeFromString<Dataset>(payload).hasManualTimeConfirmation(),
                        "Manual confirmation in legacy metadata",ErrorCode.STORAGE)
                }
                // Null preserves the old provenance as unknown; no history or anchor is rewritten.
                db.execSQL("ALTER TABLE segments ADD COLUMN manualConfirmation TEXT")
            }
        }

        val MIGRATION_10_11 = object: androidx.room.migration.Migration(10,11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                readMetadataPayload(db)?.let { payload ->
                    val old = codec.parseToJsonElement(payload).jsonObject
                    val settings = codec.decodeFromJsonElement<Settings>(old["settings"] ?: fail(ErrorCode.STORAGE,"Missing legacy settings"))
                    ensure(settings.longRecordReminder == LongRecordPreference(),"Ordinary reminder preference in v10 metadata",ErrorCode.STORAGE)
                    // Preserve schema10 manual confirmation, anchors and every history row.
                    // Only the portable preference default is new; local activation is absent.
                    val next = JsonObject(old + ("settings" to codec.encodeToJsonElement(settings)))
                    db.execSQL("UPDATE metadata SET payload=? WHERE id=1",arrayOf(next.toString()))
                }
            }
        }

        val MIGRATION_11_12 = object: androidx.room.migration.Migration(11,12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                readMetadataPayload(db)?.let { payload ->
                    val prior = codec.decodeFromString<Dataset>(payload)
                    ensure(!prior.hasChoicePersonalization(),"Personal choices in v11 metadata",ErrorCode.STORAGE)
                }
                for(table in listOf("cards","favorites")) db.query("SELECT payload FROM $table").use { rows ->
                    while(rows.moveToNext()) {
                        val json = codec.parseToJsonElement(rows.getString(0)).jsonObject
                        val profile = json["personal"]?.let { codec.decodeFromJsonElement<ChoicePersonalization>(it) } ?: ChoicePersonalization()
                        ensure(profile == ChoicePersonalization(),"Personal choices in v11 rows",ErrorCode.STORAGE)
                    }
                }
                // Defaults decode losslessly; no facts, anchors, IDs or rows are rewritten.
            }
        }

        fun open(context: Context,name: String = "tracker.db"): TrackerDatabase {
            val file = context.getDatabasePath(name)
            val existing = DatabaseFiles.paths(context,name).filter { it.exists() }
            if(!file.exists() && existing.isNotEmpty()) {
                DatabaseArchives.capture(context,name)
                throw CoreError(ErrorCode.STORAGE,"Database main file is missing; sidecars preserved. Use explicit recovery")
            }
            if(file.exists()) {
                val expected = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
                val header = ByteArray(100)
                val validHeader = file.inputStream().use { input -> var n = 0
                    while(n < header.size) { val count = input.read(header,n,header.size-n);if(count < 0) break;n += count }
                    n == header.size && header.copyOfRange(0,expected.size).contentEquals(expected)
                }
                // SQLite may discard invalid sidecars before its corruption callback. Preserve
                // them before opening. Clean closed stores without sidecars avoid this copy.
                val storedVersion = if(validHeader) java.nio.ByteBuffer.wrap(header,60,4).int else null
                if(!validHeader || storedVersion != TRACKER_DATABASE_VERSION || existing.any { it != file })
                    DatabaseArchives.capture(context,name)
                ensure(validHeader,"Database header is damaged; originals preserved. Use explicit recovery",ErrorCode.STORAGE)
            }
            return Room.databaseBuilder(context,TrackerDatabase::class.java,name)
            .addMigrations(MIGRATION_1_2,MIGRATION_2_3,MIGRATION_3_4,MIGRATION_4_5,MIGRATION_5_6,MIGRATION_6_7,MIGRATION_7_8,MIGRATION_8_9,MIGRATION_9_10,MIGRATION_10_11,MIGRATION_11_12).openHelperFactory { configuration ->
                val original = configuration.callback
                val guarded = object: androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(original.version) {
                    override fun onConfigure(db: androidx.sqlite.db.SupportSQLiteDatabase) = original.onConfigure(db)
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = original.onCreate(db)
                    override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase,old: Int,new: Int) = original.onUpgrade(db,old,new)
                    override fun onDowngrade(db: androidx.sqlite.db.SupportSQLiteDatabase,old: Int,new: Int) = original.onDowngrade(db,old,new)
                    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) = original.onOpen(db)
                    override fun onCorruption(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        try { DatabaseArchives.capture(context,name) }
                        catch(_: Exception) { android.util.Log.e("Recovery","Original database remains in place; archival copy failed") }
                        // Do not call the default callback: it deletes corrupted database files.
                    }
                }
                androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
                    androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(configuration.context).name(configuration.name)
                        .callback(guarded).noBackupDirectory(configuration.useNoBackupDirectory).allowDataLossOnRecovery(false).build())
            }.build()
        }
    }
}
/** The committed generation invalidates the local graph cache across store instances.
 * Commands update only changed rows. No REPLACE on sessions (which would cascade-delete segments).
 * Every changed/deleted row is read back in the transaction; a failed commit discards the cache.
 */
class RoomStore(private val db: TrackerDatabase,private val initial: ()->Dataset = { Dataset() },private val allowInitialize: Boolean = true,
    private val onLoadPhase: ((String,Long)->Unit)? = null,private val loadPageSize: Int? = 512): StateStore {
    @Volatile private var retired = false
    internal fun retire() { retired = true }
    private fun active() { if(retired) throw CoreError(ErrorCode.STORAGE,"Store was closed for explicit recovery") }
    private val dao = db.dao()
    @Volatile private var cached: Pair<Long,CheckedState>? = null // accessed only inside Room transactions
    init { db.runInTransaction {
        if(!dao.hasMetadata()) {
            val tables = listOf("metadata","activity_types","cards","sessions","segments","favorites","measurements","vocabulary","store_generation","history_classifications")
            val remnants = tables.any { table -> db.openHelper.readableDatabase.query("SELECT 1 FROM $table LIMIT 1").use { it.moveToFirst() } }
            if(remnants || !allowInitialize) throw CoreError(ErrorCode.STORAGE,"Metadata is missing from an existing store; use explicit recovery. Nothing was initialized.")
            write(null,CheckedState.validate(initial()))
        } else if(dao.vocabulary() == null || dao.generation() == null) {
            throw CoreError(ErrorCode.STORAGE,"Store metadata is incomplete; use explicit recovery. Nothing was changed.")
        }
    } }
    override val checkedChanges: Flow<CheckedState> = dao.generations().distinctUntilChanged().map { readChecked() }.flowOn(Dispatchers.IO)
    override val changes: Flow<Dataset> = checkedChanges.map { it.data }
    override fun readChecked(): CheckedState { active();return db.runInTransaction(Callable { active();load() }) }
    override fun read(): Dataset = readChecked().data
    override fun <T> transaction(block: (Dataset)->Pair<Dataset,T>): T = checkedTransaction { before ->
        val (next,result) = block(before.data)
        before.replace(next) { result }
    }
    override fun <T> checkedTransaction(block: (CheckedState)->CheckedCommit<T>): T = try {
        active()
        db.runInTransaction(Callable {
            active();val before = load(); val commit = block(before); commit.requireBase(before);active()
            if(before !== commit.state) write(before.data,commit.state,commit.timerRows)
            commit.result
        })
    } catch(e: Exception) { cached = null;throw e }
    private fun load(): CheckedState {
        for(table in listOf("metadata","vocabulary","store_generation")) {
            db.openHelper.readableDatabase.query("SELECT COUNT(*),MIN(id),MAX(id) FROM $table").use { c ->
                ensure(c.moveToFirst() && c.getLong(0) == 1L && c.getInt(1) == 1 && c.getInt(2) == 1,
                    "Store metadata is incomplete; use explicit recovery",ErrorCode.STORAGE)
            }
        }
        val generation = dao.generation() ?: throw CoreError(ErrorCode.STORAGE,"Missing store generation")
        ensure(generation >= 0,"Invalid store generation",ErrorCode.STORAGE)
        cached?.takeIf { it.first == generation }?.let { return it.second }
        val meta = loadPhase("sql-metadata") { readMetadataPayload(db.openHelper.readableDatabase) ?: fail(ErrorCode.STORAGE,"Missing metadata") }
        val base = loadPhase("decode-metadata") { codec.decodeFromString<Dataset>(meta) }
        val rawVocabulary = loadPhase("sql-vocabulary") { dao.vocabulary()!!.payload }
        val vocabulary = loadPhase("decode-vocabulary") { codec.decodeFromString<VocabularyData>(rawVocabulary) }
        val rawSelections = loadPhase("sql-history-classifications") { dao.classifications() }
        val selections = loadPhase("decode-history-classifications") { rawSelections.associate {
            ensure(it.id == HistoryColumns.key(it.payload),"Historical classification content hash differs; use explicit recovery",ErrorCode.STORAGE)
            it.id to codec.decodeFromString<Classification>(it.payload)
        } }
        val data = base.copy(axes = vocabulary.axes,metrics = vocabulary.metrics,units = vocabulary.units,
            types = payloads("activity_types") { codec.decodeFromString<ActivityType>(it) },cards = payloads("cards") { codec.decodeFromString<Card>(it) },
            sessions = historyRows("sessions",HistoryColumns.SESSION_COLUMNS) { HistoryColumns.session(it,selections) },
            segments = historyRows("segments",HistoryColumns.SEGMENT_COLUMNS) { HistoryColumns.segment(it,selections) },
            favorites = payloads("favorites") { codec.decodeFromString<Favorite>(it) },
            measurements = payloads("measurements") { codec.decodeFromString<Measurement>(it) })
        val checked = CheckedState.validate(data,onLoadPhase)
        cached = generation to checked
        return checked
    }
    private fun <T> loadPhase(name: String,read: ()->T): T {
        if(onLoadPhase == null) return read()
        val start = System.nanoTime();val value = read()
        onLoadPhase.invoke(name,System.nanoTime()-start);return value
    }
    /** One cursor window / one payload at a time; never retain a second full list of JSON rows. */
    private fun <T> payloads(table: String,decode: (String)->T): List<T> {
        check(table in setOf("activity_types","cards","favorites","measurements"))
        val result = ArrayList<T>()
        fun checked(cursor: android.database.Cursor): T {
            val value = decode(cursor.getString(2))
            val id = when(value) { is ActivityType -> value.id;is Card -> value.id;is Favorite -> value.id;is Measurement -> value.id;else -> error("Unsupported payload table") }
            ensure(id == cursor.getString(0),"Stored row ID contradicts its payload; use explicit recovery",ErrorCode.STORAGE)
            ensure(cursor.getLong(1) in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong(),"Stored position out of range",ErrorCode.STORAGE)
            return value
        }
        val start = if(onLoadPhase != null) System.nanoTime() else 0
        db.openHelper.readableDatabase.query("SELECT id,position,payload FROM $table ORDER BY position,id").use { cursor ->
            if(onLoadPhase == null) while(cursor.moveToNext()) result += checked(cursor)
            else {
                var sqlNs = System.nanoTime()-start;var decodeNs = 0L
                while(true) {
                    var tick = System.nanoTime();val next = cursor.moveToNext()
                    sqlNs += System.nanoTime()-tick
                    if(!next) break
                    tick = System.nanoTime();result += checked(cursor);decodeNs += System.nanoTime()-tick
                }
                onLoadPhase.invoke("sql-$table",sqlNs);onLoadPhase.invoke("decode-$table",decodeNs)
            }
        }
        return result
    }
    private fun <T> historyRows(table: String,columns: String,decode: (android.database.Cursor)->T): List<T> {
        check(table in setOf("sessions","segments"))
        val size = loadPageSize;require(size == null || size in 1..1024)
        val result = ArrayList<T>();var position: Long? = null;var id: String? = null
        var sqlNs = 0L;var decodeNs = 0L
        do {
            val tick = if(onLoadPhase != null) System.nanoTime() else 0L
            val query = if(position == null) "SELECT $columns FROM $table ORDER BY position,id"+(size?.let { " LIMIT $it" } ?: "") else
                "SELECT $columns FROM $table WHERE position>=? AND (position>? OR id>?) ORDER BY position,id LIMIT $size"
            val args = if(position == null) emptyArray() else arrayOf<Any>(position!!,position!!,id!!)
            var count = 0
            db.openHelper.readableDatabase.query(query,args).use { cursor ->
                var previous = if(onLoadPhase != null) System.nanoTime() else 0L
                if(onLoadPhase != null) sqlNs += previous-tick
                while(cursor.moveToNext()) {
                    position = cursor.getLong(1);id = cursor.getString(0);count++
                    val beforeDecode = if(onLoadPhase != null) System.nanoTime() else 0L
                    if(onLoadPhase != null) sqlNs += beforeDecode-previous
                    result += decode(cursor)
                    previous = if(onLoadPhase != null) System.nanoTime() else 0L
                    if(onLoadPhase != null) decodeNs += previous-beforeDecode
                }
                if(onLoadPhase != null) sqlNs += System.nanoTime()-previous
            }
        } while(size != null && count == size)
        onLoadPhase?.invoke("sql-$table",sqlNs);onLoadPhase?.invoke("decode-$table",decodeNs)
        return result
    }
    private fun <T,R> sync(old: List<T>,next: List<T>,id: (T)->String,row: (Int,T)->R,
        put: (List<R>)->Unit,delete: (List<String>)->Unit,read: (List<String>)->List<R>) {
        if(old === next) return
        val previous = old.mapIndexed { i,v -> id(v) to (i to v) }.toMap()
        val ids = next.map(id).toSet()
        val removed = previous.keys.filter { it !in ids }
        val changed = next.mapIndexedNotNull { i,v -> if(previous[id(v)] == (i to v)) null else id(v) to row(i,v) }
        removed.chunked(500).forEach(delete)
        if(changed.isNotEmpty()) put(changed.map { it.second })
        // Chunk queries below SQLite's portable bind limit. Verify deletions as well as writes.
        val expected = changed.toMap()
        (removed+changed.map { it.first }).chunked(500).forEach { keys ->
            check(read(keys).toSet() == keys.mapNotNull { expected[it] }.toSet()) { "Persistence verification failed" }
        }
    }
    private fun write(old: Dataset?,state: CheckedState,timer: TimerRows? = null) {
        val d = state.data
        val definitions = mutableMapOf<Classification,String>()
        fun selection(c: Classification): String = definitions.getOrPut(c) {
            val row = codec.encodeToString(c).let { ClassificationRow(HistoryColumns.key(it),it) }
            val existing = dao.classification(row.id)
            if(existing == null) dao.classification(row)
            check(dao.classification(row.id) == row) { "Historical classification verification failed" }
            row.id
        }
        if(timer != null) {
            val sessions = timer.sessions.map { HistoryColumns.session(it.value,it.position,selection(it.value.selection)) }
            val segments = timer.segments.map { HistoryColumns.segment(it.value,it.position,selection(it.value.selection)) }
            dao.sessions(sessions)
            dao.segments(segments.filter { it.openSlot == null }); dao.segments(segments.filter { it.openSlot != null })
            check(dao.readSessions(sessions.map { it.id }).toSet() == sessions.toSet()) { "Timer session verification failed" }
            check(dao.readSegments(segments.map { it.id }).toSet() == segments.toSet()) { "Timer segment verification failed" }
        } else {
        // Session removals wait until child segments have been removed/reparented.
        val removeSessions = mutableListOf<String>()
        sync(old?.sessions.orEmpty(),d.sessions,{ it.id },{ i,s -> HistoryColumns.session(s,i,selection(s.selection)) },
            dao::sessions,{ removeSessions.addAll(it) },{ ids -> dao.readSessions(ids).filterNot { it.id in removeSessions } })
        sync(old?.segments.orEmpty(),d.segments,{ it.id },{ i,s -> HistoryColumns.segment(s,i,selection(s.selection)) },
            { rows -> dao.segments(rows.filter { it.openSlot == null }); dao.segments(rows.filter { it.openSlot != null }) },dao::deleteSegments,dao::readSegments)
        removeSessions.chunked(500).forEach { dao.deleteSessions(it); check(dao.readSessions(it).isEmpty()) }
        sync(old?.types.orEmpty(),d.types,{ it.id },{ i,t -> TypeRow(t.id,i,codec.encodeToString(t)) },dao::types,dao::deleteTypes,dao::readTypes)
        sync(old?.cards.orEmpty(),d.cards,{ it.id },{ i,t -> CardRow(t.id,i,codec.encodeToString(t)) },dao::cards,dao::deleteCards,dao::readCards)
        sync(old?.favorites.orEmpty(),d.favorites,{ it.id },{ i,t -> FavoriteRow(t.id,i,codec.encodeToString(t)) },dao::favorites,dao::deleteFavorites,dao::readFavorites)
        sync(old?.measurements.orEmpty(),d.measurements,{ it.id },{ i,t -> MeasurementRow(t.id,i,codec.encodeToString(t)) },dao::measurements,dao::deleteMeasurements,dao::readMeasurements)
        if(old == null || old.axes != d.axes || old.metrics != d.metrics || old.units != d.units) {
            val row = VocabularyRow(payload = codec.encodeToString(VocabularyData(emptyList(),d.axes,d.metrics,d.units)))
            dao.vocabulary(row); check(dao.vocabulary() == row) { "Vocabulary verification failed" }
        }
        }
        val meta = MetaRow(payload = codec.encodeToString(d.copy(types = emptyList(),cards = emptyList(),sessions = emptyList(),segments = emptyList(),favorites = emptyList(),measurements = emptyList(),axes = emptyList(),metrics = emptyList(),units = emptyList())))
        dao.metadata(meta); check(readMetadataPayload(db.openHelper.readableDatabase) == meta.payload) { "Metadata verification failed" }
        val generation = Math.addExact(dao.generation() ?: 0,1)
        dao.generation(GenerationRow(generation = generation))
        check(dao.generation() == generation) { "Generation verification failed" }
        cached = generation to state
    }
}

/** Transfer journals can exceed a single CursorWindow row. Read fixed-size SQL text slices
 * inside the caller's transaction; SQLite substr offsets count Unicode code points, not UTF-16.
 * Whole-state decoding remains explicit; this does not claim bounded total domain memory.
 */
internal fun readMetadataPayload(db: androidx.sqlite.db.SupportSQLiteDatabase): String? {
    val length = db.query("SELECT length(payload) FROM metadata WHERE id=1").use { c ->
        if(!c.moveToFirst()) return null
        ensure(!c.isNull(0),"Null metadata payload",ErrorCode.STORAGE);c.getLong(0)
    }
    ensure(length in 0..Int.MAX_VALUE.toLong(),"Metadata text exceeds supported memory range",ErrorCode.STORAGE)
    val text = StringBuilder();var offset = 1L
    while(offset <= length) {
        val chunk = db.query("SELECT substr(payload,?,131072) FROM metadata WHERE id=1",arrayOf<Any>(offset)).use { c ->
            ensure(c.moveToFirst() && !c.isNull(0),"Metadata disappeared during read",ErrorCode.STORAGE);c.getString(0)
        }
        ensure(chunk.isNotEmpty(),"Metadata slice is missing",ErrorCode.STORAGE);text.append(chunk);offset += 131072
    }
    return text.toString()
}
