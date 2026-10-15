package dev.notai.tracker

import kotlinx.serialization.Serializable

@Serializable enum class CopyKind { EXPORT, ROLLBACK, DATABASE_ARCHIVE, RESTORE_INPUT, FORM, RECOVERY_JOURNAL }
@Serializable enum class CopyBlocker { IN_PROGRESS, CHOOSER_RESULT_PENDING, EXPORT_WRITING, LIVE_FORM_REFERENCE, RESTORE_SOURCE_IN_USE,
    REQUIRED_BY_PENDING_RECOVERY, UNKNOWN_FILE_OR_STATE, PATH_NOT_SAFE, HARDLINK_GRAPH_INCOMPLETE, PREVIEW_STALE, CLEANUP_INTERRUPTED }
@Serializable enum class CopyWarning { EXPORT_RETRY_AVAILABLE, SNAPSHOT_MAY_CONTAIN_REMOVED_RECORDS, LAST_RECOVERY_COPY,
    RESTORE_SOURCE_WILL_BE_DISCARDED, FORM_DRAFT_WILL_BE_DISCARDED, ARCHIVE_MAY_CONTAIN_REMOVED_RECORDS }
@Serializable data class CopyMember(val opaqueId: String,val bytes: Long,val sha256: String)
@Serializable data class PrivateCopy(val id: String,val kind: CopyKind,val state: String,val bytes: Long,val sha256: String,val members: List<CopyMember>,
    val hardBlockers: Set<CopyBlocker> = emptySet(),val confirmableWarnings: Set<CopyWarning> = emptySet(),val missingLiveEntityIds: Set<String> = emptySet()) {
    val selectable get() = kind != CopyKind.RECOVERY_JOURNAL && hardBlockers.isEmpty()
}
@Serializable data class CopyInventory(val revision: Long,val createdAt: Long,val items: List<PrivateCopy>)
class CleanupPreview internal constructor(internal val owner: PrivateCopyCleanup,val id: String,val revision: Long,val items: List<PrivateCopy>) {
    val bytes get() = items.fold(0L) { n,i -> Math.addExact(n,i.bytes) }
}
@Serializable data class WarningAcknowledgement(val itemId: String,val warning: CopyWarning)
@Serializable data class CleanupConfirmation(val previewId: String,val revision: Long,val itemHashes: Map<String,String>,
    val warningsAcknowledged: Set<WarningAcknowledgement>,val lastCopyAcknowledged: Set<String>,val idempotencyKey: String)
@Serializable enum class CleanupPhase { PREPARED, QUARANTINED, PARTIAL, DONE, RESTORED }
@Serializable data class CleanupItemProgress(val item: PrivateCopy,val quarantined: Boolean = false,val deletedMemberIds: Set<String> = emptySet(),val restored: Boolean = false)
@Serializable data class CleanupJournal(val operationId: String,val commandKey: String,val payloadHash: String,val phase: CleanupPhase,
    val items: List<CleanupItemProgress>,val error: String? = null)
/** Platform must hold the SAME maintenance gate as all writers/choosers/rescue. All IDs below
 * are registry IDs, never paths supplied by UI. Unsafe primitives MUST make items PATH_NOT_SAFE.
 * Journal writes include file fsync, atomic rename, parent fsync. Mutations are descriptor-relative,
 * no-follow, same-filesystem and manifest-allowlisted, or throw before touching that element.
 */
interface PrivateCopyStorage {
    fun <T> maintenance(block: ()->T): T
    fun inventory(): CopyInventory
    fun operations(): List<CleanupJournal>
    fun saveDurably(journal: CleanupJournal)
    fun quarantine(item: PrivateCopy,operationId: String)
    fun remaining(item: PrivateCopy,operationId: String): List<CopyMember>
    fun removeMember(item: PrivateCopy,member: CopyMember,operationId: String)
    /** Explicit execution only: remove an empty quarantine container and sync its parent. */
    fun finishGroup(item: PrivateCopy,operationId: String) {}
    fun verifyAbsent(item: PrivateCopy,operationId: String): Boolean
    fun restoreWholeGroup(item: PrivateCopy,operationId: String)
    /** Descriptor adapter can reconcile a rename committed before its progress receipt. */
    fun isQuarantined(item: PrivateCopy,operationId: String): Boolean? = null
}
/** Manual-only cleanup protocol. Constructor and reads never resume deletion after a crash. */
class PrivateCopyCleanup(private val storage: PrivateCopyStorage) {
    private val previews = mutableMapOf<String,CleanupPreview>()
    fun inventory(): CopyInventory = storage.maintenance { storage.inventory().let { it.copy(items = frozenList(it.items.map { item -> item.frozenCopy() })) } }
    fun preview(expectedRevision: Long,itemIds: Set<String>): CleanupPreview = storage.maintenance {
        val ids = itemIds.toSet()
        val live = storage.inventory();ensure(live.revision == expectedRevision,"Cleanup preview stale",ErrorCode.STALE)
        ensure(ids.isNotEmpty() && ids.all { id -> live.items.any { it.id == id } },"Unknown/empty cleanup selection")
        ensure(storage.operations().none { it.phase !in setOf(CleanupPhase.DONE,CleanupPhase.RESTORED) },"Cleanup interrupted; reconcile first",ErrorCode.STORAGE)
        val selected = live.items.filter { it.id in ids };ensure(selected.all { it.selectable },"Selected copy is protected")
        CleanupPreview(this,newId(),live.revision,frozenList(selected.map { it.frozenCopy() })).also { previews[it.id] = it }
    }
    fun confirm(confirmation: CleanupConfirmation): CleanupJournal = storage.maintenance {
        val c = codec.decodeFromString<CleanupConfirmation>(codec.encodeToString(confirmation))
        val hash = payloadHash(c.copy(itemHashes = c.itemHashes.toSortedMap(),warningsAcknowledged = c.warningsAcknowledged.sortedWith(compareBy<WarningAcknowledgement> { it.itemId }.thenBy { it.warning.name }).toSet(),lastCopyAcknowledged = c.lastCopyAcknowledged.sorted().toSet()))
        storage.operations().find { it.commandKey == c.idempotencyKey }?.let { old ->
            ensureReason(old.payloadHash == hash,ErrorReason.IDEMPOTENCY_CONFLICT,ErrorCode.STALE,mapOf("operationId" to ErrorArgument.Identifier(old.operationId)));return@maintenance reconcile(old)
        }
        ensure(c.idempotencyKey.isNotBlank(),"Missing cleanup idempotency key")
        val p = previews[c.previewId] ?: fail(ErrorCode.STALE,"Preview expired after process restart")
        ensure(p.owner === this && p.revision == c.revision && c.itemHashes == p.items.associate { it.id to it.sha256 },"Cleanup confirmation differs from preview",ErrorCode.STALE)
        val live = storage.inventory();ensure(live.revision == p.revision && p.items.all { i -> live.items.singleOrNull { it.id == i.id } == i && i.selectable },"Copy changed or in use",ErrorCode.STALE)
        ensure(p.items.all { i -> i.confirmableWarnings.all { WarningAcknowledgement(i.id,it) in c.warningsAcknowledged } },"Warning acknowledgement missing")
        ensure(p.items.filter { CopyWarning.LAST_RECOVERY_COPY in it.confirmableWarnings }.all { it.id in c.lastCopyAcknowledged },"Separate last-copy acknowledgement required")
        val journal = CleanupJournal(newId(),c.idempotencyKey,hash,CleanupPhase.PREPARED,p.items.map(::CleanupItemProgress))
        storage.saveDurably(journal);previews.remove(p.id)
        execute(journal)
    }
    fun operations(): List<CleanupJournal> = storage.maintenance { storage.operations().map(::reconcile) }
    /** A second explicit action includes a digest of the exact surviving bytes displayed to the owner. */
    fun resume(operationId: String,expectedRemainingHash: String): CleanupJournal = storage.maintenance {
        val op = storage.operations().singleOrNull { it.operationId == operationId } ?: fail(ErrorCode.INVALID_INPUT,"Cleanup operation missing")
        val current = reconcile(op)
        ensure(remainingHash(current) == expectedRemainingHash,"Remaining bytes changed",ErrorCode.STALE)
        if(current.phase in setOf(CleanupPhase.DONE,CleanupPhase.RESTORED)) current else execute(current)
    }
    fun remainingHash(op: CleanupJournal): String = storage.maintenance {
        val saved = storage.operations().single { it.operationId == op.operationId }
        payloadHash(saved.items.map { it.item.id to storage.remaining(it.item,saved.operationId) })
    }
    fun restoreSurvivingGroup(operationId: String,itemId: String): CleanupJournal = storage.maintenance {
        val op = reconcile(storage.operations().single { it.operationId == operationId });val progress = op.items.single { it.item.id == itemId }
        ensure(progress.quarantined && !progress.restored && storage.remaining(progress.item,operationId) == progress.item.members,"Only an intact surviving group can be returned")
        storage.restoreWholeGroup(progress.item,operationId)
        val items = op.items.map { if(it.item.id == itemId) it.copy(restored = true) else it }
        op.copy(items = items,phase = if(items.all { it.restored }) CleanupPhase.RESTORED else CleanupPhase.PARTIAL).also(storage::saveDurably)
    }
    private fun reconcile(op: CleanupJournal): CleanupJournal {
        if(op.phase in setOf(CleanupPhase.DONE,CleanupPhase.RESTORED)) return op
        // Read-only reconciliation records exact observed missing members. No unlink/rename occurs here.
        val progress = op.items.map { saved ->
            val location = storage.isQuarantined(saved.item,op.operationId)
            val p = if(location == true) saved.copy(quarantined = true) else if(location == false && saved.quarantined &&
                storage.remaining(saved.item,op.operationId) == saved.item.members) saved.copy(restored = true) else saved
            if(!p.quarantined || p.restored) p else {
                val remaining = storage.remaining(p.item,op.operationId).map { it.opaqueId }.toSet()
                p.copy(deletedMemberIds = p.item.members.map { it.opaqueId }.toSet()-remaining)
            }
        }
        val done = progress.all { it.restored || storage.verifyAbsent(it.item,op.operationId) }
        return op.copy(items = progress,phase = if(done) { if(progress.all { it.restored }) CleanupPhase.RESTORED else CleanupPhase.DONE } else CleanupPhase.PARTIAL,
            error = if(done) null else "CLEANUP_INTERRUPTED").also(storage::saveDurably)
    }
    private fun execute(initial: CleanupJournal): CleanupJournal {
        var op = initial
        try {
            for(index in op.items.indices) {
                var p = op.items[index];if(p.restored) continue
                if(!p.quarantined) {
                    storage.quarantine(p.item,op.operationId)
                    p = p.copy(quarantined = true)
                    op = op.copy(phase = CleanupPhase.QUARANTINED,items = op.items.mapIndexed { i,x -> if(i == index) p else x });storage.saveDurably(op)
                }
                for(member in storage.remaining(p.item,op.operationId)) {
                    ensure(member in p.item.members,"Quarantine contains unknown or changed bytes",ErrorCode.STORAGE)
                    storage.removeMember(p.item,member,op.operationId)
                    p = p.copy(deletedMemberIds = p.deletedMemberIds+member.opaqueId)
                    op = op.copy(items = op.items.mapIndexed { i,x -> if(i == index) p else x });storage.saveDurably(op)
                }
                storage.finishGroup(p.item,op.operationId)
                ensure(storage.verifyAbsent(p.item,op.operationId),"Copy deletion readback failed",ErrorCode.STORAGE)
            }
            return op.copy(phase = if(op.items.all { it.restored }) CleanupPhase.RESTORED else CleanupPhase.DONE,error = null).also(storage::saveDurably)
        } catch(e: Exception) {
            // If journaling itself fails, preserve the last durable journal and propagate storage failure.
            op = op.copy(phase = CleanupPhase.PARTIAL,error = e.javaClass.simpleName)
            storage.saveDurably(op);return op
        }
    }
}

private fun PrivateCopy.frozenCopy(): PrivateCopy = copy(members = frozenList(members),hardBlockers = frozenSet(hardBlockers),
    confirmableWarnings = frozenSet(confirmableWarnings),missingLiveEntityIds = frozenSet(missingLiveEntityIds))
