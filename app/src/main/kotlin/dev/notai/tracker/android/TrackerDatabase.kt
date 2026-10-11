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
@Entity(tableName = "sessions") data class SessionRow(@PrimaryKey val id: String,val position: Int,val payload: String)
@Entity(tableName = "segments", foreignKeys = [ForeignKey(entity = SessionRow::class,parentColumns = ["id"],childColumns = ["sessionId"],onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId"),Index(value = ["openSlot"],unique = true)])
data class SegmentRow(@PrimaryKey val id: String,val position: Int,val sessionId: String,val openSlot: Int?,val payload: String)
@Entity(tableName = "favorites") data class FavoriteRow(@PrimaryKey val id: String,val position: Int,val payload: String)
@Entity(tableName = "measurements") data class MeasurementRow(@PrimaryKey val id: String,val position: Int,val payload: String)
@Entity(tableName = "store_generation") data class GenerationRow(@PrimaryKey val id: Int = 1,val generation: Long)
@Entity(tableName = "vocabulary") data class VocabularyRow(@PrimaryKey val id: Int = 1,val payload: String)
@Dao interface TrackerDao {
    @Query("SELECT generation FROM store_generation WHERE id=1") fun generation(): Long?
    @Query("SELECT generation FROM store_generation WHERE id=1") fun generations(): Flow<Long?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun generation(row: GenerationRow)
    @Query("SELECT * FROM vocabulary WHERE id=1") fun vocabulary(): VocabularyRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun vocabulary(row: VocabularyRow)
    @Query("SELECT * FROM metadata WHERE id=1") fun metadata(): MetaRow?
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
@Database(entities = [TypeRow::class,MetaRow::class,CardRow::class,SessionRow::class,SegmentRow::class,FavoriteRow::class,MeasurementRow::class,GenerationRow::class,VocabularyRow::class],version = 3,exportSchema = true)
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

        fun open(context: Context,name: String = "tracker.db") = Room.databaseBuilder(context,TrackerDatabase::class.java,name).addMigrations(MIGRATION_1_2,MIGRATION_2_3).build()
    }
}
/** The committed generation invalidates the local graph cache across store instances.
 * Commands update only changed rows. No REPLACE on sessions (which would cascade-delete segments).
 * Every changed/deleted row is read back in the transaction; a failed commit discards the cache.
 */
class RoomStore(private val db: TrackerDatabase): StateStore {
    private val dao = db.dao()
    private var cached: Pair<Long,CheckedState>? = null // accessed only inside Room transactions
    init { db.runInTransaction { if (dao.metadata() == null) write(null,CheckedState.validate(Dataset())) } }
    override val checkedChanges: Flow<CheckedState> = dao.generations().distinctUntilChanged().map { readChecked() }.flowOn(Dispatchers.IO)
    override val changes: Flow<Dataset> = checkedChanges.map { it.data }
    override fun readChecked(): CheckedState = db.runInTransaction(Callable { load() })
    override fun read(): Dataset = readChecked().data
    override fun <T> transaction(block: (Dataset)->Pair<Dataset,T>): T = checkedTransaction { before ->
        val (next,result) = block(before.data)
        before.replace(next) { result }
    }
    override fun <T> checkedTransaction(block: (CheckedState)->CheckedCommit<T>): T = try {
        db.runInTransaction(Callable {
            val before = load(); val commit = block(before); commit.requireBase(before)
            if(before !== commit.state) write(before.data,commit.state,commit.timerRows)
            commit.result
        })
    } catch(e: Exception) { db.runInTransaction { cached = null }; throw e }
    private fun load(): CheckedState {
        val generation = requireNotNull(dao.generation())
        cached?.takeIf { it.first == generation }?.let { return it.second }
        val base = codec.decodeFromString<Dataset>(dao.metadata()!!.payload)
        val vocabulary = codec.decodeFromString<VocabularyData>(dao.vocabulary()!!.payload)
        val data = base.copy(axes = vocabulary.axes,metrics = vocabulary.metrics,units = vocabulary.units,
            types = dao.types().map { codec.decodeFromString<ActivityType>(it.payload) },cards = dao.cards().map { codec.decodeFromString<Card>(it.payload) },
            sessions = dao.sessions().map { codec.decodeFromString<Session>(it.payload) },
            segments = dao.segments().map { codec.decodeFromString<Segment>(it.payload) },
            favorites = dao.favorites().map { codec.decodeFromString<Favorite>(it.payload) },
            measurements = dao.measurements().map { codec.decodeFromString<Measurement>(it.payload) })
        val checked = CheckedState.validate(data)
        cached = generation to checked
        return checked
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
        if(timer != null) {
            val sessions = timer.sessions.map { SessionRow(it.value.id,it.position,codec.encodeToString(it.value)) }
            val segments = timer.segments.map { SegmentRow(it.value.id,it.position,it.value.sessionId,if(it.value.end == null) 1 else null,codec.encodeToString(it.value)) }
            dao.sessions(sessions)
            dao.segments(segments.filter { it.openSlot == null }); dao.segments(segments.filter { it.openSlot != null })
            check(dao.readSessions(sessions.map { it.id }).toSet() == sessions.toSet()) { "Timer session verification failed" }
            check(dao.readSegments(segments.map { it.id }).toSet() == segments.toSet()) { "Timer segment verification failed" }
        } else {
        // Session removals wait until child segments have been removed/reparented.
        val removeSessions = mutableListOf<String>()
        sync(old?.sessions.orEmpty(),d.sessions,{ it.id },{ i,s -> SessionRow(s.id,i,codec.encodeToString(s)) },
            dao::sessions,{ removeSessions.addAll(it) },{ ids -> dao.readSessions(ids).filterNot { it.id in removeSessions } })
        sync(old?.segments.orEmpty(),d.segments,{ it.id },{ i,s -> SegmentRow(s.id,i,s.sessionId,if(s.end == null) 1 else null,codec.encodeToString(s)) },
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
        dao.metadata(meta); check(dao.metadata() == meta) { "Metadata verification failed" }
        val generation = Math.addExact(dao.generation() ?: 0,1)
        dao.generation(GenerationRow(generation = generation))
        check(dao.generation() == generation) { "Generation verification failed" }
        cached = generation to state
    }
}
