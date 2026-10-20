package dev.notai.tracker.android

import dev.notai.tracker.Backups
import dev.notai.tracker.HistoryFiles
import dev.notai.tracker.MAX_BACKUP_BYTES
import dev.notai.tracker.RestorePreview
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Full user-owned JSON uses Backups.write -> ExportOperations.prepare/write -> SAF CreateDocument.
 * Staging in noBackupFilesDir is NOT an external backup: only a completed provider write is one.
 * ExportWriteResult.verified distinguishes SHA-256 readback from a write-only provider receipt.
 * Restore is a separate OpenDocument -> prepare -> explicit Backups.apply(preview) flow; preparing
 * never replaces data, apply verifies rollback and uses the preview's original token atomically.
 * Do not retain URI grants, enable Auto Backup or add network permissions for this one-shot flow.
 * The SAF restore boundary shares the core byte limit, strict UTF-8 and BOM handling.
 */
internal object BackupDocuments {
    fun prepare(context: android.content.Context,input: InputStream,backups: Backups): RestorePreview {
        val file = stageRestore(context,input)
        return backups.prepare { file.inputStream() }
    }
    fun prepare(input: InputStream, backups: Backups): RestorePreview {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (output.size() <= MAX_BACKUP_BYTES) {
            val count = input.read(chunk, 0, minOf(chunk.size, MAX_BACKUP_BYTES + 1 - output.size()))
            if (count < 0) break
            if (count == 0) {
                val byte = input.read()
                if (byte < 0) break
                output.write(byte)
            } else output.write(chunk, 0, count)
        }
        return HistoryFiles.inspect(output.toByteArray()).prepareRestore(backups)
    }
}
