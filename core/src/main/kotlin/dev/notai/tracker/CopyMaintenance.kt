package dev.notai.tracker

import kotlinx.serialization.Serializable

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
    val memberNames: Map<String,String>,val identities: Map<String,FileIdentity>)
@Serializable data class CopyMaintenanceState(val format: Int = 1,val revision: Long = 0,
    val references: List<CopyReference> = emptyList(),val copies: List<RegisteredCopy> = emptyList(),
    val journals: List<CleanupJournal> = emptyList())
class CopyMaintenance(private val metadata: CopyMetadata) {
    private var poisoned = false
    @Synchronized fun <T> locked(block: ()->T): T { check(!poisoned) { "Maintenance persistence failed; reopen and inspect" };return block() }
    fun state(): CopyMaintenanceState = locked {
        (metadata.read()?.let { codec.decodeFromString<CopyMaintenanceState>(it) } ?: CopyMaintenanceState()).also {
            check(it.format == 1 && it.revision >= 0 && it.references.map { r -> r.token }.distinct().size == it.references.size)
            check(it.copies.map { c -> c.item.id }.distinct().size == it.copies.size)
        }
    }
    fun update(change: (CopyMaintenanceState)->CopyMaintenanceState) = locked {
        val before = state();val after = change(before).copy(revision = Math.addExact(before.revision,1))
        try { metadata.writeDurably(codec.encodeToString(after)) } catch(e: Exception) { poisoned = true;throw e }
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
    fun <T> writing(owner: String,itemId: String? = null,blocker: CopyBlocker = CopyBlocker.IN_PROGRESS,block: ()->T): T {
        val ref = locked {
            ensure(state().journals.none { it.phase !in setOf(CleanupPhase.DONE,CleanupPhase.RESTORED) },"Cleanup requires explicit completion",ErrorCode.STORAGE)
            acquire(owner,itemId,blocker)
        }
        val result = block();release(ref);return result
    }
    fun blockers(itemId: String): Set<CopyBlocker> = locked { state().references.filter { it.itemId == null || it.itemId == itemId }.map { it.blocker }.toSet() }
}
