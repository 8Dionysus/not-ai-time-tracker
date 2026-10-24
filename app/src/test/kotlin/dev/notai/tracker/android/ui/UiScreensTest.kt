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
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
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
@Config(qualifiers = "ru-rRU-w400dp-h860dp-xhdpi")
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
        override val autoBackup: AutoBackupBackend = FakeAutoBackup(tracker)
        override val historyTransfer = HistoryTransfer(tracker, MemoryRollback())
        /** One timed meeting today 16:00–16:45 and an all-day event that must not become an interval. */
        override val calendar = dev.notai.tracker.android.CalendarOccurrences({ true }, { _: android.net.Uri, cols: Array<String>, _: String, _: Array<String>, _: String? ->
            val day = LocalDate.of(2026, 10, 13)
            fun ms(h: Int, m: Int) = day.atTime(h, m).atZone(ZoneId.of("America/Mexico_City")).toInstant().toEpochMilli()
            android.database.MatrixCursor(cols).apply {
                addRow(arrayOf<Any?>(7L, ms(0, 0), ms(23, 59), "Отпуск коллеги", 1))
                addRow(arrayOf<Any?>(9L, ms(16, 0), ms(16, 45), "Созвон с командой", 0))
            }
        })
    }
    /** A folder in a cloud app with three verified copies; actions only change the in-memory state. */
    private class FakeAutoBackup(private val tracker: Tracker) : AutoBackupBackend {
        private val tree = "content://com.example.cloud/tree/not-ai"
        private fun version(i: Int, at: Long) = dev.notai.tracker.android.ExternalBackupVersion("op$i", tree, "$tree/document/$i",
            dev.notai.tracker.android.StagedBackup("op$i", dev.notai.tracker.android.BackupRevision("d", i.toLong()), at, 180_000L + i * 4_000L, "0".repeat(64)), at + 40_000)
        var state: dev.notai.tracker.android.AutomaticBackupState = run {
            val now = tracker.clock.now().wall
            val v = listOf(version(1, now - 2 * 86_400_000L), version(2, now - 86_400_000L), version(3, now - 3_600_000L))
            dev.notai.tracker.android.AutomaticBackupState(enabled = true, destination = tree, versions = v, lastSuccess = v.last())
        }
        override fun status() = state
        override fun folderName(tree: String) = "Облако · not-ai"
        override fun selectionIntent() = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT_TREE)
        override fun select(uri: android.net.Uri, returnedFlags: Int, policy: dev.notai.tracker.android.BackupPolicy) = state
        override fun enable(tree: String, policy: dev.notai.tracker.android.BackupPolicy) = state.copy(enabled = true, policy = policy).also { state = it }
        override fun disable() = state.copy(enabled = false).also { state = it }
        override fun retry() {}
        override fun requestBackup() { state = state.copy(requested = true) }
        override fun abandonPending(operationId: String) {}
        override fun prepareRestore(operationId: String): RestorePreview = error("not in screen tests")
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
        val reading = card("Чтение", "Книга", "книга")
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
        vm.setCorrectionStepMinutes(5); compose.waitForIdle()
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
        waitText("Работа")
        shot("05-overview", vm)
        compose.onAllNodesWithText("Работа").onFirst().performClick()
        waitText("За 7 дней")
        shot("34-overview-area", vm)
    }

    @Test fun settings() {
        val vm = render(seed())
        compose.onNodeWithText("Настройки").performClick()
        waitText("Разделы")
        waitText("Автокопия ")
        shot("06-settings", vm)
    }

    /** Each section is its own page; back returns to the short root. */
    @Config(qualifiers = "w400dp-h1300dp-xhdpi")
    @Test fun settingsFullPage() {
        val vm = render(seed())
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Таймер вне приложения").performClick()
        waitText("Плавающая кнопка")
        shot("06b-settings-surfaces", vm)
        compose.onNodeWithContentDescription("Назад к настройкам").performClick()
        waitText("Разделы")
        compose.onNodeWithText("Ещё").performClick()
        waitText("Техническая оболочка")
        shot("06c-settings-more", vm)
    }

    @Test fun reportDayBoundaryIsHiddenUntilRequestedAndCancelDoesNotWrite() {
        val s = seed()
        val vm = render(s)
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Ещё").performClick()
        waitText("Часовой пояс")
        shot("feedback-settings-more",vm)
        assertTrue(compose.onAllNodesWithText("Граница отчётного дня").fetchSemanticsNodes().isEmpty())
        val before = s.tracker.data()
        compose.onNodeWithText("Дополнительно").performClick()
        compose.onNodeWithText("Граница отчётного дня").performClick()
        waitText("Сохранить")
        compose.onNodeWithContentDescription("Граница дня на час позже").performClick()
        compose.onNodeWithContentDescription("Закрыть").performClick()
        compose.waitForIdle()
        assertTrue("Editing and cancelling must not mutate the Dataset", before == s.tracker.data())
    }

    @Test fun reportDayEditorRetainsExistingMinuteWhenOpened() {
        var writes = 0
        compose.setContent { NotAiTheme { DayBoundarySheet(275, { writes++ }, {}) } }
        waitText("4:35")
        compose.waitForIdle()
        assertTrue("Opening a 4:35 boundary must not silently round it to 4:00", writes == 0)
    }

    /** A Toggl-style export: columns are suggested from headers, the time layout from the first row, then preview and append. */
    @Config(qualifiers = "w400dp-h1300dp-xhdpi")
    @Test fun csvImport() {
        val seed = seed()
        val vm = render(seed)
        val before = seed.tracker.data().sessions.size
        listOf("account:a","account:b").forEachIndexed { index,source ->
            val day = if(index==0) "2026-10-01" else "2026-10-02"
            val file = java.io.File.createTempFile("toggl", ".csv").apply {
                writeText("ID,Project,Client,Description,Start date,Start time,End date,End time,Duration\n" +
                    "same-id-1,Английский,Школа,Урок,$day,09:00:00,$day,09:45:00,00:45:00\n" +
                    "same-id-2,Чтение,,,$day,21:00:00,$day,21:30:00,00:30:00\n")
            }
            assertTrue("Synthetic CSV must be inspectable",HistoryFiles.inspect(file.readBytes()).format == ImportFormat.DELIMITED_TEXT)
            vm.openCsv(android.net.Uri.fromFile(file));waitText("История из CSV")
            compose.onNodeWithContentDescription("Источник CSV (сервис и аккаунт)").performTextReplacement(source)
            if(index==0) shot("31-csv-columns",vm)
            compose.onNodeWithText("Дальше").performClick();waitText("Добавится 2 записи")
            if(index==0) shot("32-csv-preview",vm)
            compose.onNodeWithText("Добавить 2 записи").performClick()
            compose.waitUntil(10_000) { seed.tracker.data().sessions.size == before + (index+1)*2 && vm.csvImport.value == null }
        }
    }
    @Test fun csvBackDuringSuspendedPreviewDoesNotLeaveContinueBusy() {
        val s = seed();UiBackends.open = { TestBackend(s.tracker,s.catalog) }
        val vm = TrackerViewModel(ApplicationProvider.getApplicationContext())
        val file = CsvImportUi(HistoryFiles.inspect("Activity,Start,End\nStudy,2026-10-01T09:00:00Z,2026-10-01T10:00:00Z\n".toByteArray()),"synthetic.csv")
        val entered = java.util.concurrent.atomic.AtomicInteger()
        val cancelled = java.util.concurrent.atomic.AtomicInteger()
        val release = CompletableDeferred<Result<HistoryPreview>>()
        compose.setContent { NotAiTheme { CsvImportSheet(vm,file,ZoneId.of("UTC"),{}, {},previewCsv = { _,_ ->
            entered.incrementAndGet()
            try { release.await() } finally { if(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.isCancelled == true) cancelled.incrementAndGet() }
        }) } }
        compose.onNodeWithContentDescription("Источник CSV (сервис и аккаунт)").performTextReplacement("account:cancel")
        compose.onNodeWithText("Дальше").performClick();waitText("Занятия и проверка")
        compose.mainClock.advanceTimeBy(500);compose.waitUntil(10000) { entered.get()>=1 }
        val firstCalls = entered.get()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.waitUntil(10000) { cancelled.get()>=1 }
        compose.onNodeWithText("Дальше").assertIsEnabled().performClick()
        waitText("Занятия и проверка");compose.mainClock.advanceTimeBy(500)
        compose.waitUntil(10000) { entered.get()>firstCalls }
        release.complete(Result.failure(java.io.IOException("synthetic preview failure")))
    }

    @Config(qualifiers = "w400dp-h1300dp-xhdpi")
    @Test fun backupPage() {
        val vm = render(seed())
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Резервная копия").performClick()
        waitText("Копии в папке")
        waitText("Облако · not-ai")
        shot("30-backup", vm)
        compose.onNodeWithText("Сохранить сейчас").performClick()
        compose.waitUntil(5_000) { vm.autoBackup.value?.state?.requested == true }
    }

    /** A new install: no history, no favourites. The first screen a person sees. */
    @Test fun firstLaunch() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val catalog = ReadyCatalog.read { app.assets.open("catalog/$it") }
        val clock = MutableClock(at(today, 9, 41), 1_000_000L)
        val tracker = Tracker(MemoryStore(newInstallData(catalog, zone.id)), clock)
        val vm = render(Seed(tracker, clock, catalog))
        waitText("Чем ты занимаешься чаще всего?")
        shot("13-first-launch", vm)
        assertTrue("nothing older to show on a new install", compose.onAllNodesWithText("Показать раньше").fetchSemanticsNodes().isEmpty())
        listOf("Работа", "Язык", "Чтение").forEach { compose.onNodeWithText(it).performClick() }
        compose.onNodeWithText("Готово · 3").performClick()
        compose.waitUntil(5_000) { tracker.data().favorites.size == 3 }
        waitText("Начать сразу")
        assertTrue("the card gives way to the quick-start chips", compose.onAllNodesWithText("Чем ты занимаешься чаще всего?").fetchSemanticsNodes().isEmpty())
        shot("15-first-picked", vm)
        compose.onNodeWithText("Обзор").performClick()
        waitText("Обзор соберётся сам")
        shot("14-first-overview", vm)
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
        waitText("Запись перемещена в «Удалённое»")
        // The commit/notice can arrive before Home's new projection. Inject the next gesture
        // only after the removed row has left composition and the remaining rows have settled.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("9:10–11:02", substring = true).fetchSemanticsNodes().isEmpty() }
        compose.waitForIdle()
        compose.onAllNodesWithText("8:30–8:55", substring = true).onFirst().performTouchInput { swipeLeft() }
        compose.waitUntil(5_000) { seed.tracker.data().sessions.size == before - 2 }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("8:30–8:55", substring = true).fetchSemanticsNodes().isEmpty() }
    }

    /** A new record can take its times from a calendar event; all-day events are not offered. */
    @Test fun calendarFill() {
        org.robolectric.Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>()).grantPermissions(android.Manifest.permission.READ_CALENDAR)
        val seed = seed()
        val vm = render(seed)
        compose.onNodeWithText("Добавить запись задним числом").performScrollTo().performClick()
        waitText("Из календаря")
        compose.onNodeWithText("Из календаря").performClick()
        waitText("Созвон с командой")
        assertTrue(compose.onAllNodesWithText("Отпуск коллеги").fetchSemanticsNodes().isEmpty())
        shot("37-calendar", vm)
        compose.onNodeWithText("Созвон с командой").performClick()
        waitText("16:45")
    }

    /** A saved report follows its period: create «Прошлая неделя» for everything, open it, see the total. */
    @Config(qualifiers = "w400dp-h1300dp-xhdpi")
    @Test fun savedReport() {
        val seed = seed()
        val vm = render(seed)
        compose.onNodeWithText("Обзор").performClick()
        waitText("Свой отчёт: занятие и период")
        compose.onNodeWithText("Свой отчёт: занятие и период").performClick()
        waitText("Что считать")
        compose.onNodeWithText("Прошлая неделя").performClick()
        shot("35-report-editor", vm)
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(5_000) { seed.tracker.data().savedReports.size == 1 }
        waitText("Все занятия, прошлая неделя")
        compose.onNodeWithText("Все занятия, прошлая неделя").performClick()
        waitText("Изменить")
        shot("36-report", vm)
    }

    @Test fun feedbackBatchDeletionConfirmationCommitsAndUndoRestoresTheOriginalRecords() {
        val s = seed(false);val vm = render(s)
        val before = s.tracker.data();val ids = before.sessions.take(2).map { it.id }.toSet()
        compose.runOnIdle { ids.forEach(vm::togglePicked) }
        waitText("Удалить · 2")
        compose.onNodeWithText("Удалить · 2").performClick()
        waitText("Удалить записи (2)?")
        shot("feedback-batch-delete",vm)
        compose.onNodeWithText("Удалить").performClick()
        compose.waitUntil(5_000) { s.tracker.data().sessions.none { it.id in ids } }
        waitText("Перемещено в «Удалённое»: 2")
        compose.onNodeWithText("Отмена").performClick()
        compose.waitUntil(5_000) { s.tracker.data().sessions.size == before.sessions.size }
        assertTrue(before.sessions == s.tracker.data().sessions)
        assertTrue(before.segments == s.tracker.data().segments)
    }

    /** Actual Compose → VM → Room, including re-entry with the identical day/session lazy key. */
    @Test fun recoveryRoomSwipeUndoDoesNotRedismissAndRejectsScrollingGestures() {
        val source = seed(false)
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val dbName = "ui-recovery-${newId()}.db"
        val db = androidx.room.Room.databaseBuilder(app,dev.notai.tracker.android.TrackerDatabase::class.java,dbName).allowMainThreadQueries().build()
        val t = Tracker(dev.notai.tracker.android.RoomStore(db,{ source.tracker.data() }),source.clock)
        val s = Seed(t,source.clock,source.catalog);val vm = render(s)
        try {
            val original = t.data()
            val record = original.sessions.single { session -> original.segments.any { it.sessionId == session.id && it.start == at(today,9,10) } }
            val tag = "history-record:${record.id}"
            fun row() = compose.onNodeWithTag(tag)
            fun showRow() {
                compose.waitUntil(5_000) { vm.home.value?.days?.any { day -> day.records.any { it.sessionId == record.id } } == true }
                compose.onNode(hasScrollAction()).performScrollToKey("$today:${record.id}")
                row().performScrollTo()
            }
            row().performScrollTo()
            val scrollBefore = compose.onNode(hasScrollAction()).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            row().performTouchInput { swipe(Offset(width * 0.8f,centerY),Offset(width * 0.7f,centerY-250f),400) }
            val scrollAfter = compose.onNode(hasScrollAction()).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue("Vertical scrolling must still move the history",scrollAfter > scrollBefore)
            assertTrue(original == t.data())
            row().performScrollTo()
            row().performTouchInput { swipe(Offset(width * 0.9f,centerY),Offset(width * 0.2f,centerY-width*0.35f),400) }
            assertTrue(original == t.data())
            row().performScrollTo()
            row().performTouchInput { swipe(Offset(width * 0.8f,centerY),Offset(width * 0.5f,centerY),300) }
            assertTrue(original == t.data())
            repeat(2) {
                row().performScrollTo();row().performTouchInput { swipeLeft() }
                compose.waitUntil(5_000) { t.data().sessions.none { it.id == record.id } }
                waitText("Запись перемещена в «Удалённое»")
                if(it == 1) t.saveCard(t.snapshot().token,Card("later-ui-edit","Keep later independent edit"))
                compose.onNodeWithText("Отмена").performClick()
                compose.waitUntil(5_000) { t.data().sessions.any { it.id == record.id } }
                showRow();compose.mainClock.advanceTimeBy(3_000);compose.waitForIdle()
                assertTrue("Same record must remain restored after its previous dismiss lifetime",t.data().sessions.single { it.id == record.id } == record)
                assertTrue(t.data().deletedRecords.isEmpty())
                assertTrue(original.segments == t.data().segments)
                if(it == 1) assertTrue(t.data().cards.any { card -> card.id == "later-ui-edit" })
            }
            shot("recovery-restored-history",vm)
            row().performScrollTo();row().performTouchInput { swipeLeft() }
            compose.waitUntil(5_000) { t.data().deletedRecords.size == 1 }
            compose.onNodeWithText("Настройки").performClick()
            compose.onNodeWithText("Удалённое").performScrollTo().performClick()
            waitText("Автоматической очистки нет.")
            shot("recovery-deleted",vm)
            compose.onNodeWithText("Восстановить").performScrollTo().performClick()
            compose.waitUntil(5_000) { t.data().deletedRecords.isEmpty() }
            assertTrue(original.sessions == t.data().sessions);assertTrue(original.segments == t.data().segments)
        } finally { vm.viewModelScope.cancel();db.close();app.deleteDatabase(dbName) }
    }

    @Test fun recoveryDeletedPaginationLocalizesAndRestoresBeyondTheFirstPage() {
        val source = seed(false)
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val dbName = "ui-deleted-pages-${newId()}.db"
        val db = androidx.room.Room.databaseBuilder(app,dev.notai.tracker.android.TrackerDatabase::class.java,dbName).allowMainThreadQueries().build()
        val t = Tracker(dev.notai.tracker.android.RoomStore(db,{ source.tracker.data() }),source.clock)
        val ids = (0..50).map { i ->
            val day = today.minusDays(10L+i)
            t.manual(t.snapshot().token,at(day,10,0),at(day,10,20),Classification(),note = "Deleted page fixture $i")
            t.data().sessions.last().id
        }
        val before = t.data()
        t.deleteSessions(t.snapshot().token,ids)
        val archived = t.data().deletedRecords
        val target = archived.sortedByDescending { it.deletedAt }[50]
        val vm = render(Seed(t,source.clock,source.catalog))
        try {
            compose.runOnIdle { vm.setLanguage("en") }
            waitText("Settings");compose.onNodeWithText("Settings").performClick()
            compose.onNodeWithText("Deleted").performScrollTo().performClick()
            waitText("Show more")
            assertTrue("The 51st record must not be composed before pagination",compose.onAllNodesWithText(target.session.value.note!!).fetchSemanticsNodes().isEmpty())
            compose.onNodeWithText("Show more").performScrollTo()
            compose.runOnIdle { vm.setLanguage("ja") }
            waitText("さらに表示")
            assertTrue(compose.onAllNodesWithText("Show more").fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithText("Показать ещё").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithText("さらに表示").performScrollTo().performClick()
            compose.onNodeWithText(target.session.value.note!!).performScrollTo()
            compose.onNodeWithTag("restore-deleted:${target.sessionId}").performScrollTo().performClick()
            compose.waitUntil(5_000) { t.data().deletedRecords.size == 50 }
            assertTrue(t.data().deletedRecords == archived.filterNot { it.sessionId == target.sessionId })
            assertTrue(t.data().sessions.single { it.id == target.sessionId } == before.sessions.single { it.id == target.sessionId })
            assertTrue(t.data().segments.filter { it.sessionId == target.sessionId } == before.segments.filter { it.sessionId == target.sessionId })
            assertTrue(compose.onAllNodesWithText("さらに表示").fetchSemanticsNodes().isEmpty())
        } finally { vm.viewModelScope.cancel();db.close();app.deleteDatabase(dbName) }
    }

    @Test fun recoveryLanguagePickerChangesVisibleControlsWithoutChangingRecords() {
        val s = seed(false);val vm = render(s);val before = s.tracker.data()
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Язык").performScrollTo().performClick()
        waitText("English");compose.onNodeWithText("English").performClick()
        waitText("Settings");compose.onNodeWithText("Deleted").performScrollTo()
        shotAs("recovery-language-en",vm,ThemeChoice.DARK)
        assertTrue(before == s.tracker.data())
        compose.onNodeWithText("Language").performScrollTo().performClick()
        compose.onNodeWithText("日本語").performScrollTo().performClick()
        waitText("設定");compose.onNodeWithText("言語").performScrollTo()
        shotAs("recovery-language-ja",vm,ThemeChoice.DARK)
        compose.onNodeWithText("言語").performClick()
        compose.onNodeWithText("システムの言語").performClick()
        waitText("Настройки")
        assertTrue(vm.language.value.preference.explicitTag == null)
        assertTrue(before == s.tracker.data())
    }
    @Test fun recoveryNeonAccentsRemainDistinctWithReducedMotion() {
        val s = seed();val before = s.tracker.data();val vm = render(s,reducedMotion = true,initialTheme = ThemeChoice.NEON)
        waitText("1:24")
        val images = mutableListOf<Bitmap>()
        for(accent in Accent.entries) {
            vm.setAppearance { it.copy(accent = accent) }
            shotAs("recovery-neon-running-${accent.name.lowercase()}",vm,ThemeChoice.NEON)
            val views = windows();val first = views.first()
            val current = Bitmap.createBitmap(first.width,first.height,Bitmap.Config.ARGB_8888)
            val canvas = Canvas(current);views.forEach { drawWindow(canvas,first,it) };images += current
        }
        assertTrue("All four timer accents must render different pixels",images.indices.all { i -> images.indices.all { j -> i == j || !images[i].sameAs(images[j]) } })
        assertTrue(before == s.tracker.data())
    }
    @Test fun recoveryChangingLanguageKeepsThePendingDeletionUndoAction() {
        val s = seed(false);val vm = render(s);val before = s.tracker.data()
        val id = before.sessions.first().id
        compose.runOnIdle { vm.applyDeleteSession(s.tracker.previewDeleteSession(id)) }
        compose.waitUntil(5_000) { s.tracker.data().sessions.none { it.id == id } }
        waitText("Запись перемещена в «Удалённое»")
        val deleted = s.tracker.data()
        compose.runOnIdle { vm.setLanguage("en") }
        waitText("Settings");assertTrue(deleted == s.tracker.data())
        // The in-flight receipt keeps its original label and remains functional across locale recomposition.
        compose.onNodeWithText("Отмена").performClick()
        compose.waitUntil(5_000) { s.tracker.data().sessions.any { it.id == id } }
        assertTrue(before.sessions == s.tracker.data().sessions);assertTrue(before.segments == s.tracker.data().segments)
        compose.runOnIdle { vm.setLanguage(null) }
        waitText("Настройки") // Drain UI recomposition/Main dispatch as well as the preference IO.
        assertTrue(vm.language.value.preference.explicitTag == null)
    }

    /** «Выбрать несколько» from the menu, a second tap adds another, and one note goes to both after a confirmation. */
    @Test fun batchNote() {
        val seed = seed()
        val vm = render(seed)
        waitText("8:30–8:55")
        compose.onAllNodesWithText("9:10–11:02", substring = true).onFirst().performTouchInput { longClick() }
        waitText("Выбрать несколько")
        compose.onNodeWithText("Выбрать несколько").performClick()
        waitText("Выбрано: 1")
        compose.onAllNodesWithText("8:30–8:55", substring = true).onFirst().performClick()
        waitText("Выбрано: 2")
        shot("33-batch", vm)
        compose.onNodeWithText("Заметка").performClick()
        waitText("Заметка для выбранных")
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("проект Альфа")
        compose.onNodeWithText("Заменить у всех").performClick()
        compose.onNodeWithText("Дальше").performClick()
        waitText("Изменить 2 записи?")
        compose.onNodeWithText("Изменить").performClick()
        compose.waitUntil(5_000) { seed.tracker.data().sessions.count { it.note == "проект Альфа" } == 2 }
        waitText("Изменено 2 записи")
        assertTrue(vm.picked.value.isEmpty())
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
        waitText("15:29–15:54")
        val before = t.data().sessions.size
        compose.onAllNodesWithText("15:29–15:54", substring = true, useUnmergedTree = true).onFirst().performClick()
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

    private fun plan(seed: Seed, card: String, time: String?) {
        val t = seed.tracker
        t.scheduleCommand(ScheduleCommand(newId(), t.snapshot().token, ScheduleAction.CREATE, newId(),
            rule = ScheduleRule(t.selection(t.data().cards.first { it.name == card }.id), today.toString(), setOf(today.dayOfWeek.value), localTime = time),
            deviceZone = ZoneId.systemDefault().id))
    }

    /** Today's plan while tracking, and the folded gaps of the day opened. */
    @Test fun planAndGapsWhileRunning() {
        val seed = seed()
        plan(seed, "Тренировка", "19:00"); plan(seed, "Чтение", null)
        val vm = render(seed)
        waitText("Дальше по плану")
        waitText("Начал раньше")
        compose.onNodeWithText("2 промежутка без записи").performClick()
        waitText("не записано")
        shot("16-plan-gaps", vm)
        // A gap opens the ordinary form on exactly that stretch and goes through the core's draft.
        val before = seed.tracker.data().sessions.size
        compose.onAllNodesWithText("13:40–14:05", substring = true).onFirst().performClick()
        waitText("Записать промежуток")
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(5_000) { seed.tracker.data().sessions.size == before + 1 }
        val added = seed.tracker.data().segments.single { it.start == at(today, 13, 40) }
        org.junit.Assert.assertEquals(at(today, 14, 5), added.end)
    }

    @Test fun planWhileIdle() {
        val seed = seed(running = false)
        plan(seed, "Тренировка", "19:00"); plan(seed, "Английский", null)
        val vm = render(seed)
        waitText("По плану на сегодня")
        shot("17-plan-idle", vm)
        compose.onNodeWithContentDescription("Тренировка, 19:00").performClick()
        compose.waitUntil(5_000) { seed.tracker.data().schedule.links.size == 1 }
        assertTrue("an ordinary running record", seed.tracker.snapshot().mode == Mode.RUNNING)
    }

    /** "Начал раньше": the core proposes the stretch from the previous record's end; Save adds it, marked as stated. */
    @Test fun startEarlier() {
        val seed = seed()
        val vm = render(seed)
        compose.onNodeWithText("Начал раньше").performClick()
        waitText("С конца прошлой записи")
        waitText("Добавится 25 мин")
        shot("18-start-earlier", vm)
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(5_000) { seed.tracker.data().segments.any { it.start == at(today, 13, 40) && it.manualConfirmation != null } }
    }

    @Test fun goalEditor() {
        val seed = seed()
        val t = seed.tracker
        t.saveGoal(t.snapshot().token, Goal(name = "Тренировка", metric = GoalMetric.DISTINCT_RECORDED_DAYS, target = 3, period = GoalPeriod.WEEK,
            zone = zone.id, filter = Filter(cardId = t.data().cards.first { it.name == "Тренировка" }.id)))
        val vm = render(seed)
        compose.onNodeWithText("Обзор").performClick()
        waitText("от 3 дней в неделю")
        waitText("с записями")
        compose.onAllNodesWithText("Английский, от 30 мин в день", substring = true).onFirst().performClick()
        waitText("Когда применить")
        shot("19-goal-edit", vm)
        compose.onNodeWithContentDescription("Больше").performClick()
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(5_000) { t.data().goals.first { it.name == "Английский" }.revisions.isNotEmpty() }
        val g = t.data().goals.first { it.name == "Английский" }
        org.junit.Assert.assertEquals("next period keeps today", 30 * 60_000L, t.goalProgress(g.id).goal.target)
        org.junit.Assert.assertEquals(45 * 60_000L, g.target)
    }

    @Test fun shareSummary() {
        val vm = render(seed())
        compose.onNodeWithText("Обзор").performClick()
        waitText("По областям")
        compose.onNodeWithContentDescription("Поделиться итогом").performClick()
        waitText("Добавить заметки")
        // The preview is produced off the main thread; let the looper deliver it.
        repeat(10) { compose.waitForIdle(); org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(150) }
        waitText("Всего")
        assertTrue("notes stay out until asked", compose.onAllNodesWithText("конспект", substring = true).fetchSemanticsNodes().isEmpty())
        shot("20-share", vm)
    }

    @Config(qualifiers = "w400dp-h1960dp-xhdpi")
    @Test fun planEditor() {
        val seed = seed()
        plan(seed, "Английский", "18:00")
        val vm = render(seed)
        compose.onNodeWithText("Настройки").performClick()
        compose.onNodeWithText("Занятия и план").performClick()
        waitText("Добавить ещё")
        compose.onNodeWithText("Добавить ещё").performClick()
        waitText("По дням недели")
        shot("21-plan-editor", vm)
    }

    /** One theme only, for the extra themes (black, neon). */
    private fun shotAs(name: String, vm: TrackerViewModel, theme: ThemeChoice) {
        vm.setTheme(theme)
        repeat(3) { compose.waitForIdle(); org.robolectric.shadows.ShadowLooper.idleMainLooper(); Thread.sleep(150) }
        if (System.getProperty("tracker.screenshots") != "true") return
        val views = windows(); val first = views.first()
        val base = Bitmap.createBitmap(first.width, first.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(base); views.forEach { v -> drawWindow(canvas, first, v) }
        save(base, name)
    }

    /** A record with pauses is one line; its mark unfolds the segments and the pauses between them. */
    @Test fun recordWithPauses() {
        val seed = seed(running = false)
        val t = seed.tracker
        t.start(t.snapshot().token, t.selection(t.data().cards.first { it.name == "Чтение" }.id)); seed.clock.advance(18 * 60_000L)
        t.pause(t.snapshot().token); seed.clock.advance(7 * 60_000L)
        t.resume(t.snapshot().token); seed.clock.advance(9 * 60_000L)
        t.pause(t.snapshot().token); seed.clock.advance(5 * 60_000L)
        t.resume(t.snapshot().token); seed.clock.advance(22 * 60_000L)
        t.stop(t.snapshot().token)
        val vm = render(seed)
        waitText("2 паузы")
        assertTrue("one line per record", compose.onAllNodesWithText("15:29–16:30", substring = true).fetchSemanticsNodes().size == 1)
        compose.onNodeWithText("2 паузы").performClick()
        waitText("пауза")
        shot("22-record-pauses", vm)
    }

    @Test fun favoritesAndEditor() {
        val seed = seed()
        val t = seed.tracker
        t.addFavorite(t.snapshot().token, Favorite(label = "Тренировка", selection = t.selection(t.data().cards.first { it.name == "Тренировка" }.id)))
        val vm = render(seed)
        vm.openFavorites(true)
        waitText("Добавить в избранное")
        shot("23-favorites", vm)
        vm.openFavorites(false)
        vm.openEditor(EditTarget.Card(t.data().cards.first { it.name == "Философия" }.id))
        waitText("Своё занятие")
        shot("24-activity-editor", vm)
    }
    @Test fun draggingFavoriteCommitsTheDisplayedOrderAndKeepsItWhenReopened() {
        val s = seed(running = false);val tracker = s.tracker
        listOf("A drag" to "Работа","B drag" to "Тренировка","C drag" to "Философия").forEach { (label,name) ->
            tracker.addFavorite(tracker.snapshot().token,Favorite(label=label,selection=tracker.selection(tracker.data().cards.first { it.name==name }.id)))
        }
        val vm = render(s);vm.openFavorites(true);waitText("Добавить в избранное")
        val before = vm.favorites(vm.view.value).map { it.id };val index = vm.favorites(vm.view.value).indexOfFirst { it.title=="B drag" }
        val expected = before.toMutableList().apply { add(index-1,removeAt(index)) }
        compose.onNode(hasText("B drag") and hasAnyAncestor(isDialog())).performTouchInput {
            down(center);advanceEventTime(700);moveBy(androidx.compose.ui.geometry.Offset(0f,-140f));advanceEventTime(32);up()
        }
        compose.waitUntil(10000) { vm.favorites(vm.view.value).map { it.id } == expected }
        vm.openFavorites(false);compose.waitForIdle();vm.openFavorites(true);waitText("Добавить в избранное")
        val first = compose.onNode(hasText("B drag") and hasAnyAncestor(isDialog())).fetchSemanticsNode().boundsInRoot.top
        val second = compose.onNode(hasText("A drag") and hasAnyAncestor(isDialog())).fetchSemanticsNode().boundsInRoot.top
        assertTrue("Saved drag order must survive reopening",first < second)
    }

    @Test fun focusEditorFromLongPress() {
        val vm = render(seed(running = false))
        compose.onNodeWithText("Фокус: 25 мин работы").performTouchInput { longClick() }
        waitText("Перерыв")
        shot("25-focus-editor", vm)
    }

    @Test fun blackAndNeonThemes() {
        val running = seed()
        val vm = render(running)
        waitText("1:24")
        shotAs("26-neon-running", vm, ThemeChoice.NEON)
        shotAs("27-amoled-running", vm, ThemeChoice.AMOLED)
        vm.setAppearance { it.copy(accent = Accent.TEAL) }
        shotAs("28-teal-light", vm, ThemeChoice.LIGHT)
        vm.setAppearance { Appearance() }
    }

    @Test fun neonIdle() {
        val vm = render(seed(running = false))
        waitText("Начать")
        shotAs("29-neon-idle", vm, ThemeChoice.NEON)
    }

    /** Each existing accent is available in Neon and changes through the actual settings control. */
    @Test fun neonAccentSettings() {
        val s = seed()
        val vm = render(s, initialTheme = ThemeChoice.NEON)
        val before = s.tracker.data()
        compose.onNodeWithText("Настройки").performClick()
        waitText("Акцент")
        for (accent in Accent.entries) {
            val choice = compose.onNode(androidx.compose.ui.test.SemanticsMatcher("Accent: ${accent.title}") { node ->
                val click = androidx.compose.ui.semantics.SemanticsActions.OnClick
                click in node.config && node.config[click].label == accent.title
            }, useUnmergedTree = true)
            choice.performClick()
            compose.waitUntil(5000) { vm.appearance.value.accent == accent }
            choice.assertIsSelected()
            shotAs("33-neon-settings-${accent.name.lowercase()}", vm, ThemeChoice.NEON)
        }
        vm.setAppearance { it.copy(accent = Accent.AMBER) }
        compose.onNodeWithText("Таймер").performClick()
        waitText("1:24")
        shotAs("34-neon-running-amber", vm, ThemeChoice.NEON)
        assertTrue("Appearance changes must not change records or reporting settings", before == s.tracker.data())
    }

    @Test fun recovery() {
        val seed = seed()
        seed.clock.boot = "boot:after-restart" // another boot: the open interval has no trusted end
        val vm = render(seed)
        waitText("Конец неизвестен")
        shot("07-recovery", vm)
    }
}
