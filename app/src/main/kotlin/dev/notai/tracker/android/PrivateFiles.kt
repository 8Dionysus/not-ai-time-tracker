package dev.notai.tracker.android

import android.content.Context
import android.net.Uri
import dev.notai.tracker.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable

internal fun fileHash(file: File): String = file.inputStream().use(::streamHash)
private fun streamHash(input: InputStream): String {
    val hash = MessageDigest.getInstance("SHA-256");val buffer = ByteArray(64*1024)
    while(true) { val n = input.read(buffer);if(n < 0) break;if(n > 0) hash.update(buffer,0,n) }
    return hash.digest().joinToString("") { "%02x".format(it) }
}
internal object DirectoryDurability {
    // Injectable platform boundary: Robolectric does not implement opening a directory fd.
    var rename: (File,File)->Unit = { source,target -> android.system.Os.rename(source.absolutePath,target.absolutePath) }
    var link: (File,File)->Unit = { source,target -> android.system.Os.link(source.absolutePath,target.absolutePath) }
    var sync: (File)->Unit = { directory ->
    val fd = android.system.Os.open(directory.absolutePath,android.system.OsConstants.O_RDONLY,0)
    try { android.system.Os.fsync(fd) } finally { android.system.Os.close(fd) }
    }
}
/** Known private child only; never follow a pre-existing symlink directory. */
internal fun privateCopyDirectory(context: Context,name: String): File {
    require(name.matches(Regex("[a-z-]+")))
    val directory = File(context.noBackupFilesDir,name)
    try { java.nio.file.Files.createDirectory(directory.toPath()) } catch(_: java.nio.file.FileAlreadyExistsException) { /* Validate the winning directory below. */ }
    ensure(java.nio.file.Files.isDirectory(directory.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS),"Private copy directory is unsafe",ErrorCode.STORAGE)
    return directory
}
internal fun syncDirectory(directory: File) = DirectoryDurability.sync(directory)
internal fun atomicText(file: File,text: String) {
    atomicBytes(file,text.toByteArray(Charsets.UTF_8))
}
internal fun atomicBytes(file: File,bytes: ByteArray) {
    file.parentFile!!.mkdirs();val part = File(file.parentFile,"${file.name}.${newId()}.part")
    FileOutputStream(part).use { it.write(bytes);it.fd.sync() }
    DirectoryDurability.rename(part,file);syncDirectory(file.parentFile!!);file.parentFile?.parentFile?.let(::syncDirectory)
}

@Serializable data class ExportOperation(val id: String,val mime: String,val title: String,val state: String = "PREPARING",
    val bytes: Long = 0,val sha256: String? = null,val error: String? = null,val resultReceived: Boolean = false)
data class ExportWriteResult(val bytes: Long,val verified: Boolean)
/** File-backed jobs survive Activity recreation. The Bundle only carries the operation ID. */
class ExportOperations private constructor(private val context: Context,private val completedRegistration: (Context,String) -> Unit,
    private val handoffRegistration: (Context,String) -> Unit) {
    internal constructor(context: Context,registration: (Context,String) -> Unit): this(context,registration,registration)
    constructor(context: Context): this(context,{ c,id -> PrivateCopyBackend.registerExport(c,id);Unit },
        { c,id -> PrivateCopyBackend.registerCompletedExport(c,id);Unit })
    private val directory = File(context.noBackupFilesDir,"exports").apply { mkdirs() }
    private fun dir(id: String): File { require(id.matches(Regex("[0-9a-f-]{36}")));return File(directory,id) }
    fun begin(mime: String,title: String): ExportOperation = PrivateFileMaintenance.writing(context,"export-begin") { beginUnsafe(mime,title) }
    private fun beginUnsafe(mime: String,title: String): ExportOperation {
        val operation = ExportOperation(newId(),mime,title);save(operation);return operation
    }
    private fun save(operation: ExportOperation,registerCompleted: Boolean = true) {
        PrivateFileMaintenance.invalidate(context,"exports/${operation.id}")
        atomicText(File(dir(operation.id),"operation.json"),codec.encodeToString(operation))
        if(registerCompleted && operation.state in setOf("COMPLETE","CANCELLED") && operation.resultReceived) completedRegistration(context,operation.id)
    }
    fun read(id: String): ExportOperation = codec.decodeFromString(File(dir(id),"operation.json").readText())
    fun latestPending(): ExportOperation? = directory.listFiles().orEmpty().filter { it.isDirectory }
        .sortedByDescending { File(it,"operation.json").lastModified() }.firstNotNullOfOrNull { folder ->
            runCatching { read(folder.name) }.getOrNull()?.takeIf { it.state in setOf("READY","FAILED","PREPARING","CANCELLED") }
        }
    fun prepare(id: String,write: (OutputStream)->Unit): ExportOperation = PrivateFileMaintenance.writing(context,"export-prepare:$id",CopyBlocker.EXPORT_WRITING) { prepareUnsafe(id,write) }
    private fun prepareUnsafe(id: String,write: (OutputStream)->Unit): ExportOperation {
        PrivateFileMaintenance.invalidate(context,"exports/$id")
        val op = read(id);val part = File(dir(id),"data.part")
        return try {
            FileOutputStream(part).use { write(it);it.fd.sync() }
            val data = File(dir(id),"data");DirectoryDurability.rename(part,data);syncDirectory(dir(id))
            val bytes = data.length();val hash = fileHash(data)
            PrivateFileMaintenance.forContext(context).locked {
                val current = read(id)
                op.copy(state = if(current.state == "CANCELLED") "CANCELLED" else "READY",bytes = bytes,sha256 = hash,error = null,resultReceived = current.resultReceived).also(::save)
            }
        } catch(e: Exception) {
            PrivateFileMaintenance.forContext(context).locked {
                val current = read(id)
                save(current.copy(state = if(current.state == "CANCELLED") "CANCELLED" else "FAILED",error = e.javaClass.simpleName))
            }
            throw e
        }
    }
    fun cancel(id: String) = PrivateFileMaintenance.writing(context,"export-cancel:$id") { cancelUnsafe(id) }
    private fun cancelUnsafe(id: String) = PrivateFileMaintenance.forContext(context).locked { val op = read(id);save(op.copy(state = "CANCELLED")) } // retain staged bytes for explicit retry
    /** Persist consumption of a chooser result (or explicit owner cancellation before any chooser).
     * The owner must keep an active chooser protected until its actual result arrives.
     * No destination URI or grant is retained: an interrupted write needs a fresh chooser. */
    fun receiveResult(id: String,cancelled: Boolean) {
        val saved = PrivateFileMaintenance.writing(context,"export-result:$id") { receiveResultUnsafe(id,cancelled) }
        if(saved.state in setOf("COMPLETE","CANCELLED") && saved.resultReceived)
            CopyLifetimes.get(context).registerCompletedExport(id,handoffRegistration)
    }
    private fun receiveResultUnsafe(id: String,cancelled: Boolean): ExportOperation {
        val op = read(id)
        val saved = op.copy(resultReceived = true,state = if(cancelled) "CANCELLED" else op.state)
        save(saved,registerCompleted = false)
        val gate = PrivateFileMaintenance.forContext(context)
        gate.locked { gate.state().references.filter { it.owner == "export-chooser:$id" }.forEach(gate::release) }
        return saved
    }
    fun awaitResult(id: String) = PrivateFileMaintenance.writing(context,"export-await:$id",CopyBlocker.CHOOSER_RESULT_PENDING) { awaitResultUnsafe(id) }
    private fun awaitResultUnsafe(id: String) {
        val gate = PrivateFileMaintenance.forContext(context)
        gate.locked {
            if(gate.state().references.none { it.owner == "export-chooser:$id" })
                gate.acquire("export-chooser:$id",PrivateCopyBackend.itemId("exports/$id"),CopyBlocker.CHOOSER_RESULT_PENDING)
            val op = read(id);save(op.copy(resultReceived = false))
        }
    }
    fun write(id: String,uri: Uri): ExportWriteResult = PrivateFileMaintenance.writing(context,"export-write:$id",CopyBlocker.EXPORT_WRITING) { writeUnsafe(id,uri) }
    private fun writeUnsafe(id: String,uri: Uri): ExportWriteResult {
        val op = read(id);val data = File(dir(id),"data")
        ensure(op.state in setOf("READY","CANCELLED") && data.exists() && data.length() == op.bytes && fileHash(data) == op.sha256,
            "Export is not ready or the private staged file is damaged",ErrorCode.STORAGE)
        try {
            val output = context.contentResolver.openOutputStream(uri,"wt") ?: throw java.io.IOException("Destination unavailable")
            output.use { out -> data.inputStream().use { input -> input.copyTo(out,64*1024) };out.flush() }
            val remoteHash = try { context.contentResolver.openInputStream(uri)?.use(::streamHash) } catch(_: Exception) { null }
            ensure(remoteHash == null || remoteHash == op.sha256,"Written document failed readback verification",ErrorCode.STORAGE)
            save(op.copy(state = "COMPLETE",error = if(remoteHash == null) "Provider does not allow readback" else null))
            return ExportWriteResult(op.bytes,remoteHash != null)
        } catch(e: Exception) { save(op.copy(state = "READY",error = e.javaClass.simpleName));throw e }
    }
}

@Serializable internal data class FormState(val selectedCardId: String? = null,val name: String = "",val details: String = "",
    val note: String = "",val start: String = "",val end: String = "",val zone: String = "",val source: String? = null,
    val sourceReference: String? = null,val pendingText: String? = null,
    val csvMapping: CsvMapping? = null,val csvSourceSha256: String? = null,val csvUseCard: Boolean = false,
    val backendRevision: Long = 0,val scheduleCommand: ScheduleCommandDraft? = null,val selectiveDocument: SelectiveDocumentDraft? = null)
internal class FormFiles(private val context: Context,captureActivityOwner: Boolean = true) {
    private val lifecycle = CopyLifetimes.get(context)
    private val actor = if(captureActivityOwner) lifecycle.callingOwner() else null // captured when the Activity creates its backend form handle
    private val directory = privateCopyDirectory(context,"forms")
    fun save(id: String?,state: FormState): String {
        val reference = id ?: newId();lifecycle.unclaimedForm(reference,actor)
        val gate = PrivateFileMaintenance.forContext(context);val item = PrivateCopyBackend.itemId("forms/$reference.json")
        return gate.locked {
            val prior = gate.state().references.filter { it.owner == "form-save:$reference" && it.itemId == item && it.blocker == CopyBlocker.LIVE_FORM_REFERENCE }
            gate.writing("form-save:$reference",item,CopyBlocker.LIVE_FORM_REFERENCE) {
                saveUnsafe(reference,state).also { prior.forEach(gate::release) }
            }
        }
    }
    private fun saveUnsafe(id: String?,state: FormState): String {
        val reference = id ?: newId();require(reference.matches(Regex("[0-9a-f-]{36}")))
        val text = codec.encodeToString(state);CopyMetadataBytes.check(text)
        PrivateFileMaintenance.invalidate(context,"forms/$reference.json")
        atomicText(File(directory,"$reference.json"),text)
        ensure(PrivateFileMaintenance.metadata(File(directory,"$reference.json")).read() == text,"Form readback failed",ErrorCode.STORAGE)
        PrivateCopyBackend.registerForm(context,reference);return reference
    }
    fun read(id: String,claimUnowned: Boolean = true): FormState {
        require(id.matches(Regex("[0-9a-f-]{36}")))
        if(claimUnowned) lifecycle.unclaimedForm(id,actor)
        return codec.decodeFromString(PrivateFileMaintenance.metadata(File(directory,"$id.json")).read() ?: fail(ErrorCode.STORAGE,"Form missing"))
    }
    fun ids(): List<String> = directory.listFiles().orEmpty().filter { it.name.matches(Regex("[0-9a-f-]{36}\\.json")) &&
        java.nio.file.Files.isRegularFile(it.toPath(),java.nio.file.LinkOption.NOFOLLOW_LINKS) }.map { it.name.removeSuffix(".json") }
}

internal fun stageRestore(context: Context,input: InputStream): File = CopyLifetimes.get(context).stage(input)
