package dev.notai.tracker.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.notai.tracker.LongRecordAction
import dev.notai.tracker.Token
import dev.notai.tracker.codec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Answers to the long-record check-in from the shade. Each carries the ticket hash and the token it was shown with;
 * the controller refuses a stale answer instead of applying it to whatever timer is running now. */
object LongRecordActions {
    private const val ACTION = "dev.notai.tracker.LONG_RECORD_ANSWER"
    private const val HASH = "hash"; private const val TOKEN = "token"; private const val ANSWER = "answer"
    fun pending(context: Context,hash: String,token: Token,action: LongRecordAction): PendingIntent {
        val intent = Intent(context,LongRecordActionReceiver::class.java).setAction(ACTION)
            .putExtra(HASH,hash).putExtra(TOKEN,codec.encodeToString(token)).putExtra(ANSWER,action.name)
        return PendingIntent.getBroadcast(context,100+action.ordinal,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    internal fun read(intent: Intent): Triple<String,Token,LongRecordAction>? {
        if(intent.action != ACTION) return null
        val hash = intent.getStringExtra(HASH)?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
        val token = runCatching { codec.decodeFromString<Token>(intent.getStringExtra(TOKEN) ?: return null) }.getOrNull() ?: return null
        val action = runCatching { LongRecordAction.valueOf(intent.getStringExtra(ANSWER) ?: return null) }.getOrNull()
            ?.takeIf { it in setOf(LongRecordAction.CONTINUE,LongRecordAction.SNOOZE,LongRecordAction.STOP) } ?: return null
        return Triple(hash,token,action)
    }
}

class LongRecordActionReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val (hash,token,action) = LongRecordActions.read(intent) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                if(!DatabaseFiles.exists(context)) return@launch
                TrackerRuntime.get(context).attention.ordinary.controller.respond(token,hash,action)
            } catch(e: Exception) {
                if(e is CancellationException) throw e
                android.util.Log.w("LongRecordReminder","Answer no longer current; history unchanged")
            } finally { pending.finish() }
        }
    }
}
