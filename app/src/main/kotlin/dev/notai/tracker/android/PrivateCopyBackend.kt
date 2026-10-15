package dev.notai.tracker.android

import android.content.Context
import android.os.Process
import android.system.Os
import dev.notai.tracker.*
import java.io.File

data class PrivateCopyBackendInspection(val inventory: CopyInventory?,val registration: CopyRegistrationReport?,val unavailableCapabilities: Set<String>)

/** Android Context creates no_backup as0771. Its own app group is trusted; another
 * group must have no access. Others may search but cannot list/write the root.
 * This checks authority, not a substitute for pinned no-follow descriptor identity. */
internal fun trustedPrivateCopyRoot(uid: Int,gid: Int,mode: Int,appUid: Int): Boolean =
    uid == appUid && mode and 0xf000 == 0x4000 && mode and 0x1c0 == 0x1c0 &&
        mode and 6 == 0 && (gid == appUid || mode and 0x38 == 0)

/** Backend-only Context binding. Callers supply opaque operation IDs, never roots or chooser paths.
 * Canonicalization is limited to Android's own application directories; the opened no-follow root
 * descriptor must match the checked private UID/device/inode. This wires the implementation;
 * Android validation is scoped to recorded devices; frontend lifecycle adoption stays explicit.
 * Opening a session never clears protective references or continues interrupted deletion.
 */
object PrivateCopyBackend {
    internal fun legacyScope(context: Context,gate: CopyMaintenance): Set<String> = root(context).use { PrivateCopyCatalog(it,gate).candidateIds() }
    /** Concrete backend binding, without a blanket feature switch. Every file/root/reference
     * check still applies; the legacy UI witness is preserved until its actual owner adopts it. */
    fun openSession(context: Context): PrivateCopySession {
        val gate = PrivateFileMaintenance.forContext(context)
        return try { gate.locked { PrivateFileMaintenance.legacy(gate);PrivateCopySession(root(context),gate) } }
        catch(_: LinkageError) { throw CoreError(ErrorCode.STORAGE,"Private-copy descriptor binding unavailable") }
        catch(e: CoreError) { throw e }
        catch(_: Exception) { throw CoreError(ErrorCode.STORAGE,"Private-copy root or registry unavailable") }
    }
    private fun root(context: Context): DescriptorCopyFiles {
        val app = context.applicationContext
        val data = File(app.applicationInfo.dataDir).canonicalFile
        val root = app.noBackupFilesDir.canonicalFile
        check(root.parentFile == data && root.name == "no_backup") { "Unexpected Android private root" }
        val parent = Os.lstat(data.path);val before = Os.lstat(root.path)
        check(parent.st_uid == Process.myUid() && before.st_dev == parent.st_dev &&
            trustedPrivateCopyRoot(before.st_uid,before.st_gid,before.st_mode,Process.myUid())) { "Untrusted Android private root" }
        val files = DescriptorCopyFiles(root.path)
        try {
            val actual = files.rootIdentity();val after = Os.lstat(root.path)
            check(actual.directory && actual.device == before.st_dev && actual.inode == before.st_ino && before.st_dev == after.st_dev && before.st_ino == after.st_ino &&
                before.st_uid == after.st_uid && before.st_gid == after.st_gid && before.st_mode == after.st_mode &&
                trustedPrivateCopyRoot(after.st_uid,after.st_gid,after.st_mode,Process.myUid())) { "Android private root changed" }
            return files
        } catch(e: Throwable) { files.close();throw e }
    }
    private fun <T> catalog(context: Context,block: (PrivateCopyCatalog)->T): T {
        val maintenance = PrivateFileMaintenance.forContext(context)
        return maintenance.locked { root(context).use { block(PrivateCopyCatalog(it,maintenance)) } }
    }
    /** Inventory never seals groups or resumes deletion. */
    fun inspect(context: Context): PrivateCopyBackendInspection = inspectBinding(context,false)
    /** Separate explicit, non-destructive legacy registration/reconciliation. */
    fun registerLegacy(context: Context): PrivateCopyBackendInspection = inspectBinding(context,true)
    private fun inspectBinding(context: Context,register: Boolean): PrivateCopyBackendInspection = try {
        val gate = PrivateFileMaintenance.forContext(context)
        if(register) gate.locked { PrivateFileMaintenance.legacy(gate) }
        val pending = if(register) DatabaseRecovery.protectedArchiveIds(context) else emptySet()
        catalog(context) { c ->
            val registered = if(register) c.registerLegacy(pending) { codec.decodeFromString<FormState>(it) } else null
            if(registered != null) releaseReady(gate,registered.registeredIds.toSet())
            PrivateCopyBackendInspection(c.inventory(),registered,setOf("ANDROID_DESCRIPTOR_DEVICE_QA")+
                if(gate.state().lifecycleVersion == 0) setOf("DURABLE_LIFECYCLE_CUTOVER_UNAVAILABLE") else emptySet())
        }
    } catch(_: LinkageError) { PrivateCopyBackendInspection(null,null,setOf("ANDROID_DESCRIPTOR_BINDING_UNAVAILABLE")) }
      catch(_: Exception) { PrivateCopyBackendInspection(null,null,setOf("PRIVATE_ROOT_OR_REGISTRY_UNAVAILABLE")) }
    internal fun registerRollback(context: Context,name: String) = bestEffort { catalog(context) { it.registerRollback(name) } }
    internal fun registerExport(context: Context,id: String) = bestEffort { catalog(context) { it.registerExport(id) } }
    internal fun registerForm(context: Context,id: String) = bestEffort { catalog(context) { it.registerForm("$id.json") { text -> codec.decodeFromString<FormState>(text) } } }
    internal fun registerRestoreInput(context: Context,name: String) = bestEffort { catalog(context) { it.registerRestoreInput(name) } }
    internal fun finalizeArchive(context: Context,id: String) = bestEffort { catalog(context) { c ->
        c.finalizeArchive(id)
        check(itemId("database-archives/$id") in c.registerFinalizedArchives().registeredIds) { "Archive graph registration deferred" }
    } }
    /** Lack of a verified native binding does not undo a successfully saved backup/export.
     * Its bytes stay unregistered/protected; inspect exposes the unavailable capability and retries.
     * Do not translate this best-effort catalog result into successful deletion authority. */
    private fun bestEffort(block: ()->Any?): Boolean {
        try { block();return true }
        catch(_: LinkageError) { android.util.Log.w("PrivateCopies","Descriptor registration unavailable; saved bytes retained");return false }
        catch(_: Exception) { android.util.Log.w("PrivateCopies","Registration deferred; saved bytes retained and cleanup remains protected");return false }
    }
    internal fun itemId(relative: String) = sha256(relative.toByteArray(Charsets.UTF_8))
    internal fun archiveDraft(context: Context,id: String): CopyReference = PrivateFileMaintenance.forContext(context)
        .acquire("archive-draft:$id",itemId("database-archives/$id"),CopyBlocker.REQUIRED_BY_PENDING_RECOVERY)
    internal fun completeArchive(context: Context,id: String) {
        val gate = PrivateFileMaintenance.forContext(context)
        // Logical retirement completion is durable even if the native binding is temporarily
        // unavailable. A later non-destructive inspection may finish sealing this exact owner.
        gate.locked {
            if(gate.state().references.any { it.owner == "archive-draft:$id" }) gate.update { state ->
                state.copy(references = state.references.map { if(it.owner == "archive-draft:$id") it.copy(owner = "archive-ready:$id") else it })
            }
        }
        if(finalizeArchive(context,id)) releaseReady(gate,setOf(itemId("database-archives/$id")))
    }
    private fun releaseReady(gate: CopyMaintenance,registeredIds: Set<String>) = gate.locked {
        gate.state().references.filter { it.owner.startsWith("archive-ready:") && it.itemId in registeredIds &&
            it.itemId == itemId("database-archives/"+it.owner.removePrefix("archive-ready:")) }.forEach(gate::release)
    }
    /** Opus holds the returned durable token through chooser/form/preview lifetime and explicitly
     * releases that exact token on abandonment/completion. Process death does not expire it. */
    fun retainForm(context: Context,id: String): CopyReference = retain(context,"forms/$id.json",CopyBlocker.LIVE_FORM_REFERENCE)
    fun retainRestoreInput(context: Context,id: String): CopyReference = retain(context,"restore-inputs/$id.json",CopyBlocker.RESTORE_SOURCE_IN_USE)
    fun retainExportChooser(context: Context,id: String): CopyReference = retain(context,"exports/$id",CopyBlocker.CHOOSER_RESULT_PENDING)
    private fun retain(context: Context,relative: String,blocker: CopyBlocker): CopyReference {
        val name = relative.substringAfter('/').removeSuffix(".json")
        require(name.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        return PrivateFileMaintenance.forContext(context).acquire("frontend:$relative",itemId(relative),blocker)
    }
    fun release(context: Context,reference: CopyReference) = PrivateFileMaintenance.forContext(context).release(reference)
}
