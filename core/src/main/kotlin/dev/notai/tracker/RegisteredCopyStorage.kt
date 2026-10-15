package dev.notai.tracker

/** Backend-only binding of validated manifests to opaque IDs. This class never accepts UI paths.
 * Sealing is deliberately internal: platform catalog/backup validators supply the exact group.
 * Shared hardlinks are conservatively blocked until a complete sealed graph can be proven. */
class RegisteredCopyStorage(private val files: DescriptorCopyFiles,private val gate: CopyMaintenance): PrivateCopyStorage {
    override fun <T> maintenance(block: ()->T): T = gate.locked(block)
    private val roots = mapOf(CopyKind.EXPORT to "exports",CopyKind.ROLLBACK to "rollback",CopyKind.DATABASE_ARCHIVE to "database-archives",
        CopyKind.RESTORE_INPUT to "restore-inputs",CopyKind.FORM to "forms")
    internal fun seal(item: PrivateCopy,path: String,directory: Boolean,memberNames: Map<String,String>) = maintenance {
        ensure(item.selectable && item.id.isNotBlank() && path.substringBefore('/') == roots[item.kind],"Invalid sealed copy")
        ensure(path.split('/').size == 2 && memberNames.keys == item.members.map { it.opaqueId }.toSet(),"Invalid group mapping")
        ensure(memberNames.values.distinct().size == memberNames.size && memberNames.values.all { '/' !in it && it !in setOf(".","..","") },"Invalid member mapping")
        ensure(gate.blockers(item.id).isEmpty(),"Copy referenced")
        ensure(gate.state().journals.none { it.phase !in setOf(CleanupPhase.DONE,CleanupPhase.RESTORED) },"Cleanup interrupted")
        val rootIdentity = files.stat(path) ?: fail(ErrorCode.STORAGE,"Copy missing")
        ensure(if(directory) rootIdentity.directory else rootIdentity.regular,"Group kind changed")
        ensure(!directory || files.names(path) == memberNames.values.sorted(),"Unknown group member")
        ensure(directory || (memberNames.size == 1 && memberNames.values.single() == path.substringAfterLast('/')),"Invalid single-file group")
        val identities = item.members.associate { member ->
            val (read,identity) = files.member(if(directory) "$path/${memberNames.getValue(member.opaqueId)}" else path,member.opaqueId)
            ensure(read == member,"Validated manifest differs",ErrorCode.STALE);member.opaqueId to identity
        }+ ("@group" to rootIdentity)
        ensure(item.bytes == item.members.sumOf { it.bytes } && item.sha256 == payloadHash(item.members),"Invalid group digest")
        val registered = RegisteredCopy(item,path,directory,memberNames,identities)
        gate.update { state ->
            ensure(state.copies.none { it.path == path || it.item.id == item.id },"Copy already registered")
            state.copy(copies = state.copies+registered)
        };Unit
    }
    override fun inventory(): CopyInventory = maintenance {
        val state = gate.state();val interrupted = state.journals.any { it.phase !in setOf(CleanupPhase.DONE,CleanupPhase.RESTORED) }
        val items = state.copies.mapNotNull { record ->
            val identity = files.stat(record.path) ?: return@mapNotNull null
            val blockers = record.item.hardBlockers+gate.blockers(record.item.id)+
                (if(interrupted) setOf(CopyBlocker.CLEANUP_INTERRUPTED) else emptySet())+
                (if(files.stat("recovery-pending.json") != null) setOf(CopyBlocker.REQUIRED_BY_PENDING_RECOVERY) else emptySet())
            val checked = try { validate(record,record.path,false);emptySet() } catch(_: Exception) {
                if(identity.regular && identity.links != 1L || record.item.members.any { m ->
                    runCatching { files.stat(memberPath(record,record.path,m))?.links != 1L }.getOrDefault(true)
                }) setOf(CopyBlocker.HARDLINK_GRAPH_INCOMPLETE) else setOf(CopyBlocker.PATH_NOT_SAFE,CopyBlocker.UNKNOWN_FILE_OR_STATE)
            }
            record.item.copy(hardBlockers = blockers+checked)
        }.toMutableList()
        for((kind,root) in roots) if(files.stat(root) != null) for(name in files.names(root)) {
            val path = "$root/$name"
            if(state.copies.none { it.path == path }) items += PrivateCopy(payloadHash(path),kind,"UNREGISTERED",0,"",emptyList(),setOf(CopyBlocker.UNKNOWN_FILE_OR_STATE))
        }
        if(files.stat("recovery-pending.json") != null) items += PrivateCopy("recovery-journal",CopyKind.RECOVERY_JOURNAL,"PENDING",0,"",emptyList(),setOf(CopyBlocker.REQUIRED_BY_PENDING_RECOVERY))
        val recoveryCopies = items.count { it.kind in setOf(CopyKind.ROLLBACK,CopyKind.DATABASE_ARCHIVE) }
        CopyInventory(state.revision,System.currentTimeMillis(),items.map {
            if(recoveryCopies == 1 && it.kind in setOf(CopyKind.ROLLBACK,CopyKind.DATABASE_ARCHIVE)) it.copy(confirmableWarnings = it.confirmableWarnings+CopyWarning.LAST_RECOVERY_COPY) else it
        })
    }
    override fun operations(): List<CleanupJournal> = maintenance { gate.state().journals }
    override fun saveDurably(journal: CleanupJournal) = maintenance {
        ensure(journal.items.all { p -> record(p.item).item.copy(confirmableWarnings = p.item.confirmableWarnings) == p.item },"Journal manifest unknown")
        gate.update { it.copy(journals = it.journals.filterNot { j -> j.operationId == journal.operationId }+journal) };Unit
    }
    private fun record(item: PrivateCopy) = gate.state().copies.single { it.item.id == item.id }
    private fun operation(item: PrivateCopy,id: String): RegisteredCopy {
        ensure(gate.state().journals.single { it.operationId == id }.items.any { it.item == item },"Unjournaled copy")
        return record(item)
    }
    private fun quarantinePath(item: PrivateCopy,id: String) = "cleanup-quarantine/$id-${payloadHash(item.id).take(32)}"
    private fun memberPath(r: RegisteredCopy,path: String,m: CopyMember) = if(r.directory) "$path/${r.memberNames.getValue(m.opaqueId)}" else path
    private fun validate(r: RegisteredCopy,path: String,partial: Boolean): List<CopyMember> {
        val identity = files.stat(path) ?: return if(partial) emptyList() else fail(ErrorCode.STORAGE,"Copy missing")
        val expectedGroup = r.identities.getValue("@group")
        ensure(identity.device == expectedGroup.device && identity.inode == expectedGroup.inode && identity.mode == expectedGroup.mode,"Group identity changed",ErrorCode.STALE)
        val names = if(r.directory) files.names(path) else listOf(r.memberNames.values.single())
        ensure(names.all { it in r.memberNames.values } && (partial || names == r.memberNames.values.sorted()),"Unknown/missing group member",ErrorCode.STALE)
        return r.item.members.filter { r.memberNames.getValue(it.opaqueId) in names }.map { expected ->
            val (member,identityNow) = files.member(memberPath(r,path,expected),expected.opaqueId)
            ensure(member == expected && identityNow == r.identities.getValue(expected.opaqueId),"Member changed",ErrorCode.STALE);member
        }
    }
    override fun quarantine(item: PrivateCopy,operationId: String) = maintenance {
        val r = operation(item,operationId);val target = quarantinePath(item,operationId)
        ensure(gate.blockers(item.id).isEmpty() && files.stat("recovery-pending.json") == null,"Copy became referenced",ErrorCode.STALE)
        files.mkdir("cleanup-quarantine")
        if(files.stat(target) != null) { ensure(files.stat(r.path) == null,"Both group locations exist");validate(r,target,true) }
        else { validate(r,r.path,false);files.move(r.path,target);validate(r,target,false) }
        Unit
    }
    override fun remaining(item: PrivateCopy,operationId: String): List<CopyMember> = maintenance {
        val r = operation(item,operationId);val target = quarantinePath(item,operationId)
        if(files.stat(target) != null) { ensure(files.stat(r.path) == null,"Ambiguous group locations");validate(r,target,true) }
        else if(files.stat(r.path) != null) validate(r,r.path,false) else emptyList()
    }
    override fun isQuarantined(item: PrivateCopy,operationId: String): Boolean = maintenance {
        val r = operation(item,operationId);val target = quarantinePath(item,operationId)
        if(files.stat(target) != null) { ensure(files.stat(r.path) == null,"Ambiguous group locations");validate(r,target,true);true } else false
    }
    override fun removeMember(item: PrivateCopy,member: CopyMember,operationId: String) = maintenance {
        val r = operation(item,operationId);val target = quarantinePath(item,operationId)
        ensure(gate.blockers(item.id).isEmpty() && files.stat("recovery-pending.json") == null,"Copy became referenced",ErrorCode.STALE)
        ensure(member in validate(r,target,true),"Unknown member")
        files.remove(memberPath(r,target,member),expected = r.identities.getValue(member.opaqueId));Unit
    }
    override fun verifyAbsent(item: PrivateCopy,operationId: String): Boolean = maintenance {
        val r = operation(item,operationId);val target = quarantinePath(item,operationId)
        if(files.stat(r.path) != null) false else {
            if(files.stat(target) != null) {
                validate(r,target,true)
                // Finalizing an empty directory is a mutation: only explicit execute may remove it.
                return@maintenance false
            }
            files.sync(r.path.substringBeforeLast('/'));files.sync("cleanup-quarantine");true
        }
    }
    override fun finishGroup(item: PrivateCopy,operationId: String) = maintenance {
        val r = operation(item,operationId);val target = quarantinePath(item,operationId)
        ensure(files.stat(r.path) == null,"Original group reappeared")
        if(files.stat(target) != null) {
            ensure(r.directory && validate(r,target,true).isEmpty(),"Surviving group bytes")
            files.remove(target,true)
        };Unit
    }
    override fun restoreWholeGroup(item: PrivateCopy,operationId: String) = maintenance {
        val r = operation(item,operationId);val target = quarantinePath(item,operationId)
        if(files.stat(r.path) != null) { ensure(files.stat(target) == null,"Ambiguous group locations");validate(r,r.path,false) }
        else { ensure(validate(r,target,false) == item.members,"Only an intact whole group can return");files.move(target,r.path);validate(r,r.path,false) };Unit
    }
}
