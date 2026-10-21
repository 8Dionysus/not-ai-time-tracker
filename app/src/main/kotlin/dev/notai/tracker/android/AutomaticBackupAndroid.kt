package dev.notai.tracker.android

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import android.provider.DocumentsContract
import dev.notai.tracker.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal class AndroidBackupJournal(context: Context): AutomaticBackupStore {
    private val file = File(context.noBackupFilesDir,"automatic-backup.json")
    private val metadata = PrivateFileMaintenance.metadata(file)
    private val lock = locks.getOrPut(file.absolutePath) { Any() }
    override fun read(): AutomaticBackupState = synchronized(lock) {
        metadata.read()?.let { codec.decodeFromString<AutomaticBackupState>(it) }?.also {
            require(it.format == 1);it.policy.validate()
        } ?: AutomaticBackupState()
    }
    override fun update(change: (AutomaticBackupState)->AutomaticBackupState): AutomaticBackupState = synchronized(lock) {
        val previous = read()
        change(previous).also { if(it != previous) metadata.writeDurably(codec.encodeToString(it)) }
    }
    companion object { private val locks = java.util.concurrent.ConcurrentHashMap<String,Any>() }
}

/** Only the grant actually returned by the system picker may be persisted. No account access. */
class SafBackupDestination(private val context: Context): BackupDestination {
    private val resolver get() = context.contentResolver
    companion object {
        fun selectionIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
    }
    fun acceptSelection(uri: Uri,returnedFlags: Int): String {
        val flags = returnedFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if(!DocumentsContract.isTreeUri(uri) || flags != (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            throw BackupDestinationException(AutomaticBackupError.REQUIRES_SELECTION)
        try { resolver.takePersistableUriPermission(uri,flags) }
        catch(_: SecurityException) { throw BackupDestinationException(AutomaticBackupError.REQUIRES_SELECTION) }
        check(uri.toString());return uri.toString()
    }
    private inline fun <T> provider(block: ()->T): T = try { block() }
        catch(e: BackupDestinationException) { throw e }
        catch(_: SecurityException) { throw BackupDestinationException(AutomaticBackupError.GRANT_REVOKED) }
        catch(_: UnsupportedOperationException) { throw BackupDestinationException(AutomaticBackupError.UNSUPPORTED) }
        catch(_: Exception) { throw BackupDestinationException(AutomaticBackupError.PROVIDER_UNAVAILABLE) }
    private fun tree(value: String): Uri {
        val uri = Uri.parse(value)
        if(uri.scheme != "content" || !DocumentsContract.isTreeUri(uri)) throw BackupDestinationException(AutomaticBackupError.REQUIRES_SELECTION)
        if(resolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission && it.isWritePermission })
            throw BackupDestinationException(AutomaticBackupError.GRANT_REVOKED)
        return uri
    }
    private fun root(uri: Uri) = DocumentsContract.buildDocumentUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri))
    override fun check(destination: String) = provider {
        val uri = tree(destination)
        resolver.query(root(uri),arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_FLAGS),null,null,null)?.use { c ->
            if(!c.moveToFirst() || c.getString(0) != DocumentsContract.Document.MIME_TYPE_DIR ||
                c.getLong(1) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE.toLong() == 0L)
                throw BackupDestinationException(AutomaticBackupError.UNSUPPORTED)
        } ?: throw BackupDestinationException(AutomaticBackupError.PROVIDER_UNAVAILABLE)
        // A tree URI string alone proves neither current availability nor child-list capability.
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri,DocumentsContract.getTreeDocumentId(uri))
        resolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null)?.use { it.moveToFirst() }
            ?: throw BackupDestinationException(AutomaticBackupError.PROVIDER_UNAVAILABLE)
        Unit
    }
    private fun resolveDocument(destination: String,value: String): Uri {
        val parent = tree(destination);val uri = Uri.parse(value)
        if(uri.authority != parent.authority || !DocumentsContract.isTreeUri(uri) ||
            DocumentsContract.getTreeDocumentId(uri) != DocumentsContract.getTreeDocumentId(parent))
            throw BackupDestinationException(AutomaticBackupError.REQUIRES_SELECTION)
        return uri
    }
    override fun create(destination: String,name: String): String = provider {
        val parent = tree(destination)
        DocumentsContract.createDocument(resolver,root(parent),"application/json",name)?.toString()
            ?: throw BackupDestinationException(AutomaticBackupError.UNSUPPORTED)
    }
    override fun verify(destination: String,document: String,bytes: Long,sha256: String): Boolean = provider {
        val uri = resolveDocument(destination,document)
        val hash = MessageDigest.getInstance("SHA-256");var count = 0L
        val input = resolver.openInputStream(uri) ?: throw BackupDestinationException(AutomaticBackupError.READBACK_UNAVAILABLE)
        input.use {
            val buffer = ByteArray(64*1024)
            while(true) { val n = it.read(buffer);if(n < 0) break;if(n == 0) continue
                count += n;if(count > bytes) return@provider false;hash.update(buffer,0,n)
            }
        }
        count == bytes && hash.digest().joinToString("") { "%02x".format(it) } == sha256
    }
    override fun delete(destination: String,document: String) = provider {
        val uri = resolveDocument(destination,document)
        // A crash after deletion but before the journal update is harmless on repeat.
        val exists = resolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null)?.use { it.moveToFirst() }
            ?: throw BackupDestinationException(AutomaticBackupError.PROVIDER_UNAVAILABLE)
        if(exists && !DocumentsContract.deleteDocument(resolver,uri)) throw BackupDestinationException(AutomaticBackupError.RETENTION_FAILED)
        Unit
    }
    override fun exists(destination: String,document: String): Boolean = provider {
        val uri = resolveDocument(destination,document)
        resolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),null,null,null)?.use { it.moveToFirst() }
            ?: throw BackupDestinationException(AutomaticBackupError.PROVIDER_UNAVAILABLE)
    }
}

/** Commands run on IO. Exposed from TrackerRuntime; no Activity/ViewModel lifetime dependency. */
class AutomaticBackups internal constructor(internal val context: Context,private val runtime: TrackerRuntime) {
    private val saf = SafBackupDestination(context)
    private val exports = ExportOperations(context)
    internal val controller = AutomaticBackupController(AndroidBackupJournal(context),saf,object: BackupStaging {
        override fun begin(exportId: String) { exports.beginOwned(exportId,"application/json","automatic-backup.json") }
        override fun prepare(exportId: String): StagedBackup {
            var source: BackupSource? = null
            val op = exports.prepare(exportId) { source = runtime.backups.writeSnapshot(it) }
            val captured = requireNotNull(source)
            return StagedBackup(exportId,BackupRevision(captured.datasetId,captured.revision),captured.asOf,op.bytes,requireNotNull(op.sha256))
        }
        override fun write(snapshot: StagedBackup,document: String): Boolean {
            val op = exports.read(snapshot.exportId)
            check(op.bytes == snapshot.bytes && op.sha256 == snapshot.sha256) { "Staged snapshot changed" }
            val pending = AndroidBackupJournal(context).read().pending
            check(pending != null && pending.snapshot == snapshot && pending.document == document) { "Wrong automatic operation/document" }
            return try { exports.retryWrite(snapshot.exportId,Uri.parse(document),snapshot.bytes,snapshot.sha256).verified }
            catch(_: ExportVerificationFailure) { throw BackupDestinationException(AutomaticBackupError.CORRUPT_READBACK) }
            catch(_: SecurityException) { throw BackupDestinationException(AutomaticBackupError.GRANT_REVOKED) }
            catch(_: java.io.IOException) { throw BackupDestinationException(AutomaticBackupError.PROVIDER_UNAVAILABLE) }
        }
        override fun complete(exportId: String) {
            check(exports.read(exportId).automaticOwner == exportId) { "Wrong automatic completion owner" }
            exports.receiveResult(exportId,false)
        }
        override fun abandon(exportId: String) {
            check(exports.read(exportId).automaticOwner == exportId) { "Wrong automatic cancellation owner" }
            exports.cancel(exportId);exports.receiveResult(exportId,true)
        }
    },BackupWorkScheduler { AutomaticBackupJobs.reconcile(context,it) },System::currentTimeMillis)
    fun status() = controller.status()
    fun enable(tree: String,policy: BackupPolicy = BackupPolicy()): AutomaticBackupState {
        controller.enable(tree,policy);changed(runtime.tracker.view());return status()
    }
    fun select(uri: Uri,returnedFlags: Int,policy: BackupPolicy = BackupPolicy()) = enable(saf.acceptSelection(uri,returnedFlags),policy)
    fun disable() = controller.disable()
    fun retry() = controller.retry()
    fun requestBackup() = controller.requestBackup()
    fun abandonPending(operationId: String) = controller.abandonPending(operationId)
    internal fun changed(view: TrackerView) = controller.changed(BackupRevision(view.data.datasetId,view.data.revision))
    /** Verification uses exact recorded identity, then the existing source lease/preview/rollback path. */
    fun prepareRestore(operationId: String): RestorePreview {
        val version = status().versions.single { it.operationId == operationId }
        saf.check(version.destination)
        if(!saf.verify(version.destination,version.document,version.snapshot.bytes,version.snapshot.sha256))
            throw BackupDestinationException(AutomaticBackupError.CORRUPT_READBACK)
        val file = context.contentResolver.openInputStream(Uri.parse(version.document))?.use { stageRestore(context,it) }
            ?: throw BackupDestinationException(AutomaticBackupError.PROVIDER_UNAVAILABLE)
        // Provider may change between verification and staging: verify the exact restore source again.
        if(file.length() != version.snapshot.bytes || fileHash(file) != version.snapshot.sha256)
            throw BackupDestinationException(AutomaticBackupError.CORRUPT_READBACK)
        return runtime.backups.prepare { file.inputStream() }
    }
}

internal object AutomaticBackupJobs {
    const val ON_CHANGE = 42107;const val RECOVERY = 42108
    val running = AtomicBoolean(false)
    fun reconcile(context: Context,state: AutomaticBackupState) {
        val jobs = context.getSystemService(JobScheduler::class.java)
        if(!state.enabled) { jobs.cancel(ON_CHANGE);jobs.cancel(RECOVERY);return }
        val service = ComponentName(context,AutomaticBackupJobService::class.java)
        if(jobs.getPendingJob(RECOVERY) == null) check(jobs.schedule(JobInfo.Builder(RECOVERY,service)
            .setPeriodic(900_000L).setPersisted(true).setBackoffCriteria(900_000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()) == JobScheduler.RESULT_SUCCESS)
        if(running.get()) return
        if(state.consecutiveFailures >= 5) { jobs.cancel(ON_CHANGE);return }
        val needsWork = state.pending != null || state.requested || state.dirtyAt != null || state.finalizations.isNotEmpty() || state.error == AutomaticBackupError.RETENTION_FAILED
        if(!needsWork) { jobs.cancel(ON_CHANGE);return }
        val due = maxOf(state.nextAttemptAt,(state.dirtyAt ?: 0)+AutomaticBackupController.DEBOUNCE_MS)
        val existing = jobs.getPendingJob(ON_CHANGE)
        if(existing != null && existing.extras.getLong("generation") == state.generation && existing.extras.getLong("due") == due) return
        val extras = PersistableBundle().apply { putLong("generation",state.generation);putLong("due",due) }
        check(jobs.schedule(JobInfo.Builder(ON_CHANGE,service).setMinimumLatency(maxOf(0,due-System.currentTimeMillis()))
            .setPersisted(true).setExtras(extras).setBackoffCriteria(900_000L,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()) == JobScheduler.RESULT_SUCCESS)
    }
}

class AutomaticBackupJobService: JobService() {
    private val stopped = java.util.concurrent.ConcurrentHashMap<JobParameters,AtomicBoolean>()
    override fun onStartJob(params: JobParameters): Boolean {
        if(!AutomaticBackupJobs.running.compareAndSet(false,true)) return false
        val flag = AtomicBoolean(false);stopped[params] = flag
        CoroutineScope(Dispatchers.IO).launch {
            var retry = false
            try { TrackerRuntime.get(this@AutomaticBackupJobService).automaticBackups.controller.run { flag.get() } }
            catch(_: Exception) { retry = true }
            withContext(Dispatchers.Main) {
                if(!flag.get()) jobFinished(params,retry)
                stopped.remove(params);AutomaticBackupJobs.running.set(false)
                try { AutomaticBackupJobs.reconcile(this@AutomaticBackupJobService,AndroidBackupJournal(this@AutomaticBackupJobService).read()) }
                catch(_: Exception) { /* Durable queue remains; recovery job or next foreground revision retries. */ }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { stopped[params]?.set(true);return true }
    override fun onDestroy() { stopped.values.forEach { it.set(true) };super.onDestroy() }
}
