package dev.notai.tracker.android

import android.content.Context
import androidx.room.*
import dev.notai.tracker.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
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
@Dao interface TrackerDao {
    @Query("SELECT * FROM metadata WHERE id=1") fun metadata(): MetaRow?
    @Query("SELECT * FROM metadata WHERE id=1") fun changes(): Flow<MetaRow?>
    @Query("SELECT * FROM activity_types ORDER BY position") fun types(): List<TypeRow>
    @Query("SELECT * FROM cards ORDER BY position") fun cards(): List<CardRow>
    @Query("SELECT * FROM sessions ORDER BY position") fun sessions(): List<SessionRow>
    @Query("SELECT * FROM segments ORDER BY position") fun segments(): List<SegmentRow>
    @Query("SELECT * FROM favorites ORDER BY position") fun favorites(): List<FavoriteRow>
    @Query("SELECT * FROM measurements ORDER BY position") fun measurements(): List<MeasurementRow>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun metadata(row: MetaRow)
    @Insert fun types(rows: List<TypeRow>)
    @Insert fun cards(rows: List<CardRow>)
    @Insert fun sessions(rows: List<SessionRow>)
    @Insert fun segments(rows: List<SegmentRow>)
    @Insert fun favorites(rows: List<FavoriteRow>)
    @Insert fun measurements(rows: List<MeasurementRow>)
    @Query("DELETE FROM segments") fun clearSegments()
    @Query("DELETE FROM sessions") fun clearSessions()
    @Query("DELETE FROM activity_types") fun clearTypes()
    @Query("DELETE FROM cards") fun clearCards()
    @Query("DELETE FROM favorites") fun clearFavorites()
    @Query("DELETE FROM measurements") fun clearMeasurements()
}
@Database(entities = [TypeRow::class,MetaRow::class,CardRow::class,SessionRow::class,SegmentRow::class,FavoriteRow::class,MeasurementRow::class],version = 2,exportSchema = true)
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

        fun open(context: Context,name: String = "tracker.db") = Room.databaseBuilder(context,TrackerDatabase::class.java,name).addMigrations(MIGRATION_1_2).build()
    }
}
/** A compact graph store: FK + unique open slot at SQLite level, full validation at the boundary.
 * Writes happen only for user commands, never for display ticks. Transaction readback checks persisted data.
 */
class RoomStore(private val db: TrackerDatabase): StateStore {
    private val dao = db.dao()
    init { db.runInTransaction { if (dao.metadata() == null) write(Dataset()) } }
    override val changes: Flow<Dataset> = dao.changes().map { read() }.flowOn(Dispatchers.IO)
    override fun read(): Dataset = db.runInTransaction(Callable { load() })
    override fun <T> transaction(block: (Dataset)->Pair<Dataset,T>): T = db.runInTransaction(Callable {
        val old = load(); val (next,result) = block(old)
        validate(next)
        if (old != next) {
            write(next)
            check(load() == next) { "Persistence verification failed" }
        }
        result
    })
    private fun load(): Dataset {
        val base = codec.decodeFromString<Dataset>(dao.metadata()!!.payload)
        return base.copy(types = dao.types().map { codec.decodeFromString<ActivityType>(it.payload) },cards = dao.cards().map { codec.decodeFromString<Card>(it.payload) },
            sessions = dao.sessions().map { codec.decodeFromString<Session>(it.payload) },
            segments = dao.segments().map { codec.decodeFromString<Segment>(it.payload) },
            favorites = dao.favorites().map { codec.decodeFromString<Favorite>(it.payload) },
            measurements = dao.measurements().map { codec.decodeFromString<Measurement>(it.payload) })
    }
    private fun write(d: Dataset) {
        validate(d)
        dao.clearMeasurements(); dao.clearFavorites(); dao.clearSegments(); dao.clearSessions(); dao.clearCards(); dao.clearTypes()
        dao.types(d.types.mapIndexed { i,t -> TypeRow(t.id,i,codec.encodeToString(t)) })
        dao.cards(d.cards.mapIndexed { i,c -> CardRow(c.id,i,codec.encodeToString(c)) })
        dao.sessions(d.sessions.mapIndexed { i,s -> SessionRow(s.id,i,codec.encodeToString(s)) })
        dao.segments(d.segments.mapIndexed { i,s -> SegmentRow(s.id,i,s.sessionId,if (s.end == null) 1 else null,codec.encodeToString(s)) })
        dao.favorites(d.favorites.mapIndexed { i,f -> FavoriteRow(f.id,i,codec.encodeToString(f)) })
        dao.measurements(d.measurements.mapIndexed { i,m -> MeasurementRow(m.id,i,codec.encodeToString(m)) })
        dao.metadata(MetaRow(payload = codec.encodeToString(d.copy(types = emptyList(),cards = emptyList(),sessions = emptyList(),segments = emptyList(),favorites = emptyList(),measurements = emptyList()))))
    }
}
