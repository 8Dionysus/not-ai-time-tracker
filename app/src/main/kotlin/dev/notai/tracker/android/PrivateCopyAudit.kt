package dev.notai.tracker.android

import android.content.Context
import dev.notai.tracker.*
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Read-only adapter until Android descriptor binding and frontend lifecycle adoption are verified.
 * These are audit candidates, NOT a selectable CleanupInventory. No cleanup storage is bound to
 * production, so no caller can accidentally turn path checks into deletion authority.
 */
data class PrivateCopyAuditSnapshot(val items: List<PrivateCopy>,val unavailableCapabilities: Set<String>)
class PrivateCopyAudit(context: Context) {
    private val root = context.noBackupFilesDir
    private val maintenance = PrivateFileMaintenance.forContext(context)
    fun inspect(live: Dataset): PrivateCopyAuditSnapshot {
        val items = mutableListOf<PrivateCopy>()
        fun unsafe(file: File): Boolean {
            var p: File? = file
            while(p != null && p.toPath().startsWith(root.toPath())) {
                if(Files.isSymbolicLink(p.toPath())) return true
                if(p == root) break
                p = p.parentFile
            }
            return p != root
        }
        fun add(file: File,kind: CopyKind) {
            val blockers = mutableSetOf(CopyBlocker.PATH_NOT_SAFE) // no verified Android descriptor binding
            val warnings = mutableSetOf<CopyWarning>();var state = "UNKNOWN";val members = mutableListOf<CopyMember>();val missing = mutableSetOf<String>()
            val id = sha256(root.toPath().relativize(file.toPath()).toString().toByteArray())
            blockers += maintenance.blockers(id)
            try {
                ensure(!unsafe(file),"Unsafe copy path")
                val files = if(file.isDirectory) {
                    val children = file.listFiles() ?: fail(ErrorCode.STORAGE,"Directory unavailable")
                    ensure(children.all { !unsafe(it) && Files.isRegularFile(it.toPath(),LinkOption.NOFOLLOW_LINKS) },"Unknown nested/special copy member")
                    children.toList()
                } else listOf(file)
                for(child in files.sortedBy { it.name }) {
                    ensure(Files.isRegularFile(child.toPath(),LinkOption.NOFOLLOW_LINKS) && !unsafe(child),"Unsafe copy member")
                    val attributes = runCatching { Files.getAttribute(child.toPath(),"unix:nlink",LinkOption.NOFOLLOW_LINKS) as Number }.getOrNull()
                    if(attributes == null || attributes.toLong() > 1) blockers += CopyBlocker.HARDLINK_GRAPH_INCOMPLETE
                    val digest = Files.newInputStream(child.toPath(),LinkOption.NOFOLLOW_LINKS).use { input ->
                        val hash = java.security.MessageDigest.getInstance("SHA-256");val buffer = ByteArray(65536)
                        while(true) { val n = input.read(buffer);if(n < 0) break;if(n > 0) hash.update(buffer,0,n) }
                        hash.digest().joinToString("") { "%02x".format(it) }
                    }
                    members += CopyMember(sha256((id+":"+child.name).toByteArray()),child.length(),digest)
                }
                when(kind) {
                    CopyKind.EXPORT -> {
                        ensure(files.map { it.name }.toSet() == setOf("operation.json","data"),"Unknown export group")
                        val op = codec.decodeFromString<ExportOperation>(File(file,"operation.json").readText())
                        ensure(op.id == file.name && op.bytes == File(file,"data").length() && op.sha256 == fileHash(File(file,"data")),"Export manifest differs")
                        state = op.state
                        if(state !in setOf("COMPLETE","CANCELLED")) blockers += CopyBlocker.IN_PROGRESS
                        if(!op.resultReceived) blockers += CopyBlocker.CHOOSER_RESULT_PENDING
                        // Coordination currently belongs to ViewModel; no trustworthy common lease read.
                        blockers += CopyBlocker.UNKNOWN_FILE_OR_STATE
                        if(state == "CANCELLED") warnings += CopyWarning.EXPORT_RETRY_AVAILABLE
                    }
                    CopyKind.ROLLBACK -> {
                        val backup = StreamingBackup.read { file.inputStream() };state = "VALIDATED"
                        missing += backup.data.sessions.map { it.id }.toSet()-live.sessions.map { it.id }.toSet()
                        missing += backup.data.segments.map { it.id }.toSet()-live.segments.map { it.id }.toSet()
                        warnings += CopyWarning.SNAPSHOT_MAY_CONTAIN_REMOVED_RECORDS
                        blockers += CopyBlocker.UNKNOWN_FILE_OR_STATE // rescue references not yet registered
                    }
                    CopyKind.RESTORE_INPUT -> { state = "REFERENCE_STATE_UNKNOWN";blockers += CopyBlocker.RESTORE_SOURCE_IN_USE;warnings += CopyWarning.RESTORE_SOURCE_WILL_BE_DISCARDED }
                    CopyKind.FORM -> { state = "REFERENCE_STATE_UNKNOWN";blockers += CopyBlocker.LIVE_FORM_REFERENCE;warnings += CopyWarning.FORM_DRAFT_WILL_BE_DISCARDED }
                    CopyKind.DATABASE_ARCHIVE -> { state = "SEALED_GRAPH_NOT_PROVEN";blockers += CopyBlocker.HARDLINK_GRAPH_INCOMPLETE;warnings += CopyWarning.ARCHIVE_MAY_CONTAIN_REMOVED_RECORDS }
                    CopyKind.RECOVERY_JOURNAL -> { state = "PENDING";blockers += CopyBlocker.REQUIRED_BY_PENDING_RECOVERY }
                }
            } catch(_: Exception) { blockers += CopyBlocker.UNKNOWN_FILE_OR_STATE }
            items += PrivateCopy(id,kind,state,members.sumOf { it.bytes },sha256(codec.encodeToString(members).toByteArray()),members,blockers,warnings,missing)
        }
        for((name,kind) in listOf("exports" to CopyKind.EXPORT,"rollback" to CopyKind.ROLLBACK,"database-archives" to CopyKind.DATABASE_ARCHIVE,"restore-inputs" to CopyKind.RESTORE_INPUT,"forms" to CopyKind.FORM)) {
            val directory = File(root,name)
            if(directory.exists()) {
                if(unsafe(directory)) add(directory,kind) else directory.listFiles()?.sortedBy { it.name }?.forEach { add(it,kind) }
            }
        }
        File(root,"recovery-pending.json").takeIf { it.exists() }?.let { add(it,CopyKind.RECOVERY_JOURNAL) }
        val copies = items.count { it.kind in setOf(CopyKind.ROLLBACK,CopyKind.DATABASE_ARCHIVE) }
        return PrivateCopyAuditSnapshot(items.map { if(copies == 1 && it.kind in setOf(CopyKind.ROLLBACK,CopyKind.DATABASE_ARCHIVE)) it.copy(confirmableWarnings = it.confirmableWarnings+CopyWarning.LAST_RECOVERY_COPY) else it },
            setOf("ANDROID_DESCRIPTOR_BINDING_QA","FRONTEND_DURABLE_REFERENCE_ADOPTION","LEGACY_INVENTORY_REGISTRATION","SEALED_ARCHIVE_HARDLINK_GRAPH"))
    }
}
