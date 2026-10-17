@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.notai.tracker.android.ui

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

private val NO_ACTIVITY = Label("Какое занятие?", null, null, true)

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
        SheetHeader(if (existing == null) "Добавить в план" else "План", onDismiss, extra = existing?.let { {
            IconAction(Icon.Trash, "Убрать из плана", { confirmRemove = true }, size = 19.dp)
        } })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ActivityField(label, { onPickActivity { sel, l -> selection = sel; label = l } }, if (selection == null) "Выбрать" else "Изменить")
            Segmented(listOf(false to "По дням недели", true to "Один день"), once, { once = it }, Modifier.fillMaxWidth())
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
                listOf(today to "Сегодня", today.plusDays(1) to "Завтра").forEach { (d, name) -> DateChip(name, chosen == d) { date = d.toString() } }
                val other = chosen != today && chosen != today.plusDays(1)
                DateChip(if (other) Format.dayMonth(chosen) else "Другой день", other) { calendar = true }
            }
            Row(Modifier.fillMaxWidth().pressable({ timed = !timed }, label = "Ко времени"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Ко времени", style = NotAi.type.body, color = c.ink)
                    Text(if (timed) "Время только для порядка в списке" else "Без времени: просто в этот день", style = NotAi.type.caption, color = c.ink2)
                }
                NotAiSwitch(timed, { timed = it }, "Ко времени")
            }
            if (timed) {
                // TimeField works on instants; any fixed day carries the chosen clock time.
                val base = remember { today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() }
                TimeField("Время", base + minuteOfDay * MINUTE, ZoneOffset.UTC, { minuteOfDay = Math.floorMod(((it - base) / MINUTE).toInt(), 24 * 60) })
            }
            Text("План только показывается на главном экране. Время пойдёт, когда ты нажмёшь на занятие.", style = NotAi.type.caption, color = c.ink2)
        }
        PillButton("Сохранить", {
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
            confirmButton = { PillButton("Готово", {
                state.selectedDateMillis?.let { date = java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }; calendar = false
            }, kind = ButtonKind.TEXT, height = 44.dp) },
            dismissButton = { PillButton("Отмена", { calendar = false }, kind = ButtonKind.TEXT, height = 44.dp) }) {
            DatePicker(state, showModeToggle = false, colors = DatePickerDefaults.colors(containerColor = c.raised,
                selectedDayContainerColor = c.amber, selectedDayContentColor = c.onAmber, todayDateBorderColor = c.amber, todayContentColor = c.ink))
        }
    }
    if (confirmRemove && existing != null) ConfirmDialog("Убрать «${existing.label.title}» из плана?",
        "Записи, уже начатые по плану, останутся в истории.", "Убрать", { vm.removePlan(existing); confirmRemove = false; onDismiss() },
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
        SheetHeader(if (editing) "Цель" else "Новая цель", onDismiss, extra = if (editing) { {
            IconAction(Icon.Trash, "Убрать цель", { confirmRemove = true }, size = 19.dp)
        } } else null)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ActivityField(label ?: Label("Любое занятие", null, null, false, ActivityGlyphs.custom), { onPickActivity { sel, l -> selection = sel; label = l } },
                if (selection == null) "Выбрать" else "Изменить")
            if (selection != null) Text("Считать любое занятие", Modifier.heightIn(min = 32.dp).pressable({ selection = null; label = null }).padding(top = 4.dp),
                style = NotAi.type.caption.copy(fontWeight = FontWeight.Medium), color = c.amberInk)
            Field("Что считать") {
                Segmented(listOf(GoalMetric.DURATION_MS to "Время", GoalMetric.SESSION_COUNT to "Записи", GoalMetric.DISTINCT_RECORDED_DAYS to "Дни"),
                    metric, ::pickMetric, Modifier.fillMaxWidth())
            }
            Field("Период") {
                if (editing) Text("${when (period) { GoalPeriod.DAY -> "День"; GoalPeriod.WEEK -> "Неделя"; GoalPeriod.MONTH -> "Месяц"; GoalPeriod.FIXED -> "Свои даты" }}. " +
                    "Другой период — это новая цель.", style = NotAi.type.bodySmall, color = c.ink2)
                else Segmented(listOf(GoalPeriod.DAY to "День", GoalPeriod.WEEK to "Неделя", GoalPeriod.MONTH to "Месяц"), period, {
                    period = it; if (metric == GoalMetric.DISTINCT_RECORDED_DAYS) target = target.coerceAtMost(if (it == GoalPeriod.MONTH) 31 else 7)
                }, Modifier.fillMaxWidth())
            }
            Field("Граница") {
                Segmented(listOf(GoalDirection.MINIMUM to "Не меньше", GoalDirection.LIMIT to "Не больше"), direction, { direction = it }, Modifier.fillMaxWidth())
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
                IconAction(Icon.Minus, "Меньше", { step(false) }, tint = c.ink, size = 18.dp)
                Text(Format.goalRule(metric, direction, period, target).replaceFirstChar { it.uppercase() }, Modifier.weight(1f).semantics { contentDescription = "Цель: ${Format.goalRule(metric, direction, period, target)}" },
                    style = NotAi.type.body.copy(fontWeight = FontWeight.Medium), color = c.ink, textAlign = TextAlign.Center)
                IconAction(Icon.Plus, "Больше", { step(true) }, tint = c.ink, size = 18.dp)
            }
            if (daysInvalid) Note("Дни считаются за неделю или месяц.")
            if (editing && period != GoalPeriod.FIXED) Field("Когда применить") {
                val (next, now) = when (period) {
                    GoalPeriod.DAY -> "С завтрашнего дня" to "Уже сегодня"
                    GoalPeriod.WEEK -> "Со следующей недели" to "С этой недели"
                    else -> "Со следующего месяца" to "С этого месяца"
                }
                Segmented(listOf(GoalEditMode.NEXT_PERIOD to next, GoalEditMode.CORRECT_CURRENT to now), mode, { mode = it }, Modifier.fillMaxWidth())
                Text(if (mode == GoalEditMode.NEXT_PERIOD) "Текущий период досчитается по прежней цели." else "Текущий период пересчитается по новой цели. Прошлые останутся как были.",
                    Modifier.padding(top = 6.dp), style = NotAi.type.caption, color = c.ink2)
            }
        }
        PillButton("Сохранить", {
            vm.saveGoal(GoalDraft(initial?.id, label?.title ?: "Всё время", selection, label, metric, direction, period, target),
                if (initial?.period == GoalPeriod.FIXED) GoalEditMode.CORRECT_CURRENT else mode)
            onDismiss()
        }, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp), ButtonKind.PRIMARY, enabled = !daysInvalid)
    }
    if (confirmRemove && initial?.id != null) ConfirmDialog("Убрать цель?", "Записи не изменятся, исчезнет только сама цель.", "Убрать",
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
        SheetHeader("Поделиться итогом", onDismiss)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Segmented(Period.entries.map { it to it.title }, period, { period = it }, Modifier.fillMaxWidth(), height = 36.dp)
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 300.dp).clip(RoundedCornerShape(14.dp)).background(c.sunk)
                .verticalScroll(rememberScrollState()).padding(14.dp)) {
                Text(text.orEmpty(), style = NotAi.type.bodySmall, color = c.ink)
            }
            Row(Modifier.fillMaxWidth().pressable({ notes = !notes }, label = "Добавить заметки"), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Добавить заметки", style = NotAi.type.body, color = c.ink)
                    Text("Заметки бывают личными, поэтому по умолчанию их нет", style = NotAi.type.caption, color = c.ink2)
                }
                NotAiSwitch(notes, { notes = it }, "Добавить заметки")
            }
            PillButton("Отправить", {
                val body = text ?: return@PillButton
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
                runCatching { context.startActivity(Intent.createChooser(send, "Поделиться итогом")) }
                onDismiss()
            }, Modifier.fillMaxWidth().padding(bottom = 20.dp), ButtonKind.PRIMARY, Icon.Share, enabled = text != null)
        }
    }
}
