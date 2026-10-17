package dev.notai.tracker.android.qa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.notai.tracker.android.atomicText
import java.io.File

/** No new app grant, service, UI, or alarm injection; only adb shell's existing DUMP permission. */
class BackendColdQaReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action!="dev.notai.tracker.qa.BOUNDED_COLD") return
        val tag=intent.getStringExtra("tag") ?: return
        val mode=intent.getStringExtra("mode") ?: return
        if(!tag.matches(Regex("[a-zA-Z0-9-]+")) || mode !in setOf("coldarm","coldcheck","coldcleanup","coldabandon","channelclose")) return
        val pending=goAsync()
        Thread {
            try {
                val result=BackendAcceptance().runColdFromShell(context,checkNotNull(intent.extras))
                val file=File(context.noBackupFilesDir,"backend-acceptance/$tag/shell-$mode.json")
                atomicText(file,result.toString())
            } finally { pending.finish() }
        }.start()
    }
}
