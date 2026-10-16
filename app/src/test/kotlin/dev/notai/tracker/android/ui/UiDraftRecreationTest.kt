package dev.notai.tracker.android.ui

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import dev.notai.tracker.*
import dev.notai.tracker.android.*
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** MainActivity, real form/picker callbacks, actual framework recreation; never a device claim. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[36],qualifiers="w400dp-h860dp-xhdpi")
class UiDraftRecreationTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val oldOpen=UiBackends.open
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var t: Tracker
    private lateinit var vm: TrackerViewModel
    private fun at(value:String)=Instant.parse("2026-10-16T${value}:00Z").toEpochMilli()
    private class Store:StateStore {
        private var value=Dataset(settings=Settings("UTC"));override val changes=MutableStateFlow(value)
        @Synchronized override fun read()=value
        @Synchronized override fun <T> transaction(block:(Dataset)->Pair<Dataset,T>):T {val(n,r)=block(value);validate(n);value=n;changes.value=n;return r}
    }
    private fun prepareBackend() {
        t=Tracker(Store(),FakeClock(at("14:00")))
        val selected=Card("p2-form-choice","Draft selected activity");t.saveCard(t.snapshot().token,selected)
        t.manual(t.snapshot().token,at("12:00"),at("12:30"),Classification(name="Original activity"),"Original correction note")
        val backend=object:UiBackend {
            override val tracker=t;override val backups=Backups(t,MemoryRollback());override val focusDue=FocusDueSignals();override val runtime:TrackerRuntime?=null
            override fun prepareCatalog():CatalogPreview=error("Unused fixture")
            override fun applyCatalog(preview:CatalogPreview):Tracker.Change=error("Unused fixture")
            override fun submitShortcut(action:SurfaceAction)=CompletableDeferred<Result<Tracker.Change>>(Result.failure(IllegalStateException("Unused fixture")))
        }
        UiBackends.open={backend}
    }
    @Before fun setup() {
        prepareBackend()
        controller=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        vm=ViewModelProvider(controller.get())[TrackerViewModel::class.java]
        compose.waitUntil(10000) { vm.home.value!=null && vm.view.value!=null }
        compose.waitForIdle()
    }
    @After fun close() { if(::controller.isInitialized)controller.pause().stop().destroy();UiBackends.open=oldOpen }
    private fun recreate() {
        controller.recreate().visible()
        assertSame(vm,ViewModelProvider(controller.get())[TrackerViewModel::class.java])
        compose.waitForIdle()
    }
    private fun form(correction:Boolean) {
        val initial=t.data()
        if(correction) compose.onNodeWithText("Original correction note",substring=true).performScrollTo().performClick()
        else compose.onNodeWithText("Добавить запись задним числом").performScrollTo().performClick()
        compose.onNodeWithText(if(correction) "Исправить запись" else "Добавить запись").assertExists()
        compose.onNodeWithContentDescription("Заметка").performTextReplacement("Typed retained note")
        compose.onNodeWithContentDescription("Начало позже на 5 минут").performClick()
        compose.onNodeWithContentDescription("Конец раньше на 5 минут").performClick()
        compose.onNode(SemanticsMatcher("Activity chooser action") { it.config.getOrNull(SemanticsActions.OnClick)?.label=="Изменить занятие" }).performClick()
        compose.onNodeWithContentDescription("Найти или создать занятие").performTextInput("Draft selected")
        compose.waitUntil(10000) { compose.onAllNodesWithText("Draft selected activity").fetchSemanticsNodes().size==1 }
        compose.onNodeWithText("Draft selected activity").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(initial,t.data()) // Selecting the form activity cannot hit a home quick-start tile.
        val before=t.data()
        if(correction) t.manual(t.snapshot().token,at("10:00"),at("11:00"),Classification(name="Concurrent fixture fact"),"Keep this fact")
        val afterExternal=t.data();recreate()
        compose.onNodeWithText(if(correction) "Исправить запись" else "Добавить запись").assertExists()
        compose.onNodeWithContentDescription("Заметка").assertTextContains("Typed retained note")
        compose.onNodeWithText("Draft selected activity").assertExists()
        compose.onNodeWithText(if(correction) "12:05" else "13:35").assertExists()
        compose.onNodeWithText(if(correction) "12:25" else "13:55").assertExists()
        assertEquals(afterExternal,t.data()) // Recreation never saves a fact or reuses a persisted command.
        compose.waitUntil(10000) { compose.onAllNodes(hasText("Сохранить") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Сохранить").performClick()
        compose.waitUntil(10000) { t.data().sessions.any { it.note=="Typed retained note" } }
        val saved=t.data().sessions.single { it.note=="Typed retained note" }
        assertEquals("Saved classification: ${saved.selection}","p2-form-choice",saved.selection.cardId)
        val intervals=t.data().segments.filter { it.sessionId==saved.id }
        assertEquals(if(correction)at("12:05") else at("13:35"),intervals.minOf { it.start })
        assertEquals(if(correction)at("12:25") else at("13:55"),intervals.maxOf { it.end!! })
        if(correction) assertTrue(t.data().sessions.any { it.note=="Keep this fact" })
        else assertTrue(t.data().sessions.containsAll(before.sessions))
    }
    @Test fun manualInputsAndTargetSurviveActivityRecreationWithoutAutosave()=form(false)
    @Test fun correctionInputsSurviveAndPreviewRecomputesAgainstChangedData()=form(true)
    private fun incoming(text:String) {
        MainActivity::class.java.getDeclaredMethod("onNewIntent",Intent::class.java).apply { isAccessible=true }.invoke(controller.get(),
            Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").putExtra(Intent.EXTRA_PROCESS_TEXT,text))
        compose.waitForIdle()
    }
    private fun replacement(accept:Boolean) {
        val before=t.data();incoming("First retained text");assertEquals("First retained text",vm.draft.value!!.note)
        incoming("Second incoming text");assertEquals("First retained text",vm.draft.value!!.note)
        recreate()
        compose.onNodeWithText("Заменить черновик?").assertExists()
        compose.onNodeWithText(if(accept) "Заменить" else "Отмена").performClick();compose.waitForIdle()
        val expected=if(accept) "Second incoming text" else "First retained text"
        assertEquals(expected,vm.draft.value!!.note);assertEquals(before,t.data())
        recreate();assertEquals(expected,vm.draft.value!!.note);assertEquals(before,t.data())
        compose.onNodeWithText("Заменить черновик?").assertDoesNotExist()
    }
    @Test fun secondProcessTextKeepsFirstUntilExplicitRejectionAcrossRecreation()=replacement(false)
    @Test fun secondProcessTextReplacesOnlyAfterExplicitAcceptanceAcrossRecreation()=replacement(true)
}
