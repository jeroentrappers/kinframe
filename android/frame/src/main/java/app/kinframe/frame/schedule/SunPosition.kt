package app.kinframe.frame.schedule

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Solar elevation from latitude/longitude and time (low-precision almanac, ±0.5°: plenty for brightness). */
object SunPosition {
    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)

    /** Elevation of the sun's centre above the horizon in degrees (negative at night). */
    fun elevation(latDeg: Double, lonDeg: Double, epochMs: Long): Double {
        val jd = epochMs / 86_400_000.0 + 2440587.5
        val n = jd - 2451545.0
        val l = (280.460 + 0.9856474 * n).mod(360.0)
        val g = rad((357.528 + 0.9856003 * n).mod(360.0))
        val lambda = rad(l + 1.915 * sin(g) + 0.020 * sin(2 * g))
        val eps = rad(23.439 - 0.0000004 * n)
        val ra = atan2(cos(eps) * sin(lambda), cos(lambda))
        val dec = asin(sin(eps) * sin(lambda))
        val gmstHours = (18.697374558 + 24.06570982441908 * n).mod(24.0)
        val lmstDeg = gmstHours * 15.0 + lonDeg
        val ha = rad(lmstDeg) - ra
        val lat = rad(latDeg)
        return deg(asin(sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(ha)))
    }
}
