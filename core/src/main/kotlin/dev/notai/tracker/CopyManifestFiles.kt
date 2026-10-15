package dev.notai.tracker

/** All names originate in a backend-validated manifest, never a chooser/UI path. */
data class ArchiveCopyManifest(val item: PrivateCopy,val path: String,
    val memberPaths: Map<String,String>,val directories: Set<String>)
internal data class CopyLocation(val path: String?,val partial: Boolean)
internal data class ManifestRead(val members: List<CopyMember>,val identities: Map<String,FileIdentity>)
internal class ArchiveGraphRefusal(val blockers: Set<CopyBlocker>): IllegalStateException("Archive graph is protected")
internal fun FileIdentity.sameFile(other: FileIdentity) =
    device == other.device && inode == other.inode && mode == other.mode && bytes == other.bytes && modifiedNanos == other.modifiedNanos
internal fun FileIdentity.node() = device to inode

/** One manifest reader for flat copies and sealed archive forests. No traversal of unknown children. */
internal class CopyManifestFiles(private val files: DescriptorCopyFiles) {
    private fun component(s: String) = s.isNotEmpty() && s !in setOf(".","..") && s.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-" }
    private fun relative(s: String) = s.split('/').size <= 32 && s.split('/').all(::component)
    private fun schema(r: RegisteredCopy) {
        val root = when(r.item.kind) {
            CopyKind.EXPORT -> "exports";CopyKind.ROLLBACK -> "rollback";CopyKind.DATABASE_ARCHIVE -> "database-archives"
            CopyKind.RESTORE_INPUT -> "restore-inputs";CopyKind.FORM -> "forms";CopyKind.RECOVERY_JOURNAL -> ""
        }
        ensure(root.isNotEmpty() && r.path.substringBefore('/') == root && r.path.split('/').size == 2 && relative(r.path),"Unsafe registry root")
        val ids = r.item.members.map { it.opaqueId }
        ensure(ids.all { it.isNotBlank() } && ids.distinct().size == ids.size && "@group" !in ids && r.memberNames.keys == ids.toSet() && r.identities.keys == ids.toSet()+"@group","Invalid registered members")
        ensure(r.item.members.all { it.bytes >= 0 && it.sha256.matches(Regex("[0-9a-f]{64}")) } &&
            r.item.bytes == r.item.members.fold(0L) { n,m -> Math.addExact(n,m.bytes) } && r.item.sha256 == payloadHash(r.item.members),"Registered digest changed")
        ensure(r.memberNames.values.distinct().size == ids.size && r.memberNames.values.all(::relative) && r.directories.keys.all(::relative),"Unsafe registered path")
        ensure(r.directories.keys.intersect(r.memberNames.values.toSet()).isEmpty(),"Invalid registered forest")
        ensure((r.directories.keys+r.memberNames.values).all { it.substringBeforeLast('/',"").let { p -> p.isEmpty() || p in r.directories } },"Missing registered directory")
        ensure(!r.sealedArchive || r.directory && r.item.kind == CopyKind.DATABASE_ARCHIVE && r.item.state == "SEALED","Unsealed graph owner")
        ensure(r.sealedArchive || r.directories.isEmpty() && r.memberNames.values.none { '/' in it },"Unsealed nested owner")
        ensure(r.directory || r.memberNames.values.singleOrNull() == r.path.substringAfterLast('/'),"Invalid single file")
    }
    fun capture(item: PrivateCopy,path: String,directory: Boolean,memberNames: Map<String,String>,directories: Set<String>,sealed: Boolean): RegisteredCopy {
        ensure(item.id.isNotBlank() && item.selectable && item.members.map { it.opaqueId }.distinct().size == item.members.size,"Invalid copy manifest")
        ensure(item.members.none { it.opaqueId == "@group" } && memberNames.keys == item.members.map { it.opaqueId }.toSet(),"Invalid member mapping")
        ensure(path.split('/').size == 2 && relative(path),"Invalid group path")
        ensure(memberNames.values.distinct().size == memberNames.size && memberNames.values.all(::relative),"Invalid member paths")
        ensure(directories.size+memberNames.size <= 100_000 && directories.all(::relative),"Invalid directory manifest")
        ensure(directories.intersect(memberNames.values.toSet()).isEmpty(),"Directory is also a file")
        ensure(directories.all { it.substringBeforeLast('/',"").let { p -> p.isEmpty() || p in directories } } &&
            memberNames.values.all { it.substringBeforeLast('/',"").let { p -> p.isEmpty() || p in directories } },"Missing parent directory")
        ensure(directory || (directories.isEmpty() && memberNames.size == 1 && memberNames.values.single() == path.substringAfterLast('/')),"Invalid single-file group")
        ensure(sealed || (directories.isEmpty() && memberNames.values.none { '/' in it }),"Nested manifests require sealed archives")
        ensure(!sealed || directory && item.kind == CopyKind.DATABASE_ARCHIVE && item.state == "SEALED","Only sealed archives may share inodes")
        val root = files.stat(path) ?: fail(ErrorCode.STORAGE,"Copy missing")
        ensure(if(directory) root.directory else root.regular,"Group kind changed")
        val dirs = directories.sorted().associateWith { name ->
            (files.stat("$path/$name") ?: fail(ErrorCode.STORAGE,"Directory missing")).also { ensure(it.directory,"Unsafe directory") }
        }
        val identities = item.members.associate { m ->
            val full = if(directory) "$path/${memberNames.getValue(m.opaqueId)}" else path
            val identity = files.stat(full) ?: fail(ErrorCode.STORAGE,"Member missing")
            val (read,now) = files.member(full,m.opaqueId,if(sealed) identity.links else 1)
            ensure(read == m,"Validated manifest differs",ErrorCode.STALE);m.opaqueId to now
        }+("@group" to root)
        ensure(item.bytes == item.members.fold(0L) { sum,m -> Math.addExact(sum,m.bytes) } && item.sha256 == payloadHash(item.members),"Invalid group digest")
        return RegisteredCopy(item,path,directory,memberNames,identities,dirs,sealed).also { read(it,CopyLocation(path,false)) }
    }
    fun read(r: RegisteredCopy,location: CopyLocation): ManifestRead {
        schema(r)
        val path = location.path ?: return ManifestRead(emptyList(),emptyMap())
        val partial = location.partial
        fun dir(relative: String,expected: FileIdentity) {
            val full = if(relative.isEmpty()) path else "$path/$relative"
            val current = files.stat(full)
            if(current == null) { ensure(partial,"Directory missing",ErrorCode.STALE);return }
            ensure(current.directory && current.node() == expected.node() && current.mode == expected.mode,"Directory identity changed",ErrorCode.STALE)
            val allowed = (r.memberNames.values+r.directories.keys).filter { it.substringBeforeLast('/',"") == relative }.map { it.substringAfterLast('/') }.sorted()
            val names = files.names(full)
            ensure(names.all { it in allowed } && (partial || names == allowed),"Unknown/missing manifest child",ErrorCode.STALE)
        }
        if(r.directory) {
            dir("",r.identities.getValue("@group"))
            r.directories.forEach { (name,identity) -> dir(name,identity) }
        }
        val identities = mutableMapOf<String,FileIdentity>()
        val members = r.item.members.mapNotNull { m ->
            val full = if(r.directory) "$path/${r.memberNames.getValue(m.opaqueId)}" else path
            val identity = files.stat(full)
            if(identity == null) { ensure(partial,"Member missing",ErrorCode.STALE);return@mapNotNull null }
            val (member,now) = files.member(full,m.opaqueId,if(r.sealedArchive) identity.links else 1)
            ensure(member == m && now.sameFile(r.identities.getValue(m.opaqueId)),"Member changed",ErrorCode.STALE)
            identities[m.opaqueId] = now;member
        }
        return ManifestRead(members,identities)
    }
    fun component(all: List<RegisteredCopy>,r: RegisteredCopy): List<RegisteredCopy> {
        val ids = mutableSetOf(r.item.id);val nodes = mutableSetOf<Pair<Long,Long>>()
        do {
            val before = ids.size
            all.filter { it.sealedArchive && it.item.id in ids }.forEach { row -> row.item.members.forEach { nodes += row.identities.getValue(it.opaqueId).node() } }
            all.filter { row -> row.sealedArchive && row.item.members.any { row.identities.getValue(it.opaqueId).node() in nodes } }.forEach { ids += it.item.id }
        } while(ids.size != before)
        return all.filter { it.item.id in ids }
    }
    fun prove(all: List<RegisteredCopy>,r: RegisteredCopy,references: List<CopyReference>,locate: (RegisteredCopy)->CopyLocation): Map<String,ManifestRead> {
        val component = component(all,r)
        val locations = component.associate { it.item.id to locate(it) }
        val live = component.filter { locations.getValue(it.item.id).path != null }
        val blockers = references.filter { it.itemId == null || it.itemId in live.map { c -> c.item.id } }.map { it.blocker }.toSet()+live.flatMap { it.item.hardBlockers }
        if(blockers.isNotEmpty()) throw ArchiveGraphRefusal(blockers)
        val reads = component.associate { it.item.id to read(it,locations.getValue(it.item.id)) }
        val aliases = reads.values.flatMap { it.identities.values }.groupBy { it.node() }
        ensure(aliases.values.all { values -> values.all { it.links == values.size.toLong() } },"HARDLINK_GRAPH_INCOMPLETE",ErrorCode.STALE)
        return reads
    }
}
