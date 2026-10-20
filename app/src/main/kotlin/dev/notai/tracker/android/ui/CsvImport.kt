package dev.notai.tracker.android.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.notai.tracker.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Date/time layouts offered for files without a time zone. The file's own example decides the preselection. */
internal val csvPatterns = listOf(
    null to "С поясом (ISO)",
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
fun CsvImportSheet(vm: TrackerViewModel, ui: CsvImportUi, zone: ZoneId, onPickActivity: ((Classification, Label) -> Unit) -> Unit, onDismiss: () -> Unit) {
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
    var step by remember { mutableIntStateOf(0) }
    var parsed by remember { mutableStateOf<TransferFile?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val targets = remember { mutableStateMapOf<SourceActivity, Pair<Classification, String>>() }
    var preview by remember { mutableStateOf<HistoryPreview?>(null) }
    var working by remember { mutableStateOf(false) }

    val ready = activity != null && start != null && (end != null || duration != null)
    fun mapping() = CsvMapping("csv:local", activity!!, start!!, end = end, duration = duration, durationFormat = format, category = category, note = note,
        id = id, startTime = startTime, endTime = endTime, localPattern = pattern, zone = if (pattern == null) "UTC" else fileZone, delimiter = delimiter)
    LaunchedEffect(step, parsed, targets.toMap()) {
        val file = parsed ?: return@LaunchedEffect
        if (step != 1) return@LaunchedEffect
        delay(150); working = true
        vm.previewCsv(file, targets.mapValues { it.value.first }).onSuccess { preview = it; problem = null }
            .onFailure { preview = null; problem = Format.importError(it) }
        working = false
    }

    NotAiSheet(onDismiss) {
        SheetHeader(if (step == 0) "История из CSV" else "Занятия и проверка", onDismiss, back = if (step == 1) ({ step = 0; preview = null }) else null)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (step == 0) {
                item {
                    Text("${ui.fileName ?: "Файл"}: ${Format.plural(table.columns.size, "колонка", "колонки", "колонок")}. Проверь, что означает каждая — " +
                        "подсказки уже расставлены по заголовкам.", style = NotAi.type.caption, color = c.ink2)
                }
                if (candidates.size > 1) item {
                    Labeled2("Разделитель") {
                        Segmented(candidates.map { it.delimiter to when (it.delimiter) { '\t' -> "Табуляция"; ',' -> "Запятая"; ';' -> "Точка с запятой"; else -> it.delimiter.toString() } },
                            delimiter, { delimiter = it }, Modifier.fillMaxWidth())
                    }
                }
                item { ColumnPick("Занятие", table, activity, required = true) { activity = it } }
                item { ColumnPick("Категория или клиент", table, category) { category = it } }
                item { ColumnPick("Начало", table, start, required = true) { start = it } }
                item { ColumnPick("Время начала, если отдельной колонкой", table, startTime) { startTime = it } }
                item { ColumnPick("Конец", table, end) { end = it } }
                item { ColumnPick("Время конца, если отдельной колонкой", table, endTime) { endTime = it } }
                item { ColumnPick("Длительность", table, duration) { duration = it } }
                if (duration != null) item {
                    Labeled2("Длительность записана как") {
                        Segmented(listOf(DurationFormat.HMS to "1:30:00", DurationFormat.DECIMAL_HOURS to "1,5 ч", DurationFormat.SECONDS to "секунды",
                            DurationFormat.MILLISECONDS to "мс"), format, { format = it }, Modifier.fillMaxWidth())
                    }
                }
                item { ColumnPick("Заметка", table, note) { note = it } }
                item {
                    Labeled2("Как записано время" + (startExample?.let { " · в файле: $it" } ?: "")) {
                        PatternPick(pattern) { pattern = it }
                        if (pattern != null) Segmented(listOf(zone.id to "Мой пояс", "UTC" to "UTC"), fileZone, { fileZone = it }, Modifier.fillMaxWidth())
                        Text(if (pattern == null) "Пояс указан в самом времени" else "Время в файле считается по поясу ${fileZone.replace('_', ' ')}",
                            style = NotAi.type.caption, color = c.ink2)
                    }
                }
                problem?.let { p -> item { Text(p, style = NotAi.type.caption, color = c.amberInk) } }
                if (!ready) item { Text("Нужны занятие, начало и конец или длительность", style = NotAi.type.caption, color = c.ink2) }
            } else {
                val file = parsed
                if (file != null) {
                    item { SectionLabel("Как это назвать в трекере") }
                    items(file.activities, key = { it.activity + "\u0000" + it.category }) { a ->
                        val count = file.rows.count { it.activity == a }
                        val target = targets[a]
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).pressable({ onPickActivity { sel, label -> targets[a] = sel to label.title } },
                            label = "Выбрать занятие для «${a.activity}»"), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(a.activity.ifBlank { "Без названия" } + (a.category.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                    style = NotAi.type.body, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text("${Format.plural(count, "строка", "строки", "строк")} → ${target?.second ?: "как в файле"}",
                                    style = NotAi.type.caption, color = if (target != null) c.amberInk else c.ink2)
                            }
                            if (target != null) IconAction(Icon.Close, "Оставить как в файле", { targets.remove(a) }, size = 16.dp)
                            else M3Icon(Icon.ChevronRight, null, Modifier.size(16.dp), tint = c.ink3)
                        }
                    }
                }
                item { SectionLabel("Проверка", Modifier.padding(top = 8.dp)) }
                val p = preview
                when {
                    working && p == null -> item { Text("Считаю…", style = NotAi.type.caption, color = c.ink2) }
                    p != null -> {
                        item {
                            Text(buildString {
                                append("Добавится ${Format.plural(p.added, "запись", "записи", "записей")}")
                                if (p.duplicates > 0) append(", уже есть: ${p.duplicates}")
                                if (p.errorIssues.isNotEmpty()) append(", с ошибками: ${p.errorIssues.size}")
                            }, style = NotAi.type.body.copy(fontWeight = FontWeight.Medium), color = c.ink)
                        }
                        items(p.errorIssues.take(8)) { e -> Text(Format.importIssue(e), style = NotAi.type.caption, color = c.amberInk) }
                        items(p.intervals.sortedBy { it.start }.take(12), key = { it.id }) { seg ->
                            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.sunk).padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text("${Format.dayMonth(Instant.ofEpochMilli(seg.start).atZone(zone).toLocalDate())}, ${Format.clock(seg.start, zone)}–${seg.end?.let { Format.clock(it, zone) } ?: ""}",
                                    Modifier.padding(end = 12.dp), style = NotAi.type.caption, color = c.ink2, maxLines = 1, softWrap = false)
                                Text(seg.selection.name ?: targets.values.firstOrNull { it.first == seg.selection }?.second ?: "Без занятия",
                                    style = NotAi.type.caption, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (p.added > 12) item { Text("и ещё ${p.added - 12}", style = NotAi.type.caption, color = c.ink3) }
                        item { Text("Записи добавятся к истории, ничего существующего не заменится. Повторы из этого же источника пропускаются.",
                            style = NotAi.type.caption, color = c.ink2) }
                    }
                    else -> problem?.let { pr -> item { Text(pr, style = NotAi.type.caption, color = c.amberInk) } }
                }
            }
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            if (step == 0) PillButton(if (working) "Читаю…" else "Дальше", {
                working = true
                vm.launchParse(ui, delimiter, mapping(), duration?.let { format }) { result ->
                    working = false
                    result.onSuccess { f ->
                        if (f.rows.isEmpty()) problem = f.errorIssues.firstOrNull()?.let(Format::importIssue) ?: "В файле не нашлось ни одной записи"
                        else { parsed = f; problem = null; step = 1 }
                    }.onFailure { problem = Format.importError(it) }
                }
            }, Modifier.fillMaxWidth(), ButtonKind.PRIMARY, enabled = ready && !working)
            else PillButton(preview?.let { "Добавить ${Format.plural(it.added, "запись", "записи", "записей")}" } ?: "Добавить", {
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
                Text(value?.let { v -> v + (sample(v)?.let { " · $it" } ?: "") } ?: "Нет такой колонки", style = NotAi.type.body,
                    color = if (value == null) c.ink3 else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            M3Icon(Icon.ChevronDown, null, Modifier.size(16.dp), tint = c.ink3)
        }
        DropdownMenu(open, { open = false }, containerColor = c.raised) {
            if (!required) DropdownMenuItem({ Text("Нет такой колонки", color = c.ink2) }, { onPick(null); open = false })
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
            .pressable({ open = true }, label = "Формат времени").padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(csvPatterns.firstOrNull { it.first == pattern }?.second ?: pattern.orEmpty(), Modifier.weight(1f), style = NotAi.type.body, color = c.ink)
            M3Icon(Icon.ChevronDown, null, Modifier.size(16.dp), tint = c.ink3)
        }
        DropdownMenu(open, { open = false }, containerColor = c.raised) {
            csvPatterns.forEach { (p, label) -> DropdownMenuItem({ Text(label, color = c.ink) }, { onPick(p); open = false }) }
        }
    }
}
