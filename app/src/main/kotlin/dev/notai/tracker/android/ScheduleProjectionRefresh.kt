package dev.notai.tracker.android

import android.Manifest
import android.app.*
import android.appwidget.AppWidgetManager
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import dev.notai.tracker.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable

enum class ScheduleDataSurface { HOME, WIDGET, QUIET_SHADE }
/** Application-scoped data invalidation only. Opus supplies rendering; a quiet shade
 * consumer names its already created silent low-importance channel. No Start/post here. */
interface ScheduleProjectionConsumer {
    val id: String
    val surface: ScheduleDataSurface
    val channelId: String? get() = null
    fun available(context: Context): Boolean
    fun invalidate(value: ScheduleSurfaceState,projectionKey: String)
}
object ScheduleProjectionBinding {
    @Volatile internal var consumers: List<ScheduleProjectionConsumer> = emptyList()
    fun install(values: List<ScheduleProjectionConsumer>) {
        require(values.size <= 32 && values.all { it.id.isNotBlank() && it.id.length <= 128 } && values.map { it.id }.distinct().size == values.size)
        consumers = values.toList();TrackerRuntime.refreshExistingSchedule()
    }
}
@Serializable private data class ProjectionTicket(val datasetId: String,val barrier: String,val revision: Long,val date: String,val zone: String,
    val boundary: String,val at: Long,val consumerIds: List<String>) {
    val hash get() = sha256(codec.encodeToString(this).toByteArray())
}
@Serializable private data class ProjectionJournal(val format: Int = 1,val pending: ProjectionTicket? = null)
data class ProjectionRefreshResult(val state: ScheduleSurfaceState,val projectionKey: String,val connectedConsumers: Set<String>,val nextRefreshAt: Long?,val failedConsumers: Set<String>)

/** Independent of reminder receipts/alerts. Exactly one inexact civil-boundary wake-up
 * exists only for connected+enabled background consumers. Duplicate old hashes are inert;
 * foreground/time/zone changes recompute, never replay missed days or create facts. */
class ScheduleProjectionRefresh(context: Context,private val tracker: Tracker,private val workspace: ScheduleWorkspace) {
    private val context = context.applicationContext
    private val metadata = PrivateFileMaintenance.metadata(File(this.context.noBackupFilesDir,"schedule-projection-refresh.json"))
    private val lock = Any()
    private fun saved(): ProjectionJournal = metadata.read()?.let { codec.decodeFromString<ProjectionJournal>(it).also { ensure(it.format == 1,"Unknown projection journal",ErrorCode.STORAGE) } } ?: ProjectionJournal()
    private fun permitted(c: ScheduleProjectionConsumer,value: ScheduleSurfaceState): Boolean = try {
        c.available(context) && when(c.surface) {
            ScheduleDataSurface.HOME -> true
            ScheduleDataSurface.WIDGET -> value.surfaces.widget && context.getSystemService(AppWidgetManager::class.java)
                .getAppWidgetIds(ComponentName(context,TimerWidget::class.java)).isNotEmpty()
            ScheduleDataSurface.QUIET_SHADE -> {
                val manager = context.getSystemService(NotificationManager::class.java)
                val channel = c.channelId?.let(manager::getNotificationChannel)
                value.settings.quietShadeEnabled && (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
                    manager.areNotificationsEnabled() && channel != null && channel.importance in NotificationManager.IMPORTANCE_MIN..NotificationManager.IMPORTANCE_LOW && channel.sound == null && !channel.shouldVibrate()
            }
        }
    } catch(_: RuntimeException) { false }
    fun synchronize(): ProjectionRefreshResult = synchronized(lock) {
        try {
        val value = workspace.refresh();val consumers = ScheduleProjectionBinding.consumers.filter { permitted(it,value) }
        val background = consumers.filter { it.surface != ScheduleDataSurface.HOME }.map { it.id }.sorted()
        val ticket = ProjectionTicket(value.token.datasetId,value.controlBarrier,value.token.revision,value.day.date.toString(),value.day.zone,value.day.boundary,value.nextDayBoundary,background)
        val next = if(background.isEmpty()) null else ticket
        if(saved().pending != next) metadata.writeDurably(codec.encodeToString(ProjectionJournal(pending = next)))
        configure(next)
        val failures = mutableSetOf<String>()
        for(consumer in consumers) try { consumer.invalidate(value,ticket.hash) } catch(_: RuntimeException) { failures += consumer.id }
        ProjectionRefreshResult(value,ticket.hash,consumers.map { it.id }.toSet(),next?.at,failures)
        } catch(e: Exception) { disarm(context);throw e }
    }
    fun onWake(hash: String): ProjectionRefreshResult? = synchronized(lock) {
        val pending = saved().pending ?: return@synchronized null
        if(pending.hash != hash || tracker.clock.now().wall < pending.at) return@synchronized null
        synchronize()
    }
    private fun configure(ticket: ProjectionTicket?) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context,ScheduleProjectionReceiver::class.java).setAction(ACTION).setData(android.net.Uri.parse("notai://schedule/projection"))
        val pending = PendingIntent.getBroadcast(context,0,intent.putExtra(HASH,ticket?.hash),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if(ticket == null) alarm.cancel(pending) else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,ticket.at,pending)
        val manager = context.packageManager;val component = ComponentName(context,ScheduleProjectionReceiver::class.java)
        val desired = if(ticket == null) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        if(manager.getComponentEnabledSetting(component) != desired) manager.setComponentEnabledSetting(component,desired,PackageManager.DONT_KILL_APP)
    }
    companion object {
        /** Fail closed without overwriting an unreadable/future journal. */
        internal fun disarm(context: Context) {
            val intent = Intent(context,ScheduleProjectionReceiver::class.java).setAction(ACTION).setData(android.net.Uri.parse("notai://schedule/projection"))
            PendingIntent.getBroadcast(context,0,intent,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)?.let {
                context.getSystemService(AlarmManager::class.java).cancel(it)
            }
            context.packageManager.setComponentEnabledSetting(ComponentName(context,ScheduleProjectionReceiver::class.java),PackageManager.COMPONENT_ENABLED_STATE_DISABLED,PackageManager.DONT_KILL_APP)
        }
        internal const val ACTION = "dev.notai.tracker.SCHEDULE_PROJECTION";internal const val HASH = "projectionHash" }
}
class ScheduleProjectionReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action !in setOf(ScheduleProjectionRefresh.ACTION,Intent.ACTION_DATE_CHANGED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val result = goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                if(!DatabaseFiles.exists(context) || ScheduleProjectionBinding.consumers.none { it.surface != ScheduleDataSurface.HOME }) { ScheduleProjectionRefresh.disarm(context);return@launch }
                val runtime = TrackerRuntime.get(context)
                if(intent.action == ScheduleProjectionRefresh.ACTION) intent.getStringExtra(ScheduleProjectionRefresh.HASH)?.let { runtime.scheduleProjection.onWake(it) }
                else runtime.scheduleProjection.synchronize()
            } catch(e: Exception) { if(e is CancellationException) throw e;android.util.Log.w("ScheduleProjection","Refresh unavailable; no facts changed") }
            finally { result.finish() }
        }
    }
}
