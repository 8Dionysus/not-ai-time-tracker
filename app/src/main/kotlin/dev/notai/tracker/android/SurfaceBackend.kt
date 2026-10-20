package dev.notai.tracker.android

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import dev.notai.tracker.*
import kotlinx.serialization.Serializable

/** Widget control preference and launcher placement are separate facts. */
enum class WidgetPlacement { AVAILABLE, UNSUPPORTED, PENDING, PINNED, UNCONFIRMED, FAILED }
enum class SurfaceProblem { PERMISSION_REQUIRED, CHANNEL_BLOCKED, FOREGROUND_REQUIRED, LAUNCHER_UNSUPPORTED, PLATFORM_FAILURE, RECEIPT_STORAGE }
data class WidgetState(val supported: Boolean,val ids: List<Int>,val placement: WidgetPlacement,val requestId: String?,val problem: SurfaceProblem? = null)
/** Narrow platform port for deterministic native failure fixtures. Production construction
 * always uses the real AppWidgetManager; durable receipt and foreground gates stay here. */
internal interface WidgetPlacementPlatform {
    val supported: Boolean
    fun ids(provider: ComponentName): IntArray
    fun request(provider: ComponentName,callback: PendingIntent): Boolean
}
private class AndroidWidgetPlacementPlatform(private val context: Context): WidgetPlacementPlatform {
    private val manager get() = AppWidgetManager.getInstance(context)
    override val supported get() = manager.isRequestPinAppWidgetSupported
    override fun ids(provider: ComponentName) = manager.getAppWidgetIds(provider)
    override fun request(provider: ComponentName,callback: PendingIntent) = manager.requestPinAppWidget(provider,null,callback)
}
class WidgetPlacementController internal constructor(private val context: Context,private val platform: WidgetPlacementPlatform) {
    constructor(context: Context): this(context,AndroidWidgetPlacementPlatform(context))
    private val provider get() = ComponentName(context,TimerWidget::class.java)
    private val prefs get() = context.getSharedPreferences("widget-placement",Context.MODE_PRIVATE)
    fun read(reconcileForeground: Boolean = false): WidgetState {
        val supported = platform.supported
        val ids = platform.ids(provider).sorted()
        val request = prefs.getString("request",null)
        var state = runCatching { WidgetPlacement.valueOf(prefs.getString("state",WidgetPlacement.AVAILABLE.name)!!) }.getOrDefault(WidgetPlacement.FAILED)
        state = when { ids.isNotEmpty() -> WidgetPlacement.PINNED; !supported -> WidgetPlacement.UNSUPPORTED
            state == WidgetPlacement.PINNED -> WidgetPlacement.UNCONFIRMED
            reconcileForeground && state == WidgetPlacement.PENDING -> WidgetPlacement.UNCONFIRMED; else -> state }
        if(reconcileForeground) prefs.edit().putString("state",state.name).commit()
        return WidgetState(supported,ids,state,request,if(!supported) SurfaceProblem.LAUNCHER_UNSUPPORTED else null)
    }
    /** Explicit user action from a visible Activity. A true platform result is only an accepted request. */
    fun request(activity: Activity): WidgetState {
        val before = read()
        if(before.ids.isNotEmpty() || !before.supported) return before
        val foreground = android.os.Looper.myLooper() == android.os.Looper.getMainLooper() && if(activity is ComponentActivity) activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) else activity.hasWindowFocus()
        if(!foreground) return before.copy(problem = SurfaceProblem.FOREGROUND_REQUIRED)
        if(before.placement == WidgetPlacement.PENDING) return before // foreground reconciliation enables an explicit retry
        val id = newId()
        if(!prefs.edit().putString("request",id).putString("state",WidgetPlacement.PENDING.name).commit())
            return before.copy(placement = WidgetPlacement.FAILED,problem = SurfaceProblem.RECEIPT_STORAGE)
        val callback = Intent(context,WidgetPinReceiver::class.java).setAction("dev.notai.tracker.WIDGET_PIN")
            .setData(Uri.Builder().scheme("notai").authority("widget-pin").appendPath(id).build()).putExtra("request",id)
        // Immutable callback: don't rely on launcher fill-in extras, query actual IDs after callback instead.
        val pending = PendingIntent.getBroadcast(context,0,callback,PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return try {
            if(platform.request(provider,pending)) read()
            else { prefs.edit().putString("state",WidgetPlacement.UNCONFIRMED.name).commit(); read().copy(problem = SurfaceProblem.LAUNCHER_UNSUPPORTED) }
        } catch(_: RuntimeException) {
            prefs.edit().putString("state",WidgetPlacement.FAILED.name).commit()
            read().copy(problem = SurfaceProblem.PLATFORM_FAILURE)
        }
    }
    fun callback(id: String?) {
        if(id == null || id != prefs.getString("request",null)) return
        // Callback is not assumed to imply placement; IDs remain the authoritative observation.
        prefs.edit().putString("state",if(platform.ids(provider).isNotEmpty()) WidgetPlacement.PINNED.name else WidgetPlacement.UNCONFIRMED.name).commit()
    }
}
class WidgetPinReceiver: BroadcastReceiver() {
    override fun onReceive(context: Context,intent: Intent) {
        if(intent.action == "dev.notai.tracker.WIDGET_PIN") WidgetPlacementController(context).callback(intent.getStringExtra("request"))
    }
}

@Serializable data class SurfacePickerRoute(val id: String,val source: String,val target: TimerTarget,val assignmentId: String? = null,val application: ChoiceApplication = ChoiceApplication.START)
data class SurfacePickerState(val route: SurfacePickerRoute,val choices: List<QuickChoice>,val assignment: StoppedAssignment?)
object SurfaceChoices {
    const val ACTION = "dev.notai.tracker.SURFACE_PICKER"
    private const val EXTRA = "surfacePicker"
    private const val SAVED = "surfacePickerPending"
    fun route(source: Surface,snapshot: Snapshot,assignment: StoppedAssignment? = null): SurfacePickerRoute {
        require(source in setOf(Surface.NOTIFICATION,Surface.OVERLAY,Surface.SHORTCUT))
        return SurfacePickerRoute(newId(),source.name,requireNotNull(snapshot.timerTarget),assignment?.id,if(snapshot.mode == Mode.IDLE && snapshot.focus == null) ChoiceApplication.START else ChoiceApplication.SWITCH)
    }
    fun intent(context: Context,route: SurfacePickerRoute): Intent = Intent(context,MainActivity::class.java).setAction(ACTION)
        .setData(Uri.Builder().scheme("notai").authority("picker").appendPath(route.id).build()).putExtra(EXTRA,codec.encodeToString(route))
    /** Direct Activity PendingIntent: no receiver/service startActivity notification trampoline. */
    fun pending(context: Context,route: SurfacePickerRoute): PendingIntent = PendingIntent.getActivity(context,0,intent(context,route),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun parse(intent: Intent): SurfacePickerRoute? {
        if(intent.action != ACTION) return null
        val payload = intent.getStringExtra(EXTRA) ?: return null
        if(payload.length > 8192) return null
        return runCatching { codec.decodeFromString<SurfacePickerRoute>(payload).takeIf { it.id.isNotBlank() && it.source in setOf(Surface.NOTIFICATION.name,Surface.OVERLAY.name,Surface.SHORTCUT.name) } }.getOrNull()
    }
    fun restore(saved: android.os.Bundle?,launch: Intent): SurfacePickerRoute? =
        if(saved?.containsKey(SAVED) == true) saved.getString(SAVED)?.let { runCatching { codec.decodeFromString<SurfacePickerRoute>(it) }.getOrNull() } else parse(launch)
    fun save(saved: android.os.Bundle,route: SurfacePickerRoute?) { saved.putString(SAVED,route?.let { codec.encodeToString(it) }) }
    private fun allowed(context: Context,d: Dataset,source: Surface) = TimerSurfaces.enabled(d.settings.surfaces,source) && when(source) {
        Surface.NOTIFICATION -> TimerSurfaces.notificationsAllowed(context)
        Surface.OVERLAY -> TimerSurfaces.overlayAllowed(context)
        Surface.SHORTCUT -> d.focus == null
        else -> false
    }
    fun read(tracker: Tracker,route: SurfacePickerRoute): SurfacePickerState {
        val view = tracker.view(); val snapshot = view.snapshot()
        ensure(snapshot.timerTarget == route.target,"Picker timer target changed",ErrorCode.STALE)
        val offer = route.assignmentId?.let { id -> tracker.pendingAssignments().find { it.id == id } ?: fail(ErrorCode.STALE,"Assignment expired") }
        val end = minOf(MAX_TIME,tracker.clock.now().wall+1)
        val excluded = if(route.assignmentId == null && route.application == ChoiceApplication.SWITCH) snapshot.session?.selection else null
        return SurfacePickerState(route,view.choices().quick(HistoryWindow(maxOf(0,end-30L*24*60*60*1000),end),excludeSelection = excluded),offer)
    }
    /** User explicitly reopens a saved record; stale routes/nonces are not silently refreshed. */
    fun reopen(context: Context,tracker: Tracker,source: Surface,expected: Token,sessionId: String): SurfacePickerRoute {
        require(source in setOf(Surface.NOTIFICATION,Surface.OVERLAY,Surface.SHORTCUT))
        val change = tracker.reopenAssignment(expected,sessionId) { allowed(context,it,source) }
        return route(source,change.state,change.assignment!!)
    }
    fun choose(context: Context,tracker: Tracker,route: SurfacePickerRoute,choice: ChoiceReference,note: String? = null): Tracker.Change {
        val source = runCatching { Surface.valueOf(route.source) }.getOrElse { fail(ErrorCode.INVALID_INPUT,"Unknown picker source") }
        return if(route.assignmentId != null) tracker.assignStopped(route.assignmentId,choice,note,requestTarget = route.target) { allowed(context,it,source) }
        else tracker.applyChoice(route.target,choice,route.application) { allowed(context,it,source) }
    }
    /** The original route gates catalog creation and application inside one Tracker transaction. */
    fun createAndChoose(context: Context,tracker: Tracker,route: SurfacePickerRoute,expected: Token,card: Card): Tracker.Change {
        val source = runCatching { Surface.valueOf(route.source) }.getOrElse { fail(ErrorCode.INVALID_INPUT,"Unknown picker source") }
        return tracker.createCardForChoice(expected,route.target,card,route.application,route.assignmentId) { allowed(context,it,source) }
    }
}

enum class SurfaceSettingsAction { REQUEST_NOTIFICATION_PERMISSION, OPEN_NOTIFICATION_SETTINGS, OPEN_TIMER_CHANNEL_SETTINGS, OPEN_OVERLAY_PERMISSION, ADD_WIDGET, RETRY_WIDGET, REFRESH }
data class SurfaceCapability(val enabled: Boolean,val permitted: Boolean,val problem: SurfaceProblem?,val actions: Set<SurfaceSettingsAction>)
data class SettingsCapabilities(val widget: SurfaceCapability,val widgetPlacement: WidgetState,val notification: SurfaceCapability,val overlay: SurfaceCapability,val focusSignal: SurfaceCapability,
    val shortcut: SurfaceCapability, val timerNotification: TimerNotificationCapability? = null)
object SurfaceSettingsBackend {
    fun read(context: Context,settings: SurfaceSettings,reconcileForeground: Boolean = false): SettingsCapabilities {
        val widget = WidgetPlacementController(context).read(reconcileForeground)
        val notifications = TimerSurfaces.notificationsAllowed(context)
        val permission = android.os.Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val notificationProblem = when { !permission -> SurfaceProblem.PERMISSION_REQUIRED; !notifications -> SurfaceProblem.CHANNEL_BLOCKED; else -> null }
        val overlay = TimerSurfaces.overlayAllowed(context)
        val focus = FocusSignals.allowed(context)
        return SettingsCapabilities(SurfaceCapability(settings.widget,true,widget.problem,setOf(if(widget.placement == WidgetPlacement.UNCONFIRMED) SurfaceSettingsAction.RETRY_WIDGET else SurfaceSettingsAction.ADD_WIDGET)),widget,
            SurfaceCapability(settings.notification,notifications,notificationProblem,setOf(SurfaceSettingsAction.REQUEST_NOTIFICATION_PERMISSION,SurfaceSettingsAction.OPEN_NOTIFICATION_SETTINGS,SurfaceSettingsAction.OPEN_TIMER_CHANNEL_SETTINGS)),
            SurfaceCapability(settings.overlay,overlay,if(overlay) null else SurfaceProblem.PERMISSION_REQUIRED,setOf(SurfaceSettingsAction.OPEN_OVERLAY_PERMISSION)),
            SurfaceCapability(settings.focusSignal,focus,if(focus) null else if(permission) SurfaceProblem.CHANNEL_BLOCKED else SurfaceProblem.PERMISSION_REQUIRED,setOf(SurfaceSettingsAction.OPEN_NOTIFICATION_SETTINGS)),
            SurfaceCapability(settings.shortcut,true,null,emptySet()),timerNotificationCapability(context))
    }
    fun settingsIntent(context: Context,action: SurfaceSettingsAction): Intent? = when(action) {
        SurfaceSettingsAction.OPEN_NOTIFICATION_SETTINGS -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)
        SurfaceSettingsAction.OPEN_TIMER_CHANNEL_SETTINGS -> {
            TimerSurfaces.channels(context) // Ensure a settings destination even before the first notification.
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID,TimerSurfaces.CONTROL_CHANNEL)
        }
        SurfaceSettingsAction.OPEN_OVERLAY_PERMISSION -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:${context.packageName}"))
        else -> null // runtime permission and foreground widget request require their corresponding platform API
    }
}

/** Observed platform capability, not a promise of OEM lock-screen presentation.
 * Only explicit system settings can change importance on the existing control channel.
 * MIN keeps a normal drawer notification while requesting no status-bar icon; NONE blocks it.
 * This never changes or suppresses the separate foreground-service indicator.
 */
data class TimerNotificationCapability(val importance: Int?,val statusBarIconRequested: Boolean?,
    val channelLockScreenVisibility: Int?,val settingsAction: SurfaceSettingsAction = SurfaceSettingsAction.OPEN_TIMER_CHANNEL_SETTINGS)
internal fun timerNotificationCapability(context: Context): TimerNotificationCapability {
    val channel = context.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(TimerSurfaces.CONTROL_CHANNEL)
    val importance = channel?.importance
    return TimerNotificationCapability(importance,importance?.let { it >= android.app.NotificationManager.IMPORTANCE_LOW },channel?.lockscreenVisibility)
}
