package app.kinframe.frame.birthdays

import android.app.Application
import app.kinframe.frame.SyncTestBase
import app.kinframe.frame.data.OutboxEntity
import app.kinframe.frame.sync.Outbox
import app.kinframe.frame.sync.SyncEngine
import app.kinframe.core.api.FrameBirthdays
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BirthdayGreetingTest : SyncTestBase() {
    private val outbox by lazy { Outbox(db) { now } }
    private fun greetings() = fake.requests.filter { it.url.encodedPath.contains("/device/birthdays/") }

    @Test
    fun oneGreetingPerPersonAndDay() = runBlocking {
        assertTrue(outbox.birthdayGreeting("b1", "2027-03-12"))
        assertFalse("a second tap sends nothing", outbox.birthdayGreeting("b1", "2027-03-12"))
        assertTrue("another person", outbox.birthdayGreeting("b2", "2027-03-12"))
        assertEquals(2, db.outbox().ofKind(OutboxEntity.BIRTHDAY_GREETING).size)
        assertEquals(setOf("b1", "b2"), db.birthdayGreetings().observeForDate("2027-03-12").first().toSet())
        assertTrue(db.birthdayGreetings().observeForDate("2027-03-13").first().isEmpty())

        val job = async { outbox.run(api) }
        withTimeout(10_000) { while (db.outbox().ofKind(OutboxEntity.BIRTHDAY_GREETING).isNotEmpty()) delay(50) }
        job.cancel()
        val r = greetings()
        assertEquals(listOf("/v1/device/birthdays/b1/greetings/2027-03-12", "/v1/device/birthdays/b2/greetings/2027-03-12"), r.map { it.url.encodedPath })
        assertTrue(r.all { it.method == "PUT" })
        assertFalse("still sent today", outbox.birthdayGreeting("b1", "2027-03-12"))
    }

    @Test
    fun queuedGreetingsOlderThanTwoDaysAreDropped() = runBlocking {
        outbox.birthdayGreeting("b1", "2027-03-12")
        now += Outbox.GREETING_MAX_AGE_MS + 1 // offline for more than two days
        val job = async { outbox.run(api) }
        withTimeout(10_000) { while (db.outbox().ofKind(OutboxEntity.BIRTHDAY_GREETING).isNotEmpty()) delay(50) }
        job.cancel()
        assertTrue(greetings().isEmpty())
        assertTrue("the button keeps showing Sent", db.birthdayGreetings().has("b1", "2027-03-12"))
    }

    @Test
    fun aGreetingForSomeoneNoLongerOnTheFrameIsDropped() = runBlocking {
        fake.status["/v1/device/birthdays/b9/greetings/2027-03-12"] = 404
        outbox.birthdayGreeting("b9", "2027-03-12")
        val job = async { outbox.run(api) }
        withTimeout(10_000) { while (db.outbox().ofKind(OutboxEntity.BIRTHDAY_GREETING).isNotEmpty()) delay(50) }
        job.cancel()
        assertEquals(1, greetings().size)
    }

    @Test
    fun theBirthdaysChangeIsKeptAndNullDropsIt() = runBlocking {
        val sync = SyncEngine(api, db, media) { now }
        sync.applyPage(listOf(app.kinframe.core.api.KinJson.decodeFromString(app.kinframe.core.api.Change.serializer(),
            """{"seq":1,"type":"birthdays","birthdays":{"people":[{"id":"b1","name":"Oma","day":12,"month":3,"feed_ids":["fd1"],"birthday_ids":["b1"]}]}}""")), 1, initial = false)
        assertEquals("Oma", FrameBirthdays.parse(db.syncState().get()!!.birthdaysJson)!!.people.single().name)
        sync.applyPage(listOf(app.kinframe.core.api.KinJson.decodeFromString(app.kinframe.core.api.Change.serializer(), """{"seq":2,"type":"birthdays","birthdays":null}""")), 2, initial = false)
        assertNull(db.syncState().get()!!.birthdaysJson)
    }
}
