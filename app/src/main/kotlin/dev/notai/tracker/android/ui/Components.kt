package dev.notai.tracker.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ButtonKind { PRIMARY, TONAL, AMBER, OUTLINE, TEXT }

/** Press feedback is a short 3 % squeeze; nothing else animates on touch. */
@Composable
fun Modifier.pressable(onClick: () -> Unit, enabled: Boolean = true, role: Role = Role.Button, label: String? = null): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val reduced = NotAi.reducedMotion
    return this.scale(if (pressed && !reduced) 0.97f else 1f)
        .clickable(source, indication = null, enabled = enabled, role = role, onClickLabel = label, onClick = onClick)
}

@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: ButtonKind = ButtonKind.PRIMARY,
               icon: ImageVector? = null, height: Dp = 54.dp, enabled: Boolean = true) {
    val c = NotAi.colors
    val (bg, fg) = when (kind) {
        ButtonKind.PRIMARY -> c.ink to c.paper
        ButtonKind.TONAL -> c.tonal to c.ink
        ButtonKind.AMBER -> c.amber to c.onAmber
        ButtonKind.OUTLINE -> Color.Transparent to c.ink
        ButtonKind.TEXT -> Color.Transparent to c.amberInk
    }
    Row(
        modifier.heightIn(min = height).clip(RoundedCornerShape(50))
            .background(if (enabled) bg else bg.copy(alpha = 0.5f))
            .then(if (kind == ButtonKind.OUTLINE) Modifier.border(1.dp, c.rule, RoundedCornerShape(50)) else Modifier)
            .pressable(onClick, enabled).padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { M3Icon(icon, null, Modifier.size(16.dp), tint = fg); Spacer(Modifier.width(8.dp)) }
        // Large system fonts wrap a phrase to two lines (the button grows); paired buttons stack via ButtonPair.
        Text(text, Modifier.padding(vertical = 6.dp), style = NotAi.type.button, color = if (enabled) fg else fg.copy(alpha = 0.6f), textAlign = TextAlign.Center,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Two buttons side by side while both labels fit; stacked full-width on narrow screens or large fonts. */
@Composable
fun ButtonPair(modifier: Modifier = Modifier, spacing: Dp = 10.dp, content: @Composable (Modifier) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
        if ((maxWidth - spacing) / 2 >= 140.dp * fontScale) Row(horizontalArrangement = Arrangement.spacedBy(spacing)) { content(Modifier.weight(1f)) }
        else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content(Modifier.fillMaxWidth()) }
    }
}

@Composable
fun IconAction(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = NotAi.colors.ink2, size: Dp = 20.dp) {
    Box(modifier.size(44.dp).clip(CircleShape).pressable(onClick, label = description).semantics { contentDescription = description },
        contentAlignment = Alignment.Center) {
        M3Icon(icon, null, Modifier.size(size), tint = tint)
    }
}

/** Activity colour mark. A dashed ring means "no activity chosen yet". */
@Composable
fun ActivityDot(slot: Int?, modifier: Modifier = Modifier, size: Dp = 10.dp, unclassified: Boolean = false, square: Boolean = false) {
    val c = NotAi.colors
    val color = slot?.let { c.areas[it] } ?: c.none
    if (unclassified) Canvas(modifier.size(size)) {
        drawCircle(c.amberInk, radius = this.size.minDimension / 2 - 1.dp.toPx(),
            style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 1.6.dp.toPx()))))
    } else Box(modifier.size(size).clip(if (square) RoundedCornerShape(3.dp) else CircleShape).background(color))
}

/** Activity icon in its area colour. "No activity yet" keeps the dashed ring in amber ink: a choice still open. */
@Composable
fun ActivityGlyph(glyph: Int, slot: Int?, modifier: Modifier = Modifier, size: Dp = 20.dp, unclassified: Boolean = false) {
    val c = NotAi.colors
    val tint = when { unclassified -> c.amberInk; slot != null -> c.areas[slot]; else -> c.ink2 }
    M3Icon(painterResource(if (unclassified) ActivityGlyphs.unclassified else glyph), null, modifier.size(size), tint = tint)
}

@Composable
fun ActivityGlyph(label: Label, modifier: Modifier = Modifier, size: Dp = 20.dp) =
    ActivityGlyph(label.glyph, label.slot, modifier, size, label.unclassified)

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, style = NotAi.type.caption, color = NotAi.colors.ink2)

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: String? = null, trailingNote: String? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(text, Modifier.weight(1f), style = NotAi.type.section, color = NotAi.colors.ink)
        if (trailing != null) Text(trailing, style = NotAi.type.section.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = NotAi.colors.ink)
        if (trailingNote != null) Text(" $trailingNote", style = NotAi.type.section.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), color = NotAi.colors.ink2)
    }
}

data class RibbonPiece(val start: Long, val end: Long, val color: Color, val hatched: Boolean = false, val live: Boolean = false)

/** A day as a thin 24-hour strip. Positions are drawn from the report rows' own start/end. */
@Composable
fun DayRibbon(dayStart: Long, dayEnd: Long, pieces: List<RibbonPiece>, modifier: Modifier = Modifier, now: Long? = null, labels: Boolean = true,
              height: Dp = 8.dp) {
    val c = NotAi.colors
    Column(modifier.fillMaxWidth().semantics { contentDescription = "Лента дня" }) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val span = (dayEnd - dayStart).coerceAtLeast(1).toFloat()
            drawRoundRect(c.sunk, cornerRadius = CornerRadius(size.height / 2))
            pieces.forEach { p ->
                val a = ((p.start - dayStart) / span).coerceIn(0f, 1f) * size.width
                val b = ((p.end - dayStart) / span).coerceIn(0f, 1f) * size.width
                val w = maxOf(b - a, 2.dp.toPx())
                if (p.hatched) clipRect(a, 0f, a + w, size.height) {
                    val step = 4.dp.toPx(); var x = a - size.height
                    while (x < a + w) {
                        drawLine(c.ink3, Offset(x, size.height), Offset(x + size.height, 0f), strokeWidth = 1.5.dp.toPx())
                        x += step
                    }
                } else drawRoundRect(p.color, Offset(a, 0f), Size(w, size.height), CornerRadius(2.dp.toPx()))
            }
            now?.let { n ->
                if (n in dayStart..dayEnd) {
                    val x = ((n - dayStart) / span) * size.width
                    drawLine(c.ink2, Offset(x, -2.dp.toPx()), Offset(x, size.height + 2.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                }
            }
        }
        if (labels) Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("0", "6", "12", "18", "24").forEach { Text(it, style = NotAi.type.micro, color = c.ink2) }
        }
    }
}

@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, height: Dp = 38.dp) {
    val c = NotAi.colors
    Row(modifier.clip(RoundedCornerShape(14.dp)).background(c.sunk).padding(3.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier.weight(1f).height(height).clip(RoundedCornerShape(11.dp)).background(if (on) c.raised else Color.Transparent)
                    .pressable({ onSelect(value) }, role = Role.RadioButton, label = label).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = NotAi.type.bodySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = if (on) c.ink else c.ink2, maxLines = 1) }
        }
    }
}

@Composable
fun NotAiSwitch(checked: Boolean, onChange: (Boolean) -> Unit, description: String) {
    val c = NotAi.colors
    Switch(checked, onChange, Modifier.semantics { contentDescription = description }, colors = SwitchDefaults.colors(
        checkedThumbColor = Color.White, checkedTrackColor = c.amber, checkedBorderColor = c.amber,
        uncheckedThumbColor = c.ink3, uncheckedTrackColor = c.sunk, uncheckedBorderColor = c.rule,
    ))
}

/** A plain list row: hairline below, 44 dp+ target, title with optional quiet detail. */
@Composable
fun ListRow(title: String, modifier: Modifier = Modifier, detail: String? = null, subtitle: String? = null, onClick: (() -> Unit)? = null,
            leading: (@Composable () -> Unit)? = null, trailing: (@Composable RowScope.() -> Unit)? = null, divider: Boolean = true, minHeight: Dp = 52.dp) {
    val c = NotAi.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = minHeight).then(if (onClick != null) Modifier.pressable(onClick) else Modifier),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading?.invoke()
            Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row {
                    Text(title, style = NotAi.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (detail != null) Text(" $detail", style = NotAi.type.body, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                if (subtitle != null) Text(subtitle, style = NotAi.type.caption, color = c.ink2, maxLines = 2)
            }
            trailing?.invoke(this)
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(c.rule))
    }
}

@Composable
fun Chip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, slot: Int? = null, dashed: Boolean = false, icon: ImageVector? = null,
         glyph: Int? = null) {
    val c = NotAi.colors
    Row(
        modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(50))
            .then(if (dashed) Modifier.dashedBorder(c.ink3) else Modifier.background(c.raised).border(BorderStroke(1.dp, c.rule), RoundedCornerShape(50)))
            .pressable(onClick).padding(start = if (icon != null || slot != null || glyph != null) 11.dp else 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (icon != null) M3Icon(icon, null, Modifier.size(14.dp), tint = c.ink2)
        else if (glyph != null) ActivityGlyph(glyph, slot, size = 17.dp)
        else if (slot != null || !dashed) ActivityDot(slot, size = 8.dp)
        Text(text, style = NotAi.type.bodySmall, color = if (dashed) c.ink2 else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

fun Modifier.dashedBorder(color: Color, radius: Dp = 20.dp): Modifier = this.drawBehind {
    drawRoundRect(color, cornerRadius = CornerRadius(radius.toPx()), style = Stroke(1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
}

@Composable
fun Hairline(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().height(1.dp).background(NotAi.colors.rule))

@Composable
fun Note(text: String, modifier: Modifier = Modifier, wash: Boolean = false) {
    val c = NotAi.colors
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (wash) c.amberWash else c.sunk).padding(PaddingValues(14.dp, 12.dp))) {
        Text(text, style = NotAi.type.bodySmall, color = c.ink)
    }
}
