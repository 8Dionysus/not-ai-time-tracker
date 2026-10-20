@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.notai.tracker.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.notai.tracker.ChoiceAvailability
import dev.notai.tracker.ChoiceKind
import dev.notai.tracker.Classification
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Shared sheet chrome: raised surface, 28 dp top corners, quiet handle. */
@Composable
fun NotAiSheet(onDismiss: () -> Unit, fullHeight: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val c = NotAi.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.raised, contentColor = c.ink, scrimColor = c.scrim,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 8.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(c.rule)) },
    ) {
        Column(if (fullHeight) Modifier.fillMaxHeight(0.88f) else Modifier, content = content)
    }
}

@Composable
fun SheetHeader(title: String, onClose: () -> Unit, back: (() -> Unit)? = null, extra: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = if (back == null) 20.dp else 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) IconAction(Icon.ChevronLeft, "Назад", back, tint = NotAi.colors.ink)
        Text(title, Modifier.weight(1f), style = NotAi.type.sheetTitle, color = NotAi.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        extra?.invoke()
        IconAction(Icon.Close, "Закрыть", onClose)
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val c = NotAi.colors
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(c.sunk).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        M3Icon(Icon.Search, null, Modifier.size(18.dp), tint = c.ink2)
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = NotAi.type.body, color = c.ink2)
            BasicTextField(value, onChange, Modifier.fillMaxWidth().semantics { contentDescription = placeholder }, singleLine = true,
                textStyle = NotAi.type.body.copy(color = c.ink), cursorBrush = SolidColor(c.amber),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Search))
        }
        if (value.isNotEmpty()) IconAction(Icon.Close, "Очистить", { onChange("") }, size = 16.dp)
    }
}

/**
 * Choose an activity. With [onReturn] the choice is handed back to a form (manual entry, correction);
 * otherwise the view model applies it as Start, Switch, labelling or a retrospective switch.
 */
@Composable
fun PickerSheet(vm: TrackerViewModel, mode: PickMode, active: Boolean, startClock: String?, boundaryClock: String?, boundary: Long?,
                onDismiss: () -> Unit, surfaceRoute: dev.notai.tracker.android.SurfacePickerRoute? = null,
                onReturn: ((Classification, Label) -> Unit)? = null) {
    val c = NotAi.colors
    var effective by remember { mutableStateOf(mode) }
    var query by remember { mutableStateOf("") }
    var stack by remember { mutableStateOf(listOf<PickerItem>()) }
    var showMore by remember { mutableStateOf(false) }
    val committed by vm.view.collectAsStateWithLifecycle()
    val data by produceState<PickerData?>(null, committed) { value = vm.pickerData() }
    val results by produceState(emptyList<PickerItem>(), query, committed) { if (query.isBlank()) value = emptyList() else { delay(120); value = vm.search(query) } }
    val breadcrumb = stack.lastOrNull()
    val projectedParent by produceState(breadcrumb,breadcrumb,committed) { value = breadcrumb?.let { vm.refreshPickerItem(it) } }
    val parent = projectedParent
    val children by produceState(emptyList<PickerItem>(), parent, committed) { value = parent?.let { vm.children(it.entry.sourceId) } ?: emptyList() }
    val draft by vm.draft.collectAsStateWithLifecycle()

    fun pick(item: PickerItem) {
        // Areas with children open first; the area itself stays choosable as "Вся область" inside.
        if (!item.whole && (item.entry.availability == ChoiceAvailability.GROUP || (item.count ?: 0) > 0)) { stack = stack + item; query = ""; return }
        if (!item.entry.canStart) return
        if (surfaceRoute != null) {
            item.reference?.let { vm.chooseSurface(it,expectedRoute = surfaceRoute) }
            return // The route closes only after a successful command; failures leave it reviewable.
        }
        if (onReturn != null) onReturn(item.entry.selection, item.label) else vm.choose(item.entry, effective, boundary,item.reference,item.expected)
        onDismiss()
    }
    fun create(name: String) {
        if (surfaceRoute != null) { vm.createSurfaceCard(surfaceRoute,name); return }
        if (onReturn != null) vm.createCard(name) { sel, label -> onReturn(sel, label) } else vm.createAndChoose(name, effective, boundary)
        onDismiss()
    }

    NotAiSheet(onDismiss) {
        val title = when (effective) {
            PickMode.START -> if (draft != null) "Новая запись из текста" else "Что начинаем?"
            PickMode.SWITCH, PickMode.LABEL -> "Чем занят?"
            PickMode.EARLIER -> "На что переключился?"
            PickMode.FOCUS -> "Фокус: занятие"
            PickMode.MANUAL -> "Занятие"
        }
        SheetHeader(if (parent != null && query.isBlank()) parent.label.title else title, onDismiss,
            back = if (parent != null && query.isBlank()) ({ stack = stack.dropLast(1) }) else null)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (surfaceRoute == null && active && (mode == PickMode.SWITCH || mode == PickMode.LABEL)) {
                Segmented(listOf(PickMode.SWITCH to "Начать новое", PickMode.LABEL to "Уточнить текущее"), effective, { effective = it })
                Text(if (effective == PickMode.SWITCH) "Текущая запись закончится сейчас, новая начнётся с этой минуты."
                     else "Название получит уже идущая запись. Начало остаётся ${startClock ?: "прежним"}.",
                    style = NotAi.type.caption, color = c.ink2)
            }
            if (effective == PickMode.EARLIER && boundaryClock != null)
                Text("До $boundaryClock остаётся текущее занятие, с $boundaryClock начнётся выбранное.", style = NotAi.type.caption, color = c.ink2)
            draft?.note?.takeIf { surfaceRoute == null && onReturn == null && effective != PickMode.EARLIER }?.let { note ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.amberWash).padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("Заметка: «$note»", Modifier.weight(1f), style = NotAi.type.bodySmall, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconAction(Icon.Close, "Убрать заметку", vm::dropDraft, size = 16.dp)
                }
            }
            SearchField(query, { query = it }, "Найти или создать занятие", Modifier.padding(top = 4.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)) {
            when {
                query.isNotBlank() -> {
                    item { SectionLabel(if (results.isEmpty()) "Ничего не нашлось" else "Найдено", Modifier.padding(top = 14.dp, bottom = 4.dp)) }
                    items(results, key = { "r-" + it.entry.id }) { r -> ChoiceRow(r, ::pick) }
                    item {
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).pressable({ create(query) }), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            M3Icon(Icon.Plus, null, Modifier.size(16.dp), tint = c.amberInk)
                            Text("Создать занятие «${query.trim()}»", style = NotAi.type.body.copy(fontWeight = FontWeight.Medium), color = c.amberInk)
                        }
                    }
                }
                parent != null -> {
                    if (parent.entry.canStart) item(key = "whole") {
                        ChoiceRow(parent.wholeArea(), ::pick)
                    }
                    items(children, key = { "c-" + it.entry.id }) { ch -> ChoiceRow(ch, ::pick) }
                }
                else -> {
                    val d = data
                    if (d == null) item { Spacer(Modifier.height(200.dp)) } else {
                        if (d.recents.isNotEmpty()) {
                            item { SectionLabel("Недавние", Modifier.padding(top = 14.dp, bottom = 4.dp)) }
                            items(d.recents, key = { "h-" + it.entry.id }) { r ->
                                ChoiceRow(r, ::pick, trailing = { IconAction(Icon.StarLine, "В избранное", { vm.saveFavorite(r.label.title, r.entry.selection) }, size = 16.dp) })
                            }
                        }
                        if (d.favorites.isNotEmpty()) {
                            item { SectionLabel("Избранное", Modifier.padding(top = 20.dp, bottom = 4.dp)) }
                            items(d.favorites, key = { "f-" + it.entry.id }) { f ->
                                ChoiceRow(f, ::pick, title = f.entry.title, trailing = {
                                    IconAction(Icon.Star, "Убрать из избранного", { vm.removeFavorite(f.entry.sourceId) }, tint = c.amber, size = 16.dp)
                                })
                            }
                        }
                        item { SectionLabel("Области", Modifier.padding(top = 20.dp, bottom = 4.dp)) }
                        items(d.areas, key = { "a-" + it.entry.id }) { a -> ChoiceRow(a, ::pick, square = true) }
                        if (d.moreAreas.isNotEmpty()) {
                            if (showMore) items(d.moreAreas, key = { "m-" + it.entry.id }) { a -> ChoiceRow(a, ::pick, square = true) }
                            else item {
                                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).pressable({ showMore = true }), verticalAlignment = Alignment.CenterVertically) {
                                    Text("Ещё ${d.moreAreas.size} областей: профессии и работа", style = NotAi.type.body, color = c.ink2)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (surfaceRoute == null && effective == PickMode.START && onReturn == null) {
            Hairline()
            PillButton(if (draft != null) "Начать без занятия, с заметкой" else "Начать без занятия", {
                if (draft != null) vm.startDraftBlank() else vm.startBlank(); onDismiss()
            }, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp))
        }
    }
}

@Composable
private fun ChoiceRow(item: PickerItem, onPick: (PickerItem) -> Unit, title: String = item.label.title, square: Boolean = false,
                      trailing: (@Composable RowScope.() -> Unit)? = null) {
    val c = NotAi.colors
    val group = !item.whole && (item.entry.availability == ChoiceAvailability.GROUP || (item.count ?: 0) > 0)
    val enabled = group || item.entry.canStart
    ListRow(title, detail = if (group || square) null else item.label.detail,
        subtitle = if (!enabled) "в архиве" else null,
        onClick = { onPick(item) },
        leading = { ActivityGlyph(item.label) },
        trailing = {
            if (trailing != null) trailing()
            if (item.count != null && item.count > 0) Text("${item.count}", style = NotAi.type.bodySmall, color = c.ink2)
            if (group) M3Icon(Icon.ChevronRight, null, Modifier.size(16.dp), tint = c.ink2)
        })
}
