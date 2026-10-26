package dev.corn.flashing.ui

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Lucide-style stroke icons, same paths as the mockup generator (gen.py `P`). */
object Lucide {
    private fun icon(d: String): ImageVector = ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(d).toNodes(),
        fill = null, stroke = SolidColor(Color.White), strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
    ).build()

    val Bolt = icon("M13 2 4 14h7l-1 8 9-12h-7z")
    val File = icon("M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6")
    val Sliders = icon("M4 21v-7M4 10V3M12 21v-9M12 8V3M20 21v-5M20 12V3M1 14h6M9 8h6M17 16h6")
    val Usb = icon("M9.0 7.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0zM3.0 20.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0zM4.7 19.3 19 5M21 3l-3 1 2 2zM9.26 7.68 5 12l2 5M10 14l5 2 3.5-3.5M17.0 12.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0z")
    val List = icon("M8 6h13M8 12h13M8 18h13M3 6h.01M3 12h.01M3 18h.01")
    val Gear = icon("M9.0 12.0a3.0 3.0 0 1 0 6.0 0a3.0 3.0 0 1 0 -6.0 0zM19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z")
    val Check = icon("M20 6 9 17l-5-5")
    val X = icon("M18 6 6 18M6 6l12 12")
    val Search = icon("M4.0 11.0a7.0 7.0 0 1 0 14.0 0a7.0 7.0 0 1 0 -14.0 0zm21 21-4.3-4.3")
    val Plus = icon("M12 5v14M5 12h14")
    val Chip = icon("M7.0 5.0h10.0a2.0 2.0 0 0 1 2.0 2.0v10.0a2.0 2.0 0 0 1 -2.0 2.0h-10.0a2.0 2.0 0 0 1 -2.0 -2.0v-10.0a2.0 2.0 0 0 1 2.0 -2.0zM9 9h6v6H9zM9 1v4M15 1v4M9 19v4M15 19v4M1 9h4M1 15h4M19 9h4M19 15h4")
    val Refresh = icon("M21 12a9 9 0 1 1-3-6.7L21 8M21 3v5h-5")
    val Alert = icon("M10.3 3.9 1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0zM12 9v4M12 17h.01")
    val Chev = icon("m9 18 6-6-6-6")
    val Back = icon("m15 18-6-6 6-6")
    val Lock = icon("M5.0 11.0h14.0a2.0 2.0 0 0 1 2.0 2.0v7.0a2.0 2.0 0 0 1 -2.0 2.0h-14.0a2.0 2.0 0 0 1 -2.0 -2.0v-7.0a2.0 2.0 0 0 1 2.0 -2.0zM7 11V7a5 5 0 0 1 10 0v4")
    val Phone = icon("M8.0 2.0h8.0a2.0 2.0 0 0 1 2.0 2.0v16.0a2.0 2.0 0 0 1 -2.0 2.0h-8.0a2.0 2.0 0 0 1 -2.0 -2.0v-16.0a2.0 2.0 0 0 1 2.0 -2.0zM11 18h2")
    val Download = icon("M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3")
    val Play = icon("m6 3 14 9-14 9z")
    val Shield = icon("M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z")
    val Repeat = icon("m17 2 4 4-4 4M3 11v-1a4 4 0 0 1 4-4h14M7 22l-4-4 4-4M21 13v1a4 4 0 0 1-4 4H3")
}
