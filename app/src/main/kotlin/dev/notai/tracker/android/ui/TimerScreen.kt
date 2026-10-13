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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
)

private enum class HeroKind { LOADING, IDLE, ACTIVE, RECOVERY, FOCUS }
private data class HeroState(val kind: HeroKind,val snapshot: Snapshot?)

@Composable
fun TimerScreen(snapshot: Snapshot?, home: HomeData?, vm: TrackerViewModel, actions: TimerActions, dark: Boolean, modifier: Modifier = Modifier) {
    val c = NotAi.colors
    val zone = home?.zone ?: ZoneId.systemDefault()
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
        if (home != null) history(home, snapshot, vm, actions)
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
            .pressable(vm::startBlank, label = "Начать учёт времени").semantics { contentDescription = "Начать учёт времени" },
            contentAlignment = Alignment.Center) {
            IntervalFrame(IntervalState.IDLE, bracketWidth = 26.dp, bracketHeight = 104.dp, gap = 22.dp, opening = false) {
                Text("Начать", style = NotAi.type.start, color = c.ink)
            }
        }
        Text("Время пойдёт сразу.\nЗанятие можно выбрать потом.", style = NotAi.type.bodySmall, color = c.ink2, textAlign = TextAlign.Center)
        Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel("Начать сразу")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                home?.quick?.forEach { q ->
                    Chip(q.title, { vm.choose(q.entry, PickMode.START) }, slot = q.slot, icon = null)
                }
                Chip("Все занятия", { actions.pick(PickMode.START) }, dashed = true, icon = Icon.Plus)
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
            ActivityDot(null, unclassified = true)
            Text("Без занятия", style = NotAi.type.label, color = c.ink)
            Text("выбрать", style = NotAi.type.label.copy(fontWeight = FontWeight.Medium), color = c.amberInk)
        }
    } else {
        Row(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).pressable(onClick, label = "Сменить или уточнить занятие")
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActivityDot(label.slot)
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
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.mode == Mode.RUNNING) PillButton("Пауза", vm::pause, Modifier.weight(1f), ButtonKind.TONAL, Icon.Pause)
            else PillButton("Продолжить", vm::resume, Modifier.weight(1f), ButtonKind.AMBER, Icon.Play)
            PillButton("Стоп", vm::stop, Modifier.weight(1f), ButtonKind.PRIMARY, Icon.Stop)
        }
        if (s.mode == Mode.RUNNING && "switchedEarlier" in s.actions) Row(Modifier.heightIn(min = 44.dp).pressable(actions.earlier).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Забыл переключиться? ", style = NotAi.type.bodySmall, color = c.ink2)
            Text("Указать когда", style = NotAi.type.bodySmall.copy(fontWeight = FontWeight.Medium), color = c.amberInk)
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
                ActivityDot(l.slot); Row { Text(l.title, style = NotAi.type.label, color = c.ink); l.detail?.let { Text(" $it", style = NotAi.type.label, color = c.ink2) } }
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
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when (f.mode) {
                FocusMode.RUNNING -> PillButton("Пауза", vm::pause, Modifier.weight(1f), ButtonKind.TONAL, Icon.Pause)
                FocusMode.PAUSED -> PillButton("Продолжить", vm::resume, Modifier.weight(1f), ButtonKind.AMBER, Icon.Play)
                else -> PillButton(if (work) "Начать перерыв" else "Начать работу", vm::focusNext, Modifier.weight(1f), ButtonKind.AMBER)
            }
            PillButton("Завершить", vm::stop, Modifier.weight(1f), ButtonKind.PRIMARY, Icon.Stop)
        }
        if (f.mode != FocusMode.DUE) PillButton(if (work) "Пропустить отрезок" else "Пропустить перерыв", vm::focusSkip, kind = ButtonKind.TEXT, height = 44.dp)
        Text("Перерыв не входит во время занятия. Следующий отрезок начнётся, когда ты нажмёшь.", style = NotAi.type.caption, color = c.ink2,
            textAlign = TextAlign.Center)
    }
}

private fun LazyListScope.history(home: HomeData, snapshot: Snapshot?, vm: TrackerViewModel, actions: TimerActions) {
    home.days.forEach { day ->
        item(key = "day-${day.date}") {
            val c = NotAi.colors
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 4.dp)) {
                SectionTitle(Format.dayTitle(day.date, home.today), trailing = Format.duration(day.recordedMs))
                Spacer(Modifier.height(10.dp))
                val pieces = day.ribbon.map { RibbonPiece(it.start, it.end, it.slot?.let { s -> c.areas[s] } ?: c.none) } +
                    day.live.map { RibbonPiece(it.start, it.end, c.amber, live = true) }
                DayRibbon(day.start, day.end, pieces)
            }
        }
        items(day.entries, key = { "${day.date}:${it.segmentId}" }) { e ->
            EntryRow(e, home.zone, onOpen = { actions.edit(e, day) }, onRepeat = { vm.repeat(e) },
                modifier = Modifier.padding(horizontal = 20.dp).animateItem())
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

@Composable
fun EntryRow(e: EntryUi, zone: ZoneId, onOpen: () -> Unit, onRepeat: () -> Unit, modifier: Modifier = Modifier) {
    val c = NotAi.colors
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).pressable(onOpen, label = "Исправить запись"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActivityDot(e.label.slot, unclassified = e.label.unclassified)
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
