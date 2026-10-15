package dev.notai.tracker

import java.io.OutputStream
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackupWriteLimitTest {
    @Test fun exporterRejectsARecordItsOwnReaderCannotRestore() {
        val data = Dataset(types = emptyList(),axes = emptyList(),metrics = emptyList(),units = emptyList(),
            catalogVersion = "x".repeat(MAX_BACKUP_RECORD_CHARS))
        val failure = assertFailsWith<CoreError> { StreamingBackup.write(Backup(asOf = 0,data = data),OutputStream.nullOutputStream()) }
        assertEquals(ErrorCode.INVALID_INPUT,failure.code)
    }
}
