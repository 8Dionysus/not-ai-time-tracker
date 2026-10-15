package dev.notai.tracker

import kotlinx.serialization.Serializable

@Serializable data class TransferRetentionSelection(val operationIds: Set<String> = emptySet(),val originIds: Set<String> = emptySet())
@Serializable enum class RetentionTargetKind { OPERATION, ORIGIN, PROCESS, TRANSFER_METADATA }
@Serializable enum class RetentionBlocker { MISSING, PENDING_OPERATION, PENDING_REFERENCE, ACTIVE_OR_RECOVERY_REFERENCE, ASSIGNMENT_REFERENCE, SOURCE_RECONSTRUCTION_UNAVAILABLE }
@Serializable enum class RetentionWarningCode { OPERATION_UNDO_REMOVED, FULL_RESULT_PAYLOAD_REMOVED, PROCESS_UNDO_INVALIDATED, OTHER_COPIES_UNCHANGED, METADATA_NOT_SMALLER }
@Serializable data class RetentionTarget(val kind: RetentionTargetKind,val id: String)
@Serializable data class RetentionWarningKey(val target: RetentionTarget,val code: RetentionWarningCode)
@Serializable data class RetentionCapabilityChange(val operationId: String,val undoAvailableBefore: Boolean,val undoAvailableAfter: Boolean,
    val pendingResumeAvailableBefore: Boolean,val pendingResumeAvailableAfter: Boolean,val fullResultPayloadBefore: Boolean,val fullResultPayloadAfter: Boolean)
@Serializable data class RetentionTargetPreview(val target: RetentionTarget,val requested: Boolean,val willChange: Boolean,val payloadBytes: Long,
    val beforeHash: String?,val blockers: List<RetentionBlocker>)
@Serializable data class RetentionIntent(val expected: Token,val controlBarrier: String,val selection: TransferRetentionSelection,
    val targets: List<RetentionTargetPreview>,val transferHash: String,val afterHash: String,val warnings: List<RetentionWarningKey>,
    val capabilities: List<RetentionCapabilityChange>,val preservedLeaseOperationIds: List<String>,val processUndoAvailable: Boolean)
/** No raw payloads are retained in a preview. Bytes describe JSON metadata, never SQLite physical reclaim. */
class TransferRetentionPreview internal constructor(internal val intent: RetentionIntent,val metadataBytesBefore: Long,
    val metadataBytesAfterWithoutReceipt: Long,val removedPayloadBytes: Long) {
    val expected get() = intent.expected
    val targets get() = intent.targets
    val capabilities get() = intent.capabilities
    val preservedLeaseOperationIds get() = intent.preservedLeaseOperationIds
    val requiredAcknowledgements get() = frozenSet(intent.warnings)
    val processUndoAvailable get() = intent.processUndoAvailable
    val canApply get() = targets.any { it.willChange } && targets.all { it.blockers.isEmpty() }
    val hash get() = payloadHash(listOf(payloadHash(intent),metadataBytesBefore.toString(),metadataBytesAfterWithoutReceipt.toString(),removedPayloadBytes.toString()))
}
@Serializable data class TransferCompactionReceipt(val commandId: String,val requestHash: String,val previewHash: String,
    val baseDatasetId: String,val baseControlBarrier: String,val baseRevision: Long,val resultRevision: Long,
    val targets: List<RetentionTarget>,val acknowledgements: List<RetentionWarningKey>,val beforeHash: String,val afterHashWithoutReceipt: String,
    val metadataBytesBefore: Long,val metadataBytesAfterWithoutReceipt: Long,val removedPayloadBytes: Long)

/** Local metadata lifecycle only; no file deletion, VACUUM, auto purge or domain record mutation. */
internal object TransferRetention {
    private data class Prepared(val preview: TransferRetentionPreview,val transfer: TransferState)
    private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8).size.toLong()
    private fun contentBytes(c: TransferContent?) = if(c == null || c == TransferContent()) 0L else bytes(codec.encodeToString(c))
    private fun payloadBytes(origin: TransferOrigin) = contentBytes(origin.localGroup)
    private fun payloadBytes(op: TransferOperation) = op.groups.sumOf { contentBytes(it.group.content)+contentBytes(it.inserted)+contentBytes(it.previousOrigin?.localGroup) }
    fun preview(d: Dataset,selection: TransferRetentionSelection,processUndoAvailable: Boolean) = prepare(d,selection,processUndoAvailable).preview
    private fun prepare(d: Dataset,requested: TransferRetentionSelection,processUndoAvailable: Boolean): Prepared {
        val selection = requested.copy(operationIds = frozenSet(requested.operationIds.sorted()),originIds = frozenSet(requested.originIds.sorted()))
        ensure((selection.operationIds+selection.originIds).all { it.isNotBlank() },"Blank retention target")
        val operations = d.transfer.operations.associateBy { it.id };val origins = d.transfer.origins.associateBy { it.originId }
        val opIds = selection.operationIds.toMutableSet();val originIds = selection.originIds.toMutableSet()
        // Closure removes terminal duplicate snapshots of a selected origin, never pending work.
        do {
            val old = opIds.size+originIds.size
            opIds.mapNotNull { operations[it] }.filter { it.complete }.flatMap { it.groups }.forEach { g ->
                if(g.group.originId in origins) originIds += g.group.originId
                g.previousOrigin?.originId?.takeIf { it in origins }?.let { originIds += it }
            }
            operations.values.filter { it.complete && it.groups.any { g -> g.group.originId in originIds || g.previousOrigin?.originId in originIds } }.forEach { opIds += it.id }
        } while(old != opIds.size+originIds.size)
        val active = d.sessions.filter { it.status != SessionStatus.STOPPED }.map { it.id }.toSet()
        val assignments = d.pendingAssignments.map { it.sessionId }.toSet()
        val pending = operations.values.filterNot { it.complete }
        fun protected(ids: Set<String>): List<RetentionBlocker> = buildList {
            if(ids.any { it in active }) add(RetentionBlocker.ACTIVE_OR_RECOVERY_REFERENCE)
            if(ids.any { it in assignments }) add(RetentionBlocker.ASSIGNMENT_REFERENCE)
        }
        val compactedOrigins = mutableMapOf<String,TransferOrigin>();val targets = mutableListOf<RetentionTargetPreview>()
        for(id in opIds.sorted()) {
            val op = operations[id];val blockers = mutableListOf<RetentionBlocker>()
            if(op == null) blockers += RetentionBlocker.MISSING else if(!op.payloadsPruned) {
                if(!op.complete) blockers += RetentionBlocker.PENDING_OPERATION
                val ids = op.groups.flatMap { g -> g.group.content.sessions.map { it.id }+g.inserted?.sessions.orEmpty().map { it.id }+
                    g.previousOrigin?.let { originMemberIds(it,"session") }.orEmpty() }.toSet()
                blockers += protected(ids)
            }
            targets += RetentionTargetPreview(RetentionTarget(RetentionTargetKind.OPERATION,id),id in selection.operationIds,op != null && !op.payloadsPruned && op.complete && blockers.isEmpty(),
                op?.let(::payloadBytes) ?: 0,op?.let { payloadHash(it) },frozenList(blockers.distinct()))
        }
        for(id in originIds.sorted()) {
            val origin = origins[id];val blockers = mutableListOf<RetentionBlocker>()
            if(origin == null) blockers += RetentionBlocker.MISSING else if(origin.compaction == null) {
                blockers += protected(originMemberIds(origin,"session"))
                if(pending.any { it.groups.any { g -> g.group.originId == id || g.previousOrigin?.originId == id } }) blockers += RetentionBlocker.PENDING_REFERENCE
                try { compactedOrigins[id] = compactTransferOrigin(d,origin) } catch(_: CoreError) { blockers += RetentionBlocker.SOURCE_RECONSTRUCTION_UNAVAILABLE }
            }
            if(blockers.isNotEmpty()) compactedOrigins.remove(id)
            targets += RetentionTargetPreview(RetentionTarget(RetentionTargetKind.ORIGIN,id),id in selection.originIds,origin != null && origin.compaction == null && blockers.isEmpty(),
                origin?.let(::payloadBytes) ?: 0,origin?.let { payloadHash(it) },frozenList(blockers.distinct()))
        }
        val pruneOps = targets.filter { it.target.kind == RetentionTargetKind.OPERATION && it.willChange }.map { it.target.id }.toSet()
        val warnings = mutableListOf<RetentionWarningKey>();val capabilities = mutableListOf<RetentionCapabilityChange>()
        for(id in pruneOps.sorted()) operations[id]?.takeIf { !it.payloadsPruned }?.let { op ->
            val undo = op.complete && !op.undone && op.baseDatasetId == d.datasetId && op.baseControlBarrier == d.controlBarrier && op.nextRevision == d.revision
            warnings += RetentionWarningKey(RetentionTarget(RetentionTargetKind.OPERATION,id),RetentionWarningCode.FULL_RESULT_PAYLOAD_REMOVED)
            if(undo) warnings += RetentionWarningKey(RetentionTarget(RetentionTargetKind.OPERATION,id),RetentionWarningCode.OPERATION_UNDO_REMOVED)
            capabilities += RetentionCapabilityChange(id,undo,false,!op.complete,!op.complete,true,false)
        }
        warnings += RetentionWarningKey(RetentionTarget(RetentionTargetKind.PROCESS,"local-undo"),RetentionWarningCode.PROCESS_UNDO_INVALIDATED)
        warnings += RetentionWarningKey(RetentionTarget(RetentionTargetKind.TRANSFER_METADATA,"other-copies"),RetentionWarningCode.OTHER_COPIES_UNCHANGED)
        val revision = Math.addExact(d.revision,1)
        val preserved = operations.values.filter { it.id !in pruneOps && !it.undone && !it.payloadsPruned && it.baseDatasetId == d.datasetId && it.baseControlBarrier == d.controlBarrier && it.nextRevision == d.revision }.map { it.id }.sorted()
        val next = d.transfer.copy(origins = d.transfer.origins.map { compactedOrigins[it.originId] ?: it },operations = d.transfer.operations.map { op ->
            if(op.id in pruneOps && op.complete && !op.payloadsPruned) op.copy(payloadsPruned = true,groups = op.groups.map { it.copy(group = it.group.copy(content = TransferContent()),inserted = null,previousOrigin = null) })
            else if(op.id in preserved) op.copy(nextRevision = revision) else op
        })
        val beforeBytes = bytes(codec.encodeToString(d.transfer));val afterBytes = bytes(codec.encodeToString(next))
        if(afterBytes >= beforeBytes) warnings += RetentionWarningKey(RetentionTarget(RetentionTargetKind.TRANSFER_METADATA,"metadata"),RetentionWarningCode.METADATA_NOT_SMALLER)
        val intent = RetentionIntent(token(d),d.controlBarrier,selection,frozenList(targets),payloadHash(d.transfer),payloadHash(next),frozenList(warnings),frozenList(capabilities),frozenList(preserved),processUndoAvailable)
        return Prepared(TransferRetentionPreview(intent,beforeBytes,afterBytes,targets.filter { it.willChange }.sumOf { it.payloadBytes }),next)
    }
    /** Replay is read-only even after revisions/reopen. A new command must match the exact preview. */
    fun apply(d: Dataset,preview: TransferRetentionPreview,commandId: String,acknowledged: Set<RetentionWarningKey>,processUndoAvailable: Boolean,now: Moment): Pair<Dataset,Pair<TransferCompactionReceipt,Boolean>> {
        ensure(commandId.isNotBlank(),"Retention command ID required")
        val ordered = acknowledged.sortedWith(compareBy({ it.target.kind.name },{ it.target.id },{ it.code.name }))
        val hash = payloadHash(listOf(preview.hash,payloadHash(ordered)))
        d.transfer.compactions.find { it.commandId == commandId }?.let { old ->
            ensureReason(old.requestHash == hash,ErrorReason.IDEMPOTENCY_CONFLICT,ErrorCode.STALE,mapOf("commandId" to ErrorArgument.Identifier(commandId)))
            return d to (old to false)
        }
        checkToken(d,preview.expected,now)
        ensureReason(d.controlBarrier == preview.intent.controlBarrier,ErrorReason.RETENTION_PREVIEW_CHANGED,ErrorCode.STALE)
        val prepared = prepare(d,preview.intent.selection,processUndoAvailable)
        ensureReason(prepared.preview.hash == preview.hash,ErrorReason.RETENTION_PREVIEW_CHANGED,ErrorCode.STALE)
        ensureReason(preview.canApply,ErrorReason.RETENTION_BLOCKED,ErrorCode.INVALID_INPUT,mapOf("blockedCount" to ErrorArgument.Count(preview.targets.count { it.blockers.isNotEmpty() }.toLong())))
        ensureReason(acknowledged == preview.requiredAcknowledgements,ErrorReason.RETENTION_CONFIRMATION_REQUIRED,ErrorCode.INVALID_INPUT,
            mapOf("requiredCount" to ErrorArgument.Count(preview.requiredAcknowledgements.size.toLong())))
        val revision = Math.addExact(d.revision,1)
        val receipt = TransferCompactionReceipt(commandId,hash,preview.hash,d.datasetId,d.controlBarrier,d.revision,revision,
            frozenList(preview.targets.filter { it.willChange }.map { it.target }),frozenList(ordered),preview.intent.transferHash,preview.intent.afterHash,
            preview.metadataBytesBefore,preview.metadataBytesAfterWithoutReceipt,preview.removedPayloadBytes)
        val transfer = prepared.transfer.copy(compactions = prepared.transfer.compactions+receipt)
        return d.copy(revision = revision,transfer = transfer) to (receipt to true)
    }
}
internal fun validateTransferCompactions(receipts: List<TransferCompactionReceipt>) {
    ensure(receipts.map { it.commandId }.distinct().size == receipts.size,"Duplicate retention receipts")
    receipts.forEach { r ->
        ensure(r.commandId.isNotBlank() && r.baseDatasetId.isNotBlank() && r.baseRevision >= 0 && r.resultRevision > r.baseRevision && r.resultRevision-r.baseRevision == 1L &&
            listOf(r.requestHash,r.previewHash,r.beforeHash,r.afterHashWithoutReceipt).all { it.matches(Regex("[0-9a-f]{64}")) },"Invalid retention receipt")
        ensure(r.targets.isNotEmpty() && r.targets.distinct().size == r.targets.size && r.targets.all { it.kind in setOf(RetentionTargetKind.OPERATION,RetentionTargetKind.ORIGIN) && it.id.isNotBlank() } &&
            r.acknowledgements.distinct().size == r.acknowledgements.size && r.metadataBytesBefore >= 0 && r.metadataBytesAfterWithoutReceipt >= 0 && r.removedPayloadBytes >= 0,"Invalid retention receipt targets")
    }
}
internal fun TransferState.hasCompactionState() = compactions.isNotEmpty() || origins.any { it.compaction != null } || operations.any { it.payloadsPruned || it.groups.any { g -> g.previousOrigin?.compaction != null } }
