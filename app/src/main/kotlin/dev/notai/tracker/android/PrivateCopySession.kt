package dev.notai.tracker.android

import dev.notai.tracker.*

/** One application-root backend session, opened only by the Context/descriptor binder.
 * Uses the existing cleanup engine. Closing expires previews/FDs, never deletes/resumes bytes.
 * Durable writer/UI/legacy references remain authoritative hard blockers at every operation.
 */
class PrivateCopySession internal constructor(private val files: DescriptorCopyFiles,private val gate: CopyMaintenance): AutoCloseable {
    private val cleanup = PrivateCopyCleanup(RegisteredCopyStorage(files,gate))
    private var closed = false
    private fun <T> useOpen(block: ()->T): T = gate.locked {
        ensure(!closed,"Private-copy session closed",ErrorCode.STORAGE);block()
    }
    fun inventory(): CopyInventory = useOpen { cleanup.inventory() }
    fun preview(revision: Long,ids: Set<String>): CleanupPreview = useOpen { cleanup.preview(revision,ids) }
    fun confirm(value: CleanupConfirmation): CleanupJournal = useOpen { cleanup.confirm(value) }
    fun operations(): List<CleanupJournal> = useOpen { cleanup.operations() }
    fun remainingHash(operation: CleanupJournal): String = useOpen { cleanup.remainingHash(operation) }
    fun resume(operationId: String,expectedRemainingHash: String): CleanupJournal = useOpen { cleanup.resume(operationId,expectedRemainingHash) }
    fun restoreSurvivingGroup(operationId: String,itemId: String): CleanupJournal = useOpen { cleanup.restoreSurvivingGroup(operationId,itemId) }
    override fun close() = gate.locked { if(!closed) { closed = true;files.close() };Unit }
}
