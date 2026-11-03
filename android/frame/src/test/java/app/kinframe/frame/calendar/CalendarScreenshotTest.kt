package app.kinframe.frame.calendar

import android.app.Application
import app.kinframe.core.api.CalendarSettings
import app.kinframe.frame.Screenshots
import app.kinframe.frame.weather.WeatherFixtures
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The full-screen calendar in its three views, landscape and portrait (frame-sized mdpi screens), rendered to
 * `frame/build/screenshots/calendar_*.png` for design review.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class CalendarScreenshotTest {
    private val data = CalendarFixtures.data()
    private val weather = WeatherFixtures.gent()

    private fun screen(view: CalendarView, settings: CalendarSettings = CalendarSettings(), first: String = "mon") = Screenshots.shot("calendar_${view.key}_${orientation}${suffix}") {
        CalendarScreen(
            data, weather, CalendarFixtures.nowMs, CalendarFixtures.zone, settings.copy(view = view.key), first, "c", h24 = true, syncing = false,
            onEvent = {}, onSync = {}, onClose = {},
        )
    }

    private var orientation = "land"
    private var suffix = ""

    @Test fun monthLandscape() = screen(CalendarView.Month)
    @Test fun weekLandscape() = screen(CalendarView.Week)
    @Test fun fourDayLandscape() = screen(CalendarView.FourDay)

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun monthPortrait() { orientation = "port"; screen(CalendarView.Month) }

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun weekPortrait() { orientation = "port"; screen(CalendarView.Week) }

    @Test @Config(qualifiers = "w800dp-h1280dp-port-mdpi")
    fun fourDayPortrait() { orientation = "port"; screen(CalendarView.FourDay) }

    @Test @Config(qualifiers = "nl-w1280dp-h800dp-land-mdpi")
    fun weekDutchSundayWeekNumbers() {
        suffix = "_nl_sun"
        screen(CalendarView.Week, CalendarSettings(startOnToday = false, weekNumbers = true), first = "sun")
    }

    @Test @Config(qualifiers = "nl-w1280dp-h800dp-land-mdpi")
    fun monthDutchWeekNumbers() {
        suffix = "_nl"
        screen(CalendarView.Month, CalendarSettings(weekNumbers = true))
    }

    @Test fun emptyLandscape() = Screenshots.shot("calendar_empty_land") {
        CalendarScreen(
            null, weather, CalendarFixtures.nowMs, CalendarFixtures.zone, CalendarSettings(), "mon", "c", h24 = true, syncing = false,
            onEvent = {}, onSync = {}, onClose = {},
        )
    }
}
