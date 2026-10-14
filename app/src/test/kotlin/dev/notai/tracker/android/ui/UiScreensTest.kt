package dev.notai.tracker.android.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeLeft
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.test.core.app.ApplicationProvider
import dev.notai.tracker.*
import dev.notai.tracker.android.FocusDueSignals
import dev.notai.tracker.android.SurfaceAction
import dev.notai.tracker.android.TrackerRuntime
import dev.notai.tracker.android.newInstallData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.ZoneId

/**
 * Renders the product screens with a real core Tracker (in-memory store, fixed clock) through the same
 * view model as the app. Always checks that key states compose with real data; writes PNGs to
 * docs/ui/screens only with -PuiScreenshots=true. JVM rendering is not a physical-device check.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-xhdpi")
class UiScreensTest {
    @get:Rule val compose = createComposeRule()

    private class MutableClock(var wall: Long, var elapsed: Long, var boot: String = "boot:screens") : Clock {
        override fun now() = Moment(wall, elapsed, boot, "process:screens")
        fun advance(ms: Long) { wall += ms; elapsed += ms }
    }
    private class MemoryStore(initial: Dataset) : StateStore {
        private var data = initial
        override val changes = MutableStateFlow(initial)
        @Synchronized override fun read() = data
        @Synchronized override fun <T> transaction(block: (Dataset) -> Pair<Dataset, T>): T {
            val (next, result) = block(data); validate(next); data = next; changes.value = next; return result
        }
    }
    private class MemoryRollback : RollbackStore {
        private val saved = mutableMapOf<String, String>()
        override fun save(json: String) = newId().also { saved[it] = json }
        override fun read(reference: String) = saved.getValue(reference)
    }
    private class TestBackend(override val tracker: Tracker, private val catalog: ReadyCatalog) : UiBackend {
        override val backups = Backups(tracker, MemoryRollback())
        override val focusDue = FocusDueSignals()
        override val runtime: TrackerRuntime? = null
        override fun prepareCatalog() = CatalogImports(tracker) { catalog }.prepare()
        override fun applyCatalog(preview: CatalogPreview) = CatalogImports(tracker) { catalog }.apply(preview)
        override fun submitShortcut(action: SurfaceAction) = CompletableDeferred<Result<Tracker.Change>>(Result.failure(IllegalStateException("no shortcuts")))
    }

    private val zone = ZoneId.of("America/Mexico_City")
    private val today = LocalDate.of(2026, 10, 13)
    private fun at(date: LocalDate, h: Int, m: Int) = date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    private class Seed(val tracker: Tracker, val clock: MutableClock, val catalog: ReadyCatalog)

    /** A believable week: personal cards on catalog types, a favourite, two goals, a session running since 14:05. */
    private fun seed(running: Boolean = true): Seed {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val catalog = ReadyCatalog.read { app.assets.open("catalog/$it") }
        val data = newInstallData(catalog, zone.id)
        val clock = MutableClock(at(today, 14, 5), 9_000_000L)
        val t = Tracker(MemoryStore(data), clock)
        fun tok() = t.snapshot().token
        fun type(name: String) = data.types.first { it.name == name && it.selectable && !it.archived }.id
        fun card(name: String, typeName: String, detail: String): Card = Card(name = name, activityTypeId = type(typeName), details = mapOf("activity" to detail))
            .also { t.saveCard(tok(), it) }
        val english = card("Английский", "Практика языка", "чтение")
        val work = card("Работа", "Сосредоточенная работа", "сосредоточенная")
        val sport = card("Тренировка", "Кардио", "кардио")
        val philosophy = card("Философия", "Сосредоточенная учёба", "чтение")
        val reading = card("Чтение", "Читать книгу", "книга")
        fun sel(c: Card, detail: String? = null) = t.selection(c.id).let { if (detail == null) it else it.copy(details = mapOf("activity" to detail)) }
        fun manual(d: LocalDate, h1: Int, m1: Int, h2: Int, m2: Int, s: Classification, note: String? = null) = t.manual(tok(), at(d, h1, m1), at(d, h2, m2), s, note)
        val week = listOf(
            listOf(Triple(9 to 0, 13 to 0, sel(work)), Triple(18 to 0, 18 to 48, sel(english)), Triple(19 to 0, 20 to 0, sel(sport)), Triple(21 to 0, 21 to 30, sel(philosophy))),
            listOf(Triple(9 to 30, 13 to 0, sel(work)), Triple(17 to 0, 18 to 0, sel(english, "слушание")), Triple(20 to 0, 21 to 12, sel(philosophy))),
            listOf(Triple(9 to 0, 14 to 0, sel(work)), Triple(15 to 0, 15 to 30, sel(english)), Triple(18 to 0, 19 to 6, sel(sport))),
            listOf(Triple(10 to 0, 11 to 12, sel(english, "разговор")), Triple(12 to 0, 13 to 30, sel(sport)), Triple(16 to 0, 17 to 24, sel(reading))),
            listOf(Triple(11 to 0, 11 to 54, sel(english, "фильмы")), Triple(15 to 0, 16 to 30, sel(philosophy)), Triple(20 to 0, 21 to 0, sel(reading))),
        )
        week.forEachIndexed { i, day -> day.forEach { (a, b, s) -> manual(today.minusDays(6L - i), a.first, a.second, b.first, b.second, s) } }
        val yesterday = today.minusDays(1)
        manual(yesterday, 9, 0, 12, 20, sel(work))
        manual(yesterday, 13, 0, 13, 30, Classification())
        manual(yesterday, 17, 30, 18, 20, sel(sport, "силовая"))
        manual(yesterday, 19, 10, 19, 55, sel(english, "фильмы"))
        manual(today, 8, 30, 8, 55, sel(english))
        manual(today, 9, 10, 11, 2, sel(work))
        manual(today, 11, 15, 12, 20, sel(sport))
        manual(today, 12, 30, 13, 40, sel(philosophy), "конспект второй главы")
        t.saveFavorite(tok(), Favorite(label = "Утренний английский", selection = sel(english)))
        t.saveGoal(tok(), Goal(name = "Английский", metric = GoalMetric.DURATION_MS, target = 30 * 60_000L, direction = GoalDirection.MINIMUM,
            period = GoalPeriod.DAY, zone = zone.id, filter = Filter(cardId = english.id)))
        t.saveGoal(tok(), Goal(name = "Работа", metric = GoalMetric.DURATION_MS, target = 6 * 3_600_000L, direction = GoalDirection.LIMIT,
            period = GoalPeriod.DAY, zone = zone.id, filter = Filter(cardId = work.id)))
        if (running) {
            t.start(tok(), sel(english, "слушание"))
            clock.advance(84 * 60_000L + 7_000L)
        } else clock.advance(84 * 60_000L)
        return Seed(t, clock, catalog)
    }

    private var actionReady = 0

    private fun render(seed: Seed,reducedMotion: Boolean = true,initialTheme: ThemeChoice? = null): TrackerViewModel {
        UiBackends.open = { TestBackend(seed.tracker, seed.catalog) }
        val vm = TrackerViewModel(ApplicationProvider.getApplicationContext())
        initialTheme?.let(vm::setTheme)
        compose.setContent { TrackerApp(vm, openTechnical = {}, reducedMotion = reducedMotion, onActionReady = { actionReady++ }) }
        waitText("Сегодня")
        assertTrue("startup trace hook fires once controls are live", actionReady > 0)
        return vm
    }

    private fun waitText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    /** Every attached window (screen, then sheets/dialogs), drawn in software and composited bottom-aligned. */
    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<android.view.View> {
        val global = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val names = global.javaClass.getMethod("getViewRootNames").invoke(global) as Array<String>
        return names.map { global.javaClass.getMethod("getRootView", String::class.java).invoke(global, it) as android.view.View }
            .filter { it.isAttachedToWindow && it.width > 0 && it.height > 0 }
    }

    private fun shot(name: String, vm: TrackerViewModel) {
        for (theme in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            vm.setTheme(theme)
            repeat(3) { compose.waitForIdle(); org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(150) }
            if (System.getProperty("tracker.screenshots") != "true") continue
            val views = windows()
            val first = views.first()
            val base = Bitmap.createBitmap(first.width, first.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(base)
            views.forEach { v -> drawWindow(canvas, first, v) }
            save(base, "$name-${theme.name.lowercase()}")
        }
    }

    /** Popups (menus) at their own window position; sheets and dialogs bottom-aligned as the system places them. */
    private fun drawWindow(canvas: Canvas, first: android.view.View, v: android.view.View) {
        val lp = v.layoutParams as? android.view.WindowManager.LayoutParams
        val positioned = lp != null && v !== first && (lp.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK) == android.view.Gravity.TOP && (lp.x != 0 || lp.y != 0)
        val centred = lp != null && v !== first && (lp.gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK) == android.view.Gravity.CENTER_VERTICAL
        canvas.save()
        when {
            positioned -> canvas.translate(lp!!.x.toFloat(), lp.y.toFloat())
            centred -> canvas.translate((first.width - v.width) / 2f, (first.height - v.height) / 2f)
            else -> canvas.translate(0f, (first.height - v.height).toFloat())
        }
        v.draw(canvas); canvas.restore()
    }

    private fun save(bitmap: Bitmap, file: String) {
        if (System.getProperty("tracker.screenshots") != "true") return
        val dir = File(System.getProperty("tracker.screenshotDir")!!).apply { mkdirs() }
        FileOutputStream(File(dir, "$file.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Composables outside the app shell (floating button), drawn in both themes. */
    private fun shotContent(name: String, content: @androidx.compose.runtime.Composable (ThemeChoice) -> Unit) {
        val theme = androidx.compose.runtime.mutableStateOf(ThemeChoice.LIGHT)
        compose.setContent { content(theme.value) }
        for (t in listOf(ThemeChoice.LIGHT, ThemeChoice.DARK)) {
            theme.value = t
            repeat(3) { compose.waitForIdle(); org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(120) }
            val views = windows(); val first = views.first()
            val base = Bitmap.createBitmap(first.width, first.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(base); views.forEach { v -> drawWindow(canvas, first, v) }
            save(base, "$name-${t.name.lowercase()}")
        }
    }

    @Test fun runningTimer() {
        val vm = render(seed())
        waitText("1:24")
        assertTrue(compose.onAllNodesWithText("слушание", substring = true).fetchSemanticsNodes().isNotEmpty())
        shot("01-timer-running", vm)
    }

    @Test fun idleTimer() {
        val seed = seed(running = false)
        val vm = render(seed)
        waitText("Начать")
        shot("00-timer-idle", vm)
    }

    @Test fun picker() {
        val vm = render(seed())
        waitText("Английский")
        compose.onAllNodesWithText("слушание", substring = true).onFirst().performClick()
        waitText("Уточнить текущее")
        waitText("Недавние")
        shot("02-picker", vm)
    }

    @Test fun correction() {
        val vm = render(seed())
        waitText("9:10–11:02")
        compose.onAllNodesWithText("9:10–11:02", substring = true).onFirst().performClick()
        waitText("Исправить запись")
        compose.onAllNodesWithContentDescription("Начало раньше на 5 минут")[0].performClick()
        waitText("→ 6 ч 01 мин")
        shot("03-correction", vm)
    }

    @Test fun focus() {
        val seed = seed(running = false)
        seed.tracker.startFocus(seed.tracker.snapshot().token, seed.tracker.selection(seed.tracker.data().cards.first { it.name == "Работа" }.id))
        seed.clock.advance(6 * 60_000L + 18_000L)
        val vm = render(seed)
        waitText("осталось из")
        shot("04-focus", vm)
    }

    @Test fun finishedFocusKeepsItsSnapshotDuringOutgoingAnimation() {
        val seed = seed(running = false)
        seed.tracker.startFocus(seed.tracker.snapshot().token)
        val vm = render(seed,reducedMotion = false)
        waitText("осталось из")
        compose.mainClock.autoAdvance = false
        vm.stop()
        compose.waitUntil(5_000) { seed.tracker.data().focus == null }
        repeat(5) { compose.mainClock.advanceTimeBy(16);compose.waitForIdle() }
        compose.mainClock.advanceTimeBy(400)
        compose.mainClock.autoAdvance = true
        waitText("Время пойдёт сразу.")
        assertTrue(seed.tracker.data().focus == null)
    }

    @Test fun largeFontSearchPlaceholderFitsItsContainer() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,1.5f)) {
                NotAiTheme(ThemeChoice.LIGHT,reducedMotion = true) {
                    SearchField("",{},"Найти или создать занятие",Modifier.width(320.dp).testTag("qa-search"))
                }
            }
        }
        compose.waitForIdle()
        val container = compose.onNodeWithTag("qa-search").fetchSemanticsNode().boundsInRoot
        val text = compose.onNodeWithText("Найти или создать занятие").fetchSemanticsNode().boundsInRoot
        assertTrue("two-line placeholder fits vertically",text.top >= container.top && text.bottom <= container.bottom)
    }

    @Test fun crossMidnightSegmentHasDistinctKeysForEachCalendarDay() {
        val seed = seed(running = false)
        seed.tracker.manual(seed.tracker.snapshot().token,at(today.minusDays(1),23,55),at(today,0,5),
            Classification(),"cross-midnight")
        render(seed)
        compose.onNode(hasScrollAction()).performScrollToIndex(7)
        compose.waitForIdle()
        assertTrue("both clipped day rows compose",compose.onAllNodesWithText("cross-midnight",substring = true).fetchSemanticsNodes().size == 2)
    }

    @Test fun headerThemeToggleUsesCurrentAppearanceOnEveryClick() {
        val vm = render(seed(running = false),initialTheme = ThemeChoice.LIGHT)
        compose.onNodeWithContentDescription("Тёмная тема").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(ThemeChoice.DARK,vm.theme.value) }
        compose.onNodeWithContentDescription("Светлая тема").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(ThemeChoice.LIGHT,vm.theme.value) }
        compose.onNodeWithContentDescription("Тёмная тема").performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals(ThemeChoice.DARK,vm.theme.value) }
    }

    @Test fun overview() {
        val vm = render(seed())
        compose.onNodeWithText("Обзор").performClick()
        waitText("По областям")
        waitText("Работа и проекты")
        shot("05-overview", vm)
    }

    @Test fun settings() {
        val vm = render(seed())
        compose.onNodeWithText("Настройки").performClick()
        waitText("Таймер вне приложения")
        waitText("Резервная копия")
        shot("06-settings", vm)
    }

    /** Long press opens the menu; delete goes straight through with Undo for a one-segment record. Swipe does the same. */
    @Test fun deleteFromMenuAndSwipe() {
        val seed = seed()
        val vm = render(seed)
        waitText("8:30–8:55")
        val before = seed.tracker.data().sessions.size
        compose.onAllNodesWithText("9:10–11:02", substring = true).onFirst().performTouchInput { longClick() }
        waitText("Удалить")
        shot("08-entry-menu", vm)
        compose.onNodeWithText("Удалить").performClick()
        compose.waitUntil(5_000) { seed.tracker.data().sessions.size == before - 1 }
        waitText("Запись удалена")
        compose.onAllNodesWithText("8:30–8:55", substring = true).onFirst().performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { seed.tracker.data().sessions.size == before - 2 }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("8:30–8:55", substring = true).fetchSemanticsNodes().isEmpty() }
    }

    /** A record split by a pause takes all its segments with it: one explicit confirmation first. */
    @Test fun deleteMultiSegmentAsksFirstAndSheetIcon() {
        val seed = seed(running = false)
        val t = seed.tracker
        t.start(t.snapshot().token); seed.clock.advance(10 * 60_000L)
        t.pause(t.snapshot().token); seed.clock.advance(5 * 60_000L)
        t.resume(t.snapshot().token); seed.clock.advance(10 * 60_000L)
        t.stop(t.snapshot().token)
        val vm = render(seed)
        waitText("15:29–15:39")
        val before = t.data().sessions.size
        compose.onAllNodesWithText("15:29–15:39", substring = true).onFirst().performClick()
        waitText("Исправить запись")
        compose.onNodeWithContentDescription("Удалить запись").performClick()
        waitText("Удалить запись целиком?")
        shot("09-delete-confirm", vm)
        compose.onNodeWithText("Удалить").performClick()
        compose.waitUntil(5_000) { t.data().sessions.size == before - 1 }
    }

    /** "Все занятия" in the notification opens the app with the route's quick choices. */
    @Test fun notificationQuickPick() {
        val seed = seed()
        val vm = render(seed)
        vm.receiveSurfacePicker(dev.notai.tracker.android.SurfaceChoices.route(dev.notai.tracker.android.Surface.NOTIFICATION, seed.tracker.snapshot()))
        waitText("Переключиться на")
        waitText("Утренний английский")
        assertTrue("unlabelled records are not quick choices", compose.onAllNodesWithText("Без занятия").fetchSemanticsNodes().isEmpty())
        shot("10-quick-pick", vm)
    }

    /** Notification (collapsed, expanded, idle) and widget as RemoteViews inflated from the real resources. */
    @Test fun surfacesOutsideTheApp() {
        val running = seed()
        val idle = seed(running = false)
        for (night in listOf(false, true)) {
            org.robolectric.RuntimeEnvironment.setQualifiers(if (night) "+night" else "+notnight")
            val context = ApplicationProvider.getApplicationContext<android.content.Context>()
            val density = context.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            val backdrop = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL; setPadding(dp(12), dp(16), dp(12), dp(16))
                setBackgroundColor(if (night) 0xFF0B0F15.toInt() else 0xFFE4E7E1.toInt())
            }
            fun caption(text: String) = backdrop.addView(android.widget.TextView(context).apply {
                this.text = text; textSize = 12f; setTextColor(if (night) 0xFFA3ABB7.toInt() else 0xFF5E646E.toInt()); setPadding(dp(6), dp(10), 0, dp(6))
            })
            fun card(views: android.widget.RemoteViews, widget: Boolean = false) {
                val frame = android.widget.FrameLayout(context).apply {
                    if (!widget) {
                        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(if (night) 0xFF1E242C.toInt() else 0xFFFFFFFF.toInt()) }
                        setPadding(dp(16), dp(12), dp(16), dp(12))
                    }
                    addView(views.apply(context, this))
                }
                backdrop.addView(frame, android.widget.LinearLayout.LayoutParams(-1, if (widget) dp(76) else -2).apply { bottomMargin = dp(4) })
            }
            val (small, big) = dev.notai.tracker.android.SurfaceViews.notificationViews(context, running.tracker.view())
            val (_, idleBig) = dev.notai.tracker.android.SurfaceViews.notificationViews(context, idle.tracker.view())
            caption("Шторка, свёрнуто"); card(small)
            caption("Шторка, развёрнуто"); card(requireNotNull(big))
            caption("Шторка, учёт не идёт"); card(requireNotNull(idleBig))
            caption("Виджет 4×1"); card(dev.notai.tracker.android.SurfaceViews.widget(context, running.tracker.view(), true), widget = true)
            backdrop.measure(android.view.View.MeasureSpec.makeMeasureSpec(dp(400), android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED))
            backdrop.layout(0, 0, backdrop.measuredWidth, backdrop.measuredHeight)
            val bitmap = Bitmap.createBitmap(backdrop.measuredWidth, backdrop.measuredHeight, Bitmap.Config.ARGB_8888)
            backdrop.draw(Canvas(bitmap))
            save(bitmap, "11-surfaces-${if (night) "dark" else "light"}")
        }
        org.robolectric.RuntimeEnvironment.setQualifiers("+notnight")
    }

    /** The floating button over another app, idle and running, and the short card after STOP. */
    @Test fun floatingButtonAndAssignmentCard() {
        val seed = seed(running = false)
        val view = seed.tracker.view()
        val d = view.data
        val end = seed.clock.now().wall + 1
        val choices = view.choices().quick(HistoryWindow(end - 30L * 24 * 60 * 60 * 1000, end), 6).map { q ->
            val l = d.label(q.entry.selection)
            dev.notai.tracker.android.OverlayChoice(q, if (q.entry.kind == ChoiceKind.FAVORITE) q.entry.title else l.title, l.glyph, l.slot)
        }
        val idle = seed.tracker.snapshot()
        seed.tracker.start(idle.token); seed.clock.advance(12 * 60_000L)
        val running = seed.tracker.snapshot()
        val route = dev.notai.tracker.android.SurfacePickerRoute("preview", "OVERLAY", requireNotNull(running.timerTarget), "receipt")
        val card = dev.notai.tracker.android.OverlayAssignment(route, "14:05–15:29", choices)
        shotContent("12-floating") { theme ->
            NotAiTheme(theme, reducedMotion = true) {
                val c = NotAi.colors
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(c.paper)) {
                    androidx.compose.foundation.layout.Column(Modifier.padding(20.dp).padding(top = 40.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)) {
                        repeat(9) { i -> androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(if (i % 3 == 2) 0.6f else 1f).height(14.dp).background(c.sunk)) }
                    }
                    androidx.compose.foundation.layout.Column(Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(top = 140.dp, end = 8.dp),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(24.dp)) {
                        dev.notai.tracker.android.Puck(idle, false, {}, { _, _ -> }, {})
                        dev.notai.tracker.android.Puck(running, false, {}, { _, _ -> }, {})
                    }
                    androidx.compose.foundation.layout.Box(Modifier.align(androidx.compose.ui.Alignment.BottomCenter).padding(bottom = 56.dp)) {
                        dev.notai.tracker.android.AssignmentCard(card, {}, {})
                    }
                }
            }
        }
        waitText("Чем ты был занят?")
    }

    /** 150 % font on a narrow screen: the time and button labels shrink or wrap, never lose characters. */
    @Test fun largeFontTimeAndButtonsKeepTheirText() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                NotAiTheme(ThemeChoice.LIGHT, reducedMotion = true) {
                    // 320 dp screen minus the 20 dp gutters.
                    androidx.compose.foundation.layout.Column(Modifier.width(280.dp)) {
                        TimeField("Начало", at(today, 11, 2), zone, {}, Modifier.fillMaxWidth())
                        PillButton("Указать, когда закончил", {}, Modifier.fillMaxWidth())
                        ButtonPair { m -> PillButton("Сохранить", {}, m); PillButton("Восстановить", {}, m) }
                    }
                }
            }
        }
        compose.waitForIdle()
        fun overflow(text: String): Boolean {
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(text).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            // Centred single lines report a wider paragraph than their box; what matters is ellipsis or lost height.
            val r = results.first()
            return r.didOverflowHeight || (0 until r.lineCount).any { r.isLineEllipsized(it) }
        }
        org.junit.Assert.assertFalse("time keeps its digits", overflow("11:02"))
        org.junit.Assert.assertTrue("time field keeps its own height", compose.onNodeWithText("11:02").fetchSemanticsNode().boundsInRoot.height < 200f)
        org.junit.Assert.assertFalse("button keeps its label", overflow("Указать, когда закончил"))
        org.junit.Assert.assertFalse("paired buttons stack instead of cutting", overflow("Восстановить"))
    }

    @Test fun recovery() {
        val seed = seed()
        seed.clock.boot = "boot:after-restart" // another boot: the open interval has no trusted end
        val vm = render(seed)
        waitText("Конец неизвестен")
        shot("07-recovery", vm)
    }
}
