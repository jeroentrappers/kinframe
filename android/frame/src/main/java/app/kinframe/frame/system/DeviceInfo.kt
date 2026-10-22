package app.kinframe.frame.system

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.DisplayMetrics
import android.view.WindowManager
import app.kinframe.core.api.DeviceAppInfo
import app.kinframe.core.api.DeviceCapabilities
import app.kinframe.core.api.DeviceEnroll
import app.kinframe.core.api.DeviceHardware
import app.kinframe.core.api.Screen
import app.kinframe.frame.BuildConfig
import java.util.Locale
import java.util.TimeZone

/**
 * Per-board capability overrides (product spec §5 "Hardware capabilities"). Android's feature flags and sensor
 * list are unreliable on frame hardware: the Denver PFF-1024 advertises Rockchip template sensors that don't exist
 * (docs/hardware/denver-pff-1024.md).
 */
object BoardProfiles {
    data class Override(
        val lightSensor: Boolean? = null,
        val motionSensor: Boolean? = null,
        val accelerometer: Boolean? = null,
        val battery: Boolean? = null,
        val speaker: Boolean? = null,
        val sdCard: Boolean? = null,
        val usbHost: Boolean? = null,
    )

    private val known = mapOf(
        "denver/PFF_1024" to Override(
            lightSensor = false, motionSensor = false, accelerometer = true, battery = false,
            speaker = true, sdCard = true, usbHost = true,
        ),
    )

    fun forBuild(): Override? = known["${Build.BRAND.lowercase()}/${Build.MODEL}"] ?: known["${Build.BRAND.lowercase()}/${Build.DEVICE}"]
}

object DeviceInfo {
    fun screen(context: Context): Screen {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        val landscape = m.widthPixels >= m.heightPixels
        return Screen(m.widthPixels, m.heightPixels, m.densityDpi, if (landscape) "landscape" else "portrait")
    }

    /** Largest H.264 size a hardware (or any) decoder accepts, so the server never sends what we can't play. */
    fun maxVideoDecode(): Screen? = runCatching {
        var best: Pair<Int, Int>? = null
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        for (info in list) {
            if (info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }) continue
            val caps: MediaCodecInfo.VideoCapabilities = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities ?: continue
            for ((w, h) in listOf(3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720, 854 to 480)) {
                if (caps.isSizeSupported(w, h)) {
                    if (best == null || w * h > best.first * best.second) best = w to h
                    break
                }
            }
        }
        best?.let { Screen(it.first, it.second) }
    }.getOrNull()

    fun capabilities(context: Context, root: Boolean): DeviceCapabilities {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val pm = context.packageManager
        val o = BoardProfiles.forBuild()
        return DeviceCapabilities(
            lightSensor = o?.lightSensor ?: (sm.getDefaultSensor(Sensor.TYPE_LIGHT) != null),
            motionSensor = o?.motionSensor ?: false,
            accelerometer = o?.accelerometer ?: (sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null),
            battery = o?.battery ?: hasBattery(context),
            speaker = o?.speaker ?: pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT),
            sdCard = o?.sdCard ?: (context.getExternalFilesDirs(null).size > 1),
            usbHost = o?.usbHost ?: pm.hasSystemFeature(PackageManager.FEATURE_USB_HOST),
            root = root,
        )
    }

    private fun hasBattery(context: Context): Boolean {
        val i = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        return i?.getBooleanExtra(android.os.BatteryManager.EXTRA_PRESENT, false) == true &&
            java.io.File("/sys/class/power_supply").list()?.isNotEmpty() == true
    }

    fun enrollRequest(context: Context, root: Boolean): DeviceEnroll {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        return DeviceEnroll(
            hardware = DeviceHardware(
                brand = Build.BRAND,
                model = Build.MODEL,
                board = Build.BOARD,
                androidSdk = Build.VERSION.SDK_INT,
                abis = Build.SUPPORTED_ABIS.toList(),
                ramMb = (mi.totalMem / (1024 * 1024)).toInt(),
                storageTotalBytes = stat.totalBytes,
                maxVideoDecode = maxVideoDecode(),
                capabilities = capabilities(context, root),
            ),
            screen = screen(context),
            app = DeviceAppInfo(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            locale = app.kinframe.frame.Locales.effective(context),
            timezone = TimeZone.getDefault().id,
        )
    }

    fun isPortrait(context: Context) = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
}
