package dev.notai.tracker.android

import android.content.Context
import dev.notai.tracker.*
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.Serializable

typealias PreservedDatabaseFile = PreservedDatabaseMember
typealias DatabaseArchive = StoredDatabaseArchive
/** Main file and sidecars form one store, including when the main file is missing. */
internal object DatabaseFiles {
    fun paths(context: Context,name: String = "tracker.db"): List<File> =
        listOf("","-wal","-shm","-journal").map { context.getDatabasePath(name+it) }
    fun exists(context: Context,name: String = "tracker.db") = paths(context,name).any { it.exists() }
}
/** Preserve raw originals without opening SQLite, including sidecars, before any explicit rescue.
 * SQLite's default corruption handler must never delete this application's files.
 */
object DatabaseArchives {
    fun capture(context: Context,name: String = "tracker.db",forceSeparate: Boolean = false): File = PrivateFileMaintenance.writing(context,"database-archive") { captureUnsafe(context,name,forceSeparate) }
    private fun captureUnsafe(context: Context,name: String,forceSeparate: Boolean): File {
        val start = System.nanoTime();StartupTrace.mark(context,"archive-start")
        val root = File(context.noBackupFilesDir,"database-archives").apply { mkdirs() }
        // Only sealed, immutable archival copies can be shared. Never link a live
        // SQLite file: subsequent writes would then alter the preserved original.
        val previous = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { folder ->
            runCatching { folder to codec.decodeFromString<DatabaseArchive>(File(folder,"manifest.json").readText()) }.getOrNull()
        }.filter { it.second.database == name }
        val sources = DatabaseFiles.paths(context,name).filter { it.exists() }
        val snapshot = sources.associate { it.name to PreservedDatabaseFile(it.name,it.length(),fileHash(it)) }
        if(!forceSeparate) previous.firstOrNull { (folder,archive) ->
            archive.files.associateBy { it.name } == snapshot && archive.files.all { entry ->
                val file = File(folder,entry.name)
                file.isFile && file.length() == entry.bytes && fileHash(file) == entry.sha256
            } && sources.all { source -> val entry = snapshot.getValue(source.name);source.length() == entry.bytes && fileHash(source) == entry.sha256 }
        }?.let { StartupTrace.mark(context,"archive-reused",System.nanoTime()-start);return it.first }
        val id = newId();PrivateCopyBackend.archiveDraft(context,id)
        val directory = File(root,id).apply { mkdirs() }
        val files = sources.map { source ->
            val filename = source.name
            val target = File(directory,filename)
            val sourceBytes = snapshot.getValue(filename).bytes;val sourceHash = snapshot.getValue(filename).sha256
            val reusable = previous.firstNotNullOfOrNull { (folder,archive) ->
                archive.files.singleOrNull { it.name == filename && it.bytes == sourceBytes && it.sha256 == sourceHash }?.let {
                    File(folder,filename).takeIf { it.isFile && it.length() == sourceBytes && fileHash(it) == sourceHash }
                }
            }
            if(reusable != null) try { DirectoryDurability.link(reusable,target) } catch(_: Exception) { /* Filesystems without hard links copy below. */ }
            if(!target.exists()) source.inputStream().use { input -> FileOutputStream(target).use { input.copyTo(it,64*1024);it.fd.sync() } }
            // Do not truncate an existing target after a partial link failure. It may
            // already share an inode with a previous, still-needed archive.
            ensure(source.length() == sourceBytes && target.length() == sourceBytes && sourceHash == fileHash(target) && sourceHash == fileHash(source),
                "Original database changed during preservation",ErrorCode.STORAGE)
            PreservedDatabaseFile(filename,target.length(),sourceHash)
        }
        atomicText(File(directory,"manifest.json"),codec.encodeToString(DatabaseArchive(id,name,files)))
        syncDirectory(directory);syncDirectory(root);syncDirectory(context.noBackupFilesDir)
        if(!forceSeparate) PrivateCopyBackend.completeArchive(context,id)
        StartupTrace.mark(context,"archive-created",System.nanoTime()-start);return directory
    }
}
@Serializable private data class RecoveryJob(val id: String,val source: String,val archive: String? = null,val candidate: String? = null,val sourceSha256: String,
    val format: Int = 1,val referenceTokens: Set<String> = emptySet(),val archiveIds: Set<String> = emptySet(),
    val completedDatasetSha256: String? = null,val completedSessions: Int? = null,val sourceReferences: List<CopyReference> = emptyList())
class RescuePreview internal constructor(internal val source: File,internal val backup: Backup,internal val sourceSha256: String,private val lease: RestoreLease? = null): AutoCloseable {
    internal val sourceReferences get() = (lease as? PrivateRestoreLease)?.references.orEmpty()
    private var closed = false
    internal fun <T> protecting(block: () -> T): T { ensure(!closed,"Recovery preview closed",ErrorCode.STALE);return lease?.protecting(block) ?: block() }
    override fun close() { if(!closed) { lease?.close();closed = true } }
    val sessions get() = backup.data.sessions.size
    val segments get() = backup.data.segments.size
    val cards get() = backup.data.cards.size
    val asOf get() = backup.asOf
}
data class RescueResult(val archive: String,val sessions: Int)
object DatabaseRecovery {
    /** Fixture interruption point; production never installs a callback. */
    internal var afterCompletionDurable: (() -> Unit)? = null
    private fun pending(context: Context) = File(context.noBackupFilesDir,"recovery-pending.json")
    fun needsAttention(context: Context) = pending(context).exists()
    private fun job(context: Context): RecoveryJob? = PrivateFileMaintenance.metadata(pending(context)).read()?.let { text ->
        codec.decodeFromString<RecoveryJob>(text).also { j ->
            ensure(j.format in 1..3 && (j.format != 1 || j.referenceTokens.isEmpty() && j.archiveIds.isEmpty() && j.archive != null && j.candidate != null) &&
                (j.format < 2 || j.referenceTokens.isNotEmpty()) &&
                (j.format == 3 || j.completedDatasetSha256 == null && j.completedSessions == null && j.sourceReferences.isEmpty()),"Unknown recovery journal version",ErrorCode.STORAGE)
            ensure(j.completedDatasetSha256 == null && j.completedSessions == null || j.completedDatasetSha256?.matches(Regex("[0-9a-f]{64}")) == true &&
                j.completedSessions != null && j.completedSessions >= 0 && j.archive != null && j.candidate != null,"Invalid recovery completion",ErrorCode.STORAGE)
            ensure(j.id.matches(Regex("[0-9a-f-]{36}")) && File(j.source).isAbsolute && (j.candidate == null || j.candidate.matches(Regex("rescue-[0-9a-f-]{36}\\.db"))),"Invalid recovery job paths",ErrorCode.STORAGE)
            ensure(j.sourceSha256.matches(Regex("[0-9a-f]{64}")) && j.referenceTokens.all { it.matches(Regex("[0-9a-f-]{36}")) } &&
                j.archiveIds.all { it.matches(Regex("[0-9a-f-]{36}")) },"Invalid recovery journal identity",ErrorCode.STORAGE)
            ensure(j.sourceReferences.map { it.token }.distinct().size == j.sourceReferences.size && j.sourceReferences.all {
                it.token.matches(Regex("[0-9a-f-]{36}")) && it.owner.startsWith("lifetime:") && it.blocker == CopyBlocker.RESTORE_SOURCE_IN_USE
            },"Invalid recovery source references",ErrorCode.STORAGE)
            j.archive?.let { path -> val a = File(path)
                ensure(a.parentFile == File(context.noBackupFilesDir,"database-archives") && a.name.matches(Regex("[0-9a-f-]{36}")),"Recovery archive reference is not trusted",ErrorCode.STORAGE)
            }
        }
    }
    internal fun protectedArchiveIds(context: Context): Set<String> = job(context)?.let { j -> j.archiveIds+j.archive?.let { setOf(File(it).name) }.orEmpty() }.orEmpty()
    fun prepare(context: Context,source: File): RescuePreview = prepareWithLifecycle(source,CopyLifetimes.get(context))
    // Known private inputs share the same application lifecycle even through the legacy reader.
    // External originals have no cleanup authority in this application.
    fun prepare(source: File): RescuePreview = prepareWithLifecycle(source,CopyLifetimes.forOwnedSource(source))
    private fun prepareWithLifecycle(source: File,lifecycle: RestoreLifecycle?): RescuePreview {
        fun read(): RescuePreview {
            val hash = fileHash(source);val backup = StreamingBackup.read { source.inputStream() }
            ensure(fileHash(source) == hash,"Recovery source changed while preparing",ErrorCode.STALE)
            return RescuePreview(source,backup,hash,lifecycle?.acquire(hash))
        }
        return lifecycle?.reading(::read) ?: read()
    }
    /** Explicit replacement. Source/rollback and raw original files remain available. */
    fun apply(context: Context,preview: RescuePreview): RescueResult {
        val result = preview.protecting { PrivateFileMaintenance.forContext(context).locked {
        // A known stale preview refuses before acquiring a durable writer or changing a journal.
        ensure(fileHash(preview.source) == preview.sourceSha256,"Recovery source changed after preview",ErrorCode.STALE)
        job(context)?.let {
            ensure(it.source == preview.source.absolutePath && it.sourceSha256 == preview.sourceSha256,"Complete the existing recovery before choosing another source",ErrorCode.STALE)
            if(it.completedDatasetSha256 != null) return@locked completeReferences(context,it)
        }
        val result = PrivateFileMaintenance.writingWithReference(context,"database-recovery",CopyBlocker.REQUIRED_BY_PENDING_RECOVERY) { reference -> applyUnsafe(context,preview,reference) }
        completeReferences(context,job(context) ?: fail(ErrorCode.STORAGE,"Recovery completion missing"))
        result
        } }
        preview.close();return result
    }
    private fun applyUnsafe(context: Context,preview: RescuePreview,reference: CopyReference): RescueResult {
        ensure(fileHash(preview.source) == preview.sourceSha256,"Recovery source changed after preview",ErrorCode.STALE)
        val liveSource = StreamingBackup.read { preview.source.inputStream() }
        ensure(liveSource == preview.backup,"Recovery source changed after preview",ErrorCode.STALE)
        val previous = job(context)?.takeIf { it.source == preview.source.absolutePath && it.sourceSha256 == preview.sourceSha256 }
        val gate = PrivateFileMaintenance.forContext(context)
        val priorTokens = previous?.referenceTokens.orEmpty()
        ensure(priorTokens.all { token -> gate.state().references.any { it.token == token && it.owner == "database-recovery" && it.blocker == CopyBlocker.REQUIRED_BY_PENDING_RECOVERY } },"Recovery reference registry changed",ErrorCode.STORAGE)
        TrackerRuntime.beginRecovery()
        try {
            var current = RecoveryJob(newId(),preview.source.absolutePath,sourceSha256 = preview.sourceSha256,format = 3,
                referenceTokens = priorTokens+reference.token,sourceReferences = (previous?.sourceReferences.orEmpty()+preview.sourceReferences).distinctBy { it.token },archiveIds = previous?.let { it.archiveIds+it.archive?.let { a -> setOf(File(a).name) }.orEmpty() }.orEmpty())
            // Durable source/reference relationship precedes archive/candidate preparation too.
            atomicText(pending(context),codec.encodeToString(current))
            val archive = DatabaseArchives.capture(context,forceSeparate = true)
            val candidateName = "rescue-${newId()}.db"
            current = current.copy(archive = archive.absolutePath,candidate = candidateName,archiveIds = current.archiveIds+archive.name)
            atomicText(pending(context),codec.encodeToString(current))
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
            current.archiveIds.forEach { PrivateCopyBackend.completeArchive(context,it) }
            // Keep every exact token durably associated until release is itself durable.
            // A crash before/after any release resumes reconciliation, never replacement.
            current = current.copy(completedDatasetSha256 = sha256(codec.encodeToString(restored).toByteArray()),completedSessions = restored.sessions.size)
            atomicText(pending(context),codec.encodeToString(current))
            afterCompletionDurable?.invoke()
            return RescueResult(archive.name,restored.sessions.size)
        } finally { TrackerRuntime.endRecovery() }
    }
    /** Retry from the retained verified source after a crash; no automatic empty initialization. */
    fun resume(context: Context): RescueResult {
        val job = job(context) ?: fail(ErrorCode.STORAGE,"Recovery journal missing")
        if(job.completedDatasetSha256 != null) return PrivateFileMaintenance.forContext(context).locked { completeReferences(context,job) }
        ensure(fileHash(File(job.source)) == job.sourceSha256,"Retained recovery source changed; select a copy and review it again",ErrorCode.STALE)
        return apply(context,prepare(File(job.source)))
    }
    private fun completeReferences(context: Context,job: RecoveryJob): RescueResult {
        ensure(job.completedDatasetSha256 != null && job.completedSessions != null,"Recovery is not complete",ErrorCode.STORAGE)
        TrackerRuntime.beginRecovery()
        try {
            val db = TrackerDatabase.open(context)
            try {
                val installed = RoomStore(db).read()
                ensure(sha256(codec.encodeToString(installed).toByteArray()) == job.completedDatasetSha256,"Installed recovery changed before completion",ErrorCode.STALE)
            } finally { db.close() }
            val gate = PrivateFileMaintenance.forContext(context)
            for(token in job.referenceTokens) gate.state().references.singleOrNull { it.token == token }?.let { reference ->
                ensure(reference.owner == "database-recovery" && reference.blocker == CopyBlocker.REQUIRED_BY_PENDING_RECOVERY,"Recovery reference registry changed",ErrorCode.STORAGE)
                gate.release(reference)
            }
            for(reference in job.sourceReferences) gate.state().references.singleOrNull { it.token == reference.token }?.let {
                ensure(it == reference,"Recovery source reference changed",ErrorCode.STORAGE);gate.release(it)
            }
            ensure(pending(context).delete(),"Recovery journal could not be completed",ErrorCode.STORAGE)
            syncDirectory(context.noBackupFilesDir)
            return RescueResult(File(job.archive!!).name,job.completedSessions!!)
        } finally { TrackerRuntime.endRecovery() }
    }
}
