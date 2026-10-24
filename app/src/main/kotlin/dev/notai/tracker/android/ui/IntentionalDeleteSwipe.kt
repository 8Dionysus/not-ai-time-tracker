package dev.notai.tracker.android.ui

import dev.notai.tracker.android.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** No velocity shortcut: a deliberate, predominantly horizontal left drag must cover 60%.
 * Vertical/diagonal intention exits without consuming, leaving the history scroller in charge. */
@Composable internal fun IntentionalDeleteSwipe(delete: suspend () -> Boolean,modifier: Modifier = Modifier,content: @Composable () -> Unit) {
    val c = NotAi.colors
    var width by remember { mutableIntStateOf(0) }
    var offset by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    val latestDelete by rememberUpdatedState(delete)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val minimum = with(LocalDensity.current) { 120.dp.toPx() }
    val maxVertical = with(LocalDensity.current) { 48.dp.toPx() }
    Box(modifier.onSizeChanged { width = it.width }) {
        if(offset < 0) Box(Modifier.matchParentSize().background(c.danger.copy(alpha = 0.14f)).padding(end = 24.dp),contentAlignment = Alignment.CenterEnd) {
            Text(stringResource(R.string.editorial_tracker_draft_98c2a48d88776822811d_v0),style = NotAi.type.bodySmall,color = c.danger)
        }
        Box(Modifier.graphicsLayer { translationX = offset }.background(c.paper).pointerInput(width,busy) {
            if(busy || width == 0) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var total = Offset.Zero
                var horizontal = false
                try {
                    while(true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if(change.isConsumed) break
                        total += change.positionChange()
                        if(!horizontal) {
                            if(abs(total.y) > viewConfiguration.touchSlop || total.x > viewConfiguration.touchSlop) break
                            if(total.x < -2 * viewConfiguration.touchSlop) {
                                if(abs(total.x) < 2.5f * abs(total.y)) break
                                horizontal = true
                            }
                        }
                        if(horizontal) {
                            if(abs(total.y) > maxVertical || abs(total.y) > abs(total.x) * 0.4f) break
                            change.consume()
                            offset = total.x.coerceIn(-width.toFloat(),0f)
                        }
                        if(!change.pressed) {
                            if(horizontal && -total.x >= max(minimum,width * 0.6f)) {
                                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                busy = true
                                scope.launch { try { latestDelete() } finally { busy = false } }
                            }
                            break
                        }
                    }
                } finally { offset = 0f }
            }
        }) { content() }
    }
}
