package dev.notai.tracker.android

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.*
import android.view.*
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dev.notai.tracker.*
import dev.notai.tracker.android.ui.*
import kotlinx.coroutines.*

/**
 * The floating button: a small translucent puck with one action, START or STOP (TimerSurfaces.overlayPrimary).
 * After an overlay STOP the core returns a durable assignment receipt; a compact card then asks what the
 * stopped time was. The service exists only while the user-enabled button is visible; it does not keep time.
 * No boot receiver, sticky restart, alarm, wake lock or periodic DB write.
 */
class FloatingTimerService : Service() {
    private val scope = MainScope()
    private val owner = OverlayOwner()
    private var puck: ComposeView? = null
    private var panel: ComposeView? = null
    private var puckParams: WindowManager.LayoutParams? = null
    private var observation: Job? = null
    private var tracker: Tracker? = null
    private var hideJob: Job? = null
    private val snapshot = mutableStateOf<Snapshot?>(null)
    private val assignment = mutableStateOf<OverlayAssignment?>(null)
    private val busy = mutableStateOf(false)
    private val windows get() = getSystemService(WindowManager::class.java)
    private val prefs get() = getSharedPreferences("overlay", Context.MODE_PRIVATE)
    private val permissionWatcher = AppOpsManager.OnOpChangedListener { _, _ ->
        if (!TimerSurfaces.overlayAllowed(this)) Handler(Looper.getMainLooper()).post { stopSelf() }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        owner.start()
        getSystemService(AppOpsManager::class.java).startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, permissionWatcher)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!TimerSurfaces.overlayAllowed(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            val notification = TimerSurfaces.overlayNotification(this)
            if (Build.VERSION.SDK_INT >= 34) startForeground(TimerSurfaces.OVERLAY_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(TimerSurfaces.OVERLAY_ID, notification)
        } catch (_: RuntimeException) { stopSelf(); return START_NOT_STICKY }
        if (observation == null) observation = scope.launch {
            try {
                val t = withContext(Dispatchers.IO) { TrackerRuntime.get(this@FloatingTimerService).tracker }
                tracker = t
                t.views.collect { view ->
                    if (!view.data.settings.surfaces.overlay || !TimerSurfaces.overlayAllowed(this@FloatingTimerService)) { stopSelf(); return@collect }
                    val s = view.snapshot()
                    snapshot.value = s
                    // Any later timer transition invalidates the receipt; the card goes with it.
                    assignment.value?.let { a -> if (a.route.target != s.timerTarget) hidePanel() }
                    showPuck()
                }
            } catch (e: Exception) { if (e is CancellationException) throw e; stopSelf() }
        }
        return START_NOT_STICKY
    }

    private fun overlayParams(width: Int, height: Int) = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT)

    private fun ComposeView.attach() { setViewTreeLifecycleOwner(owner); setViewTreeSavedStateRegistryOwner(owner) }

    /** The saved place belongs to an earlier screen size: keep the button on the current screen, on its edge. */
    private fun place(params: WindowManager.LayoutParams) {
        val metrics = resources.displayMetrics
        val size = params.width
        val margin = (8 * metrics.density).toInt()
        params.x = if (prefs.getBoolean("left", false)) margin else (metrics.widthPixels - size - margin).coerceAtLeast(0)
        params.y = params.y.coerceIn(0, (metrics.heightPixels - size).coerceAtLeast(0))
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val view = puck ?: return; val params = puckParams ?: return
        place(params); runCatching { windows.updateViewLayout(view, params) }
    }

    private fun showPuck() {
        if (puck != null) return
        val metrics = resources.displayMetrics
        val size = (PUCK_DP * metrics.density).toInt()
        val margin = (8 * metrics.density).toInt()
        val params = overlayParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
            y = prefs.getInt("y", (metrics.heightPixels * 0.38f).toInt())
        }
        place(params)
        puckParams = params
        val view = ComposeView(this).apply {
            attach()
            setContent {
                Puck(snapshot.value, busy.value, onTap = ::tap, onDrag = { dx, dy ->
                    val m = resources.displayMetrics
                    params.x = (params.x + dx).toInt().coerceIn(0, (m.widthPixels - size).coerceAtLeast(0))
                    params.y = (params.y + dy).toInt().coerceIn(0, (m.heightPixels - size).coerceAtLeast(0))
                    runCatching { windows.updateViewLayout(this, params) }
                }, onDragEnd = {
                    val m = resources.displayMetrics
                    val left = params.x + size / 2 < m.widthPixels / 2
                    params.x = if (left) margin else m.widthPixels - size - margin
                    runCatching { windows.updateViewLayout(this, params) }
                    prefs.edit().putBoolean("left", left).putInt("y", params.y).apply()
                })
            }
        }
        try { windows.addView(view, params); puck = view } catch (_: RuntimeException) { stopSelf() }
    }

    /** START or STOP only. Recovery has no automatic command: the tap opens the app. */
    private fun tap() {
        val s = snapshot.value ?: return
        val t = tracker ?: return
        val command = TimerSurfaces.overlayPrimary(s)
        if (command == null) { try { TimerSurfaces.open(this).send() } catch (_: PendingIntent.CanceledException) { }; return }
        if (busy.value) return
        busy.value = true
        scope.launch {
            try {
                val change = withContext(Dispatchers.IO) {
                    TimerSurfaces.execute(t, SurfaceAction(Surface.OVERLAY, command, s.token, s.timerTarget), TimerSurfaces.overlayAllowed(this@FloatingTimerService))
                }
                change.assignment?.let { offer -> offerAssignment(t, change, offer) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Toast.makeText(this@FloatingTimerService, "Таймер уже изменился. Нажми ещё раз", Toast.LENGTH_SHORT).show()
            } finally { busy.value = false }
        }
    }

    /** The stopped record already has a label: nothing to ask, the receipt is closed. Otherwise show the short card. */
    private suspend fun offerAssignment(t: Tracker, change: Tracker.Change, offer: StoppedAssignment) {
        val d = change.data
        val session = d.sessions.firstOrNull { it.id == offer.sessionId } ?: return
        if (!d.label(session.selection).unclassified) { withContext(Dispatchers.IO) { runCatching { t.cancelAssignment(offer.id) } }; return }
        val route = SurfaceChoices.route(Surface.OVERLAY, change.state, offer)
        val state = withContext(Dispatchers.IO) { runCatching { SurfaceChoices.read(t, route) }.getOrNull() } ?: return
        val zone = runCatching { java.time.ZoneId.of(d.settings.reportZone) }.getOrDefault(java.time.ZoneId.systemDefault())
        val segments = d.segments.filter { it.sessionId == session.id }
        val range = segments.minOfOrNull { it.start }?.let { a -> Format.range(a, segments.mapNotNull { it.end }.maxOrNull() ?: a, zone) }
        val choices = state.choices.filter { it.entry.canStart && !d.label(it.entry.selection).unclassified }.take(6).map { q ->
            val l = d.label(q.entry.selection)
            OverlayChoice(q, if (q.entry.kind == ChoiceKind.FAVORITE) q.entry.title else l.title, l.glyph, l.slot)
        }
        assignment.value = OverlayAssignment(route, range, choices)
        showPanel()
        hideJob?.cancel()
        hideJob = scope.launch { delay(PANEL_TIMEOUT_MS); dismiss(leave = true) }
    }

    private fun showPanel() {
        if (panel != null) return
        val view = ComposeView(this).apply {
            attach()
            setContent {
                val theme = remember { runCatching { ThemeChoice.valueOf(getSharedPreferences("ui", Context.MODE_PRIVATE).getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeChoice.SYSTEM) }
                NotAiTheme(theme) {
                    assignment.value?.let { a -> AssignmentCard(a, onChoose = ::choose, onLeave = { dismiss(leave = true) }) }
                }
            }
        }
        val params = overlayParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.BOTTOM; y = (56 * resources.displayMetrics.density).toInt()
        }
        try { windows.addView(view, params); panel = view } catch (_: RuntimeException) { assignment.value = null }
    }

    /** Bind to the chooser card's explicit "all activities" action; retain the original receipt/target.
     * Opening the app transfers review, it does not leave/cancel the saved assignment.
     */
    internal fun openAssignmentChooser() {
        val route = assignment.value?.route ?: return
        try {
            startActivity(SurfaceChoices.intent(this,route).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            hidePanel()
        } catch (_: RuntimeException) {
            Toast.makeText(this,"Открыть выбор не удалось. Запись сохранена",Toast.LENGTH_SHORT).show()
        }
    }

    private fun choose(choice: OverlayChoice) {
        val a = assignment.value ?: return
        val t = tracker ?: return
        hideJob?.cancel()
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { SurfaceChoices.choose(this@FloatingTimerService, t, a.route, choice.choice.reference) } }
            hidePanel()
            Toast.makeText(this@FloatingTimerService, if (result.isSuccess) "Записано: ${choice.title}" else "Запись уже изменилась. Исправь её в приложении",
                Toast.LENGTH_SHORT).show()
        }
    }

    /** "Leave without activity" and the timeout consume the receipt; the stopped time itself stays saved. */
    private fun dismiss(leave: Boolean) {
        val a = assignment.value
        val t = tracker
        hidePanel()
        if (leave && a?.route?.assignmentId != null && t != null) scope.launch(Dispatchers.IO) { runCatching { t.cancelAssignment(a.route.assignmentId) } }
    }

    private fun hidePanel() {
        hideJob?.cancel(); hideJob = null
        assignment.value = null
        panel?.let { runCatching { windows.removeView(it) } }; panel = null
    }

    override fun onDestroy() {
        scope.cancel()
        getSystemService(AppOpsManager::class.java).stopWatchingMode(permissionWatcher)
        listOfNotNull(puck, panel).forEach { runCatching { windows.removeView(it) } }; puck = null; panel = null
        owner.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val PUCK_DP = 52
        private const val PANEL_TIMEOUT_MS = 30_000L

        /** Only the resumed Activity calls this; never rely on a background-start exemption. */
        fun fromVisibleActivity(activity: Activity, settings: SurfaceSettings): String {
            if (!settings.overlay) { activity.stopService(Intent(activity, FloatingTimerService::class.java)); return "Плавающая кнопка выключена" }
            if (!TimerSurfaces.overlayAllowed(activity)) return "Плавающая кнопка: разрешение ОС не предоставлено; таймер доступен в приложении"
            return try {
                activity.startForegroundService(Intent(activity, FloatingTimerService::class.java))
                "Плавающая кнопка запрошена; системный индикатор сервиса обязателен"
            } catch (_: RuntimeException) { "ОС не разрешила запуск плавающей кнопки; таймер доступен в приложении" }
        }
    }
}

internal class OverlayChoice(val choice: QuickChoice, val title: String, val glyph: Int, val slot: Int?)
internal class OverlayAssignment(val route: SurfacePickerRoute, val range: String?, val choices: List<OverlayChoice>)

/** Lifecycle and saved state for Compose windows owned by a service. */
private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val saved = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
    fun start() { saved.performAttach(); saved.performRestore(null); registry.currentState = Lifecycle.State.RESUMED }
    fun stop() { registry.currentState = Lifecycle.State.DESTROYED }
}

/**
 * Translucent dark disc with the (not)AI brackets: play when idle, stop while time runs (amber brackets).
 * Dark with a light hairline so it reads over both light and dark apps without covering them.
 */
@Composable
internal fun Puck(s: Snapshot?, busy: Boolean, onTap: () -> Unit, onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit) {
    val command = s?.let(TimerSurfaces::overlayPrimary)
    val running = s != null && (s.mode == Mode.RUNNING || s.focus?.mode == FocusMode.RUNNING)
    val active = command == TimerCommand.STOP
    var pressed by remember { mutableStateOf(false) }
    val opacity by animateFloatAsState(when { pressed -> 0.95f; active -> 0.78f; else -> 0.55f }, tween(180), label = "puck")
    val amber = Color(0xFFFFB23C)
    val label = when (command) { TimerCommand.START -> "Начать учёт"; TimerCommand.STOP -> "Остановить учёт"; else -> "Открыть приложение" }
    Box(Modifier.size(52.dp).padding(4.dp).alpha(opacity).shadow(6.dp, CircleShape).clip(CircleShape).background(Color(0xFF1D2024))
        .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape)
        .semantics { contentDescription = label; role = Role.Button; onClick(label) { onTap(); true } }
        .pointerInput(Unit) {
            detectTapGestures(onPress = { pressed = true; tryAwaitRelease(); pressed = false }, onTap = { if (!busy) onTap() })
        }
        .pointerInput(Unit) {
            detectDragGestures(onDragEnd = onDragEnd, onDragCancel = onDragEnd) { change, drag -> change.consume(); onDrag(drag.x, drag.y) }
        }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(26.dp)) {
            val w = size.width; val h = size.height
            val bracket = if (running) amber else Color.White.copy(alpha = 0.7f)
            val stroke = Stroke(1.7.dp.toPx(), cap = StrokeCap.Round)
            val left = Path().apply { moveTo(w * 0.24f, h * 0.06f); cubicTo(w * 0.04f, h * 0.36f, w * 0.04f, h * 0.64f, w * 0.24f, h * 0.94f) }
            val right = Path().apply { moveTo(w * 0.76f, h * 0.06f); cubicTo(w * 0.96f, h * 0.36f, w * 0.96f, h * 0.64f, w * 0.76f, h * 0.94f) }
            drawPath(left, bracket, style = stroke); drawPath(right, bracket, style = stroke)
            val ink = Color.White.copy(alpha = 0.92f)
            when (command) {
                TimerCommand.START -> drawPath(Path().apply {
                    moveTo(w * 0.42f, h * 0.34f); lineTo(w * 0.66f, h * 0.5f); lineTo(w * 0.42f, h * 0.66f); close()
                }, ink)
                TimerCommand.STOP -> drawRoundRect(ink, Offset(w * 0.38f, h * 0.38f), Size(w * 0.24f, h * 0.24f), CornerRadius(1.5.dp.toPx()))
                else -> drawCircle(ink, w * 0.05f, Offset(w / 2, h / 2))
            }
        }
    }
}

/** After STOP: the saved record and up to six quick choices. Nothing here changes the stopped time. */
@Composable
internal fun AssignmentCard(a: OverlayAssignment, onChoose: (OverlayChoice) -> Unit, onLeave: () -> Unit) {
    val c = NotAi.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth().shadow(16.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp))
            .background(c.raised).border(1.dp, c.rule, RoundedCornerShape(24.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Чем ты был занят?", style = NotAi.type.sheetTitle, color = c.ink)
                Text(listOfNotNull(a.range, "время уже сохранено").joinToString(" · "), style = NotAi.type.caption, color = c.ink2)
            }
            a.choices.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { choice ->
                        Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(c.sunk)
                            .pressable({ onChoose(choice) }, label = choice.title).padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActivityGlyph(choice.glyph, choice.slot, size = 18.dp)
                            Text(choice.title, style = NotAi.type.bodySmall, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            if (a.choices.isEmpty()) Text("Частых занятий пока нет. Выбрать можно в приложении, в истории.", style = NotAi.type.bodySmall, color = c.ink2)
            PillButton("Оставить без занятия", onLeave, Modifier.align(Alignment.End), ButtonKind.TEXT, height = 40.dp)
        }
    }
}
