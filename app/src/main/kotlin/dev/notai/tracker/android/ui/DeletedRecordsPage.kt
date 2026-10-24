package dev.notai.tracker.android.ui

import dev.notai.tracker.android.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.notai.tracker.TrackerView
import java.time.ZoneId

@Composable internal fun DeletedRecordsPage(vm: TrackerViewModel,view: TrackerView?) {
    val c = NotAi.colors
    var count by remember { mutableIntStateOf(50) }
    val records = view?.data?.deletedRecords.orEmpty().sortedByDescending { it.deletedAt }
    Text(stringResource(R.string.deleted_retained),
        Modifier.padding(bottom = 16.dp),style = NotAi.type.bodySmall,color = c.ink2)
    if(records.isEmpty()) Text(stringResource(R.string.deleted_empty),style = NotAi.type.body,color = c.ink2)
    records.take(count).forEach { record -> key(record.sessionId) {
        Group(view!!.data.label(record.session.value.selection).title) {
            Column(Modifier.padding(16.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val zone = ZoneId.of(view.data.settings.reportZone)
                val first = record.segments.minOf { it.value.start }
                val last = record.segments.maxOf { it.value.end ?: it.value.start }
                Text("${Format.range(first,last,zone)} · ${Format.duration(record.recordedMs)}",style = NotAi.type.bodySmall,color = c.ink2)
                record.session.value.note?.let { Text(it,style = NotAi.type.body,color = c.ink) }
                PillButton(stringResource(R.string.editorial_tracker_draft_86dfb8c919ec425b6196_v0),{ vm.restoreDeleted(record.sessionId,view.snapshot().token) },Modifier.fillMaxWidth().testTag("restore-deleted:${record.sessionId}"),ButtonKind.TONAL)
            }
        }
    } }
    if(records.size > count) PillButton(stringResource(R.string.deleted_show_more),{ count += 50 },Modifier.fillMaxWidth(),ButtonKind.TONAL)
}
