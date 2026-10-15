package dev.notai.tracker

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun validateScheduleTicket(t: ScheduleReminderTicket) {
    ensure(t.datasetId.isNotBlank() && t.key.seriesId.isNotBlank() && t.seriesRevision > 0 &&
        (t.overrideRevision == null || t.overrideRevision > 0),"Invalid reminder identity",ErrorCode.STORAGE)
    try { scheduleDate(t.key.originalDate);scheduleZone(t.zone);Instant.ofEpochMilli(t.at) }
    catch(_: Exception) { fail(ErrorCode.STORAGE,"Invalid reminder ticket") }
}
internal fun validateScheduleAttempt(a: ScheduleReminderAttempt) {
    validateScheduleTicket(a.ticket)
    ensure(a.attemptedAt in 0..MAX_TIME,"Invalid reminder receipt time",ErrorCode.STORAGE)
}
internal fun validateScheduleDeliveryState(s: ScheduleDeliveryState) {
    ensure(s.format == 1 && s.revision >= 0,"Invalid schedule delivery state",ErrorCode.STORAGE)
    ensure(s.attempts.map { it.ticket.identity }.distinct().size == s.attempts.size,"Duplicate reminder receipt",ErrorCode.STORAGE)
    s.attempts.forEach(::validateScheduleAttempt)
    s.pending?.let { b ->
        ensure(b.tickets.isNotEmpty() && b.tickets.all { it.at == b.at } && b.tickets.map { it.identity }.distinct().size == b.tickets.size,"Invalid alarm batch",ErrorCode.STORAGE)
        b.tickets.forEach(::validateScheduleTicket)
    }
}

/** Narrow SQL boundary, implemented by the existing Android SQLite stack. Transactions MUST
 * commit durably before returning; exceptions must roll back or report commit uncertainty.
 * No network, timer, domain Dataset writes or receipt eviction belongs to this boundary. */
interface ScheduleReceiptSql: AutoCloseable {
    fun execute(sql: String,args: List<Any?> = emptyList())
    fun query(sql: String,args: List<Any?> = emptyList()): List<List<String?>>
    fun <T> transaction(block: ()->T): T
}

@Serializable private data class ScheduleIndexMarker(val format: Int = 2,val databaseId: String,val legacySha256: String? = null)

/** Indexed operational sidecar. Every outer locked call opens/closes a connection; nested calls
 * reuse it. Instances for one root share [gate]. SQLite unique identities + transactions also
 * refuse a duplicate reserve independently of that process gate. No lifetime scan on sync/post.
 * The old path becomes a fail-closed v2 authority marker only AFTER a durable SQLite import.
 * The exact v1 text is retained separately, never deleted. A missing indexed DB is not re-created
 * when the marker names it. Snapshot read is explicit diagnostic compatibility only.
 */
class IndexedScheduleDeliveryStore(private val gate: Any,private val manifest: CopyMetadata,
    private val legacySource: CopyMetadata,private val open: (allowCreate: Boolean)->ScheduleReceiptSql): ScheduleDeliveryStore {
    private val controlSchema = "CREATE TABLE control (id INTEGER PRIMARY KEY CHECK(id=1), database_id TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision>=0), pending TEXT, source_hash TEXT)"
    private val attemptSchema = "CREATE TABLE attempts (sequence INTEGER PRIMARY KEY, identity TEXT NOT NULL UNIQUE, dataset_id TEXT NOT NULL, series_id TEXT NOT NULL, original_date TEXT NOT NULL, payload TEXT NOT NULL, UNIQUE(dataset_id,series_id,original_date))"
    private var connection: ScheduleReceiptSql? = null
    private val json = Json(codec) { prettyPrint = false }
    private val db get() = connection ?: error("Reminder index outside its gate")
    override fun <T> locked(block: ()->T): T = synchronized(gate) {
        if(connection != null) block() else {
            val text = manifest.read()
            val format = text?.let { json.parseToJsonElement(it).jsonObject["format"]?.jsonPrimitive?.content?.toInt() }
            ensure(text != null || legacySource.read() == null,"Reminder migration source is missing; retained source preserved",ErrorCode.STORAGE)
            ensure(text == null || format in 1..2,"Unknown reminder storage format",ErrorCode.STORAGE)
            open(format != 2).use { sql ->
                connection = sql
                try { initialize(text,format);block() } finally { connection = null }
            }
        }
    }
    private fun initialize(text: String?,format: Int?) {
        val schema = db.query("PRAGMA user_version").single().single()!!.toInt()
        ensure(schema in 0..1 && (format != 2 || schema == 1),"Unknown/missing reminder index schema",ErrorCode.STORAGE)
        if(schema == 0) db.transaction {
            ensure(db.query("SELECT name FROM sqlite_master WHERE type='table' AND name!='android_metadata'").isEmpty(),"Unrecognized reminder index",ErrorCode.STORAGE)
            db.execute(controlSchema)
            db.execute(attemptSchema)
            db.execute("PRAGMA user_version=1")
        }
        val schemaRows = db.query("SELECT name,sql FROM sqlite_master WHERE type='table' AND name IN ('control','attempts')")
        ensure(schemaRows.size == 2 && schemaRows.associate { it[0] to it[1] } == mapOf("control" to controlSchema,"attempts" to attemptSchema),
            "Reminder index schema differs",ErrorCode.STORAGE)
        val controls = db.query("SELECT database_id,source_hash FROM control WHERE id=1")
        ensure(controls.size <= 1,"Duplicate reminder control",ErrorCode.STORAGE)
        var row = controls.singleOrNull()
        if(row == null) {
            ensure(format != 2,"Reminder authority has no indexed control",ErrorCode.STORAGE)
            val legacy = text?.let(ScheduleDeliveryJson::decode) ?: ScheduleDeliveryState()
            validateScheduleDeliveryState(legacy)
            val sourceHash = text?.let { sha256(it.toByteArray(Charsets.UTF_8)) }
            if(text != null) {
                val previous = legacySource.read()
                ensure(previous == null || previous == text,"Changed reminder migration source",ErrorCode.STORAGE)
                if(previous == null) legacySource.writeDurably(text)
                ensure(legacySource.read() == text,"Unverified reminder migration source",ErrorCode.STORAGE)
            }
            val id = newId()
            db.transaction {
                db.execute("INSERT INTO control VALUES (1,?,?,?,?)",listOf(id,legacy.revision,legacy.pending?.let { json.encodeToString(it) },sourceHash))
                for(attempt in legacy.attempts) insert(attempt)
            }
            row = listOf(id,sourceHash)
        }
        ensure(row[0]?.isNotBlank() == true && (row[1] == null || row[1]!!.matches(Regex("[0-9a-f]{64}"))),"Invalid reminder authority",ErrorCode.STORAGE)
        val marker = ScheduleIndexMarker(databaseId = row[0]!!,legacySha256 = row[1])
        if(format == 2) ensure(json.decodeFromString<ScheduleIndexMarker>(text!!) == marker,"Reminder authority/index mismatch",ErrorCode.STORAGE)
        else {
            ensure(text?.let { sha256(it.toByteArray(Charsets.UTF_8)) } == marker.legacySha256,"Reminder migration source changed after commit",ErrorCode.STORAGE)
            val encoded = json.encodeToString(marker)
            manifest.writeDurably(encoded)
            ensure(manifest.read() == encoded,"Unverified reminder authority",ErrorCode.STORAGE)
        }
        control() // Validate the small operational control, not the lifetime receipt graph.
    }
    override fun control(): ScheduleDeliveryState {
        val row = db.query("SELECT revision,pending FROM control WHERE id=1").single()
        return ScheduleDeliveryState(revision = row[0]!!.toLong(),pending = row[1]?.let { json.decodeFromString<ScheduleAlarmBatch>(it) })
            .also(::validateScheduleDeliveryState)
    }
    private fun decode(row: List<String?>): Pair<Long,ScheduleReminderAttempt> {
        val a = json.decodeFromString<ScheduleReminderAttempt>(row[1]!!).also(::validateScheduleAttempt)
        ensure(a.ticket.identity == row[2] && a.ticket.datasetId == row[3] && a.ticket.key.seriesId == row[4] && a.ticket.key.originalDate == row[5],
            "Contradictory reminder row identity",ErrorCode.STORAGE)
        return row[0]!!.toLong().also { ensure(it > 0,"Invalid reminder sequence",ErrorCode.STORAGE) } to a
    }
    private val columns = "sequence,payload,identity,dataset_id,series_id,original_date"
    override fun find(datasetId: String,key: OccurrenceKey): ScheduleReminderAttempt? {
        val identity = payloadHash(datasetId to key)
        val rows = db.query("SELECT $columns FROM attempts WHERE identity=?",listOf(identity))
        ensure(rows.size <= 1,"Duplicate reminder identity",ErrorCode.STORAGE)
        return rows.singleOrNull()?.let(::decode)?.second?.also {
            ensure(it.ticket.datasetId == datasetId && it.ticket.key == key,"Reminder identity collision",ErrorCode.STORAGE)
        }
    }
    private fun insert(value: ScheduleReminderAttempt) {
        validateScheduleAttempt(value)
        val payload = json.encodeToString(value);CopyMetadataBytes.check(payload) // Per row, not lifetime capacity.
        db.execute("INSERT INTO attempts (identity,dataset_id,series_id,original_date,payload) VALUES (?,?,?,?,?)",
            listOf(value.ticket.identity,value.ticket.datasetId,value.ticket.key.seriesId,value.ticket.key.originalDate,payload))
    }
    override fun setPending(expected: ScheduleDeliveryState,pending: ScheduleAlarmBatch?) = db.transaction {
        ensure(control() == expected,"Stale reminder control",ErrorCode.STORAGE)
        validateScheduleDeliveryState(expected.copy(pending = pending))
        if(expected.pending != pending) {
            val payload = pending?.let { json.encodeToString(it).also { CopyMetadataBytes.check(it) } }
            db.execute("UPDATE control SET revision=?,pending=? WHERE id=1",listOf(Math.addExact(expected.revision,1),payload))
        }
    }
    override fun reserve(expectedRevision: Long,value: ScheduleReminderAttempt): Boolean = db.transaction {
        if(find(value.ticket.datasetId,value.ticket.key) != null) false else {
            val current = control()
            ensure(current.revision == expectedRevision && value.status == ScheduleAttemptStatus.RESERVED,"Stale reminder reserve",ErrorCode.STORAGE)
            insert(value)
            db.execute("UPDATE control SET revision=? WHERE id=1",listOf(Math.addExact(current.revision,1)))
            true
        }
    }
    override fun complete(reserved: ScheduleReminderAttempt,result: ScheduleReminderAttempt) = db.transaction {
        ensure(reserved.status == ScheduleAttemptStatus.RESERVED && result.ticket == reserved.ticket && result.attemptedAt == reserved.attemptedAt &&
            result.status != ScheduleAttemptStatus.RESERVED,"Invalid reminder completion",ErrorCode.STORAGE)
        ensure(find(reserved.ticket.datasetId,reserved.ticket.key) == reserved,"Changed reminder reservation",ErrorCode.STORAGE)
        validateScheduleAttempt(result)
        val current = control()
        db.execute("UPDATE attempts SET payload=? WHERE identity=?",listOf(json.encodeToString(result),reserved.ticket.identity))
        db.execute("UPDATE control SET revision=? WHERE id=1",listOf(Math.addExact(current.revision,1)))
    }
    override fun page(afterSequence: Long,limit: Int): ScheduleAttemptPage {
        require(afterSequence >= 0 && limit in 1..1024)
        val rows = db.query("SELECT $columns FROM attempts WHERE sequence>? ORDER BY sequence LIMIT ?",listOf(afterSequence,limit)).map(::decode)
        return ScheduleAttemptPage(frozenList(rows.map { it.second }),if(rows.size == limit) rows.last().first else null)
    }
    override fun read(): ScheduleDeliveryState {
        val attempts = mutableListOf<ScheduleReminderAttempt>();var cursor = 0L
        do { val p = page(cursor,1024);attempts += p.attempts;cursor = p.nextAfterSequence ?: break } while(true)
        return control().copy(attempts = frozenList(attempts))
    }
    override fun writeDurably(state: ScheduleDeliveryState): Unit = fail(ErrorCode.STORAGE,"Whole-state reminder replacement is prohibited")
}
