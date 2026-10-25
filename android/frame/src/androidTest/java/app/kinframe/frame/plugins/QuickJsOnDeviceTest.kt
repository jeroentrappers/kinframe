package app.kinframe.frame.plugins

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kinframe.core.plugin.PluginFormat
import app.kinframe.core.plugin.PluginViewParser
import app.kinframe.core.plugin.ViewResult
import app.kinframe.frame.plugins.js.PluginBundle
import app.kinframe.frame.plugins.js.PluginNet
import app.kinframe.frame.plugins.js.PluginPermissions
import app.kinframe.frame.plugins.js.QuickJsPluginInstance
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.util.Locale

/**
 * The plugin host on a real Android runtime: the QuickJS native library loads on Android 6, the bundled P1 plugin and
 * the prelude come from the APK's assets, and its view code runs inside the per-call CPU budget. Logs the JavaScript
 * time per render (`KinQuickJsTest`), the number to compare with the RK3126:
 *
 *     ANDROID_SERIAL=emulator-5554 ./gradlew :frame:connectedDebugAndroidTest -PemulatorAbis=x86_64 \
 *       -Pandroid.testInstrumentationRunnerArguments.class=app.kinframe.frame.plugins.QuickJsOnDeviceTest
 */
@RunWith(AndroidJUnit4::class)
class QuickJsOnDeviceTest {
    @Test fun p1ViewsRunInTheSandbox(): Unit = runBlocking {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val p1 = PluginBundle.fromAssets(assets, "kinframe.homewizard-p1")!!
        val prelude = assets.open("plugin-host/prelude.js").bufferedReader().use { it.readText() }
        // The P1 plugin's own view code, fed a fixed meter reading instead of polling a meter.
        val reading = """{"active_power_w":1203,"active_power_l1_w":412,"active_power_l2_w":531,"active_power_l3_w":260,"total_power_import_kwh":13779.338,"total_power_export_kwh":5114.106,"total_gas_m3":3204.581}"""
        val js = p1.mainJs.replace("export default {", "const live = {") + """
            export default {
              async render(ctx) { const h = history(ctx); const r = parse($reading); add(h, r, ctx.now()); updateDay(h, r, ctx); return main(r, trend(h), ctx.settings, ctx); },
              async detail(ctx) { const h = history(ctx); return detail(h.last, lastHour(h), today(h, h.last), ctx); },
            };"""
        val bundle = PluginBundle(p1.manifest, js, p1.locales)
        val settings = JsonObject(mapOf("host" to JsonPrimitive("192.168.1.50"), "style" to JsonPrimitive("gauge")))
        val instance = QuickJsPluginInstance(bundle, prelude, PluginNet(OkHttpClient(), PluginPermissions(bundle.manifest, settings)))
        val store = HashMap<String, String>()
        var now = 1_792_900_000_000L
        val ctx = PluginContext(
            "device", settings, HashMap(),
            object : PluginStore {
                override fun get(key: String) = store[key]
                override fun put(key: String, value: String?) { if (value == null) store.remove(key) else store[key] = value }
            },
            PluginHttp(OkHttpClient(), { false }, localOnly = true),
            format = { PluginFormat(Locale.forLanguageTag("nl"), ZoneId.of("Europe/Brussels")) }, t = { it }, log = { Log.i("KinQuickJsTest", it) },
            now = { now }, zone = { ZoneId.of("Europe/Brussels") }, invalidate = {},
        )
        val t0 = System.nanoTime()
        instance.start(ctx)
        Log.i("KinQuickJsTest", "start: %.1f ms".format((System.nanoTime() - t0) / 1e6))
        val times = ArrayList<Double>()
        repeat(100) {
            now += 5_000
            val v = instance.render(ctx)!!
            times += instance.lastCallMs
            assertTrue(PluginViewParser.parse(v) is ViewResult.Valid)
        }
        val d = instance.detail(ctx)!!
        assertTrue(PluginViewParser.parse(d) is ViewResult.Valid)
        times.sort()
        Log.i("KinQuickJsTest", "render ms p50 %.2f p90 %.2f max %.2f; detail %.2f ms".format(times[50], times[90], times.last(), instance.lastCallMs))
        instance.stop(ctx)
    }
}
