package app.kinframe.frame.birthdays

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.FrameBirthday
import app.kinframe.frame.FakePhoto
import app.kinframe.frame.Paper
import app.kinframe.frame.Screenshots
import app.kinframe.frame.calendar.CountdownColumn
import app.kinframe.frame.calendar.CountdownItem
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * The birthday card in the countdown column over a photo and on paper, the several-people card and the greeting
 * sheet, to `frame/build/screenshots/birthdays/` for review (records, not goldens).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "w1280dp-h800dp-land-mdpi")
class BirthdayCardScreenshotTest {
    private val today = LocalDate.of(2027, 3, 12)
    private fun occ(name: String, age: Int?, day: LocalDate = today) =
        BirthdayOccurrence(FrameBirthday(name, name, day.dayOfMonth, day.monthValue, age?.let { day.year - it }), day, age)

    private val tiles = listOf(
        BirthdayLogic.tile(BirthdayDay(today.plusDays(3), listOf(occ("Lotte", 1, today.plusDays(3))))),
        CountdownItem("e1", "Summer holiday", today.plusDays(40), null, "teal"),
    )

    @Composable
    private fun column(people: List<BirthdayOccurrence>, onPaper: Boolean) {
        if (onPaper) Paper() else FakePhoto(0)
        Box(Modifier.fillMaxSize().padding(top = 28.dp, end = 40.dp, bottom = 28.dp), contentAlignment = if (onPaper) Alignment.BottomEnd else Alignment.TopEnd) {
            CountdownColumn({ BirthdayCard(people, emptyMap(), onPaper, onClick = {}) }, tiles, today, onPaper, onClick = {})
        }
    }

    @Test fun onePersonOnAPhoto() = Screenshots.shot("birthdays/card_one") { column(listOf(occ("Oma", 80)), onPaper = false) }

    @Test fun withoutAge() = Screenshots.shot("birthdays/card_plain") { column(listOf(occ("Grootmoeder Margaretha", null)), onPaper = false) }

    @Test fun severalPeopleOnPaper() = Screenshots.shot("birthdays/card_many") {
        column(listOf(occ("Oma", 80), occ("Lotte", 1), occ("Ben", null), occ("Carla", null), occ("Daan", 41)), onPaper = true)
    }

    @Test @Config(qualifiers = "nl-w1280dp-h800dp-land-mdpi")
    fun dutch() = Screenshots.shot("birthdays/card_one_nl") { column(listOf(occ("Oma", 80)), onPaper = false) }

    @Test @Config(qualifiers = "ar-w1280dp-h800dp-land-mdpi")
    fun arabic() = Screenshots.shot("birthdays/card_one_ar") { column(listOf(occ("Oma", 80)), onPaper = false) }

    @Test fun greetingSheet() = Screenshots.shot("birthdays/greeting_sheet") {
        FakePhoto(2)
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Box(Modifier.width(560.dp)) {
                GreetingSheetContent(listOf(occ("Oma", 80), occ("Lotte", 1)), sent = setOf("Lotte"), photos = emptyMap(), onGreet = {}, onClose = {})
            }
        }
    }

    @Test fun balloons() = Screenshots.shot("birthdays/balloons") {
        FakePhoto(3)
        BalloonsLayer(seed = 7, previewProgress = 0.45f) {}
    }
}
