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
    // autoMirror: directional icons (back, forward, undo) flip in right-to-left languages (Arabic, Hebrew).
    private fun line(name: String, vararg d: String, filled: Boolean = false, autoMirror: Boolean = false): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f, autoMirror = autoMirror)
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
    val ChevronLeft by lazy { line("chevronLeft", "M15 5l-7 7 7 7", autoMirror = true) }
    val ChevronRight by lazy { line("chevronRight", "M9 5l7 7-7 7", autoMirror = true) }
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
    val Undo by lazy { line("undo", "M9 14L4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 0 11H11", autoMirror = true) }
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
}

/** Tinted icon with a default size. */
@Composable
fun KinIcon(icon: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Kin.colors.ink, size: Dp = 28.dp) {
    Icon(icon, contentDescription, modifier.size(size), tint = tint)
}
