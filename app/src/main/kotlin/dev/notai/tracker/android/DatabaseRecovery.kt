package dev.notai.tracker.android

import android.content.Context
import dev.notai.tracker.*
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.Serializable

@Serializable data class PreservedDatabaseFile(val name: String,val bytes: Long,val sha256: String)
@Serializable data class DatabaseArchive(val id: String,val database: String,val files: List<PreservedDatabaseFile>)
/** Preserve raw originals without opening SQLite, including sidecars, before any explicit rescue.
 * SQLite's default corruption handler must never delete this application's files.
 */
object DatabaseArchives {
    fun capture(context: Context,name: String = "tracker.db"): File {
        val root = File(context.noBackupFilesDir,"database-archives").apply { mkdirs() }
        val id = newId();val directory = File(root,id).apply { mkdirs() }
        val files = listOf(name,"$name-wal","$name-shm","$name-journal").mapNotNull { filename ->
            val source = context.getDatabasePath(filename);if(!source.exists()) return@mapNotNull null
            val target = File(directory,filename)
            source.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it,64*1024);it.fd.sync() } }
            val sourceHash = fileHash(source);ensure(source.length() == target.length() && sourceHash == fileHash(target),"Original database changed during preservation",ErrorCode.STORAGE)
            PreservedDatabaseFile(filename,target.length(),sourceHash)
        }
        atomicText(File(directory,"manifest.json"),codec.encodeToString(DatabaseArchive(id,name,files)))
        syncDirectory(directory);syncDirectory(root);syncDirectory(context.noBackupFilesDir);return directory
    }
}
@Serializable private data class RecoveryJob(val id: String,val source: String,val archive: String,val candidate: String,val sourceSha256: String)
class RescuePreview internal constructor(internal val source: File,internal val backup: Backup,internal val sourceSha256: String) {
    val sessions get() = backup.data.sessions.size
    val segments get() = backup.data.segments.size
    val cards get() = backup.data.cards.size
    val asOf get() = backup.asOf
}
data class RescueResult(val archive: String,val sessions: Int)
object DatabaseRecovery {
    private fun pending(context: Context) = File(context.noBackupFilesDir,"recovery-pending.json")
    fun needsAttention(context: Context) = pending(context).exists()
    fun prepare(source: File): RescuePreview {
        val hash = fileHash(source);val backup = StreamingBackup.read { source.inputStream() }
        ensure(fileHash(source) == hash,"Recovery source changed while preparing",ErrorCode.STALE)
        return RescuePreview(source,backup,hash)
    }
    /** Explicit replacement. Source/rollback and raw original files remain available. */
    fun apply(context: Context,preview: RescuePreview): RescueResult {
        ensure(fileHash(preview.source) == preview.sourceSha256,"Recovery source changed after preview",ErrorCode.STALE)
        val liveSource = StreamingBackup.read { preview.source.inputStream() }
        ensure(liveSource == preview.backup,"Recovery source changed after preview",ErrorCode.STALE)
        TrackerRuntime.beginRecovery()
        try {
            val archive = DatabaseArchives.capture(context)
            val candidateName = "rescue-${newId()}.db"
            val restored = Backups.restoredData(liveSource,Math.addExact(liveSource.data.revision,1))
            val candidate = TrackerDatabase.open(context,candidateName)
            try {
                val store = RoomStore(candidate,{ restored })
                ensure(store.read() == restored,"Recovery candidate readback failed",ErrorCode.STORAGE)
                candidate.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { cursor ->
                    ensure(cursor.moveToFirst() && cursor.getInt(0) == 0,"Candidate checkpoint failed",ErrorCode.STORAGE)
                }
            } finally { candidate.close() }
            ensure(fileHash(preview.source) == preview.sourceSha256,"Recovery source changed before replacement",ErrorCode.STALE)
            val job = RecoveryJob(newId(),preview.source.absolutePath,archive.absolutePath,candidateName,preview.sourceSha256)
            atomicText(pending(context),codec.encodeToString(job))
            val retired = File(archive,"retired").apply { mkdirs() }
            for(name in listOf("tracker.db","tracker.db-wal","tracker.db-shm","tracker.db-journal")) {
                val original = context.getDatabasePath(name)
                if(original.exists()) DirectoryDurability.rename(original,File(retired,name))
            }
            syncDirectory(retired);syncDirectory(archive)
            val staged = context.getDatabasePath(candidateName);val target = context.getDatabasePath("tracker.db")
            DirectoryDurability.rename(staged,target);syncDirectory(target.parentFile!!)
            // Validation occurs before declaring completion; failed replacement remains gated by journal.
            val db = TrackerDatabase.open(context)
            try { ensure(RoomStore(db,{ restored }).read() == restored,"Installed recovery readback failed",ErrorCode.STORAGE) }
            finally { db.close() }
            ensure(pending(context).delete(),"Recovery journal could not be completed",ErrorCode.STORAGE)
            syncDirectory(context.noBackupFilesDir)
            return RescueResult(archive.name,restored.sessions.size)
        } finally { TrackerRuntime.endRecovery() }
    }
    /** Retry from the retained verified source after a crash; no automatic empty initialization. */
    fun resume(context: Context): RescueResult {
        val job = codec.decodeFromString<RecoveryJob>(pending(context).readText())
        ensure(fileHash(File(job.source)) == job.sourceSha256,"Retained recovery source changed; select a copy and review it again",ErrorCode.STALE)
        return apply(context,prepare(File(job.source)))
    }
}
