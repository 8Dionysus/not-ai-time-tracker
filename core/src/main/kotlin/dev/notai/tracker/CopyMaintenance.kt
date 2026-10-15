package dev.notai.tracker

import kotlinx.serialization.Serializable
import kotlinx.serialization.Required

/** One instance per private root. Cleanup holds this gate through quarantine/deletion. Writers
 * durably acquire/release under it; their references protect long I/O without blocking UI handoff.
 * References survive process death; only the owning lifecycle may explicitly release them.
 * A missing legacy lifecycle is unknown, never an expired lease inferred from age. */
interface CopyMetadata {
    fun read(): String?
    fun writeDurably(value: String)
}
@Serializable data class CopyReference(val token: String,val owner: String,val itemId: String?,val blocker: CopyBlocker,
    val scopeItemIds: Set<String>? = null) {
    fun protects(id: String): Boolean = if(itemId != null) itemId == id else scopeItemIds == null || id in scopeItemIds
}
@Serializable data class CopyLifetime(val id: String,val referenceTokens: Set<String> = emptySet())
@Serializable data class StagedCopyInput(val id: String,val itemId: String,val bytes: Long = 0,val sha256: String? = null,
    val pendingToken: String? = null,val writerTokens: Set<String> = emptySet(),val state: String = "PREPARING",val handoffTokens: Set<String> = emptySet())
@Serializable data class RegisteredCopy(val item: PrivateCopy,val path: String,val directory: Boolean,
    val memberNames: Map<String,String>,val identities: Map<String,FileIdentity>,
    val directories: Map<String,FileIdentity> = emptyMap(),val sealedArchive: Boolean = false)
@Serializable data class CopyMaintenanceState(@Required val format: Int = 3,val revision: Long = 0,
    val references: List<CopyReference> = emptyList(),val copies: List<RegisteredCopy> = emptyList(),
    val journals: List<CleanupJournal> = emptyList(),val lifecycleVersion: Int = 0,
    val lifetimes: List<CopyLifetime> = emptyList(),val stagedInputs: List<StagedCopyInput> = emptyList())
class CopyMaintenance(private val metadata: CopyMetadata,private val maxMetadataBytes: Int = CopyMetadataBytes.MAX_BYTES) {
    init { require(maxMetadataBytes in 1..CopyMetadataBytes.MAX_BYTES) }
    private var poisoned = false
    @Synchronized fun <T> locked(block: ()->T): T { check(!poisoned) { "Maintenance persistence failed; reopen and inspect" };return block() }
    fun state(): CopyMaintenanceState = locked {
        (metadata.read()?.let { CopyMetadataBytes.check(it,maxMetadataBytes);codec.decodeFromString<CopyMaintenanceState>(it) } ?: CopyMaintenanceState()).also {
            check(it.format in 1..3 && it.revision >= 0 && it.references.map { r -> r.token }.distinct().size == it.references.size)
            check(it.format != 1 || it.copies.all { c -> !c.sealedArchive && c.directories.isEmpty() })
            check(it.lifecycleVersion in 0..1 && (it.format == 3 || it.lifecycleVersion == 0 && it.lifetimes.isEmpty() && it.stagedInputs.isEmpty() && it.references.all { r -> r.scopeItemIds == null }))
            check(it.lifetimes.map { o -> o.id }.distinct().size == it.lifetimes.size && it.lifetimes.all { o -> o.id.isNotBlank() &&
                o.referenceTokens.all { t -> it.references.any { r -> r.token == t && r.owner.startsWith("lifetime:${o.id}:") } } })
            check(it.stagedInputs.map { s -> s.id }.distinct().size == it.stagedInputs.size && it.stagedInputs.all { s -> s.bytes >= 0 &&
                s.state in setOf("PREPARING","READY","ABANDONED") && (s.sha256 == null || s.sha256.matches(Regex("[0-9a-f]{64}"))) && (s.state != "READY" || s.sha256 != null) })
            check(it.copies.map { c -> c.item.id }.distinct().size == it.copies.size)
            check(it.copies.map { c -> c.path }.distinct().size == it.copies.size)
        }.copy(format = 3) // Legacy registrations/references keep their authority until explicit adoption.
    }
    fun update(change: (CopyMaintenanceState)->CopyMaintenanceState) = locked {
        val before = state();val after = change(before).copy(revision = Math.addExact(before.revision,1))
        val encoded = codec.encodeToString(after)
        CopyMetadataBytes.check(encoded,maxMetadataBytes) // Known refusal before touching durable state; recovery can still release a confirmed reference.
        try { metadata.writeDurably(encoded) } catch(e: Exception) { poisoned = true;throw e }
        after
    }
    fun acquire(owner: String,itemId: String?,blocker: CopyBlocker,scopeItemIds: Set<String>? = null): CopyReference = locked {
        require(owner.isNotBlank());val reference = CopyReference(newId(),owner,itemId,blocker,scopeItemIds?.let(::frozenSet))
        update { it.copy(references = it.references+reference) };reference
    }
    fun release(reference: CopyReference) = locked {
        ensure(state().references.any { it == reference },"Unknown reference")
        update { it.copy(references = it.references-reference,lifetimes = it.lifetimes.map { o -> o.copy(referenceTokens = o.referenceTokens-reference.token) },
            stagedInputs = it.stagedInputs.map { s -> if(s.pendingToken == reference.token) s.copy(pendingToken = null) else s }) };Unit
    }
    /** Failed/interrupted operations keep their durable reference. Recovery is explicit. */
    fun <T> writing(owner: String,itemId: String? = null,blocker: CopyBlocker = CopyBlocker.IN_PROGRESS,block: ()->T): T =
        writingWithReference(owner,itemId,blocker) { block() }
    fun <T> writingWithReference(owner: String,itemId: String? = null,blocker: CopyBlocker = CopyBlocker.IN_PROGRESS,block: (CopyReference)->T): T {
        val ref = locked {
            ensure(state().journals.none { it.phase !in setOf(CleanupPhase.DONE,CleanupPhase.RESTORED) },"Cleanup requires explicit completion",ErrorCode.STORAGE)
            acquire(owner,itemId,blocker)
        }
        val result = block(ref);release(ref);return result
    }
    fun blockers(itemId: String): Set<CopyBlocker> = locked { state().references.filter { it.protects(itemId) }.map { it.blocker }.toSet() }
    /** Called once only after a descriptor inventory under this gate and adoption of all
     * current file lifetimes. No legacy owner/token is removed: its unknown protection
     * is scoped to every byte group present at the cutover, including refused groups.
     * Future independent UUID groups cannot belong to a terminated legacy writer. */
    fun adoptLifecycle(legacyItemIds: Set<String>) = locked {
        val before = state();if(before.lifecycleVersion == 1) return@locked
        ensure(before.copies.all { it.item.id in legacyItemIds },"Incomplete lifecycle inventory",ErrorCode.STORAGE)
        update { it.copy(lifecycleVersion = 1,references = it.references.map { r ->
            if(r.itemId == null && r.scopeItemIds == null) r.copy(scopeItemIds = frozenSet(legacyItemIds)) else r
        }) };Unit
    }
    fun activateLifetime(ownerId: String) = locked {
        require(ownerId.isNotBlank());if(state().lifetimes.none { it.id == ownerId }) update { it.copy(lifetimes = it.lifetimes+CopyLifetime(ownerId)) };Unit
    }
    fun retainLifetime(ownerId: String,purpose: String,itemId: String?,blocker: CopyBlocker,scopeItemIds: Set<String>? = null): CopyReference = locked {
        activateLifetime(ownerId);val owner = "lifetime:$ownerId:$purpose";val before = state()
        before.references.singleOrNull { it.owner == owner && it.itemId == itemId && it.blocker == blocker }?.let { return@locked it }
        val reference = CopyReference(newId(),owner,itemId,blocker,scopeItemIds?.let(::frozenSet))
        update { it.copy(references = it.references+reference,lifetimes = it.lifetimes.map { o -> if(o.id == ownerId) o.copy(referenceTokens = o.referenceTokens+reference.token) else o }) }
        reference
    }
    /** The lifecycle record, not a name/age scan, is the authority for exact release. */
    fun finishLifetime(ownerId: String,preserveTokens: Set<String> = emptySet()) = locked {
        val before = state();val owner = before.lifetimes.singleOrNull { it.id == ownerId } ?: return@locked
        ensure(preserveTokens.all { it in owner.referenceTokens },"Unowned active lifetime reference",ErrorCode.STORAGE)
        ensure(owner.referenceTokens.all { t -> before.references.any { it.token == t && it.owner.startsWith("lifetime:$ownerId:") } },"Lifetime references changed",ErrorCode.STORAGE)
        val released = owner.referenceTokens-preserveTokens
        update { it.copy(references = it.references.filterNot { r -> r.token in released },lifetimes = it.lifetimes.mapNotNull { o ->
            if(o.id != ownerId) o else if(preserveTokens.isEmpty()) null else o.copy(referenceTokens = frozenSet(preserveTokens))
        }) };Unit
    }
    /** A backend writer invalidates the old immutable registration BEFORE altering its bytes.
     * A durable protective reference must already be held; stale previews then fail by revision.
     * Receipts/journals and references are never pruned by this operation. */
    fun invalidateRegistration(itemId: String) = locked {
        val before = state()
        ensure(before.references.any { it.protects(itemId) },"Writer reference required")
        ensure(before.journals.none { it.phase !in setOf(CleanupPhase.DONE,CleanupPhase.RESTORED) },"Cleanup requires explicit completion",ErrorCode.STORAGE)
        if(before.copies.any { it.item.id == itemId }) update { it.copy(copies = it.copies.filterNot { c -> c.item.id == itemId }) }
        Unit
    }
}
