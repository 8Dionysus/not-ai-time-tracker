package dev.notai.tracker.android

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import dev.notai.tracker.*
import java.io.File
import kotlinx.coroutines.*

/** Opus owns text, channel creation and notification actions. The factory must be process-wide,
 * installed before cold receivers, with no Activity reference. No renderer means no delivery. */
fun interface LongRecordNoticeFactory {
    fun create(context: Context,ticket: LongRecordTicket,snapshot: Snapshot): Notification
}
object LongRecordNoticeBinding {
    @Volatile var factory: LongRecordNoticeFactory? = null
}
private val longRecordGates = mutableMapOf<String,Any>()
class AndroidLongRecordStore(context: Context): LongRecordStore {
    private val file = File(context.applicationContext.noBackupFilesDir,"long-record-reminder.json")
    private val metadata = PrivateFileMaintenance.metadata(file,64*1024)
    private val gate = synchronized(longRecordGates) { longRecordGates.getOrPut(file.absolutePath) { Any() } }
    override fun <T> locked(block: ()->T): T = synchronized(gate) { block() }
    override fun read(): LongRecordControl = metadata.read()?.let { codec.decodeFromString<LongRecordControl>(it) } ?: LongRecordControl()
    override fun writeDurably(value: LongRecordControl) = metadata.writeDurably(codec.encodeToString(value))
}
class AndroidLongRecordPlatform(context: Context): LongRecordPlatform {
    private val context = context.applicationContext
    companion object {
        const val CHANNEL = "ordinary_timer_checkin"
        const val ACTION = "dev.notai.tracker.ORDINARY_TIMER_CHECKIN"
        const val HASH = "longRecordHash"
        private const val NOTICE = 85
    }
    private fun intent() = Intent(context,LongRecordAlarmReceiver::class.java).setAction(ACTION)
        .setData(Uri.parse("notai://ordinary-timer/checkin"))
    override fun availability(): ScheduleDeliveryAvailability {
        val manager = context.getSystemService(NotificationManager::class.java)
        val blocked = mutableSetOf<ScheduleDeliveryBlocker>()
        if(LongRecordNoticeBinding.factory == null) blocked += ScheduleDeliveryBlocker.RENDERER_UNAVAILABLE
        if(manager == null || context.getSystemService(AlarmManager::class.java) == null) blocked += ScheduleDeliveryBlocker.PLATFORM_UNAVAILABLE
        if(Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED || manager?.areNotificationsEnabled() == false)
            blocked += ScheduleDeliveryBlocker.PERMISSION
        if(manager?.getNotificationChannel(CHANNEL)?.importance.let { it == null || it == NotificationManager.IMPORTANCE_NONE }) blocked += ScheduleDeliveryBlocker.CHANNEL
        val quiet = when(manager?.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> ScheduleQuietMode.ALL
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> ScheduleQuietMode.PRIORITY
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> ScheduleQuietMode.ALARMS_ONLY
            NotificationManager.INTERRUPTION_FILTER_NONE -> ScheduleQuietMode.NONE
            else -> ScheduleQuietMode.UNKNOWN
        }
        return ScheduleDeliveryAvailability(blocked,quiet)
    }
    override fun schedule(ticket: LongRecordTicket) {
        check(availability().ready)
        val pending = PendingIntent.getBroadcast(context,0,intent().putExtra(HASH,ticket.hash),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,ticket.dueElapsed,pending)
    }
    override fun cancelAlarm() {
        val pending = PendingIntent.getBroadcast(context,0,intent(),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pending)
    }
    override fun prepare(ticket: LongRecordTicket,snapshot: Snapshot): PreparedLongRecordNotice? {
        if(!availability().ready) return null
        val notice = LongRecordNoticeBinding.factory?.create(context,ticket,snapshot) ?: return null
        if(notice.channelId != CHANNEL) return null
        return PreparedLongRecordNotice {
            if(!availability().ready) false else {
                context.getSystemService(NotificationManager::class.java).notify(NOTICE,notice);true
            }
        }
    }
    override fun cancelNotice() = context.getSystemService(NotificationManager::class.java).cancel(NOTICE)
}
/** Blocking controller; call on IO. Shared file gate also serializes multiple wrappers.
 * Runtime integration is deliberately a small owner-applied patch, not a new timer engine. */
class AndroidLongRecordReminders(context: Context,tracker: Tracker,active: ()->Boolean = { true }) {
    val controller = LongRecordReminder(tracker,AndroidLongRecordStore(context),AndroidLongRecordPlatform(context),active)
    fun synchronize() = controller.synchronize()
    fun onAlarm(hash: String) = controller.onAlarm(hash)
}
class LongRecordAlarmReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action != AndroidLongRecordPlatform.ACTION) return
        val hash = intent.getStringExtra(AndroidLongRecordPlatform.HASH)?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                if(!DatabaseFiles.exists(context)) return@launch
                val runtime = TrackerRuntime.get(context)
                AndroidLongRecordReminders(context,runtime.tracker).onAlarm(hash)
            } catch(e: Exception) { if(e !is CancellationException) android.util.Log.w("LongRecordReminder","Check-in unavailable; history unchanged") }
            finally { pending.finish() }
        }
    }
}
