package dev.notai.tracker.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/** Small line icons on a 24-unit grid, drawn to match the design. No icon font or extended icon set. */
object Icon {
    private fun stroke(name: String, vararg paths: String, width: Float = 1.8f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d ->
                addPath(addPathNodes(d), stroke = SolidColor(Color.Black), strokeLineWidth = width,
                    strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
            }
        }.build()
    private fun fill(name: String, vararg paths: String): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { d -> addPath(addPathNodes(d), fill = SolidColor(Color.Black)) }
        }.build()

    val Pause = fill("pause", "M6.5 5h3.5v14H6.5z", "M14 5h3.5v14H14z")
    val Play = fill("play", "M7 4.8v14.4c0 .8.9 1.3 1.6.8l11-7.2c.6-.4.6-1.2 0-1.6l-11-7.2C7.9 3.5 7 4 7 4.8z")
    val Stop = fill("stop", "M7.5 5h9a2.5 2.5 0 0 1 2.5 2.5v9a2.5 2.5 0 0 1-2.5 2.5h-9A2.5 2.5 0 0 1 5 16.5v-9A2.5 2.5 0 0 1 7.5 5z")
    val Repeat = stroke("repeat", "M7.5 5.2v13.6c0 .6.7 1 1.2.6l10-6.8c.5-.3.5-1 0-1.3l-10-6.8c-.5-.4-1.2 0-1.2.7z")
    val Plus = stroke("plus", "M12 5v14M5 12h14", width = 2f)
    val Minus = stroke("minus", "M5 12h14", width = 2f)
    val Close = stroke("close", "M6 6l12 12M18 6L6 18", width = 2f)
    val ChevronDown = stroke("down", "M6 9l6 6 6-6", width = 2f)
    val ChevronRight = stroke("right", "M9 6l6 6-6 6", width = 2f)
    val ChevronLeft = stroke("left", "M15 6l-6 6 6 6", width = 2f)
    val Search = stroke("search", "M11 4.5a6.5 6.5 0 1 1 0 13a6.5 6.5 0 1 1 0-13z", "M16 16l4.5 4.5", width = 1.9f)
    val Star = fill("star", "M12 3.2l2.6 5.5 6 .7-4.4 4.1 1.1 5.9L12 16.5l-5.3 2.9 1.1-5.9-4.4-4.1 6-.7z")
    val StarLine = stroke("starLine", "M12 3.2l2.6 5.5 6 .7-4.4 4.1 1.1 5.9L12 16.5l-5.3 2.9 1.1-5.9-4.4-4.1 6-.7z", width = 1.6f)
    val Sun = stroke("sun", "M12 7.8a4.2 4.2 0 1 1 0 8.4a4.2 4.2 0 1 1 0-8.4z", "M12 2.5v2.2M12 19.3v2.2M2.5 12h2.2M19.3 12h2.2M5.3 5.3l1.6 1.6M17.1 17.1l1.6 1.6M5.3 18.7l1.6-1.6M17.1 6.9l1.6-1.6", width = 1.7f)
    val Moon = stroke("moon", "M19.5 14.6A8 8 0 0 1 9.4 4.5a8 8 0 1 0 10.1 10.1z", width = 1.7f)
    val TabTimer = stroke("tabTimer", "M8 3.5C4.6 7.6 4.6 16.4 8 20.5M16 3.5c3.4 4.1 3.4 12.9 0 17")
    val TabOverview = stroke("tabOverview", "M5 20V12M10 20V6M15 20v-9M20 20V9")
    val TabData = stroke("tabData", "M5 6c0-1.4 3.1-2.6 7-2.6s7 1.2 7 2.6-3.1 2.6-7 2.6S5 7.4 5 6z",
        "M5 6v6c0 1.4 3.1 2.6 7 2.6s7-1.2 7-2.6V6M5 12v6c0 1.4 3.1 2.6 7 2.6s7-1.2 7-2.6v-6")
    val Check = stroke("check", "M5 12.5l4.5 4.5L19 7.5", width = 2.2f)
    val Limit = stroke("limit", "M4 12h16M16 8l4 4-4 4", width = 2f)
    val Edit = stroke("edit", "M4 20h4L19 9l-4-4L4 16z")
}
