package app.kinframe.frame.plugins

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kinframe.frame.plugins.station.WeathercloudSource
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One real request to Weathercloud from the device, for the station given as an instrumentation argument (skipped
 * otherwise, so CI never calls the unofficial endpoint). Checks TLS on Android 6 (the chain ends in USERTrust RSA) and
 * that the live response still parses:
 *
 *     ANDROID_SERIAL=emulator-5554 ./gradlew :frame:connectedDebugAndroidTest -PemulatorAbis=x86_64 \
 *       -Pandroid.testInstrumentationRunnerArguments.class=app.kinframe.frame.plugins.WeathercloudLiveTest \
 *       -Pandroid.testInstrumentationRunnerArguments.weathercloudCode=<code>
 */
@RunWith(AndroidJUnit4::class)
class WeathercloudLiveTest {
    @Test fun readsTheStation(): Unit = runBlocking {
        val code = InstrumentationRegistry.getArguments().getString("weathercloudCode")
        assumeTrue("no weathercloudCode argument", !code.isNullOrBlank())
        val source = WeathercloudSource(code!!)
        val http = PluginHttp(OkHttpClient(), allowed = source::allows, localOnly = false, userAgent = "kinframe-frame/test")
        val r = source.read(http)
        Log.i("KinWeathercloudTest", "reading: $r")
        assertNotNull(r.atMs)
        assertNotNull(r.tempC)
        Log.i("KinWeathercloudTest", "info: ${source.info(http)}")
        Unit
    }
}
