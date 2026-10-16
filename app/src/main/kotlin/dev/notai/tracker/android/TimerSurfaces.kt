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
data class SurfaceAction(val source: Surface,val command: TimerCommand,val token: Token,val target: TimerTarget? = null)
object TimerSurfaces {
    const val CONTROL_CHANNEL = "timer_controls"
    /** Minimal-importance channel: the system indicator the floating button's service must show. */
    const val OVERLAY_CHANNEL = "floating_indicator"
    const val CONTROL_ID = 41
    const val OVERLAY_ID = 42
    private const val PENDING_SHORTCUT = "pendingShortcut"
    @Suppress("DEPRECATION")
    fun restoredShortcut(saved: android.os.Bundle?,launch: Intent): SurfaceAction? {
        val pending = if(saved == null) launch else saved.getParcelable<Intent>(PENDING_SHORTCUT)
        return pending?.let(::parse)?.takeIf { it.source == Surface.SHORTCUT }
    }
    fun saveShortcut(c: Context,saved: android.os.Bundle,pending: SurfaceAction?) {
        if(pending != null) saved.putParcelable(PENDING_SHORTCUT,intent(c,pending)) else saved.remove(PENDING_SHORTCUT)
    }
    fun primary(s: Snapshot): TimerCommand? = if(s.focus != null) when(s.focus!!.mode) {
        FocusMode.RUNNING -> TimerCommand.PAUSE; FocusMode.PAUSED -> TimerCommand.RESUME; FocusMode.DUE, FocusMode.RECOVERY -> null
    } else when(s.mode) {
        Mode.IDLE -> TimerCommand.START; Mode.RUNNING -> TimerCommand.PAUSE
        Mode.PAUSED -> TimerCommand.RESUME; Mode.RECOVERY -> null
    }
    /** Overlay has a separate simple START/STOP policy; other surfaces keep pause/resume. */
    fun overlayPrimary(s: Snapshot): TimerCommand? = when {
        s.mode == Mode.RECOVERY || s.focus?.mode == FocusMode.RECOVERY -> null
        s.session != null || s.focus != null -> TimerCommand.STOP
        else -> TimerCommand.START
    }
    /** Visible Russian labels for the same commands; identity stays in the action, never in the text. */
    fun label(command: TimerCommand): String = when(command) {
        TimerCommand.START -> "Начать"; TimerCommand.PAUSE -> "Пауза"; TimerCommand.RESUME -> "Продолжить"; TimerCommand.STOP -> "Стоп"
    }
    fun notificationsAllowed(c: Context): Boolean = (Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        c.getSystemService(NotificationManager::class.java).let { it.areNotificationsEnabled() && it.getNotificationChannel(CONTROL_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE }
    fun overlayAllowed(c: Context) = Settings.canDrawOverlays(c)
    fun enabled(s: SurfaceSettings,source: Surface) = when(source) {
        Surface.WIDGET -> s.widget; Surface.NOTIFICATION -> s.notification; Surface.OVERLAY -> s.overlay; Surface.SHORTCUT -> s.shortcut
    }
    /** Policy is checked again after process recreation, before the core revision check/write. */
    fun execute(tracker: Tracker,action: SurfaceAction,permission: Boolean = true): Tracker.Change {
        if(action.source == Surface.OVERLAY && action.command == TimerCommand.STOP) {
            val target = action.target ?: tracker.snapshot().let { s ->
                ensure(s.token == action.token,"Timer target changed",ErrorCode.STALE); s.timerTarget!!
            }
            return tracker.stopForAssignment(target) { permission && enabled(it.settings.surfaces,Surface.OVERLAY) }
        }
        if(action.target != null) return tracker.timerCommand(action.target,TimerDirective.valueOf(action.command.name)) { data ->
            permission && enabled(data.settings.surfaces,action.source)
        }
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
            .appendPath(a.token.datasetId).appendPath(a.token.revision.toString()).appendPath(a.token.sessionId ?: "").appendPath(a.target?.epoch?.toString() ?: "").appendPath(a.target?.barrier ?: "").build()
        putExtra("source",a.source.name); putExtra("command",a.command.name)
        putExtra("dataset",a.token.datasetId); putExtra("revision",a.token.revision); putExtra("session",a.token.sessionId)
        a.target?.let { target -> putExtra("timerEpoch",target.epoch);putExtra("controlBarrier",target.barrier) }
    }
    fun parse(i: Intent): SurfaceAction? = try {
        if(i.action != "dev.notai.tracker.TIMER_ACTION" || !i.hasExtra("revision")) null else
            SurfaceAction(Surface.valueOf(requireNotNull(i.getStringExtra("source"))),TimerCommand.valueOf(requireNotNull(i.getStringExtra("command"))),
                Token(requireNotNull(i.getStringExtra("dataset")),i.getLongExtra("revision",-1),i.getStringExtra("session")),
                if(i.hasExtra("timerEpoch")) TimerTarget(requireNotNull(i.getStringExtra("dataset")),i.getStringExtra("session"),
                    i.getLongExtra("timerEpoch",-1),i.getStringExtra("controlBarrier").orEmpty()) else null)
    } catch(_: IllegalArgumentException) { null }
    fun pending(c: Context,a: SurfaceAction): PendingIntent = PendingIntent.getBroadcast(c,0,intent(c,a),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun open(c: Context): PendingIntent = PendingIntent.getActivity(c,0,Intent(c,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun channels(c: Context) {
        c.getSystemService(NotificationManager::class.java).apply {
            createNotificationChannel(NotificationChannel(CONTROL_CHANNEL,"Таймер в шторке",NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            createNotificationChannel(NotificationChannel(OVERLAY_CHANNEL,"Плавающая кнопка: системный индикатор",NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) })
            deleteNotificationChannel("floating_controls") // replaced by the minimal-importance indicator
        }
    }
    /** Shade controls: rendered by SurfaceViews (row with start/stop, quick choices when expanded). */
    fun notification(c: Context,view: TrackerView): Notification = SurfaceViews.notification(c,view)
    /** The floating button's mandatory foreground-service indicator. */
    fun overlayNotification(c: Context): Notification {
        channels(c)
        return Notification.Builder(c,OVERLAY_CHANNEL).setSmallIcon(R.drawable.ic_stat_notai).setColor(c.getColor(R.color.widget_amber))
            .setContentTitle("Плавающая кнопка включена").setContentText("Выключить можно в настройках приложения").setContentIntent(open(c))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true).setShowWhen(false).build()
    }
    fun widget(c: Context,view: TrackerView,enabled: Boolean): RemoteViews = SurfaceViews.widget(c,view,enabled)
    /** Launcher may throttle updates. A retained/pinned old shortcut fails STALE, never replays. */
    fun shortcut(c: Context,s: Snapshot,settings: SurfaceSettings) {
        val manager = c.getSystemService(android.content.pm.ShortcutManager::class.java)
        if(!settings.shortcut) {
            manager.disableShortcuts(listOf("timer_action"))
            manager.removeDynamicShortcuts(listOf("timer_action"))
            return
        }
        val command = primary(s)
        if(command == null) {
            manager.disableShortcuts(listOf("timer_action"),"Откройте приложение для уточнения времени")
            manager.removeDynamicShortcuts(listOf("timer_action"))
            return
        }
        val action = intent(c,SurfaceAction(Surface.SHORTCUT,command,s.token,s.timerTarget)).setClass(c,MainActivity::class.java)
        manager.enableShortcuts(listOf("timer_action"))
        manager.setDynamicShortcuts(listOf(android.content.pm.ShortcutInfo.Builder(c,"timer_action")
            .setShortLabel(label(command)).setLongLabel("Таймер: ${label(command).lowercase()}")
            .setIcon(android.graphics.drawable.Icon.createWithResource(c,R.drawable.ic_shortcut)).setIntent(action).build()))
    }
    fun refresh(c: Context,view: TrackerView,previous: SurfaceSettings? = null,forceWidget: Boolean = false) {
        val settings = view.data.settings.surfaces
        try { shortcut(c,view.snapshot(),settings) } catch(_: RuntimeException) { android.util.Log.w("TimerSurfaces","Launcher shortcut update unavailable") }
        if(!forceWidget && settings == SurfaceSettings() && previous == SurfaceSettings()) return
        // No per-second work; called on committed revisions or explicit widget/permission events only.
        if(settings.widget || previous?.widget == true || forceWidget) {
            val manager = AppWidgetManager.getInstance(c)
            val ids = manager.getAppWidgetIds(ComponentName(c,TimerWidget::class.java))
            if(ids.isNotEmpty()) manager.updateAppWidget(ids,widget(c,view,settings.widget))
        }
        val notifications = c.getSystemService(NotificationManager::class.java)
        if(settings.notification && notificationsAllowed(c)) {
            try { notifications.notify(CONTROL_ID,notification(c,view)) } catch(_: SecurityException) { notifications.cancel(CONTROL_ID) }
        } else if(previous == null || previous.notification) notifications.cancel(CONTROL_ID)
        if(previous?.overlay == true && !settings.overlay) c.stopService(Intent(c,FloatingTimerService::class.java))
    }
}

/** Submitted work belongs to the process runtime, never to an Activity awaiting its result.
 * The original revision still makes recreation/repeated delivery safe without a command queue. */
internal class ShortcutCommands(private val tracker: Tracker,private val scope: CoroutineScope) {
    fun submit(action: SurfaceAction): Deferred<Result<Tracker.Change>> = scope.async {
        runCatching {
            require(action.source == Surface.SHORTCUT)
            TimerSurfaces.execute(tracker,action)
        }
    }
}

class TimerActionReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        val action = TimerSurfaces.parse(intent) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                val runtime = TrackerRuntime.get(context)
                handleCurrent(context,runtime.tracker,action)
            } catch(_: Exception) { android.util.Log.w("TimerSurfaces","Action unavailable; local state retained") }
            finally { pending.finish() }
        }
    }
    internal fun handleCurrent(context: Context,tracker: Tracker,action: SurfaceAction) {
                val permitted = when(action.source) {
                    Surface.NOTIFICATION -> TimerSurfaces.notificationsAllowed(context)
                    Surface.OVERLAY -> TimerSurfaces.overlayAllowed(context)
                    Surface.WIDGET, Surface.SHORTCUT -> true
                }
                try { TimerSurfaces.execute(tracker,action,permitted) }
                catch(e: CoreError) {
                    val message = "${e.code}: ${e.message}. Откройте приложение; история не изменена."
                    context.getSharedPreferences("surface-results",Context.MODE_PRIVATE).edit().putString("last",message).apply()
                    android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(context,message,android.widget.Toast.LENGTH_LONG).show() }
                }
                TimerSurfaces.refresh(context,tracker.view(),forceWidget = action.source == Surface.WIDGET)
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
