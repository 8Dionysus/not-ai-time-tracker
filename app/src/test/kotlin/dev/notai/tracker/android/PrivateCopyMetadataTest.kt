package dev.notai.tracker.android

import dev.notai.tracker.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.Assert.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PrivateCopyMetadataTest {
    private fun fixture(block: (java.io.File)->Unit) {
        val root = Files.createTempDirectory("tracker-android-metadata-").toFile()
        val sync = DirectoryDurability.sync;val rename = DirectoryDurability.rename
        try {
            DirectoryDurability.sync = { java.nio.channels.FileChannel.open(it.toPath(),java.nio.file.StandardOpenOption.READ).use { fd -> fd.force(true) } }
            DirectoryDurability.rename = { a,b -> Files.move(a.toPath(),b.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);Unit }
            block(java.io.File(root,"maintenance.json"))
        } finally { DirectoryDurability.sync = sync;DirectoryDurability.rename = rename;root.deleteRecursively() }
    }
    @Test fun writerCapacityRefusalPreservesReferencesAndAllowsExplicitRecoveryRelease() = fixture { file ->
        val metadata = PrivateFileMaintenance.metadata(file,1024);val gate = CopyMaintenance(metadata,1024)
        val source = gate.acquire("fixture-restore","copy",CopyBlocker.RESTORE_SOURCE_IN_USE)
        val bytes = file.readBytes();val before = gate.state()
        assertThrows(IllegalStateException::class.java) { gate.acquire("я".repeat(700),"copy",CopyBlocker.EXPORT_WRITING) }
        assertArrayEquals(bytes,file.readBytes());assertEquals(before,gate.state())
        assertEquals(listOf(source),CopyMaintenance(PrivateFileMaintenance.metadata(file,1024),1024).state().references)
        gate.release(source) // An explicit confirmed owner action still works; no automatic reference loss.
        assertTrue(gate.state().references.isEmpty());assertEquals(before.revision+1,gate.state().revision)
        assertFalse(file.parentFile!!.listFiles()!!.any { it.name.endsWith(".part") })
    }
    @Test fun rawOversizeAndMalformedUtf8RefuseReadWithoutChangingSource() = fixture { file ->
        val metadata = PrivateFileMaintenance.metadata(file,32)
        metadata.writeDurably("retained recovery source")
        val previous = file.readBytes()
        assertThrows(IllegalStateException::class.java) { metadata.writeDurably("я".repeat(17)) };assertArrayEquals(previous,file.readBytes())
        file.writeBytes(ByteArray(33) { 32 });val tooLarge = file.readBytes()
        assertThrows(IllegalStateException::class.java) { metadata.read() };assertArrayEquals(tooLarge,file.readBytes())
        file.writeBytes(byteArrayOf(0xc3.toByte(),0x28));val malformed = file.readBytes()
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { metadata.read() };assertArrayEquals(malformed,file.readBytes())
    }
    @Test fun specialMetadataFileRefusesBeforeOpeningABlockingStream() = fixture { file ->
        val p = ProcessBuilder("mkfifo",file.absolutePath).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().use { it.readText() };assertEquals(output,0,p.waitFor())
        assertFalse(Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java).isRegularFile)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val read = pool.submit<Boolean> {
                try { PrivateFileMaintenance.metadata(file).read();false } catch(_: IllegalStateException) { true }
            }
            try { assertTrue(read.get(2,java.util.concurrent.TimeUnit.SECONDS)) }
            catch(e: java.util.concurrent.TimeoutException) {
                // Unblock only this fixture FIFO if the regression reopens it as a stream.
                java.io.FileOutputStream(file).use { it.write(32) }
                throw e
            }
        } finally { pool.shutdownNow();pool.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS) }
        assertFalse(Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java).isRegularFile)
    }
    @Test fun chooserReferenceSurvivesReopenUntilTheExistingResultCallbackConfirmsCompletion() = fixture { _ ->
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val exports = ExportOperations(app);val op = exports.begin("text/plain","fixture only")
        exports.prepare(op.id) { it.write("fixture export".toByteArray()) }
        exports.awaitResult(op.id);exports.awaitResult(op.id)
        val gate = PrivateFileMaintenance.forContext(app)
        val held = gate.state().references.single { it.owner == "export-chooser:${op.id}" }
        val reopened = CopyMaintenance(PrivateFileMaintenance.metadata(java.io.File(app.noBackupFilesDir,"private-copy-maintenance.json")))
        assertTrue(held in reopened.state().references);assertEquals(CopyBlocker.CHOOSER_RESULT_PENDING,held.blocker)
        exports.cancel(op.id);assertTrue(held in gate.state().references)
        exports.receiveResult(op.id,true);exports.receiveResult(op.id,true)
        assertFalse(gate.state().references.any { it.owner == held.owner })
        assertTrue(gate.state().references.any { it.owner == "legacy-ui-lifecycle" })
        assertEquals("CANCELLED",exports.read(op.id).state);assertTrue(exports.read(op.id).resultReceived)
        assertEquals("fixture export",java.io.File(app.noBackupFilesDir,"exports/${op.id}/data").readText())
    }
    @Test fun failedRollbackConsumerKeepsItsDurableReferenceForExplicitRecovery() = fixture { _ ->
        val app = org.robolectric.RuntimeEnvironment.getApplication();val rollback = PrivateRollbackStore(app)
        assertThrows(java.io.IOException::class.java) { rollback.protecting<Unit> { throw java.io.IOException("fixture uncertain domain commit") } }
        val gate = CopyMaintenance(PrivateFileMaintenance.metadata(java.io.File(app.noBackupFilesDir,"private-copy-maintenance.json")))
        val held = gate.state().references.single { it.owner == "rollback-consumer" }
        assertEquals(CopyBlocker.REQUIRED_BY_PENDING_RECOVERY,held.blocker)
        gate.release(held) // Test owner's explicit review/release; never age-based or automatic.
        assertTrue(gate.state().references.any { it.owner == "legacy-ui-lifecycle" })
    }
    @Test fun unavailableNativeBindingRetainsAReadyArchiveReferenceWithoutLosingRawBytes() = fixture { _ ->
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        app.getDatabasePath("tracker.db").apply { parentFile!!.mkdirs();writeText("isolated raw fixture") }
        val archive = DatabaseArchives.capture(app)
        val gate = PrivateFileMaintenance.forContext(app)
        assertFalse(gate.state().references.any { it.owner == "archive-draft:${archive.name}" })
        assertTrue(gate.state().references.any { it.owner == "archive-ready:${archive.name}" })
        assertEquals("isolated raw fixture",java.io.File(archive,"tracker.db").readText())
        assertFalse(PrivateCopyBackend.inspect(app).unavailableCapabilities.isEmpty())
        assertTrue(gate.state().references.any { it.owner == "legacy-ui-lifecycle" })
    }
}
