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
    val RotateCw by lazy { line("rotateCw", "M20 3v5h-5", "M20 8A8.5 8.5 0 1 0 20.5 14") }
    /** Fill the screen: corners pointing out. */
    val Expand by lazy { line("expand", "M4 9V4h5", "M15 4h5v5", "M20 15v5h-5", "M9 20H4v-5") }
    /** Show the whole photo: corners pointing in. */
    val Shrink by lazy { line("shrink", "M9 4v5H4", "M15 4v5h5", "M20 15h-5v5", "M4 15h5v5") }
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

    // ------------------------------------------------------------------ home, energy and travel (plugin views)

    val Bolt by lazy { line("bolt", "M13 2.5L4.5 13.5H11L10 21.5l8.5-11H12z") }
    /** The electricity grid: a pylon (not the layout [Grid]). */
    val Pylon by lazy { line("pylon", "M8.5 21L12 3l3.5 18", "M5 7.5h14", "M6.5 12h11", "M10.3 15l4.2 5", "M13.7 15l-4.2 5") }
    val SolarPanel by lazy { line("solarPanel", "M5 4h14l2 10H3z", "M4 9h16", "M10 4l-1 10", "M14 4l1 10", "M12 14v6", "M8 20h8") }
    val Home by lazy { line("home", "M3.5 11L12 4l8.5 7", "M5.5 9.5V20h13V9.5", "M10 20v-5h4v5") }
    val Battery by lazy { line("battery", "M3 8h15v8H3z", "M21 10.5v3") }
    val BatteryCharging by lazy { line("batteryCharging", "M3 8h15v8H3z", "M21 10.5v3", "M11.5 9.5l-2.5 3h3.5l-2.5 3") }
    val Plug by lazy { line("plug", "M9 3v5", "M15 3v5", "M6 8h12v3a6 6 0 0 1-12 0z", "M12 17v4") }
    val Car by lazy { line("car", "M4 16.5V12l2-5h12l2 5v4.5z", "M4 12h16", "M7 16.5V19", "M17 16.5V19", "M7.5 14.2h0.01", "M16.5 14.2h0.01") }
    val Flame by lazy {
        line("flame", "M12 21c-3.9 0-6.5-2.6-6.5-6.2 0-3.6 3-5.6 4-9.3 2.7 1.7 3.5 4.3 3.3 6.3 1-.6 1.7-1.6 2-2.8 1.8 1.6 3.7 3.8 3.7 6.1 0 3.4-2.6 5.9-6.5 5.9z")
    }
    /** Gas supply: a flame in the meter box. */
    val Gas by lazy {
        line("gas", "M5 4h14v16H5z", "M12 17c-1.8 0-3-1.2-3-2.8 0-1.6 1.4-2.6 2-4.2 1.2.8 1.6 2 1.5 2.9.5-.3.8-.7 1-1.3.8.7 1.5 1.7 1.5 2.7 0 1.5-1.2 2.7-3 2.7z")
    }
    val Meter by lazy { line("meter", "M3.5 17a8.5 8.5 0 1 1 17 0z", "M12 17l3.5-4.5", "M7 11.5l1 1", "M12 9v1.5") }
    val Droplet by lazy { line("droplet", "M12 3.5s6 6.4 6 10.5a6 6 0 0 1-12 0c0-4.1 6-10.5 6-10.5z") }
    val Thermometer by lazy { line("thermometer", "M10 14.5V5a2 2 0 0 1 4 0v9.5a4 4 0 1 1-4 0z", "M12 10v7") }
    val Wind by lazy { line("wind", "M3 9h11a3 3 0 1 0-3-3", "M3 13h15a3 3 0 1 1-3 3", "M3 17h6") }
    val Leaf by lazy { line("leaf", "M6 18C4 10 10 4 20 4c0 10-6 15-14 14z", "M6 18l7-7") }
    val Euro by lazy { line("euro", "M17.5 6.5A7 7 0 1 0 17.5 17.5", "M4 10.5h9", "M4 13.5h9") }
    val Fan by lazy {
        line(
            "fan", "M12 12C12 9 13 6 15 6C17 6 17 9 12 12", "M12 12C14.6 13.5 16.7 15.9 15.7 17.6C14.7 19.3 12.1 17.8 12 12",
            "M12 12C9.4 13.5 6.3 14.1 5.3 12.4C4.3 10.7 6.9 9.2 12 12",
        )
    }
    val Lightbulb by lazy { line("lightbulb", "M9 18h6", "M10 21h4", "M12 3a6 6 0 0 0-3.5 10.9c.6.5 1 1.2 1 2V16h5v-.1c0-.8.4-1.5 1-2A6 6 0 0 0 12 3z") }
    val Cloud by lazy { line("cloud", "M7 18h10a4 4 0 0 0 .6-8A6 6 0 0 0 6.2 11 3.5 3.5 0 0 0 7 18z") }
    val CalendarDay by lazy { line("calendarDay", "M4 6h16v14H4z", "M4 10h16", "M8 3v4", "M16 3v4") }
    val WashingMachine by lazy { line("washingMachine", "M5 3h14v18H5z", "M5 7h14", "M12 10a4 4 0 1 0 0 8a4 4 0 1 0 0-8z", "M8 5h0.01") }
    val Door by lazy { line("door", "M6 21V3h12v18", "M4 21h16", "M14.5 12h0.01") }
    val Bike by lazy {
        line(
            "bike", "M6 13a4 4 0 1 0 0 8a4 4 0 1 0 0-8z", "M18 13a4 4 0 1 0 0 8a4 4 0 1 0 0-8z", "M6 17l4-7h6.5L18 17", "M10 10l3 7h-7",
            "M8.5 7h3", "M15 6.5h2.5l-1 3.5",
        )
    }
    val Bus by lazy { line("bus", "M5 4h14v13H5z", "M5 11h14", "M7 17v3", "M17 17v3", "M8 14h0.01", "M16 14h0.01") }
    val Train by lazy {
        line("train", "M7 3h10a2 2 0 0 1 2 2v9a3 3 0 0 1-3 3H8a3 3 0 0 1-3-3V5a2 2 0 0 1 2-2z", "M5 10h14", "M8 21l2-4", "M16 21l-2-4", "M9 13.5h0.01", "M15 13.5h0.01")
    }
    val Warning by lazy { line("warning", "M12 4L2.5 20h19z", "M12 10v4", "M12 17h0.01") }
    val ArrowUp by lazy { line("arrowUp", "M12 19V5", "M6 11l6-6 6 6") }
    val ArrowDown by lazy { line("arrowDown", "M12 5v14", "M6 13l6 6 6-6") }
    val ArrowLeft by lazy { line("arrowLeft", "M19 12H5", "M11 6l-6 6 6 6") }
    /** Rain: a cloud with three slanted drops. */
    val Rain by lazy { line("rain", "M7 14h10a3.5 3.5 0 0 0 .5-7A5.5 5.5 0 0 0 6.6 8.3 3 3 0 0 0 7 14z", "M8.5 17.5l-1 2.5", "M12.5 17.5l-1 2.5", "M16.5 17.5l-1 2.5") }
    /**
     * A vane arrow pointing up (north at rotation 0), with a fletched tail so it reads as a direction rather than a
     * trend; meant to be turned by `icon_rotation` (wind direction, heading).
     */
    val Direction by lazy { line("direction", "M12 19V3.5", "M7.5 8L12 3.5 16.5 8", "M8.5 21.5l3.5-3 3.5 3") }
}

/** The API 1 plugin icon names (`app.kinframe.core.plugin.PluginIconNames`) drawn with [KinIcons]. */
object PluginIcons {
    fun of(name: String?): ImageVector? = when (name) {
        "bolt" -> KinIcons.Bolt
        "grid" -> KinIcons.Pylon
        "solar" -> KinIcons.SolarPanel
        "sun" -> KinIcons.Sun
        "home" -> KinIcons.Home
        "battery" -> KinIcons.Battery
        "battery-charging" -> KinIcons.BatteryCharging
        "plug" -> KinIcons.Plug
        "car" -> KinIcons.Car
        "flame" -> KinIcons.Flame
        "gas" -> KinIcons.Gas
        "meter" -> KinIcons.Meter
        "water" -> KinIcons.Droplet
        "thermometer" -> KinIcons.Thermometer
        "wind" -> KinIcons.Wind
        "leaf" -> KinIcons.Leaf
        "euro" -> KinIcons.Euro
        "fan" -> KinIcons.Fan
        "lightbulb" -> KinIcons.Lightbulb
        "moon" -> KinIcons.Moon
        "cloud" -> KinIcons.Cloud
        "clock" -> KinIcons.Clock
        "calendar" -> KinIcons.CalendarDay
        "rain" -> KinIcons.Rain
        "direction" -> KinIcons.Direction
        "washing-machine" -> KinIcons.WashingMachine
        "trash" -> KinIcons.Trash
        "door" -> KinIcons.Door
        "lock" -> KinIcons.Lock
        "bike" -> KinIcons.Bike
        "bus" -> KinIcons.Bus
        "train" -> KinIcons.Train
        "user" -> KinIcons.User
        "wifi" -> KinIcons.Wifi
        "bell" -> KinIcons.Bell
        "info" -> KinIcons.Info
        "warning" -> KinIcons.Warning
        "check" -> KinIcons.Check
        "arrow-up" -> KinIcons.ArrowUp
        "arrow-down" -> KinIcons.ArrowDown
        "arrow-left" -> KinIcons.ArrowLeft
        "arrow-right" -> KinIcons.ArrowRight
        else -> null
    }
}

/** Tinted icon with a default size. */
@Composable
fun KinIcon(icon: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Kin.colors.ink, size: Dp = 28.dp) {
    Icon(icon, contentDescription, modifier.size(size), tint = tint)
}
