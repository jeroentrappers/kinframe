package app.kinframe.frame.plugins

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.plugin.PluginView
import app.kinframe.core.plugin.imageSources
import app.kinframe.core.plugin.PluginViewParser
import app.kinframe.core.plugin.ViewResult
import app.kinframe.core.plugin.ui.PluginPalette
import app.kinframe.core.plugin.ui.PluginViewContent
import app.kinframe.core.plugin.ui.ViewForm
import app.kinframe.frame.FakePhoto
import app.kinframe.frame.Screenshots
import app.kinframe.frame.weather.WeatherFixtures
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Plugin views and the overlay / band layouts at the Denver's size (1280×800 mdpi, landscape and portrait), rendered to
 * `frame/build/screenshots/plugins_*.png` for design review. Neutral drawn backgrounds only, no photos. Representative
 * ones are copied to docs/plugins/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class PluginScreenshotTest {
    private val examples = File("../../docs/api/plugin-views/examples")

    private fun view(name: String): PluginView = when (val r = PluginViewParser.parse(examples.resolve("$name.json").readText())) {
        is ViewResult.Valid -> r.view
        is ViewResult.Invalid -> error("$name: $r")
    }

    private val now = WeatherFixtures.nowMs
    private val weather = WeatherFixtures.gent()

    private fun stage(
        name: String, layout: PluginLayoutConfig, vararg instances: InstanceState, detail: InstanceState? = null, detailView: PluginView? = null,
        seed: Int = 0, pair: Boolean = false,
    ) =
        Screenshots.shot("plugins_$name") {
            androidx.compose.foundation.layout.BoxWithConstraints {
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                val img = androidx.compose.runtime.remember {
                    if (!pair) placeholderPhoto(w, h, seed) else {
                        // Two photos side by side as the slideshow renders them, at the photo area's size.
                        val area = (BandGeometry.compute(w, h, 1f, layout.side) as? BandGeometry.Band)?.photo ?: PxRect(0, 0, w, h)
                        pairPhoto(area.width, area.height, seed)
                    }
                }
                PluginStage(
                    layout, instances.toList(), "14:20", "Sunday 25 October", weather, now, shortDate = "Sun\n25 Oct",
                    photoImage = img, detail = detail, detailView = detailView,
                ) {
                    androidx.compose.foundation.Image(img, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                }
            }
        }

    /** A pair (two portrait photos on a landscape area, two landscape ones on a portrait area) with sender lines. */
    private fun pairPhoto(w: Int, h: Int, seed: Int): androidx.compose.ui.graphics.ImageBitmap {
        val landscape = w >= h
        val dir = kotlin.io.path.createTempDirectory("pair").toFile()
        fun file(i: Int): File {
            val pw = if (landscape) 600 else 1200
            val ph = if (landscape) 900 else 800
            val f = File(dir, "p$i.png")
            f.outputStream().use { placeholderPhoto(pw, ph, seed + i).asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            return f
        }
        val tiles = app.kinframe.frame.slideshow.Collage.cells(2, landscape).mapIndexed { i, cell ->
            app.kinframe.frame.slideshow.SlideRenderer.TileRequest(
                file(i), 0, null, null, cell,
                label = listOf("Anna · Yesterday", "Line Van Gavere · via Frameo · 5 days ago")[i],
                caption = "Autumn walk in the woods".takeIf { i == 1 },
            )
        }
        return app.kinframe.frame.slideshow.SlideRenderer().renderTiles(tiles, w, h, 1f, app.kinframe.frame.slideshow.CornerReserve.None).asImageBitmap()
    }

    private val band = PluginLayoutConfig(PluginLayoutMode.Band, BandSide.Right)
    private val overlay = PluginLayoutConfig(PluginLayoutMode.Overlay)

    @Test fun bandLandscapePaper() = stage(
        "band_landscape_paper", band,
        previewInstance("a", view("flow-solar-home-grid"), Slot.TopLeft), previewInstance("b", view("value-using"), Slot.TopRight),
    )

    /** The Denver as used: a portrait pair, the band on the left over the photo's colours, the P1 dial in it. */
    @Test fun bandLandscapePairLeft() = stage(
        "band_landscape_pair_left", band.copy(side = BandSide.Left, background = BandBackground.Photo),
        previewInstance("a", view("gauge-dial-using"), Slot.TopLeft), seed = 1, pair = true,
    )

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun bandPortraitPair() = stage(
        "band_portrait_pair", band.copy(background = BandBackground.Photo),
        previewInstance("a", view("gauge-dial-using"), Slot.TopLeft), previewInstance("b", view("value-returning"), Slot.TopRight),
        seed = 2, pair = true,
    )

    @Test fun bandLandscapePhotoLeft() = stage(
        "band_landscape_photo_left", band.copy(side = BandSide.Left, background = BandBackground.Photo),
        previewInstance("a", view("value-returning"), Slot.TopLeft), previewInstance("b", view("gauge-car"), Slot.TopRight), seed = 1,
    )

    @Test fun bandLandscapeFiveNodes() = stage(
        "band_landscape_flow5", band,
        previewInstance("a", view("flow-five-nodes"), Slot.TopLeft), seed = 3,
    )

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun bandPortraitPaper() = stage(
        "band_portrait_paper", band,
        previewInstance("a", view("flow-solar-home-grid"), Slot.TopLeft), previewInstance("b", view("value-using"), Slot.TopRight),
        seed = 2,
    )

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun bandPortraitPhoto() = stage(
        "band_portrait_photo", band.copy(background = BandBackground.Photo),
        previewInstance("a", view("stack-car"), Slot.TopLeft), previewInstance("b", view("value-returning"), Slot.TopRight),
        seed = 1,
    )

    @Test fun overlayLandscape() = stage(
        "overlay_landscape", overlay,
        previewInstance("a", view("flow-solar-home-grid"), Slot.TopLeft), previewInstance("b", view("value-using"), Slot.TopRight),
        previewInstance("c", view("text-charging"), Slot.TopRight),
    )

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun overlayPortrait() = stage(
        "overlay_portrait", overlay,
        previewInstance("a", view("gauge-car"), Slot.TopLeft), previewInstance("b", view("value-returning"), Slot.TopRight),
        seed = 3,
    )

    @Test fun overlayStaleAndUnavailable() = stage(
        "overlay_stale_unavailable", overlay,
        previewInstance("a", view("value-using"), Slot.TopLeft, InstanceStatus.Stale),
        previewInstance("b", null, Slot.TopRight, InstanceStatus.Unavailable),
        seed = 1,
    )

    @Test fun fourByThreeFallsBackToCorners() = Unit // geometry: BandGeometryTest.fourByThreeHasNoRoomAndUsesTheCorners

    @Test fun detailCard() {
        val p1 = previewInstance("p1", view("value-using"), Slot.TopRight, name = "HomeWizard P1 meter")
        stage("detail_landscape", band, p1, detail = p1, detailView = view("detail-p1"))
    }

    @Test @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun bandFullHd() = stage(
        "band_1920x1080", band,
        previewInstance("a", view("flow-solar-home-grid"), Slot.TopLeft), previewInstance("b", view("gauge-car"), Slot.TopRight),
    )

    // ------------------------------------------------------------------ weather station (§9.4)

    private val station = "Weather station"

    @Test fun p1DialBand() = stage(
        "p1_dial_band", band,
        previewInstance("a", view("gauge-dial-using"), Slot.TopLeft), previewInstance("b", view("gauge-dial-returning"), Slot.TopRight),
    )

    @Test fun p1DialOverlay() = stage(
        "p1_dial_overlay", overlay,
        previewInstance("a", view("gauge-dial-using"), Slot.TopLeft), previewInstance("b", view("gauge-dial-returning"), Slot.TopRight),
        seed = 2,
    )

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun p1DialStrip() = stage(
        "p1_dial_strip", band,
        previewInstance("a", view("gauge-dial-using"), Slot.TopLeft), previewInstance("b", view("gauge-dial-returning"), Slot.TopRight),
        seed = 1,
    )

    @Test fun stationCorners() = stage(
        "station_overlay_landscape", overlay,
        previewInstance("a", view("weather-station-compact"), Slot.TopLeft, name = station),
        previewInstance("b", view("weather-station-rows"), Slot.TopRight, name = station),
        seed = 3,
    )

    @Test fun stationBandColumn() = stage(
        "station_band_column", band,
        previewInstance("a", view("weather-station-compact"), Slot.TopLeft, name = station),
        previewInstance("b", view("weather-station-rows"), Slot.TopRight, name = station),
    )

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun stationBandStrip() = stage(
        "station_band_strip", band.copy(background = BandBackground.Photo),
        previewInstance("a", view("weather-station-compact"), Slot.TopLeft, name = station),
        previewInstance("b", view("weather-station-rows"), Slot.TopRight, name = station),
        seed = 1,
    )

    @Test fun stationDetail() {
        val s = previewInstance("ws", view("weather-station-compact"), Slot.TopLeft, name = station)
        stage("station_detail", overlay, s, detail = s, detailView = view("detail-weather-station"), seed = 3)
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun stationDetailPortrait() {
        val s = previewInstance("ws", view("weather-station-compact"), Slot.TopLeft, name = station)
        stage("station_detail_portrait", band, s, detail = s, detailView = view("detail-weather-station"), seed = 2)
    }

    /** Every example in every form: corner and detail (on photo and on paper) and the two compact band forms. */
    @Test @Config(qualifiers = "w1280dp-h1900dp-port-mdpi")
    fun gallery() = Screenshots.shot("plugins_gallery") {
        val names = examples.list()!!.filter { it.endsWith(".json") && !it.startsWith("detail-") }.sorted().map { it.removeSuffix(".json") }
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(560.dp).fillMaxHeight()) {
                FakePhoto(1)
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    names.forEach { PluginViewContent(view(it), ViewForm.Corner, PluginPalette.OnPhoto) }
                }
            }
            Column(Modifier.width(96.dp).fillMaxHeight().background(Kin.colors.paper).padding(vertical = 20.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Label("column")
                names.forEach { PluginViewContent(view(it), ViewForm.Column, PluginPalette.onPaper(Kin.colors)) }
            }
            Column(Modifier.fillMaxSize().background(Color(0xFF2B2A33)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Label("strip", Color.White)
                names.forEach { Box(Modifier.height(64.dp), contentAlignment = Alignment.CenterStart) { PluginViewContent(view(it), ViewForm.Strip, PluginPalette.OnPhoto) } }
            }
        }
    }

    @Test fun detailForms() = Screenshots.shot("plugins_detail_forms") {
        Row(Modifier.fillMaxSize().background(Kin.colors.surface).padding(28.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
                listOf("detail-p1").forEach { PluginViewContent(view(it), ViewForm.Detail, PluginPalette.onPaper(Kin.colors)) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(26.dp)) {
                listOf("flow-five-nodes", "rows-energy", "sparkline-day").forEach { PluginViewContent(view(it), ViewForm.Detail, PluginPalette.onPaper(Kin.colors)) }
            }
        }
    }

    @Composable
    private fun Label(t: String, c: Color = Kin.colors.inkSoft) = Text(t, style = Kin.type.caption, color = c)

    // ---------------------------------------------------------------------------- JavaScript plugins (step 2)

    /** Views straight from the bundled plugins, run in QuickJS against the fake Home Assistant and fixtures. */
    private fun jsViews(plugin: String, fixture: String, vararg settings: Pair<String, Any>, prepare: (app.kinframe.frame.plugins.js.FakeHomeAssistant) -> Unit = {}): Pair<PluginView, PluginView> =
        kotlinx.coroutines.runBlocking {
            val ha = app.kinframe.frame.plugins.js.FakeHomeAssistant(java.io.File("../../plugins/$plugin/fixtures/$fixture"))
            prepare(ha)
            val map = mapOf("base_url" to kotlinx.serialization.json.JsonPrimitive(ha.baseUrl), "token" to kotlinx.serialization.json.JsonPrimitive(ha.token)) +
                settings.associate { (k, v) ->
                    k to if (v is List<*>) kotlinx.serialization.json.JsonArray(v.map { kotlinx.serialization.json.JsonPrimitive(it as String) }) else kotlinx.serialization.json.JsonPrimitive(v.toString())
                }
            val kit = app.kinframe.frame.plugins.js.JsPluginKit(app.kinframe.frame.plugins.js.JsPluginKit.plugin(plugin), kotlinx.serialization.json.JsonObject(map), language = "en")
            kit.now = java.time.Instant.parse("2026-10-25T20:00:00Z").toEpochMilli()
            try { kit.view() to kit.detail() } finally { kit.close(); ha.close() }
        }

    private val energyKeys = arrayOf<Pair<String, Any>>(
        "solar" to listOf("sensor.solis_s6_eh1p_total_pv_power_2", "sensor.omnik_hoofddak_inverter_omnik_hoofddak_current_power_production"),
        "grid" to "sensor.p1_meter_vermogen", "battery" to "sensor.solis_s6_eh1p_battery_power_2",
        "battery_soc" to "sensor.solis_s6_eh1p_battery_soc_2", "home" to "sensor.huisverbruik_vermogen", "ev" to "sensor.ev_laadvermogen_smartevse_w",
    )

    private fun energy(fixture: String) = jsViews("kinframe.ha-energy", fixture, *energyKeys)
    private fun media() = jsViews("kinframe.ha-media", "states-playing.json", "entity" to "media_player.woonkamer")
    private fun car(charging: Boolean) = jsViews("kinframe.ha-ev", "states.json", "prefix" to "ev3", "name" to "EV3") { ha ->
        if (charging) { ha.set("binary_sensor.ev3_ev_battery_charge", "on"); ha.set("sensor.ev3_estimated_charge_duration", "95"); ha.set("sensor.ev3_ev_battery_level", "64") }
    }

    /** A stand-in album cover drawn here (no real artwork): soft bands and a circle. */
    private fun albumArt(): androidx.compose.ui.graphics.ImageBitmap {
        val b = android.graphics.Bitmap.createBitmap(300, 300, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(b)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.shader = android.graphics.LinearGradient(0f, 0f, 300f, 300f, 0xFFE07A5F.toInt(), 0xFF3D405B.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 300f, 300f, paint)
        paint.shader = null; paint.color = 0xCCF2CC8F.toInt()
        c.drawCircle(200f, 110f, 60f, paint)
        paint.color = 0x9981B29A.toInt()
        c.drawRect(0f, 210f, 300f, 300f, paint)
        return b.asImageBitmap()
    }

    private fun withArt(s: InstanceState, v: PluginView): InstanceState =
        s.copy(images = v.imageSourcesDeep().associateWith { albumArt() })

    private fun PluginView.imageSourcesDeep(): List<String> = imageSources()

    @Test fun jsBandColumn() {
        val (flow, _) = energy("states-sunny.json")
        val (playing, _) = media()
        stage(
            "js_band_column", band.copy(side = BandSide.Left, background = BandBackground.Photo),
            previewInstance("e", flow, Slot.TopLeft, name = "Energy flow"),
            withArt(previewInstance("m", playing, Slot.TopRight, name = "Now playing"), playing), seed = 1, pair = true,
        )
    }

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun jsStrip() {
        val (ev, _) = car(charging = true)
        stage("js_strip", band, previewInstance("c", ev, Slot.TopLeft, name = "Car battery"), previewInstance("d", view("gauge-dial-today"), Slot.TopRight), seed = 2)
    }

    @Test fun jsCorners() {
        val (flow, _) = energy("states-evening.json")
        val (ev, _) = car(charging = false)
        stage("js_corners", overlay, previewInstance("e", flow, Slot.TopLeft, name = "Energy flow"), previewInstance("c", ev, Slot.TopRight, name = "Car battery"), seed = 3)
    }

    @Test fun jsDetailMedia() {
        val (playing, detail) = media()
        val s = withArt(previewInstance("m", playing, Slot.TopRight, name = "Now playing"), detail)
        stage("js_detail_media", band, s, detail = s, detailView = detail, seed = 2)
    }

    @Test fun jsDetailEnergy() {
        val (flow, detail) = energy("states-sunny.json")
        val s = previewInstance("e", flow, Slot.TopLeft, name = "Energy flow")
        stage("js_detail_energy", overlay, s, detail = s, detailView = detail, seed = 0)
    }

    @Test fun jsDetailCar() {
        val (ev, detail) = car(charging = true)
        val s = previewInstance("c", ev, Slot.TopLeft, name = "Car battery")
        stage("js_detail_car", overlay, s, detail = s, detailView = detail, seed = 1)
    }

    @Test fun newComponentsInEveryForm() = Screenshots.shot("plugins_new_components") {
        val names = listOf("gauge-dial-today", "progress-temperature-range", "flow-battery-ring", "image-album-art")
        Column(Modifier.fillMaxSize().background(Color(0xFF1C1714)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            names.forEach { n ->
                val v = view(n)
                val images = v.imageSourcesDeep().associateWith { albumArt() }
                androidx.compose.runtime.CompositionLocalProvider(app.kinframe.core.plugin.ui.LocalPluginImages provides { src -> images[src] }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                        Text(n, color = Color.White, modifier = Modifier.width(150.dp))
                        Box(Modifier.width(80.dp).background(Kin.colors.paper).padding(vertical = 6.dp), contentAlignment = Alignment.TopCenter) {
                            PluginViewContent(v, ViewForm.Column, PluginPalette.onPaper(Kin.colors))
                        }
                        Box(Modifier.width(300.dp).background(Kin.colors.paper).padding(8.dp)) { PluginViewContent(v, ViewForm.Strip, PluginPalette.onPaper(Kin.colors)) }
                        Box(Modifier.width(300.dp).background(Color(0xFF5B6B5E)).padding(8.dp)) { PluginViewContent(v, ViewForm.Corner, PluginPalette.OnPhoto) }
                        Box(Modifier.width(380.dp).background(Kin.colors.surface).padding(8.dp)) { PluginViewContent(v, ViewForm.Detail, PluginPalette.onPaper(Kin.colors)) }
                    }
                }
            }
        }
    }
}
