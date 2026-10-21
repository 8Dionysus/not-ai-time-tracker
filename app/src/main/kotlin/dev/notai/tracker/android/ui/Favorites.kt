package dev.notai.tracker.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.notai.tracker.Classification
import dev.notai.tracker.Favorite
import dev.notai.tracker.selectionIdentity
import kotlin.math.roundToInt

/** What the activity editor opens on. */
sealed interface EditTarget {
    data class Fav(val id: String) : EditTarget
    data class Card(val id: String) : EditTarget
}

/** A favourite as the lists show it, in its saved order. */
data class FavoriteUi(val id: String, val title: String, val label: Label, val selection: Classification)

/**
 * All favourites in their order. Long press and drag moves a row (the order also drives the shade, widget and
 * floating button, which show only the first few); arrows do the same for TalkBack. Tap edits, the star removes.
 */
@Composable
fun FavoritesSheet(vm: TrackerViewModel, onEdit: (EditTarget) -> Unit, onAdd: () -> Unit, onDismiss: () -> Unit) {
    val c = NotAi.colors
    val view by vm.view.collectAsStateWithLifecycle()
    val source = remember(view) { vm.favorites(view) }
    var order by remember(source) { mutableStateOf(source) }
    val token = remember(view) { view?.snapshot()?.token }
    fun commit(next: List<FavoriteUi>) {
        if (next.map { it.id } == source.map { it.id }) return
        token?.let { t -> vm.orderFavorites(t, next.map { it.id }) }
    }
    val rowPx = with(LocalDensity.current) { 56.dp.toPx() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val haptics = LocalHapticFeedback.current
    NotAiSheet(onDismiss) {
        SheetHeader("Избранное", onDismiss)
        Text(if (order.isEmpty()) "Отметь звёздочкой занятия, которые начинаешь чаще всего. Они встанут на главный экран и в шторку."
            else "Первые в списке видны в шторке, на виджете и у плавающей кнопки. Чтобы переставить, зажми и тяни.",
            Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = NotAi.type.caption, color = c.ink2)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            order.forEachIndexed { index, f ->
                val moving = dragging == f.id
                fun move(to: Int) { val list = order.toMutableList(); list.add(to.coerceIn(0, list.lastIndex), list.removeAt(index)); order = list }
                Row(Modifier.fillMaxWidth().height(56.dp).zIndex(if (moving) 1f else 0f)
                    .graphicsLayer { translationY = if (moving) offset else 0f; scaleX = if (moving) 1.02f else 1f; scaleY = if (moving) 1.02f else 1f }
                    .then(if (moving) Modifier.shadow(10.dp, RoundedCornerShape(14.dp)) else Modifier)
                    .clip(RoundedCornerShape(14.dp)).background(if (moving) c.raised else c.raised.copy(alpha = 0f))
                    .pointerInput(f.id, order.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = f.id; offset = 0f; haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDrag = { change, amount ->
                                change.consume(); offset += amount.y
                                val i = order.indexOfFirst { it.id == f.id }
                                val steps = (offset / rowPx).roundToInt()
                                if (steps != 0) {
                                    val to = (i + steps).coerceIn(0, order.lastIndex)
                                    if (to != i) { val list = order.toMutableList(); list.add(to, list.removeAt(i)); order = list; offset -= (to - i) * rowPx
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) }
                                }
                            },
                            onDragEnd = { dragging = null; offset = 0f; commit(order) },
                            onDragCancel = { dragging = null; offset = 0f; commit(order) })
                    }
                    .pressable({ onEdit(EditTarget.Fav(f.id)) }, label = "Изменить: ${f.title}")
                    .semantics {
                        customActions = listOfNotNull(
                            if (index > 0) CustomAccessibilityAction("Выше") { move(index - 1); commit(order); true } else null,
                            if (index < order.lastIndex) CustomAccessibilityAction("Ниже") { move(index + 1); commit(order); true } else null)
                    }
                    .padding(start = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    M3Icon(Icon.Grip, null, Modifier.size(18.dp), tint = c.ink3)
                    ActivityGlyph(f.label)
                    Column(Modifier.weight(1f)) {
                        Text(f.title, style = NotAi.type.body, color = c.ink, maxLines = 1)
                        if (f.title != f.label.title) Text(f.label.title, style = NotAi.type.caption, color = c.ink2, maxLines = 1)
                    }
                    if (index < 4) Text("${index + 1}", style = NotAi.type.caption, color = c.ink3)
                    IconAction(Icon.Star, "Убрать из избранного: ${f.title}", { vm.removeFavorite(f.id) }, tint = c.amber, size = 17.dp)
                }
                Hairline()
            }
        }
        PillButton("Добавить в избранное", onAdd, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp), ButtonKind.TONAL, Icon.Plus)
    }
}

/**
 * Edit one activity. A favourite has its own button title and points at an activity; an own activity has a name and
 * an optional section (a catalog category), which is what statistics sum it under. Past records keep where they were.
 */
@Composable
fun ActivityEditorSheet(vm: TrackerViewModel, target: EditTarget, onPick: ((Classification, Label) -> Unit) -> Unit, onDismiss: () -> Unit) {
    val c = NotAi.colors
    val view by vm.view.collectAsStateWithLifecycle()
    val data = view?.data ?: return
    val token = remember { view?.snapshot()?.token }
    when (target) {
        is EditTarget.Fav -> {
            val fav = data.favorites.firstOrNull { it.id == target.id } ?: run { LaunchedEffect(Unit) { onDismiss() }; return }
            var title by remember { mutableStateOf(fav.label) }
            var selection by remember { mutableStateOf(fav.selection) }
            var label by remember { mutableStateOf(data.label(fav.selection)) }
            val card = selection.cardId?.let { id -> data.cards.firstOrNull { it.id == id } }
            NotAiSheet(onDismiss, fullHeight = false) {
                SheetHeader("Избранное", onDismiss, extra = { IconAction(Icon.Trash, "Убрать из избранного", { vm.removeFavorite(fav.id); onDismiss() }, size = 19.dp) })
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TextInput("Название на кнопке", title, { title = it }, placeholder = label.title)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Занятие", style = NotAi.type.caption, color = c.ink2)
                        ActivityField(label, { onPick { sel, l -> selection = sel; label = l; if (title == fav.label && fav.label == data.label(fav.selection).title) title = l.title } })
                    }
                    if (card != null) Text("Изменить само занятие «${card.name}»", Modifier.heightIn(min = 36.dp).pressable({ onDismiss(); vm.openEditor(EditTarget.Card(card.id)) })
                        .padding(vertical = 8.dp), style = NotAi.type.bodySmall.copy(fontWeight = FontWeight.Medium), color = c.amberInk)
                }
                PillButton("Сохранить", {
                    token?.let { vm.editFavorite(it, fav.copy(label = title.trim().ifEmpty { label.title }, selection = selection)) }; onDismiss()
                }, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp), ButtonKind.PRIMARY, enabled = token != null)
            }
        }
        is EditTarget.Card -> {
            val card = data.cards.firstOrNull { it.id == target.id } ?: run { LaunchedEffect(Unit) { onDismiss() }; return }
            var name by remember { mutableStateOf(card.name) }
            var parent by remember { mutableStateOf(card.activityTypeId) }
            val parentLabel = parent?.let { data.label(Classification(activityTypeId = it)) }
            val favored = data.favorites.any { selectionIdentity(it.selection) == selectionIdentity(card.classification()) }
            NotAiSheet(onDismiss, fullHeight = false) {
                SheetHeader("Своё занятие", onDismiss)
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    TextInput("Название", name, { name = it })
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Раздел", style = NotAi.type.caption, color = c.ink2)
                        ActivityField(parentLabel ?: Label("Без раздела", null, null, true), {
                            onPick { sel, _ -> sel.activityTypeId?.let { parent = it } }
                        }, if (parent == null) "Выбрать" else "Изменить")
                        if (parent != null) Text("Без раздела", Modifier.heightIn(min = 32.dp).pressable({ parent = null }).padding(top = 4.dp),
                            style = NotAi.type.caption.copy(fontWeight = FontWeight.Medium), color = c.amberInk)
                    }
                    Text("Время занятия войдёт в итог раздела в обзоре и целях. Уже сделанные записи сохранят своё прежнее место.",
                        style = NotAi.type.caption, color = c.ink2)
                    if (!favored) Text("Добавить в избранное", Modifier.heightIn(min = 36.dp).pressable({ vm.saveFavorite(card.name, card.classification()) }).padding(vertical = 8.dp),
                        style = NotAi.type.bodySmall.copy(fontWeight = FontWeight.Medium), color = c.amberInk)
                }
                PillButton("Сохранить", {
                    token?.let { vm.saveCard(it, card.copy(name = name.trim().ifEmpty { card.name }.replaceFirstChar { ch -> ch.uppercase() }, activityTypeId = parent)) }; onDismiss()
                }, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 20.dp), ButtonKind.PRIMARY, enabled = token != null && name.isNotBlank())
            }
        }
    }
}

@Composable
fun TextInput(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "") {
    val c = NotAi.colors
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = NotAi.type.caption, color = c.ink2)
        Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(14.dp)).background(c.sunk).padding(horizontal = 14.dp, vertical = 14.dp)) {
            if (value.isEmpty()) Text(placeholder, style = NotAi.type.body, color = c.ink3)
            BasicTextField(value, { onChange(it.take(120)) }, Modifier.fillMaxWidth().semantics { contentDescription = label }, textStyle = NotAi.type.body.copy(color = c.ink),
                cursorBrush = SolidColor(c.amber), singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))
        }
    }
}
