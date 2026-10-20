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
    val Bell = stroke("bell", "M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2h-15z", "M10 20.5a2 2 0 0 0 4 0", width = 1.7f)
    val Floating = stroke("floating", "M7 3.5h10A1.5 1.5 0 0 1 18.5 5v14a1.5 1.5 0 0 1-1.5 1.5H7A1.5 1.5 0 0 1 5.5 19V5A1.5 1.5 0 0 1 7 3.5z",
        "M14.5 7.2a2 2 0 1 1 0 4a2 2 0 1 1 0-4z", width = 1.7f)
    val Widget = stroke("widget", "M5.5 4h4A1.5 1.5 0 0 1 11 5.5v4A1.5 1.5 0 0 1 9.5 11h-4A1.5 1.5 0 0 1 4 9.5v-4A1.5 1.5 0 0 1 5.5 4z",
        "M14.5 4h4A1.5 1.5 0 0 1 20 5.5v4a1.5 1.5 0 0 1-1.5 1.5h-4A1.5 1.5 0 0 1 13 9.5v-4A1.5 1.5 0 0 1 14.5 4z",
        "M5.5 13h13a1.5 1.5 0 0 1 1.5 1.5v4a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 18.5v-4A1.5 1.5 0 0 1 5.5 13z", width = 1.7f)
    val TabSettings = stroke("tabSettings", "M4 7.5h9", "M18 7.5h2", "M15.5 5.3a2.2 2.2 0 1 1 0 4.4a2.2 2.2 0 1 1 0-4.4z",
        "M4 16.5h2", "M11 16.5h9", "M8.5 14.3a2.2 2.2 0 1 1 0 4.4a2.2 2.2 0 1 1 0-4.4z")
    val Globe = stroke("globe", "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 1 1 0-17z",
        "M12 3.5c-2.4 2.5-3.5 5.3-3.5 8.5s1.1 6 3.5 8.5c2.4-2.5 3.5-5.3 3.5-8.5s-1.1-6-3.5-8.5z", "M3.5 12h17", width = 1.6f)
    val Download = stroke("download", "M12 4v10", "M8 10.5l4 4 4-4", "M5 15.5V18a1.5 1.5 0 0 0 1.5 1.5h11A1.5 1.5 0 0 0 19 18v-2.5", width = 1.7f)
    val Upload = stroke("upload", "M12 15V5", "M8 8.5l4-4 4 4", "M5 15.5V18a1.5 1.5 0 0 0 1.5 1.5h11A1.5 1.5 0 0 0 19 18v-2.5", width = 1.7f)
    val Table = stroke("table", "M5.5 5h13A1.5 1.5 0 0 1 20 6.5v11a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 17.5v-11A1.5 1.5 0 0 1 5.5 5z",
        "M4 10h16", "M4 14.5h16", "M10 5v14", width = 1.6f)
    val Layers = stroke("layers", "M12 4l8.5 4.5L12 13 3.5 8.5z", "M3.5 12.5L12 17l8.5-4.5", "M3.5 16.5L12 21l8.5-4.5", width = 1.6f)
    val Info = stroke("info", "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 1 1 0-17z", "M12 11v5.5", "M12 7.8v.1", width = 1.7f)
    val Share = stroke("share", "M17.5 3.8a2.3 2.3 0 1 1 0 4.6a2.3 2.3 0 1 1 0-4.6z", "M6.5 9.7a2.3 2.3 0 1 1 0 4.6a2.3 2.3 0 1 1 0-4.6z",
        "M17.5 15.6a2.3 2.3 0 1 1 0 4.6a2.3 2.3 0 1 1 0-4.6z", "M8.6 10.9l6.8-3.7M8.6 13.1l6.8 3.7", width = 1.6f)
    val Calendar = stroke("calendar", "M5.5 5.5h13A1.5 1.5 0 0 1 20 7v11.5a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 18.5V7a1.5 1.5 0 0 1 1.5-1.5z",
        "M4 10h16", "M8.5 3.5v4M15.5 3.5v4", width = 1.6f)
    val Grip = stroke("grip", "M9 6.5h.01M15 6.5h.01M9 12h.01M15 12h.01M9 17.5h.01M15 17.5h.01", width = 2.6f)
    val Trash = stroke("trash", "M4.5 6.5h15", "M9.5 6.5V5a1.5 1.5 0 0 1 1.5-1.5h2A1.5 1.5 0 0 1 14.5 5v1.5",
        "M6.5 6.5l.9 12.1a1.5 1.5 0 0 0 1.5 1.4h6.2a1.5 1.5 0 0 0 1.5-1.4l.9-12.1", "M10 10.5v6M14 10.5v6", width = 1.7f)
    val Folder = stroke("folder", "M4 7a1.5 1.5 0 0 1 1.5-1.5h4l2 2h7A1.5 1.5 0 0 1 20 9v8.5a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 17.5z", width = 1.6f)
    val Shield = stroke("shield", "M12 3.5l7 2.8v5.2c0 4.3-2.9 7.6-7 9-4.1-1.4-7-4.7-7-9V6.3z", "M8.8 12.2l2.2 2.2 4.2-4.4", width = 1.6f)
    val Palette = stroke("palette", "M12 3.8a8.2 8.2 0 0 0 0 16.4c1.2 0 1.8-.8 1.8-1.7 0-1.2-1-1.4-1-2.5 0-.9.7-1.6 1.6-1.6h2.1a3.3 3.3 0 0 0 3.3-3.3c0-4-3.5-7.3-7.8-7.3z",
        "M7.8 11.2h.01M10 7.6h.01M14.2 7.6h.01", width = 1.6f)
    val Clock = stroke("clock", "M12 3.5a8.5 8.5 0 1 1 0 17a8.5 8.5 0 1 1 0-17z", "M12 7.5V12l3 2", width = 1.7f)
    val More = stroke("more", "M6 12h.01M12 12h.01M18 12h.01", width = 2.6f)
}
