package dev.notai.tracker.android.ui

import dev.notai.tracker.android.R
import dev.notai.tracker.android.L

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.notai.tracker.DeletedRecord
import dev.notai.tracker.TrackerView
import java.time.Instant
import java.time.ZoneId

/**
 * «Удалённое»: what was deleted, by the day it was deleted, newest first. Each line reads like a history row
 * (icon, activity, when and how long) with a quiet «Восстановить» beside it. Nothing here deletes for good and
 * nothing is cleaned up automatically; «Восстановить все» asks first.
 */
@Composable internal fun DeletedRecordsPage(vm: TrackerViewModel,view: TrackerView?) {
    val c = NotAi.colors
    var count by remember { mutableIntStateOf(50) }
    var confirmAll by remember { mutableStateOf(false) }
    val records = view?.data?.deletedRecords.orEmpty().sortedByDescending { it.deletedAt }
    val zone = view?.let { ZoneId.of(it.data.settings.reportZone) } ?: ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(vm.now()).atZone(zone).toLocalDate()
    Text(stringResource(R.string.deleted_retained),Modifier.padding(start = 4.dp,bottom = 12.dp),style = NotAi.type.bodySmall,color = c.ink2)
    if(records.isEmpty()) {
        Text(stringResource(R.string.deleted_empty),Modifier.padding(start = 4.dp,top = 8.dp),style = NotAi.type.body,color = c.ink2)
        return
    }
    if(records.size > 1) PillButton(L.s(R.string.t_restore_all, records.size),{ confirmAll = true },
        Modifier.fillMaxWidth().testTag("restore-all-deleted"),ButtonKind.TONAL,height = 44.dp)
    records.take(count).groupBy { Instant.ofEpochMilli(it.deletedAt).atZone(zone).toLocalDate() }.forEach { (day,group) ->
        Row(Modifier.fillMaxWidth().padding(start = 4.dp,end = 4.dp,top = 18.dp,bottom = 8.dp),verticalAlignment = Alignment.Bottom) {
            Text(L.s(R.string.t_deleted_on, Format.dayTitle(day,today)),Modifier.weight(1f),style = NotAi.type.caption.copy(fontWeight = FontWeight.Medium),color = c.ink2)
            Text(L.p(R.plurals.p_6c73bc4d74,group.size),style = NotAi.type.caption,color = c.ink3)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.raised).border(1.dp,c.rule,RoundedCornerShape(20.dp))) {
            group.forEachIndexed { i,record -> key(record.sessionId) {
                if(i > 0) Box(Modifier.fillMaxWidth().padding(start = 52.dp).height(1.dp).background(c.rule))
                DeletedRow(record,view!!,zone,today) { vm.restoreDeleted(record.sessionId,view.snapshot().token) }
            } }
        }
    }
    if(records.size > count) PillButton(stringResource(R.string.deleted_show_more),{ count += 50 },Modifier.fillMaxWidth().padding(top = 14.dp),ButtonKind.TONAL)
    if(confirmAll) ConfirmDialog(L.s(R.string.t_restore_all_title, L.p(R.plurals.p_6c73bc4d74,records.size)),L.s(R.string.t_restore_all_body),
        L.s(R.string.t_restore_all_confirm),{ confirmAll = false;view?.let { vm.restoreAllDeleted(records.map { r -> r.sessionId },it.snapshot().token) } },{ confirmAll = false })
}

@Composable private fun DeletedRow(record: DeletedRecord,view: TrackerView,zone: ZoneId,today: java.time.LocalDate,restore: () -> Unit) {
    val c = NotAi.colors
    val label = view.data.label(record.session.value.selection)
    val first = record.segments.minOf { it.value.start }
    val last = record.segments.maxOf { it.value.end ?: it.value.start }
    val date = Instant.ofEpochMilli(first).atZone(zone).toLocalDate()
    val restoreLabel = stringResource(R.string.editorial_tracker_draft_86dfb8c919ec425b6196_v0)
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp,end = 6.dp,top = 10.dp,bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ActivityGlyph(label,size = 22.dp)
        Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label.title,style = NotAi.type.body,color = c.ink,maxLines = 1,overflow = TextOverflow.Ellipsis)
            Text("${Format.dayTitle(date,today)} · ${Format.range(first,last,zone)} · ${Format.duration(record.recordedMs)}",
                style = NotAi.type.caption,color = c.ink2,maxLines = 1,overflow = TextOverflow.Ellipsis)
            record.session.value.note?.takeIf { it.isNotBlank() }?.let { Text(it,style = NotAi.type.caption,color = c.ink3,maxLines = 2,overflow = TextOverflow.Ellipsis) }
        }
        Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).testTag("restore-deleted:${record.sessionId}")
            .pressable(restore,role = Role.Button,label = restoreLabel).padding(horizontal = 12.dp),contentAlignment = Alignment.Center) {
            Text(restoreLabel,style = NotAi.type.bodySmall.copy(fontWeight = FontWeight.Medium),color = c.amberInk)
        }
    }
}
