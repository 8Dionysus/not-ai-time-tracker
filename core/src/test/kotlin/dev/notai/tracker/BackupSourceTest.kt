package dev.notai.tracker

import org.junit.Test
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.OutputStream

class BackupSourceTest {
    @Test fun streamingReceiptNamesTheCapturedRevisionEvenWhenHistoryChangesDuringSerialization() {
        val clock = FakeClock();val tracker = Tracker(MemoryStore(),clock)
        tracker.saveType(tracker.snapshot().token,ActivityType("before","Before"))
        val revision = tracker.data().revision
        val bytes = ByteArrayOutputStream();var changed = false
        val output = object: OutputStream() {
            override fun write(value: Int) {
                if(!changed) { changed = true;tracker.saveType(tracker.snapshot().token,ActivityType("later","Later")) }
                bytes.write(value)
            }
        }
        val receipt = Backups(tracker,MemoryRollback()).writeSnapshot(output)
        val backup = StreamingBackup.read { bytes.toByteArray().inputStream() }
        assertTrue(changed);assertEquals(revision,receipt.revision);assertEquals(revision,backup.data.revision)
        assertEquals(backup.data.datasetId,receipt.datasetId)
        assertEquals(clock.wall,receipt.asOf)
        assertFalse(backup.data.types.any { it.id == "later" });assertTrue(tracker.data().types.any { it.id == "later" })
        assertEquals(15,backup.formatVersion)
    }
}
