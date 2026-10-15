package dev.notai.tracker

/** Backend-only binding of validated manifests to opaque IDs. This class never accepts UI paths.
 * Sealing is deliberately internal: platform catalog/backup validators supply the exact group.
 * Shared hardlinks require a complete sealed archive graph; other copies stay single-link. */
class RegisteredCopyStorage(private val files: DescriptorCopyFiles,private val gate: CopyMaintenance): PrivateCopyStorage {
    override fun <T> maintenance(block: ()->T): T = gate.locked(block)
    private val roots = mapOf(CopyKind.EXPORT to "exports",CopyKind.ROLLBACK to "rollback",CopyKind.DATABASE_ARCHIVE to "database-archives",
        CopyKind.RESTORE_INPUT to "restore-inputs",CopyKind.FORM to "forms")
    private val manifests = CopyManifestFiles(files)
    private fun mayRegister() = ensure(gate.state().journals.none { it.phase !in setOf(CleanupPhase.DONE,CleanupPhase.RESTORED) },"Cleanup interrupted")
    internal fun seal(item: PrivateCopy,path: String,directory: Boolean,memberNames: Map<String,String>) = maintenance {
        mayRegister();ensure(path.substringBefore('/') == roots[item.kind],"Invalid copy root")
        ensure(gate.blockers(item.id).isEmpty(),"Copy referenced")
        val registered = manifests.capture(item,path,directory,memberNames,emptySet(),false)
        gate.update { state ->
            ensure(state.copies.none { it.path == path || it.item.id == item.id },"Copy already registered")
            state.copy(copies = state.copies+registered)
        };Unit
    }
    /** Atomic registration of all new sealed aliases. Incomplete graphs grant no authority. */
    internal fun sealArchiveGraph(batch: List<ArchiveCopyManifest>) = maintenance {
        mayRegister();ensure(batch.isNotEmpty(),"Empty archive registration")
        val before = gate.state()
        val captured = batch.map { m ->
            ensure(m.path.substringBefore('/') == roots[CopyKind.DATABASE_ARCHIVE],"Invalid archive root")
            manifests.capture(m.item,m.path,true,m.memberPaths,m.directories,true)
        }
        val all = before.copies+captured
        ensure(all.map { it.item.id }.distinct().size == all.size && all.map { it.path }.distinct().size == all.size,"Duplicate registered copy")
        captured.forEach { r -> manifests.prove(all,r,before.references) { locate(it,before) } }
        gate.update { it.copy(copies = all) };Unit
    }
    fun archiveGraph(selectedIds: Set<String>): ArchiveLinkGraph = maintenance {
        val ids = selectedIds.toSet();val state = gate.state()
        ensure(ids.isNotEmpty() && ids.all { id -> state.copies.any { it.item.id == id && it.sealedArchive } },"Unknown sealed archive")
        val selected = state.copies.filter { it.item.id in ids }
        ensure(selected.all { locate(it,state).path != null },"Selected archive no longer exists",ErrorCode.STALE)
        val component = selected.flatMap { manifests.component(state.copies,it) }.distinctBy { it.item.id }.filter { locate(it,state).path != null }
        val reads = mutableMapOf<String,ManifestRead>()
        selected.forEach { r -> if(r.item.id !in reads) reads.putAll(manifests.prove(state.copies,r,emptyList()) { locate(it,state) }) }
        val nodes = component.flatMap { r -> reads.getValue(r.item.id).members.map { m ->
            Triple(reads.getValue(r.item.id).identities.getValue(m.opaqueId),m,ArchiveAlias(r.item.id,m.opaqueId))
        } }.groupBy { it.first.node() }.map { (node,rows) ->
            ArchiveLinkNode(payloadHash(node),rows.first().second.bytes,rows.first().second.sha256,
                rows.map { it.third }.sortedWith(compareBy<ArchiveAlias> { it.copyId }.thenBy { it.memberId }))
        }.sortedBy { it.opaqueId }
        val blockers = state.references.filter { it.itemId == null || it.itemId in component.map { c -> c.item.id } }.map { it.blocker }.toSet()+
            (if(state.journals.any { it.phase !in setOf(CleanupPhase.DONE,CleanupPhase.RESTORED) }) setOf(CopyBlocker.CLEANUP_INTERRUPTED) else emptySet())+
            (if(files.stat("recovery-pending.json") != null) setOf(CopyBlocker.REQUIRED_BY_PENDING_RECOVERY) else emptySet())
        ArchiveLinkGraph(state.revision,ids,component.map { it.item.id }.toSet(),nodes,
            nodes.fold(0L) { n,node -> Math.addExact(n,Math.multiplyExact(node.bytes,node.aliases.count { it.copyId in ids }.toLong())) },
            nodes.filter { n -> n.aliases.all { it.copyId in ids } }.fold(0L) { n,node -> Math.addExact(n,node.bytes) },blockers)
    }
    override fun inventory(): CopyInventory = maintenance {
        val state = gate.state();val interrupted = state.journals.any { it.phase !in setOf(CleanupPhase.DONE,CleanupPhase.RESTORED) }
        val recovery = try { files.stat("recovery-pending.json") != null } catch(_: Exception) { null }
        val recoveryBlockers = if(recovery != false) setOf(CopyBlocker.REQUIRED_BY_PENDING_RECOVERY)+
            (if(recovery == null) setOf(CopyBlocker.UNKNOWN_FILE_OR_STATE) else emptySet()) else emptySet()
        val items = state.copies.mapNotNull { record ->
            val blockers = record.item.hardBlockers+gate.blockers(record.item.id)+
                (if(interrupted) setOf(CopyBlocker.CLEANUP_INTERRUPTED) else emptySet())+
                recoveryBlockers
            val identity = try { files.stat(record.path) } catch(_: Exception) {
                return@mapNotNull record.item.copy(hardBlockers = blockers+setOf(CopyBlocker.PATH_NOT_SAFE,CopyBlocker.UNKNOWN_FILE_OR_STATE))
            } ?: return@mapNotNull null
            val checked = try { validate(record,record.path,false,true);emptySet() } catch(e: ArchiveGraphRefusal) { e.blockers } catch(_: Exception) {
                if(record.sealedArchive) setOf(CopyBlocker.HARDLINK_GRAPH_INCOMPLETE,CopyBlocker.PATH_NOT_SAFE,CopyBlocker.UNKNOWN_FILE_OR_STATE)
                else if(identity.regular && identity.links != 1L || record.item.members.any { m ->
                    runCatching { files.stat(memberPath(record,record.path,m))?.links != 1L }.getOrDefault(true)
                }) setOf(CopyBlocker.HARDLINK_GRAPH_INCOMPLETE) else setOf(CopyBlocker.PATH_NOT_SAFE,CopyBlocker.UNKNOWN_FILE_OR_STATE)
            }
            record.item.copy(hardBlockers = blockers+checked)
        }.toMutableList()
        for((kind,root) in roots) try {
            if(files.stat(root) != null) for(name in files.names(root)) {
                val path = "$root/$name"
                if(state.copies.none { it.path == path }) items += PrivateCopy(payloadHash(path),kind,"UNREGISTERED",0,"",emptyList(),setOf(CopyBlocker.UNKNOWN_FILE_OR_STATE)+recoveryBlockers)
            }
        } catch(_: Exception) {
            items += PrivateCopy(payloadHash("unreadable-root:$root"),kind,"UNREADABLE_ROOT",0,"",emptyList(),setOf(CopyBlocker.PATH_NOT_SAFE,CopyBlocker.UNKNOWN_FILE_OR_STATE)+recoveryBlockers)
        }
        if(recovery != false) items += PrivateCopy("recovery-journal",CopyKind.RECOVERY_JOURNAL,"PENDING_OR_UNREADABLE",0,"",emptyList(),recoveryBlockers)
        val recoveryCopies = items.count { it.kind in setOf(CopyKind.ROLLBACK,CopyKind.DATABASE_ARCHIVE) && it.hardBlockers.isEmpty() }
        CopyInventory(state.revision,System.currentTimeMillis(),items.map {
            if(recoveryCopies == 1 && it.hardBlockers.isEmpty() && it.kind in setOf(CopyKind.ROLLBACK,CopyKind.DATABASE_ARCHIVE)) it.copy(confirmableWarnings = it.confirmableWarnings+CopyWarning.LAST_RECOVERY_COPY) else it
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
    private fun locate(r: RegisteredCopy,state: CopyMaintenanceState = gate.state()): CopyLocation {
        val journals = state.journals.filter { op -> op.items.any { it.item.id == r.item.id } }
        val targets = journals.map { quarantinePath(r.item,it.operationId) }.filter { files.stat(it) != null }
        ensure(targets.size <= 1 && (targets.isEmpty() || files.stat(r.path) == null),"Ambiguous group locations",ErrorCode.STALE)
        if(targets.isNotEmpty()) return CopyLocation(targets.single(),true)
        if(files.stat(r.path) != null) return CopyLocation(r.path,false)
        ensure(journals.any { op -> op.items.any { it.item.id == r.item.id && it.quarantined && !it.restored } },"Sealed archive disappeared without cleanup proof",ErrorCode.STALE)
        return CopyLocation(null,true)
    }
    private fun validate(r: RegisteredCopy,path: String,partial: Boolean,protect: Boolean = false): ManifestRead {
        if(!r.sealedArchive) return manifests.read(r,CopyLocation(path,partial))
        val state = gate.state()
        return manifests.prove(state.copies,r,if(protect) state.references else emptyList()) { row ->
            if(row.item.id == r.item.id) CopyLocation(path,partial) else locate(row,state)
        }.getValue(r.item.id)
    }
    override fun quarantine(item: PrivateCopy,operationId: String) = maintenance {
        val r = operation(item,operationId);val target = quarantinePath(item,operationId)
        ensure(gate.blockers(item.id).isEmpty() && files.stat("recovery-pending.json") == null,"Copy became referenced",ErrorCode.STALE)
        files.mkdir("cleanup-quarantine")
        if(files.stat(target) != null) { ensure(files.stat(r.path) == null,"Both group locations exist");validate(r,target,true,true) }
        else { validate(r,r.path,false,true);files.move(r.path,target);validate(r,target,false,true) }
        Unit
    }
    override fun remaining(item: PrivateCopy,operationId: String): List<CopyMember> = maintenance {
        val r = operation(item,operationId);val target = quarantinePath(item,operationId)
        // Observe exact selected members independently of whether all external owners are safe.
        // Every destructive step separately proves the complete graph and its references.
        if(files.stat(target) != null) { ensure(files.stat(r.path) == null,"Ambiguous group locations");manifests.read(r,CopyLocation(target,true)).members }
        else if(files.stat(r.path) != null) manifests.read(r,CopyLocation(r.path,false)).members else emptyList()
    }
    override fun isQuarantined(item: PrivateCopy,operationId: String): Boolean = maintenance {
        val r = operation(item,operationId);val target = quarantinePath(item,operationId)
        if(files.stat(target) != null) { ensure(files.stat(r.path) == null,"Ambiguous group locations");manifests.read(r,CopyLocation(target,true));true } else false
    }
    override fun removeMember(item: PrivateCopy,member: CopyMember,operationId: String) = maintenance {
        val r = operation(item,operationId);val target = quarantinePath(item,operationId)
        ensure(gate.blockers(item.id).isEmpty() && files.stat("recovery-pending.json") == null,"Copy became referenced",ErrorCode.STALE)
        val checked = validate(r,target,true,true);ensure(member in checked.members,"Unknown member")
        files.remove(memberPath(r,target,member),expected = checked.identities.getValue(member.opaqueId),
            expectedLinks = if(r.sealedArchive) checked.identities.getValue(member.opaqueId).links else 1);Unit
    }
    override fun verifyAbsent(item: PrivateCopy,operationId: String): Boolean = maintenance {
        val r = operation(item,operationId);val target = quarantinePath(item,operationId)
        if(files.stat(r.path) != null) false else {
            if(files.stat(target) != null) {
                manifests.read(r,CopyLocation(target,true))
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
            ensure(gate.blockers(item.id).isEmpty() && files.stat("recovery-pending.json") == null,"Copy became referenced",ErrorCode.STALE)
            ensure(r.directory && validate(r,target,true,true).members.isEmpty(),"Surviving group bytes")
            for((name,identity) in r.directories.entries.sortedWith(compareByDescending<Map.Entry<String,FileIdentity>> { it.key.count { c -> c == '/' } }.thenByDescending { it.key })) {
                if(files.stat("$target/$name") != null) files.remove("$target/$name",true,identity)
            }
            files.remove(target,true,r.identities.getValue("@group"))
        };Unit
    }
    override fun restoreWholeGroup(item: PrivateCopy,operationId: String) = maintenance {
        val r = operation(item,operationId);val target = quarantinePath(item,operationId)
        ensure(gate.blockers(item.id).isEmpty() && files.stat("recovery-pending.json") == null,"Copy became referenced",ErrorCode.STALE)
        if(files.stat(r.path) != null) { ensure(files.stat(target) == null,"Ambiguous group locations");validate(r,r.path,false,true) }
        else { ensure(validate(r,target,false,true).members == item.members,"Only an intact whole group can return");files.move(target,r.path);validate(r,r.path,false,true) };Unit
    }
}
