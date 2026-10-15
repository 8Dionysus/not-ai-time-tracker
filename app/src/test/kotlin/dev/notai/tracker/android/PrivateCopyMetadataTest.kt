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
}
