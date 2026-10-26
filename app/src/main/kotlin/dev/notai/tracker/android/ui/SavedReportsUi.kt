package dev.notai.tracker.android.ui


import dev.notai.tracker.android.L
import dev.notai.tracker.android.R

import androidx.compose.ui.res.stringResource

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
private val reportWindows get() = listOf(
    ReportWindow(ReportWindowKind.CURRENT_WEEK) to L.s(R.string.t_19295833d9),
    ReportWindow(ReportWindowKind.PREVIOUS_WEEK) to L.s(R.string.t_2373dd4f8d),
    ReportWindow(ReportWindowKind.CURRENT_MONTH) to L.s(R.string.t_a0113f46a3),
    ReportWindow(ReportWindowKind.PREVIOUS_MONTH) to L.s(R.string.t_86652580fd),
    ReportWindow(ReportWindowKind.TRAILING_DAYS, 7) to L.s(R.string.t_e65da6ce30),
    ReportWindow(ReportWindowKind.TRAILING_DAYS, 30) to L.s(R.string.t_fb925c5d79),
    ReportWindow(ReportWindowKind.TRAILING_DAYS, 90) to L.s(R.string.t_c85eb5f581),
)
internal fun windowTitle(w: ReportWindow) = reportWindows.firstOrNull { it.first == w }?.second ?: L.s(R.string.t_617d07376f, w.days)

/** «Свои отчёты» on Overview: one line each with its current total; a quiet row adds one. */
@Composable
fun ColumnScope.SavedReportsSection(vm: TrackerViewModel, reports: List<SavedReportUi>, open: (SavedReportUi) -> Unit, create: () -> Unit) {
    val c = NotAi.colors
    if (reports.isNotEmpty()) Text(L.s(R.string.t_c2bf13bbb1), Modifier.padding(top = 18.dp, bottom = 4.dp), style = NotAi.type.section, color = c.ink)
    reports.forEach { r ->
        ListRow(r.query.name, subtitle = "${windowTitle(r.query.window)}, ${r.window}", onClick = { open(r) },
            leading = { M3Icon(Icon.TabOverview, null, Modifier.size(18.dp), tint = c.ink2) },
            trailing = { Text(Format.duration(r.totalMs), style = NotAi.type.bodySmall, color = c.ink) })
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).pressable(create, label = L.s(R.string.t_9924fc6aa9)),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        M3Icon(Icon.Plus, null, Modifier.size(18.dp), tint = c.ink2)
        Text(if (reports.isEmpty()) L.s(R.string.t_6d4aeaf49a) else L.s(R.string.t_f3ff661a74), style = NotAi.type.bodySmall, color = c.ink2)
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
            Text("${windowTitle(r.query.window)}: ${r.window}" + if (r.incomplete) L.s(R.string.t_6257137741) else "", style = NotAi.type.bodySmall, color = c.ink2)
            Text(Format.duration(r.totalMs), style = NotAi.type.big, color = c.ink)
            r.filterLabel?.let { Text(L.s(R.string.t_1bd48e6642, it.title) + (it.detail?.let { d -> " · $d" } ?: ""), style = NotAi.type.bodySmall, color = c.ink2) }
                ?: Text(stringResource(R.string.editorial_tracker_draft_d0f2db9385d5fd9998ae_v0), style = NotAi.type.bodySmall, color = c.ink2)
            if (r.missing) Note(L.s(R.string.t_d015e9be93))
            if (r.parts.size > 1) {
                Spacer(Modifier.height(6.dp))
                val max = r.parts.maxOf { it.second }.coerceAtLeast(1)
                r.parts.forEach { (name, ms) -> AreaLine(name, ms, max) }
            }
            Spacer(Modifier.height(8.dp))
            ButtonPair(spacing = 8.dp) { m ->
                PillButton(stringResource(R.string.editorial_tracker_draft_a723640e7ed84719343f_v0), onEdit, m, ButtonKind.TONAL, height = 44.dp)
                PillButton(stringResource(R.string.editorial_tracker_draft_98c2a48d88776822811d_v0), { confirm = true }, m, ButtonKind.TEXT, height = 44.dp)
            }
        }
    }
    if (confirm) ConfirmDialog(L.s(R.string.t_964e56ab98, r.query.name), L.s(R.string.t_f48645430a), L.s(R.string.t_86ea33aef5),
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
    val shownName = if (typed) name else listOfNotNull(label ?: L.s(R.string.t_c0634e4cb3), windowTitle(window).lowercase()).joinToString(", ")
    NotAiSheet(onDismiss, fullHeight = false) {
        SheetHeader(if (existing == null) L.s(R.string.t_9924fc6aa9) else L.s(R.string.t_164b8bb2af), onDismiss)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(L.s(R.string.t_9b4ee5c556), style = NotAi.type.caption, color = c.ink2)
                ListRow(label ?: L.s(R.string.t_c0634e4cb3), subtitle = if (label == null) L.s(R.string.t_ed52dd3411) else L.s(R.string.t_5fb4185cca),
                    onClick = { pickActivity { sel, l -> selection = sel.copy(name = null, details = emptyMap()); label = l.title } },
                    trailing = { if (label != null) IconAction(Icon.Close, L.s(R.string.t_c0634e4cb3), { selection = null; label = null }, size = 16.dp) })
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(L.s(R.string.t_b2822e2bb3), style = NotAi.type.caption, color = c.ink2)
                reportWindows.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { (w, t) -> PeriodPill(t, w == window, Modifier.weight(1f)) { window = w } }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            TextInput(L.s(R.string.t_3de49828e8), shownName, { name = it; typed = true })
            PillButton(stringResource(R.string.editorial_tracker_draft_8f4fc15539624b530640_v0), { vm.saveReport(existing?.query, shownName.trim().ifBlank { L.s(R.string.t_d7748bfad0) }, selection, window); onDismiss() },
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
