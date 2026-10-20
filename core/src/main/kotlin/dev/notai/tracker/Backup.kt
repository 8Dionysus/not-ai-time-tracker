package dev.notai.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.Required
import java.io.InputStream
import java.io.OutputStream
import java.io.ByteArrayOutputStream

const val CURRENT_BACKUP_FORMAT = 15

/** Identity of the exact committed revision serialized by a streaming export. Not a restore token. */
data class BackupSource(val datasetId: String,val revision: Long,val asOf: Long)

@Serializable data class Backup(@Required val formatVersion: Int = CURRENT_BACKUP_FORMAT,@Required val backupId: String = newId(),val asOf: Long,val data: Dataset)
/** Bounded whole-String convenience API only; streaming full backups have no total-file cap. */
const val MAX_BACKUP_BYTES = 10*1024*1024

interface RollbackStore {
    /** Persistent adapters hold a durable protective reference across save→verify→domain commit. */
    fun <T> protecting(block: ()->T): T = block()
    /** Persist durably and return a reference. Do not overwrite the user's existing copies. */
    fun save(json: String): String
    fun read(reference: String): String
    /** Compatibility fallback for small/custom stores; persistent adapters override with file streams. */
    fun saveSnapshot(backup: Backup): String {
        val bytes = ByteArrayOutputStream();StreamingBackup.write(backup,bytes)
        return save(bytes.toString("UTF-8"))
    }
    fun readSnapshot(reference: String): Backup = StreamingBackup.read { read(reference).byteInputStream(Charsets.UTF_8) }
}
interface RestoreLease: AutoCloseable { fun <T> protecting(block: () -> T): T = block() }
fun interface RestoreLifecycle {
    fun acquire(sourceSha256: String): RestoreLease?
    fun <T> reading(block: () -> T): T = block()
}
class RestorePreview internal constructor(internal val backup: Backup,internal val expected: Token,private val lease: RestoreLease? = null): AutoCloseable {
    private var closed = false
    @Synchronized internal fun <T> protecting(block: () -> T): T {
        ensure(!closed,"Restore preview was closed",ErrorCode.STALE)
        return lease?.protecting(block) ?: block()
    }
    @Synchronized override fun close() { if(!closed) { lease?.close();closed = true } }
    val settings get() = backup.data.settings
    /** Restored intent requires separate confirmation on this device before delivery. */
    val ordinaryReminderConfirmationRequired get() = settings.longRecordReminder.enabled
    /** Inspect incoming reminder choices without a second parse or authority to edit the restore. */
    val schedule: ScheduleData = backup.data.schedule.frozen()
    val datasetId get() = backup.data.datasetId
    val backupId get() = backup.backupId
    val asOf get() = backup.asOf
    val types get() = backup.data.types.size
    val cards get() = backup.data.cards.size
    val sessions get() = backup.data.sessions.size
    val segments get() = backup.data.segments.size
    val scheduleSeries get() = backup.data.schedule.series.size
    val scheduleOverrides get() = backup.data.schedule.overrides.size
    val scheduleLinks get() = backup.data.schedule.links.size
    val unfinishedFocus get() = backup.data.focus != null
    val goals get() = backup.data.goals.size
    val measurements get() = backup.data.measurements.size
    val unfinishedTail get() = backup.data.segments.any { it.end == null }
    val consequence = "Полная замена локальных записей, карточек, избранного, целей и настроек. Новые локальные данные будут удалены."
}
data class RestoreResult(val state: Snapshot,val rollbackReference: String)
class Backups(private val tracker: Tracker,private val rollback: RollbackStore,private val lifecycle: RestoreLifecycle? = null) {
    /** Capture is fast and consistent. The caller writes returned bytes outside the DB transaction. */
    fun export(): String = storage {
        val captured = tracker.store.transaction { d -> d to (d to tracker.clock.now()) }
        encode(captured.first,captured.second) // serialization/validation never holds the DB transaction
    }
    /** Full-size portable export: no whole-file text or byte buffer. Caller owns the stream. */
    fun write(output: OutputStream) { writeSnapshot(output) }
    fun writeSnapshot(output: OutputStream): BackupSource = storage {
        val captured = tracker.store.transaction { d -> d to (d to tracker.clock.now()) }
        StreamingBackup.write(StreamingBackup.portable(captured.first,captured.second),output)
        BackupSource(captured.first.datasetId,captured.first.revision,captured.second.wall)
    }
    private fun <T> reading(block: () -> T): T = lifecycle?.reading(block) ?: block()
    fun prepare(json: String): RestorePreview = reading { RestorePreview(decode(json),tracker.snapshot().token,lifecycle?.acquire(sha256(json.toByteArray(Charsets.UTF_8)))) }
    fun prepare(open: ()->InputStream): RestorePreview = reading {
        var hash: String? = null
        val backup = StreamingBackup.readWithSource(open) { hash = it }
        RestorePreview(backup,tracker.snapshot().token,lifecycle?.acquire(requireNotNull(hash)))
    }
    /** Calling apply is explicit confirmation of a preview. Abandoning the preview has no effects. */
    fun apply(preview: RestorePreview): RestoreResult = preview.protecting { storage { rollback.protecting {
        val old = tracker.data(); checkToken(old,preview.expected,tracker.clock.now())
        val rollbackBackup = StreamingBackup.portable(old,tracker.clock.now())
        val reference = rollback.saveSnapshot(rollbackBackup)
        val readBack = try { rollback.readSnapshot(reference) } catch(_: Exception) { fail(ErrorCode.STORAGE,"Rollback verification failed") }
        ensure(readBack == rollbackBackup, "Rollback verification failed",ErrorCode.STORAGE)
        val state = tracker.store.transaction { live ->
            checkToken(live,preview.expected,tracker.clock.now())
            val imported = restoredData(preview.backup,Math.addExact(live.revision,1))
            imported to snapshot(imported,tracker.clock.now())
        }
        RestoreResult(state,reference)
    } } }.also { preview.close() }
    companion object {
        fun restoredData(backup: Backup,revision: Long): Dataset {
            StreamingBackup.validateBackup(backup)
            val source = backup.data
            val imported = source.copy(revision = revision,
                controlBarrier = newId(),pendingAssignments = emptyList(),
                sessions = source.sessions.map { if(it.status in setOf(SessionStatus.RUNNING,SessionStatus.RECOVERY)) it.copy(status = SessionStatus.RECOVERY) else it },
                segments = source.segments.map { it.copy(anchor = null,uncertain = it.uncertain || it.end == null) }).frozen()
            validate(imported);return imported
        }
        fun encode(d: Dataset,n: Moment): String {
            validateMoment(n)
            validate(d)
            val frozen = d.copy(pendingAssignments = emptyList(),segments = d.segments.map { materialize(it,n).copy(anchor = null) },focus = Focus.freeze(d,n))
            val json = codec.encodeToString(Backup(formatVersion = CURRENT_BACKUP_FORMAT,asOf = n.wall,data = frozen))
            ensure(json.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES,
                "Backup exceeds supported 10 MiB restore limit; no usable backup was produced",ErrorCode.INVALID_INPUT)
            // An export must pass the same temporal/reference checks as a future restore.
            decode(json)
            return json
        }
        fun decode(json: String): Backup {
            ensure(json.toByteArray(Charsets.UTF_8).size <= MAX_BACKUP_BYTES,"Backup exceeds 10 MiB",ErrorCode.UNSUPPORTED_IMPORT)
            val b = try { decodeBackupVersion(json) } catch (_: Exception) { fail(ErrorCode.UNSUPPORTED_IMPORT,"Malformed or unsupported JSON") }
            ensure(b.formatVersion == CURRENT_BACKUP_FORMAT,"Unsupported backup version",ErrorCode.UNSUPPORTED_IMPORT)
            ensure(b.backupId.isNotBlank() && b.asOf in 0..MAX_TIME,"Invalid backup metadata",ErrorCode.UNSUPPORTED_IMPORT)
            try {
                validate(b.data)
                ensure(b.data.goalRevisionTimes().all { it <= b.asOf },"Goal revision after snapshot")
                ensure(b.data.quantityRecordedTimes().all { it <= b.asOf },"Quantity audit time after snapshot")
                ensure(b.data.manualTimeConfirmations().all { it.confirmedAt <= b.asOf },"Confirmation after snapshot")
                ensure(b.data.focus?.breakAnchor == null,"Backup must not contain Focus anchors")
                ensure(b.data.segments.all { it.anchor == null },"Backup must not contain device anchors")
                ensure(b.data.segments.all { it.start <= b.asOf || it.uncertain || it.isEmptyInterval() },"Segment starts after snapshot")
                ensure(b.data.segments.all { it.end == null || it.end <= b.asOf || it.uncertain || it.isEmptyInterval() },"Segment ends after snapshot")
                ensure(b.data.segments.all { it.observedUntil == null || it.observedUntil <= b.asOf || it.uncertain },"Observation after snapshot")
            } catch (e: CoreError) { fail(ErrorCode.UNSUPPORTED_IMPORT,"Invalid backup: ${e.message}") }
            return b
        }
    }
}
