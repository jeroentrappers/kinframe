package app.kinframe.core.design

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * kinframe's own line icon set (24 × 24 grid, 2 px round strokes). Hand-authored so we don't ship the large
 * material-icons-extended artifact on 1 GB frames.
 */
object KinIcons {
    private fun line(name: String, vararg d: String, filled: Boolean = false): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        d.forEach { path ->
            b.addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private const val HEART = "M12 20s-7.5-4.6-9-9.2C2 7.3 4.3 4.5 7.3 4.5c2 0 3.6 1.1 4.7 2.8 1.1-1.7 2.7-2.8 4.7-2.8 3 0 5.3 2.8 4.3 6.3C19.5 15.4 12 20 12 20z"
    private const val WIFI_ARCS = "M2.5 9a14 14 0 0 1 19 0"
    private const val WIFI_MID = "M5.5 12.5a9.5 9.5 0 0 1 13 0"
    private const val WIFI_IN = "M8.8 16a4.7 4.7 0 0 1 6.4 0"
    private const val DOT = "M12 19.5h0.01"

    val Close by lazy { line("close", "M6 6L18 18M18 6L6 18") }
    val Check by lazy { line("check", "M5 12.5l4.5 4.5L19 7.5") }
    val ChevronLeft by lazy { line("chevronLeft", "M15 5l-7 7 7 7") }
    val ChevronRight by lazy { line("chevronRight", "M9 5l7 7-7 7") }
    val Plus by lazy { line("plus", "M12 5v14M5 12h14") }
    val Minus by lazy { line("minus", "M5 12h14") }
    val Power by lazy { line("power", "M12 3v8", "M6.3 6.3a8 8 0 1 0 11.4 0") }
    val Wifi by lazy { line("wifi", WIFI_ARCS, WIFI_MID, WIFI_IN, DOT) }
    val WifiWeak by lazy { line("wifiWeak", WIFI_IN, DOT) }
    val WifiOff by lazy { line("wifiOff", WIFI_ARCS, WIFI_MID, WIFI_IN, DOT, "M3 3l18 18") }
    val Settings by lazy { line("settings", "M4 7h9", "M17 7h3", "M15 5v4", "M4 17h3", "M11 17h9", "M9 15v4") }
    val Moon by lazy { line("moon", "M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z") }
    val Sun by lazy {
        line(
            "sun", "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z",
            "M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4",
        )
    }
    val Restart by lazy { line("restart", "M20 12a8 8 0 1 1-2.6-5.9", "M20 4v5h-5") }
    val Trash by lazy { line("trash", "M4 7h16", "M10 11v6", "M14 11v6", "M6 7l1 13h10l1-13", "M9 7V4h6v3") }
    val EyeOff by lazy {
        line(
            "eyeOff", "M3 3l18 18", "M10.6 6.1A9.8 9.8 0 0 1 12 6c5 0 9 6 9 6a16 16 0 0 1-2.6 3.2",
            "M6.6 7.7C4.1 9.3 3 12 3 12s4 6 9 6a8.7 8.7 0 0 0 4.4-1.2", "M9.9 10a3 3 0 0 0 4.1 4.2",
        )
    }
    val Heart by lazy { line("heart", HEART) }
    val HeartFilled by lazy { line("heartFilled", HEART, filled = true) }
    val Undo by lazy { line("undo", "M9 14L4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 0 11H11") }
    val Globe by lazy { line("globe", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M3 12h18", "M12 3a14 14 0 0 1 0 18", "M12 3a14 14 0 0 0 0 18") }
    val Server by lazy { line("server", "M4 4h16v6H4z", "M4 14h16v6H4z", "M8 7h0.01", "M8 17h0.01") }
    val Info by lazy { line("info", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M12 11v6", "M12 7.5h0.01") }
    val Image by lazy { line("image", "M4 5h16v14H4z", "M4 16l5-5 4 4 3-3 4 4", "M15.5 8.5h0.01") }
    val Lock by lazy { line("lock", "M6 11h12v9H6z", "M8.5 11V8a3.5 3.5 0 0 1 7 0v3") }
    val Smile by lazy { line("smile", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M8.5 14a4.5 4.5 0 0 0 7 0", "M9 9.5h0.01", "M15 9.5h0.01") }
    val Backspace by lazy { line("backspace", "M9 5h11v14H9l-6-7z", "M12.5 9.5l5 5", "M17.5 9.5l-5 5") }
    val Bell by lazy { line("bell", "M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z", "M10 20.5a2.2 2.2 0 0 0 4 0") }
    val UserPlus by lazy { line("userPlus", "M10 11a3.5 3.5 0 1 0 0-7a3.5 3.5 0 1 0 0 7z", "M3.5 20a6.5 6.5 0 0 1 13 0", "M19 8v6", "M16 11h6") }
    val Storage by lazy { line("storage", "M4 6c0-1.7 3.6-3 8-3s8 1.3 8 3-3.6 3-8 3-8-1.3-8-3z", "M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6", "M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3") }
    val Clock by lazy { line("clock", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M12 7v5l3.5 2") }
    val Download by lazy { line("download", "M12 4v11", "M7 10.5l5 5 5-5", "M5 20h14") }
    val Login by lazy { line("login", "M14 4h5v16h-5", "M3 12h11", "M10 8l4 4-4 4") }

    // ---- family app
    /** A picture frame on a stand. */
    val Frame by lazy { line("frame", "M3 5h18v12H3z", "M6.5 8h11v6h-11z", "M9 21l3-4 3 4") }
    val Send by lazy { line("send", "M21 3L10 14", "M21 3l-7 18-4-7-7-4z") }
    val History by lazy { line("history", "M3.5 12a8.5 8.5 0 1 0 2.5-6", "M3 4v4.5h4.5", "M12 7.5V12l3 2") }
    val People by lazy {
        line(
            "people", "M9 4a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7z", "M2.5 20a6.5 6.5 0 0 1 13 0",
            "M16 4.3a3.5 3.5 0 0 1 0 6.4", "M18 14.2a6.5 6.5 0 0 1 3.5 5.8",
        )
    }
    val User by lazy { line("user", "M12 4a4 4 0 1 0 0 8a4 4 0 1 0 0-8z", "M4.5 20.5a7.5 7.5 0 0 1 15 0") }
    val Share by lazy { line("share", "M12 3v12", "M7.5 7.5L12 3l4.5 4.5", "M5 12v7h14v-7") }
    val Link by lazy { line("link", "M10 14a4.5 4.5 0 0 0 6.4 0l3-3a4.5 4.5 0 0 0-6.4-6.4l-1 1", "M14 10a4.5 4.5 0 0 0-6.4 0l-3 3a4.5 4.5 0 0 0 6.4 6.4l1-1") }
    val Copy by lazy { line("copy", "M9 9h11v11H9z", "M5 15H4V4h11v1") }
    val Play by lazy { line("play", "M7 4.5v15l12.5-7.5z") }
    val Video by lazy { line("video", "M3 6h12v12H3z", "M15 10l6-3.5v11L15 14") }
    val MapPin by lazy { line("mapPin", "M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21z", "M12 7a2.5 2.5 0 1 0 0 5a2.5 2.5 0 1 0 0-5z") }
    val Search by lazy { line("search", "M10.5 4a6.5 6.5 0 1 0 0 13a6.5 6.5 0 1 0 0-13z", "M15.5 15.5L20 20") }
    val SignOut by lazy { line("signOut", "M10 4H5v16h5", "M15 8l4 4-4 4", "M19 12H9") }
    val Edit by lazy { line("edit", "M4 20h4L19 9l-4-4L4 16z", "M13.5 6.5l4 4") }
    val Refresh by lazy { line("refresh", "M20 11a8 8 0 0 0-14.5-4.5L4 8", "M4 4v4h4", "M4 13a8 8 0 0 0 14.5 4.5L20 16", "M20 20v-4h-4") }
    val SkipBack by lazy { line("skipBack", "M18 5v14L8 12z", "M6 5v14") }
    val SkipForward by lazy { line("skipForward", "M6 5v14l10-7z", "M18 5v14") }
    val Eye by lazy { line("eye", "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z", "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z") }
    val More by lazy { line("more", "M5 12h0.01", "M12 12h0.01", "M19 12h0.01") }
    val Grid by lazy { line("grid", "M4 4h7v7H4z", "M13 4h7v7h-7z", "M4 13h7v7H4z", "M13 13h7v7h-7z") }
    val Crop by lazy { line("crop", "M6 2v16h16", "M2 6h16v16") }
    val Maximize by lazy { line("maximize", "M4 9V4h5", "M20 9V4h-5", "M4 15v5h5", "M20 15v5h-5") }
    val Target by lazy { line("target", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8z", "M12 11.5h0.01") }
    val Scissors by lazy {
        line("scissors", "M6 3a3 3 0 1 0 0 6a3 3 0 1 0 0-6z", "M6 15a3 3 0 1 0 0 6a3 3 0 1 0 0-6z", "M8.1 8.1L20 20", "M8.1 15.9L20 4")
    }
    val Message by lazy { line("message", "M4 5h16v11H9l-5 4z") }
    val Shield by lazy { line("shield", "M12 3l8 3v6c0 4.5-3.4 8-8 9-4.6-1-8-4.5-8-9V6z") }
    val ArrowRight by lazy { line("arrowRight", "M5 12h14", "M13 6l6 6-6 6") }
    val Clipboard by lazy { line("clipboard", "M8 4h8v3H8z", "M8 5.5H5V21h14V5.5h-3") }
}

/** Tinted icon with a default size. */
@Composable
fun KinIcon(icon: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Kin.colors.ink, size: Dp = 28.dp) {
    Icon(icon, contentDescription, modifier.size(size), tint = tint)
}
