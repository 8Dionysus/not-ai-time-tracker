package dev.notai.tracker.android

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import dev.notai.tracker.*
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.*

/** Application-owned rendering only. TrackerApplication owns process startup/channel lifecycle;
 * supply visual resources through ScheduleNoticeConfiguration. Never retain an Activity. */
fun interface ScheduleNoticeFactory {
    fun create(context: Context,ticket: ScheduleReminderTicket,projection: ScheduleProjection): Notification
}
/** Same public backend port; the sidecar now uses an indexed SQLite journal. */
class AndroidScheduleDeliveryStore(context: Context): ScheduleDeliveryStore by scheduleIndex(context)
private fun scheduleIndex(context: Context): IndexedScheduleDeliveryStore {
    val root = context.applicationContext.noBackupFilesDir
    val file = File(root,"schedule-delivery.sqlite")
    val gate = synchronized(scheduleIndexGates) { scheduleIndexGates.getOrPut(file.absolutePath) { Any() } }
    return IndexedScheduleDeliveryStore(gate,PrivateFileMaintenance.metadata(File(root,"schedule-delivery.json")),
        PrivateFileMaintenance.metadata(File(root,"schedule-delivery.v1-source.json"))) { allowCreate -> AndroidScheduleReceiptSql(file,allowCreate) }
}
private val scheduleIndexGates = mutableMapOf<String,Any>()
class AndroidScheduleDeliveryPlatform(context: Context,private val factory: (() -> ScheduleNoticeFactory?)? = null): ScheduleDeliveryPlatform {
    private val context = context.applicationContext
    companion object {
        const val CHANNEL = "schedule_reminders"
        internal const val ACTION = "dev.notai.tracker.SCHEDULE_REMINDER"
        internal const val HASH = "scheduleBatchHash"
        private const val TICKET = "scheduleReminderTicket"
        private const val TAG = "schedule:"
        private const val NOTICE = 84
    }
    private fun renderer() = if(factory == null) ScheduleNoticeBinding.factory else factory.invoke()
    private fun intent() = Intent(context,ScheduleAlarmReceiver::class.java).setAction(ACTION)
        .setData(Uri.Builder().scheme("notai").authority("schedule").appendPath("reminder").build())
    override fun availability(): ScheduleDeliveryAvailability = try {
        val manager = context.getSystemService(NotificationManager::class.java)
        val alarm = context.getSystemService(AlarmManager::class.java)
        val blockers = mutableSetOf<ScheduleDeliveryBlocker>()
        if(renderer() == null) blockers += ScheduleDeliveryBlocker.RENDERER_UNAVAILABLE
        if(manager == null || alarm == null) blockers += ScheduleDeliveryBlocker.PLATFORM_UNAVAILABLE
        if(Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED || manager?.areNotificationsEnabled() == false)
            blockers += ScheduleDeliveryBlocker.PERMISSION
        if(manager?.getNotificationChannel(CHANNEL)?.importance.let { it == null || it == NotificationManager.IMPORTANCE_NONE }) blockers += ScheduleDeliveryBlocker.CHANNEL
        val quiet = when(manager?.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> ScheduleQuietMode.ALL
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> ScheduleQuietMode.PRIORITY
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> ScheduleQuietMode.ALARMS_ONLY
            NotificationManager.INTERRUPTION_FILTER_NONE -> ScheduleQuietMode.NONE
            else -> ScheduleQuietMode.UNKNOWN
        }
        ScheduleDeliveryAvailability(blockers,quiet)
    } catch(_: RuntimeException) { ScheduleDeliveryAvailability(setOf(ScheduleDeliveryBlocker.PLATFORM_UNAVAILABLE)) }
    override fun schedule(batch: ScheduleAlarmBatch) {
        check(availability().ready)
        val pending = PendingIntent.getBroadcast(context,0,intent().putExtra(HASH,batch.hash),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        context.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,batch.at,pending)
    }
    override fun cancelAlarm() {
        val pending = PendingIntent.getBroadcast(context,0,intent(),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) ?: return
        context.getSystemService(AlarmManager::class.java).cancel(pending)
    }
    override fun prepare(ticket: ScheduleReminderTicket,projection: ScheduleProjection): PreparedScheduleNotice? {
        if(!availability().ready) return null
        val installed = if(factory == null) ScheduleNoticeBinding.capture() else null
        val render = (if(installed != null) installed.factory else renderer()) ?: return null
        val notification = render.create(context,ticket,projection)
        if(notification.channelId != CHANNEL) return null
        if(notification.extras == null) notification.extras = android.os.Bundle()
        notification.extras.putString(TICKET,codec.encodeToString(ticket))
        return PreparedScheduleNotice {
            val post = {
                if(!availability().ready) false else {
                    context.getSystemService(NotificationManager::class.java).notify(TAG+ticket.identity,NOTICE,notification)
                    true // notify returned; not an OS/user visibility acknowledgement.
                }
            }
            // A prepared notification belongs to one exact registration, including reinstalling
            // the same factory object. Serialize production rebind with the final platform post.
            if(installed != null) ScheduleNoticeBinding.publishIfCurrent(installed,post)
            else renderer() === render && post() // Explicit fixture/custom supplier, no global binding.
        }
    }
    override fun visible(): List<ScheduleReminderTicket> = context.getSystemService(NotificationManager::class.java).activeNotifications.mapNotNull { n ->
        if(n.id != NOTICE || n.tag?.startsWith(TAG) != true) return@mapNotNull null
        val raw = n.notification.extras.getString(TICKET) ?: return@mapNotNull null
        if(raw.length > 65536) return@mapNotNull null
        runCatching { codec.decodeFromString<ScheduleReminderTicket>(raw) }.getOrNull()?.takeIf { n.tag == TAG+it.identity }
    }
    override fun cancelNotice(ticket: ScheduleReminderTicket) = context.getSystemService(NotificationManager::class.java).cancel(TAG+ticket.identity,NOTICE)
    override fun configureWakeups(enabled: Boolean) {
        val component = ComponentName(context,SchedulePlatformReceiver::class.java);val manager = context.packageManager
        val current = manager.getComponentEnabledSetting(component)
        val desired = if(enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        if(current != desired && !(current == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && !enabled))
            manager.setComponentEnabledSetting(component,desired,PackageManager.DONT_KILL_APP)
    }
}
class AndroidScheduleDelivery(context: Context,tracker: Tracker) {
    private val platform = AndroidScheduleDeliveryPlatform(context)
    val coordinator = ScheduleDelivery(tracker,AndroidScheduleDeliveryStore(context),platform) { ZoneId.systemDefault() }
    /** Call on foreground/platform changes too; unchanged Dataset revision is not a clock/permission witness. */
    fun synchronize() = coordinator.synchronize()
    fun onAlarm(hash: String) = coordinator.onAlarm(hash)
}
internal fun hasScheduleWakeSource(context: Context): Boolean {
    if(!DatabaseFiles.exists(context)) return false
    val store = AndroidScheduleDeliveryStore(context)
    return store.locked { store.control().pending != null }
}
class ScheduleAlarmReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action != AndroidScheduleDeliveryPlatform.ACTION) return
        val hash = intent.getStringExtra(AndroidScheduleDeliveryPlatform.HASH)?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return
        val result = goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                if(!DatabaseFiles.exists(context)) return@launch // A background wake-up must not initialize an empty replacement store.
                TrackerRuntime.get(context).scheduleDelivery.onAlarm(hash)
            }
            catch(e: Exception) { if(e !is CancellationException) android.util.Log.w("ScheduleDelivery","Reminder unavailable; plan/facts unchanged") }
            finally { result.finish() }
        }
    }
}
/** Disabled in the manifest. A usable, explicitly opted-in reminder enables it; ordinary timer
 * use does not add a boot worker. Cold delivery still requires factory binding in Application. */
class SchedulePlatformReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val result = goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                if(!hasScheduleWakeSource(context)) return@launch
                TrackerRuntime.get(context).scheduleDelivery.synchronize()
            } catch(e: Exception) { if(e !is CancellationException) android.util.Log.w("ScheduleDelivery","Reminder reschedule unavailable; foreground review required") }
            finally { result.finish() }
        }
    }
}
