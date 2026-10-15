package dev.notai.tracker

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assume.assumeNotNull
import org.junit.Test
import kotlin.test.*

/** Real isolated files and JNI syscalls, not an Android/OEM acceptance claim. */
class DescriptorCleanupTest {
    private fun fixture(block: (File,DescriptorCopyFiles,CopyMaintenance,RegisteredCopyStorage)->Unit) {
        val library = System.getProperty("tracker.cleanup.library");assumeNotNull(library)
        val root = Files.createTempDirectory("tracker-copy-").toFile()
        try { DescriptorCopyFiles(root.path,library!!).use { files ->
            val gate = CopyMaintenance(files.metadata());block(root,files,gate,RegisteredCopyStorage(files,gate))
        } } finally { root.deleteRecursively() } // Only this test's newly created fixture root.
    }
    private fun seal(root: File,storage: RegisteredCopyStorage): PrivateCopy {
        val dir = File(root,"exports/${newId()}").apply { mkdirs() }
        File(dir,"data").writeText("fixture bytes");File(dir,"operation.json").writeText("{\"state\":\"COMPLETE\"}")
        val members = dir.listFiles()!!.sortedBy { it.name }.map { CopyMember(it.name,it.length(),sha256(it.readBytes())) }
        val item = PrivateCopy(newId(),CopyKind.EXPORT,"COMPLETE",members.sumOf { it.bytes },payloadHash(members),members)
        storage.seal(item,"exports/${dir.name}",true,members.associate { it.opaqueId to it.opaqueId });return item
    }
    private fun confirmation(p: CleanupPreview,key: String = newId()) = CleanupConfirmation(p.id,p.revision,p.items.associate { it.id to it.sha256 },
        p.items.flatMap { i -> i.confirmableWarnings.map { WarningAcknowledgement(i.id,it) } }.toSet(),p.items.map { it.id }.toSet(),key)
    @Test fun metadataOverflowCannotReplaceReadableJournal() = fixture { _,files,_,_ ->
        val metadata = files.metadata("bounded.json",16)
        metadata.writeDurably("existing receipt")
        assertFailsWith<IllegalStateException> { metadata.writeDurably("я".repeat(9)) }
        assertEquals("existing receipt",files.metadata("bounded.json",16).read())
        assertFalse(files.names("").any { it.endsWith(".part") })
    }
    @Test fun explicitChoiceDurableReceiptAndExactGroup() = fixture { root,files,gate,storage ->
        val item = seal(root,storage);val cleanup = PrivateCopyCleanup(storage);val inv = cleanup.inventory()
        val p = cleanup.preview(inv.revision,setOf(item.id));val c = confirmation(p)
        assertTrue(File(root,gate.state().copies.single().path).exists());assertTrue(cleanup.operations().isEmpty())
        assertEquals(CleanupPhase.DONE,cleanup.confirm(c).phase)
        assertFalse(File(root,gate.state().copies.single().path).exists())
        val reopened = PrivateCopyCleanup(RegisteredCopyStorage(files,CopyMaintenance(files.metadata())))
        assertEquals(CleanupPhase.DONE,reopened.confirm(c).phase)
        assertFailsWith<CoreError> { reopened.confirm(c.copy(idempotencyKey = c.idempotencyKey,itemHashes = emptyMap())) }
    }
    @Test fun leasesRecoveryAndStalePreviewProtectBytes() = fixture { root,files,gate,storage ->
        val item = seal(root,storage);val cleanup = PrivateCopyCleanup(storage)
        for(blocker in listOf(CopyBlocker.EXPORT_WRITING,CopyBlocker.CHOOSER_RESULT_PENDING,CopyBlocker.LIVE_FORM_REFERENCE,CopyBlocker.RESTORE_SOURCE_IN_USE,CopyBlocker.REQUIRED_BY_PENDING_RECOVERY)) {
            val ref = gate.acquire("fixture",item.id,blocker)
            assertTrue(blocker in cleanup.inventory().items.single().hardBlockers)
            assertFailsWith<CoreError> { cleanup.preview(gate.state().revision,setOf(item.id)) }
            assertEquals(ref,CopyMaintenance(files.metadata()).state().references.single());gate.release(ref)
        }
        val p = cleanup.preview(gate.state().revision,setOf(item.id));gate.writing("writer") { File(root,"unrelated-fixture").writeText("new") }
        assertFailsWith<CoreError> { cleanup.confirm(confirmation(p)) }
        File(root,"recovery-pending.json").writeText("pending")
        assertTrue(cleanup.inventory().items.all { CopyBlocker.REQUIRED_BY_PENDING_RECOVERY in it.hardBlockers })
        assertTrue(File(root,gate.state().copies.single().path).exists())
    }
    @Test fun symlinkHardlinkAndUnknownChildFailClosed() = fixture { root,_,gate,storage ->
        val item = seal(root,storage);val path = File(root,gate.state().copies.single().path);val data = File(path,"data")
        Files.createLink(File(root,"shared").toPath(),data.toPath())
        assertTrue(CopyBlocker.HARDLINK_GRAPH_INCOMPLETE in storage.inventory().items.single().hardBlockers)
        Files.delete(File(root,"shared").toPath())
        val saved = data.readBytes();data.delete();Files.createSymbolicLink(data.toPath(),File(root,"outside-fixture").toPath())
        assertFalse(storage.inventory().items.single().selectable);Files.delete(data.toPath());data.writeBytes(saved)
        File(path,"unknown").writeText("keep")
        assertFalse(storage.inventory().items.single().selectable)
        assertFailsWith<CoreError> { PrivateCopyCleanup(storage).preview(gate.state().revision,setOf(item.id)) }
        assertTrue(File(path,"unknown").exists())
    }
    @Test fun crashAfterRenameNeedsExplicitResumeAndCanReturnIntactGroup() = fixture { root,files,gate,storage ->
        val item = seal(root,storage)
        val crash = object: PrivateCopyStorage by storage {
            override fun quarantine(item: PrivateCopy,operationId: String) { storage.quarantine(item,operationId);throw IOException("after durable rename") }
        }
        val cleanup = PrivateCopyCleanup(crash);val p = cleanup.preview(gate.state().revision,setOf(item.id))
        val partial = cleanup.confirm(confirmation(p));assertEquals(CleanupPhase.PARTIAL,partial.phase)
        val fresh = PrivateCopyCleanup(RegisteredCopyStorage(files,CopyMaintenance(files.metadata())))
        val seen = fresh.operations().single();assertEquals(CleanupPhase.PARTIAL,seen.phase)
        assertEquals(item.members,fresh.inventory().let { storage.remaining(item,partial.operationId) })
        assertFalse(File(root,gate.state().copies.single().path).exists())
        assertTrue(seen.items.single().quarantined)
        assertEquals(CleanupPhase.RESTORED,fresh.restoreSurvivingGroup(seen.operationId,item.id).phase)
        assertTrue(File(root,gate.state().copies.single().path).exists())
    }
    @Test fun interruptedPreparedJournalAndFailedWriterStayProtected() = fixture { root,files,gate,storage ->
        val item = seal(root,storage);val metadata = files.metadata();var fail = true
        val faultGate = CopyMaintenance(object: CopyMetadata {
            override fun read() = metadata.read()
            override fun writeDurably(value: String) {
                metadata.writeDurably(value)
                if(fail && codec.decodeFromString<CopyMaintenanceState>(value).journals.any { it.phase == CleanupPhase.PREPARED }) {
                    fail = false;throw IOException("metadata directory fsync result unknown")
                }
            }
        })
        val cleanup = PrivateCopyCleanup(RegisteredCopyStorage(files,faultGate));val p = cleanup.preview(gate.state().revision,setOf(item.id))
        assertFailsWith<IOException> { cleanup.confirm(confirmation(p)) }
        assertTrue(File(root,gate.state().copies.single().path).exists())
        val reopenedGate = CopyMaintenance(files.metadata());val reopened = PrivateCopyCleanup(RegisteredCopyStorage(files,reopenedGate))
        val partial = reopened.operations().single();assertEquals(CleanupPhase.PARTIAL,partial.phase)
        assertFalse(File(root,"cleanup-quarantine").exists())
        assertFailsWith<CoreError> { reopenedGate.writing("must-not-start") { error("writer entered") } }
        assertEquals(CleanupPhase.DONE,reopened.resume(partial.operationId,reopened.remainingHash(partial)).phase)
        assertFailsWith<IOException> { reopenedGate.writing("interrupted-writer",blocker = CopyBlocker.EXPORT_WRITING) { throw IOException("crash") } }
        assertEquals(CopyBlocker.EXPORT_WRITING,CopyMaintenance(files.metadata()).state().references.single().blocker)
    }
    @Test fun longWriterIsDurablyProtectedWithoutBlockingInspection() = fixture { root,_,gate,storage ->
        val item = seal(root,storage);val cleanup = PrivateCopyCleanup(storage)
        val p = cleanup.preview(gate.state().revision,setOf(item.id))
        val entered = java.util.concurrent.CountDownLatch(1);val finish = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val writer = pool.submit { gate.writing("long-writer",item.id,CopyBlocker.EXPORT_WRITING) {
                entered.countDown();check(finish.await(5,java.util.concurrent.TimeUnit.SECONDS))
            } }
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
            val observed = pool.submit<CopyInventory> { cleanup.inventory() }.get(2,java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(CopyBlocker.EXPORT_WRITING in observed.items.single().hardBlockers)
            assertFailsWith<CoreError> { cleanup.confirm(confirmation(p)) }
            assertTrue(File(root,gate.state().copies.single().path).exists())
            finish.countDown();writer.get(2,java.util.concurrent.TimeUnit.SECONDS)
            assertTrue(cleanup.inventory().items.single().selectable)
        } finally { finish.countDown();pool.shutdownNow();pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS) }
    }
    @Test fun unlinkInterruptionPersistsRemainingBytesWithoutAutomaticContinuation() = fixture { root,files,gate,storage ->
        val item = seal(root,storage);var unlinks = 0
        val crash = object: PrivateCopyStorage by storage {
            override fun removeMember(item: PrivateCopy,member: CopyMember,operationId: String) {
                storage.removeMember(item,member,operationId);unlinks++;throw IOException("after unlink before receipt")
            }
        }
        val cleanup = PrivateCopyCleanup(crash);val p = cleanup.preview(gate.state().revision,setOf(item.id))
        val partial = cleanup.confirm(confirmation(p));assertEquals(CleanupPhase.PARTIAL,partial.phase);assertEquals(1,unlinks)
        val fresh = PrivateCopyCleanup(RegisteredCopyStorage(files,CopyMaintenance(files.metadata())))
        val seen = fresh.operations().single();assertEquals(1,seen.items.single().deletedMemberIds.size)
        assertEquals(1,storage.remaining(item,seen.operationId).size)
        assertFailsWith<CoreError> { fresh.restoreSurvivingGroup(seen.operationId,item.id) }
        assertFailsWith<CoreError> { fresh.resume(seen.operationId,"unreviewed") }
        assertEquals(1,storage.remaining(item,seen.operationId).size)
        assertEquals(CleanupPhase.DONE,fresh.resume(seen.operationId,fresh.remainingHash(seen)).phase)
        assertTrue(storage.remaining(item,seen.operationId).isEmpty())
    }
}
