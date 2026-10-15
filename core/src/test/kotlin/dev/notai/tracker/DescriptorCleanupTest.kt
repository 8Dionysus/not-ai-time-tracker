package dev.notai.tracker

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assume.assumeNotNull
import org.junit.Test
import kotlin.test.*

/** Real isolated files and JNI syscalls, not an Android/OEM acceptance claim. */
class DescriptorCleanupTest {
    @Test fun rootPinsSearchOnlyAncestorsWithoutRequiringTheirDirectoryListing() {
        val library = System.getProperty("tracker.cleanup.library");assumeNotNull(library)
        val ancestor = Files.createTempDirectory("tracker-search-only-")
        val owned = Files.createDirectory(ancestor.resolve("owned-private-root"))
        try {
            // Only this test's own ancestor changes mode. No host/system permissions change.
            Files.setPosixFilePermissions(ancestor,java.nio.file.attribute.PosixFilePermissions.fromString("--x--x--x"))
            DescriptorCopyFiles(owned.toString(),library).use { files ->
                assertTrue(files.rootIdentity().directory)
                files.metadata().writeDurably("owned fixture")
                assertEquals("owned fixture",files.metadata().read())
            }
        } finally {
            Files.setPosixFilePermissions(ancestor,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            ancestor.toFile().deleteRecursively()
        }
    }
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
    @Test fun unknownUnicodeSiblingBlocksOnlyItself() = fixture { root,_,_,storage ->
        val item = seal(root,storage)
        val unknown = File(root,"exports/личное").apply { writeText("unregistered fixture; preserve") }
        val inventory = storage.inventory()
        assertTrue(inventory.items.single { it.id == item.id }.selectable)
        assertEquals(setOf(CopyBlocker.UNKNOWN_FILE_OR_STATE),inventory.items.single { it.id != item.id }.hardBlockers)
        assertEquals("unregistered fixture; preserve",unknown.readText())
    }
    @Test fun unrepresentableTimestampBlocksOnlyThatCopyWithoutSignedOverflow() = fixture { root,files,gate,storage ->
        val safe = seal(root,storage);val unsupported = seal(root,storage)
        val path = gate.state().copies.single { it.item.id == unsupported.id }.path
        // This JDK's FileTime conversion clamps beyond signed nanoseconds. Set and verify the
        // actual POSIX fixture timestamp with the same Python3 required by the catalog build.
        val process = ProcessBuilder("python3","-c",
            "import os,sys; ns=13569465600000000000; os.utime(sys.argv[1],ns=(ns,ns)); assert os.stat(sys.argv[1]).st_mtime_ns == ns",
            File(root,path).absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0,process.waitFor(),output)
        assertFailsWith<IOException> { files.stat(path) }
        val inventory = storage.inventory()
        assertTrue(inventory.items.single { it.id == safe.id }.selectable)
        assertTrue(CopyBlocker.PATH_NOT_SAFE in inventory.items.single { it.id == unsupported.id }.hardBlockers)
        val cleanup = PrivateCopyCleanup(storage);val p = cleanup.preview(inventory.revision,setOf(safe.id))
        assertEquals(CleanupPhase.DONE,cleanup.confirm(confirmation(p)).phase)
        assertEquals("fixture bytes",File(root,"$path/data").readText())
    }
    @Test fun referencedRecoveryCopyCannotHideLastUnusedCopyWarning() = fixture { root,_,gate,storage ->
        fun rollback(name: String): PrivateCopy {
            val file = File(root,"rollback/$name.json").apply { parentFile.mkdirs();writeText("fixture $name") }
            val members = listOf(CopyMember("data",file.length(),sha256(file.readBytes())))
            val item = PrivateCopy(newId(),CopyKind.ROLLBACK,"VERIFIED",file.length(),payloadHash(members),members)
            storage.seal(item,"rollback/${file.name}",false,mapOf("data" to file.name));return item
        }
        val active = rollback("active");val unused = rollback("unused")
        val ref = gate.acquire("fixture-restore",active.id,CopyBlocker.RESTORE_SOURCE_IN_USE)
        val inventory = storage.inventory()
        assertTrue(CopyBlocker.RESTORE_SOURCE_IN_USE in inventory.items.single { it.id == active.id }.hardBlockers)
        assertTrue(CopyWarning.LAST_RECOVERY_COPY in inventory.items.single { it.id == unused.id }.confirmableWarnings)
        gate.release(ref)
        assertTrue(storage.inventory().items.all { CopyWarning.LAST_RECOVERY_COPY !in it.confirmableWarnings })
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
    @Test fun preparedButMissingOriginalCannotBeCertifiedDeletedOrRestored() = fixture { root,files,gate,storage ->
        val item = seal(root,storage);val path = gate.state().copies.single().path
        val fault = object: PrivateCopyStorage by storage {
            override fun saveDurably(journal: CleanupJournal) {
                storage.saveDurably(journal)
                if(journal.phase == CleanupPhase.PREPARED) throw IOException("fixture before any quarantine")
            }
        }
        val cleanup = PrivateCopyCleanup(fault);val p = cleanup.preview(gate.state().revision,setOf(item.id))
        assertFailsWith<IOException> { cleanup.confirm(confirmation(p)) }
        val held = File(root,"owned-fixture-held");Files.move(File(root,path).toPath(),held.toPath())
        val freshStorage = RegisteredCopyStorage(files,CopyMaintenance(files.metadata()));val fresh = PrivateCopyCleanup(freshStorage)
        val seen = fresh.operations().single()
        assertEquals(CleanupPhase.PARTIAL,seen.phase);assertFalse(seen.items.single().quarantined)
        assertFalse(seen.items.single().restored);assertTrue(seen.items.single().deletedMemberIds.isEmpty())
        assertFalse(freshStorage.verifyAbsent(item,seen.operationId));assertFalse(File(root,"cleanup-quarantine").exists())
        assertEquals(CleanupPhase.PARTIAL,fresh.resume(seen.operationId,fresh.remainingHash(seen)).phase)
        assertEquals("fixture bytes",File(held,"data").readText())
        assertEquals(CleanupPhase.PARTIAL,fresh.operations().single().phase)
    }
    @Test fun lastUnlinkBeforeReceiptStillHasDurableQuarantineProof() = fixture { root,files,gate,storage ->
        val file = File(root,"rollback/one.json").apply { parentFile.mkdirs();writeText("isolated last unlink") }
        val member = CopyMember("data",file.length(),sha256(file.readBytes()))
        val item = PrivateCopy("single",CopyKind.ROLLBACK,"VERIFIED",member.bytes,payloadHash(listOf(member)),listOf(member))
        storage.seal(item,"rollback/one.json",false,mapOf("data" to "one.json"))
        val fault = object: PrivateCopyStorage by storage {
            override fun removeMember(item: PrivateCopy,member: CopyMember,operationId: String) {
                storage.removeMember(item,member,operationId);throw IOException("fixture after last unlink before receipt")
            }
        }
        val cleanup = PrivateCopyCleanup(fault);val partial = cleanup.confirm(confirmation(cleanup.preview(gate.state().revision,setOf(item.id))))
        assertEquals(CleanupPhase.PARTIAL,partial.phase);assertTrue(partial.items.single().quarantined)
        assertTrue(partial.items.single().deletedMemberIds.isEmpty())
        val fresh = PrivateCopyCleanup(RegisteredCopyStorage(files,CopyMaintenance(files.metadata())));val seen = fresh.operations().single()
        assertEquals(CleanupPhase.DONE,seen.phase);assertEquals(setOf("data"),seen.items.single().deletedMemberIds)
        assertFalse(seen.items.single().restored);assertFalse(file.exists())
    }
    @Test fun emptyContainerFinalizationBeforeReceiptIsDoneAndNeverAnInventedRestore() = fixture { root,files,gate,storage ->
        File(root,"exports/empty").mkdirs()
        val item = PrivateCopy("empty",CopyKind.EXPORT,"COMPLETE",0,payloadHash(emptyList<CopyMember>()),emptyList())
        storage.seal(item,"exports/empty",true,emptyMap())
        val fault = object: PrivateCopyStorage by storage {
            override fun finishGroup(item: PrivateCopy,operationId: String) {
                storage.finishGroup(item,operationId);throw IOException("fixture after final rmdir before DONE")
            }
        }
        val cleanup = PrivateCopyCleanup(fault);val partial = cleanup.confirm(confirmation(cleanup.preview(gate.state().revision,setOf(item.id))))
        assertEquals(CleanupPhase.PARTIAL,partial.phase);assertTrue(partial.items.single().quarantined)
        val fresh = PrivateCopyCleanup(RegisteredCopyStorage(files,CopyMaintenance(files.metadata())));val seen = fresh.operations().single()
        assertEquals(CleanupPhase.DONE,seen.phase);assertFalse(seen.items.single().restored)
        assertFalse(File(root,"exports/empty").exists())
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
