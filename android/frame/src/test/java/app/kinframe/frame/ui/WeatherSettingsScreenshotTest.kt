package app.kinframe.frame.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.frame.R
import app.kinframe.frame.Screenshots
import app.kinframe.frame.weather.WeatherSwitches
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings → Weather: the forecast and the weather station side by side (both on; no location yet). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class WeatherSettingsScreenshotTest {
    @Test fun weatherGroup() = Screenshots.shot("settings_weather") {
        Column(
            Modifier.fillMaxSize().background(Kin.colors.paper).padding(horizontal = 88.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SectionCard(stringResource(R.string.wx_section)) {
                WeatherSwitches(forecast = true, hasPlace = true, station = true, onForecast = {}, onStation = {})
            }
            SectionCard(stringResource(R.string.wx_section)) {
                WeatherSwitches(forecast = false, hasPlace = false, station = false, onForecast = {}, onStation = {})
            }
        }
    }
}
