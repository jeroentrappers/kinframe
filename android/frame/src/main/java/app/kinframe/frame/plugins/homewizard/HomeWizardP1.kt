package app.kinframe.frame.plugins.homewizard

import app.kinframe.core.plugin.GaugeStyle
import app.kinframe.core.plugin.PluginFormat
import app.kinframe.core.plugin.Tone
import app.kinframe.core.plugin.Trend
import app.kinframe.core.plugin.ViewJson
import app.kinframe.frame.plugins.LocalAddress
import app.kinframe.frame.plugins.PluginContext
import app.kinframe.frame.plugins.PluginInstance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import java.time.Instant
import kotlin.math.abs

/** One reading of the meter's local API v1 (`GET /api/v1/data`). Fields the meter doesn't have are null. */
data class P1Reading(
    /** Positive = taking from the grid, negative = returning (solar). */
    val activePowerW: Double?,
    val phasesW: List<Double>,
    val importKwh: Double?,
    val exportKwh: Double?,
    val gasM3: Double?,
    val meterModel: String?,
)

object HomeWizardData {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Maps the v1 response. Real meters omit fields they don't measure (no gas meter, single phase), and older
     * firmware has no `total_power_import_kwh` but only the per-tariff `_t1`/`_t2` totals, which are summed.
     */
    fun parse(body: String): P1Reading {
        val o = json.parseToJsonElement(body).jsonObject
        fun d(key: String) = (o[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
        fun total(prefix: String): Double? = d("${prefix}_kwh") ?: listOfNotNull(d("${prefix}_t1_kwh"), d("${prefix}_t2_kwh")).takeIf { it.isNotEmpty() }?.sum()
        return P1Reading(
            activePowerW = d("active_power_w"),
            phasesW = listOfNotNull(d("active_power_l1_w"), d("active_power_l2_w"), d("active_power_l3_w")),
            importKwh = total("total_power_import"),
            exportKwh = total("total_power_export"),
            gasM3 = d("total_gas_m3"),
            meterModel = (o["meter_model"] as? JsonPrimitive)?.contentOrNull,
        )
    }
}

/**
 * The built-in HomeWizard P1 widget (docs/plugins.md §9.2), written against [PluginInstance] so the QuickJS plugin
 * `kinframe.homewizard-p1` can replace it. Polls the meter every 5 s on the home network only.
 *
 * Settings: `host` (address as typed or discovered), `style` ("value", "flow" or "gauge"), `warn_w` (0 = off; value
 * and flow), `max_w` (the gauge's full scale either way, default 4000 W).
 */
class HomeWizardP1 : PluginInstance {
    override val refreshSeconds = 5

    override suspend fun render(ctx: PluginContext): JsonElement? {
        val host = (ctx.settings["host"] as? JsonPrimitive)?.contentOrNull ?: return null
        val base = LocalAddress.baseUrl(host) ?: throw IllegalArgumentException("not an address: $host")
        val body = ctx.http.get(base.newBuilder().addPathSegments("api/v1/data").build())
        val r = HomeWizardData.parse(body)
        val st = P1State.of(ctx)
        st.add(r, ctx.now())
        st.updateDay(r, ctx)
        return HomeWizardViews.main(r, st.trend(), ctx.settings, ctx.format(), ctx.t)
    }

    override suspend fun detail(ctx: PluginContext): JsonElement? {
        val st = P1State.of(ctx)
        val r = st.last ?: return null
        return HomeWizardViews.detail(r, st.lastHour(), st.today(r), ctx.format(), ctx.t)
    }
}

/** Today's totals: used, returned and gas since the first reading of the day. */
data class P1Today(val importKwh: Double?, val exportKwh: Double?, val gasM3: Double?)

object HomeWizardViews {
    const val DEFAULT_MAX_W = 4000.0

    fun main(r: P1Reading, trend: Trend?, settings: JsonObject, f: PluginFormat, t: (String) -> String): JsonObject {
        val p = r.activePowerW ?: 0.0
        val warnW = (settings["warn_w"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val exporting = p < -0.5
        val tone = when {
            exporting -> Tone.Good
            warnW > 0 && p > warnW -> Tone.Warn
            else -> Tone.Neutral
        }
        val style = (settings["style"] as? JsonPrimitive)?.contentOrNull
        if (style == "gauge") {
            val maxW = (settings["max_w"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0 } ?: DEFAULT_MAX_W
            return ViewJson.gauge(
                (p / maxW * 100).coerceIn(-100.0, 100.0).toFloat(), label = f.number(abs(p), unit = "W"), style = GaugeStyle.Dial,
                tone = Tone.Info, negativeTone = Tone.Good,
            )
        }
        if (style == "flow") {
            return ViewJson.flow(
                listOf(
                    ViewJson.node("grid", "grid", f.power(abs(p)), t("grid")),
                    ViewJson.node("home", "home", label = t("home")),
                ),
                listOf(if (exporting) ViewJson.edge("home", "grid", (-p).toFloat()) else ViewJson.edge("grid", "home", p.toFloat())),
                tone,
            )
        }
        return ViewJson.value(
            f.power(abs(p)), label = t(if (exporting) "returning" else "using"), icon = if (exporting) "sun" else "bolt", trend = trend, tone = tone,
        )
    }

    fun detail(r: P1Reading, lastHour: List<Float?>, today: P1Today, f: PluginFormat, t: (String) -> String): JsonObject {
        val items = mutableListOf<JsonElement>(main(r, null, JsonObject(emptyMap()), f, t))
        if (lastHour.count { it != null } >= 2) {
            items += ViewJson.sparkline(lastHour, label = t("last_hour"), showRange = true)
        }
        if (r.phasesW.size > 1) {
            items += ViewJson.rows(r.phasesW.mapIndexed { i, w -> ViewJson.value(f.power(w), label = "L${i + 1}", icon = "bolt") })
        }
        val totals = listOfNotNull(
            today.importKwh?.let { ViewJson.value(f.energy(it), label = t("today_import"), icon = "grid") },
            today.exportKwh?.takeIf { it > 0.0 }?.let { ViewJson.value(f.energy(it), label = t("today_export"), icon = "sun", tone = Tone.Good) },
            today.gasM3?.let { ViewJson.value(f.volume(it), label = t("today_gas"), icon = "flame") },
        )
        if (totals.isNotEmpty()) items += ViewJson.rows(totals)
        return ViewJson.stack(items.take(4))
    }
}

/** What the widget remembers: recent samples (ctx.state) and the day's starting totals (ctx.store). */
internal class P1State {
    var last: P1Reading? = null
    private val samples = FloatArray(SAMPLES) { Float.NaN }
    private val times = LongArray(SAMPLES)
    private var head = 0
    private var count = 0

    fun add(r: P1Reading, at: Long) {
        last = r
        val p = r.activePowerW ?: return
        samples[head] = p.toFloat(); times[head] = at
        head = (head + 1) % SAMPLES
        if (count < SAMPLES) count++
    }

    /** Up or down when the last reading differs from the 30 s before it by more than 10 % (and 50 W). */
    fun trend(): Trend? {
        if (count < 4) return null
        val lastIdx = (head - 1 + SAMPLES) % SAMPLES
        val now = samples[lastIdx]
        var sum = 0f; var n = 0
        for (k in 1..6) {
            if (k >= count) break
            val v = samples[(lastIdx - k + SAMPLES) % SAMPLES]
            if (!v.isNaN()) { sum += v; n++ }
        }
        if (n == 0) return null
        val avg = sum / n
        val delta = now - avg
        return when {
            abs(delta) < maxOf(50f, abs(avg) * 0.1f) -> null
            delta > 0 -> Trend.Up
            else -> Trend.Down
        }
    }

    /** The last hour in 96 buckets (≈37 s each), averaged; null where there was no reading. */
    fun lastHour(): List<Float?> {
        val lastIdx = (head - 1 + SAMPLES) % SAMPLES
        if (count == 0) return emptyList()
        val end = times[lastIdx]
        val start = end - HOUR_MS
        val sums = FloatArray(BUCKETS); val ns = IntArray(BUCKETS)
        for (k in 0 until count) {
            val i = (lastIdx - k + SAMPLES) % SAMPLES
            val tt = times[i]
            if (tt < start) break
            val b = (((tt - start).toDouble() / HOUR_MS) * (BUCKETS - 1)).toInt().coerceIn(0, BUCKETS - 1)
            sums[b] += samples[i]; ns[b]++
        }
        return (0 until BUCKETS).map { if (ns[it] == 0) null else sums[it] / ns[it] }
    }

    private var day: String? = null
    private var dayImport: Double? = null
    private var dayExport: Double? = null
    private var dayGas: Double? = null

    fun updateDay(r: P1Reading, ctx: PluginContext) {
        val today = Instant.ofEpochMilli(ctx.now()).atZone(ctx.zone()).toLocalDate().toString()
        if (day == null) {
            // Restore the day's baseline after a restart.
            ctx.store.get("day")?.split('|')?.takeIf { it.size == 4 }?.let { p ->
                day = p[0]; dayImport = p[1].toDoubleOrNull(); dayExport = p[2].toDoubleOrNull(); dayGas = p[3].toDoubleOrNull()
            }
        }
        if (day != today) {
            day = today; dayImport = r.importKwh; dayExport = r.exportKwh; dayGas = r.gasM3
            runCatching { ctx.store.put("day", "$today|${dayImport ?: ""}|${dayExport ?: ""}|${dayGas ?: ""}") }
        }
    }

    fun today(r: P1Reading) = P1Today(
        importKwh = r.importKwh?.let { v -> dayImport?.let { (v - it).coerceAtLeast(0.0) } },
        exportKwh = r.exportKwh?.let { v -> dayExport?.let { (v - it).coerceAtLeast(0.0) } },
        gasM3 = r.gasM3?.let { v -> dayGas?.let { (v - it).coerceAtLeast(0.0) } },
    )

    companion object {
        private const val SAMPLES = 720 // one hour at 5 s
        private const val BUCKETS = 96
        private const val HOUR_MS = 3_600_000L

        fun of(ctx: PluginContext): P1State = ctx.state.getOrPut("p1") { P1State() } as P1State
    }
}
