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
        val file = File(root,"private-copy-maintenance.json")
        CopyMaintenance(object: CopyMetadata {
            override fun read(): String? = if(file.exists()) file.readText() else null
            override fun writeDurably(value: String) = atomicText(file,value)
        })
    }
    /** Existing File/preview APIs cannot prove Activity/ViewModel reference release. One durable
     * migration witness blocks cleanup until Opus connects every lifecycle and reviews its removal. */
    internal fun legacy(gate: CopyMaintenance) = gate.locked {
        if(gate.state().references.none { it.owner == "legacy-ui-lifecycle" })
            gate.acquire("legacy-ui-lifecycle",null,CopyBlocker.UNKNOWN_FILE_OR_STATE)
        Unit
    }
    internal fun <T> writing(context: Context,owner: String,blocker: CopyBlocker = CopyBlocker.IN_PROGRESS,block: ()->T): T {
        val gate = forContext(context)
        gate.locked { legacy(gate) }
        return if(blocker == CopyBlocker.REQUIRED_BY_PENDING_RECOVERY) gate.locked { gate.writing(owner,blocker = blocker,block = block) }
            else gate.writing(owner,blocker = blocker,block = block)
    }
}
