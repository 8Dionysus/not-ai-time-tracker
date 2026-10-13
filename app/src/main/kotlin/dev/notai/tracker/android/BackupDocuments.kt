package dev.notai.tracker.android

import dev.notai.tracker.Backups
import dev.notai.tracker.HistoryFiles
import dev.notai.tracker.MAX_BACKUP_BYTES
import dev.notai.tracker.RestorePreview
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** The SAF restore boundary shares the core byte limit, strict UTF-8 and BOM handling. */
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
