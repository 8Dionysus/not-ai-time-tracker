package dev.notai.tracker.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.notai.tracker.Classification
import dev.notai.tracker.ReportWindow
import dev.notai.tracker.ReportWindowKind
import dev.notai.tracker.SavedReportQuery

/** Periods a saved report can follow; each is resolved again on every opening. */
private val reportWindows = listOf(
    ReportWindow(ReportWindowKind.CURRENT_WEEK) to "Эта неделя",
    ReportWindow(ReportWindowKind.PREVIOUS_WEEK) to "Прошлая неделя",
    ReportWindow(ReportWindowKind.CURRENT_MONTH) to "Этот месяц",
    ReportWindow(ReportWindowKind.PREVIOUS_MONTH) to "Прошлый месяц",
    ReportWindow(ReportWindowKind.TRAILING_DAYS, 7) to "7 дней",
    ReportWindow(ReportWindowKind.TRAILING_DAYS, 30) to "30 дней",
    ReportWindow(ReportWindowKind.TRAILING_DAYS, 90) to "90 дней",
)
internal fun windowTitle(w: ReportWindow) = reportWindows.firstOrNull { it.first == w }?.second ?: "${w.days} дней"

/** «Свои отчёты» on Overview: one line each with its current total; a quiet row adds one. */
@Composable
fun ColumnScope.SavedReportsSection(vm: TrackerViewModel, reports: List<SavedReportUi>, open: (SavedReportUi) -> Unit, create: () -> Unit) {
    val c = NotAi.colors
    if (reports.isNotEmpty()) Text("Свои отчёты", Modifier.padding(top = 18.dp, bottom = 4.dp), style = NotAi.type.section, color = c.ink)
    reports.forEach { r ->
        ListRow(r.query.name, subtitle = "${windowTitle(r.query.window)}, ${r.window}", onClick = { open(r) },
            leading = { M3Icon(Icon.TabOverview, null, Modifier.size(18.dp), tint = c.ink2) },
            trailing = { Text(Format.duration(r.totalMs), style = NotAi.type.bodySmall, color = c.ink) })
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).pressable(create, label = "Свой отчёт"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        M3Icon(Icon.Plus, null, Modifier.size(18.dp), tint = c.ink2)
        Text(if (reports.isEmpty()) "Свой отчёт: занятие и период" else "Ещё отчёт", style = NotAi.type.bodySmall, color = c.ink2)
    }
}

/** An opened report: total for its period now, one line per own activity, then edit or delete. */
@Composable
fun SavedReportSheet(r: SavedReportUi, onEdit: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    val c = NotAi.colors
    var confirm by remember { mutableStateOf(false) }
    NotAiSheet(onDismiss, fullHeight = false) {
        SheetHeader(r.query.name, onDismiss)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${windowTitle(r.query.window)}: ${r.window}" + if (r.incomplete) ", период ещё идёт" else "", style = NotAi.type.bodySmall, color = c.ink2)
            Text(Format.duration(r.totalMs), style = NotAi.type.big, color = c.ink)
            r.filterLabel?.let { Text("Занятие: ${it.title}" + (it.detail?.let { d -> " · $d" } ?: ""), style = NotAi.type.bodySmall, color = c.ink2) }
                ?: Text("Все занятия", style = NotAi.type.bodySmall, color = c.ink2)
            if (r.missing) Note("Занятие этого отчёта удалено или в архиве. Отчёт считает только его прошлые записи.")
            if (r.parts.size > 1) {
                Spacer(Modifier.height(6.dp))
                val max = r.parts.maxOf { it.second }.coerceAtLeast(1)
                r.parts.forEach { (name, ms) -> AreaLine(name, ms, max) }
            }
            Spacer(Modifier.height(8.dp))
            ButtonPair(spacing = 8.dp) { m ->
                PillButton("Изменить", onEdit, m, ButtonKind.TONAL, height = 44.dp)
                PillButton("Удалить", { confirm = true }, m, ButtonKind.TEXT, height = 44.dp)
            }
        }
    }
    if (confirm) ConfirmDialog("Удалить отчёт «${r.query.name}»?", "Удалится только сохранённый отчёт. Записи не меняются.", "Удалить",
        { confirm = false; onDelete() }, { confirm = false }, destructive = true)
}

@Composable
private fun AreaLine(name: String, ms: Long, max: Long) {
    val c = NotAi.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), style = NotAi.type.bodySmall, color = c.ink)
            Text(Format.duration(ms), style = NotAi.type.bodySmall, color = c.ink)
        }
        Box(Modifier.fillMaxWidth().height(4.dp).background(c.sunk, androidx.compose.foundation.shape.RoundedCornerShape(2.dp))) {
            Box(Modifier.fillMaxWidth(ms.toFloat() / max).fillMaxHeight().background(c.ink2, androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
        }
    }
}

/** New or edited report: what to count and over which period. The name follows the activity until typed. */
@Composable
fun SavedReportEditor(vm: TrackerViewModel, existing: SavedReportUi?, pickActivity: ((Classification, Label) -> Unit) -> Unit, onDismiss: () -> Unit) {
    val c = NotAi.colors
    var selection by remember { mutableStateOf(existing?.query?.filter?.let { f -> f.cardId?.let { Classification(cardId = it) } ?: f.activityTypeId?.let { Classification(activityTypeId = it) } }) }
    var label by remember { mutableStateOf(existing?.filterLabel?.title) }
    var window by remember { mutableStateOf(existing?.query?.window ?: ReportWindow(ReportWindowKind.CURRENT_MONTH)) }
    var name by remember { mutableStateOf(existing?.query?.name ?: "") }
    var typed by remember { mutableStateOf(existing != null) }
    val shownName = if (typed) name else listOfNotNull(label ?: "Все занятия", windowTitle(window).lowercase()).joinToString(", ")
    NotAiSheet(onDismiss, fullHeight = false) {
        SheetHeader(if (existing == null) "Свой отчёт" else "Изменить отчёт", onDismiss)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Что считать", style = NotAi.type.caption, color = c.ink2)
                ListRow(label ?: "Все занятия", subtitle = if (label == null) "Нажми, чтобы выбрать одно занятие или раздел" else "Вместе со всем, что внутри",
                    onClick = { pickActivity { sel, l -> selection = sel.copy(name = null, details = emptyMap()); label = l.title } },
                    trailing = { if (label != null) IconAction(Icon.Close, "Все занятия", { selection = null; label = null }, size = 16.dp) })
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Период", style = NotAi.type.caption, color = c.ink2)
                reportWindows.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (w, t) -> PeriodPill(t, w == window, Modifier.weight(1f)) { window = w } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            TextInput("Название", shownName, { name = it; typed = true })
            PillButton("Сохранить", { vm.saveReport(existing?.query, shownName.trim().ifBlank { "Отчёт" }, selection, window); onDismiss() },
                Modifier.fillMaxWidth(), ButtonKind.PRIMARY)
        }
    }
}

@Composable
private fun PeriodPill(text: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = NotAi.colors
    Box(modifier.heightIn(min = 44.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
        .background(if (on) c.ink else c.sunk).semantics { selected = on }
        .pressable(onClick, role = androidx.compose.ui.semantics.Role.RadioButton, label = text), contentAlignment = Alignment.Center) {
        Text(text, style = NotAi.type.bodySmall.copy(fontWeight = FontWeight.Medium), color = if (on) c.paper else c.ink)
    }
}
