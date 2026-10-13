package dev.notai.tracker.android

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import dev.notai.tracker.*
import kotlinx.coroutines.*

/** Opt-in, best-effort inexact delivery. No exact-alarm permission, foreground timer service,
 * automatic phase advance, DND or music. Boot/wall-clock uncertainty is still core-owned.
 */
object FocusSignals {
    const val CHANNEL = "focus_completion"
    private const val NOTICE = 43
    private fun preferences(c: Context) = c.getSharedPreferences("focus-signals",Context.MODE_PRIVATE)
    fun allowed(c: Context): Boolean {
        val manager = c.getSystemService(NotificationManager::class.java)
        return (Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }
    private fun channel(c: Context) = c.getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL,"Завершение интервала Focus",NotificationManager.IMPORTANCE_DEFAULT))
    private fun pending(c: Context,id: String,index: Long): PendingIntent {
        val intent = Intent(c,FocusAlarmReceiver::class.java).setAction("dev.notai.tracker.FOCUS_DUE")
            .setData(android.net.Uri.parse("notai://focus/$id/$index")).putExtra("id",id).putExtra("index",index)
        return PendingIntent.getBroadcast(c,0,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    fun refresh(c: Context,view: TrackerView) {
        val settings = view.data.settings.surfaces;val state = view.snapshot().focus;val prefs = preferences(c)
        val alarm = c.getSystemService(AlarmManager::class.java)
        prefs.getString("scheduledId",null)?.let { id -> alarm.cancel(pending(c,id,prefs.getLong("scheduledIndex",0))) }
        prefs.edit().remove("scheduledId").remove("scheduledIndex").apply()
        if(!settings.focusSignal || state == null || !allowed(c)) return
        channel(c)
        if(state.mode == FocusMode.DUE) { deliver(c,state);return }
        if(state.mode != FocusMode.RUNNING) return
        val intent = pending(c,state.id,state.phaseIndex)
        // Android/OEM may delay this in idle; the receiver checks the same phase again.
        alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime()+state.remainingMs,intent)
        prefs.edit().putString("scheduledId",state.id).putLong("scheduledIndex",state.phaseIndex).apply()
    }
    @Synchronized fun deliver(c: Context,focus: FocusSnapshot): Boolean {
        if(focus.mode != FocusMode.DUE || !allowed(c)) return false
        val key = focus.signalKey ?: return false;val prefs = preferences(c)
        val delivered = prefs.getStringSet("delivered",emptySet()).orEmpty()
        if(key in delivered) return false
        channel(c)
        val next = (delivered.filter { it.startsWith(focus.id+":") }+key).takeLast(64).toSet()
        if(!prefs.edit().putStringSet("delivered",next).commit()) return false
        try {
            val notification = Notification.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.ic_media_pause)
                .setContentTitle("Focus: интервал завершён").setContentText("Выберите следующий этап в приложении")
                .setContentIntent(TimerSurfaces.open(c)).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build()
            c.getSystemService(NotificationManager::class.java).notify(NOTICE,notification)
            return true
        } catch(_: RuntimeException) { prefs.edit().putStringSet("delivered",delivered).commit();return false }
    }
}
class FocusAlarmReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val id = intent.getStringExtra("id") ?: return;val index = intent.getLongExtra("index",-1)
        val pending = goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                val runtime = TrackerRuntime.get(context);val view = runtime.tracker.view();val focus = view.snapshot().focus
                if(!view.data.settings.surfaces.focusSignal || focus?.id != id || focus.phaseIndex != index) return@launch
                if(focus.mode == FocusMode.DUE) FocusSignals.deliver(context,focus) else FocusSignals.refresh(context,view)
            } catch(_: Exception) { android.util.Log.w("FocusSignals","Signal unavailable; phase/history unchanged") }
            finally { pending.finish() }
        }
    }
}
