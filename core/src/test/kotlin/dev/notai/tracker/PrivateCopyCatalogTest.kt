package dev.notai.tracker

import java.io.File
import java.nio.file.Files
import org.junit.Test
import kotlin.test.*

class PrivateCopyCatalogTest {
    private fun fixture(block: (File,DescriptorCopyFiles,CopyMaintenance,PrivateCopyCatalog)->Unit) {
        val library = System.getProperty("tracker.cleanup.library");org.junit.Assume.assumeNotNull(library)
        val root = Files.createTempDirectory("tracker-private-catalog-").toFile()
        try { DescriptorCopyFiles(root.path,library).use { files ->
            val gate = CopyMaintenance(files.metadata());block(root,files,gate,PrivateCopyCatalog(files,gate))
        } } finally { root.deleteRecursively() }
    }
    private fun archive(root: File,id: String,linked: File? = null,retired: Boolean = false): File {
        val dir = File(root,"database-archives/$id").apply { mkdirs() };val data = File(dir,"tracker.db")
        if(linked == null) data.writeText("isolated preserved SQLite fixture") else Files.createLink(data.toPath(),linked.toPath())
        val entry = PreservedDatabaseMember(data.name,data.length(),sha256(data.readBytes()))
        File(dir,"manifest.json").writeText(codec.encodeToString(StoredDatabaseArchive(id,"tracker.db",listOf(entry))))
        if(retired) File(dir,"retired").mkdirs().also { File(dir,"retired/tracker.db-wal").writeText("retired WAL fixture") }
        return data
    }
    private val a = "00000000-0000-0000-0000-000000000001"
    private val b = "00000000-0000-0000-0000-000000000002"
    private val c = "00000000-0000-0000-0000-000000000003"
    @Test fun finalRetiredArchiveGraphRegistersUnderProtectiveReferencesAndReopensExactly() = fixture { root,files,gate,catalog ->
        val data = archive(root,a);archive(root,b,data,true)
        val unknown = gate.acquire("legacy-ui-lifecycle",null,CopyBlocker.UNKNOWN_FILE_OR_STATE)
        catalog.finalizeArchive(a);catalog.finalizeArchive(b)
        val report = catalog.registerLegacy(emptySet()) { error("No forms") }
        assertEquals(2,report.registeredIds.size);assertTrue(report.refused.isEmpty())
        assertTrue(catalog.inventory().items.all { CopyBlocker.UNKNOWN_FILE_OR_STATE in it.hardBlockers })
        val before = gate.state();assertEquals(report,catalog.registerLegacy(emptySet()) { });assertEquals(before,gate.state())
        gate.release(unknown)
        val reopened = PrivateCopyCatalog(files,CopyMaintenance(files.metadata()))
        assertTrue(reopened.inventory().items.all { it.selectable });assertFalse(File(root,"cleanup-quarantine").exists())
        val selected = reopened.inventory().items.single { i -> gate.state().copies.single { it.item.id == i.id }.path.endsWith(a) }
        val other = reopened.inventory().items.single { it.id != selected.id }
        val reader = gate.acquire("fixture-rescue-reader",other.id,CopyBlocker.REQUIRED_BY_PENDING_RECOVERY)
        assertTrue(CopyBlocker.REQUIRED_BY_PENDING_RECOVERY in reopened.inventory().items.single { it.id == selected.id }.hardBlockers)
        gate.release(reader)
        val cleanup = PrivateCopyCleanup(RegisteredCopyStorage(files,gate));val inventory = cleanup.inventory();val p = cleanup.preview(inventory.revision,setOf(selected.id))
        val confirmation = CleanupConfirmation(p.id,p.revision,p.items.associate { it.id to it.sha256 },
            p.items.flatMap { i -> i.confirmableWarnings.map { WarningAcknowledgement(i.id,it) } }.toSet(),emptySet(),"fixture-delete-a")
        assertEquals(CleanupPhase.DONE,cleanup.confirm(confirmation).phase)
        assertEquals("isolated preserved SQLite fixture",File(root,"database-archives/$b/tracker.db").readText())
        assertEquals("retired WAL fixture",File(root,"database-archives/$b/retired/tracker.db-wal").readText())
    }
    @Test fun incompleteComponentAndPendingDraftDoNotPreventIndependentLegacyRegistration() = fixture { root,_,gate,catalog ->
        val bad = archive(root,a);Files.createLink(File(root,"outside-archive-alias").toPath(),bad.toPath())
        archive(root,b);archive(root,c)
        val draftId = sha256("database-archives/$c".toByteArray())
        val ref = gate.acquire("archive-draft:$c",draftId,CopyBlocker.REQUIRED_BY_PENDING_RECOVERY)
        val report = catalog.registerLegacy(emptySet()) { }
        assertEquals(listOf(sha256("database-archives/$b".toByteArray())),report.registeredIds)
        assertEquals(setOf(sha256("database-archives/$a".toByteArray()),draftId),report.refused.map { it.itemId }.toSet())
        assertFalse(File(root,"database-archives/$c/cleanup-seal.json").exists());assertTrue(File(root,"outside-archive-alias").exists())
        gate.release(ref)
        val pending = catalog.registerLegacy(setOf(c)) { }
        assertTrue(pending.refused.any { it.itemId == draftId && CopyBlocker.REQUIRED_BY_PENDING_RECOVERY in it.blockers })
    }
    @Test fun rollbackRestoreFormAndFinishedExportUseOneRegistryAndWriterInvalidatesOldPreview() = fixture { root,files,gate,catalog ->
        val clock = FakeClock();val backup = StreamingBackup.portable(Dataset(),clock.now())
        for(path in listOf("rollback/rollback-$a.json","restore-inputs/$a.json")) {
            val f = File(root,path);f.parentFile.mkdirs();f.outputStream().use { StreamingBackup.write(backup,it) }
        }
        File(root,"forms").mkdirs();File(root,"forms/$a.json").writeText("{\"name\":\"own draft\"}")
        File(root,"forms/$b.json").writeText("invalid fixture")
        val export = File(root,"exports/$a").apply { mkdirs() };File(export,"data").writeText("own export")
        fun writeReceipt(state: String) = File(export,"operation.json").writeText("""{"id":"$a","mime":"text/plain","title":"fixture","state":"$state","bytes":10,"sha256":"${sha256("own export".toByteArray())}","error":null,"resultReceived":true}""")
        writeReceipt("COMPLETE")
        val validator: (String)->Unit = { kotlinx.serialization.json.Json.parseToJsonElement(it) }
        val result = catalog.registerLegacy(emptySet(),validator)
        assertEquals(4,result.registeredIds.size);assertEquals(1,result.refused.size)
        val storage = RegisteredCopyStorage(files,gate);val cleanup = PrivateCopyCleanup(storage);val inventory = cleanup.inventory()
        val exportId = sha256("exports/$a".toByteArray());val preview = cleanup.preview(inventory.revision,setOf(exportId))
        gate.writing("fixture-export-retry",exportId) { gate.invalidateRegistration(exportId);writeReceipt("READY") }
        assertFalse(catalog.inventory().items.any { it.id == exportId });assertFails { catalog.registerExport(a) }
        val confirmation = CleanupConfirmation(preview.id,preview.revision,preview.items.associate { it.id to it.sha256 },emptySet(),emptySet(),"stale-fixture")
        assertFailsWith<CoreError> { cleanup.confirm(confirmation) };assertTrue(File(export,"data").exists())
        gate.writing("fixture-export-finish",exportId) { writeReceipt("COMPLETE");catalog.registerExport(a) }
        assertTrue(catalog.inventory().items.any { it.id == exportId && it.selectable })
    }
    @Test fun finalSealCannotSilentlyAcceptNewRetiredBytesOrSymlinkTargets() = fixture { root,_,_,catalog ->
        archive(root,a,retired = true);catalog.finalizeArchive(a);catalog.registerFinalizedArchives()
        val seal = File(root,"database-archives/$a/cleanup-seal.json");val before = seal.readBytes()
        File(root,"database-archives/$a/retired/tracker.db-journal").writeText("unexpected later bytes")
        assertFailsWith<CoreError> { catalog.finalizeArchive(a) };assertContentEquals(before,seal.readBytes())
        assertFalse(catalog.inventory().items.single().selectable)
        File(root,"forms").mkdirs();val outside = File(root,"outside").apply { writeText("preserved") }
        Files.createSymbolicLink(File(root,"forms/$a.json").toPath(),outside.toPath())
        assertFails { catalog.registerForm("$a.json") { } };assertEquals("preserved",outside.readText())
    }
}
