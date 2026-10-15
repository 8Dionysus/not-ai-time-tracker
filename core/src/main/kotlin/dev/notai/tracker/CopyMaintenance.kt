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
@Serializable data class CopyReference(val token: String,val owner: String,val itemId: String?,val blocker: CopyBlocker)
@Serializable data class RegisteredCopy(val item: PrivateCopy,val path: String,val directory: Boolean,
    val memberNames: Map<String,String>,val identities: Map<String,FileIdentity>,
    val directories: Map<String,FileIdentity> = emptyMap(),val sealedArchive: Boolean = false)
@Serializable data class CopyMaintenanceState(@Required val format: Int = 2,val revision: Long = 0,
    val references: List<CopyReference> = emptyList(),val copies: List<RegisteredCopy> = emptyList(),
    val journals: List<CleanupJournal> = emptyList())
class CopyMaintenance(private val metadata: CopyMetadata,private val maxMetadataBytes: Int = CopyMetadataBytes.MAX_BYTES) {
    init { require(maxMetadataBytes in 1..CopyMetadataBytes.MAX_BYTES) }
    private var poisoned = false
    @Synchronized fun <T> locked(block: ()->T): T { check(!poisoned) { "Maintenance persistence failed; reopen and inspect" };return block() }
    fun state(): CopyMaintenanceState = locked {
        (metadata.read()?.let { CopyMetadataBytes.check(it,maxMetadataBytes);codec.decodeFromString<CopyMaintenanceState>(it) } ?: CopyMaintenanceState()).also {
            check(it.format in 1..2 && it.revision >= 0 && it.references.map { r -> r.token }.distinct().size == it.references.size)
            check(it.format != 1 || it.copies.all { c -> !c.sealedArchive && c.directories.isEmpty() })
            check(it.copies.map { c -> c.item.id }.distinct().size == it.copies.size)
            check(it.copies.map { c -> c.path }.distinct().size == it.copies.size)
        }.copy(format = 2) // v1 flat, single-link registrations retain their original semantics.
    }
    fun update(change: (CopyMaintenanceState)->CopyMaintenanceState) = locked {
        val before = state();val after = change(before).copy(revision = Math.addExact(before.revision,1))
        val encoded = codec.encodeToString(after)
        CopyMetadataBytes.check(encoded,maxMetadataBytes) // Known refusal before touching durable state; recovery can still release a confirmed reference.
        try { metadata.writeDurably(encoded) } catch(e: Exception) { poisoned = true;throw e }
        after
    }
    fun acquire(owner: String,itemId: String?,blocker: CopyBlocker): CopyReference = locked {
        require(owner.isNotBlank());val reference = CopyReference(newId(),owner,itemId,blocker)
        update { it.copy(references = it.references+reference) };reference
    }
    fun release(reference: CopyReference) = locked {
        ensure(state().references.any { it == reference },"Unknown reference")
        update { it.copy(references = it.references-reference) };Unit
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
    fun blockers(itemId: String): Set<CopyBlocker> = locked { state().references.filter { it.itemId == null || it.itemId == itemId }.map { it.blocker }.toSet() }
    /** A backend writer invalidates the old immutable registration BEFORE altering its bytes.
     * A durable protective reference must already be held; stale previews then fail by revision.
     * Receipts/journals and references are never pruned by this operation. */
    fun invalidateRegistration(itemId: String) = locked {
        val before = state()
        ensure(before.references.any { it.itemId == null || it.itemId == itemId },"Writer reference required")
        ensure(before.journals.none { it.phase !in setOf(CleanupPhase.DONE,CleanupPhase.RESTORED) },"Cleanup requires explicit completion",ErrorCode.STORAGE)
        if(before.copies.any { it.item.id == itemId }) update { it.copy(copies = it.copies.filterNot { c -> c.item.id == itemId }) }
        Unit
    }
}
