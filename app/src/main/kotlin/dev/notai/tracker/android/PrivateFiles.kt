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
internal fun syncDirectory(directory: File) = DirectoryDurability.sync(directory)
internal fun atomicText(file: File,text: String) {
    file.parentFile!!.mkdirs();val part = File(file.parentFile,"${file.name}.${newId()}.part")
    FileOutputStream(part).use { it.write(text.toByteArray(Charsets.UTF_8));it.fd.sync() }
    DirectoryDurability.rename(part,file);syncDirectory(file.parentFile!!);file.parentFile?.parentFile?.let(::syncDirectory)
}

@Serializable data class ExportOperation(val id: String,val mime: String,val title: String,val state: String = "PREPARING",
    val bytes: Long = 0,val sha256: String? = null,val error: String? = null)
data class ExportWriteResult(val bytes: Long,val verified: Boolean)
/** File-backed jobs survive Activity recreation. The Bundle only carries the operation ID. */
class ExportOperations(private val context: Context) {
    private val directory = File(context.noBackupFilesDir,"exports").apply { mkdirs() }
    private fun dir(id: String): File { require(id.matches(Regex("[0-9a-f-]{36}")));return File(directory,id) }
    fun begin(mime: String,title: String): ExportOperation {
        val operation = ExportOperation(newId(),mime,title);save(operation);return operation
    }
    private fun save(operation: ExportOperation) = atomicText(File(dir(operation.id),"operation.json"),codec.encodeToString(operation))
    fun read(id: String): ExportOperation = codec.decodeFromString(File(dir(id),"operation.json").readText())
    fun latestPending(): ExportOperation? = directory.listFiles().orEmpty().filter { it.isDirectory }
        .sortedByDescending { File(it,"operation.json").lastModified() }.firstNotNullOfOrNull { folder ->
            runCatching { read(folder.name) }.getOrNull()?.takeIf { it.state in setOf("READY","FAILED","PREPARING","CANCELLED") }
        }
    fun prepare(id: String,write: (OutputStream)->Unit): ExportOperation {
        val op = read(id);val part = File(dir(id),"data.part")
        return try {
            FileOutputStream(part).use { write(it);it.fd.sync() }
            val data = File(dir(id),"data");DirectoryDurability.rename(part,data);syncDirectory(dir(id))
            op.copy(state = if(read(id).state == "CANCELLED") "CANCELLED" else "READY",bytes = data.length(),sha256 = fileHash(data),error = null).also(::save)
        } catch(e: Exception) { save(op.copy(state = "FAILED",error = e.javaClass.simpleName));throw e }
    }
    fun cancel(id: String) { val op = read(id);save(op.copy(state = "CANCELLED")) } // retain staged bytes for explicit retry
    fun write(id: String,uri: Uri): ExportWriteResult {
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
    val sourceReference: String? = null,val pendingText: String? = null)
internal class FormFiles(context: Context) {
    private val directory = File(context.noBackupFilesDir,"forms").apply { mkdirs() }
    fun save(id: String?,state: FormState): String {
        val reference = id ?: newId();require(reference.matches(Regex("[0-9a-f-]{36}")))
        atomicText(File(directory,"$reference.json"),codec.encodeToString(state));return reference
    }
    fun read(id: String): FormState {
        require(id.matches(Regex("[0-9a-f-]{36}")));return codec.decodeFromString(File(directory,"$id.json").readText())
    }
}

internal fun stageRestore(context: Context,input: InputStream): File {
    val directory = File(context.noBackupFilesDir,"restore-inputs").apply { mkdirs() }
    val file = File(directory,"${newId()}.json")
    FileOutputStream(file).use { out -> input.copyTo(out,64*1024);out.fd.sync() };syncDirectory(directory)
    return file // keep source even after a refusal; never overwrite a user's original
}
