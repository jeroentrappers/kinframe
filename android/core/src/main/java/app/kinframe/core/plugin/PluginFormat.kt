package app.kinframe.core.plugin

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * `ctx.format` (docs/plugins.md §3.2): numbers in the frame's language, so plugins don't each reinvent "1,2 kW".
 * Number and unit are joined with a no-break space; [ValueView] splits that unit off to draw it smaller.
 * Not thread-safe (NumberFormat); create one per thread or per plugin host.
 */
class PluginFormat(
    val locale: Locale,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val h24: Boolean = true,
    /** The frame's `locale.temperature_unit`: "c" or "f". */
    val temperatureUnit: String = "c",
) {
    private val nf = NumberFormat.getNumberInstance(locale).apply { isGroupingUsed = true }

    private fun num(v: Double, decimals: Int): String {
        nf.minimumFractionDigits = decimals
        nf.maximumFractionDigits = decimals
        // "-0" reads as noise on a meter that hovers around zero.
        val r = if (abs(v) < 0.5 * Math.pow(10.0, -decimals.toDouble())) 0.0 else v
        return nf.format(r)
    }

    private fun join(n: String, unit: String) = "$n$NBSP$unit"

    /** Watts: "850 W", "1,2 kW", "12 kW", "1,5 MW". */
    fun power(watts: Double): String {
        val a = abs(watts)
        return when {
            a < 999.5 -> join(num(watts, 0), "W")
            a < 9_950.0 -> join(num(watts / 1000, 1), "kW")
            a < 999_500.0 -> join(num(watts / 1000, 0), "kW")
            else -> join(num(watts / 1_000_000, 1), "MW")
        }
    }

    /** Kilowatt-hours: "450 Wh", "3,45 kWh", "12,3 kWh", "1.234 kWh". */
    fun energy(kwh: Double): String {
        val a = abs(kwh)
        return when {
            a < 0.9995 -> join(num(kwh * 1000, 0), "Wh")
            a < 9.995 -> join(num(kwh, 2), "kWh")
            a < 99.95 -> join(num(kwh, 1), "kWh")
            a < 99_999.5 -> join(num(kwh, 0), "kWh")
            else -> join(num(kwh / 1000, 0), "MWh")
        }
    }

    /** Cubic metres (gas, water): "1,23 m³", "12,3 m³". */
    fun volume(m3: Double): String = join(num(m3, if (abs(m3) < 9.995) 2 else if (abs(m3) < 99.95) 1 else 0), "m³")

    /** 0–100: "68 %", localised ("68 %" in French, "%68" in Turkish). */
    fun percent(value: Double): String = NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 }.format(value / 100)

    /** Celsius in, the frame's unit out: "12°" / "54°", or "12,8°" with [decimals] = 1. */
    fun temperature(celsius: Double, unit: String = temperatureUnit, decimals: Int = 0): String =
        num(if (unit.equals("f", ignoreCase = true)) celsius * 9 / 5 + 32 else celsius, decimals.coerceIn(0, 3)) + "°"

    /** Any number in the frame's language, with an optional unit: "2,3 m/s", "989 hPa", "1.024". */
    fun number(value: Double, decimals: Int = 0, unit: String? = null): String {
        val n = num(value, decimals.coerceIn(0, 6))
        return if (unit.isNullOrEmpty()) n else join(n, unit)
    }

    /** Seconds: "45 s", "12 min", "2 h 15 min". */
    fun duration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return when {
            s < 60 -> join(num(s.toDouble(), 0), "s")
            s < 3600 -> join(num((s / 60).toDouble(), 0), "min")
            else -> join(num((s / 3600).toDouble(), 0), "h") + " " + join(num((s % 3600 / 60).toDouble(), 0), "min")
        }
    }

    /** Clock time of an instant: "23:10" or "11:10 PM". */
    fun time(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm a", locale))

    companion object {
        const val NBSP = ' '

        /** Splits "1,2 kW" into "1,2" and "kW" for drawing (a unit is the trailing word after a (no-break) space). */
        fun splitUnit(value: String): Pair<String, String?> {
            val i = value.lastIndexOfAny(charArrayOf(NBSP, ' ', ' '))
            if (i <= 0 || i == value.length - 1) return value to null
            val unit = value.substring(i + 1)
            // Only short, non-numeric tails are units ("kW", "m³", "%"), not "12 34".
            return if (unit.length <= 4 && unit.none { it.isDigit() }) value.substring(0, i) to unit else value to null
        }

        /**
         * Splits a formatted number into what is drawn at full size and the decimals drawn small and raised after it,
         * the way price tags write "12,⁹⁹": "21,4" → "21," and "4"; "1.234,5" → "1.234," and "5"; "12,8°" → "12," and
         * "8°". The separator stays full size on the baseline, so the decimals never read as an exponent. Null when
         * there is nothing to raise (no decimals). Locale-free: a decimal part has one or two digits after the last "," or ".", while grouping always
         * has three ("1.234" is a thousand, not a decimal), and the whole part may only be grouped with the other
         * separator or a space. A short trailing sign ("°", "%") stays with the decimals.
         */
        fun raisedDecimals(number: String): Pair<String, String>? {
            val m = RAISED.matchEntire(number) ?: return null
            val whole = m.groupValues[1]
            val sep = m.groupValues[2][0]
            if (whole.contains(sep)) return null
            return number.substring(0, whole.length + 1) to number.substring(whole.length + 1)
        }

        // An optional sign, digits grouped in threes (".", ",", "'", spaces) or not at all, then the decimals.
        private val RAISED = Regex("""^([-−+]?(?:\d{1,3}(?:[.,' \u00A0\u202F]\d{3})+|\d+))([.,])\d{1,2}[^\d.,\s]{0,2}$""")
    }
}
