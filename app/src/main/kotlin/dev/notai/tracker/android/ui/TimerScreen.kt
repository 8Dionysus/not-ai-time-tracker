@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.notai.tracker.android.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.produceState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.DpOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.notai.tracker.FocusMode
import dev.notai.tracker.FocusPhase
import dev.notai.tracker.Mode
import dev.notai.tracker.Snapshot
import java.time.ZoneId

/** Everything the timer screen can ask its host to open. */
class TimerActions(
    val pick: (PickMode) -> Unit,
    val edit: (EntryUi, DayUi) -> Unit,
    val manual: (DayUi) -> Unit,
    val earlier: () -> Unit,
    val resolve: () -> Unit,
    val toggleTheme: () -> Unit,
    /** Shared by swipe and the long-press menu. Returns true when the record is already gone. */
    val delete: suspend (EntryUi) -> Boolean = { false },
    /** The current record actually began before Start was pressed. */
    val startEarlier: () -> Unit = {},
    /** An unrecorded stretch between two records: opens the ordinary entry form on exactly that stretch. */
    val fillGap: (GapUi, DayUi) -> Unit = { _, _ -> },
)

private enum class HeroKind { LOADING, IDLE, ACTIVE, RECOVERY, FOCUS }
private data class HeroState(val kind: HeroKind,val snapshot: Snapshot?)

@Composable
fun TimerScreen(snapshot: Snapshot?, home: HomeData?, vm: TrackerViewModel, actions: TimerActions, dark: Boolean, modifier: Modifier = Modifier) {
    val c = NotAi.colors
    val zone = home?.zone ?: ZoneId.systemDefault()
    // Gaps stay folded per day until asked for: visible, never nagging.
    var openGaps by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(listOf<String>()) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "top") {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Wordmark(Modifier.weight(1f))
                Text(home?.today?.let { Format.headerDate(it) } ?: "", style = NotAi.type.bodySmall, color = c.ink2)
                IconAction(if (dark) Icon.Sun else Icon.Moon, if (dark) "Светлая тема" else "Тёмная тема", actions.toggleTheme)
            }
        }
        item(key = "hero") {
            val kind = when {
                snapshot == null -> HeroKind.LOADING
                snapshot.focus != null -> HeroKind.FOCUS
                snapshot.mode == Mode.RECOVERY -> HeroKind.RECOVERY
                snapshot.mode == Mode.IDLE -> HeroKind.IDLE
                else -> HeroKind.ACTIVE
            }
            val reduced = NotAi.reducedMotion
            // Outgoing content keeps the snapshot that belongs to its own kind.
            // A completed Focus must not render against the new idle snapshot.
            AnimatedContent(HeroState(kind,snapshot), Modifier.fillMaxWidth(), contentKey = { it.kind }, transitionSpec = {
                if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                else (fadeIn(tween(260, 90)) + slideInVertically(tween(320)) { it / 12 }) togetherWith fadeOut(tween(120))
            }, label = "hero") { state ->
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 8.dp), contentAlignment = Alignment.TopCenter) {
                    when (state.kind) {
                        HeroKind.LOADING -> Spacer(Modifier.height(300.dp))
                        HeroKind.IDLE -> IdleHero(home, vm, actions)
                        HeroKind.ACTIVE -> ActiveHero(state.snapshot!!, home, vm, actions, zone)
                        HeroKind.RECOVERY -> RecoveryHero(state.snapshot!!, home, vm, actions, zone)
                        HeroKind.FOCUS -> FocusHero(state.snapshot!!, home, vm, actions, zone)
                    }
                }
            }
        }
        if (home != null) history(home, snapshot, vm, actions, openGaps) { d -> openGaps = if (d in openGaps) openGaps - d else openGaps + d }
    }
}

@Composable
fun Wordmark(modifier: Modifier = Modifier, size: Int = 18) {
    val c = NotAi.colors
    Row(modifier) {
        Text("(not)", style = NotAi.type.label.copy(fontSize = size.sp, fontWeight = FontWeight.Medium), color = c.ink2)
        Text("AI", style = NotAi.type.label.copy(fontSize = size.sp, fontWeight = FontWeight.Medium), color = c.ink)
    }
}

@Composable
private fun IdleHero(home: HomeData?, vm: TrackerViewModel, actions: TimerActions) {
    val c = NotAi.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.height(128.dp).widthIn(min = 280.dp).clip(RoundedCornerShape(64.dp))
            .pressable(haptic(HapticFeedbackType.Confirm, vm::startBlank), label = "Начать учёт времени").semantics { contentDescription = "Начать учёт времени" },
            contentAlignment = Alignment.Center) {
            IntervalFrame(IntervalState.IDLE, bracketWidth = 26.dp, bracketHeight = 104.dp, gap = 22.dp, opening = false) {
                Text("Начать", style = NotAi.type.start, color = c.ink)
            }
        }
        Text("Время пойдёт сразу.\nЗанятие можно выбрать потом.", style = NotAi.type.bodySmall, color = c.ink2, textAlign = TextAlign.Center)
        Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val plan = home?.plan.orEmpty()
            if (plan.isNotEmpty()) {
                SectionLabel("По плану на сегодня")
                PlanChips(plan, vm)
                Spacer(Modifier.height(2.dp))
            }
            val quick = home?.quick.orEmpty()
            val dismissed by vm.starterDismissed.collectAsStateWithLifecycle()
            // A new install has no favourites or history yet: invite a few own activities instead of an empty section.
            if (home != null && quick.isEmpty() && !dismissed) StarterCard(vm)
            else {
                if (quick.isNotEmpty()) SectionLabel("Начать сразу")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    quick.forEach { q ->
                        Chip(q.title, { vm.choose(q.entry, PickMode.START,reference = q.reference,expected = q.expected) }, slot = q.slot, glyph = q.glyph)
                    }
                    Chip(if (quick.isEmpty()) "Начать с занятием" else "Все занятия", { actions.pick(PickMode.START) }, dashed = true, icon = Icon.Plus)
                }
            }
            PillButton("Фокус: ${home?.focusMinutes ?: 25} мин работы", vm::startFocus, kind = ButtonKind.TEXT, height = 44.dp,
                modifier = Modifier.align(Alignment.Start).offset(x = (-18).dp))
        }
    }
}

@Composable
private fun Digits(ms: Long, state: IntervalState) {
    val c = NotAi.colors
    val color by animateColorAsState(if (state == IntervalState.RUNNING) c.ink else c.ink2, tween(400), label = "digits")
    Row(modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "Прошло ${Format.duration(ms)}" }) {
        Text(Format.timerMain(ms), style = NotAi.type.timer, color = color, modifier = Modifier.alignByBaseline())
        Text(Format.timerSeconds(ms), style = NotAi.type.timerSeconds, color = c.ink2, modifier = Modifier.alignByBaseline().padding(start = 4.dp).widthIn(min = 30.dp))
    }
}

@Composable
private fun CurrentLabel(home: HomeData?, onClick: () -> Unit) {
    val c = NotAi.colors
    val label = home?.currentLabel
    if (label == null || label.unclassified) {
        Row(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(c.amberWash).pressable(onClick, label = "Выбрать занятие")
            .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActivityGlyph(ActivityGlyphs.unclassified, null, size = 18.dp, unclassified = true)
            Text("Без занятия", style = NotAi.type.label, color = c.ink)
            Text("выбрать", style = NotAi.type.label.copy(fontWeight = FontWeight.Medium), color = c.amberInk)
        }
    } else {
        Row(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).pressable(onClick, label = "Сменить или уточнить занятие")
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActivityGlyph(label)
            Row(Modifier.weight(1f, fill = false)) {
                Text(label.title, style = NotAi.type.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                label.detail?.let { Text(" $it", style = NotAi.type.label, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
            }
            M3Icon(Icon.ChevronDown, null, Modifier.size(16.dp), tint = c.ink2)
        }
    }
}

@Composable
private fun ActiveHero(s: Snapshot, home: HomeData?, vm: TrackerViewModel, actions: TimerActions, zone: ZoneId) {
    val c = NotAi.colors
    val state = if (s.mode == Mode.RUNNING) IntervalState.RUNNING else IntervalState.PAUSED
    val start = home?.currentStart
    val labelled = home?.currentLabel?.unclassified == false
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.height(128.dp), contentAlignment = Alignment.Center) {
            IntervalFrame(state) { Digits(s.trustedDurationMs, state) }
        }
        CurrentLabel(home) { actions.pick(if (labelled) PickMode.SWITCH else PickMode.LABEL) }
        val line = when {
            s.mode == Mode.PAUSED -> "На паузе. Пауза не входит во время занятия."
            start == null -> ""
            labelled -> "С ${Format.clock(start, zone)}"
            else -> "Идёт с ${Format.clock(start, zone)}. Начало сохранится при выборе."
        }
        Text(line, style = NotAi.type.caption, color = c.ink2, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        ButtonPair(Modifier.padding(top = 4.dp)) { m ->
            if (s.mode == Mode.RUNNING) PillButton("Пауза", haptic(HapticFeedbackType.ToggleOff, vm::pause), m, ButtonKind.TONAL, Icon.Pause)
            else PillButton("Продолжить", haptic(HapticFeedbackType.ToggleOn, vm::resume), m, ButtonKind.AMBER, Icon.Play)
            PillButton("Стоп", haptic(HapticFeedbackType.Confirm, vm::stop), m, ButtonKind.PRIMARY, Icon.Stop)
        }
        // Two quiet fixes of the same kind: the record began before Start, or the switch happened before it was tapped.
        val earlier = s.mode == Mode.RUNNING && "switchedEarlier" in s.actions
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TimeFix("Начал раньше", actions.startEarlier)
            if (earlier) {
                Text("·", style = NotAi.type.bodySmall, color = c.ink3)
                TimeFix("Переключился раньше", actions.earlier)
            }
        }
        val plan = home?.plan.orEmpty().filter { !it.started }
        if (plan.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("Дальше по плану")
            PlanChips(plan, vm)
        }
    }
}

@Composable
private fun TimeFix(text: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).pressable(onClick, label = text).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = NotAi.type.bodySmall.copy(fontWeight = FontWeight.Medium), color = NotAi.colors.amberInk)
    }
}

/** Today's plan. A tap starts (or switches to) it; a long press can drop it for today. Done ones stay, quietly checked. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlanChips(plan: List<PlanItem>, vm: TrackerViewModel) {
    val c = NotAi.colors
    val haptics = LocalHapticFeedback.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        plan.forEach { p ->
            var menu by remember { mutableStateOf(false) }
            Box {
                Row(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50))
                    .background(if (p.started) Color.Transparent else c.sunk)
                    .then(if (p.started) Modifier.border(1.dp, c.rule, RoundedCornerShape(50)) else Modifier)
                    .combinedClickable(enabled = !p.started, onClickLabel = "Начать по плану", onLongClickLabel = "Действия с планом",
                        onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true }, onClick = { vm.startPlanned(p) })
                    .semantics { contentDescription = p.title + (p.time?.let { ", $it" } ?: "") + if (p.started) ", уже начато" else "" }
                    .padding(start = 11.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (p.started) M3Icon(Icon.Check, null, Modifier.size(16.dp), tint = c.ink2) else ActivityGlyph(p.glyph, p.slot, size = 17.dp)
                    Text(p.title, style = NotAi.type.bodySmall, color = if (p.started) c.ink2 else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    p.time?.let { Text(it, style = NotAi.type.bodySmall, color = c.ink2, maxLines = 1) }
                }
                DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(16.dp), containerColor = c.raised,
                    border = BorderStroke(1.dp, c.rule), shadowElevation = 8.dp) {
                    DropdownMenuItem(text = { Text("Убрать на сегодня", style = NotAi.type.body, color = c.ink) },
                        onClick = { menu = false; vm.skipPlanned(p) }, leadingIcon = { M3Icon(Icon.Close, null, Modifier.size(18.dp), tint = c.ink2) })
                }
            }
        }
    }
}

@Composable
private fun RecoveryHero(s: Snapshot, home: HomeData?, vm: TrackerViewModel, actions: TimerActions, zone: ZoneId) {
    val c = NotAi.colors
    val start = s.active?.start ?: home?.currentStart
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.height(128.dp), contentAlignment = Alignment.Center) {
            IntervalFrame(IntervalState.UNKNOWN, opening = false) {
                // Known part only. After a restart nothing after the start is trusted, so the start itself is shown.
                if (s.trustedDurationMs > 0 || start == null) Digits(s.trustedDurationMs, IntervalState.PAUSED)
                else Text(Format.clock(start, zone), style = NotAi.type.timer, color = c.ink2,
                    modifier = Modifier.semantics { contentDescription = "Начало ${Format.clock(start, zone)}, конец неизвестен" })
            }
        }
        home?.currentLabel?.takeIf { !it.unclassified }?.let { l ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActivityGlyph(l); Row { Text(l.title, style = NotAi.type.label, color = c.ink); l.detail?.let { Text(" $it", style = NotAi.type.label, color = c.ink2) } }
            }
        }
        Text(if (start != null) "Конец неизвестен" else "Время неизвестно",
            style = NotAi.type.label.copy(fontWeight = FontWeight.Medium), color = c.ink, textAlign = TextAlign.Center)
        Text("Телефон перезагружался или часы сдвинулись, пока шёл учёт. Сколько длилось занятие, приложение не знает и угадывать не станет.",
            style = NotAi.type.bodySmall, color = c.ink2, textAlign = TextAlign.Center)
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton("Указать, когда закончил", actions.resolve, Modifier.fillMaxWidth(), ButtonKind.AMBER)
            PillButton(if (start != null) "Не учитывать с ${Format.clock(start, zone)}" else "Не учитывать", vm::excludeTail, Modifier.fillMaxWidth(), ButtonKind.TONAL)
        }
    }
}

@Composable
private fun FocusHero(s: Snapshot, home: HomeData?, vm: TrackerViewModel, actions: TimerActions, zone: ZoneId) {
    val c = NotAi.colors
    val f = s.focus!!
    if (f.mode == FocusMode.RECOVERY) { RecoveryHero(s, home, vm, actions, zone); return }
    val work = f.phase == FocusPhase.WORK
    val state = if (f.mode == FocusMode.RUNNING) IntervalState.RUNNING else IntervalState.PAUSED
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (work) "Фокус, отрезок работы ${f.phaseIndex / 2 + 1}" else "Фокус, перерыв", style = NotAi.type.caption, color = c.ink2)
        Box(Modifier.height(128.dp), contentAlignment = Alignment.Center) {
            IntervalFrame(if (f.mode == FocusMode.DUE) IntervalState.PAUSED else state) {
                Text(Format.countdown(f.remainingMs), style = NotAi.type.timer, color = if (state == IntervalState.RUNNING) c.ink else c.ink2,
                    modifier = Modifier.semantics { contentDescription = "Осталось ${Format.duration(f.remainingMs)}" })
            }
        }
        Text(if (f.mode == FocusMode.DUE) (if (work) "Отрезок работы закончился" else "Перерыв закончился")
             else "осталось из ${Format.duration(f.durationMs)}", style = NotAi.type.bodySmall, color = c.ink2)
        val fraction = if (f.durationMs > 0) (f.elapsedMs.toFloat() / f.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.sunk)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(if (work) c.amber else c.ink3))
        }
        if (work) CurrentLabel(home) { actions.pick(PickMode.LABEL) }
        ButtonPair(Modifier.padding(top = 4.dp)) { m ->
            when (f.mode) {
                FocusMode.RUNNING -> PillButton("Пауза", haptic(HapticFeedbackType.ToggleOff, vm::pause), m, ButtonKind.TONAL, Icon.Pause)
                FocusMode.PAUSED -> PillButton("Продолжить", haptic(HapticFeedbackType.ToggleOn, vm::resume), m, ButtonKind.AMBER, Icon.Play)
                else -> PillButton(if (work) "Начать перерыв" else "Начать работу", vm::focusNext, m, ButtonKind.AMBER)
            }
            PillButton("Завершить", haptic(HapticFeedbackType.Confirm, vm::stop), m, ButtonKind.PRIMARY, Icon.Stop)
        }
        if (f.mode != FocusMode.DUE) PillButton(if (work) "Пропустить отрезок" else "Пропустить перерыв", vm::focusSkip, kind = ButtonKind.TEXT, height = 44.dp)
        Text("Перерыв не входит во время занятия. Следующий отрезок начнётся, когда ты нажмёшь.", style = NotAi.type.caption, color = c.ink2,
            textAlign = TextAlign.Center)
    }
}

private fun LazyListScope.history(home: HomeData, snapshot: Snapshot?, vm: TrackerViewModel, actions: TimerActions,
                                   openGaps: List<String>, toggleGaps: (String) -> Unit) {
    home.days.forEach { day ->
        item(key = "day-${day.date}") {
            val c = NotAi.colors
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 4.dp)) {
                val empty = day.entries.isEmpty() && day.live.isEmpty() && day.recordedMs == 0L
                SectionTitle(Format.dayTitle(day.date, home.today), trailing = if (empty) null else Format.duration(day.recordedMs))
                Spacer(Modifier.height(10.dp))
                if (empty) Text(if (day.date == home.today) "Записей пока нет. Здесь появится каждый отрезок." else "Записей нет.",
                    style = NotAi.type.bodySmall, color = c.ink2)
                else {
                    val pieces = day.ribbon.map { RibbonPiece(it.start, it.end, it.slot?.let { s -> c.areas[s] } ?: c.none) } +
                        day.live.map { RibbonPiece(it.start, it.end, c.amber, live = true) }
                    DayRibbon(day.start, day.end, pieces,zone = home.zone)
                    val fillable = day.gaps.count { it.kind == dev.notai.tracker.HistoryGapKind.UNRECORDED }
                    if (fillable > 0) {
                        val open = day.date.toString() in openGaps
                        Text((if (open) "Скрыть промежутки" else Format.plural(fillable, "промежуток", "промежутка", "промежутков") + " без записи"),
                            Modifier.padding(top = 6.dp).heightIn(min = 32.dp).pressable({ toggleGaps(day.date.toString()) },
                                label = if (open) "Скрыть промежутки без записи" else "Показать промежутки без записи").padding(vertical = 6.dp),
                            style = NotAi.type.caption.copy(fontWeight = FontWeight.Medium), color = c.amberInk)
                    }
                }
            }
        }
        // Newest first, gaps in their place between the two records around them.
        val gaps = if (day.date.toString() in openGaps) day.gaps else emptyList()
        val rows: List<Any> = (day.entries + gaps).sortedByDescending { if (it is EntryUi) it.start else (it as GapUi).start }
        items(rows, key = { r -> if (r is EntryUi) "${day.date}:${r.segmentId}" else "${day.date}:gap:${(r as GapUi).start}" }) { r ->
            if (r is EntryUi) SwipeToDelete(r, actions.delete, Modifier.animateItem()) {
                EntryRow(r, home.zone, onOpen = { actions.edit(r, day) }, onRepeat = { vm.repeat(r) },
                    onDelete = { actions.delete(r) }, modifier = Modifier.padding(horizontal = 20.dp))
            } else GapRow(r as GapUi, home.zone, { actions.fillGap(r, day) }, Modifier.animateItem().padding(horizontal = 20.dp))
        }
        if (day.date == home.today) item(key = "manual") {
            Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().heightIn(min = 48.dp).pressable({ actions.manual(day) }),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                M3Icon(Icon.Plus, null, Modifier.size(16.dp), tint = NotAi.colors.ink2)
                Text("Добавить запись задним числом", style = NotAi.type.bodySmall, color = NotAi.colors.ink2)
            }
        }
    }
    if (home.canLoadMore) item(key = "more") {
        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            PillButton("Показать раньше", vm::showMoreDays, kind = ButtonKind.OUTLINE, height = 44.dp)
        }
    }
}

/** Swipe left to delete. The row stays away while the record goes; it comes back if nothing was deleted. */
@Composable
private fun SwipeToDelete(e: EntryUi, delete: suspend (EntryUi) -> Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = NotAi.colors
    val state = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.35f })
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state.targetValue) {
        if (state.targetValue == SwipeToDismissBoxValue.EndToStart) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) {
            if (!delete(e)) state.reset()
            else { delay(2_500); state.reset() } // still composed: the write failed or Undo restored it
        }
    }
    SwipeToDismissBox(state, modifier = modifier, enableDismissFromStartToEnd = false, backgroundContent = {
        if (state.dismissDirection != SwipeToDismissBoxValue.EndToStart) return@SwipeToDismissBox
        val active = state.targetValue == SwipeToDismissBoxValue.EndToStart
        Row(Modifier.fillMaxSize().background(if (active) c.danger.copy(alpha = 0.16f) else c.sunk).padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text("Удалить", style = NotAi.type.bodySmall.copy(fontWeight = FontWeight.Medium), color = if (active) c.danger else c.ink2)
            Spacer(Modifier.width(10.dp))
            M3Icon(Icon.Trash, null, Modifier.size(20.dp), tint = if (active) c.danger else c.ink2)
        }
    }) { Box(Modifier.background(c.paper)) { content() } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryRow(e: EntryUi, zone: ZoneId, onOpen: () -> Unit, onRepeat: () -> Unit, modifier: Modifier = Modifier, onDelete: (suspend () -> Unit)? = null) {
    val c = NotAi.colors
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth()) {
        Box {
            EntryMenu(menu, onDismiss = { menu = false }, onOpen = onOpen, onRepeat = onRepeat,
                onDelete = onDelete?.let { d -> { scope.launch { d() } } })
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .combinedClickable(onClickLabel = "Исправить запись", onLongClickLabel = "Действия с записью",
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true }, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActivityGlyph(e.label)
            Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row {
                    Text(e.label.title, style = NotAi.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    e.label.detail?.let { Text(" $it", style = NotAi.type.body, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                }
                Text(Format.range(e.start, e.end, zone) + (e.note?.let { ", $it" } ?: ""), style = NotAi.type.caption, color = c.ink2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(Format.duration(e.durationMs), style = NotAi.type.body.copy(fontSize = 15.sp), color = c.ink)
            IconAction(Icon.Repeat, "Повторить: ${e.label.title}", onRepeat, size = 16.dp)
        }
        Hairline()
    }
}

/**
 * A quiet row for a stretch without a record. Unrecorded time can be filled in one tap; a pause or unknown time is only named.
 * No duration: the app does not measure what was not recorded.
 */
@Composable
private fun GapRow(g: GapUi, zone: ZoneId, onFill: () -> Unit, modifier: Modifier = Modifier) {
    val c = NotAi.colors
    val fillable = g.kind == dev.notai.tracker.HistoryGapKind.UNRECORDED
    val what = when (g.kind) {
        dev.notai.tracker.HistoryGapKind.UNRECORDED -> "не записано"
        dev.notai.tracker.HistoryGapKind.PAUSE -> "пауза"
        dev.notai.tracker.HistoryGapKind.RECOVERY_UNCERTAIN -> "время неизвестно"
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).then(if (fillable) Modifier.pressable(onFill, label = "Записать промежуток") else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = "${Format.range(g.start, g.end, zone)}, $what" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // An empty dashed ring in the icon column: a place where nothing was recorded.
            androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
                drawCircle(c.ink3, radius = 6.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))))
            }
            Text(Format.range(g.start, g.end, zone), style = NotAi.type.caption, color = c.ink2)
            Text(what, Modifier.weight(1f), style = NotAi.type.caption, color = c.ink3)
            if (fillable) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                M3Icon(Icon.Plus, null, Modifier.size(14.dp), tint = c.amberInk)
                Text("Записать", style = NotAi.type.caption.copy(fontWeight = FontWeight.Medium), color = c.amberInk)
            }
        }
        Hairline()
    }
}

/** Long press: the same three actions as elsewhere, delete last and in the danger colour. */
@Composable
private fun EntryMenu(open: Boolean, onDismiss: () -> Unit, onOpen: () -> Unit, onRepeat: () -> Unit, onDelete: (() -> Unit)?) {
    val c = NotAi.colors
    DropdownMenu(open, onDismiss, offset = DpOffset(56.dp, 0.dp), shape = RoundedCornerShape(16.dp), containerColor = c.raised,
        border = BorderStroke(1.dp, c.rule), shadowElevation = 8.dp) {
        @Composable fun item(text: String, icon: ImageVector, color: Color, action: () -> Unit) = DropdownMenuItem(
            text = { Text(text, style = NotAi.type.body, color = color) }, onClick = { onDismiss(); action() },
            leadingIcon = { M3Icon(icon, null, Modifier.size(18.dp), tint = color) }, modifier = Modifier.widthIn(min = 196.dp))
        item("Исправить", Icon.Edit, c.ink, onOpen)
        item("Повторить", Icon.Repeat, c.ink, onRepeat)
        if (onDelete != null) item("Удалить", Icon.Trash, c.danger, onDelete)
    }
}

/** Light, meaningful haptics: confirm for start/stop, toggle for pause/resume. Nothing on ordinary taps. */
@Composable
private fun haptic(type: HapticFeedbackType, action: () -> Unit): () -> Unit {
    val h = LocalHapticFeedback.current
    return { h.performHapticFeedback(type); action() }
}

/**
 * First launch: a dozen everyday activities to pick from. "Done" stores the picked ones as favourites, which
 * are the quick-start chips from then on. "Not now" hides the card for good; everything stays in the search.
 */
@Composable
private fun StarterCard(vm: TrackerViewModel) {
    val c = NotAi.colors
    val starters by produceState(emptyList<Starter>()) { value = vm.starterChoices() }
    var picked by remember { mutableStateOf(listOf<String>()) }
    if (starters.isEmpty()) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.raised).border(1.dp, c.rule, RoundedCornerShape(20.dp))
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Чем ты занимаешься чаще всего?", style = NotAi.type.section, color = c.ink)
            Text("Отметь два-три занятия: они встанут сюда, и старт будет в одно касание. Остальное найдётся в поиске.",
                style = NotAi.type.caption, color = c.ink2)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            starters.forEach { st ->
                val on = st.title in picked
                Row(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50))
                    .background(if (on) c.ink else c.paper).border(1.dp, if (on) c.ink else c.rule, RoundedCornerShape(50))
                    .semantics { selected = on }
                    .pressable({ picked = if (on) picked - st.title else picked + st.title }, role = Role.Checkbox, label = st.title)
                    .padding(start = 11.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (on) M3Icon(Icon.Check, null, Modifier.size(16.dp), tint = c.paper)
                    else ActivityGlyph(st.glyph, st.slot, size = 16.dp)
                    Text(st.title, style = NotAi.type.bodySmall, color = if (on) c.paper else c.ink, maxLines = 1)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PillButton("Не сейчас", vm::dismissStarter, kind = ButtonKind.TEXT, height = 44.dp, modifier = Modifier.offset(x = (-12).dp))
            Spacer(Modifier.weight(1f))
            PillButton(if (picked.isEmpty()) "Готово" else "Готово · ${picked.size}", {
                vm.saveStarters(starters.filter { it.title in picked })
            }, kind = ButtonKind.PRIMARY, height = 44.dp, enabled = picked.isNotEmpty())
        }
    }
}
