package dev.notai.tracker.android

import android.content.Context
import dev.notai.tracker.*
import java.io.File

/** Shared backend gate. No thread/process expiry can turn an abandoned reference into an orphan.
 * Production deletion remains unbound until frontend lifecycle adoption and Android JNI QA. */
object PrivateFileMaintenance {
    private val roots = mutableMapOf<String,CopyMaintenance>()
    @Synchronized fun forContext(context: Context): CopyMaintenance = forRoot(context.noBackupFilesDir)
    @Synchronized internal fun forRoot(root: File): CopyMaintenance = roots.getOrPut(root.absolutePath) {
        CopyMaintenance(metadata(File(root,"private-copy-maintenance.json")))
    }
    internal fun metadata(file: File,maxBytes: Int = CopyMetadataBytes.MAX_BYTES): CopyMetadata = object: CopyMetadata {
        override fun read(): String? = try {
            val attributes = java.nio.file.Files.readAttributes(file.toPath(),java.nio.file.attribute.BasicFileAttributes::class.java,
                java.nio.file.LinkOption.NOFOLLOW_LINKS)
            check(attributes.isRegularFile) { "Maintenance metadata is not a regular file" }
            check(attributes.size() <= maxBytes) { "Maintenance metadata exceeds reader capacity" }
            java.nio.file.Files.newInputStream(file.toPath(),java.nio.file.StandardOpenOption.READ,java.nio.file.LinkOption.NOFOLLOW_LINKS)
                .use { CopyMetadataBytes.read(it,maxBytes) }
        } catch(_: java.nio.file.NoSuchFileException) { null }
        override fun writeDurably(value: String) = atomicBytes(file,CopyMetadataBytes.encode(value,maxBytes))
    }
    internal fun invalidate(context: Context,relative: String) = forContext(context).invalidateRegistration(PrivateCopyBackend.itemId(relative))
    /** Existing File/preview APIs cannot prove Activity/ViewModel reference release. One durable
     * migration witness blocks cleanup until Opus connects every lifecycle and reviews its removal. */
    internal fun legacy(gate: CopyMaintenance) = gate.locked {
        if(gate.state().references.none { it.owner == "legacy-ui-lifecycle" })
            gate.acquire("legacy-ui-lifecycle",null,CopyBlocker.UNKNOWN_FILE_OR_STATE)
        Unit
    }
    internal fun <T> writing(context: Context,owner: String,blocker: CopyBlocker = CopyBlocker.IN_PROGRESS,block: ()->T): T =
        writingWithReference(context,owner,blocker) { block() }
    internal fun <T> writingWithReference(context: Context,owner: String,blocker: CopyBlocker = CopyBlocker.IN_PROGRESS,block: (CopyReference)->T): T {
        val gate = forContext(context)
        gate.locked { legacy(gate) }
        return if(blocker == CopyBlocker.REQUIRED_BY_PENDING_RECOVERY) gate.locked { gate.writingWithReference(owner,blocker = blocker,block = block) }
            else gate.writingWithReference(owner,blocker = blocker,block = block)
    }
}
