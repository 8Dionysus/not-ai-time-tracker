@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.notai.tracker.android.ui

import dev.notai.tracker.ReportingDayPolicy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.notai.tracker.Classification
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

private const val MINUTE = 60_000L
private fun floorMinute(ms: Long) = ms - ms % MINUTE

/** A time of day with ±5 min steps and a dial on tap. The date stays the value's own local date. */
@Composable
fun TimeField(label: String, value: Long, zone: ZoneId, onChange: (Long) -> Unit, modifier: Modifier = Modifier, focused: Boolean = false, hint: String? = null) {
    val c = NotAi.colors
    var dial by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = NotAi.type.caption, color = c.ink2)
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp))
            .border(if (focused) 1.5.dp else 1.dp, if (focused) c.amber else c.rule, RoundedCornerShape(14.dp)).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icon.Minus, "$label раньше на 5 минут", { onChange(value - 5 * MINUTE) }, size = 18.dp)
            Box(Modifier.weight(1f).heightIn(min = 52.dp).pressable({ dial = true }, label = "Выбрать время: $label"), contentAlignment = Alignment.Center) {
                // Never wraps: shrinks with large system fonts instead of losing digits.
                BasicText(Format.clock(value, zone), style = NotAi.type.sheetTitle.copy(fontSize = 22.sp, fontWeight = FontWeight.Normal, color = c.ink),
                    maxLines = 1, softWrap = false, autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = 22.sp))
            }
            IconAction(Icon.Plus, "$label позже на 5 минут", { onChange(value + 5 * MINUTE) }, size = 18.dp)
        }
        if (hint != null) Text(hint, style = NotAi.type.micro.copy(fontSize = 12.sp), color = c.ink2)
    }
    if (dial) {
        val local = Instant.ofEpochMilli(value).atZone(zone)
        val state = rememberTimePickerState(local.hour, local.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { dial = false }, containerColor = c.raised,
            confirmButton = { PillButton("Готово", {
                onChange(local.withHour(state.hour).withMinute(state.minute).withSecond(0).withNano(0).toInstant().toEpochMilli()); dial = false
            }, kind = ButtonKind.TEXT, height = 44.dp) },
            dismissButton = { PillButton("Отмена", { dial = false }, kind = ButtonKind.TEXT, height = 44.dp) },
            title = { Text(label, style = NotAi.type.sheetTitle, color = c.ink) },
            text = {
                TimePicker(state, colors = TimePickerDefaults.colors(
                    clockDialColor = c.sunk, selectorColor = c.amber, containerColor = c.raised,
                    clockDialSelectedContentColor = c.onAmber, clockDialUnselectedContentColor = c.ink,
                    timeSelectorSelectedContainerColor = c.amberWash, timeSelectorUnselectedContainerColor = c.sunk,
                    timeSelectorSelectedContentColor = c.ink, timeSelectorUnselectedContentColor = c.ink2,
                ))
            },
        )
    }
}

/** Start and end side by side when both fit with their ±5 buttons, stacked on narrow screens or large fonts. */
@Composable
private fun TimePair(content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
        if (maxWidth >= 330.dp * fontScale) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { content(Modifier.weight(1f)) }
        else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content(Modifier.fillMaxWidth()) }
    }
}

@Composable
fun NoteField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = NotAi.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Заметка", style = NotAi.type.caption, color = c.ink2)
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, c.rule, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp)) {
            if (value.isEmpty()) Text("Не обязательно", style = NotAi.type.body, color = c.ink2)
            BasicTextField(value, onChange, Modifier.fillMaxWidth().semantics { contentDescription = "Заметка" }, textStyle = NotAi.type.body.copy(color = c.ink),
                cursorBrush = SolidColor(c.amber), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), maxLines = 4)
        }
    }
}

@Composable
private fun ActivityField(label: Label, onClick: () -> Unit) {
    val c = NotAi.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(c.sunk).pressable(onClick, label = "Изменить занятие")
        .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActivityGlyph(label)
        Row(Modifier.weight(1f)) {
            Text(label.title, style = NotAi.type.body, color = c.ink, maxLines = 1)
            label.detail?.let { Text(" $it", style = NotAi.type.body, color = c.ink2, maxLines = 1) }
        }
        Text("Изменить", style = NotAi.type.bodySmall.copy(fontWeight = FontWeight.Medium), color = c.amberInk)
    }
}

/** Neighbours, the old interval (dashed) and the edited one framed by the brand brackets. */
@Composable
private fun MiniTimeline(day: DayUi, sessionId: String?, start: Long, end: Long, oldStart: Long?, oldEnd: Long?, slot: Int?, zone: ZoneId) {
    val c = NotAi.colors
    val hour = 60 * MINUTE
    val lo = minOf(start, oldStart ?: start) - 30 * MINUTE
    val hi = maxOf(end, oldEnd ?: end) + 30 * MINUTE
    val step = maxOf(1L, ((hi - lo) / hour + 3) / 4) * hour
    val from = maxOf(day.start, lo - Math.floorMod(lo - day.start, hour))
    val count = ((hi - from + step - 1) / step).coerceAtLeast(1)
    val until = from + count * step
    val span = (until - from).toFloat()
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "Положение записи среди соседних" }) {
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            fun x(t: Long) = ((t - from) / span).coerceIn(0f, 1f) * size.width
            val top = 20.dp.toPx(); val h = 12.dp.toPx()
            drawRoundRect(c.sunk, Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
            day.entries.filter { it.sessionId != sessionId }.forEach { e ->
                if (e.end > from && e.start < until) drawRoundRect((e.label.slot?.let { c.areas[it] } ?: c.none).copy(alpha = 0.35f),
                    Offset(x(e.start), top), Size(maxOf(x(e.end) - x(e.start), 2f), h), CornerRadius(3.dp.toPx()))
            }
            drawRoundRect(slot?.let { c.areas[it] } ?: c.none, Offset(x(start), top), Size(maxOf(x(end) - x(start), 2f), h), CornerRadius(3.dp.toPx()))
            if (oldStart != null && oldEnd != null && (oldStart != start || oldEnd != end)) {
                val y = top + h + 7.dp.toPx() // "was": a dashed line under the bar
                drawLine(c.ink3, Offset(x(oldStart), y), Offset(x(oldEnd), y), strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())))
            }
            bracket(x(start) - 15.dp.toPx(), true, c.amber)
            bracket(x(end) - 1.dp.toPx(), false, c.ink2)
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            (0..count).map { from + it * step }.forEach { Text(Format.clock(it, zone), style = NotAi.type.micro, color = c.ink2) }
        }
    }
}

private fun DrawScope.bracket(left: Float, isLeft: Boolean, color: androidx.compose.ui.graphics.Color) {
    val w = 16.dp.toPx(); val h = size.height
    val sx = w / 26f; val sy = h / 120f
    val p = Path().apply {
        if (isLeft) { moveTo(left + 21 * sx, 4 * sy); cubicTo(left + 5 * sx, 34 * sy, left + 5 * sx, 86 * sy, left + 21 * sx, 116 * sy) }
        else { moveTo(left + 5 * sx, 4 * sy); cubicTo(left + 21 * sx, 34 * sy, left + 21 * sx, 86 * sy, left + 5 * sx, 116 * sy) }
    }
    drawPath(p, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
}

/**
 * Correct a finished entry (with a core preview of the day before/after) or add one retrospectively.
 * Nothing is written until Save; Cancel needs no command.
 */
@Composable
fun EntrySheet(vm: TrackerViewModel, zone: ZoneId, day: DayUi, entry: EntryUi?, onDismiss: () -> Unit, onPickActivity: ((Classification, Label) -> Unit) -> Unit,
               onDelete: ((EntryUi) -> Unit)? = null) {
    val c = NotAi.colors
    val session = remember(entry) { entry?.let { vm.session(it.sessionId) } }
    val origStart = session?.second?.firstOrNull()?.start
    val origEnd = session?.second?.lastOrNull()?.end
    val now = remember { vm.now() }
    val reportingDate by vm.reportingDate.collectAsState()
    val today = reportingDate ?: ReportingDayPolicy(zone.id,vm.view.value?.data?.settings?.reportDayStartMinute ?: 0).dateAt(vm.now())
    var start by remember { mutableLongStateOf(origStart ?: floorMinute(minOf(now, day.end) - 30 * MINUTE).coerceAtLeast(day.start)) }
    var end by remember { mutableLongStateOf(origEnd ?: floorMinute(minOf(now, day.end - 1))) }
    var selection by remember { mutableStateOf<Classification?>(null) }
    val baseSelection = session?.first?.selection ?: Classification()
    var label by remember { mutableStateOf(entry?.label ?: Label("Без занятия", null, null, true)) }
    var note by remember { mutableStateOf(session?.first?.note.orEmpty()) }
    var state by remember { mutableStateOf<CorrectionState?>(null) }
    var favorite by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(entry != null) }
    // Save always applies the preview of exactly what is on screen: any edit invalidates it until recomputed.
    if (entry != null) LaunchedEffect(start, end, selection, note) {
        pending = true
        delay(150)
        state = vm.previewCorrection(CorrectionDraft(entry.sessionId, start, end, selection, note), day.start, day.end)
        pending = false
    }
    NotAiSheet(onDismiss) {
        SheetHeader(if (entry != null) "Исправить запись" else "Добавить запись", onDismiss, extra = if (entry != null && onDelete != null) {
            { IconAction(Icon.Trash, "Удалить запись", { onDelete(entry) }, size = 19.dp) }
        } else null)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ActivityField(label) { onPickActivity { sel, l -> selection = sel; label = l } }
            MiniTimeline(day, entry?.sessionId, start, end, origStart, origEnd, label.slot, zone)
            TimePair {
                TimeField("Начало", start, zone, { start = it }, it, focused = origStart != null && start != origStart,
                    hint = when { origStart != null && start != origStart -> "было ${Format.clock(origStart, zone)}"
                        state?.freeFrom != null -> "свободно с ${Format.clock(state!!.freeFrom!!, zone)}"; else -> null })
                TimeField("Конец", end, zone, { end = it }, it, focused = origEnd != null && end != origEnd,
                    hint = when { origEnd != null && end != origEnd -> "было ${Format.clock(origEnd, zone)}"
                        state?.freeUntil != null -> "свободно до ${Format.clock(state!!.freeUntil!!, zone)}"; else -> null })
            }
            NoteField(note, { note = it })
            val st = state
            if (entry != null && st != null) {
                if (st.error != null) Note(st.error)
                else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.amberWash).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row { Text("${Format.dayTitle(day.date, today)}, итог", Modifier.weight(1f), style = NotAi.type.bodySmall, color = c.ink)
                        Text(Format.duration(st.beforeMs), style = NotAi.type.bodySmall, color = c.ink2)
                        Text(" → ${Format.duration(st.afterMs)}", style = NotAi.type.bodySmall, color = c.ink) }
                }
            }
            Row(Modifier.heightIn(min = 44.dp).pressable({ favorite = true; vm.saveFavorite(label.title, selection ?: baseSelection) }),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                M3Icon(if (favorite) Icon.Star else Icon.StarLine, null, Modifier.size(18.dp), tint = if (favorite) c.amber else c.ink2)
                Text(if (favorite) "В избранном" else "Добавить в избранное", style = NotAi.type.bodySmall, color = c.ink2)
            }
        }
        val preview = state?.preview
        ButtonPair(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp)) { m ->
            PillButton("Отмена", onDismiss, m, ButtonKind.TONAL)
            PillButton("Сохранить", {
                if (entry != null) preview?.let(vm::applyCorrection) else vm.manual(start, end, selection ?: Classification(), note)
                onDismiss()
            }, m, ButtonKind.PRIMARY, enabled = entry == null || (preview != null && !pending))
        }
    }
}

/** "Forgot to switch": choose the past moment first; the picker then applies the retrospective switch. */
@Composable
fun EarlierSheet(zone: ZoneId, segmentStart: Long, now: Long, onNext: (Long) -> Unit, onDismiss: () -> Unit) {
    var at by remember { mutableLongStateOf(floorMinute(maxOf(segmentStart, now - 10 * MINUTE))) }
    NotAiSheet(onDismiss, fullHeight = false) {
        SheetHeader("Когда ты переключился?", onDismiss)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TimeField("Момент переключения", at, zone, { at = it.coerceIn(segmentStart, now) }, focused = true,
                hint = "между ${Format.clock(segmentStart, zone)} и сейчас")
            Text("Время до этого момента останется у текущего занятия, после него перейдёт к новому.", style = NotAi.type.bodySmall, color = NotAi.colors.ink2)
            PillButton("Выбрать занятие", { onNext(at) }, Modifier.fillMaxWidth().padding(bottom = 20.dp), ButtonKind.PRIMARY)
        }
    }
}

/** Unknown tail: the person states the real interval; the app never assumes one. */
@Composable
fun ResolveSheet(zone: ZoneId, knownStart: Long, now: Long, onConfirm: (Long, Long) -> Unit, onDismiss: () -> Unit) {
    var start by remember { mutableLongStateOf(knownStart) }
    var end by remember { mutableLongStateOf(floorMinute(now)) }
    NotAiSheet(onDismiss, fullHeight = false) {
        SheetHeader("Когда ты закончил?", onDismiss)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TimePair {
                TimeField("Начало", start, zone, { start = it }, it, hint = "записано ${Format.clock(knownStart, zone)}")
                TimeField("Конец", end, zone, { end = minOf(it, now) }, it, focused = true, hint = "не позже, чем сейчас")
            }
            Text("Запись закроется с этими границами. Если занятие ещё идёт, начни новую запись после сохранения.",
                style = NotAi.type.bodySmall, color = NotAi.colors.ink2)
            PillButton("Сохранить", { onConfirm(start, end); onDismiss() }, Modifier.fillMaxWidth().padding(bottom = 20.dp), ButtonKind.AMBER, enabled = end > start)
        }
    }
}
