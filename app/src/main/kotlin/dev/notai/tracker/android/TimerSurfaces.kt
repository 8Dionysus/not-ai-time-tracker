package dev.notai.tracker.android

import android.Manifest
import android.app.*
import android.appwidget.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.RemoteViews
import dev.notai.tracker.*
import kotlinx.coroutines.*

/** An action carries the revision that the user actually saw. No retries with a newer token. */
enum class Surface { WIDGET, NOTIFICATION, OVERLAY, SHORTCUT }
enum class TimerCommand { START, PAUSE, RESUME, STOP }
data class SurfaceAction(val source: Surface,val command: TimerCommand,val token: Token)
object TimerSurfaces {
    const val CONTROL_CHANNEL = "timer_controls"
    const val OVERLAY_CHANNEL = "floating_controls"
    const val CONTROL_ID = 41
    const val OVERLAY_ID = 42
    fun primary(mode: Mode): TimerCommand? = when(mode) {
        Mode.IDLE -> TimerCommand.START; Mode.RUNNING -> TimerCommand.PAUSE
        Mode.PAUSED -> TimerCommand.RESUME; Mode.RECOVERY -> null
    }
    fun notificationsAllowed(c: Context): Boolean = (Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        c.getSystemService(NotificationManager::class.java).let { it.areNotificationsEnabled() && it.getNotificationChannel(CONTROL_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE }
    fun overlayAllowed(c: Context) = Settings.canDrawOverlays(c)
    fun enabled(s: SurfaceSettings,source: Surface) = when(source) {
        Surface.WIDGET -> s.widget; Surface.NOTIFICATION -> s.notification; Surface.OVERLAY -> s.overlay; Surface.SHORTCUT -> true
    }
    /** Policy is checked again after process recreation, before the core revision check/write. */
    fun execute(tracker: Tracker,action: SurfaceAction,permission: Boolean = true): Tracker.Change {
        if(!permission || !enabled(tracker.data().settings.surfaces,action.source)) throw CoreError(ErrorCode.INVALID_INPUT,"Surface disabled or permission denied")
        return when(action.command) {
            TimerCommand.START -> tracker.start(action.token)
            TimerCommand.PAUSE -> tracker.pause(action.token)
            TimerCommand.RESUME -> tracker.resume(action.token)
            TimerCommand.STOP -> tracker.stop(action.token)
        }
    }
    fun intent(c: Context,a: SurfaceAction): Intent = Intent(c,TimerActionReceiver::class.java).apply {
        action = "dev.notai.tracker.TIMER_ACTION"
        // PendingIntent identity includes the immutable token; newer rendering cannot rewrite old actions.
        data = Uri.Builder().scheme("notai").authority("timer").appendPath(a.source.name).appendPath(a.command.name)
            .appendPath(a.token.datasetId).appendPath(a.token.revision.toString()).appendPath(a.token.sessionId ?: "").build()
        putExtra("source",a.source.name); putExtra("command",a.command.name)
        putExtra("dataset",a.token.datasetId); putExtra("revision",a.token.revision); putExtra("session",a.token.sessionId)
    }
    fun parse(i: Intent): SurfaceAction? = try {
        if(i.action != "dev.notai.tracker.TIMER_ACTION" || !i.hasExtra("revision")) null else
            SurfaceAction(Surface.valueOf(requireNotNull(i.getStringExtra("source"))),TimerCommand.valueOf(requireNotNull(i.getStringExtra("command"))),
                Token(requireNotNull(i.getStringExtra("dataset")),i.getLongExtra("revision",-1),i.getStringExtra("session")))
    } catch(_: IllegalArgumentException) { null }
    fun pending(c: Context,a: SurfaceAction): PendingIntent = PendingIntent.getBroadcast(c,0,intent(c,a),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun open(c: Context): PendingIntent = PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun channels(c: Context) {
        c.getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(CONTROL_CHANNEL,"Управление таймером",NotificationManager.IMPORTANCE_LOW))
            createNotificationChannel(NotificationChannel(OVERLAY_CHANNEL,"Плавающая кнопка: системный индикатор",NotificationManager.IMPORTANCE_LOW))
        }
    }
    fun notification(c: Context,s: Snapshot?,overlay: Boolean = false): Notification {
        channels(c)
        val builder = Notification.Builder(c,if(overlay) OVERLAY_CHANNEL else CONTROL_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle(if(overlay) "Плавающее управление включено" else "(not)AI Time Tracker")
            .setContentText(s?.safeLabel ?: "Открыть приложение").setContentIntent(open(c)).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true).setShowWhen(false)
        if(!overlay && s != null) {
            primary(s.mode)?.let { cmd -> builder.addAction(Notification.Action.Builder(null,cmd.name,pending(c,SurfaceAction(Surface.NOTIFICATION,cmd,s.token))).build()) }
            if(s.mode == Mode.RUNNING || s.mode == Mode.PAUSED) builder.addAction(Notification.Action.Builder(null,"STOP",pending(c,SurfaceAction(Surface.NOTIFICATION,TimerCommand.STOP,s.token))).build())
        }
        return builder.build()
    }
    fun widget(c: Context,s: Snapshot,enabled: Boolean): RemoteViews = RemoteViews(c.packageName,R.layout.timer_widget).apply {
        setTextViewText(R.id.widget_status,if(enabled) s.safeLabel else "Виджет выключен · открыть настройки")
        setOnClickPendingIntent(R.id.widget_status,open(c))
        val command = if(enabled) primary(s.mode) else null
        setTextViewText(R.id.widget_primary,command?.name ?: "Открыть")
        setOnClickPendingIntent(R.id.widget_primary,command?.let { pending(c,SurfaceAction(Surface.WIDGET,it,s.token)) } ?: open(c))
        val canStop = enabled && s.mode in setOf(Mode.RUNNING,Mode.PAUSED)
        setViewVisibility(R.id.widget_stop,if(canStop) android.view.View.VISIBLE else android.view.View.GONE)
        setOnClickPendingIntent(R.id.widget_stop,if(canStop) pending(c,SurfaceAction(Surface.WIDGET,TimerCommand.STOP,s.token)) else open(c))
    }
    /** Launcher may throttle updates. A retained/pinned old shortcut fails STALE, never replays. */
    fun shortcut(c: Context,s: Snapshot) {
        val manager = c.getSystemService(android.content.pm.ShortcutManager::class.java)
        val command = primary(s.mode)
        if(command == null) {
            manager.disableShortcuts(listOf("timer_action"),"Откройте приложение для уточнения времени")
            manager.removeDynamicShortcuts(listOf("timer_action"))
            return
        }
        val action = intent(c,SurfaceAction(Surface.SHORTCUT,command,s.token)).setClass(c,MainActivity::class.java)
        manager.enableShortcuts(listOf("timer_action"))
        manager.setDynamicShortcuts(listOf(android.content.pm.ShortcutInfo.Builder(c,"timer_action")
            .setShortLabel("Таймер: ${command.name}").setLongLabel("Таймер: ${command.name}")
            .setIcon(android.graphics.drawable.Icon.createWithResource(c,android.R.drawable.ic_media_play)).setIntent(action).build()))
    }
    fun refresh(c: Context,view: TrackerView,previous: SurfaceSettings? = null,forceWidget: Boolean = false) {
        val settings = view.data.settings.surfaces
        try { shortcut(c,view.snapshot()) } catch(_: RuntimeException) { android.util.Log.w("TimerSurfaces","Launcher shortcut update unavailable") }
        if(!forceWidget && settings == SurfaceSettings() && previous == SurfaceSettings()) return
        // No per-second work; called on committed revisions or explicit widget/permission events only.
        if(settings.widget || previous?.widget == true || forceWidget) {
            val manager = AppWidgetManager.getInstance(c)
            val ids = manager.getAppWidgetIds(ComponentName(c,TimerWidget::class.java))
            if(ids.isNotEmpty()) manager.updateAppWidget(ids,widget(c,view.snapshot(),settings.widget))
        }
        val notifications = c.getSystemService(NotificationManager::class.java)
        if(settings.notification && notificationsAllowed(c)) {
            try { notifications.notify(CONTROL_ID,notification(c,view.snapshot())) } catch(_: SecurityException) { notifications.cancel(CONTROL_ID) }
        } else if(previous == null || previous.notification) notifications.cancel(CONTROL_ID)
        if(previous?.overlay == true && !settings.overlay) c.stopService(Intent(c,FloatingTimerService::class.java))
    }
}

class TimerActionReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val action = TimerSurfaces.parse(intent) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                val runtime = TrackerRuntime.get(context)
                val permitted = when(action.source) {
                    Surface.NOTIFICATION -> TimerSurfaces.notificationsAllowed(context)
                    Surface.OVERLAY -> TimerSurfaces.overlayAllowed(context)
                    Surface.WIDGET, Surface.SHORTCUT -> true
                }
                try { TimerSurfaces.execute(runtime.tracker,action,permitted) } catch(_: CoreError) { /* stale/denied: show current state, never replay */ }
                TimerSurfaces.refresh(context,runtime.tracker.view(),forceWidget = action.source == Surface.WIDGET)
            } catch(_: Exception) { android.util.Log.w("TimerSurfaces","Action unavailable; local state retained") }
            finally { pending.finish() }
        }
    }
}
class TimerWidget: AppWidgetProvider() {
    override fun onUpdate(context: Context,manager: AppWidgetManager,ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try { TimerSurfaces.refresh(context,TrackerRuntime.get(context).tracker.view(),forceWidget = true) }
            catch(_: Exception) { android.util.Log.w("TimerSurfaces","Widget unavailable; open application") }
            finally { pending.finish() }
        }
    }
}
