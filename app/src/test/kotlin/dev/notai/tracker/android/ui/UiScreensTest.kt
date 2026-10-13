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
import androidx.compose.ui.test.performClick
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

    private fun render(seed: Seed): TrackerViewModel {
        UiBackends.open = { TestBackend(seed.tracker, seed.catalog) }
        val vm = TrackerViewModel(ApplicationProvider.getApplicationContext())
        compose.setContent { TrackerApp(vm, openTechnical = {}, reducedMotion = true, onActionReady = { actionReady++ }) }
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
            views.forEach { v ->
                canvas.save(); canvas.translate(0f, (first.height - v.height).toFloat()); v.draw(canvas); canvas.restore()
            }
            val dir = File(System.getProperty("tracker.screenshotDir")!!).apply { mkdirs() }
            FileOutputStream(File(dir, "$name-${theme.name.lowercase()}.png")).use { base.compress(Bitmap.CompressFormat.PNG, 100, it) }
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

    @Test fun overview() {
        val vm = render(seed())
        compose.onNodeWithText("Обзор").performClick()
        waitText("По областям")
        waitText("Работа и проекты")
        shot("05-overview", vm)
    }

    @Test fun data() {
        val vm = render(seed())
        compose.onNodeWithText("Данные").performClick()
        waitText("Резервная копия")
        shot("06-data", vm)
    }

    @Test fun recovery() {
        val seed = seed()
        seed.clock.boot = "boot:after-restart" // another boot: the open interval has no trusted end
        val vm = render(seed)
        waitText("Конец неизвестен")
        shot("07-recovery", vm)
    }
}
