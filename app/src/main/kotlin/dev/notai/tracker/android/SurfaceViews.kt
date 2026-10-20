package dev.notai.tracker.android

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import dev.notai.tracker.*
import dev.notai.tracker.android.ui.label
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * How the timer looks outside the app: notification and widget share one row (activity icon, name, live time,
 * pause, start/stop); the expanded notification adds up to four quick choices. Rendering only: every command
 * still goes through TimerSurfaces/SurfaceChoices with the token or route that was on screen.
 */
object SurfaceViews {
    private val areaColors = intArrayOf(R.color.area_0, R.color.area_1, R.color.area_2, R.color.area_3, R.color.area_4, R.color.area_5)
    private val chips = listOf(
        Triple(R.id.surface_chip_0, R.id.surface_chip_0_icon, R.id.surface_chip_0_text),
        Triple(R.id.surface_chip_1, R.id.surface_chip_1_icon, R.id.surface_chip_1_text),
        Triple(R.id.surface_chip_2, R.id.surface_chip_2_icon, R.id.surface_chip_2_text),
        Triple(R.id.surface_chip_3, R.id.surface_chip_3_icon, R.id.surface_chip_3_text),
    )

    private class Chrono(val base: Long, val running: Boolean, val countDown: Boolean, val format: String?)
    private class Row(val glyph: Int, val tint: Int, val title: String, val status: String?, val chrono: Chrono?,
                      val primary: TimerCommand?, val secondary: TimerCommand?)
    private class Quick(val route: SurfacePickerRoute, val label: String, val items: List<Triple<QuickChoice, String, Pair<Int, Int>>>)

    /** Simple START/STOP is the main button everywhere outside the app; pause/resume sits beside it. */
    private fun row(d: Dataset, s: Snapshot): Row {
        val label = s.session?.let { d.label(it.selection) }
        val name = label?.let { l -> listOfNotNull(l.title, l.detail).joinToString(" ") } ?: "Без занятия"
        val glyph = when { label == null -> R.drawable.act_unclassified; label.unclassified -> R.drawable.act_unclassified; else -> label.glyph }
        val tint = when { label == null || label.unclassified -> R.color.widget_amber_ink; label.slot != null -> areaColors[label.slot!!]; else -> R.color.widget_ink2 }
        val f = s.focus
        val now = SystemClock.elapsedRealtime()
        return when {
            s.mode == Mode.RECOVERY || f?.mode == FocusMode.RECOVERY ->
                Row(R.drawable.act_unclassified, R.color.widget_amber_ink, "Время неизвестно", "Открой, чтобы указать, когда закончил", null, null, null)
            f != null -> {
                val phase = if (f.phase == FocusPhase.WORK) "Работа" else "Перерыв"
                when (f.mode) {
                    FocusMode.DUE -> Row(glyph, tint, name, "$phase закончилась. Открой, чтобы продолжить", null, TimerCommand.STOP, null)
                    FocusMode.PAUSED -> Row(glyph, tint, name, null, Chrono(now + f.remainingMs, false, true, "Пауза · осталось %s"), TimerCommand.STOP, TimerCommand.RESUME)
                    else -> Row(glyph, tint, name, null, Chrono(now + f.remainingMs, true, true, "$phase · осталось %s"), TimerCommand.STOP, TimerCommand.PAUSE)
                }
            }
            s.mode == Mode.RUNNING -> Row(glyph, tint, name, null, Chrono(now - s.trustedDurationMs, true, false, null), TimerCommand.STOP, TimerCommand.PAUSE)
            s.mode == Mode.PAUSED -> Row(glyph, tint, name, null, Chrono(now - s.trustedDurationMs, false, false, "Пауза · %s"), TimerCommand.STOP, TimerCommand.RESUME)
            else -> Row(R.drawable.ic_stat_notai, R.color.widget_ink2, "Учёт не идёт", "Занятие выберешь потом", null, TimerCommand.START, null)
        }
    }

    private fun bind(c: Context, rv: RemoteViews, r: Row, s: Snapshot, source: Surface) {
        // The shade already shows the app mark in its header: the row repeats only an activity icon, never the brand.
        rv.setViewVisibility(R.id.surface_glyph, if (source == Surface.NOTIFICATION && r.glyph == R.drawable.ic_stat_notai) View.GONE else View.VISIBLE)
        rv.setImageViewResource(R.id.surface_glyph, r.glyph)
        rv.setInt(R.id.surface_glyph, "setColorFilter", c.getColor(r.tint))
        rv.setTextViewText(R.id.surface_title, r.title)
        val chrono = r.chrono
        if (chrono != null) {
            rv.setViewVisibility(R.id.surface_chrono, View.VISIBLE); rv.setViewVisibility(R.id.surface_status, View.GONE)
            rv.setChronometer(R.id.surface_chrono, chrono.base, chrono.format, chrono.running)
            rv.setChronometerCountDown(R.id.surface_chrono, chrono.countDown)
            rv.setTextColor(R.id.surface_chrono, c.getColor(if (chrono.running) R.color.widget_amber_ink else R.color.widget_ink2))
        } else {
            rv.setViewVisibility(R.id.surface_chrono, View.GONE); rv.setViewVisibility(R.id.surface_status, View.VISIBLE)
            rv.setTextViewText(R.id.surface_status, r.status.orEmpty())
        }
        button(c, rv, R.id.surface_primary, r.primary, s, source)
        button(c, rv, R.id.surface_secondary, r.secondary, s, source)
        rv.setOnClickPendingIntent(R.id.surface_row, TimerSurfaces.open(c))
    }

    private fun button(c: Context, rv: RemoteViews, id: Int, cmd: TimerCommand?, s: Snapshot, source: Surface) {
        if (cmd == null) { rv.setViewVisibility(id, View.GONE); return }
        rv.setViewVisibility(id, View.VISIBLE)
        rv.setImageViewResource(id, when (cmd) { TimerCommand.START, TimerCommand.RESUME -> R.drawable.ic_play; TimerCommand.PAUSE -> R.drawable.ic_pause; TimerCommand.STOP -> R.drawable.ic_stop })
        rv.setContentDescription(id, TimerSurfaces.label(cmd))
        rv.setOnClickPendingIntent(id, TimerSurfaces.pending(c, SurfaceAction(source, cmd, s.token, s.timerTarget)))
    }

    /** Favourites, then the most frequent of the last 30 days (core quick source), minus what is already running. */
    private fun quick(d: Dataset, view: TrackerView, s: Snapshot): Quick? {
        if (s.focus != null || s.mode == Mode.RECOVERY) return null
        val route = runCatching { SurfaceChoices.route(Surface.NOTIFICATION, s) }.getOrNull() ?: return null
        val end = minOf(MAX_TIME, System.currentTimeMillis() + 1)
        // Shared core projection excludes the current selection before the compact limit.
        val excluded = if(route.application == ChoiceApplication.SWITCH) s.session?.selection else null
        val items = runCatching { view.choices().quick(HistoryWindow(maxOf(0L, end - 30L * 24 * 60 * 60 * 1000), end), chips.size, excluded) }.getOrDefault(emptyList())
            .map { q ->
                val l = d.label(q.entry.selection)
                val tint = l.slot?.let { areaColors[it] } ?: R.color.widget_ink2
                Triple(q, if (q.entry.kind == ChoiceKind.FAVORITE) q.entry.title else l.title, l.glyph to tint)
            }
        // Keep the full chooser reachable even when no compact suggestion is available.
        return Quick(route, if (route.application == ChoiceApplication.START) "Начать сразу" else "Переключиться на", items)
    }

    private fun bindQuick(c: Context, rv: RemoteViews, q: Quick) {
        rv.setViewVisibility(R.id.surface_quick, View.VISIBLE)
        rv.setTextViewText(R.id.surface_quick_label, q.label)
        rv.setOnClickPendingIntent(R.id.surface_more, SurfaceChoices.pending(c, q.route))
        chips.forEachIndexed { i, (box, icon, text) ->
            val item = q.items.getOrNull(i)
            if (item == null) { rv.setViewVisibility(box, if (i < 2 || q.items.size > 2) View.INVISIBLE else View.GONE); return@forEachIndexed }
            val (choice, title, look) = item
            rv.setViewVisibility(box, View.VISIBLE)
            rv.setImageViewResource(icon, look.first)
            rv.setInt(icon, "setColorFilter", c.getColor(look.second))
            rv.setTextViewText(text, title)
            rv.setContentDescription(box, "${q.label}: $title")
            rv.setOnClickPendingIntent(box, QuickChoiceReceiver.pending(c, q.route, choice.reference, i))
        }
        if (q.items.size <= 2) chips.drop(2).forEach { (box, _, _) -> rv.setViewVisibility(box, View.GONE) }
    }

    /** Collapsed and (when there are quick choices) expanded custom views. Also used to render previews in tests. */
    internal fun notificationViews(c: Context, view: TrackerView): Pair<RemoteViews, RemoteViews?> {
        val d = view.data; val s = view.snapshot()
        val r = row(d, s)
        val small = RemoteViews(c.packageName, R.layout.notification_timer).also { bind(c, it, r, s, Surface.NOTIFICATION) }
        val big = quick(d, view, s)?.let { q -> RemoteViews(c.packageName, R.layout.notification_timer_big).also { bind(c, it, r, s, Surface.NOTIFICATION); bindQuick(c, it, q) } }
        return small to big
    }

    internal fun notificationVisibility(policy: NotificationPrivacy?): Int = when(policy) {
        null, NotificationPrivacy.PRIVATE -> Notification.VISIBILITY_PRIVATE
        NotificationPrivacy.FULL -> Notification.VISIBILITY_PUBLIC
        NotificationPrivacy.HIDDEN -> Notification.VISIBILITY_SECRET
    }

    /** Android presentation boundary; the default comes from the saved surface policy.
     * PUBLIC retains the full row/actions/chronometer, PRIVATE supplies only a redacted public
     * version, SECRET requests no lock-screen content. System/user channel policy remains final.
     */
    fun notification(c: Context, view: TrackerView, lockScreenVisibility: Int = notificationVisibility(view.data.settings.surfaces.notificationPrivacy)): Notification {
        require(lockScreenVisibility in setOf(Notification.VISIBILITY_PUBLIC,Notification.VISIBILITY_PRIVATE,Notification.VISIBILITY_SECRET))
        TimerSurfaces.channels(c)
        val s = view.snapshot()
        val r = row(view.data, s)
        val (small, big) = notificationViews(c, view)
        val builder = Notification.Builder(c, TimerSurfaces.CONTROL_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notai).setColor(c.getColor(R.color.widget_amber))
            .setStyle(Notification.DecoratedCustomViewStyle()).setCustomContentView(small)
            .setContentTitle(r.title).setContentText(s.safeLabel).setContentIntent(TimerSurfaces.open(c))
            .setOnlyAlertOnce(true).setOngoing(true).setShowWhen(false).setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(lockScreenVisibility)
        if (lockScreenVisibility == Notification.VISIBILITY_PRIVATE) builder.setPublicVersion(
            Notification.Builder(c, TimerSurfaces.CONTROL_CHANNEL).setSmallIcon(R.drawable.ic_stat_notai)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setShowWhen(false)
                .setColor(c.getColor(R.color.widget_amber)).setContentTitle(if (s.mode == Mode.IDLE && s.focus == null) "(not)AI Time" else "Учёт идёт").build())
        if (big != null) builder.setCustomBigContentView(big)
        return builder.build()
    }

    /** Narrow RemoteViews binding for a dedicated background ImageView. The XML owner supplies
     * its ID; using image alpha preserves the shape/corners and does not fade foreground content.
     */
    internal fun widgetBackgroundAlpha(views: RemoteViews, backgroundImageId: Int, percent: Int) {
        require(percent in 0..100)
        views.setInt(backgroundImageId,"setImageAlpha",(percent * 255 + 50) / 100)
    }

    fun widget(c: Context, view: TrackerView, enabled: Boolean, backgroundAlphaPercent: Int = view.data.settings.surfaces.widgetBackgroundAlphaPercent): RemoteViews = RemoteViews(c.packageName, R.layout.timer_widget).also { rv ->
        val s = view.snapshot()
        val r = if (enabled) row(view.data, s)
            else Row(R.drawable.ic_stat_notai, R.color.widget_ink2, "Виджет выключен", "Включи его в настройках приложения", null, null, null)
        bind(c, rv, r, s, Surface.WIDGET)
        widgetBackgroundAlpha(rv,R.id.widget_background,backgroundAlphaPercent)
    }
}

/**
 * A quick choice tapped in the notification: applied in the background without opening the app.
 * The route carries the timer target that was shown, so a repeated or late tap fails STALE instead of switching twice.
 */
class QuickChoiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val route = SurfaceChoices.parse(intent) ?: return
        val reference = intent.getStringExtra(EXTRA_CHOICE)?.takeIf { it.length <= 4096 }
            ?.let { runCatching { codec.decodeFromString(ChoiceReference.serializer(), it) }.getOrNull() } ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val runtime = TrackerRuntime.get(context)
                try { SurfaceChoices.choose(context, runtime.tracker, route, reference) }
                catch (e: CoreError) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        android.widget.Toast.makeText(context, "Таймер уже изменился. Открой уведомление ещё раз", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                TimerSurfaces.refresh(context, runtime.tracker)
            } catch (_: Exception) { android.util.Log.w("SurfaceViews", "Quick choice unavailable; history unchanged") }
            finally { pending.finish() }
        }
    }

    companion object {
        const val EXTRA_CHOICE = "quickChoice"
        fun pending(c: Context, route: SurfacePickerRoute, reference: ChoiceReference, index: Int): PendingIntent {
            val intent = SurfaceChoices.intent(c, route).setClass(c, QuickChoiceReceiver::class.java)
                .setData(Uri.Builder().scheme("notai").authority("quick").appendPath(route.id).appendPath(index.toString()).build())
                .putExtra(EXTRA_CHOICE, codec.encodeToString(ChoiceReference.serializer(), reference))
            return PendingIntent.getBroadcast(c, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
