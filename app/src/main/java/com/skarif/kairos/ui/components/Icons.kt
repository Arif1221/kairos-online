package com.skarif.kairos.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The design's line icons, built from the same SVG paths, so no icon library is needed.
 * Icon() tints them, so the black here is only a placeholder.
 */
object Ic {
    private fun stroke(name: String, d: String, width: Float = 2f) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(
            pathData = addPathNodes(d),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = width,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()

    private fun solid(name: String, d: String) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).addPath(pathData = addPathNodes(d), fill = SolidColor(Color.Black)).build()

    val Back by lazy { stroke("back", "M15 18l-6-6 6-6") }
    val Down by lazy { stroke("down", "M6 9l6 6 6-6") }
    val Chevron by lazy { stroke("chev", "M9 18l6-6-6-6") }
    val Play by lazy { solid("play", "M8 5v14l11-7z") }
    val Pause by lazy { solid("pause", "M7 5h3.5v14H7zM13.5 5H17v14h-3.5z") }
    val Next by lazy { solid("next", "M6 5l9 7-9 7zM16.5 5h2v14h-2z") }
    val Prev by lazy { solid("prev", "M18 5l-9 7 9 7zM5.5 5h2v14h-2z") }
    val Heart by lazy { stroke("heart", "M12 20.5s-7.5-4.6-9.6-9.2A5.3 5.3 0 0 1 12 6.4a5.3 5.3 0 0 1 9.6 4.9c-2.1 4.6-9.6 9.2-9.6 9.2z") }
    val HeartFilled by lazy { solid("heartF", "M12 20.5s-7.5-4.6-9.6-9.2A5.3 5.3 0 0 1 12 6.4a5.3 5.3 0 0 1 9.6 4.9c-2.1 4.6-9.6 9.2-9.6 9.2z") }
    val Lyrics by lazy { stroke("lyrics", "M4 5h16v11H9l-5 4zM8 9h8M8 12h5") }
    val Home by lazy { stroke("home", "M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z") }
    val Settings by lazy { stroke("settings", "M12 2.8 20 7.4v9.2l-8 4.6-8-4.6V7.4zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z") }
    val Refresh by lazy { stroke("refresh", "M20 11a8 8 0 1 0-2.3 5.7M20 4v7h-7") }
    val Folder by lazy { stroke("folder", "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z") }
    val Plus by lazy { stroke("plus", "M12 5v14M5 12h14") }
    val Close by lazy { stroke("close", "M6 6l12 12M18 6 6 18") }
    val Wave by lazy { stroke("wave", "M3 12h2M7 8v8M11 5v14M15 9v6M19 11v2") }
    val Clock by lazy { stroke("clock", "M12 7v5l3 2M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z") }
    val Queue by lazy { stroke("queue", "M3 6h13M3 12h13M3 18h8M17 15l5 3-5 3z") }
    val Check by lazy { stroke("check", "M20 6 9 17l-5-5") }
    val Music by lazy { stroke("music", "M9 18V5l12-2v13M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0zM21 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0z") }
    val Palette by lazy { stroke("palette", "M12 3a9 9 0 1 0 0 18c1 0 1.5-.8 1.5-1.6 0-1-.8-1.4-.8-2.4 0-1 .8-1.5 1.8-1.5H17a4 4 0 0 0 4-4c0-4.6-4-8.5-9-8.5z") }
    val Sliders by lazy { stroke("sliders", "M4 7h9M17 7h3M4 17h3M11 17h9M15 5v4M9 15v4") }
    val Sort by lazy { stroke("sort", "M4 7h16M7 12h10M10 17h4") }
    val Shuffle by lazy { stroke("shuffle", "M16 3h5v5M4 20 21 3M21 16v5h-5M15 15l6 6M4 4l5 5") }
    val Repeat by lazy { stroke("repeat", "M17 2l4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 0 1-3 3H3") }
    val RepeatOne by lazy { stroke("repeat1", "M17 2l4 4-4 4M3 11V9a3 3 0 0 1 3-3h15M7 22l-4-4 4-4M21 13v2a3 3 0 0 1-3 3H3M11 10l1.5-1v6") }
    val Search by lazy { stroke("search", "M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-4-4") }
    val Menu by lazy { stroke("menu", "M6 10h12M6 14h12") }
    val Stats by lazy { stroke("stats", "M5 20v-8M12 20V4M19 20v-5") }
    val Library by lazy { stroke("library", "M5 4v16M10 4v16M14.5 4.5l5 15") }
    val Up by lazy { stroke("up", "M12 19V5M5 12l7-7 7 7") }
    val DownArrow by lazy { stroke("downArrow", "M12 5v14M19 12l-7 7-7-7") }
    val Minus by lazy { stroke("minus", "M5 12h14") }
    val Trash by lazy { stroke("trash", "M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13") }
    val Share by lazy { stroke("share", "M4 12v7a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-7M16 6l-4-4-4 4M12 2v13") }
    val Radio by lazy { stroke("radio", "M4.9 19.1a10 10 0 0 1 0-14.2M19.1 4.9a10 10 0 0 1 0 14.2M7.8 16.2a6 6 0 0 1 0-8.4M16.2 7.8a6 6 0 0 1 0 8.4M12 13a1 1 0 1 0 0-2 1 1 0 0 0 0 2z") }
    val Discover by lazy { stroke("discover", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM15.5 8.5l-2 5-5 2 2-5z") }
    val Disc by lazy { stroke("disc", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 14a2 2 0 1 0 0-4 2 2 0 0 0 0 4z") }
    val Person by lazy { stroke("person", "M20 21a8 8 0 0 0-16 0M12 13a4 4 0 1 0 0-8 4 4 0 0 0 0 8z") }
    val Info by lazy { stroke("info", "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 16v-5M12 8h.01") }
    val EyeOff by lazy { stroke("eyeOff", "M3 3l18 18M10.6 5.1A9.7 9.7 0 0 1 12 5c6 0 9.5 7 9.5 7a17 17 0 0 1-2.6 3.4M6.6 6.6A17 17 0 0 0 2.5 12S6 19 12 19a9.4 9.4 0 0 0 5.4-1.6M9.9 9.9a3 3 0 0 0 4.2 4.2") }
    val Edit by lazy { stroke("edit", "M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4") }
    val Speed by lazy { stroke("speed", "M12 21a9 9 0 1 1 9-9M12 12l4-4") }
    val More by lazy { solid("more", "M5 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM12 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4zM19 10a2 2 0 1 0 0 4 2 2 0 0 0 0-4z") }
    val AddQueue by lazy { stroke("addQueue", "M3 6h13M3 12h9M3 18h9M18 13v8M14 17h8") }
    val PlaylistAdd by lazy { stroke("playlistAdd", "M3 6h12M3 12h12M3 18h7M17 14v7M13.5 17.5h7") }
}
