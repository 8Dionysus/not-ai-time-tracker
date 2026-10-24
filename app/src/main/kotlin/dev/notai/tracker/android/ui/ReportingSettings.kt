package dev.notai.tracker.android.ui

import dev.notai.tracker.android.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId

internal fun zoneSummary(zone: ZoneId, now: Long): String {
    val offset = zone.rules.getOffset(Instant.ofEpochMilli(now)).id
    return "${zone.id.replace('_', ' ')} · " + if (offset == "Z") "UTC" else "UTC$offset"
}

internal fun boundaryClock(minute: Int) = "${minute / 60}:${(minute % 60).toString().padStart(2, '0')}"

/** Choosing a mode or searching does not write preferences. A deliberate choice goes through confirmation. */
@Composable
internal fun ReportZoneSheet(automatic: Boolean, current: ZoneId, now: Long, autoAvailable: Boolean,
                             onAutomatic: () -> Unit, onManual: (String) -> Unit, onDismiss: () -> Unit) {
    val c = NotAi.colors
    var auto by remember { mutableStateOf(automatic) }
    var query by remember { mutableStateOf("") }
    val deviceZone = ZoneId.systemDefault()
    val zones = remember(current.id) { (ZoneId.getAvailableZoneIds() + current.id).sorted() }
    val matches = remember(query, zones) {
        zones.filter { it.replace('_', ' ').contains(query.trim().replace('_', ' '), ignoreCase = true) }
    }
    NotAiSheet(onDismiss) {
        SheetHeader("Часовой пояс", onDismiss)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Segmented(listOf(true to "Автоматически", false to "Вручную"), auto, { auto = it }, Modifier.fillMaxWidth())
            if (auto) {
                Text(zoneSummary(deviceZone, now), style = NotAi.type.body, color = c.ink)
                Text("Следовать часовому поясу телефона", style = NotAi.type.caption, color = c.ink2)
                PillButton("Использовать пояс телефона", onAutomatic, Modifier.fillMaxWidth(), enabled = autoAvailable)
            } else {
                Text("Сейчас: ${zoneSummary(current, now)}", style = NotAi.type.caption, color = c.ink2)
                SearchField(query, { query = it }, "Найти пояс IANA, например Europe/Moscow")
            }
        }
        if (!auto) LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
            if (matches.isEmpty()) item { Text("Пояс не найден", style = NotAi.type.body, color = c.ink2) }
            items(matches, key = { it }) { id ->
                ListRow(id, subtitle = zoneSummary(ZoneId.of(id), now).substringAfter(" · "), onClick = { onManual(id) },
                    trailing = { if (current.id == id) Text("Выбран", style = NotAi.type.caption, color = c.amberInk) })
            }
        }
    }
}

/** Opening or dismissing this editor must never round or reset an existing boundary. */
@Composable
internal fun DayBoundarySheet(minute: Int, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    val c = NotAi.colors
    var value by remember(minute) { mutableIntStateOf(minute) }
    NotAiSheet(onDismiss, fullHeight = false) {
        SheetHeader("Граница отчётного дня", onDismiss)
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconAction(Icon.Minus, "Граница дня на час раньше", { value = (value - 60).coerceAtLeast(0) }, tint = c.ink)
                Text(boundaryClock(value), style = NotAi.type.title, color = c.ink)
                IconAction(Icon.Plus, "Граница дня на час позже", { value = (value + 60).coerceAtMost(1439) }, tint = c.ink)
            }
            Text(if (value == 0) "В полночь, как в календаре" else
                "Записи до ${boundaryClock(value)} относятся к предыдущему дню. Сами записи не меняются",
                style = NotAi.type.caption, color = c.ink2)
            if (value != 0) PillButton("В полночь", { value = 0 }, Modifier.fillMaxWidth(), ButtonKind.TEXT, height = 40.dp)
            PillButton(stringResource(R.string.editorial_tracker_draft_8f4fc15539624b530640_v0), { if (value != minute) onSave(value); onDismiss() }, Modifier.fillMaxWidth())
        }
    }
}
