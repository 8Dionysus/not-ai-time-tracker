package dev.notai.tracker.android.ui

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.notai.tracker.Classification
import dev.notai.tracker.Mode

enum class Tab(val title: String) { TIMER("Таймер"), OVERVIEW("Обзор"), SETTINGS("Настройки") }

private sealed interface Sheet {
    data class Picker(val mode: PickMode, val boundary: Long? = null) : Sheet
    data class Edit(val entry: EntryUi?, val day: DayUi) : Sheet
    data object Earlier : Sheet
    data object Resolve : Sheet
}

/**
 * @param onActionReady called after the first composition that shows live timer controls (startup tracing only).
 */
@Composable
fun TrackerApp(vm: TrackerViewModel, openTechnical: () -> Unit, reducedMotion: Boolean? = null, onActionReady: () -> Unit = {}) {
    val theme by vm.theme.collectAsStateWithLifecycle()
    NotAiTheme(theme, reducedMotion) {
        val c = NotAi.colors
        val view = LocalView.current
        if (!view.isInEditMode) SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !c.isDark; isAppearanceLightNavigationBars = !c.isDark
            }
        }
        val boot by vm.boot.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxSize().background(c.paper)) {
            Crossfade(boot::class, label = "boot", animationSpec = tween(if (NotAi.reducedMotion) 0 else 300)) { kind ->
                when (kind) {
                    TrackerViewModel.Boot.Loading::class -> Splash()
                    TrackerViewModel.Boot.Failed::class -> Failed((boot as? TrackerViewModel.Boot.Failed)?.message.orEmpty(), vm::load, openTechnical)
                    else -> Main(vm, theme, openTechnical, onActionReady)
                }
            }
        }
    }
}

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        IntervalFrame(IntervalState.IDLE, bracketWidth = 18.dp, bracketHeight = 72.dp, gap = 16.dp) { Wordmark(size = 22) }
    }
}

@Composable
private fun Failed(message: String, retry: () -> Unit, openTechnical: () -> Unit) {
    val c = NotAi.colors
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        IntervalFrame(IntervalState.UNKNOWN, bracketWidth = 18.dp, bracketHeight = 72.dp, opening = false) { Text("?", style = NotAi.type.big, color = c.ink2) }
        Text("Локальная база сейчас недоступна", style = NotAi.type.sheetTitle, color = c.ink, textAlign = TextAlign.Center)
        Text("Файлы не удалены. Восстановить базу или выбрать копию можно в технической оболочке. Подробности: $message",
            style = NotAi.type.bodySmall, color = c.ink2, textAlign = TextAlign.Center)
        PillButton("Открыть восстановление", openTechnical, Modifier.fillMaxWidth(), ButtonKind.PRIMARY)
        PillButton("Попробовать снова", retry, Modifier.fillMaxWidth(), ButtonKind.TONAL)
    }
}

@Composable
private fun Main(vm: TrackerViewModel, theme: ThemeChoice, openTechnical: () -> Unit, onActionReady: () -> Unit) {
    val c = NotAi.colors
    var tab by rememberSaveable { mutableStateOf(Tab.TIMER) }
    var sheet by remember { mutableStateOf<Sheet?>(null) }
    var formPicker by remember { mutableStateOf<((Classification, Label) -> Unit)?>(null) }
    val snapshot by vm.snapshot.collectAsStateWithLifecycle()
    val home by vm.home.collectAsStateWithLifecycle()
    val view by vm.view.collectAsStateWithLifecycle()
    val draft by vm.draft.collectAsStateWithLifecycle()
    val surfacePicker by vm.surfacePicker.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val dark = c.isDark
    if (snapshot != null) SideEffect(onActionReady)

    LaunchedEffect(Unit) {
        vm.notices.collect { n ->
            val result = snackbar.showSnackbar(n.text, actionLabel = if (n.undo != null) "Отменить" else null,
                duration = if (n.undo != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed && n.undo != null) vm.undo(n.undo)
        }
    }
    LaunchedEffect(draft) { if (draft != null) { tab = Tab.TIMER; sheet = Sheet.Picker(PickMode.START) } }

    var confirmDelete by remember { mutableStateOf<DeleteRequest.Confirm?>(null) }
    val requestDelete: suspend (EntryUi) -> Boolean = { e ->
        when (val r = vm.requestDelete(e.sessionId)) {
            DeleteRequest.Applied -> true
            DeleteRequest.Failed -> false
            is DeleteRequest.Confirm -> { confirmDelete = r; false }
        }
    }
    val scope = rememberCoroutineScope()

    val actions = remember(vm,dark) {
        TimerActions(
            pick = { mode -> sheet = Sheet.Picker(mode) },
            edit = { e, d -> sheet = Sheet.Edit(e, d) },
            manual = { d -> sheet = Sheet.Edit(null, d) },
            earlier = { sheet = Sheet.Earlier },
            resolve = { sheet = Sheet.Resolve },
            toggleTheme = { vm.setTheme(if (dark) ThemeChoice.LIGHT else ThemeChoice.DARK) },
            delete = requestDelete,
        )
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().statusBarsPadding()) {
            Crossfade(tab, label = "tab", animationSpec = tween(if (NotAi.reducedMotion) 0 else 200)) { t ->
                when (t) {
                    Tab.TIMER -> TimerScreen(snapshot, home, vm, actions, dark)
                    Tab.OVERVIEW -> OverviewScreen(vm, view)
                    Tab.SETTINGS -> SettingsScreen(vm, view, theme, openTechnical)
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(12.dp)) { data ->
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).shadow(12.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
                    .background(c.ink).padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(data.visuals.message, Modifier.weight(1f).padding(vertical = 12.dp), style = NotAi.type.bodySmall.copy(fontSize = 15.sp), color = c.paper)
                    data.visuals.actionLabel?.let { a ->
                        Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).pressable({ data.performAction() }).padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center) { Text(a, style = NotAi.type.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c.amber) }
                    }
                }
            }
        }
        BottomBar(tab, { tab = it }, live = snapshot?.let { it.mode == Mode.RUNNING || it.focus != null } == true)
    }

    val zone = home?.zone ?: java.time.ZoneId.systemDefault()
    when (val s = sheet) {
        is Sheet.Picker -> {
            val active = snapshot?.let { it.mode != Mode.IDLE } == true
            PickerSheet(vm, s.mode, active, home?.currentStart?.let { Format.clock(it, zone) }, s.boundary?.let { Format.clock(it, zone) }, s.boundary,
                onDismiss = { sheet = null })
        }
        // The form stays open underneath; the picker hands its choice back to it.
        is Sheet.Edit -> EntrySheet(vm, zone, s.day, s.entry, onDismiss = { sheet = null }, onPickActivity = { callback -> formPicker = callback },
            onDelete = { e -> scope.launch { if (requestDelete(e) || confirmDelete != null) sheet = null } })
        Sheet.Earlier -> {
            val seg = snapshot?.active
            if (seg != null) EarlierSheet(zone, seg.start, remember { vm.now() }, onNext = { at -> sheet = Sheet.Picker(PickMode.EARLIER, at) }, onDismiss = { sheet = null })
            else LaunchedEffect(Unit) { sheet = null }
        }
        Sheet.Resolve -> {
            val start = snapshot?.active?.start ?: home?.currentStart
            if (start != null) ResolveSheet(zone, start, remember { vm.now() }, onConfirm = vm::resolveTail, onDismiss = { sheet = null })
            else LaunchedEffect(Unit) { sheet = null }
        }
        null -> Unit
    }
    // Opened from the notification ("Все занятия") or a saved stopped record: the same quick choices, applied by route.
    surfacePicker?.let { state ->
        val d = view?.data
        if (d != null) QuickPickSheet(state, d, onChoose = { vm.chooseSurface(it) }, onDismiss = vm::cancelSurfacePicker, onAll = {
            val switch = state.route.application == dev.notai.tracker.ChoiceApplication.SWITCH
            vm.cancelSurfacePicker(); tab = Tab.TIMER; sheet = Sheet.Picker(if (switch) PickMode.SWITCH else PickMode.START)
        })
    }
    confirmDelete?.let { r ->
        val parts = r.consequences.joinToString(" и ")
        val corrections = if(r.exerciseSets > 0 || r.eventQuantities > 0) " Включая сохранённые исправления этих данных." else ""
        ConfirmDialog("Удалить запись целиком?", "${r.range}: вместе с записью удалятся $parts.$corrections Сразу после удаления можно отменить.",
            "Удалить", onConfirm = { vm.applyDeleteSession(r.preview); confirmDelete = null }, onDismiss = { confirmDelete = null }, destructive = true)
    }
    formPicker?.let { back ->
        PickerSheet(vm, PickMode.MANUAL, false, null, null, null, onDismiss = { formPicker = null }, onReturn = { sel, label -> back(sel, label) })
    }
}

@Composable
private fun BottomBar(tab: Tab, onSelect: (Tab) -> Unit, live: Boolean) {
    val c = NotAi.colors
    Column(Modifier.fillMaxWidth().background(c.paper)) {
        Hairline()
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp)) {
            Tab.entries.forEach { t ->
                val on = t == tab
                val icon: ImageVector = when (t) { Tab.TIMER -> Icon.TabTimer; Tab.OVERVIEW -> Icon.TabOverview; Tab.SETTINGS -> Icon.TabSettings }
                Column(Modifier.weight(1f).fillMaxHeight().semantics { selected = on }.pressable({ onSelect(t) }, role = Role.Tab, label = t.title),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(contentAlignment = Alignment.Center) {
                        M3Icon(icon, null, Modifier.size(22.dp), tint = if (on) c.ink else c.ink2)
                        if (t == Tab.TIMER) Box(Modifier.size(4.dp).clip(CircleShape).background(if (live) c.amber else if (on) c.ink else c.ink2))
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(t.title, style = NotAi.type.micro.copy(fontSize = 12.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), color = if (on) c.ink else c.ink2)
                }
            }
        }
    }
}

@Composable
private fun QuickPickSheet(state: dev.notai.tracker.android.SurfacePickerState, d: dev.notai.tracker.Dataset,
                           onChoose: (dev.notai.tracker.ChoiceReference) -> Unit, onDismiss: () -> Unit, onAll: () -> Unit) {
    val c = NotAi.colors
    val assignment = state.assignment != null
    val switch = state.route.application == dev.notai.tracker.ChoiceApplication.SWITCH
    NotAiSheet(onDismiss, fullHeight = false) {
        SheetHeader(when { assignment -> "Чем ты был занят?"; switch -> "Переключиться на"; else -> "Начать сразу" }, onDismiss)
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when { assignment -> "Время уже сохранено, осталось выбрать занятие."; switch -> "Текущая запись закончится сейчас, новая начнётся с этой минуты."
                else -> "Избранное и частые занятия за 30 дней." }, style = NotAi.type.bodySmall, color = c.ink2, modifier = Modifier.padding(bottom = 6.dp))
            state.choices.filter { it.entry.canStart && !d.label(it.entry.selection).unclassified }.forEach { q ->
                val l = d.label(q.entry.selection)
                ListRow(if (q.entry.kind == dev.notai.tracker.ChoiceKind.FAVORITE) q.entry.title else l.title,
                    detail = if (q.entry.kind == dev.notai.tracker.ChoiceKind.FAVORITE) null else l.detail, onClick = { onChoose(q.reference) },
                    leading = { ActivityGlyph(l) })
            }
            if (state.choices.isEmpty()) Text("Частых занятий пока нет.", style = NotAi.type.body, color = c.ink2)
            if (!assignment) PillButton("Все занятия", onAll, Modifier.fillMaxWidth().padding(top = 8.dp), ButtonKind.TONAL, height = 48.dp)
            else PillButton("Оставить без занятия", onDismiss, Modifier.fillMaxWidth().padding(top = 8.dp), ButtonKind.TONAL, height = 48.dp)
        }
    }
}
