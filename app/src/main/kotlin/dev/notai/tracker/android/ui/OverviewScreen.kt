package dev.notai.tracker.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.notai.tracker.TrackerView

/** Totals by area. Every number is a core report value; the screen only lays them out. */
@Composable
fun OverviewScreen(vm: TrackerViewModel, view: TrackerView?, modifier: Modifier = Modifier) {
    val c = NotAi.colors
    var period by rememberSaveableEnum(Period.WEEK)
    val data by produceState<OverviewData?>(null, period, view) { value = vm.overview(period) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Обзор", Modifier.weight(1f), style = NotAi.type.title, color = c.ink)
        }
        Segmented(Period.entries.map { it to it.title }, period, { period = it }, Modifier.fillMaxWidth(), height = 36.dp)
        Spacer(Modifier.height(12.dp))
        val d = data ?: run { Spacer(Modifier.height(400.dp)); return@Column }
        Column(Modifier.padding(top = 4.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val title = if (d.period == Period.DAY) "Сегодня" else Format.period(d.first, d.last)
            Text(title + if (d.incomplete) ", день ещё идёт" else "", style = NotAi.type.bodySmall, color = c.ink2)
            Text(Format.duration(d.totalMs), style = NotAi.type.big, color = c.ink)
            d.differenceMs?.let { diff ->
                val prev = if (d.period == Period.DAY) "вчера" else Format.period(d.previousFirst, d.previousLast)
                Text(when {
                    d.previousMs == 0L -> if (d.period == Period.DAY) "Вчера записей нет" else "За $prev записей нет"
                    diff > 0 -> "на ${Format.duration(diff)} больше, чем ${if (d.period == Period.DAY) prev else "за $prev"}"
                    diff < 0 -> "на ${Format.duration(-diff)} меньше, чем ${if (d.period == Period.DAY) prev else "за $prev"}"
                    else -> "столько же, сколько ${if (d.period == Period.DAY) prev else "за $prev"}"
                }, style = NotAi.type.bodySmall, color = c.ink2)
            }
        }
        if (d.period == Period.DAY) {
            DayRibbon(d.dayStart, d.dayEnd, d.ribbon.map { RibbonPiece(it.start, it.end, it.slot?.let { s -> c.areas[s] } ?: c.none) }, height = 16.dp)
        } else Columns(d.columns, d.period)
        if (d.unresolved > 0) Note("Есть отрезки с неизвестным временем: ${d.unresolved}. В итог они не входят, пока ты их не уточнишь.", Modifier.padding(top = 14.dp))

        Text("По областям", Modifier.padding(top = 20.dp, bottom = 4.dp), style = NotAi.type.section, color = c.ink)
        if (d.areas.isEmpty()) Text("За этот период записей нет.", style = NotAi.type.bodySmall, color = c.ink2)
        val max = d.areas.maxOfOrNull { it.ms }?.coerceAtLeast(1) ?: 1
        d.areas.forEach { a ->
            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActivityGlyph(a.glyph, a.slot, size = 18.dp)
                    Text(a.name, Modifier.weight(1f), style = NotAi.type.bodySmall, color = c.ink)
                    Text(Format.duration(a.ms), style = NotAi.type.bodySmall, color = c.ink)
                }
                Box(Modifier.padding(start = 19.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.sunk)) {
                    Box(Modifier.fillMaxWidth(a.ms.toFloat() / max).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(a.slot?.let { c.areas[it] } ?: c.none))
                }
            }
        }
        if (d.goals.isNotEmpty()) {
            Text("Цели", Modifier.padding(top = 18.dp, bottom = 4.dp), style = NotAi.type.section, color = c.ink)
            d.goals.forEach { g ->
                ListRow(g.name, subtitle = "${g.value}, ${g.status}", leading = {
                    M3Icon(if (g.ok) Icon.Check else Icon.Limit, null, Modifier.size(18.dp), tint = if (g.ok) c.areas[Areas.WORK] else c.ink2)
                })
            }
        }
    }
}

/** Stacked columns per local day, 2 dp gaps between parts, recessive 2-hour grid. */
@Composable
private fun Columns(columns: List<DayColumn>, period: Period) {
    val c = NotAi.colors
    val maxMs = maxOf(columns.maxOfOrNull { it.totalMs } ?: 0L, 2 * 3_600_000L)
    val gridHours = generateSequence(2) { it + 2 }.takeWhile { it * 3_600_000L <= maxMs }.toList()
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "Время по дням: " + columns.joinToString { "${Format.shortDay(it.date)} ${Format.duration(it.totalMs)}" } }) {
        Box(Modifier.fillMaxWidth().height(152.dp)) {
            Canvas(Modifier.fillMaxSize().padding(end = 28.dp)) {
                gridHours.forEach { h ->
                    val y = size.height - size.height * (h * 3_600_000f / maxMs)
                    drawLine(c.rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                val n = columns.size
                val slotW = size.width / n
                val barW = minOf(26.dp.toPx(), slotW * 0.62f)
                val gap = 2.dp.toPx()
                columns.forEachIndexed { i, col ->
                    var y = size.height
                    val x = slotW * i + (slotW - barW) / 2
                    col.parts.forEach { (slot, ms) ->
                        val h = size.height * (ms.toFloat() / maxMs)
                        if (h >= 1f) {
                            drawRoundRect(slot?.let { c.areas[it] } ?: c.none, Offset(x, y - h + gap / 2), Size(barW, maxOf(h - gap, 1f)), CornerRadius(3.dp.toPx()))
                        }
                        y -= h
                    }
                }
            }
            gridHours.forEach { h ->
                Text("$h ч", Modifier.align(Alignment.BottomEnd).offset(y = -(152 * h * 3_600_000f / maxMs).dp + 6.dp), style = NotAi.type.micro, color = c.ink2)
            }
        }
        if (period == Period.WEEK) Row(Modifier.fillMaxWidth().padding(end = 28.dp, top = 6.dp)) {
            columns.forEach { col ->
                val today = col.incomplete
                Text(if (today) "сегодня" else Format.shortDay(col.date), Modifier.weight(1f), style = NotAi.type.micro.copy(fontWeight = if (today) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (today) c.ink else c.ink2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
            }
        } else Row(Modifier.fillMaxWidth().padding(end = 28.dp, top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Format.dayMonth(columns.first().date), style = NotAi.type.micro, color = c.ink2)
            Text("сегодня", style = NotAi.type.micro.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
        }
    }
}

@Composable
private fun rememberSaveableEnum(initial: Period): MutableState<Period> {
    val name = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initial.name) }
    return remember {
        object : MutableState<Period> {
            override var value: Period get() = Period.valueOf(name.value); set(v) { name.value = v.name }
            override fun component1() = value
            override fun component2(): (Period) -> Unit = { value = it }
        }
    }
}
