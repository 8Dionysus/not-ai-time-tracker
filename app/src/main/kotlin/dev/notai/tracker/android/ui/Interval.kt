package dev.notai.tracker.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The (not)AI mark: an interval. Idle is an empty pair, running breathes, paused holds still,
 * unknown time leaves the closing bracket open (dotted) until the person states the end. */
enum class IntervalState { IDLE, RUNNING, PAUSED, UNKNOWN }

/** One parenthesis drawn from the design path (viewBox 26×120). */
@Composable
fun Bracket(left: Boolean, color: Color, modifier: Modifier = Modifier, width: Dp = 28.dp, height: Dp = 120.dp,
            stroke: Dp = 2.2.dp, dotted: Boolean = false, glow: Boolean = false, glowColor: Color = color, glowStrength: Float = 1f) {
    val density = LocalDensity.current
    val pulse = NotAi.neonPulse
    Canvas(modifier.size(width, height)) {
        val sx = size.width / 26f; val sy = size.height / 120f
        val path = Path().apply {
            if (left) { moveTo(21f * sx, 4f * sy); cubicTo(5f * sx, 34f * sy, 5f * sx, 86f * sy, 21f * sx, 116f * sy) }
            else { moveTo(5f * sx, 4f * sy); cubicTo(21f * sx, 34f * sy, 21f * sx, 86f * sy, 5f * sx, 116f * sy) }
        }
        val w = with(density) { stroke.toPx() }
        val effect = if (dotted) PathEffect.dashPathEffect(floatArrayOf(0.1f, w * 4.2f)) else null
        // Neon: three widening strokes under the line read as light, without blur or extra layers. They breathe with
        // the shared pulse, so the halo is plainly alive but never flashes.
        if (glow && !dotted) {
            val p = pulse.value; val k = glowStrength
            drawPath(path, glowColor.copy(alpha = (0.05f + 0.05f * p) * k), style = Stroke(width = w * 11f, cap = StrokeCap.Round))
            drawPath(path, glowColor.copy(alpha = (0.12f + 0.12f * p) * k), style = Stroke(width = w * 5.5f, cap = StrokeCap.Round))
            drawPath(path, glowColor.copy(alpha = (0.30f + 0.15f * p) * k), style = Stroke(width = w * 2.4f, cap = StrokeCap.Round))
        }
        drawPath(path, color, style = Stroke(width = if (dotted) w * 1.2f else w, cap = StrokeCap.Round, pathEffect = effect))
    }
}

/**
 * The hero interval around [content]. [opening] plays the start motion once when the composable enters;
 * breathing runs only while [state] is RUNNING and motion is allowed.
 */
@Composable
fun IntervalFrame(state: IntervalState, modifier: Modifier = Modifier, bracketWidth: Dp = 28.dp, bracketHeight: Dp = 120.dp,
                  stroke: Dp = 2.2.dp, gap: Dp = 18.dp, opening: Boolean = true, breathe: Dp = 4.dp, invite: Boolean = false,
                  content: @Composable RowScope.() -> Unit) {
    val c = NotAi.colors
    val reduced = NotAi.reducedMotion
    val target = when (state) {
        IntervalState.RUNNING -> c.amber
        IntervalState.PAUSED -> c.ink3
        IntervalState.IDLE, IntervalState.UNKNOWN -> c.ink2
    }
    val color by animateColorAsState(target, tween(if (reduced) 0 else 400), label = "bracket")
    val open = remember { Animatable(if (opening && !reduced) 1f else 0f) }
    LaunchedEffect(Unit) { if (open.value > 0f) open.animateTo(0f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow)) }
    val breathing = state == IntervalState.RUNNING && !reduced
    // Idle invitation: slower, inward, half as deep as running, so the two never read alike.
    val inviting = invite && state == IntervalState.IDLE && !reduced
    val phase = if (breathing || inviting) {
        val t = rememberInfiniteTransition(label = "breath")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (inviting) 2800 else 2100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathPhase").value
    } else 0f
    val density = LocalDensity.current
    val shiftPx = with(density) { breathe.toPx() } * phase * (if (inviting) -0.75f else 1f)
    val fade = if (inviting) 0.3f else 0.55f
    val openPx = with(density) { 54.dp.toPx() } * open.value
    // Neon: the accent lights the brackets fully while recording, faintly at rest, and not at all when paused.
    val glowStrength = when (state) { IntervalState.RUNNING -> 1f; IntervalState.IDLE, IntervalState.UNKNOWN -> 0.55f; IntervalState.PAUSED -> 0.25f }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
        Bracket(true, color, Modifier.graphicsLayer { translationX = openPx - shiftPx; alpha = (1f - fade * phase) * (1f - open.value) },
            width = bracketWidth, height = bracketHeight, stroke = stroke, glow = c.glow, glowColor = c.amber, glowStrength = glowStrength)
        content()
        Bracket(false, if (state == IntervalState.UNKNOWN) c.ink3 else color,
            Modifier.graphicsLayer { translationX = -openPx + shiftPx; alpha = (1f - fade * phase) * (1f - open.value) },
            width = bracketWidth, height = bracketHeight, stroke = stroke, dotted = state == IntervalState.UNKNOWN, glow = c.glow, glowColor = c.amber,
            glowStrength = glowStrength)
    }
}
