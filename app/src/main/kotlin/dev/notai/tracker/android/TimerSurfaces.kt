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
import dev.notai.tracker.android.ui.label
import kotlinx.coroutines.*

/** An action carries the revision that the user actually saw. No retries with a newer token. */
enum class Surface { WIDGET, NOTIFICATION, OVERLAY, SHORTCUT }
enum class TimerCommand { START, PAUSE, RESUME, STOP }
data class SurfaceAction(val source: Surface,val command: TimerCommand,val token: Token,val target: TimerTarget? = null)
/** The runtime collector's publication history, independent of view observation history. */
internal class TimerSurfaceObserver(private val context: Context,private val tracker: Tracker) {
    private var previous: SurfaceSettings? = null
    suspend fun refresh(view: TrackerView) {
        if(TimerSurfaces.refresh(context,tracker,view,previous)) previous = view.data.settings.surfaces
    }
}
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
    internal var notificationFactory: (Context,TrackerView) -> Notification = { c,v -> SurfaceViews.notification(c,v) }
    internal var refreshPrepared: ((Context,TrackerView) -> Unit)? = null
    fun notification(c: Context,view: TrackerView): Notification = notificationFactory(c,view)
    /** The floating button's mandatory foreground-service indicator. */
    fun overlayNotification(c: Context): Notification {
        channels(c)
        return Notification.Builder(c,OVERLAY_CHANNEL).setSmallIcon(R.drawable.ic_stat_notai).setColor(c.getColor(R.color.widget_amber))
            .setContentTitle("Плавающая кнопка включена").setContentText("Выключить можно в настройках приложения").setContentIntent(open(c))
            .setVisibility(Notification.VISIBILITY_PRIVATE).setOngoing(true).setShowWhen(false).build()
    }
    fun widget(c: Context,view: TrackerView,enabled: Boolean): RemoteViews = SurfaceViews.widget(c,view,enabled)
    /** Launcher may throttle updates. A retained/pinned old shortcut fails STALE, never replays. */
    fun shortcut(c: Context,s: Snapshot,settings: SurfaceSettings,view: TrackerView? = null) { prepareShortcut(c,view,s,settings).invoke() }
    private fun favouriteShortcuts(c: Context,view: TrackerView,s: Snapshot): List<android.content.pm.ShortcutInfo> {
        val end = minOf(MAX_TIME,System.currentTimeMillis()+1)
        val excluded = if(s.mode == Mode.IDLE) null else s.session?.selection
        return view.choices().quick(HistoryWindow(maxOf(0L,end-30L*24*60*60*1000),end),2,excluded).filter { it.entry.kind == ChoiceKind.FAVORITE }
            .mapIndexed { i,q ->
                val route = SurfaceChoices.route(Surface.SHORTCUT,s)
                val title = q.entry.title.ifBlank { view.data.label(q.entry.selection).title }
                android.content.pm.ShortcutInfo.Builder(c,"timer_favorite_$i").setShortLabel(title.take(24)).setLongLabel((if(s.mode == Mode.IDLE) "Начать: " else "Переключиться: ")+title)
                    .setIcon(android.graphics.drawable.Icon.createWithResource(c,R.drawable.ic_shortcut))
                    .setIntent(SurfaceChoices.intent(c,route).putExtra(QuickChoiceReceiver.EXTRA_CHOICE,codec.encodeToString(ChoiceReference.serializer(),q.reference)))
                    .setRank(2+i).build()
            }
    }
    private fun prepareShortcut(c: Context,view: TrackerView?,s: Snapshot,settings: SurfaceSettings): () -> Unit {
        val manager = c.getSystemService(android.content.pm.ShortcutManager::class.java)
        val ids = listOf("timer_action", "timer_choose", "timer_favorite_0", "timer_favorite_1")
        // Withdraw the former static MAIN alias, including pinned copies retained by a launcher.
        fun retireOpenAlias() { manager.disableShortcuts(listOf("open_timer")) }
        if(!settings.shortcut) return { retireOpenAlias();manager.disableShortcuts(ids);manager.removeDynamicShortcuts(ids) }
        val command = primary(s)
        if(command == null) return {
            retireOpenAlias()
            manager.disableShortcuts(ids,"Откройте приложение для уточнения времени")
            manager.removeDynamicShortcuts(ids)
        }
        val action = intent(c,SurfaceAction(Surface.SHORTCUT,command,s.token,s.timerTarget)).setClass(c,MainActivity::class.java)
        val info = android.content.pm.ShortcutInfo.Builder(c,"timer_action")
            .setShortLabel(label(command)).setLongLabel("Таймер: ${label(command).lowercase()}")
            .setIcon(android.graphics.drawable.Icon.createWithResource(c,R.drawable.ic_shortcut)).setIntent(action).build()
        val chooser = android.content.pm.ShortcutInfo.Builder(c,"timer_choose")
            .setShortLabel("Выбрать занятие").setLongLabel("Таймер: выбрать занятие")
            .setIcon(android.graphics.drawable.Icon.createWithResource(c,R.drawable.ic_shortcut))
            .setIntent(SurfaceChoices.intent(c,SurfaceChoices.route(Surface.SHORTCUT,s))).setRank(1).build()
        // The first favourites in their saved order, each a one-tap Start (or Switch) through the same stale-safe route.
        val favourites = if(s.focus != null || view == null) emptyList() else runCatching { favouriteShortcuts(c,view,s) }.getOrDefault(emptyList())
        val entries = (listOf(info) + (if(s.focus == null) listOf(chooser) else emptyList()) + favourites)
            .take(manager.maxShortcutCountPerActivity.coerceAtLeast(0).coerceAtMost(4))
        val shownIds = entries.map { it.id }
        return {
            retireOpenAlias()
            // Removing a dynamic entry alone leaves pinned copies enabled. Withdraw those too.
            val withdrawn = ids - shownIds
            if(withdrawn.isNotEmpty()) manager.disableShortcuts(withdrawn,"Откройте приложение для выбора занятия")
            if(shownIds.isNotEmpty()) manager.enableShortcuts(shownIds)
            LauncherIconController.withBinding(c) { activity ->
                manager.setDynamicShortcuts(entries.map { entry ->
                    LauncherIconController.boundShortcut(c,entry,activity)
                })
            }
            Unit
        }
    }
    /** Prepare outside the store gate, then recheck the full captured view at each publication.
     * Notification confirmation has its own first gate: shortcuts/widgets cannot delay it.
     * No global publication lock precedes the store lock; failures remain independent. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun refresh(c: Context,tracker: Tracker,view: TrackerView? = null,previous: SurfaceSettings? = null,forceWidget: Boolean = false): Boolean =
        refreshCurrent(c,tracker,view,previous,null)

    internal suspend fun refreshAfterAction(c: Context,tracker: Tracker,trace: TimerActionTrace?): Boolean =
        refreshCurrent(c,tracker,null,null,trace)

    internal var ancillaryPreparing: ((Context,TrackerView) -> Unit)? = null
    internal var ancillaryPrepared: ((Context,TrackerView) -> Unit)? = null
    private suspend fun refreshCurrent(c: Context,tracker: Tracker,view: TrackerView?,previous: SurfaceSettings?,trace: TimerActionTrace?): Boolean = withContext(Dispatchers.IO) {
        val captured = view ?: tracker.view();val shown = captured.snapshot();val settings = captured.data.settings.surfaces
        var prepared = true
        fun <T> prepare(surface: Surface,block: () -> T): T? = try { block() } catch(_: RuntimeException) {
            prepared = false;android.util.Log.w("TimerSurfaces","$surface preparation unavailable");null
        }
        fun effect(surface: Surface,block: () -> Unit): Boolean = try { block();true } catch(_: RuntimeException) {
            android.util.Log.w("TimerSurfaces","$surface publication unavailable");false
        }
        fun publishCurrent(block: () -> Boolean): Boolean = tracker.store.checkedTransaction { checked ->
            val live = checked.data;val current = checked.snapshot(tracker.clock.now())
            val valid = live.datasetId == captured.data.datasetId && live.revision == captured.data.revision &&
                live.controlBarrier == captured.data.controlBarrier && current.token == shown.token && current.timerTarget == shown.timerTarget &&
                current.mode == shown.mode && current.focus?.id == shown.focus?.id && current.focus?.phaseIndex == shown.focus?.phaseIndex && current.focus?.mode == shown.focus?.mode
            checked.replace(live) { valid && block() }
        }
        val notice = if(settings.notification) prepare(Surface.NOTIFICATION) { if(notificationsAllowed(c)) notification(c,captured) else null } else null
        refreshPrepared?.invoke(c,captured)
        val notificationApplied = publishCurrent {
            effect(Surface.NOTIFICATION) {
                val manager = c.getSystemService(NotificationManager::class.java)
                if(settings.notification && notificationsAllowed(c) && notice != null) manager.notify(CONTROL_ID,notice)
                else manager.cancel(CONTROL_ID)
            }
        }
        trace?.mark(if(!notificationApplied) "notification-skipped" else if(notice != null) "notification-published" else "notification-withdrawn")
        // Disabled is a current policy, not a transition that observation may lose.
        ancillaryPreparing?.invoke(c,captured)
        val launcher = prepare(Surface.SHORTCUT) { prepareShortcut(c,captured,shown,settings) }
        val preparedWidget = prepare(Surface.WIDGET) {
            val manager = AppWidgetManager.getInstance(c)
            val ids = manager.getAppWidgetIds(ComponentName(c,TimerWidget::class.java))
            Triple(manager,ids,if(ids.isEmpty()) null else widget(c,captured,settings.widget))
        }
        ancillaryPrepared?.invoke(c,captured)
        val ancillaryApplied = publishCurrent {
            var applied = prepared
            launcher?.let { applied = effect(Surface.SHORTCUT,it) && applied }
            preparedWidget?.let { (manager,ids,views) -> if(views != null) applied = effect(Surface.WIDGET) { manager.updateAppWidget(ids,views) } && applied }
            if(previous?.overlay != false && !settings.overlay) applied = effect(Surface.OVERLAY) { c.stopService(Intent(c,FloatingTimerService::class.java));Unit } && applied
            applied
        }
        trace?.mark(if(ancillaryApplied) "ancillary-published" else "ancillary-skipped")
        notificationApplied && ancillaryApplied // Stale/failed attempts never advance observer history.
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
        val trace = TimerActionTrace.begin(context)
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                val runtime = TrackerRuntime.get(context)
                trace?.mark("runtime-ready")
                handleCurrent(context,runtime.tracker,action,trace)
            } catch(_: Exception) { android.util.Log.w("TimerSurfaces","Action unavailable; local state retained") }
            finally { pending.finish() }
        }
    }
    internal suspend fun handleCurrent(context: Context,tracker: Tracker,action: SurfaceAction,trace: TimerActionTrace? = null) = withContext(Dispatchers.IO) {
                val permitted = when(action.source) {
                    Surface.NOTIFICATION -> TimerSurfaces.notificationsAllowed(context)
                    Surface.OVERLAY -> TimerSurfaces.overlayAllowed(context)
                    Surface.WIDGET, Surface.SHORTCUT -> true
                }
                try { TimerSurfaces.execute(tracker,action,permitted);trace?.mark("command-committed") }
                catch(e: CoreError) {
                    trace?.mark("command-rejected")
                    val message = "${e.code}: ${e.message}. Откройте приложение и проверьте текущее состояние."
                    context.getSharedPreferences("surface-results",Context.MODE_PRIVATE).edit().putString("last",message).putString("lastCode",e.code.name).apply()
                    android.os.Handler(android.os.Looper.getMainLooper()).post { android.widget.Toast.makeText(context,message,android.widget.Toast.LENGTH_LONG).show() }
                }
                TimerSurfaces.refreshAfterAction(context,tracker,trace)
    }

}
class TimerWidget: AppWidgetProvider() {
    override fun onUpdate(context: Context,manager: AppWidgetManager,ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try { TimerSurfaces.refresh(context,TrackerRuntime.get(context).tracker,forceWidget = true) }
            catch(_: Exception) { android.util.Log.w("TimerSurfaces","Widget unavailable; open application") }
            finally { pending.finish() }
        }
    }
}
