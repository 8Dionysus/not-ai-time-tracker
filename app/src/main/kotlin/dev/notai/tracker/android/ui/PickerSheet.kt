@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.notai.tracker.android.ui


import dev.notai.tracker.android.L
import dev.notai.tracker.android.R

import androidx.compose.ui.res.stringResource

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
        if (back != null) IconAction(Icon.ChevronLeft, L.s(R.string.t_f6dab074d7), back, tint = NotAi.colors.ink)
        Text(title, Modifier.weight(1f), style = NotAi.type.sheetTitle, color = NotAi.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        extra?.invoke()
        IconAction(Icon.Close, L.s(R.string.t_4ae50d3073), onClose)
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
        if (value.isNotEmpty()) IconAction(Icon.Close, L.s(R.string.t_98b2073ed1), { onChange("") }, size = 16.dp)
    }
}

/**
 * Choose an activity. With [onReturn] the choice is handed back to a form (manual entry, correction);
 * otherwise the view model applies it as Start, Switch, labelling or a retrospective switch.
 */
@Composable
fun PickerSheet(vm: TrackerViewModel, mode: PickMode, active: Boolean, startClock: String?, boundaryClock: String?, boundary: Long?,
                onDismiss: () -> Unit, onReturn: ((Classification, Label) -> Unit)? = null) =
    PickerSheet(vm,mode,active,startClock,boundaryClock,boundary,onDismiss,surfaceRoute = null,onReturn = onReturn)

/** A surface route keeps its own command semantics through full-catalog selection. */
@Composable
fun PickerSheet(vm: TrackerViewModel, mode: PickMode, active: Boolean, startClock: String?, boundaryClock: String?, boundary: Long?,
                onDismiss: () -> Unit, surfaceRoute: dev.notai.tracker.android.SurfacePickerRoute?,
                onReturn: ((Classification, Label) -> Unit)? = null) {
    val favoriteKeys = remember(vm.view.collectAsStateWithLifecycle().value) {
        vm.view.value?.data?.favorites.orEmpty().map { dev.notai.tracker.selectionIdentity(it.selection) }.toSet()
    }
    fun starred(item: PickerItem) = dev.notai.tracker.selectionIdentity(item.entry.selection) in favoriteKeys
    /** Long press edits favourites and own activities; catalog entries have nothing personal to edit. */
    fun editAction(item: PickerItem): (() -> Unit)? = when {
        item.entry.kind == dev.notai.tracker.ChoiceKind.FAVORITE -> ({ vm.openEditor(EditTarget.Fav(item.entry.sourceId)) })
        item.entry.selection.cardId != null -> ({ vm.openEditor(EditTarget.Card(item.entry.selection.cardId!!)) })
        else -> null
    }
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
    // Created inside an open section, a new activity belongs to it: its time is summed with the section's.
    val section = parent?.takeIf { it.entry.kind == dev.notai.tracker.ChoiceKind.TYPE }?.entry?.sourceId
    fun create(name: String) {
        if (surfaceRoute != null) { vm.createSurfaceCard(surfaceRoute,name,section); return }
        if (onReturn != null) vm.createCard(name, section) { sel, label -> onReturn(sel, label) } else vm.createAndChoose(name, effective, boundary, section)
        onDismiss()
    }

    NotAiSheet(onDismiss) {
        val title = when (effective) {
            PickMode.START -> if (draft != null) L.s(R.string.t_8ff0d08fec) else L.s(R.string.t_dd15d6eecb)
            PickMode.SWITCH, PickMode.LABEL -> L.s(R.string.t_ba2ec9712b)
            PickMode.EARLIER -> L.s(R.string.t_3ac95324a3)
            PickMode.FOCUS -> L.s(R.string.t_28061309a5)
            PickMode.MANUAL -> L.s(R.string.t_70b8d5d8d5)
        }
        SheetHeader(if (parent != null && query.isBlank()) parent.label.title else title, onDismiss,
            back = if (parent != null && query.isBlank()) ({ stack = stack.dropLast(1) }) else null)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (surfaceRoute == null && active && (mode == PickMode.SWITCH || mode == PickMode.LABEL)) {
                Segmented(listOf(PickMode.SWITCH to L.s(R.string.t_ad2b7927cc), PickMode.LABEL to L.s(R.string.t_ef5ff32a17)), effective, { effective = it })
                Text(if (effective == PickMode.SWITCH) L.s(R.string.t_b20271fb06)
                     else L.s(R.string.t_bdcd78489d, startClock ?: L.s(R.string.t_851c0ae977)),
                    style = NotAi.type.caption, color = c.ink2)
            }
            if (effective == PickMode.EARLIER && boundaryClock != null)
                Text(L.s(R.string.t_c67ba2d89b, boundaryClock, boundaryClock), style = NotAi.type.caption, color = c.ink2)
            draft?.note?.takeIf { surfaceRoute == null && onReturn == null && effective != PickMode.EARLIER }?.let { note ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.amberWash).padding(start = 14.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(L.s(R.string.t_3e32dd6cfd, note), Modifier.weight(1f), style = NotAi.type.bodySmall, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconAction(Icon.Close, L.s(R.string.t_b32615689f), vm::dropDraft, size = 16.dp)
                }
            }
            SearchField(query, { query = it }, L.s(R.string.t_0087bd0bf7), Modifier.padding(top = 4.dp))
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)) {
            when {
                query.isNotBlank() -> {
                    item { SectionLabel(if (results.isEmpty()) L.s(R.string.t_84f85ce506) else L.s(R.string.t_212edd982b), Modifier.padding(top = 14.dp, bottom = 4.dp)) }
                    items(results, key = { "r-" + it.entry.id }) { r -> ChoiceRow(r, ::pick, onEdit = editAction(r)) }
                    item {
                        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).pressable({ create(query) }), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            M3Icon(Icon.Plus, null, Modifier.size(16.dp), tint = c.amberInk)
                            Text(if (parent != null && section != null) L.s(R.string.t_a2503487b7, query.trim(), parent.label.title) else L.s(R.string.t_67946fca81, query.trim()),
                                style = NotAi.type.body.copy(fontWeight = FontWeight.Medium), color = c.amberInk)
                        }
                    }
                }
                parent != null -> {
                    val categoryRoot = parent.entry.selection.ancestorPathAtWrite.firstOrNull()
                        ?: parent.label.fallbackRootId ?: parent.entry.sourceId
                    if (parent.entry.canStart) item(key = "whole") {
                        ChoiceRow(parent.wholeArea(), ::pick, categoryRoot = categoryRoot)
                    }
                    items(children, key = { "c-" + it.entry.id }) { ch ->
                        ChoiceRow(ch, ::pick, onEdit = editAction(ch), categoryRoot = categoryRoot)
                    }
                    if (section != null) item(key = "own") {
                        Text(L.s(R.string.t_8241e9552e), Modifier.padding(vertical = 14.dp), style = NotAi.type.caption, color = c.ink2)
                    }
                }
                else -> {
                    val d = data
                    if (d == null) item { Spacer(Modifier.height(200.dp)) } else {
                        if (d.recents.isNotEmpty()) {
                            item { SectionLabel(stringResource(R.string.editorial_tracker_draft_5a23a043869c64456576_v0), Modifier.padding(top = 14.dp, bottom = 4.dp)) }
                            items(d.recents, key = { "h-" + it.entry.id }) { r ->
                                val on = starred(r)
                                ChoiceRow(r, ::pick, onEdit = editAction(r), trailing = {
                                    if (on) IconAction(Icon.Star, L.s(R.string.t_d9f48a75fd), { vm.removeFavorites(vm.favoriteIds(r.entry.selection)) }, tint = c.amber, size = 16.dp)
                                    else IconAction(Icon.StarLine, L.s(R.string.t_5da9e0e83b), { vm.saveFavorite(r.label.title, r.entry.selection) }, size = 16.dp)
                                })
                            }
                        }
                        if (d.favorites.isNotEmpty()) {
                            item {
                                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    SectionLabel(stringResource(R.string.editorial_tracker_draft_8a016df5c8f3d36a6ece_v0), Modifier.weight(1f))
                                    Text(L.s(R.string.t_f6f1e5272f), Modifier.heightIn(min = 32.dp).pressable({ vm.openFavorites(true) }, label = L.s(R.string.t_750b471214)).padding(horizontal = 4.dp, vertical = 6.dp),
                                        style = NotAi.type.caption.copy(fontWeight = FontWeight.Medium), color = c.amberInk)
                                }
                            }
                            items(d.favorites, key = { "f-" + it.entry.id }) { f ->
                                ChoiceRow(f, ::pick, title = f.entry.title, onEdit = { vm.openEditor(EditTarget.Fav(f.entry.sourceId)) }, trailing = {
                                    IconAction(Icon.Star, L.s(R.string.t_d9f48a75fd), { vm.removeFavorite(f.entry.sourceId) }, tint = c.amber, size = 16.dp)
                                })
                            }
                        }
                        item { SectionLabel(stringResource(R.string.editorial_tracker_draft_a168a3a5ba1749583020_v0), Modifier.padding(top = 20.dp, bottom = 4.dp)) }
                        items(d.areas, key = { "a-" + it.entry.id }) { a -> ChoiceRow(a, ::pick, square = true) }
                        if (d.moreAreas.isNotEmpty()) {
                            if (showMore) items(d.moreAreas, key = { "m-" + it.entry.id }) { a -> ChoiceRow(a, ::pick, square = true) }
                            else item {
                                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).pressable({ showMore = true }), verticalAlignment = Alignment.CenterVertically) {
                                    Text(L.s(R.string.t_30e9845126, d.moreAreas.size), style = NotAi.type.body, color = c.ink2)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (surfaceRoute == null && effective == PickMode.START && onReturn == null) {
            Hairline()
            PillButton(if (draft != null) L.s(R.string.t_20feaa7e40) else L.s(R.string.t_5c675dcb97), {
                if (draft != null) vm.startDraftBlank() else vm.startBlank(); onDismiss()
            }, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp))
        }
    }
}

@Composable
private fun ChoiceRow(item: PickerItem, onPick: (PickerItem) -> Unit, title: String = item.label.title, square: Boolean = false,
                      trailing: (@Composable RowScope.() -> Unit)? = null, onEdit: (() -> Unit)? = null, categoryRoot: String? = null) {
    val c = NotAi.colors
    val group = !item.whole && (item.entry.availability == ChoiceAvailability.GROUP || (item.count ?: 0) > 0)
    val enabled = group || item.entry.canStart
    ListRow(title, detail = if (group || square || (item.whole && categoryRoot != null)) null else item.label.detailInCategory(categoryRoot),
        subtitle = if (!enabled) L.s(R.string.t_5bb00e40b9) else null,
        onClick = { onPick(item) },
        onLongClick = onEdit, longClickLabel = L.s(R.string.t_9d809f884b),
        leading = { ActivityGlyph(item.label) },
        trailing = {
            if (trailing != null) trailing()
            if (item.count != null && item.count > 0) Text("${item.count}", style = NotAi.type.bodySmall, color = c.ink2)
            if (group) M3Icon(Icon.ChevronRight, null, Modifier.size(16.dp), tint = c.ink2)
        })
}
