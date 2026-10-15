package dev.notai.tracker

import org.junit.Test
import kotlin.test.*

/** Isolated storage simulator, not evidence of Android filesystem primitive safety. */
class CleanupProtocolTest {
    private class Storage: PrivateCopyStorage {
        var revision = 1L;val live = linkedMapOf<String,PrivateCopy>();val quarantine = linkedMapOf<String,MutableList<CopyMember>>()
        val journal = linkedMapOf<String,CleanupJournal>();var failAfterUnlink = false;var unlinkCount = 0
        override fun <T> maintenance(block: ()->T): T = synchronized(this,block)
        override fun inventory() = CopyInventory(revision,0,live.values.toList())
        override fun operations() = journal.values.toList()
        override fun saveDurably(journal: CleanupJournal) { this.journal[journal.operationId] = journal }
        override fun quarantine(item: PrivateCopy,operationId: String) {
            // Idempotent reconciliation of rename committed before its journal update.
            if(item.id !in quarantine) { assertEquals(item,live.remove(item.id));quarantine[item.id] = item.members.toMutableList();revision++ }
        }
        override fun remaining(item: PrivateCopy,operationId: String) = quarantine[item.id]?.toList() ?: live[item.id]?.members.orEmpty()
        override fun removeMember(item: PrivateCopy,member: CopyMember,operationId: String) {
            assertTrue(quarantine.getValue(item.id).remove(member));unlinkCount++
            if(failAfterUnlink) { failAfterUnlink = false;throw java.io.IOException("injected crash after unlink") }
        }
        override fun verifyAbsent(item: PrivateCopy,operationId: String) = item.id !in live && quarantine[item.id].orEmpty().isEmpty()
        override fun restoreWholeGroup(item: PrivateCopy,operationId: String) { assertNull(live[item.id]);assertEquals(item.members,quarantine.remove(item.id)?.toList());live[item.id] = item;revision++ }
    }
    private fun item(id: String,warnings: Set<CopyWarning> = emptySet(),blockers: Set<CopyBlocker> = emptySet()) =
        PrivateCopy(id,CopyKind.ROLLBACK,"VALIDATED",2,"a".repeat(64),listOf(CopyMember("a",1,"a".repeat(64)),CopyMember("b",1,"b".repeat(64))),blockers,warnings)
    private fun confirmation(p: CleanupPreview,key: String = "delete") = CleanupConfirmation(p.id,p.revision,p.items.associate { it.id to it.sha256 },
        p.items.flatMap { i -> i.confirmableWarnings.map { WarningAcknowledgement(i.id,it) } }.toSet(),p.items.map { it.id }.toSet(),key)
    @Test fun warningsLastCopyAndIdempotencyAreSeparate() {
        val storage = Storage();storage.live["last"] = item("last",setOf(CopyWarning.LAST_RECOVERY_COPY))
        val cleanup = PrivateCopyCleanup(storage);val preview = cleanup.preview(1,setOf("last"));assertEquals(0,storage.unlinkCount)
        val confirmation = confirmation(preview)
        assertFailsWith<CoreError> { cleanup.confirm(confirmation.copy(lastCopyAcknowledged = emptySet())) }
        assertEquals(0,storage.unlinkCount)
        val done = cleanup.confirm(confirmation);assertEquals(CleanupPhase.DONE,done.phase);assertEquals(2,storage.unlinkCount)
        assertEquals(done,cleanup.confirm(confirmation));assertEquals(2,storage.unlinkCount)
        assertFailsWith<CoreError> { cleanup.confirm(confirmation.copy(itemHashes = emptyMap())) }
    }
    @Test fun interruptionIsVisibleAndNeverAutomaticallyDeletes() {
        val storage = Storage();storage.live["copy"] = item("copy");storage.failAfterUnlink = true
        var cleanup = PrivateCopyCleanup(storage);val preview = cleanup.preview(1,setOf("copy"))
        val partial = cleanup.confirm(confirmation(preview));assertEquals(CleanupPhase.PARTIAL,partial.phase);assertEquals(1,storage.unlinkCount)
        cleanup = PrivateCopyCleanup(storage)
        val reconciled = cleanup.operations().single();assertEquals(CleanupPhase.PARTIAL,reconciled.phase);assertEquals(setOf("a"),reconciled.items.single().deletedMemberIds)
        assertEquals(1,storage.unlinkCount)
        assertFailsWith<CoreError> { cleanup.restoreSurvivingGroup(partial.operationId,"copy") }
        val done = cleanup.resume(partial.operationId,cleanup.remainingHash(reconciled));assertEquals(CleanupPhase.DONE,done.phase);assertEquals(2,storage.unlinkCount)
    }
    @Test fun staleAndProtectedSelectionsNeverReachQuarantine() {
        val storage = Storage();storage.live["live"] = item("live",blockers = setOf(CopyBlocker.LIVE_FORM_REFERENCE))
        val cleanup = PrivateCopyCleanup(storage);assertFailsWith<CoreError> { cleanup.preview(1,setOf("live")) }
        storage.live["other"] = item("other");val preview = cleanup.preview(1,setOf("other"));storage.revision++
        assertFailsWith<CoreError> { cleanup.confirm(confirmation(preview)) };assertTrue(storage.quarantine.isEmpty())
        assertFailsWith<CoreError> { cleanup.preview(storage.revision,setOf("../outside")) }
    }
}
