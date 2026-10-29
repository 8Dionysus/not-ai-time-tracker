@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.notai.tracker.android.ui


import dev.notai.tracker.android.L
import androidx.compose.ui.platform.testTag

import dev.notai.tracker.android.R

import androidx.compose.ui.res.stringResource

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.produceState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.DpOffset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.notai.tracker.FocusMode
import dev.notai.tracker.FocusPhase
import dev.notai.tracker.Mode
import dev.notai.tracker.Snapshot
import java.time.ZoneId

/** Everything the timer screen can ask its host to open. */
class TimerActions(
    val pick: (PickMode) -> Unit,
    val edit: (EntryUi, DayUi) -> Unit,
    val manual: (DayUi) -> Unit,
    val earlier: () -> Unit,
    val resolve: () -> Unit,
    val toggleTheme: () -> Unit,
    /** Shared by swipe and the long-press menu. Returns true when the record is already gone. */
    val delete: suspend (EntryUi) -> Boolean = { false },
    /** The current record actually began before Start was pressed. */
    val startEarlier: () -> Unit = {},
    /** An unrecorded stretch between two records: opens the ordinary entry form on exactly that stretch. */
    val fillGap: (GapUi, DayUi) -> Unit = { _, _ -> },
)

private enum class HeroKind { LOADING, IDLE, ACTIVE, RECOVERY, FOCUS }
private data class HeroState(val kind: HeroKind,val snapshot: Snapshot?)

@Composable
fun TimerScreen(snapshot: Snapshot?, home: HomeData?, vm: TrackerViewModel, actions: TimerActions, dark: Boolean, modifier: Modifier = Modifier) {
    val c = NotAi.colors
    val zone = home?.zone ?: ZoneId.systemDefault()
    // Gaps stay folded per day until asked for: visible, never nagging.
    var openGaps by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(listOf<String>()) }
    val picked by vm.picked.collectAsStateWithLifecycle()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val reveal = remember { SwipeReveal() }
    // Any scroll folds an uncovered Delete back: it never stays armed under a moving finger.
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) reveal.open = null }
    CompositionLocalProvider(LocalSwipeReveal provides reveal) {
    LazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "top") {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Wordmark(Modifier.weight(1f))
                Text(home?.today?.let { Format.headerDate(it) } ?: "", style = NotAi.type.bodySmall, color = c.ink2)
                IconAction(if (dark) Icon.Sun else Icon.Moon, if (dark) L.s(R.string.t_b45bbb4570) else L.s(R.string.t_ec1c55d866), actions.toggleTheme)
            }
        }
        item(key = "hero") {
            val kind = when {
                snapshot == null -> HeroKind.LOADING
                snapshot.focus != null -> HeroKind.FOCUS
                snapshot.mode == Mode.RECOVERY -> HeroKind.RECOVERY
                snapshot.mode == Mode.IDLE -> HeroKind.IDLE
                else -> HeroKind.ACTIVE
            }
            val reduced = NotAi.reducedMotion
            // Outgoing content keeps the snapshot that belongs to its own kind.
            // A completed Focus must not render against the new idle snapshot.
            AnimatedContent(HeroState(kind,snapshot), Modifier.fillMaxWidth(), contentKey = { it.kind }, transitionSpec = {
                if (reduced) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                else (fadeIn(tween(260, 90)) + slideInVertically(tween(320)) { it / 12 }) togetherWith fadeOut(tween(120))
            }, label = "hero") { state ->
                Box(Modifier.fillMaxWidth().neonGlow(state.kind == HeroKind.ACTIVE || state.kind == HeroKind.FOCUS).padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 8.dp), contentAlignment = Alignment.TopCenter) {
                    when (state.kind) {
                        HeroKind.LOADING -> Spacer(Modifier.height(300.dp))
                        HeroKind.IDLE -> IdleHero(home, vm, actions)
                        HeroKind.ACTIVE -> ActiveHero(state.snapshot!!, home, vm, actions, zone)
                        HeroKind.RECOVERY -> RecoveryHero(state.snapshot!!, home, vm, actions, zone)
                        HeroKind.FOCUS -> FocusHero(state.snapshot!!, home, vm, actions, zone)
                    }
                }
            }
        }
        if (home != null) history(home, snapshot, vm, actions, openGaps, { d -> openGaps = if (d in openGaps) openGaps - d else openGaps + d }, picked)
    }
    }
}

/** Neon only: a wash of the accent behind the hero that breathes with the shared pulse. Recording glows clearly,
 * rest glows faintly; with animations off it holds still at the midpoint. */
@Composable
private fun Modifier.neonGlow(live: Boolean): Modifier {
    val c = NotAi.colors
    if (!c.glow) return this
    val pulse = NotAi.neonPulse
    return this.drawBehind {
        val p = pulse.value
        val main = if (live) 0.15f + 0.11f * p else 0.06f + 0.05f * p
        val side = if (live) 0.07f + 0.06f * (1f - p) else 0.03f + 0.03f * (1f - p)
        val a = c.amber.copy(alpha = main); val b = c.amber.copy(alpha = side)
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(a, a.copy(alpha = 0f)),
            androidx.compose.ui.geometry.Offset(size.width * (0.42f + 0.08f * p), size.height * 0.36f), size.width * (0.52f + 0.06f * p)), radius = size.width)
        drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(b, b.copy(alpha = 0f)),
            androidx.compose.ui.geometry.Offset(size.width * (0.78f - 0.08f * p), size.height * 0.6f), size.width * 0.5f), radius = size.width)
    }
}

@Composable
fun Wordmark(modifier: Modifier = Modifier, size: Int = 18) {
    val c = NotAi.colors
    Row(modifier) {
        Text("(not)", style = NotAi.type.label.copy(fontSize = size.sp, fontWeight = FontWeight.Medium), color = c.ink2)
        // Neon: the "AI" carries the accent with a soft light around it.
        Text("AI", style = NotAi.type.label.copy(fontSize = size.sp, fontWeight = FontWeight.Medium,
            shadow = if (c.glow) androidx.compose.ui.graphics.Shadow(c.amber.copy(alpha = 0.7f), blurRadius = 14f) else null), color = if (c.glow) c.amberInk else c.ink)
    }
}

@Composable
private fun IdleHero(home: HomeData?, vm: TrackerViewModel, actions: TimerActions) {
    val c = NotAi.colors
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.height(128.dp).widthIn(min = 280.dp).clip(RoundedCornerShape(64.dp))
            .pressable(haptic(HapticFeedbackType.Confirm, vm::startBlank), label = L.s(R.string.t_13f1659f76)).semantics { contentDescription = L.s(R.string.t_13f1659f76) },
            contentAlignment = Alignment.Center) {
            IntervalFrame(IntervalState.IDLE, bracketWidth = 26.dp, bracketHeight = 104.dp, gap = 22.dp, opening = false, invite = NotAi.appearance.pulse) {
                Text(stringResource(R.string.editorial_tracker_draft_5799c96ef993116ab577_v0), style = NotAi.type.start, color = c.ink)
            }
        }
        Text(L.s(R.string.t_49c5f1afe2), style = NotAi.type.bodySmall, color = c.ink2, textAlign = TextAlign.Center)
        Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val plan = home?.plan.orEmpty()
            if (plan.isNotEmpty()) {
                SectionLabel(L.s(R.string.t_6cde6c34a0))
                PlanChips(plan, vm)
                Spacer(Modifier.height(2.dp))
            }
            val quick = home?.quick.orEmpty()
            val dismissed by vm.starterDismissed.collectAsStateWithLifecycle()
            // A new install has no favourites or history yet: invite a few own activities instead of an empty section.
            if (home != null && quick.isEmpty() && !dismissed) StarterCard(vm)
            else {
                if (quick.isNotEmpty()) SectionLabel(L.s(R.string.t_fc8e4e2d1c))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    quick.forEach { q -> QuickChip(q, vm) }
                    Chip(if (quick.isEmpty()) L.s(R.string.t_549bc063f3) else L.s(R.string.t_c0634e4cb3), { actions.pick(PickMode.START) }, dashed = true, icon = Icon.Plus)
                }
            }
            if (NotAi.appearance.showFocus) FocusLink(home?.focusMinutes ?: 25, vm, Modifier.align(Alignment.Start).offset(x = (-12).dp))
        }
    }
}

@Composable
private fun Digits(ms: Long, state: IntervalState) {
    val c = NotAi.colors
    val color by animateColorAsState(if (state == IntervalState.RUNNING) c.ink else c.ink2, tween(400), label = "digits")
    Row(modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = L.s(R.string.t_d349cb398d, Format.duration(ms)) }) {
        Text(Format.timerMain(ms), style = NotAi.type.timer, color = color, modifier = Modifier.alignByBaseline())
        Text(Format.timerSeconds(ms), style = NotAi.type.timerSeconds, color = c.ink2, modifier = Modifier.alignByBaseline().padding(start = 4.dp).widthIn(min = 30.dp))
    }
}

@Composable
private fun CurrentLabel(home: HomeData?, onClick: () -> Unit) {
    val c = NotAi.colors
    val label = home?.currentLabel
    if (label == null || label.unclassified) {
        Row(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(c.amberWash).pressable(onClick, label = L.s(R.string.t_5916628df1))
            .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActivityGlyph(ActivityGlyphs.unclassified, null, size = 18.dp, unclassified = true)
            Text(stringResource(R.string.editorial_tracker_draft_f49276090fc98f59c7eb_v0), style = NotAi.type.label, color = c.ink)
            Text(stringResource(R.string.editorial_tracker_draft_d134c6d82d62e46ec879_v0), style = NotAi.type.label.copy(fontWeight = FontWeight.Medium), color = c.amberInk)
        }
    } else {
        Row(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).pressable(onClick, label = L.s(R.string.t_2840f49e22))
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ActivityGlyph(label)
            Row(Modifier.weight(1f, fill = false)) {
                Text(label.title, style = NotAi.type.label, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                label.detail?.let { Text(" $it", style = NotAi.type.label, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
            }
            M3Icon(Icon.ChevronDown, null, Modifier.size(16.dp), tint = c.ink2)
        }
    }
}

@Composable
private fun ActiveHero(s: Snapshot, home: HomeData?, vm: TrackerViewModel, actions: TimerActions, zone: ZoneId) {
    val c = NotAi.colors
    val state = if (s.mode == Mode.RUNNING) IntervalState.RUNNING else IntervalState.PAUSED
    val start = home?.currentStart
    val labelled = home?.currentLabel?.unclassified == false
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.height(128.dp), contentAlignment = Alignment.Center) {
            IntervalFrame(state) { Digits(s.trustedDurationMs, state) }
        }
        CurrentLabel(home) { actions.pick(if (labelled) PickMode.SWITCH else PickMode.LABEL) }
        val line = when {
            s.mode == Mode.PAUSED -> L.s(R.string.t_f94b15c5ce)
            start == null -> ""
            labelled -> L.s(R.string.t_e2e41650a0, Format.clock(start, zone))
            else -> L.s(R.string.t_03cb42c402, Format.clock(start, zone))
        }
        Text(line, style = NotAi.type.caption, color = c.ink2, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        ButtonPair(Modifier.padding(top = 4.dp)) { m ->
            if (s.mode == Mode.RUNNING) PillButton(L.s(R.string.t_03498e395a), haptic(HapticFeedbackType.ToggleOff, vm::pause), m, ButtonKind.TONAL, Icon.Pause)
            else PillButton(L.s(R.string.t_3f75368ab9), haptic(HapticFeedbackType.ToggleOn, vm::resume), m, ButtonKind.AMBER, Icon.Play)
            PillButton(stringResource(R.string.editorial_tracker_draft_f386fe7c3de9982b475f_v0), haptic(HapticFeedbackType.Confirm, vm::stop), m, ButtonKind.PRIMARY, Icon.Stop)
        }
        // Two quiet fixes of the same kind: the record began before Start, or the switch happened before it was tapped.
        val earlier = s.mode == Mode.RUNNING && "switchedEarlier" in s.actions
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TimeFix(L.s(R.string.t_909baadb08), actions.startEarlier)
            if (earlier) {
                Text("·", style = NotAi.type.bodySmall, color = c.ink3)
                TimeFix(L.s(R.string.t_6a0e6066b0), actions.earlier)
            }
        }
        val plan = home?.plan.orEmpty().filter { !it.started }
        if (plan.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(L.s(R.string.t_edd23ab082))
            PlanChips(plan, vm)
        }
    }
}

/** Tap starts Focus, long press opens its lengths. Both are announced to accessibility services. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FocusLink(minutes: Long, vm: TrackerViewModel, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Box(modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp))
        .combinedClickable(onClickLabel = L.s(R.string.t_41a9abce27), onLongClickLabel = L.s(R.string.t_9ebe3ac111),
            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); vm.openFocusEditor() }, onClick = vm::startFocus)
        .padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(L.s(R.string.t_e0382438fe, minutes), style = NotAi.type.button, color = NotAi.colors.amberInk)
    }
}

/** A quick-start chip. Long press: edit, star/unstar, and the full ordered list of favourites. */
@Composable
private fun QuickChip(q: QuickStart, vm: TrackerViewModel) {
    val c = NotAi.colors
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box {
        Chip(q.title, { vm.choose(q.entry, PickMode.START, reference = q.reference, expected = q.expected) }, slot = q.slot, glyph = q.glyph,
            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true })
        DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(16.dp), containerColor = c.raised, border = BorderStroke(1.dp, c.rule), shadowElevation = 8.dp) {
            @Composable fun item(text: String, icon: ImageVector, action: () -> Unit) = DropdownMenuItem(text = { Text(text, style = NotAi.type.body, color = c.ink) },
                onClick = { menu = false; action() }, leadingIcon = { M3Icon(icon, null, Modifier.size(18.dp), tint = c.ink2) }, modifier = Modifier.widthIn(min = 210.dp))
            val card = q.entry.selection.cardId
            if (q.favorite) item(L.s(R.string.t_9d809f884b), Icon.Edit) { vm.openEditor(EditTarget.Fav(q.entry.sourceId)) }
            else if (card != null) item(L.s(R.string.t_de5eb09e17), Icon.Edit) { vm.openEditor(EditTarget.Card(card)) }
            if (q.favorite) item(L.s(R.string.t_d9f48a75fd), Icon.StarLine) { vm.removeFavorites(vm.favoriteIds(q.entry.selection)) }
            else item(L.s(R.string.t_5da9e0e83b), Icon.Star) { vm.saveFavorite(q.title, q.entry.selection) }
            item(L.s(R.string.t_4ea0639432), Icon.Grip) { vm.openFavorites(true) }
        }
    }
}

@Composable
private fun TimeFix(text: String, onClick: () -> Unit) {
    Box(Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).pressable(onClick, label = text).padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center) {
        Text(text, style = NotAi.type.bodySmall.copy(fontWeight = FontWeight.Medium), color = NotAi.colors.amberInk)
    }
}

/** Today's plan. A tap starts (or switches to) it; a long press can drop it for today. Done ones stay, quietly checked. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlanChips(plan: List<PlanItem>, vm: TrackerViewModel) {
    val c = NotAi.colors
    val haptics = LocalHapticFeedback.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        plan.forEach { p ->
            var menu by remember { mutableStateOf(false) }
            Box {
                Row(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50))
                    .background(if (p.started) Color.Transparent else c.sunk)
                    .then(if (p.started) Modifier.border(1.dp, c.rule, RoundedCornerShape(50)) else Modifier)
                    .combinedClickable(enabled = !p.started, onClickLabel = L.s(R.string.t_afd78e8296), onLongClickLabel = L.s(R.string.t_b8b15fe0c2),
                        onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true }, onClick = { vm.startPlanned(p) })
                    .semantics { contentDescription = p.title + (p.time?.let { ", $it" } ?: "") + if (p.started) L.s(R.string.t_2f6a9e8aa1) else "" }
                    .padding(start = 11.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (p.started) M3Icon(Icon.Check, null, Modifier.size(16.dp), tint = c.ink2) else ActivityGlyph(p.glyph, p.slot, size = 17.dp)
                    Text(p.title, style = NotAi.type.bodySmall, color = if (p.started) c.ink2 else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    p.time?.let { Text(it, style = NotAi.type.bodySmall, color = c.ink2, maxLines = 1) }
                }
                DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(16.dp), containerColor = c.raised,
                    border = BorderStroke(1.dp, c.rule), shadowElevation = 8.dp) {
                    DropdownMenuItem(text = { Text(L.s(R.string.t_7b11e988e1), style = NotAi.type.body, color = c.ink) },
                        onClick = { menu = false; vm.skipPlanned(p) }, leadingIcon = { M3Icon(Icon.Close, null, Modifier.size(18.dp), tint = c.ink2) })
                }
            }
        }
    }
}

@Composable
private fun RecoveryHero(s: Snapshot, home: HomeData?, vm: TrackerViewModel, actions: TimerActions, zone: ZoneId) {
    val c = NotAi.colors
    val start = s.active?.start ?: home?.currentStart
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.height(128.dp), contentAlignment = Alignment.Center) {
            IntervalFrame(IntervalState.UNKNOWN, opening = false) {
                // Known part only. After a restart nothing after the start is trusted, so the start itself is shown.
                if (s.trustedDurationMs > 0 || start == null) Digits(s.trustedDurationMs, IntervalState.PAUSED)
                else Text(Format.clock(start, zone), style = NotAi.type.timer, color = c.ink2,
                    modifier = Modifier.semantics { contentDescription = L.s(R.string.t_49a37138c8, Format.clock(start, zone)) })
            }
        }
        home?.currentLabel?.takeIf { !it.unclassified }?.let { l ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActivityGlyph(l); Row { Text(l.title, style = NotAi.type.label, color = c.ink); l.detail?.let { Text(" $it", style = NotAi.type.label, color = c.ink2) } }
            }
        }
        Text(if (start != null) L.s(R.string.t_9f8007cba2) else L.s(R.string.t_5c65c982d1),
            style = NotAi.type.label.copy(fontWeight = FontWeight.Medium), color = c.ink, textAlign = TextAlign.Center)
        Text(stringResource(R.string.editorial_tracker_draft_801a0a1eac7e6d9ef9b4_v0),
            style = NotAi.type.bodySmall, color = c.ink2, textAlign = TextAlign.Center)
        Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(stringResource(R.string.editorial_tracker_draft_b048178228ee82e3e962_v0), actions.resolve, Modifier.fillMaxWidth(), ButtonKind.AMBER)
            PillButton(if (start != null) L.s(R.string.t_fd7707e6f0, Format.clock(start, zone)) else L.s(R.string.t_de8f3c16b9), vm::excludeTail, Modifier.fillMaxWidth(), ButtonKind.TONAL)
        }
    }
}

@Composable
private fun FocusHero(s: Snapshot, home: HomeData?, vm: TrackerViewModel, actions: TimerActions, zone: ZoneId) {
    val c = NotAi.colors
    val f = s.focus!!
    if (f.mode == FocusMode.RECOVERY) { RecoveryHero(s, home, vm, actions, zone); return }
    val work = f.phase == FocusPhase.WORK
    val state = if (f.mode == FocusMode.RUNNING) IntervalState.RUNNING else IntervalState.PAUSED
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (work) L.s(R.string.t_0c15fe2798, f.phaseIndex / 2 + 1) else L.s(R.string.t_01a3d66b22), style = NotAi.type.caption, color = c.ink2)
        Box(Modifier.height(128.dp), contentAlignment = Alignment.Center) {
            IntervalFrame(if (f.mode == FocusMode.DUE) IntervalState.PAUSED else state) {
                Text(Format.countdown(f.remainingMs), style = NotAi.type.timer, color = if (state == IntervalState.RUNNING) c.ink else c.ink2,
                    modifier = Modifier.semantics { contentDescription = L.s(R.string.t_c14f88246c, Format.duration(f.remainingMs)) })
            }
        }
        Text(if (f.mode == FocusMode.DUE) (if (work) L.s(R.string.t_c650387bcb) else L.s(R.string.t_bdcb031d09))
             else L.s(R.string.t_0faf5fa9ac, Format.duration(f.durationMs)), style = NotAi.type.bodySmall, color = c.ink2)
        val fraction = if (f.durationMs > 0) (f.elapsedMs.toFloat() / f.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.sunk)) {
            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(if (work) c.amber else c.ink3))
        }
        if (work) CurrentLabel(home) { actions.pick(PickMode.LABEL) }
        ButtonPair(Modifier.padding(top = 4.dp)) { m ->
            when (f.mode) {
                FocusMode.RUNNING -> PillButton(L.s(R.string.t_03498e395a), haptic(HapticFeedbackType.ToggleOff, vm::pause), m, ButtonKind.TONAL, Icon.Pause)
                FocusMode.PAUSED -> PillButton(L.s(R.string.t_3f75368ab9), haptic(HapticFeedbackType.ToggleOn, vm::resume), m, ButtonKind.AMBER, Icon.Play)
                else -> PillButton(if (work) L.s(R.string.t_0076203bff) else L.s(R.string.t_74b69f961b), vm::focusNext, m, ButtonKind.AMBER)
            }
            PillButton(stringResource(R.string.editorial_tracker_draft_d4c3ff0340d73303cb5b_v0), haptic(HapticFeedbackType.Confirm, vm::stop), m, ButtonKind.PRIMARY, Icon.Stop)
        }
        if (f.mode != FocusMode.DUE) PillButton(if (work) L.s(R.string.t_f4f34092fb) else L.s(R.string.t_c25bd1a7c6), vm::focusSkip, kind = ButtonKind.TEXT, height = 44.dp)
        Text(stringResource(R.string.editorial_tracker_draft_cbeca4591fe9810ca6fd_v0), style = NotAi.type.caption, color = c.ink2,
            textAlign = TextAlign.Center)
    }
}

private fun LazyListScope.history(home: HomeData, snapshot: Snapshot?, vm: TrackerViewModel, actions: TimerActions,
                                   openGaps: List<String>, toggleGaps: (String) -> Unit, picked: Set<String> = emptySet()) {
    home.days.forEach { day ->
        item(key = "day-${day.date}") {
            val c = NotAi.colors
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 4.dp)) {
                val empty = day.entries.isEmpty() && day.live.isEmpty() && day.recordedMs == 0L
                SectionTitle(Format.dayTitle(day.date, home.today), trailing = if (empty) null else Format.duration(day.recordedMs))
                Spacer(Modifier.height(10.dp))
                if (empty) Text(if (day.date == home.today) L.s(R.string.t_550c7a6d12) else L.s(R.string.t_2972f8b4c4),
                    style = NotAi.type.bodySmall, color = c.ink2)
                else {
                    val pieces = day.ribbon.map { RibbonPiece(it.start, it.end, it.slot?.let { s -> c.areas[s] } ?: c.none) } +
                        day.live.map { RibbonPiece(it.start, it.end, c.amber, live = true) }
                    if (NotAi.appearance.showRibbon) DayRibbon(day.start, day.end, pieces,zone = home.zone)
                    val fillable = day.gaps.count { it.kind == dev.notai.tracker.HistoryGapKind.UNRECORDED }
                    if (fillable > 0) {
                        val open = day.date.toString() in openGaps
                        Text((if (open) L.s(R.string.t_2960da94e0) else L.p(R.plurals.p_33de38af49, fillable) + L.s(R.string.t_ddca6f4db2)),
                            Modifier.padding(top = 6.dp).heightIn(min = 32.dp).pressable({ toggleGaps(day.date.toString()) },
                                label = if (open) L.s(R.string.t_57fe19481c) else L.s(R.string.t_5d09029804)).padding(vertical = 6.dp),
                            style = NotAi.type.caption.copy(fontWeight = FontWeight.Medium), color = c.amberInk)
                    }
                }
            }
        }
        // One line per record, newest first; unrecorded gaps in their place. Pauses live inside their record.
        val gaps = if (day.date.toString() in openGaps) day.gaps.filter { it.kind != dev.notai.tracker.HistoryGapKind.PAUSE } else emptyList()
        val rows: List<Any> = (day.records + gaps).sortedByDescending { if (it is RecordUi) it.start else (it as GapUi).start }
        items(rows, key = { r -> if (r is RecordUi) "${day.date}:${r.sessionId}" else "${day.date}:gap:${(r as GapUi).start}" }) { r ->
            if (r is RecordUi && picked.isNotEmpty()) RecordRow(r, home.zone, onOpen = { vm.togglePicked(r.sessionId) }, onRepeat = { vm.repeat(r.entry) },
                modifier = Modifier.animateItem().padding(horizontal = 20.dp), picked = r.sessionId in picked, choosing = true)
            else if (r is RecordUi) SwipeToDelete(r.entry, actions.delete, Modifier.animateItem()) {
                RecordRow(r, home.zone, onOpen = { actions.edit(r.entry, day) }, onRepeat = { vm.repeat(r.entry) },
                    onDelete = { actions.delete(r.entry) }, modifier = Modifier.padding(horizontal = 20.dp), onPickMany = { vm.togglePicked(r.sessionId) })
            } else GapRow(r as GapUi, home.zone, { actions.fillGap(r, day) }, Modifier.animateItem().padding(horizontal = 20.dp))
        }
        if (day.date == home.today) item(key = "manual") {
            Row(Modifier.padding(horizontal = 20.dp).fillMaxWidth().heightIn(min = 48.dp).pressable({ actions.manual(day) }),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                M3Icon(Icon.Plus, null, Modifier.size(16.dp), tint = NotAi.colors.ink2)
                Text(stringResource(R.string.editorial_tracker_draft_534fa1c71825748c438f_v0), style = NotAi.type.bodySmall, color = NotAi.colors.ink2)
            }
        }
    }
    if (home.canLoadMore) item(key = "more") {
        Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
            PillButton(stringResource(R.string.editorial_tracker_draft_5d4de510a166f4a6deed_v0), vm::showMoreDays, kind = ButtonKind.OUTLINE, height = 44.dp)
        }
    }
}

/** Horizontal intention is checked before consuming any part of a scrolling gesture. */
@Composable
private fun SwipeToDelete(e: EntryUi, delete: suspend (EntryUi) -> Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    IntentionalDeleteSwipe({ delete(e) },modifier.then(Modifier.testTag("history-record:${e.sessionId}")),revealKey = e.sessionId,
        actionTag = "swipe-delete:${e.sessionId}",content = content)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun EntryRow(e: EntryUi, zone: ZoneId, onOpen: () -> Unit, onRepeat: () -> Unit, modifier: Modifier = Modifier, onDelete: (suspend () -> Unit)? = null,
             onPickMany: (() -> Unit)? = null, picked: Boolean = false, choosing: Boolean = false) {
    val c = NotAi.colors
    var menu by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxWidth()) {
        Box {
            EntryMenu(menu, onDismiss = { menu = false }, onOpen = onOpen, onRepeat = onRepeat,
                onDelete = onDelete?.let { d -> { scope.launch { d() } } }, onPickMany = onPickMany)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp)
            .combinedClickable(onClickLabel = if (choosing) (if (picked) L.s(R.string.t_d184a16515) else L.s(R.string.t_fe4b0c80f0)) else L.s(R.string.t_8a22f78e9c),
                onLongClickLabel = if (choosing) null else L.s(R.string.t_9d5b844654),
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); if (choosing) onOpen() else menu = true }, onClick = onOpen)
            .semantics { if (choosing) selected = picked },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (choosing) Box(Modifier.size(24.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (picked) c.ink else c.paper)
                .border(if (picked) 0.dp else 1.5.dp, c.ink3, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                if (picked) M3Icon(Icon.Check, null, Modifier.size(14.dp), tint = c.paper)
            } else ActivityGlyph(e.label)
            Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LabelText(e.label)
                Text(Format.range(e.start, e.end, zone) + (e.note?.let { ", $it" } ?: ""), style = NotAi.type.caption, color = c.ink2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(Format.duration(e.durationMs), style = NotAi.type.body.copy(fontSize = 15.sp), color = c.ink)
            IconAction(Icon.Repeat, L.s(R.string.t_6e166fe43b, e.label.title), onRepeat, size = 16.dp)
        }
        Hairline()
    }
}

/** Title with its quiet detail as one text: long names wrap to a second line instead of cutting the detail. */
@Composable
fun LabelText(label: Label, maxLines: Int = 2, style: androidx.compose.ui.text.TextStyle = NotAi.type.body) {
    val c = NotAi.colors
    Text(androidx.compose.ui.text.buildAnnotatedString {
        append(label.title)
        label.detail?.let { withStyle(androidx.compose.ui.text.SpanStyle(color = c.ink2)) { append(" "); append(it) } }
    }, style = style, color = c.ink, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/**
 * One record of the day. A record split by pauses stays one line with a quiet "2 паузы" mark; that mark unfolds the
 * segments and the pauses between them. Tap still opens the correction of the whole record, long press its menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordRow(r: RecordUi, zone: ZoneId, onOpen: () -> Unit, onRepeat: () -> Unit, modifier: Modifier = Modifier, onDelete: (suspend () -> Unit)? = null,
                      onPickMany: (() -> Unit)? = null, picked: Boolean = false, choosing: Boolean = false) {
    val c = NotAi.colors
    val e = r.entry
    var menu by remember { mutableStateOf(false) }
    var open by androidx.compose.runtime.saveable.rememberSaveable(r.sessionId) { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val split = r.segments.size > 1
    Column(modifier.fillMaxWidth().animateContentSize(tween(if (NotAi.reducedMotion) 0 else 220))) {
        Box {
            EntryMenu(menu, onDismiss = { menu = false }, onOpen = onOpen, onRepeat = onRepeat,
                onDelete = onDelete?.let { d -> { scope.launch { d() } } }, onPickMany = onPickMany)
        }
        val compact = NotAi.appearance.compact
        Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 48.dp else 60.dp)
            .combinedClickable(onClickLabel = if (choosing) (if (picked) L.s(R.string.t_d184a16515) else L.s(R.string.t_fe4b0c80f0)) else L.s(R.string.t_8a22f78e9c),
                onLongClickLabel = if (choosing) null else L.s(R.string.t_9d5b844654),
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); if (choosing) onOpen() else menu = true }, onClick = onOpen)
            .semantics { if (choosing) selected = picked },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (choosing) Box(Modifier.size(24.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(if (picked) c.ink else c.paper)
                .border(if (picked) 0.dp else 1.5.dp, c.ink3, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                if (picked) M3Icon(Icon.Check, null, Modifier.size(14.dp), tint = c.paper)
            } else ActivityGlyph(e.label)
            Column(Modifier.weight(1f).padding(vertical = if (compact) 4.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 0.dp else 2.dp)) {
                LabelText(e.label, maxLines = if (compact) 1 else 2)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(Format.range(r.start, r.end, zone) + (e.note?.let { ", $it" } ?: ""), Modifier.weight(1f, fill = false), style = NotAi.type.caption, color = c.ink2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (split || r.mixed) Row(Modifier.padding(start = 6.dp).heightIn(min = 28.dp).clip(RoundedCornerShape(14.dp))
                        .pressable({ open = !open }, label = if (open) L.s(R.string.t_894c240b90) else L.s(R.string.t_5f239661cc)).padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(if (r.pauses.isNotEmpty()) L.p(R.plurals.p_c9ec2228f3, r.pauses.size) else L.s(R.string.t_806764743a),
                            style = NotAi.type.caption.copy(fontWeight = FontWeight.Medium), color = c.amberInk, maxLines = 1)
                        M3Icon(Icon.ChevronDown, null, Modifier.size(12.dp).graphicsLayerRotate(if (open) 180f else 0f), tint = c.amberInk)
                    }
                }
            }
            Text(Format.duration(r.recordedMs), style = NotAi.type.body.copy(fontSize = 15.sp), color = c.ink)
            if (!choosing) IconAction(Icon.Repeat, L.s(R.string.t_6e166fe43b, e.label.title), onRepeat, size = 16.dp)
        }
        if (open && !choosing) Column(Modifier.padding(start = 32.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Chronological: segment, pause, segment… Durations are the core's per-segment values.
            val lines = (r.segments.map { it.start to it } + r.pauses.map { it.start to it }).sortedBy { it.first }
            lines.forEach { (_, item) ->
                Row(Modifier.fillMaxWidth().heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (item is EntryUi) {
                        Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(item.label.slot?.let { c.areas[it] } ?: c.none))
                        Text(Format.range(item.start, item.end, zone), style = NotAi.type.caption, color = c.ink)
                        Text(if (r.mixed) item.label.title else "", Modifier.weight(1f), style = NotAi.type.caption, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Format.duration(item.durationMs), style = NotAi.type.caption, color = c.ink2)
                    } else {
                        val p = item as dev.notai.tracker.DraftInterval
                        Box(Modifier.size(width = 6.dp, height = 2.dp).background(c.ink3))
                        Text(Format.range(p.start, p.end, zone), style = NotAi.type.caption, color = c.ink3)
                        Text(L.s(R.string.t_9627af1c25), Modifier.weight(1f), style = NotAi.type.caption, color = c.ink3)
                    }
                }
            }
        }
        Hairline()
    }
}

private fun Modifier.graphicsLayerRotate(deg: Float) = this.then(Modifier.rotate(deg))

/**
 * A quiet row for a stretch without a record. Unrecorded time can be filled in one tap; a pause or unknown time is only named.
 * No duration: the app does not measure what was not recorded.
 */
@Composable
private fun GapRow(g: GapUi, zone: ZoneId, onFill: () -> Unit, modifier: Modifier = Modifier) {
    val c = NotAi.colors
    val fillable = g.kind == dev.notai.tracker.HistoryGapKind.UNRECORDED
    val what = when (g.kind) {
        dev.notai.tracker.HistoryGapKind.UNRECORDED -> L.s(R.string.t_ef34d1807a)
        dev.notai.tracker.HistoryGapKind.PAUSE -> L.s(R.string.t_9627af1c25)
        dev.notai.tracker.HistoryGapKind.RECOVERY_UNCERTAIN -> L.s(R.string.t_d4993a1dc7)
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).then(if (fillable) Modifier.pressable(onFill, label = L.s(R.string.t_f2a9788239)) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = "${Format.range(g.start, g.end, zone)}, $what" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // An empty dashed ring in the icon column: a place where nothing was recorded.
            androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
                drawCircle(c.ink3, radius = 6.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx(),
                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))))
            }
            Text(Format.range(g.start, g.end, zone), style = NotAi.type.caption, color = c.ink2)
            Text(what, Modifier.weight(1f), style = NotAi.type.caption, color = c.ink3)
            if (fillable) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                M3Icon(Icon.Plus, null, Modifier.size(14.dp), tint = c.amberInk)
                Text(L.s(R.string.t_4be730321c), style = NotAi.type.caption.copy(fontWeight = FontWeight.Medium), color = c.amberInk)
            }
        }
        Hairline()
    }
}

/** Long press: the same three actions as elsewhere, delete last and in the danger colour. */
@Composable
private fun EntryMenu(open: Boolean, onDismiss: () -> Unit, onOpen: () -> Unit, onRepeat: () -> Unit, onDelete: (() -> Unit)?, onPickMany: (() -> Unit)? = null) {
    val c = NotAi.colors
    DropdownMenu(open, onDismiss, offset = DpOffset(56.dp, 0.dp), shape = RoundedCornerShape(16.dp), containerColor = c.raised,
        border = BorderStroke(1.dp, c.rule), shadowElevation = 8.dp) {
        @Composable fun item(text: String, icon: ImageVector, color: Color, action: () -> Unit) = DropdownMenuItem(
            text = { Text(text, style = NotAi.type.body, color = color) }, onClick = { onDismiss(); action() },
            leadingIcon = { M3Icon(icon, null, Modifier.size(18.dp), tint = color) }, modifier = Modifier.widthIn(min = 196.dp))
        item(L.s(R.string.t_9b1c9a0216), Icon.Edit, c.ink, onOpen)
        item(L.s(R.string.t_9e506acb19), Icon.Repeat, c.ink, onRepeat)
        if (onPickMany != null) item(L.s(R.string.t_6155f92695), Icon.Check, c.ink, onPickMany)
        if (onDelete != null) item(stringResource(R.string.editorial_tracker_draft_98c2a48d88776822811d_v0), Icon.Trash, c.danger, onDelete)
    }
}

/** Light, meaningful haptics: confirm for start/stop, toggle for pause/resume. Nothing on ordinary taps. */
@Composable
private fun haptic(type: HapticFeedbackType, action: () -> Unit): () -> Unit {
    val h = LocalHapticFeedback.current
    return { h.performHapticFeedback(type); action() }
}

/**
 * First launch: a dozen everyday activities to pick from. "Done" stores the picked ones as favourites, which
 * are the quick-start chips from then on. "Not now" hides the card for good; everything stays in the search.
 */
@Composable
private fun StarterCard(vm: TrackerViewModel) {
    val c = NotAi.colors
    val starters by produceState(emptyList<Starter>()) { value = vm.starterChoices() }
    var picked by remember { mutableStateOf(listOf<String>()) }
    if (starters.isEmpty()) return
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.raised).border(1.dp, c.rule, RoundedCornerShape(20.dp))
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.editorial_tracker_draft_748b237516bc1d2267fc_v0), style = NotAi.type.section, color = c.ink)
            Text(stringResource(R.string.editorial_tracker_draft_0475a52ebc49a52f249b_v0),
                style = NotAi.type.caption, color = c.ink2)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            starters.forEach { st ->
                val on = st.title in picked
                Row(Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50))
                    .background(if (on) c.ink else c.paper).border(1.dp, if (on) c.ink else c.rule, RoundedCornerShape(50))
                    .semantics { selected = on }
                    .pressable({ picked = if (on) picked - st.title else picked + st.title }, role = Role.Checkbox, label = st.title)
                    .padding(start = 11.dp, end = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (on) M3Icon(Icon.Check, null, Modifier.size(16.dp), tint = c.paper)
                    else ActivityGlyph(st.glyph, st.slot, size = 16.dp)
                    Text(st.title, style = NotAi.type.bodySmall, color = if (on) c.paper else c.ink, maxLines = 1)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PillButton(stringResource(R.string.editorial_tracker_draft_16804fc936758c4dd92a_v0), vm::dismissStarter, kind = ButtonKind.TEXT, height = 44.dp, modifier = Modifier.offset(x = (-12).dp))
            Spacer(Modifier.weight(1f))
            PillButton(if (picked.isEmpty()) L.s(R.string.t_398c7d4f7b) else L.s(R.string.t_97bcfbe748, picked.size), {
                vm.saveStarters(starters.filter { it.title in picked })
            }, kind = ButtonKind.PRIMARY, height = 44.dp, enabled = picked.isNotEmpty())
        }
    }
}
