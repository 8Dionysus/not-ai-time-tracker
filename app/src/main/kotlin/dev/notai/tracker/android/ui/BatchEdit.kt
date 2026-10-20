package dev.notai.tracker.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.notai.tracker.BatchMetadataPreview
import dev.notai.tracker.Classification
import dev.notai.tracker.MetadataFillPolicy
import dev.notai.tracker.MetadataNoteEdit
import kotlinx.coroutines.launch

/**
 * Takes the place of the tab bar while records are being chosen: how many, and the two things that can be given to all
 * of them at once. Every change is previewed first and can be undone from the notice after.
 */
@Composable
fun BatchBar(vm: TrackerViewModel, count: Int, pickActivity: ((Classification, Label) -> Unit) -> Unit) {
    val c = NotAi.colors
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<Pair<BatchMetadataPreview, String>?>(null) }
    var noteDialog by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    BackHandler { vm.clearPicked() }
    fun preview(sel: Classification?, note: MetadataNoteEdit?, replace: Boolean, describe: String) = scope.launch {
        vm.previewBatch(sel, note, replace).onSuccess { p ->
            if (p.changes.isEmpty()) problem = "У выбранных записей это уже так" else confirm = p to describe
        }.onFailure { problem = Format.error(it) }
    }
    Column(Modifier.fillMaxWidth().background(c.paper)) {
        Hairline()
        Row(Modifier.fillMaxWidth().navigationBarsPadding().heightIn(min = 64.dp).padding(start = 8.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconAction(Icon.Close, "Отменить выбор", vm::clearPicked, tint = c.ink)
            Text("Выбрано: $count", Modifier.weight(1f), style = NotAi.type.body.copy(fontWeight = FontWeight.Medium), color = c.ink)
            PillButton("Занятие", { pickActivity { sel, label -> preview(sel, null, true, "Занятие станет «${label.title}».") } },
                kind = ButtonKind.TONAL, height = 40.dp)
            PillButton("Заметка", { noteDialog = true }, kind = ButtonKind.TONAL, height = 40.dp)
        }
    }
    if (noteDialog) NoteDialog(onDismiss = { noteDialog = false }) { text, all ->
        noteDialog = false
        preview(null, MetadataNoteEdit(text, if (all) MetadataFillPolicy.REPLACE else MetadataFillPolicy.EMPTY_ONLY), all,
            if (all) "Заметка «$text» заменит прежние." else "Заметка «$text» появится там, где её не было.")
    }
    confirm?.let { (p, describe) ->
        val n = p.changes.map { it.sessionId }.distinct().size
        ConfirmDialog("Изменить ${Format.plural(n, "запись", "записи", "записей")}?", "$describe Время записей не меняется. Отменить можно сразу после.",
            "Изменить", { vm.applyBatch(p); confirm = null }, { confirm = null })
    }
    problem?.let { ConfirmDialog("Не получилось", it, null, {}, { problem = null }) }
}

@Composable
private fun NoteDialog(onDismiss: () -> Unit, onDone: (String, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    var all by remember { mutableStateOf(false) }
    NotAiSheet(onDismiss, fullHeight = false) {
        SheetHeader("Заметка для выбранных", onDismiss)
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextInput("Текст заметки", text, { text = it }, placeholder = "Например, проект «Альфа»")
            Segmented(listOf(false to "Только где пусто", true to "Заменить у всех"), all, { all = it }, Modifier.fillMaxWidth())
            PillButton("Дальше", { onDone(text.trim(), all) }, Modifier.fillMaxWidth(), ButtonKind.PRIMARY, enabled = text.isNotBlank())
        }
    }
}
