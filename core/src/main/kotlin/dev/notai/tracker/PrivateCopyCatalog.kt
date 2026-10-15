package dev.notai.tracker

import kotlinx.serialization.Required
import kotlinx.serialization.Serializable

@Serializable data class PreservedDatabaseMember(val name: String,val bytes: Long,val sha256: String)
@Serializable data class StoredDatabaseArchive(val id: String,val database: String,val files: List<PreservedDatabaseMember>)
@Serializable private data class ArchiveSealMember(val path: String,val bytes: Long,val sha256: String)
@Serializable private data class DatabaseArchiveSeal(@Required val format: Int = 1,val archiveId: String,
    val sourceManifestSha256: String,val members: List<ArchiveSealMember>,val directories: Set<String>)
@Serializable private data class StagedExportRecord(val id: String,val mime: String,val title: String,val state: String = "PREPARING",
    val bytes: Long = 0,val sha256: String? = null,val error: String? = null,val resultReceived: Boolean = false)
data class CopyRegistrationFailure(val itemId: String,val blockers: Set<CopyBlocker>)
data class CopyRegistrationReport(val registeredIds: List<String>,val refused: List<CopyRegistrationFailure>)

/** Non-destructive backend catalog bridge. Known roots only, no SAF originals. It validates
 * legacy bytes before registration and seals archives only when the backend declares retirement
 * finished. Registration may coexist with protective references; it never releases those refs.
 * A complete hardlink component is registered in one existing storage transaction.
 */
class PrivateCopyCatalog(private val files: DescriptorCopyFiles,private val gate: CopyMaintenance) {
    private val storage = RegisteredCopyStorage(files,gate)
    private fun uuid(value: String) = ensure(value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")),"Invalid private operation ID")
    private fun id(path: String) = sha256(path.toByteArray(Charsets.UTF_8))
    private fun member(path: String): CopyMember = files.inspectMember(path,id(path)).first
    private fun text(path: String): String = CopyMetadataBytes.decode(files.read(path))
    private fun item(path: String,kind: CopyKind,state: String,members: List<CopyMember>,warnings: Set<CopyWarning>) =
        PrivateCopy(id(path),kind,state,members.sumOf { it.bytes },payloadHash(members),members,confirmableWarnings = warnings)
    private fun backup(path: String): Backup {
        val before = member(path)
        val value = StreamingBackup.read { files.input(path) }
        ensure(member(path) == before,"Private backup changed during validation",ErrorCode.STALE)
        return value
    }
    fun registerRollback(name: String): String = gate.locked {
        ensure(name.startsWith("rollback-") && name.endsWith(".json"),"Invalid rollback name");uuid(name.removePrefix("rollback-").removeSuffix(".json"))
        val path = "rollback/$name";backup(path)
        val m = member(path);val i = item(path,CopyKind.ROLLBACK,"VALIDATED",listOf(m),setOf(CopyWarning.SNAPSHOT_MAY_CONTAIN_REMOVED_RECORDS))
        storage.registerValidated(i,path,false,mapOf(m.opaqueId to name));i.id
    }
    fun registerRestoreInput(name: String): String = gate.locked {
        ensure(name.endsWith(".json"),"Invalid restore input name");uuid(name.removeSuffix(".json"))
        // Staging intentionally accepts arbitrary source bytes, including a refused/malformed
        // import. Discarding an orphan copy does not require treating it as a valid backup.
        // Known root/name + descriptor manifest + durable ownership registry grant authority.
        val path = "restore-inputs/$name"
        val m = member(path);val i = item(path,CopyKind.RESTORE_INPUT,"STAGED_INPUT",listOf(m),setOf(CopyWarning.RESTORE_SOURCE_WILL_BE_DISCARDED))
        storage.registerValidated(i,path,false,mapOf(m.opaqueId to name));i.id
    }
    fun registerForm(name: String,validate: (String)->Unit): String = gate.locked {
        ensure(name.endsWith(".json"),"Invalid form name");uuid(name.removeSuffix(".json"))
        val path = "forms/$name";val before = member(path);validate(text(path));ensure(member(path) == before,"Form changed",ErrorCode.STALE)
        val i = item(path,CopyKind.FORM,"VALIDATED",listOf(before),setOf(CopyWarning.FORM_DRAFT_WILL_BE_DISCARDED))
        storage.registerValidated(i,path,false,mapOf(before.opaqueId to name));i.id
    }
    fun registerExport(operationId: String): String = gate.locked {
        uuid(operationId);val path = "exports/$operationId"
        val names = files.children(path)
        ensure("operation.json" in names && names.all { it in setOf("data","data.part","operation.json") },"Unknown export group member")
        val op = codec.decodeFromString<StagedExportRecord>(text("$path/operation.json"))
        ensure(op.id == operationId && op.state in setOf("COMPLETE","CANCELLED") && op.resultReceived,"Export or chooser is still active")
        val members = names.map { member("$path/$it") }
        val data = members.zip(names).singleOrNull { it.second == "data" }?.first
        val prepared = data != null && data.bytes == op.bytes && data.sha256 == op.sha256 && "data.part" !in names
        if(op.state == "COMPLETE") ensure(prepared && names == listOf("data","operation.json"),"Completed export differs from receipt",ErrorCode.STALE)
        val warnings = when {
            op.state == "COMPLETE" -> emptySet()
            prepared -> setOf(CopyWarning.EXPORT_RETRY_AVAILABLE)
            else -> setOf(CopyWarning.INCOMPLETE_EXPORT_STAGING)
        }
        val i = item(path,CopyKind.EXPORT,op.state,members,warnings)
        storage.registerValidated(i,path,true,members.zip(names).associate { it.first.opaqueId to it.second });i.id
    }
    /** Backend calls AFTER every original/retired member is installed and fsynced. Pending rescue
     * archives must be excluded by its durable job/ref registry. This only adds a seal manifest. */
    fun finalizeArchive(archiveId: String): String = gate.locked {
        uuid(archiveId);val path = "database-archives/$archiveId"
        val manifestPath = "$path/manifest.json";val source = member(manifestPath)
        val archive = codec.decodeFromString<StoredDatabaseArchive>(text(manifestPath))
        ensure(archive.id == archiveId && archive.database.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")),"Invalid archive identity")
        val allowed = listOf("","-wal","-shm","-journal").map { archive.database+it }.toSet()
        ensure(archive.files.map { it.name }.distinct().size == archive.files.size && archive.files.all { it.name in allowed && it.bytes >= 0 && it.sha256.matches(Regex("[0-9a-f]{64}")) },"Invalid archive source manifest")
        val names = files.children(path).toSet();val dirs = if("retired" in names) setOf("retired") else emptySet()
        val expected = archive.files.map { it.name }.toSet()+"manifest.json"+dirs
        ensure(names-"cleanup-seal.json" == expected,"Unknown/missing archive child")
        val retired = if(dirs.isNotEmpty()) files.children("$path/retired").also { ensure(it.all { n -> n in allowed },"Unknown retired file") }.map { "retired/$it" } else emptyList()
        val relative = (archive.files.map { it.name }+"manifest.json"+retired).sorted()
        val members = relative.map { n -> member("$path/$n").let { ArchiveSealMember(n,it.bytes,it.sha256) } }
        archive.files.forEach { m -> ensure(members.single { it.path == m.name }.let { it.bytes == m.bytes && it.sha256 == m.sha256 },"Archive preservation receipt differs",ErrorCode.STALE) }
        ensure(member(manifestPath) == source,"Archive source manifest changed",ErrorCode.STALE)
        val seal = DatabaseArchiveSeal(archiveId = archiveId,sourceManifestSha256 = source.sha256,members = members,directories = dirs)
        val metadata = files.metadata("$path/cleanup-seal.json")
        val existing = metadata.read()
        if(existing == null) metadata.writeDurably(codec.encodeToString(seal))
        else ensure(codec.decodeFromString<DatabaseArchiveSeal>(existing) == seal,"Final archive changed after sealing",ErrorCode.STALE)
        files.sync(path);files.sync("database-archives")
        archiveId
    }
    private fun sealedArchive(archiveId: String): ArchiveCopyManifest {
        uuid(archiveId);val path = "database-archives/$archiveId"
        val seal = codec.decodeFromString<DatabaseArchiveSeal>(text("$path/cleanup-seal.json"))
        ensure(seal.format == 1 && seal.archiveId == archiveId,"Invalid final archive seal")
        // Verify against the original manifest and exact current forest without rewriting a seal.
        finalizeArchive(archiveId)
        val names = (seal.members.map { it.path }+"cleanup-seal.json").sorted()
        val members = names.map { member("$path/$it") }
        val i = item(path,CopyKind.DATABASE_ARCHIVE,"SEALED",members,setOf(CopyWarning.ARCHIVE_MAY_CONTAIN_REMOVED_RECORDS))
        return ArchiveCopyManifest(i,path,members.zip(names).associate { it.first.opaqueId to it.second },seal.directories)
    }
    private fun registerComponents(archives: List<ArchiveCopyManifest>): CopyRegistrationReport {
        val parent = IntArray(archives.size) { it }
        fun root(i: Int): Int { var n = i;while(parent[n] != n) { parent[n] = parent[parent[n]];n = parent[n] };return n }
        val owners = mutableMapOf<Pair<Long,Long>,Int>()
        for((i,a) in archives.withIndex()) for(name in a.memberPaths.values) {
            val node = files.inspect("${a.path}/$name")!!.node()
            val other = owners.putIfAbsent(node,i)
            if(other != null) parent[root(i)] = root(other)
        }
        val registered = mutableListOf<String>();val refused = mutableListOf<CopyRegistrationFailure>()
        for(component in archives.indices.groupBy(::root).values) {
            val batch = component.map { archives[it] }
            try { storage.registerValidatedArchiveGraph(batch);registered += batch.map { it.item.id } }
            catch(_: Exception) { refused += batch.map { CopyRegistrationFailure(it.item.id,setOf(CopyBlocker.HARDLINK_GRAPH_INCOMPLETE)) } }
        }
        return CopyRegistrationReport(frozenList(registered),frozenList(refused))
    }
    fun registerFinalizedArchives(): CopyRegistrationReport = gate.locked {
        if(files.inspect("database-archives") == null) return@locked CopyRegistrationReport(emptyList(),emptyList())
        val failures = mutableListOf<CopyRegistrationFailure>();val batch = mutableListOf<ArchiveCopyManifest>()
        for(name in files.children("database-archives")) try {
            if(files.inspect("database-archives/$name/cleanup-seal.json") != null) batch += sealedArchive(name)
        } catch(_: Exception) { failures += CopyRegistrationFailure(id("database-archives/$name"),setOf(CopyBlocker.UNKNOWN_FILE_OR_STATE)) }
        val result = registerComponents(batch)
        result.copy(refused = frozenList(result.refused+failures))
    }
    /** Explicit, non-destructive legacy inventory pass. Unknown/incomplete groups fail individually;
     * pending recovery source/archives remain protected and no orphan status is inferred from age.
     * Existing registry entries are verified idempotently rather than replaced with new bytes. */
    fun registerLegacy(pendingArchiveIds: Set<String>,validateForm: (String)->Unit): CopyRegistrationReport = gate.locked {
        val registered = mutableListOf<String>();val failures = mutableListOf<CopyRegistrationFailure>()
        fun attempt(path: String,block: ()->String) {
            try { registered += block() } catch(_: Exception) { failures += CopyRegistrationFailure(id(path),setOf(CopyBlocker.UNKNOWN_FILE_OR_STATE)) }
        }
        for(root in listOf("rollback","restore-inputs","forms","exports")) {
            if(files.inspect(root) == null) continue
            val children = try { files.children(root) } catch(_: Exception) { failures += CopyRegistrationFailure(id(root),setOf(CopyBlocker.PATH_NOT_SAFE));continue }
            for(name in children) attempt("$root/$name") { when(root) {
                "rollback" -> registerRollback(name);"restore-inputs" -> registerRestoreInput(name)
                "forms" -> registerForm(name,validateForm);else -> registerExport(name)
            } }
        }
        val archives = mutableListOf<ArchiveCopyManifest>()
        if(files.inspect("database-archives") != null) for(name in files.children("database-archives")) {
            val path = "database-archives/$name"
            val references = gate.state().references.filter { it.itemId == id(path) && it.owner != "archive-ready:$name" }.map { it.blocker }.toSet()
            if(name in pendingArchiveIds || references.isNotEmpty()) {
                failures += CopyRegistrationFailure(id(path),references+CopyBlocker.REQUIRED_BY_PENDING_RECOVERY);continue
            }
            try { finalizeArchive(name);archives += sealedArchive(name) }
            catch(_: Exception) { failures += CopyRegistrationFailure(id(path),setOf(CopyBlocker.UNKNOWN_FILE_OR_STATE)) }
        }
        val archiveResult = registerComponents(archives)
        registered += archiveResult.registeredIds;failures += archiveResult.refused
        CopyRegistrationReport(frozenList(registered),frozenList(failures))
    }
    fun inventory(): CopyInventory = storage.inventory()
}
