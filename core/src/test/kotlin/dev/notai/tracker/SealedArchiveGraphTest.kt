package dev.notai.tracker

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assume.assumeNotNull
import org.junit.Test
import kotlin.test.*

class SealedArchiveGraphTest {
    private fun fixture(block: (File,DescriptorCopyFiles,CopyMaintenance,RegisteredCopyStorage)->Unit) {
        val library = System.getProperty("tracker.cleanup.library");assumeNotNull(library)
        val root = Files.createTempDirectory("tracker-sealed-graph-").toFile()
        try { DescriptorCopyFiles(root.path,library!!).use { files ->
            val gate = CopyMaintenance(files.metadata());block(root,files,gate,RegisteredCopyStorage(files,gate))
        } } finally { root.deleteRecursively() } // Exclusively this test's newly created fixture.
    }
    private fun archives(root: File): List<ArchiveCopyManifest> {
        val a = File(root,"database-archives/a").apply { mkdirs() }
        val b = File(root,"database-archives/b").apply { mkdirs() }
        File(a,"retired/slots").mkdirs();File(a,"empty").mkdirs()
        File(a,"data").writeText("immutable fixture bytes")
        Files.createLink(File(a,"retired/slots/tracker.db").toPath(),File(a,"data").toPath())
        Files.createLink(File(b,"data").toPath(),File(a,"data").toPath())
        File(a,"manifest.json").writeText("sealed fixture a");File(b,"manifest.json").writeText("sealed fixture b")
        fun manifest(id: String,names: List<String>,dirs: Set<String>): ArchiveCopyManifest {
            val members = names.mapIndexed { i,name -> File(root,"database-archives/$id/$name").let { CopyMember("m$i",it.length(),sha256(it.readBytes())) } }
            return ArchiveCopyManifest(PrivateCopy(id,CopyKind.DATABASE_ARCHIVE,"SEALED",members.sumOf { it.bytes },payloadHash(members),members,
                confirmableWarnings = setOf(CopyWarning.ARCHIVE_MAY_CONTAIN_REMOVED_RECORDS)),"database-archives/$id",names.mapIndexed { i,name -> "m$i" to name }.toMap(),dirs)
        }
        return listOf(manifest("a",listOf("data","manifest.json","retired/slots/tracker.db"),setOf("retired","retired/slots","empty")),
            manifest("b",listOf("data","manifest.json"),emptySet()))
    }
    private fun confirmation(p: CleanupPreview) = CleanupConfirmation(p.id,p.revision,p.items.associate { it.id to it.sha256 },
        p.items.flatMap { i -> i.confirmableWarnings.map { WarningAcknowledgement(i.id,it) } }.toSet(),p.items.map { it.id }.toSet(),newId())
    private fun preview(cleanup: PrivateCopyCleanup,ids: Set<String>) = cleanup.inventory().let { cleanup.preview(it.revision,ids) }

    @Test fun nestedGraphReopensAndUnlinksOnlySelectedAliases() = fixture { root,files,gate,storage ->
        val batch = archives(root);storage.sealArchiveGraph(batch)
        val fresh = RegisteredCopyStorage(files,CopyMaintenance(files.metadata()))
        assertEquals(2,gate.state().format);assertTrue(fresh.inventory().items.all { it.selectable })
        val one = fresh.archiveGraph(setOf("a"));val both = fresh.archiveGraph(setOf("a","b"))
        assertEquals(setOf("a","b"),one.componentIds);assertEquals(3,both.nodes.single { it.aliases.size > 1 }.aliases.size)
        assertEquals(batch.first().item.bytes,one.logicalSelectedBytes)
        assertEquals(File(root,"database-archives/a/manifest.json").length(),one.payloadBytesWithNoRemainingAlias)
        assertTrue(both.payloadBytesWithNoRemainingAlias < both.logicalSelectedBytes)
        val cleanup = PrivateCopyCleanup(fresh)
        assertEquals(CleanupPhase.DONE,cleanup.confirm(confirmation(preview(cleanup,setOf("a")))).phase)
        assertFalse(File(root,"database-archives/a").exists())
        assertEquals("immutable fixture bytes",File(root,"database-archives/b/data").readText())
        assertEquals(1L,files.stat("database-archives/b/data")!!.links)
        val b = cleanup.inventory().items.single();assertTrue(b.selectable);assertTrue(CopyWarning.LAST_RECOVERY_COPY in b.confirmableWarnings)
        val p = preview(cleanup,setOf("b"));val c = confirmation(p)
        assertFailsWith<CoreError> { cleanup.confirm(c.copy(lastCopyAcknowledged = emptySet())) }
        assertEquals(CleanupPhase.DONE,cleanup.confirm(c).phase);assertFalse(File(root,"database-archives/b").exists())
    }
    @Test fun incompleteOrExternalGraphCannotGainAuthority() = fixture { root,_,gate,storage ->
        val batch = archives(root)
        assertFailsWith<CoreError> { storage.sealArchiveGraph(batch.take(1)) };assertTrue(gate.state().copies.isEmpty())
        val external = File(root,"unknown-alias");Files.createLink(external.toPath(),File(root,"database-archives/a/data").toPath())
        assertFailsWith<CoreError> { storage.sealArchiveGraph(batch) };assertTrue(gate.state().copies.isEmpty())
        Files.delete(external.toPath());storage.sealArchiveGraph(batch)
        val cleanup = PrivateCopyCleanup(storage);val p = preview(cleanup,setOf("a"))
        Files.createLink(external.toPath(),File(root,"database-archives/b/data").toPath())
        assertTrue(cleanup.inventory().items.all { CopyBlocker.HARDLINK_GRAPH_INCOMPLETE in it.hardBlockers })
        assertFailsWith<CoreError> { cleanup.confirm(confirmation(p)) };assertFalse(File(root,"cleanup-quarantine").exists())
        assertEquals("immutable fixture bytes",external.readText())
    }
    @Test fun activeGraphOwnerAndRecoveryProtectAllSharedCopies() = fixture { root,files,gate,storage ->
        storage.sealArchiveGraph(archives(root))
        for(blocker in listOf(CopyBlocker.RESTORE_SOURCE_IN_USE,CopyBlocker.LIVE_FORM_REFERENCE,CopyBlocker.UNKNOWN_FILE_OR_STATE,CopyBlocker.EXPORT_WRITING)) {
            val ref = gate.acquire("fixture-reader","b",blocker)
            assertTrue(RegisteredCopyStorage(files,CopyMaintenance(files.metadata())).inventory().items.all { blocker in it.hardBlockers })
            assertEquals(setOf(blocker),storage.archiveGraph(setOf("a")).hardBlockers)
            assertFailsWith<CoreError> { preview(PrivateCopyCleanup(storage),setOf("a")) };gate.release(ref)
        }
        File(root,"recovery-pending.json").writeText("fixture pending recovery")
        assertTrue(storage.inventory().items.all { CopyBlocker.REQUIRED_BY_PENDING_RECOVERY in it.hardBlockers })
        assertTrue(File(root,"database-archives/a/data").exists())
    }
    @Test fun nestedUnknownChildAndAncestorReplacementBlockConnectedGraph() = fixture { root,_,_,storage ->
        storage.sealArchiveGraph(archives(root))
        val unknown = File(root,"database-archives/a/retired/slots/unknown").apply { writeText("preserve") }
        assertTrue(storage.inventory().items.none { it.selectable });Files.delete(unknown.toPath())
        val nested = File(root,"database-archives/a/retired");val saved = File(root,"owned-test-retired")
        Files.move(nested.toPath(),saved.toPath());Files.createSymbolicLink(nested.toPath(),saved.toPath())
        assertTrue(storage.inventory().items.none { it.selectable })
        assertFailsWith<CoreError> { preview(PrivateCopyCleanup(storage),setOf("b")) }
        assertEquals("immutable fixture bytes",File(saved,"slots/tracker.db").readText())
        Files.delete(nested.toPath());Files.move(saved.toPath(),nested.toPath())
        assertTrue(storage.inventory().items.all { it.selectable })
    }
    @Test fun renameCrashCanReturnWholeNestedGraphWithoutDeletingAnything() = fixture { root,files,_,storage ->
        storage.sealArchiveGraph(archives(root))
        val fault = object: PrivateCopyStorage by storage {
            override fun quarantine(item: PrivateCopy,operationId: String) { storage.quarantine(item,operationId);throw IOException("fixture after rename") }
        }
        val cleanup = PrivateCopyCleanup(fault);val partial = cleanup.confirm(confirmation(preview(cleanup,setOf("a"))))
        assertEquals(CleanupPhase.PARTIAL,partial.phase)
        val freshStorage = RegisteredCopyStorage(files,CopyMaintenance(files.metadata()));val fresh = PrivateCopyCleanup(freshStorage)
        val seen = fresh.operations().single();assertTrue(seen.items.single().quarantined);assertTrue(seen.items.single().deletedMemberIds.isEmpty())
        assertFalse(File(root,"database-archives/a").exists());assertEquals(3,freshStorage.archiveGraph(setOf("a")).nodes.single { it.aliases.size > 1 }.aliases.size)
        assertEquals(CleanupPhase.RESTORED,fresh.restoreSurvivingGroup(partial.operationId,"a").phase)
        assertEquals("immutable fixture bytes",File(root,"database-archives/a/retired/slots/tracker.db").readText())
    }
    @Test fun partialAliasUnlinkReconcilesAcrossRestartAndActiveOwnerBlocksResume() = fixture { root,files,gate,storage ->
        storage.sealArchiveGraph(archives(root));var once = true
        val fault = object: PrivateCopyStorage by storage {
            override fun removeMember(item: PrivateCopy,member: CopyMember,operationId: String) {
                storage.removeMember(item,member,operationId);if(once) { once = false;throw IOException("fixture after alias unlink") }
            }
        }
        val cleanup = PrivateCopyCleanup(fault);cleanup.confirm(confirmation(preview(cleanup,setOf("a"))))
        val fresh = PrivateCopyCleanup(RegisteredCopyStorage(files,CopyMaintenance(files.metadata())))
        val ref = gate.acquire("fixture-restore","b",CopyBlocker.RESTORE_SOURCE_IN_USE)
        val seen = fresh.operations().single();assertEquals(1,seen.items.single().deletedMemberIds.size)
        assertFailsWith<CoreError> { fresh.restoreSurvivingGroup(seen.operationId,"a") }
        assertEquals(CleanupPhase.PARTIAL,fresh.resume(seen.operationId,fresh.remainingHash(seen)).phase)
        assertEquals(2L,files.stat("database-archives/b/data")!!.links)
        gate.release(ref)
        assertEquals(CleanupPhase.DONE,fresh.resume(seen.operationId,fresh.remainingHash(seen)).phase)
        assertFalse(File(root,"database-archives/a").exists());assertEquals(1L,files.stat("database-archives/b/data")!!.links)
        assertEquals("immutable fixture bytes",File(root,"database-archives/b/data").readText())
    }
    @Test fun interruptedDirectoryFinalizationIsReadOnlyUntilExplicitResume() = fixture { root,files,gate,storage ->
        storage.sealArchiveGraph(archives(root))
        val fault = object: PrivateCopyStorage by storage {
            override fun finishGroup(item: PrivateCopy,operationId: String) {
                val target = "cleanup-quarantine/"+files.names("cleanup-quarantine").single()
                files.remove("$target/empty",true,gate.state().copies.single { it.item.id == item.id }.directories.getValue("empty"))
                throw IOException("fixture after nested rmdir")
            }
        }
        val cleanup = PrivateCopyCleanup(fault);cleanup.confirm(confirmation(preview(cleanup,setOf("a"))))
        val fresh = PrivateCopyCleanup(RegisteredCopyStorage(files,CopyMaintenance(files.metadata())))
        val seen = fresh.operations().single();val target = "cleanup-quarantine/"+files.names("cleanup-quarantine").single()
        assertEquals(CleanupPhase.PARTIAL,seen.phase);assertTrue(files.stat("$target/retired/slots")!!.directory)
        fresh.operations();assertTrue(files.stat("$target/retired/slots")!!.directory)
        assertEquals(CleanupPhase.DONE,fresh.resume(seen.operationId,fresh.remainingHash(seen)).phase)
    }
    @Test fun v1MetadataMigratesWithoutChangingFlatOwnershipOrWritingOnRead() = fixture { _,files,_,_ ->
        val metadata = files.metadata();val legacy = """{"format":1,"revision":7,"references":[],"copies":[],"journals":[]}"""
        metadata.writeDurably(legacy);val gate = CopyMaintenance(metadata)
        assertEquals(2,gate.state().format);assertEquals(7,gate.state().revision);assertEquals(legacy,metadata.read())
        gate.acquire("fixture",null,CopyBlocker.UNKNOWN_FILE_OR_STATE)
        assertEquals(8,gate.state().revision);assertEquals(2,codec.decodeFromString<CopyMaintenanceState>(metadata.read()!!).format)
    }
    @Test fun duplicateMemberIdentityOrPathRejectsBeforeAuthority() = fixture { root,_,gate,storage ->
        val dir = File(root,"exports/duplicate").apply { mkdirs() };val file = File(dir,"data").apply { writeText("fixture") }
        val member = CopyMember("data",file.length(),sha256(file.readBytes()))
        val duplicateIds = listOf(member,member)
        val item = PrivateCopy("duplicate",CopyKind.EXPORT,"COMPLETE",member.bytes*2,payloadHash(duplicateIds),duplicateIds)
        assertFailsWith<CoreError> { storage.seal(item,"exports/duplicate",true,mapOf("data" to "data")) }
        val duplicatePaths = listOf(member,member.copy(opaqueId = "other"))
        assertFailsWith<CoreError> { storage.seal(item.copy(members = duplicatePaths,sha256 = payloadHash(duplicatePaths)),"exports/duplicate",true,mapOf("data" to "data","other" to "data")) }
        assertTrue(gate.state().copies.isEmpty());assertEquals("fixture",file.readText())
    }
    @Test fun externalAliasInsertedImmediatelyBeforeUnlinkStopsWithoutDeletingIt() = fixture { root,files,_,storage ->
        storage.sealArchiveGraph(archives(root));val external = File(root,"unregistered-live-fixture")
        val fault = object: PrivateCopyStorage by storage {
            override fun removeMember(item: PrivateCopy,member: CopyMember,operationId: String) {
                val target = "cleanup-quarantine/"+files.names("cleanup-quarantine").single()
                Files.createLink(external.toPath(),File(root,"$target/data").toPath())
                storage.removeMember(item,member,operationId)
            }
        }
        val cleanup = PrivateCopyCleanup(fault);val partial = cleanup.confirm(confirmation(preview(cleanup,setOf("a"))))
        assertEquals(CleanupPhase.PARTIAL,partial.phase);assertTrue(partial.items.single().deletedMemberIds.isEmpty())
        assertEquals("immutable fixture bytes",external.readText());assertEquals(4L,files.stat("database-archives/b/data")!!.links)
        assertFailsWith<CoreError> { cleanup.operations() } // Cannot certify an incomplete graph as an exact safe resume.
        Files.delete(external.toPath()) // Only the explicitly created external fixture alias.
        val fresh = PrivateCopyCleanup(RegisteredCopyStorage(files,CopyMaintenance(files.metadata())))
        val seen = fresh.operations().single();assertTrue(seen.items.single().deletedMemberIds.isEmpty())
        assertEquals(CleanupPhase.DONE,fresh.resume(seen.operationId,fresh.remainingHash(seen)).phase)
        assertEquals("immutable fixture bytes",File(root,"database-archives/b/data").readText())
    }
    @Test fun uncertainRegistrationIsAtomicAndDoesNotMutateArchiveBytes() = fixture { root,files,_,_ ->
        val batch = archives(root);val metadata = files.metadata()
        val gate = CopyMaintenance(object: CopyMetadata {
            override fun read() = metadata.read()
            override fun writeDurably(value: String) { metadata.writeDurably(value);throw IOException("fixture after registration fsync") }
        })
        assertFailsWith<IOException> { RegisteredCopyStorage(files,gate).sealArchiveGraph(batch) }
        val fresh = RegisteredCopyStorage(files,CopyMaintenance(metadata))
        assertEquals(2,fresh.inventory().items.size);assertTrue(fresh.inventory().items.all { it.selectable })
        assertTrue(fresh.operations().isEmpty());assertFalse(File(root,"cleanup-quarantine").exists())
        assertEquals("immutable fixture bytes",File(root,"database-archives/a/data").readText())
    }
    @Test fun expandingGraphDoesNotTrustStaleLinkCountsOrUnregisteredNewOwner() = fixture { root,_,gate,storage ->
        val batch = archives(root)
        // Remove only this fixture's B paths, so A's internal two aliases initially form a closed graph.
        Files.delete(File(root,"database-archives/b/data").toPath());Files.delete(File(root,"database-archives/b/manifest.json").toPath());Files.delete(File(root,"database-archives/b").toPath())
        storage.sealArchiveGraph(batch.take(1));assertTrue(storage.inventory().items.single().selectable)
        gate.writing("fixture-create-b","b") {
            File(root,"database-archives/b").mkdirs();Files.createLink(File(root,"database-archives/b/data").toPath(),File(root,"database-archives/a/data").toPath())
            File(root,"database-archives/b/manifest.json").writeText("sealed fixture b")
            assertFailsWith<ArchiveGraphRefusal> { storage.sealArchiveGraph(batch.drop(1)) }
        }
        assertTrue(CopyBlocker.HARDLINK_GRAPH_INCOMPLETE in storage.inventory().items.single { it.id == "a" }.hardBlockers)
        storage.sealArchiveGraph(batch.drop(1));assertTrue(storage.inventory().items.all { it.selectable })
        assertEquals(3,storage.archiveGraph(setOf("a")).nodes.single { it.aliases.size > 1 }.aliases.size)
    }
}
