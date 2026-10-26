package dev.notai.tracker.android.ui


import dev.notai.tracker.android.R
import dev.notai.tracker.android.L
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.notai.tracker.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Date/time layouts offered for files without a time zone. The file's own example decides the preselection. */
internal val csvPatterns get() = listOf(
    null to L.s(R.string.t_f62e9fd860),
    "uuuu-MM-dd HH:mm:ss" to "2026-10-13 14:05:00",
    "uuuu-MM-dd HH:mm" to "2026-10-13 14:05",
    "dd.MM.uuuu HH:mm:ss" to "13.10.2026 14:05:00",
    "dd.MM.uuuu HH:mm" to "13.10.2026 14:05",
    "MM/dd/uuuu HH:mm:ss" to "10/13/2026 14:05:00",
    "MM/dd/uuuu HH:mm" to "10/13/2026 14:05",
)

/** First layout that reads the example, so the usual case needs no choice; the person still sees and confirms it. */
internal fun guessPattern(example: String?): String? {
    val v = example?.trim().orEmpty()
    if (v.isEmpty() || runCatching { OffsetDateTime.parse(v) }.isSuccess || runCatching { Instant.parse(v) }.isSuccess) return null
    return csvPatterns.mapNotNull { it.first }.firstOrNull { p ->
        runCatching { LocalDateTime.parse(v, DateTimeFormatter.ofPattern(p, java.util.Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)) }.isSuccess
    } ?: "uuuu-MM-dd HH:mm:ss"
}

/**
 * Adds history from a CSV export (Toggl, Clockify or a table of one's own). Two steps in one sheet:
 * which columns mean what and how time is written, then which tracker activity each source activity becomes.
 * The preview shows exactly what will be added; nothing is written before «Добавить».
 */
@Composable
fun CsvImportSheet(vm: TrackerViewModel, ui: CsvImportUi, zone: ZoneId, onPickActivity: ((Classification, Label) -> Unit) -> Unit, onDismiss: () -> Unit,
                   previewCsv: suspend (TransferFile,Map<SourceActivity,Classification>)->Result<HistoryPreview> = vm::previewCsv) {
    val c = NotAi.colors
    val candidates = ui.inspection.candidates
    var delimiter by remember { mutableStateOf(candidates.first().delimiter) }
    val table = candidates.first { it.delimiter == delimiter }
    fun suggested(f: ImportField) = table.suggestions[f]?.firstOrNull()
    fun example(column: String?) = column?.let { col -> table.columns.indexOf(col).takeIf { it >= 0 }?.let { i -> table.examples.firstOrNull()?.getOrNull(i) } }
    var activity by remember(delimiter) { mutableStateOf(suggested(ImportField.ACTIVITY)) }
    var category by remember(delimiter) { mutableStateOf(suggested(ImportField.CATEGORY)) }
    var start by remember(delimiter) { mutableStateOf(suggested(ImportField.START)) }
    var startTime by remember(delimiter) { mutableStateOf(suggested(ImportField.START_TIME)) }
    var end by remember(delimiter) { mutableStateOf(suggested(ImportField.END)) }
    var endTime by remember(delimiter) { mutableStateOf(suggested(ImportField.END_TIME)) }
    var duration by remember(delimiter) { mutableStateOf(suggested(ImportField.DURATION)) }
    var note by remember(delimiter) { mutableStateOf(suggested(ImportField.NOTE)) }
    var id by remember(delimiter) { mutableStateOf(suggested(ImportField.ID)) }
    val startExample = listOfNotNull(example(start), example(startTime)).joinToString(" ").ifBlank { null }
    var pattern by remember(delimiter, start, startTime) { mutableStateOf(guessPattern(startExample)) }
    var fileZone by remember { mutableStateOf(zone.id) }
    var format by remember(delimiter) { mutableStateOf(DurationFormat.HMS) }
    var namespace by rememberSaveable { mutableStateOf("") }
    var step by remember { mutableIntStateOf(0) }
    var parsed by remember { mutableStateOf<TransferFile?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val targets = remember { mutableStateMapOf<SourceActivity, Pair<Classification, String>>() }
    var preview by remember { mutableStateOf<HistoryPreview?>(null) }
    var parsing by remember { mutableStateOf(false) }
    var previewing by remember { mutableStateOf(false) }
    var previewGeneration by remember { mutableIntStateOf(0) }
    val working = parsing || previewing

    val ready = namespace.isNotBlank() && activity != null && start != null && (end != null || duration != null)
    fun mapping() = CsvMapping(namespace.trim(), activity!!, start!!, end = end, duration = duration, durationFormat = format, category = category, note = note,
        id = id, startTime = startTime, endTime = endTime, localPattern = pattern, zone = if (pattern == null) "UTC" else fileZone, delimiter = delimiter)
    LaunchedEffect(step, parsed, targets.toMap()) {
        val generation = ++previewGeneration
        preview = null
        previewing = false
        val file = parsed ?: return@LaunchedEffect
        if (step != 1) return@LaunchedEffect
        previewing = true
        try {
            delay(150)
            val result = previewCsv(file,targets.mapValues { it.value.first })
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if(generation == previewGeneration) result.onSuccess { preview = it; problem = null }
                .onFailure { preview = null; problem = Format.importError(it) }
        } finally { if(generation == previewGeneration) previewing = false }
    }

    NotAiSheet(onDismiss) {
        SheetHeader(if (step == 0) L.s(R.string.t_ab0662026d) else L.s(R.string.t_8e273a8a05), onDismiss,
            back = if (step == 1) ({ previewGeneration++;previewing = false;step = 0;preview = null }) else null)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step == 0) {
                item {
                    Text(L.s(R.string.t_1fce9b2e5d, ui.fileName ?: L.s(R.string.t_94b8df93b6), L.p(R.plurals.p_de4636ae79, table.columns.size)) +
                        L.s(R.string.t_fc355fea11), style = NotAi.type.caption, color = c.ink2)
                }
                if (candidates.size > 1) item {
                    Labeled2(L.s(R.string.t_afb9a7edd6)) {
                        Segmented(candidates.map { it.delimiter to when (it.delimiter) { '\t' -> L.s(R.string.t_c0b04d97ff); ',' -> L.s(R.string.t_eb467ef147); ';' -> L.s(R.string.t_782c5a40e3); else -> it.delimiter.toString() } },
                            delimiter, { delimiter = it }, Modifier.fillMaxWidth())
                    }
                }
                item {
                    TextInput(L.s(R.string.t_d9e72beb20),namespace,{ namespace = it },placeholder = L.s(R.string.t_4a81786403))
                    Text(L.s(R.string.t_84707b7a44),style = NotAi.type.caption,color = c.ink2)
                }
                item { ColumnPick(L.s(R.string.t_70b8d5d8d5), table, activity, required = true) { activity = it } }
                item { ColumnPick(L.s(R.string.t_d149e0338b), table, category) { category = it } }
                item { ColumnPick(L.s(R.string.t_cb26bdc6c6), table, start, required = true) { start = it } }
                item { ColumnPick(L.s(R.string.t_f8310658c7), table, startTime) { startTime = it } }
                item { ColumnPick(L.s(R.string.t_4e895fd70d), table, end) { end = it } }
                item { ColumnPick(L.s(R.string.t_654fad1c18), table, endTime) { endTime = it } }
                item { ColumnPick(L.s(R.string.t_d4168ace06), table, duration) { duration = it } }
                if (duration != null) item {
                    Labeled2(L.s(R.string.t_11d7afb599)) {
                        Segmented(listOf(DurationFormat.HMS to "1:30:00", DurationFormat.DECIMAL_HOURS to L.s(R.string.t_820b711796), DurationFormat.SECONDS to L.s(R.string.t_86ee6df7f5),
                            DurationFormat.MILLISECONDS to L.s(R.string.t_bbb0e8e8cd)), format, { format = it }, Modifier.fillMaxWidth())
                    }
                }
                item { ColumnPick(L.s(R.string.t_917b58362c), table, note) { note = it } }
                item {
                    Labeled2(L.s(R.string.t_a5515a2b98) + (startExample?.let { L.s(R.string.t_8633c34c71, it) } ?: "")) {
                        PatternPick(pattern) { pattern = it }
                        if (pattern != null) Segmented(listOf(zone.id to L.s(R.string.t_914b6dca6d), "UTC" to "UTC"), fileZone, { fileZone = it }, Modifier.fillMaxWidth())
                        Text(if (pattern == null) L.s(R.string.t_80e474035b) else L.s(R.string.t_1b702dcc70, fileZone.replace('_', ' ')),
                            style = NotAi.type.caption, color = c.ink2)
                    }
                }
                problem?.let { p -> item { Text(p, style = NotAi.type.caption, color = c.amberInk) } }
                if (!ready) item { Text(L.s(R.string.t_fe68cb28be), style = NotAi.type.caption, color = c.ink2) }
            } else {
                val file = parsed
                if (file != null) {
                    item { SectionLabel(L.s(R.string.t_91d993d35c)) }
                    items(file.activities, key = { it.activity + "\u0000" + it.category }) { a ->
                        val count = file.rows.count { it.activity == a }
                        val target = targets[a]
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).pressable({ onPickActivity { sel, label -> targets[a] = sel to label.title } },
                            label = L.s(R.string.t_6e65291421, a.activity)), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(a.activity.ifBlank { L.s(R.string.t_32b74a3c47) } + (a.category.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                    style = NotAi.type.body, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${L.p(R.plurals.p_09d3aef0c7, count)} → ${target?.second ?: "как в файле"}",
                                    style = NotAi.type.caption, color = if (target != null) c.amberInk else c.ink2)
                            }
                            if (target != null) IconAction(Icon.Close, L.s(R.string.t_9e512e4de6), { targets.remove(a) }, size = 16.dp)
                            else M3Icon(Icon.ChevronRight, null, Modifier.size(16.dp), tint = c.ink3)
                        }
                    }
                }
                item { SectionLabel(L.s(R.string.t_457969b009), Modifier.padding(top = 8.dp)) }
                val p = preview
                when {
                    working && p == null -> item { Text(L.s(R.string.t_852778be6d), style = NotAi.type.caption, color = c.ink2) }
                    p != null -> {
                        item {
                            Text(buildString {
                                append(L.s(R.string.t_d2e9881a72, L.p(R.plurals.p_6c73bc4d74, p.added)))
                                if (p.duplicates > 0) append(L.s(R.string.t_e962d6d168, p.duplicates))
                                if (p.errorIssues.isNotEmpty()) append(L.s(R.string.t_49f64bd23b, p.errorIssues.size))
                            }, style = NotAi.type.body.copy(fontWeight = FontWeight.Medium), color = c.ink)
                        }
                        items(p.errorIssues.take(8)) { e -> Text(Format.importIssue(e), style = NotAi.type.caption, color = c.amberInk) }
                        items(p.intervals.sortedBy { it.start }.take(12), key = { it.id }) { seg ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.sunk).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text("${Format.dayMonth(Instant.ofEpochMilli(seg.start).atZone(zone).toLocalDate())}, ${Format.clock(seg.start, zone)}–${seg.end?.let { Format.clock(it, zone) } ?: ""}",
                                    Modifier.padding(end = 12.dp), style = NotAi.type.caption, color = c.ink2, maxLines = 1, softWrap = false)
                                Text(seg.selection.name ?: targets.values.firstOrNull { it.first == seg.selection }?.second ?: L.s(R.string.t_aff922a637),
                                    style = NotAi.type.caption, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (p.added > 12) item { Text(L.s(R.string.t_a773a5edc6, p.added - 12), style = NotAi.type.caption, color = c.ink3) }
                        item { Text(L.s(R.string.t_e699415c6e),
                            style = NotAi.type.caption, color = c.ink2) }
                    }
                    else -> problem?.let { pr -> item { Text(pr, style = NotAi.type.caption, color = c.amberInk) } }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            if (step == 0) PillButton(if (working) L.s(R.string.t_4784c38b90) else L.s(R.string.t_e3b9331301), {
                parsing = true
                vm.launchParse(ui, delimiter, mapping(), duration?.let { format }) { result ->
                    parsing = false
                    result.onSuccess { f ->
                        if (f.rows.isEmpty()) problem = f.errorIssues.firstOrNull()?.let(Format::importIssue) ?: L.s(R.string.t_9de49a7593)
                        else { parsed = f;problem = null;step = 1 }
                    }.onFailure { problem = Format.importError(it) }
                }
            }, Modifier.fillMaxWidth(), ButtonKind.PRIMARY, enabled = ready && !working)
            else PillButton(preview?.let { L.s(R.string.t_a94e60234f, L.p(R.plurals.p_6c73bc4d74, it.added)) } ?: L.s(R.string.t_559a87f7cc), {
                preview?.let(vm::applyCsv)
            }, Modifier.fillMaxWidth(), ButtonKind.PRIMARY, enabled = preview?.let { it.canApply && it.added > 0 } == true && !working)
        }
    }
}

@Composable
private fun Labeled2(title: String, content: @Composable ColumnScope.() -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, style = NotAi.type.caption.copy(fontWeight = FontWeight.Medium), color = NotAi.colors.ink2); content()
}

/** A column choice with the file's first value beside it, so «Начало» can be checked against «2026-10-13 14:05». */
@Composable
private fun ColumnPick(title: String, table: ColumnInspection, value: String?, required: Boolean = false, onPick: (String?) -> Unit) {
    val c = NotAi.colors
    var open by remember { mutableStateOf(false) }
    fun sample(col: String) = table.columns.indexOf(col).takeIf { it >= 0 }?.let { i -> table.examples.firstOrNull()?.getOrNull(i) }?.takeIf { it.isNotBlank() }
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(c.sunk)
            .pressable({ open = true }, label = "$title: ${value ?: "нет"}").padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title + if (required) "" else "", style = NotAi.type.caption, color = c.ink2)
                Text(value?.let { v -> v + (sample(v)?.let { " · $it" } ?: "") } ?: L.s(R.string.t_210ed3cd4d), style = NotAi.type.body,
                    color = if (value == null) c.ink3 else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            M3Icon(Icon.ChevronDown, null, Modifier.size(16.dp), tint = c.ink3)
        }
        DropdownMenu(open, { open = false }, containerColor = c.raised) {
            if (!required) DropdownMenuItem({ Text(L.s(R.string.t_210ed3cd4d), color = c.ink2) }, { onPick(null); open = false })
            table.columns.forEach { col ->
                DropdownMenuItem({ Text(col + (sample(col)?.let { " · $it" } ?: ""), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    { onPick(col); open = false })
            }
        }
    }
}

@Composable
private fun PatternPick(pattern: String?, onPick: (String?) -> Unit) {
    val c = NotAi.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(c.sunk)
            .pressable({ open = true }, label = L.s(R.string.t_747942b4fa)).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(csvPatterns.firstOrNull { it.first == pattern }?.second ?: pattern.orEmpty(), Modifier.weight(1f), style = NotAi.type.body, color = c.ink)
            M3Icon(Icon.ChevronDown, null, Modifier.size(16.dp), tint = c.ink3)
        }
        DropdownMenu(open, { open = false }, containerColor = c.raised) {
            csvPatterns.forEach { (p, label) -> DropdownMenuItem({ Text(label, color = c.ink) }, { onPick(p); open = false }) }
        }
    }
}
