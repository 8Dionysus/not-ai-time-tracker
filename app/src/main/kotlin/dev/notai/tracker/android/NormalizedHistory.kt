package dev.notai.tracker.android

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.notai.tracker.*
import java.security.MessageDigest

/** Owner storage columns, not a cache or a caller-supplied validation receipt. */
internal object HistoryColumns {
    const val SESSION_COLUMNS = "id,position,status,selectionId,note,importOrigin"
    const val SEGMENT_COLUMNS = "id,position,sessionId,openSlot,start,end,durationMs,selectionId,anchor,uncertain,observedUntil,limitMs"
    fun key(payload: String): String = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    fun session(value: Session,position: Int,selectionId: String) = SessionRow(value.id,position,value.status.name,selectionId,value.note,
        value.importOrigin?.let { codec.encodeToString(it) })
    fun segment(value: Segment,position: Int,selectionId: String) = SegmentRow(value.id,position,value.sessionId,if(value.end == null) 1 else null,
        value.start,value.end,value.durationMs,selectionId,value.anchor?.let { codec.encodeToString(it) },value.uncertain,value.observedUntil,value.limitMs)
    private fun Cursor.text(i: Int): String? = if(isNull(i)) null else getString(i)
    private fun Cursor.number(i: Int): Long? = if(isNull(i)) null else getLong(i)
    private fun selection(id: String,all: Map<String,Classification>): Classification =
        all[id] ?: throw CoreError(ErrorCode.STORAGE,"Missing historical classification; use explicit recovery")
    fun session(c: Cursor,all: Map<String,Classification>) = Session(c.getString(0),SessionStatus.valueOf(c.getString(2)),selection(c.getString(3),all),
        c.text(4),c.text(5)?.let { codec.decodeFromString<ImportOrigin>(it) })
    fun segment(c: Cursor,all: Map<String,Classification>): Segment {
        val end = c.number(5)
        ensure(c.number(3) == if(end == null) 1L else null,"Open-slot metadata contradicts the segment",ErrorCode.STORAGE)
        val uncertain = c.getInt(9);ensure(uncertain in 0..1,"Invalid stored uncertainty flag",ErrorCode.STORAGE)
        return Segment(c.getString(0),c.getString(2),c.getLong(4),end,c.getLong(6),selection(c.getString(7),all),
            c.text(8)?.let { codec.decodeFromString<Anchor>(it) },uncertain == 1,c.number(10),c.number(11))
    }
}

/** Room supplies the enclosing migration transaction. Verify the old graph before
 * changing a table; re-read the new representation and compare every domain value. */
internal fun migrateNormalizedHistory(db: SupportSQLiteDatabase) {
    for(table in listOf("metadata","vocabulary","store_generation")) db.query("SELECT COUNT(*),MIN(id),MAX(id) FROM $table").use { c ->
        ensure(c.moveToFirst() && c.getLong(0) == 1L && c.getInt(1) == 1 && c.getInt(2) == 1,"Incomplete legacy store",ErrorCode.STORAGE)
    }
    val base = legacyOne<Dataset>(db,"metadata");val vocabulary = legacyOne<VocabularyData>(db,"vocabulary")
    val selectionPool = mutableMapOf<Classification,Classification>()
    val sessions = legacyRows<Session>(db,"sessions",selectionPool);val segments = legacyRows<Segment>(db,"segments",selectionPool)
    val data = base.copy(types = legacyRows<ActivityType>(db,"activity_types").map { it.second },cards = legacyRows<Card>(db,"cards").map { it.second },
        sessions = sessions.map { it.second },segments = segments.map { it.second },favorites = legacyRows<Favorite>(db,"favorites").map { it.second },
        measurements = legacyRows<Measurement>(db,"measurements").map { it.second },axes = vocabulary.axes,metrics = vocabulary.metrics,units = vocabulary.units)
    CheckedState.validate(data)
    db.execSQL("CREATE TABLE history_classifications (id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(id))")
    db.execSQL("CREATE TABLE sessions_normalized (id TEXT NOT NULL, position INTEGER NOT NULL, status TEXT NOT NULL, selectionId TEXT NOT NULL, note TEXT, importOrigin TEXT, PRIMARY KEY(id), FOREIGN KEY(selectionId) REFERENCES history_classifications(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
    val definitions = linkedMapOf<Classification,String>()
    fun selected(c: Classification): String = definitions.getOrPut(c) {
        val payload = codec.encodeToString(c);val key = HistoryColumns.key(payload)
        db.execSQL("INSERT INTO history_classifications(id,payload) VALUES(?,?)",arrayOf(key,payload));key
    }
    for((position,s) in sessions) {
        val row = HistoryColumns.session(s,position,selected(s.selection))
        db.execSQL("INSERT INTO sessions_normalized(${HistoryColumns.SESSION_COLUMNS}) VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>(row.id,row.position,row.status,row.selectionId,row.note,row.importOrigin))
    }
    // Create the child only after the parent has its final name. Older supported
    // SQLite versions do not rewrite FK references when renaming a parent.
    db.execSQL("DROP TABLE segments");db.execSQL("DROP TABLE sessions")
    db.execSQL("ALTER TABLE sessions_normalized RENAME TO sessions")
    db.execSQL("CREATE TABLE segments (id TEXT NOT NULL, position INTEGER NOT NULL, sessionId TEXT NOT NULL, openSlot INTEGER, start INTEGER NOT NULL, end INTEGER, durationMs INTEGER NOT NULL, selectionId TEXT NOT NULL, anchor TEXT, uncertain INTEGER NOT NULL, observedUntil INTEGER, limitMs INTEGER, PRIMARY KEY(id), FOREIGN KEY(sessionId) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(selectionId) REFERENCES history_classifications(id) ON UPDATE NO ACTION ON DELETE NO ACTION)")
    for((position,s) in segments) {
        val row = HistoryColumns.segment(s,position,selected(s.selection))
        db.execSQL("INSERT INTO segments(${HistoryColumns.SEGMENT_COLUMNS}) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(row.id,row.position,row.sessionId,row.openSlot,row.start,row.end,row.durationMs,row.selectionId,row.anchor,if(row.uncertain) 1 else 0,row.observedUntil,row.limitMs))
    }
    val selections = db.query("SELECT id,payload FROM history_classifications").use { c -> buildMap { while(c.moveToNext()) put(c.getString(0),codec.decodeFromString<Classification>(c.getString(1))) } }
    val restored = data.copy(sessions = db.query("SELECT ${HistoryColumns.SESSION_COLUMNS} FROM sessions ORDER BY position,id").use { c -> buildList { while(c.moveToNext()) add(HistoryColumns.session(c,selections)) } },
        segments = db.query("SELECT ${HistoryColumns.SEGMENT_COLUMNS} FROM segments ORDER BY position,id").use { c -> buildList { while(c.moveToNext()) add(HistoryColumns.segment(c,selections)) } })
    ensure(data == restored,"Normalized migration readback differs",ErrorCode.STORAGE)
    CheckedState.validate(restored)
    db.execSQL("CREATE INDEX index_sessions_position_id ON sessions(position,id)");db.execSQL("CREATE INDEX index_sessions_selectionId ON sessions(selectionId)")
    db.execSQL("CREATE INDEX index_segments_position_id ON segments(position,id)");db.execSQL("CREATE INDEX index_segments_selectionId ON segments(selectionId)")
    db.execSQL("CREATE INDEX index_segments_sessionId ON segments(sessionId)");db.execSQL("CREATE UNIQUE INDEX index_segments_openSlot ON segments(openSlot)")
}

private inline fun <reified T> legacyOne(db: SupportSQLiteDatabase,table: String): T = db.query("SELECT payload FROM $table WHERE id=1").use { c -> check(c.moveToFirst());codec.decodeFromString<T>(c.getString(0)) }
private inline fun <reified T> legacyRows(db: SupportSQLiteDatabase,table: String,pool: MutableMap<Classification,Classification>? = null): List<Pair<Int,T>> =
    db.query("SELECT id,position,payload"+(if(table == "segments") ",sessionId,openSlot" else "")+" FROM $table ORDER BY position,id").use { c ->
        buildList { while(c.moveToNext()) {
            val value = codec.decodeFromString<T>(c.getString(2))
            val id = when(value) { is ActivityType -> value.id;is Card -> value.id;is Session -> value.id;is Segment -> value.id;is Favorite -> value.id;is Measurement -> value.id;else -> error("Unsupported legacy table") }
            ensure(id == c.getString(0),"Legacy row ID contradicts its payload",ErrorCode.STORAGE)
            ensure(c.getLong(1) in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong(),"Legacy position out of range",ErrorCode.STORAGE)
            if(value is Segment) ensure(value.sessionId == c.getString(3) && (if(c.isNull(4)) null else c.getLong(4)) == if(value.end == null) 1L else null,
                "Legacy segment columns contradict its payload",ErrorCode.STORAGE)
            val shared: Any? = if(pool == null) value else when(value) {
                is Session -> value.copy(selection = pool.getOrPut(value.selection) { value.selection })
                is Segment -> value.copy(selection = pool.getOrPut(value.selection) { value.selection })
                else -> value
            }
            @Suppress("UNCHECKED_CAST")
            add(c.getInt(1) to (shared as T))
        } }
    }
