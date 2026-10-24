package dev.notai.tracker.android.ui

import dev.notai.tracker.android.R

import androidx.compose.ui.res.stringResource

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.notai.tracker.BatchMetadataPreview
import dev.notai.tracker.Classification
import dev.notai.tracker.MetadataFillPolicy
import dev.notai.tracker.MetadataNoteEdit
import kotlinx.coroutines.launch

/**
 * Takes the place of the tab bar while records are being chosen. Every change is previewed first
 * and can be undone from the notice after. Selection is keyed by session, including across days.
 */
@Composable
fun BatchBar(vm: TrackerViewModel, count: Int, pickActivity: ((Classification, Label) -> Unit) -> Unit,
             deletionHost: BatchDeletionUiHost? = vm as? BatchDeletionUiHost) {
    val c = NotAi.colors
    val scope = rememberCoroutineScope()
    val deleteLabel = stringResource(R.string.editorial_tracker_draft_98c2a48d88776822811d_v0)
    val failureLabel = stringResource(R.string.delete_error)
    var confirm by remember { mutableStateOf<Pair<BatchMetadataPreview, String>?>(null) }
    var noteDialog by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var deletion by remember { mutableStateOf<PickedDeletionReview?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var preparingDeletion by remember { mutableStateOf(false) }
    val picked by vm.picked.collectAsStateWithLifecycle()
    val sessionCount = picked.size
    BackHandler(enabled = deletion == null && !deleting) { vm.clearPicked() }
    fun preview(sel: Classification?, note: MetadataNoteEdit?, replace: Boolean, describe: String) = scope.launch {
        vm.previewBatch(sel, note, replace).onSuccess { p ->
            if (p.changes.isEmpty()) problem = "У выбранных записей это уже так" else confirm = p to describe
        }.onFailure { problem = Format.error(it) }
    }
    Column(Modifier.fillMaxWidth().background(c.paper)) {
        Hairline()
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconAction(Icon.Close, "Отменить выбор", vm::clearPicked, tint = c.ink)
                Text("Выбрано: $sessionCount", style = NotAi.type.body.copy(fontWeight = FontWeight.Medium), color = c.ink)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton(stringResource(R.string.editorial_tracker_draft_1a2c7007a2d3e4977ace_v0), { pickActivity { sel, label -> preview(sel, null, true, "Занятие станет «${label.title}».") } },
                    Modifier.weight(1f), kind = ButtonKind.TONAL, height = 40.dp)
                PillButton(stringResource(R.string.editorial_tracker_draft_25a310c49fcbc14afa82_v0), { noteDialog = true }, Modifier.weight(1f), kind = ButtonKind.TONAL, height = 40.dp)
                PillButton(if (preparingDeletion) "…" else "$deleteLabel · $sessionCount", {
                    val host = deletionHost ?: return@PillButton
                    if (preparingDeletion) return@PillButton
                    preparingDeletion = true
                    scope.launch {
                        try {
                            host.previewPickedDeletion().onSuccess { review ->
                                when {
                                    review.sessionIds.isEmpty() -> problem = "Нет записей для удаления"
                                    review.sessionIds != vm.picked.value -> problem = "Выбор успел измениться. Проверь записи и повтори."
                                    else -> deletion = review
                                }
                            }.onFailure { problem = Format.error(it) }
                        } finally { preparingDeletion = false }
                    }
                }, Modifier.weight(1.3f), kind = ButtonKind.OUTLINE, height = 40.dp,
                    enabled = deletionHost != null && sessionCount > 0 && !preparingDeletion)
            }
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
    deletion?.let { review ->
        ConfirmDialog(stringResource(R.string.delete_title,review.sessionIds.size),
            (listOf(stringResource(R.string.delete_confirmation)) + review.consequences).joinToString("\n\n"),
            if (deleting) "…" else deleteLabel, {
                if (!deleting) {
                    deleting = true
                    scope.launch {
                        try {
                            review.apply().onFailure { problem = failureLabel }
                            // A clears picked IDs only on success; cancellation or failure keeps the selection.
                            deletion = null
                        } finally { deleting = false }
                    }
                }
            }, { if (!deleting) deletion = null }, destructive = true)
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
            PillButton(stringResource(R.string.editorial_tracker_draft_b4e3665f8905663a0236_v0), { onDone(text.trim(), all) }, Modifier.fillMaxWidth(), ButtonKind.PRIMARY, enabled = text.isNotBlank())
        }
    }
}
