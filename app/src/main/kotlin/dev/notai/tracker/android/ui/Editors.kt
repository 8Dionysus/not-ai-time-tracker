@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.notai.tracker.android.ui


import dev.notai.tracker.android.L
import dev.notai.tracker.android.R

import androidx.compose.ui.res.stringResource

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.notai.tracker.Classification
import dev.notai.tracker.GoalDirection
import dev.notai.tracker.GoalEditMode
import dev.notai.tracker.GoalMetric
import dev.notai.tracker.GoalPeriod
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

private val NO_ACTIVITY get() = Label(L.s(R.string.t_e3fc5b6cc7), null, null, true)

/**
 * One plan entry: an activity on chosen weekdays or on one date, with an optional time.
 * The plan only appears on the main screen; time starts when the person taps it.
 */
@Composable
fun PlanSheet(vm: TrackerViewModel, existing: PlanSeriesUi?, onPickActivity: ((Classification, Label) -> Unit) -> Unit, onDismiss: () -> Unit) {
    val c = NotAi.colors
    val today = remember { vm.scheduleToday() }
    val zone = remember { ZoneId.systemDefault() }
    var selection by remember { mutableStateOf(existing?.selection) }
    var label by remember { mutableStateOf(existing?.label ?: NO_ACTIVITY) }
    var once by rememberSaveable { mutableStateOf(existing?.date != null) }
    var days by rememberSaveable { mutableStateOf(existing?.weekdays?.takeIf { it.isNotEmpty() }?.toList() ?: listOf(today.dayOfWeek.value)) }
    var date by rememberSaveable { mutableStateOf((existing?.date ?: today).toString()) }
    var timed by rememberSaveable { mutableStateOf(existing?.time != null) }
    var minuteOfDay by rememberSaveable { mutableIntStateOf(existing?.time?.let { it.hour * 60 + it.minute } ?: 18 * 60) }
    var calendar by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val chosen = LocalDate.parse(date)
    val ready = selection != null && (once || days.isNotEmpty())

    NotAiSheet(onDismiss, fullHeight = false) {
        SheetHeader(if (existing == null) L.s(R.string.t_c4dca81d48) else L.s(R.string.t_3146141c93), onDismiss, extra = existing?.let { {
            IconAction(Icon.Trash, L.s(R.string.t_c82a0d4427), { confirmRemove = true }, size = 19.dp)
        } })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ActivityField(label, { onPickActivity { sel, l -> selection = sel; label = l } }, if (selection == null) L.s(R.string.t_fe4b0c80f0) else L.s(R.string.t_9d809f884b))
            Segmented(listOf(false to L.s(R.string.t_ba7631ff32), true to L.s(R.string.t_1751103733)), once, { once = it }, Modifier.fillMaxWidth())
            if (!once) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Format.weekdays.forEachIndexed { i, name ->
                    val d = i + 1; val on = d in days
                    Box(Modifier.size(42.dp).clip(CircleShape).background(if (on) c.ink else c.raised)
                        .border(1.dp, if (on) c.ink else c.rule, CircleShape)
                        .semantics { selected = on }
                        .pressable({ days = if (on) days - d else days + d }, role = Role.Checkbox, label = name),
                        contentAlignment = Alignment.Center) {
                        Text(name, style = NotAi.type.bodySmall.copy(fontWeight = FontWeight.Medium), color = if (on) c.paper else c.ink)
                    }
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(today to L.s(R.string.t_5d5d2ea20a), today.plusDays(1) to L.s(R.string.t_f6de15c50a)).forEach { (d, name) -> DateChip(name, chosen == d) { date = d.toString() } }
                val other = chosen != today && chosen != today.plusDays(1)
                DateChip(if (other) Format.dayMonth(chosen) else L.s(R.string.t_ae6b38edaf), other) { calendar = true }
            }
            Row(Modifier.fillMaxWidth().pressable({ timed = !timed }, label = L.s(R.string.t_163fdcceaa)), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(L.s(R.string.t_163fdcceaa), style = NotAi.type.body, color = c.ink)
                    Text(if (timed) L.s(R.string.t_f70eab72a9) else L.s(R.string.t_96a0b3fc1d), style = NotAi.type.caption, color = c.ink2)
                }
                NotAiSwitch(timed, { timed = it }, L.s(R.string.t_163fdcceaa))
            }
            if (timed) {
                // TimeField works on instants; any fixed day carries the chosen clock time.
                val base = remember { today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
                TimeField(L.s(R.string.t_c80d7e8172), base + minuteOfDay * MINUTE, ZoneOffset.UTC, { minuteOfDay = Math.floorMod(((it - base) / MINUTE).toInt(), 24 * 60) })
            }
            Text(L.s(R.string.t_cfafc18b42), style = NotAi.type.caption, color = c.ink2)
        }
        PillButton(stringResource(R.string.editorial_tracker_draft_8f4fc15539624b530640_v0), {
            val sel = selection ?: return@PillButton
            vm.savePlan(existing, sel, if (once) emptySet() else days.toSet(), if (once) chosen else null,
                if (timed) LocalTime.of(minuteOfDay / 60, minuteOfDay % 60) else null)
            onDismiss()
        }, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp), ButtonKind.PRIMARY, enabled = ready)
    }
    if (calendar) {
        val state = rememberDatePickerState(chosen.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : androidx.compose.material3.SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = !java.time.Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(today)
            })
        DatePickerDialog(onDismissRequest = { calendar = false }, colors = DatePickerDefaults.colors(containerColor = c.raised),
            confirmButton = { PillButton(stringResource(R.string.editorial_tracker_draft_d4f52821d4cd489ea827_v0), {
                state.selectedDateMillis?.let { date = java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }; calendar = false
            }, kind = ButtonKind.TEXT, height = 44.dp) },
            dismissButton = { PillButton(stringResource(R.string.editorial_tracker_draft_60c7c26716cac6e0288c_v0), { calendar = false }, kind = ButtonKind.TEXT, height = 44.dp) }) {
            DatePicker(state, showModeToggle = false, colors = DatePickerDefaults.colors(containerColor = c.raised,
                selectedDayContainerColor = c.amber, selectedDayContentColor = c.onAmber, todayDateBorderColor = c.amber, todayContentColor = c.ink))
        }
    }
    if (confirmRemove && existing != null) ConfirmDialog(L.s(R.string.t_22615c35f9, existing.label.title),
        L.s(R.string.t_181ee397c9), L.s(R.string.t_9c7449bfd1), { vm.removePlan(existing); confirmRemove = false; onDismiss() },
        { confirmRemove = false }, destructive = true)
}

@Composable
private fun DateChip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = NotAi.colors
    Box(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50)).background(if (on) c.ink else c.raised)
        .border(1.dp, if (on) c.ink else c.rule, RoundedCornerShape(50)).semantics { selected = on }
        .pressable(onClick, role = Role.RadioButton, label = text).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
        Text(text, style = NotAi.type.bodySmall, color = if (on) c.paper else c.ink, maxLines = 1)
    }
}

/**
 * A personal goal: time, number of records or number of days with a record, per day, week or month.
 * No streaks, no rewards. An edit says when it applies; past periods keep the goal they had.
 */
@Composable
fun GoalSheet(vm: TrackerViewModel, initial: GoalDraft?, onPickActivity: ((Classification, Label) -> Unit) -> Unit, onDismiss: () -> Unit) {
    val c = NotAi.colors
    val editing = initial?.id != null
    var selection by remember { mutableStateOf(initial?.selection) }
    var label by remember { mutableStateOf(initial?.label) }
    var activityChanged by remember { mutableStateOf(false) }
    var metric by rememberSaveable { mutableStateOf(initial?.metric ?: GoalMetric.DURATION_MS) }
    var direction by rememberSaveable { mutableStateOf(initial?.direction ?: GoalDirection.MINIMUM) }
    var period by rememberSaveable { mutableStateOf(initial?.period ?: GoalPeriod.DAY) }
    var target by rememberSaveable { mutableLongStateOf(initial?.target ?: 30 * MINUTE) }
    var mode by rememberSaveable { mutableStateOf(GoalEditMode.NEXT_PERIOD) }
    var confirmRemove by remember { mutableStateOf(false) }
    fun pickMetric(m: GoalMetric) {
        if (m == metric) return
        metric = m
        if (m == GoalMetric.DISTINCT_RECORDED_DAYS && period == GoalPeriod.DAY && !editing) period = GoalPeriod.WEEK
        target = when (m) { GoalMetric.DURATION_MS -> 30 * MINUTE; GoalMetric.SESSION_COUNT -> 1; GoalMetric.DISTINCT_RECORDED_DAYS -> 3 }
    }
    val maxDays = if (period == GoalPeriod.MONTH) 31L else 7L
    val daysInvalid = metric == GoalMetric.DISTINCT_RECORDED_DAYS && period == GoalPeriod.DAY

    NotAiSheet(onDismiss, fullHeight = false) {
        SheetHeader(if (editing) L.s(R.string.t_d5627f6a61) else L.s(R.string.t_58e6634696), onDismiss, extra = if (editing) { {
            IconAction(Icon.Trash, L.s(R.string.t_67a887cea0), { confirmRemove = true }, size = 19.dp)
        } } else null)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ActivityField(label ?: Label(L.s(R.string.t_191cacce9c), null, null, false, ActivityGlyphs.custom), { onPickActivity { sel, l -> selection = sel; label = l; activityChanged = true } },
                if (selection == null) L.s(R.string.t_fe4b0c80f0) else L.s(R.string.t_9d809f884b))
            if (selection != null) Text(L.s(R.string.t_5e94c97f99), Modifier.heightIn(min = 32.dp).pressable({ selection = null; label = null; activityChanged = true }).padding(top = 4.dp),
                style = NotAi.type.caption.copy(fontWeight = FontWeight.Medium), color = c.amberInk)
            Field(L.s(R.string.t_9b4ee5c556)) {
                Segmented(listOf(GoalMetric.DURATION_MS to L.s(R.string.t_c80d7e8172), GoalMetric.SESSION_COUNT to L.s(R.string.t_596144b0e6), GoalMetric.DISTINCT_RECORDED_DAYS to L.s(R.string.t_46a1378615)),
                    metric, ::pickMetric, Modifier.fillMaxWidth())
            }
            Field(L.s(R.string.t_b2822e2bb3)) {
                if (editing) Text("${when (period) { GoalPeriod.DAY -> L.s(R.string.t_b6ef08401f); GoalPeriod.WEEK -> L.s(R.string.t_9207563dab); GoalPeriod.MONTH -> L.s(R.string.t_e558fb6e24); GoalPeriod.FIXED -> L.s(R.string.t_90d0002e66) }}. " +
                    L.s(R.string.t_d4a88f5588), style = NotAi.type.bodySmall, color = c.ink2)
                else Segmented(listOf(GoalPeriod.DAY to L.s(R.string.t_b6ef08401f), GoalPeriod.WEEK to L.s(R.string.t_9207563dab), GoalPeriod.MONTH to L.s(R.string.t_e558fb6e24)), period, {
                    period = it; if (metric == GoalMetric.DISTINCT_RECORDED_DAYS) target = target.coerceAtMost(if (it == GoalPeriod.MONTH) 31 else 7)
                }, Modifier.fillMaxWidth())
            }
            Field(L.s(R.string.t_d8e26625e7)) {
                Segmented(listOf(GoalDirection.MINIMUM to L.s(R.string.t_0865c10035), GoalDirection.LIMIT to L.s(R.string.t_476c9846a6)), direction, { direction = it }, Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.sunk).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                fun step(up: Boolean) {
                    target = when (metric) {
                        GoalMetric.DURATION_MS -> {
                            val h = 60 * MINUTE
                            val s = if (up) (if (target < 2 * h) 15 * MINUTE else if (target < 6 * h) 30 * MINUTE else h)
                                else (if (target <= 2 * h) 15 * MINUTE else if (target <= 6 * h) 30 * MINUTE else h)
                            (if (up) target + s else target - s).coerceIn(15 * MINUTE, (if (period == GoalPeriod.DAY) 24L else if (period == GoalPeriod.WEEK) 7 * 24L else 31 * 24L) * 60 * MINUTE)
                        }
                        GoalMetric.SESSION_COUNT -> (if (up) target + 1 else target - 1).coerceIn(1, 99)
                        GoalMetric.DISTINCT_RECORDED_DAYS -> (if (up) target + 1 else target - 1).coerceIn(1, maxDays)
                    }
                }
                IconAction(Icon.Minus, L.s(R.string.t_766e7cabf7), { step(false) }, tint = c.ink, size = 18.dp)
                Text(Format.goalRule(metric, direction, period, target).replaceFirstChar { it.uppercase() }, Modifier.weight(1f).semantics { contentDescription = L.s(R.string.t_474488d799, Format.goalRule(metric, direction, period, target)) },
                    style = NotAi.type.body.copy(fontWeight = FontWeight.Medium), color = c.ink, textAlign = TextAlign.Center)
                IconAction(Icon.Plus, L.s(R.string.t_b371499aaa), { step(true) }, tint = c.ink, size = 18.dp)
            }
            if (daysInvalid) Note(L.s(R.string.t_488cfa5f88))
            if (editing && period != GoalPeriod.FIXED) Field(L.s(R.string.t_137cb582b9)) {
                val (next, now) = when (period) {
                    GoalPeriod.DAY -> L.s(R.string.t_b84fd84e60) to L.s(R.string.t_1425330b29)
                    GoalPeriod.WEEK -> L.s(R.string.t_d458ec4c77) to L.s(R.string.t_3fa9f851cc)
                    else -> L.s(R.string.t_c7d650974b) to L.s(R.string.t_30796d2694)
                }
                Segmented(listOf(GoalEditMode.NEXT_PERIOD to next, GoalEditMode.CORRECT_CURRENT to now), mode, { mode = it }, Modifier.fillMaxWidth())
                Text(if (mode == GoalEditMode.NEXT_PERIOD) L.s(R.string.t_853744f144) else L.s(R.string.t_c33b203048),
                    Modifier.padding(top = 6.dp), style = NotAi.type.caption, color = c.ink2)
            }
        }
        PillButton(stringResource(R.string.editorial_tracker_draft_8f4fc15539624b530640_v0), {
            vm.saveGoal(GoalDraft(initial?.id, label?.title ?: L.s(R.string.t_c12ee51dd9), selection, label, metric, direction, period, target, initial?.originalFilter, activityChanged),
                if (initial?.period == GoalPeriod.FIXED) GoalEditMode.CORRECT_CURRENT else mode)
            onDismiss()
        }, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp), ButtonKind.PRIMARY, enabled = !daysInvalid)
    }
    if (confirmRemove && initial?.id != null) ConfirmDialog(L.s(R.string.t_3da1c7d83d), L.s(R.string.t_e0997b4d01), L.s(R.string.t_9c7449bfd1),
        { vm.deleteGoal(initial.id); confirmRemove = false; onDismiss() }, { confirmRemove = false }, destructive = true)
}

@Composable
private fun Field(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = NotAi.type.caption, color = NotAi.colors.ink2)
        content()
    }
}

/** A readable summary of a period, built from the overview's own report. Shown in full before it leaves the phone; notes only when asked. */
@Composable
fun ShareSheet(vm: TrackerViewModel, initial: Period, onDismiss: () -> Unit) {
    val c = NotAi.colors
    val context = LocalContext.current
    var period by rememberSaveable { mutableStateOf(initial) }
    var notes by rememberSaveable { mutableStateOf(false) }
    val text by produceState<String?>(null, period, notes) { value = vm.summary(period, notes) }
    NotAiSheet(onDismiss, fullHeight = false) {
        SheetHeader(L.s(R.string.t_838a5669f7), onDismiss)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Segmented(Period.entries.map { it to it.title }, period, { period = it }, Modifier.fillMaxWidth(), height = 36.dp)
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 300.dp).clip(RoundedCornerShape(14.dp)).background(c.sunk)
                .verticalScroll(rememberScrollState()).padding(14.dp)) {
                Text(text.orEmpty(), style = NotAi.type.bodySmall, color = c.ink)
            }
            Row(Modifier.fillMaxWidth().pressable({ notes = !notes }, label = L.s(R.string.t_62c204df09)), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(L.s(R.string.t_62c204df09), style = NotAi.type.body, color = c.ink)
                    Text(L.s(R.string.t_8abcbc93e4), style = NotAi.type.caption, color = c.ink2)
                }
                NotAiSwitch(notes, { notes = it }, L.s(R.string.t_62c204df09))
            }
            PillButton(L.s(R.string.t_76dcf737a6), {
                val body = text ?: return@PillButton
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
                runCatching { context.startActivity(Intent.createChooser(send, L.s(R.string.t_838a5669f7))) }
                onDismiss()
            }, Modifier.fillMaxWidth().padding(bottom = 20.dp), ButtonKind.PRIMARY, Icon.Share, enabled = text != null)
        }
    }
}

/** Focus lengths from a long press on Home. A running phase keeps the length it started with; the next one uses the new values. */
@Composable
fun FocusEditorSheet(vm: TrackerViewModel, plan: dev.notai.tracker.FocusPlan, running: Boolean, onDismiss: () -> Unit) {
    val c = NotAi.colors
    var work by rememberSaveable { mutableIntStateOf((plan.workMs / 60_000L).toInt()) }
    var rest by rememberSaveable { mutableIntStateOf((plan.breakMs / 60_000L).toInt()) }
    NotAiSheet(onDismiss, fullHeight = false) {
        SheetHeader(stringResource(R.string.editorial_tracker_draft_1d9d344d38d034a749fa_v0), onDismiss)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MinuteStepper(L.s(R.string.t_75436e3162), work, { work = (if (work > 10) work - 5 else work - 1).coerceAtLeast(5) }, { work = (if (work >= 10) work + 5 else work + 1).coerceAtMost(180) })
            MinuteStepper(L.s(R.string.t_735877a9aa), rest, { rest = (if (rest > 5) rest - 5 else rest - 1).coerceAtLeast(1) }, { rest = (if (rest >= 5) rest + 5 else rest + 1).coerceAtMost(60) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(25 to 5, 50 to 10, 90 to 15).forEach { (w, b) -> DateChip("$w / $b", work == w && rest == b) { work = w; rest = b } }
            }
            Text(if (running) L.s(R.string.t_fd3cb2ebef) else L.s(R.string.t_6f65bc34a3),
                style = NotAi.type.caption, color = c.ink2)
        }
        ButtonPair(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp)) { m ->
            PillButton(stringResource(R.string.editorial_tracker_draft_8f4fc15539624b530640_v0), { vm.configureFocus(work, rest); onDismiss() }, m, ButtonKind.TONAL)
            if (!running) PillButton(stringResource(R.string.editorial_tracker_draft_5799c96ef993116ab577_v0), { vm.configureAndStartFocus(work, rest); onDismiss() }, m, ButtonKind.PRIMARY)
        }
    }
}

@Composable
private fun MinuteStepper(title: String, minutes: Int, minus: () -> Unit, plus: () -> Unit) {
    val c = NotAi.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.sunk).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = NotAi.type.body, color = c.ink)
        IconAction(Icon.Minus, L.s(R.string.t_1fcbdeecfd, title), minus, tint = c.ink, size = 16.dp)
        Text(L.s(R.string.t_455a50db4a, minutes), Modifier.widthIn(min = 64.dp).semantics { contentDescription = L.s(R.string.t_cf113217ae, title, minutes) },
            style = NotAi.type.body.copy(fontWeight = FontWeight.Medium), color = c.ink, textAlign = TextAlign.Center)
        IconAction(Icon.Plus, L.s(R.string.t_4ef8132cf3, title), plus, tint = c.ink, size = 16.dp)
    }
}
