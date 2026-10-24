@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.notai.tracker.android.ui

import dev.notai.tracker.android.R

import androidx.compose.ui.res.stringResource

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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import dev.notai.tracker.codec
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.notai.tracker.Classification
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

private val EntrySelectionSaver = Saver<Classification?,String>(save = { it?.let { c -> codec.encodeToString(c) } },restore = { codec.decodeFromString<Classification>(it) })
private val EntryLabelSaver = mapSaver<Label>(
    save = { mapOf("title" to it.title,"detail" to it.detail,"slot" to it.slot,"unclassified" to it.unclassified,"glyph" to it.glyph) },
    restore = { Label(it["title"] as String,it["detail"] as String?,it["slot"] as Int?,it["unclassified"] as Boolean,it["glyph"] as Int) })

internal const val MINUTE = 60_000L
internal fun floorMinute(ms: Long) = ms - ms % MINUTE

/** A time of day with ± steps (1/5/15 min) and a dial on tap. The date stays the value's own local date. */
@Composable
fun TimeField(label: String, value: Long, zone: ZoneId, onChange: (Long) -> Unit, modifier: Modifier = Modifier, focused: Boolean = false, hint: String? = null,
              step: Int = 5) {
    val by = if (step == 1) "1 минуту" else "$step минут"
    val c = NotAi.colors
    var dial by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = NotAi.type.caption, color = c.ink2)
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp))
            .border(if (focused) 1.5.dp else 1.dp, if (focused) c.amber else c.rule, RoundedCornerShape(14.dp)).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconAction(Icon.Minus, "$label раньше на $by", { onChange(value - step * MINUTE) }, size = 18.dp)
            Box(Modifier.weight(1f).heightIn(min = 52.dp).pressable({ dial = true }, label = "Выбрать время: $label"), contentAlignment = Alignment.Center) {
                // Never wraps: shrinks with large system fonts instead of losing digits.
                BasicText(Format.clock(value, zone), style = NotAi.type.sheetTitle.copy(fontSize = 22.sp, fontWeight = FontWeight.Normal, color = c.ink),
                    maxLines = 1, softWrap = false, autoSize = TextAutoSize.StepBased(minFontSize = 13.sp, maxFontSize = 22.sp))
            }
            IconAction(Icon.Plus, "$label позже на $by", { onChange(value + step * MINUTE) }, size = 18.dp)
        }
        if (hint != null) Text(hint, style = NotAi.type.micro.copy(fontSize = 12.sp), color = c.ink2)
    }
    if (dial) {
        val local = Instant.ofEpochMilli(value).atZone(zone)
        val state = rememberTimePickerState(local.hour, local.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { dial = false }, containerColor = c.raised,
            confirmButton = { PillButton(stringResource(R.string.editorial_tracker_draft_d4f52821d4cd489ea827_v0), {
                onChange(local.withHour(state.hour).withMinute(state.minute).withSecond(0).withNano(0).toInstant().toEpochMilli()); dial = false
            }, kind = ButtonKind.TEXT, height = 44.dp) },
            dismissButton = { PillButton(stringResource(R.string.editorial_tracker_draft_60c7c26716cac6e0288c_v0), { dial = false }, kind = ButtonKind.TEXT, height = 44.dp) },
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
        Text(stringResource(R.string.editorial_tracker_draft_25a310c49fcbc14afa82_v0), style = NotAi.type.caption, color = c.ink2)
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).border(1.dp, c.rule, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 13.dp)) {
            if (value.isEmpty()) Text(stringResource(R.string.editorial_tracker_draft_d464164bc61a7e85c4c6_v0), style = NotAi.type.body, color = c.ink2)
            BasicTextField(value, onChange, Modifier.fillMaxWidth().semantics { contentDescription = "Заметка" }, textStyle = NotAi.type.body.copy(color = c.ink),
                cursorBrush = SolidColor(c.amber), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), maxLines = 4)
        }
    }
}

@Composable
internal fun ActivityField(label: Label, onClick: () -> Unit, action: String = "Изменить") {
    val c = NotAi.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(c.sunk).pressable(onClick, label = "Изменить занятие")
        .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ActivityGlyph(label)
        Row(Modifier.weight(1f)) {
            Text(label.title, style = NotAi.type.body, color = c.ink, maxLines = 1)
            label.detail?.let { Text(" $it", style = NotAi.type.body, color = c.ink2, maxLines = 1) }
        }
        Text(action, style = NotAi.type.bodySmall.copy(fontWeight = FontWeight.Medium), color = c.amberInk)
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
               onDelete: ((EntryUi) -> Unit)? = null,targetId: String,gap: GapUi? = null) {
    val c = NotAi.colors
    val session = remember(entry) { entry?.let { vm.session(it.sessionId) } }
    val origStart = session?.second?.firstOrNull()?.start
    val origEnd = session?.second?.lastOrNull()?.end
    val now = remember { vm.now() }
    val step by vm.correctionStepMinutes.collectAsState()
    val reportingDate by vm.reportingDate.collectAsState()
    val today = reportingDate ?: ReportingDayPolicy(zone.id,vm.view.value?.data?.settings?.reportDayStartMinute ?: 0).dateAt(vm.now())
    var start by rememberSaveable { mutableLongStateOf(origStart ?: gap?.start ?: floorMinute(minOf(now, day.end) - 30 * MINUTE).coerceAtLeast(day.start)) }
    var end by rememberSaveable { mutableLongStateOf(origEnd ?: gap?.end ?: floorMinute(minOf(now, day.end - 1))) }
    var selection by rememberSaveable(stateSaver = EntrySelectionSaver) { mutableStateOf<Classification?>(null) }
    val baseSelection = session?.first?.selection ?: Classification()
    var label by rememberSaveable(stateSaver = EntryLabelSaver) { mutableStateOf(entry?.label ?: Label("Без занятия", null, null, true)) }
    var note by rememberSaveable { mutableStateOf(session?.first?.note.orEmpty()) }
    val currentView by vm.view.collectAsState()
    val currentForm by vm.entryForm.collectAsState()
    val form = currentForm?.takeIf { it.id == targetId }
    var state by remember { mutableStateOf<CorrectionState?>(null) }
    var favorite by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf(entry != null) }
    // Save always applies the preview of exactly what is on screen: any edit invalidates it until recomputed.
    if (entry != null) LaunchedEffect(start, end, selection, note,currentView?.snapshot()?.token,form?.reviewNonce) {
        pending = true
        delay(150)
        state = vm.previewCorrection(CorrectionDraft(entry.sessionId, start, end, selection, note), day.start, day.end)
        pending = false
    }
    NotAiSheet(onDismiss) {
        SheetHeader(when { entry != null -> "Исправить запись"; gap != null -> "Записать промежуток"; else -> "Добавить запись" }, onDismiss, extra = if (entry != null && onDelete != null) {
            { IconAction(Icon.Trash, stringResource(R.string.editorial_tracker_draft_87e40c5c1ee8a15a63bd_v0), { onDelete(entry) }, size = 19.dp) }
        } else null)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ActivityField(label, { onPickActivity { sel, l -> selection = sel; label = l } })
            if (entry == null && vm.calendarAvailable) CalendarLink(vm, day, zone) { e ->
                start = e.reference.begin.coerceIn(day.start, day.end); end = e.reference.end.coerceIn(day.start, day.end)
                if (note.isBlank()) e.title?.takeIf { it.isNotBlank() }?.let { note = it }
            }
            MiniTimeline(day, entry?.sessionId, start, end, origStart, origEnd, label.slot, zone)
            TimePair {
                TimeField("Начало", start, zone, { start = it }, it, focused = origStart != null && start != origStart, step = step,
                    hint = when { origStart != null && start != origStart -> "было ${Format.clock(origStart, zone)}"
                        state?.freeFrom != null -> "свободно с ${Format.clock(state!!.freeFrom!!, zone)}"; else -> null })
                TimeField("Конец", end, zone, { end = it }, it, focused = origEnd != null && end != origEnd, step = step,
                    hint = when { origEnd != null && end != origEnd -> "было ${Format.clock(origEnd, zone)}"
                        state?.freeUntil != null -> "свободно до ${Format.clock(state!!.freeUntil!!, zone)}"; else -> null })
            }
            StepChooser(step, vm::setCorrectionStepMinutes)
            NoteField(note, { note = it })
            form?.error?.let { Note(it) }
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
            PillButton(stringResource(R.string.editorial_tracker_draft_60c7c26716cac6e0288c_v0), onDismiss, m, ButtonKind.TONAL)
            PillButton(stringResource(R.string.editorial_tracker_draft_8f4fc15539624b530640_v0), {
                vm.submitEntryForm(targetId,start,end,selection ?: Classification(),note,preview)
            }, m, ButtonKind.PRIMARY, enabled = form?.saving != true && (entry == null || (preview != null && !pending)))
        }
    }
}

/** The ± step of every time field, one quiet line: 1 minute by default, 5 and 15 one tap away. */
@Composable
fun StepChooser(step: Int, onStep: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = NotAi.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Text("Шаг ±", style = NotAi.type.caption, color = c.ink2, modifier = Modifier.padding(end = 4.dp))
        listOf(1, 5, 15).forEach { m ->
            val on = m == step
            Box(Modifier.heightIn(min = 32.dp).widthIn(min = 44.dp).clip(RoundedCornerShape(16.dp)).background(if (on) c.sunk else androidx.compose.ui.graphics.Color.Transparent)
                .semantics { selected = on }.pressable({ onStep(m) }, role = androidx.compose.ui.semantics.Role.RadioButton, label = "Шаг $m мин")
                .padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                Text("$m мин", style = NotAi.type.caption.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), color = if (on) c.ink else c.ink2)
            }
        }
    }
}

/** "Forgot to switch": choose the past moment first; the picker then applies the retrospective switch. */
@Composable
fun EarlierSheet(zone: ZoneId, segmentStart: Long, now: Long, onNext: (Long) -> Unit, onDismiss: () -> Unit, step: Int = 1, onStep: (Int) -> Unit = {}) {
    var at by remember { mutableLongStateOf(floorMinute(maxOf(segmentStart, now - 10 * MINUTE)).coerceIn(segmentStart,now)) }
    NotAiSheet(onDismiss, fullHeight = false) {
        SheetHeader(stringResource(R.string.editorial_tracker_draft_de07b012af25d20940d9_v0), onDismiss)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TimeField("Момент переключения", at, zone, { at = it.coerceIn(segmentStart, now) }, focused = true, step = step,
                hint = "между ${Format.clock(segmentStart, zone)} и сейчас")
            StepChooser(step, onStep)
            Text(stringResource(R.string.editorial_tracker_draft_57dc53f53a2dcf590521_v0), style = NotAi.type.bodySmall, color = NotAi.colors.ink2)
            PillButton(stringResource(R.string.editorial_tracker_draft_7a94427842da266bf16f_v0), { onNext(at) }, Modifier.fillMaxWidth().padding(bottom = 20.dp), ButtonKind.PRIMARY)
        }
    }
}

/**
 * The current record began before Start was pressed. The core proposes the added stretch first (from the end of the
 * previous record, or a chosen time); nothing changes until Save, and the added time is marked as stated by the person.
 */
@Composable
fun StartEarlierSheet(vm: TrackerViewModel, zone: ZoneId, currentStart: Long, onDismiss: () -> Unit) {
    val c = NotAi.colors
    val step by vm.correctionStepMinutes.collectAsState()
    val previous by produceState<dev.notai.tracker.StartBackfillPreview?>(null) { value = vm.previewStartEarlier(null).getOrNull() }
    var at by rememberSaveable { mutableLongStateOf(floorMinute(currentStart - 15 * MINUTE)) }
    var touched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(previous) { previous?.let { if (!touched) at = it.proposedSegment.start } }
    val result by produceState<Result<dev.notai.tracker.StartBackfillPreview>?>(null, at, previous) {
        delay(120); value = vm.previewStartEarlier(at)
    }
    NotAiSheet(onDismiss, fullHeight = false) {
        SheetHeader("Когда ты начал?", onDismiss)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            previous?.let { p ->
                val chosen = at == p.proposedSegment.start
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(if (chosen) c.amberWash else c.sunk)
                    .pressable({ at = p.proposedSegment.start; touched = true }, label = "С конца прошлой записи").padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    M3Icon(if (chosen) Icon.Check else Icon.ChevronLeft, null, Modifier.size(18.dp), tint = if (chosen) c.amberInk else c.ink2)
                    Text("С конца прошлой записи", Modifier.weight(1f), style = NotAi.type.body, color = c.ink)
                    Text(Format.clock(p.proposedSegment.start, zone), style = NotAi.type.body, color = c.ink2)
                }
            }
            TimeField("Начало", at, zone, { at = minOf(it, currentStart - MINUTE); touched = true }, focused = true, step = step,
                hint = "раньше, чем ${Format.clock(currentStart, zone)}")
            StepChooser(step, vm::setCorrectionStepMinutes)
            val r = result
            val preview = r?.getOrNull()?.takeIf { it.proposedSegment.start == at }
            when {
                preview != null -> Note("Добавится ${Format.duration(preview.addedMs)} до ${Format.clock(currentStart, zone)}. " +
                    "Это время отметится как указанное тобой, а не замеренное.", wash = true)
                r?.exceptionOrNull() != null -> Note(when ((r.exceptionOrNull() as? dev.notai.tracker.CoreError)?.code) {
                    dev.notai.tracker.ErrorCode.OVERLAP -> "Это время уже занято другой записью. Выбери начало позже."
                    dev.notai.tracker.ErrorCode.RECOVERY_REQUIRED -> "Сначала уточни неизвестное время."
                    else -> Format.error(r.exceptionOrNull()!!)
                })
            }
            PillButton(stringResource(R.string.editorial_tracker_draft_8f4fc15539624b530640_v0), { preview?.let { vm.applyStartEarlier(it) {} }; onDismiss() },
                Modifier.fillMaxWidth().padding(bottom = 20.dp), ButtonKind.PRIMARY, enabled = preview != null)
        }
    }
}

/** Unknown tail: the person states the real interval; the app never assumes one. */
@Composable
fun ResolveSheet(zone: ZoneId, knownStart: Long, now: Long, onConfirm: (Long, Long) -> Unit, onDismiss: () -> Unit, step: Int = 1, onStep: (Int) -> Unit = {}) {
    var start by remember { mutableLongStateOf(knownStart) }
    var end by remember { mutableLongStateOf(floorMinute(now)) }
    NotAiSheet(onDismiss, fullHeight = false) {
        SheetHeader(stringResource(R.string.editorial_tracker_draft_117e2b56ce25cb830325_v0), onDismiss)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            TimePair {
                TimeField("Начало", start, zone, { start = it }, it, hint = "записано ${Format.clock(knownStart, zone)}", step = step)
                TimeField("Конец", end, zone, { end = minOf(it, now) }, it, focused = true, hint = "не позже, чем сейчас", step = step)
            }
            StepChooser(step, onStep)
            Text(stringResource(R.string.editorial_tracker_draft_0fe49deada10db74c773_v0),
                style = NotAi.type.bodySmall, color = NotAi.colors.ink2)
            PillButton(stringResource(R.string.editorial_tracker_draft_8f4fc15539624b530640_v0), { onConfirm(start, end); onDismiss() }, Modifier.fillMaxWidth().padding(bottom = 20.dp), ButtonKind.AMBER, enabled = end > start)
        }
    }
}

/** «Из календаря»: the day's timed events; picking one fills the times and, if empty, the note. Permission is asked on tap. */
@Composable
private fun CalendarLink(vm: TrackerViewModel, day: DayUi, zone: ZoneId, onPick: (dev.notai.tracker.android.CalendarOccurrence) -> Unit) {
    val c = NotAi.colors
    var open by remember { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    val permission = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) open = true else denied = true
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(Modifier.heightIn(min = 40.dp).pressable({
        if (context.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) == android.content.pm.PackageManager.PERMISSION_GRANTED) open = true
        else permission.launch(android.Manifest.permission.READ_CALENDAR)
    }, label = "Взять время из календаря"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        M3Icon(Icon.Calendar, null, Modifier.size(16.dp), tint = c.amberInk)
        Text(if (denied) "Календарь недоступен без разрешения" else "Из календаря", style = NotAi.type.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = if (denied) c.ink2 else c.amberInk)
    }
    if (open) {
        val page by produceState<dev.notai.tracker.android.CalendarPage?>(null, day.start) { value = vm.calendarEvents(day.start, day.end) }
        NotAiSheet({ open = false }, fullHeight = false) {
            SheetHeader("Календарь: ${Format.dayMonth(day.date)}", { open = false })
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
                val p = page
                val events = p?.occurrences.orEmpty().filter { !it.allDay }
                when {
                    p == null -> Spacer(Modifier.height(80.dp))
                    p.issue != null -> Text("Календарь сейчас не отвечает.", style = NotAi.type.bodySmall, color = c.ink2)
                    events.isEmpty() -> Text("В этот день в календаре нет событий со временем.", style = NotAi.type.bodySmall, color = c.ink2)
                    else -> events.forEach { e ->
                        ListRow(e.title?.takeIf { it.isNotBlank() } ?: "Без названия", subtitle = Format.range(e.reference.begin, e.reference.end, zone),
                            onClick = { onPick(e); open = false })
                    }
                }
            }
        }
    }
}
