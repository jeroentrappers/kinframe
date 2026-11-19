package app.kinframe.family

import android.net.Uri
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.BirthdayPerson
import app.kinframe.core.api.BirthdayPersonCreate
import app.kinframe.core.api.BirthdayPersonUpdate
import app.kinframe.core.api.BirthdayStatus
import app.kinframe.core.api.Feed
import app.kinframe.core.api.FeedBirthdayGreeting
import app.kinframe.core.api.FeedBirthdayGreetingsPage
import app.kinframe.core.api.FeedInviteAccept
import app.kinframe.core.api.FeedInvitePreview
import app.kinframe.core.api.FeedInviteFrame
import app.kinframe.core.api.FeedInviteResult
import app.kinframe.core.api.FeedRole
import app.kinframe.core.api.Problem
import app.kinframe.core.api.Subscription
import app.kinframe.core.api.SubscriptionFeed
import app.kinframe.core.api.SubscriptionFrame
import app.kinframe.core.api.SubscriptionRequest
import app.kinframe.core.api.SubscriptionUpdate
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.notify.Notifications
import app.kinframe.family.notify.PushMessage
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.feeds.BirthdayDraft
import app.kinframe.family.ui.feeds.BirthdayNotice
import app.kinframe.family.ui.feeds.BirthdayRules
import app.kinframe.family.ui.feeds.FeedBirthdaysViewModel
import app.kinframe.family.ui.feeds.FeedInviteViewModel
import app.kinframe.family.ui.feeds.FrameFeedsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

private val TODAY = LocalDate.of(2026, 11, 19)

private fun person(
    id: String, name: String, day: Int, month: Int, status: String = BirthdayStatus.APPROVED, year: Int? = null, showAge: Boolean = false,
    userId: String? = null, canEdit: Boolean = true, next: String? = null, createdById: String? = null,
) = BirthdayPerson(id, "fd1", status, name, day, month, year, showAge, userId = userId, canEdit = canEdit, nextBirthday = next, createdById = createdById)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BirthdaysTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private open inner class Api(var list: List<BirthdayPerson> = emptyList()) : FakeApi() {
        val created = mutableListOf<BirthdayPersonCreate>()
        val updates = mutableListOf<BirthdayPersonUpdate>()
        var createStatus = BirthdayStatus.APPROVED
        var createError: Exception? = null
        val photos = mutableListOf<String>()
        override suspend fun feedBirthdays(feedId: String, status: String?) = list
        override suspend fun feedBirthdayGreetings(feedId: String, birthdayId: String?, date: String?, cursor: String?, limit: Int) =
            FeedBirthdayGreetingsPage(listOf(FeedBirthdayGreeting("g1", "fd1", "b1", "Oma", "f1", "Opa's frame", null, TODAY.toString(), "2026-11-19T09:14:00Z")))
        override suspend fun createFeedBirthday(feedId: String, request: BirthdayPersonCreate, idempotencyKey: String): BirthdayPerson {
            createError?.let { throw it }
            created += request
            return person("new${created.size}", request.displayName, request.day, request.month, createStatus, request.birthYear, request.showAge, request.userId)
        }
        override suspend fun updateFeedBirthday(feedId: String, birthdayId: String, update: BirthdayPersonUpdate): BirthdayPerson {
            updates += update
            val o = list.first { it.id == birthdayId }
            return o.copy(birthYear = if (update.clearYear) null else update.birthYear ?: o.birthYear, showAge = update.showAge ?: o.showAge)
        }
        override suspend fun putFeedBirthdayPhoto(feedId: String, birthdayId: String, bytes: ByteArray, mime: String): BirthdayPerson {
            photos += birthdayId
            return person(birthdayId, "x", 1, 1, status = createStatus).copy(hasOwnPhoto = true)
        }
        override suspend fun approveFeedBirthday(feedId: String, birthdayId: String) = list.first { it.id == birthdayId }.copy(status = BirthdayStatus.APPROVED)
        override suspend fun removeFeedBirthday(feedId: String, birthdayId: String) {}
    }

    private fun vm(api: Api, live: kotlinx.coroutines.flow.Flow<LiveEvent> = emptyFlow()) =
        FeedBirthdaysViewModel(api, "fd1", live, readPhoto = { byteArrayOf(1) }, today = { TODAY }, newKey = { "k" })

    @Test fun listIsSortedByNextBirthdayAndGreetingsAreToday() = runTest {
        val vm = vm(Api(listOf(person("b2", "Lotte", 2, 6), person("b1", "Oma", 19, 11), person("b3", "Opa", 1, 1))))
        assertEquals(listOf("Oma", "Opa", "Lotte"), vm.state.value.approved.map { it.displayName })
        assertEquals(0, BirthdayRules.daysUntil(vm.state.value.approved[0], TODAY))
        assertEquals("Opa's frame", vm.state.value.greetingsFor(vm.state.value.approved[0]).single().frameName)
        // 29 February: next is 28 February in a non-leap year.
        assertEquals(LocalDate.of(2027, 2, 28), BirthdayRules.next(person("b4", "Leo", 29, 2), TODAY))
    }

    @Test fun adminsAddPostersSuggest() = runTest {
        val admin = Feed("fd1", "P", role = FeedRole.ADMIN)
        val poster = Feed("fd1", "P", role = FeedRole.POSTER)
        assertTrue(BirthdayRules.addsDirectly(admin))
        assertFalse(BirthdayRules.addsDirectly(poster))
        assertTrue("with posters_can_add_birthdays", BirthdayRules.addsDirectly(poster.copy(postersCanAddBirthdays = true)))

        val api = Api()
        val v = vm(api)
        val notices = mutableListOf<BirthdayNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { v.events.collect { notices += it } }
        v.save(BirthdayDraft(name = "Oma ", day = 12, month = 3, year = 1946, showAge = true))
        assertEquals(BirthdayPersonCreate("Oma", 12, 3, 1946, true, null), api.created.single())
        assertEquals(R.string.birthday_added, (notices.last() as BirthdayNotice.Message).message)

        api.createStatus = BirthdayStatus.PENDING
        var done = false
        v.save(BirthdayDraft(name = "Lotte", day = 2, month = 6, photo = Uri.parse("content://x/1")), onDone = { done = true })
        assertTrue(done)
        assertEquals(R.string.birthday_suggested, (notices.last() as BirthdayNotice.Message).message)
        assertEquals(listOf("new2"), api.photos)
        assertEquals(1, v.state.value.pending.size)
    }

    @Test fun showAgeNeedsAYearAndInvalidDatesAreNotSent() = runTest {
        val api = Api()
        val v = vm(api)
        v.save(BirthdayDraft(name = "Ann", day = 12, month = 3, showAge = true))
        assertFalse("no year: show_age is off", api.created.single().showAge)
        assertEquals(R.string.birthday_error_date, BirthdayDraft(name = "A", day = 30, month = 2).problem(TODAY))
        assertEquals(R.string.birthday_error_leap, BirthdayDraft(name = "A", day = 29, month = 2, year = 2025).problem(TODAY))
        assertEquals(R.string.birthday_error_year, BirthdayDraft(name = "A", day = 1, month = 1, year = 2027).problem(TODAY))
        assertNull(BirthdayDraft(name = "A", day = 29, month = 2, year = 2024).problem(TODAY))
        v.save(BirthdayDraft(name = "A", day = 31, month = 4))
        assertEquals(1, api.created.size)
    }

    @Test fun yearVisibility() {
        // The server sends the year only where the caller may see it; with show_age off it is private to them.
        val hidden = person("b1", "Oma", 12, 3, year = null)
        val private = person("b1", "Oma", 12, 3, year = 1946, showAge = false)
        val public = person("b1", "Oma", 12, 3, year = 1946, showAge = true)
        assertFalse(BirthdayRules.yearIsPrivate(hidden))
        assertNull(BirthdayRules.turns(hidden, TODAY))
        assertTrue(BirthdayRules.yearIsPrivate(private))
        assertFalse(BirthdayRules.framesShowAge(private))
        assertFalse(BirthdayRules.yearIsPrivate(public))
        assertTrue(BirthdayRules.framesShowAge(public))
        assertEquals(81, BirthdayRules.turns(public, TODAY))
        assertEquals(80, BirthdayRules.turns(public.copy(turns = 80), TODAY))
    }

    @Test fun ownEntryEditsOnlyThePrivacyFields() = runTest {
        val poster = Feed("fd1", "P", role = FeedRole.POSTER)
        val mine = person("b1", "Ben", 4, 5, year = 1990, userId = "me")
        assertTrue(BirthdayRules.ownEntryOnly(mine, poster, "me"))
        assertFalse(BirthdayRules.ownEntryOnly(mine, poster.copy(role = FeedRole.ADMIN), "me"))
        assertFalse("posters with the setting edit what they added", BirthdayRules.ownEntryOnly(mine.copy(createdById = "me"), poster.copy(postersCanAddBirthdays = true), "me"))
        assertTrue(BirthdayRules.canRemove(mine, poster, "me"))
        assertFalse(BirthdayRules.canRemove(person("b2", "Oma", 1, 1), poster, "me"))

        val api = Api(listOf(mine))
        val v = vm(api)
        v.save(BirthdayDraft.of(mine).copy(year = null, showAge = false), original = mine)
        assertEquals("""{"birth_year":null}""", api.updates.single().toJson().toString())
    }

    @Test fun duplicateBirthdayPointsToTheExistingEntry() = runTest {
        val api = Api(listOf(person("b9", "Oma", 12, 3)))
        api.createError = ApiException(409, Problem(status = 409, code = "duplicate_birthday", detail = "b9"))
        val v = vm(api)
        val notices = mutableListOf<BirthdayNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { v.events.collect { notices += it } }
        v.save(BirthdayDraft(name = "oma", day = 12, month = 3))
        assertEquals("b9", v.state.value.duplicateOf?.id)
        val m = notices.single() as BirthdayNotice.Message
        assertEquals(R.string.birthday_duplicate, m.message)
        assertEquals(listOf("Oma"), m.args)
        assertFalse(v.state.value.saving)
    }

    @Test fun approveRemoveUndoAndLiveUpdates() = runTest {
        val live = MutableSharedFlow<LiveEvent>()
        val api = Api(listOf(person("b1", "Lotte", 2, 6, status = BirthdayStatus.PENDING)))
        val v = vm(api, live)
        val notices = mutableListOf<BirthdayNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { v.events.collect { notices += it } }
        v.approve(v.state.value.pending.single())
        assertEquals(listOf("Lotte"), v.state.value.approved.map { it.displayName })
        v.remove(v.state.value.approved.single())
        assertTrue(v.state.value.approved.isEmpty())
        val removed = notices.filterIsInstance<BirthdayNotice.Removed>().single()
        v.undoRemove(removed.person)
        assertEquals("Lotte", api.created.single().displayName)

        live.emit(LiveEvent.BirthdayUpdated("fd1", person("b7", "Opa", 1, 1)))
        live.emit(LiveEvent.BirthdayUpdated("other", person("b8", "X", 1, 1)))
        assertTrue(v.state.value.approved.any { it.id == "b7" })
        assertFalse(v.state.value.approved.any { it.id == "b8" })
        live.emit(LiveEvent.BirthdayRemoved("fd1", "b7"))
        assertFalse(v.state.value.approved.any { it.id == "b7" })
    }

    @Test fun websocketEventsParse() {
        val e = LiveEvent.parse("""{"type":"birthday.greeting","feed_id":"fd1","greeting":{"id":"g1","feed_id":"fd1","birthday_id":"b1",
            "birthday_name":"Oma","frame_id":"f1","frame_name":"Opa's frame","date":"2026-11-19","created_at":"2026-11-19T09:14:00Z"}}""")
        assertEquals("Opa's frame", (e as LiveEvent.BirthdayGreeting).greeting.frameName)
        val t = LiveEvent.parse("""{"type":"birthday.today","date":"2026-11-19","birthdays":[{"feed_id":"fd1","birthday_id":"b1"}]}""")
        assertEquals(listOf("fd1" to "b1"), (t as LiveEvent.BirthdayToday).birthdays)
        assertEquals("b1", (LiveEvent.parse("""{"type":"birthday.removed","feed_id":"fd1","birthday_id":"b1"}""") as LiveEvent.BirthdayRemoved).birthdayId)
        assertTrue(LiveEvent.parse("""{"type":"birthday.suggested","feed_id":"fd1","birthday":{"id":"b1","display_name":"L","day":2,"month":6,"status":"pending"}}""") is LiveEvent.BirthdaySuggested)
    }

    @Test fun pushesOpenTheBirthdaysAndGreetingsReplaceEachOther() {
        fun push(key: String, ids: Map<String, String>, tag: String = "") = PushMessage("push", "", "", ids, key, tag)
        for (k in listOf("birthday_suggestion", "birthday_decided", "birthday_today", "birthday_greeting")) {
            assertEquals(Notifications.TARGET_FEED_BIRTHDAYS, Notifications.targetOf(push(k, mapOf("feed_id" to "fd1"))))
        }
        assertEquals("birthday:b1:2026-11-19", Notifications.tagOf(push("birthday_greeting", mapOf("birthday_id" to "b1", "date" to "2026-11-19"))))
        assertEquals("birthday:b1:2026-11-20", Notifications.tagOf(push("birthday_greeting", mapOf("birthday_id" to "b1")), LocalDate.of(2026, 11, 20)))
        assertEquals("birthday:b9:2026-11-19", Notifications.tagOf(push("birthday_greeting", mapOf("birthday_id" to "b1"), tag = "birthday:b9:2026-11-19")))
        assertNull(Notifications.tagOf(push("birthday_today", mapOf("birthday_id" to "b1"))))
        assertEquals("birthday:b1:2026-11-19", PushMessage.parse("""{"type":"birthday","key":"birthday_greeting","title":"t","body":"b","ids":{"birthday_id":"b1"},"tag":"birthday:b1:2026-11-19"}""".toByteArray())!!.tag)
    }

    @Test fun showBirthdaysPerSubscriptionAndWhenSubscribing() = runTest {
        val sub = Subscription("s1", "active", feed = SubscriptionFeed("fd1", "P"), frame = SubscriptionFrame("f1", "Oma"), canManage = true)
        val updates = mutableListOf<SubscriptionUpdate>()
        val requests = mutableListOf<SubscriptionRequest>()
        val api = object : FakeApi() {
            override suspend fun frameSubscriptions(frameId: String, status: String?) = listOf(sub)
            override suspend fun feeds() = listOf(Feed("fd2", "Q", role = FeedRole.ADMIN))
            override suspend fun settings(frameId: String) = app.kinframe.core.api.SettingsDocument(kotlinx.serialization.json.buildJsonObject {})
            override suspend fun updateSubscription(subscriptionId: String, update: SubscriptionUpdate): Subscription { updates += update; return sub.copy(birthdays = update.birthdays ?: true) }
            override suspend fun requestSubscription(feedId: String, request: SubscriptionRequest, idempotencyKey: String): Subscription { requests += request; return sub }
        }
        val v = FrameFeedsViewModel(api, "f1", manage = true, live = emptyFlow())
        v.setBirthdays(sub, false)
        assertEquals(SubscriptionUpdate(birthdays = false), updates.single())
        v.subscribe(Feed("fd2", "Q"), "none", null, birthdays = false)
        assertFalse(requests.single().birthdays)

        val accepts = mutableListOf<FeedInviteAccept?>()
        val inviteApi = object : FakeApi() {
            override suspend fun previewFeedInvite(token: String) = FeedInvitePreview("t", "subscribe", "P", frames = listOf(FeedInviteFrame("f1", "Oma")))
            override suspend fun acceptFeedInvite(token: String, accept: FeedInviteAccept?, idempotencyKey: String): FeedInviteResult {
                accepts += accept; return FeedInviteResult("subscribed", Feed("fd1", "P"))
            }
        }
        val iv = FeedInviteViewModel(inviteApi, "t", birthdays = true)
        iv.setBirthdays(false)
        iv.accept()
        assertEquals(false, accepts.single()!!.birthdays)
        val iv2 = FeedInviteViewModel(inviteApi, "t", birthdays = true)
        iv2.accept()
        assertNull("default on is not sent", accepts.last()!!.birthdays)
    }
}
