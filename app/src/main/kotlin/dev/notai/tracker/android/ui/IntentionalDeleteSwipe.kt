package dev.notai.tracker.android.ui

import dev.notai.tracker.android.R

import androidx.compose.ui.res.stringResource

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Which history row currently shows its Delete action. One at a time; scrolling closes it. */
@Stable class SwipeReveal { var open by mutableStateOf<Any?>(null) }
val LocalSwipeReveal = staticCompositionLocalOf<SwipeReveal?> { null }

/**
 * A left swipe never deletes by itself: it only uncovers a Delete button behind the row, and that button must be
 * tapped. Intent is checked before anything is consumed, so vertical or diagonal movement stays with the scroller.
 * Tapping the uncovered row, swiping it back, scrolling or opening another row closes it again.
 */
@Composable internal fun IntentionalDeleteSwipe(delete: suspend () -> Boolean, modifier: Modifier = Modifier, revealKey: Any? = null,
                                                actionTag: String? = null, content: @Composable () -> Unit) {
    val c = NotAi.colors
    val shared = LocalSwipeReveal.current
    val local = remember { SwipeReveal() }
    val reveal = shared ?: local
    val key = revealKey ?: local
    var width by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    val latestDelete by rememberUpdatedState(delete)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val reduced = NotAi.reducedMotion
    val density = LocalDensity.current
    val action = with(density) { 104.dp.toPx() }
    val maxVertical = with(density) { 48.dp.toPx() }
    val isOpen = reveal.open == key
    var settle by remember { mutableStateOf<Job?>(null) }
    fun settleTo(target: Float) {
        settle?.cancel()
        settle = scope.launch {
            if (reduced) offset = target
            else animate(offset, target, animationSpec = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) { v, _ -> offset = v }
        }
    }
    // Another row opened, the list scrolled or the record changed: fold back.
    LaunchedEffect(isOpen) { if (!isOpen && offset != 0f) settleTo(0f) }
    DisposableEffect(key) { onDispose { if (reveal.open == key) reveal.open = null } }
    val deleteLabel = stringResource(R.string.editorial_tracker_draft_98c2a48d88776822811d_v0)
    Box(modifier.onSizeChanged { width = it.width }) {
        if (offset < 0f) Box(Modifier.matchParentSize().padding(vertical = 4.dp).padding(end = 12.dp), contentAlignment = Alignment.CenterEnd) {
            val shown = (-offset / action).coerceIn(0f, 1f)
            Row(Modifier.width(88.dp).fillMaxHeight().graphicsLayer { alpha = shown; scaleX = 0.85f + 0.15f * shown; scaleY = 0.85f + 0.15f * shown }
                .clip(RoundedCornerShape(14.dp)).background(c.danger)
                .then(if (actionTag != null) Modifier.testTag(actionTag) else Modifier)
                .pressable({
                    if (busy || reveal.open != key) return@pressable
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    busy = true
                    scope.launch { try { latestDelete() } finally { busy = false; if (reveal.open == key) reveal.open = null } }
                }, label = deleteLabel),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                M3Icon(Icon.Trash, null, Modifier.size(16.dp), tint = c.paper)
                Text(deleteLabel, style = NotAi.type.caption.copy(fontWeight = FontWeight.SemiBold), color = c.paper, maxLines = 1)
            }
        }
        Box(Modifier.graphicsLayer { translationX = offset }.background(c.paper).pointerInput(width, busy, key) {
            if (busy || width == 0) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val startOffset = offset
                var total = Offset.Zero
                var horizontal = false
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) break
                    total += change.positionChange()
                    if (!horizontal) {
                        // Leftward from rest; either way once open. Mostly-vertical movement belongs to the list.
                        val slop = viewConfiguration.touchSlop
                        if (abs(total.y) > slop) break
                        if (startOffset == 0f && total.x > slop) break
                        if (abs(total.x) > 2 * slop) {
                            if (abs(total.x) < 2.5f * abs(total.y)) break
                            horizontal = true
                            settle?.cancel()
                            if (reveal.open != key) reveal.open = null
                        }
                    }
                    if (horizontal) {
                        if (abs(total.y) > maxVertical) break
                        change.consume()
                        val raw = startOffset + total.x
                        // Past the button the row resists: there is nothing further to uncover.
                        offset = if (raw < -action) -action + (raw + action) * 0.25f else raw.coerceAtMost(0f)
                    }
                    if (!change.pressed) break
                }
                if (horizontal) {
                    val opened = offset < -action * 0.5f
                    if (opened && reveal.open != key) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    reveal.open = if (opened) key else null
                    settleTo(if (opened) -action else 0f)
                }
            }
        }) {
            content()
            // While uncovered, a tap on the row only folds it back: it must not open the editor behind the button.
            if (isOpen || offset < 0f) Box(Modifier.matchParentSize().pressable({ reveal.open = null; settleTo(0f) }, label = null))
        }
    }
}
