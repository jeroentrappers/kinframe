package app.kinframe.frame.social

import app.kinframe.core.api.FramePhotoRequest
import app.kinframe.core.api.FramePrompt
import app.kinframe.core.api.Item
import app.kinframe.core.api.ItemVia
import app.kinframe.core.api.Placement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class SocialLogicTest {
    private val t0 = Instant.parse("2026-11-19T10:00:00Z").toEpochMilli()
    private val hour = 3_600_000L

    private fun request(state: String = "open", by: String? = null) = FramePhotoRequest(
        id = "r1", state = state, createdAt = "2026-11-19T10:00:00Z", nextAllowedAt = "2026-11-20T10:00:00Z", answeredByName = by,
    )

    @Test
    fun askedTodayUntilTheServerAllowsTheNextRequest() {
        assertEquals(AskState.Ready, SocialLogic.askState(null, null, t0))
        assertEquals(AskState.Asked, SocialLogic.askState(request(), null, t0 + hour))
        assertEquals(AskState.Ready, SocialLogic.askState(request(), null, t0 + 24 * hour))
        assertEquals(AskState.Answered("Ben"), SocialLogic.askState(request("answered", "Ben"), null, t0 + 2 * hour))
        // Expired requests no longer block the button once the day is over.
        assertEquals(AskState.Ready, SocialLogic.askState(request("expired"), null, t0 + 30 * hour))
    }

    @Test
    fun aTapStillInTheOutboxCountsForADay() {
        assertEquals(AskState.Asked, SocialLogic.askState(null, t0, t0 + 23 * hour))
        assertEquals(AskState.Ready, SocialLogic.askState(null, t0, t0 + 24 * hour))
        // The later of the two wins.
        assertEquals(AskState.Asked, SocialLogic.askState(request(), t0 + 10 * hour, t0 + 30 * hour))
    }

    @Test
    fun namesAreKeptOnceAndInOrder() {
        var json = SocialLogic.addNames(null, listOf("Ann", "Ben", "ann"))
        assertEquals(listOf("Ann", "Ben"), SocialLogic.names(json))
        json = SocialLogic.addNames(json, listOf("Oma"), first = true)
        assertEquals(listOf("Oma", "Ann", "Ben"), SocialLogic.names(json))
        json = SocialLogic.addNames(json, (1..10).map { "P$it" })
        assertEquals(SocialLogic.MAX_NAMES, SocialLogic.names(json).size)
        // Names announced are removed; one that arrived during the note stays for the next wake.
        val shown = listOf("Oma", "Ann")
        assertEquals(listOf("Ben", "Jan"), SocialLogic.names(SocialLogic.removeNames(SocialLogic.addNames(SocialLogic.addNames(null, shown + "Ben"), listOf("Jan")), shown)))
        assertNull(SocialLogic.removeNames(SocialLogic.addNames(null, listOf("Ann")), listOf("Ann")))
        assertEquals(emptyList<String>(), SocialLogic.names("not json"))
    }

    private fun placement(origin: String = "send", sender: String? = "Ann", via: ItemVia? = null, imported: String? = null) =
        Placement(id = "p", item = Item(id = "i", kind = "photo", senderName = sender, via = via, importedFrom = imported), origin = origin)

    @Test
    fun onlyPeoplesOwnPhotosNameSomeone() {
        assertEquals("Ann", SocialLogic.senderName(placement()))
        assertEquals("Ann", SocialLogic.senderName(placement(origin = "feed")))
        assertEquals("Jan", SocialLogic.senderName(placement(via = ItemVia(service = "telegram", label = "Jan"))))
        assertNull(SocialLogic.senderName(placement(via = ItemVia(service = "immich", label = "Immich"))))
        assertNull(SocialLogic.senderName(placement(origin = "share")))
        assertNull(SocialLogic.senderName(placement(origin = "import")))
        assertNull(SocialLogic.senderName(placement(imported = "frameo")))
        assertNull(SocialLogic.senderName(placement(sender = "  ")))
    }

    @Test
    fun aRunOfAnswersGetsOneTitleCard() {
        val prompts = mapOf("q1" to FramePrompt(promptId = "q1", feedName = "Peeters", title = "Your view right now"))
        assertEquals("Your view right now", SocialLogic.promptCard(true, "q1", null, prompts)?.title)
        assertNull(SocialLogic.promptCard(true, "q1", "q1", prompts))
        assertEquals("Your view right now", SocialLogic.promptCard(true, "q1", "q0", prompts)?.title)
        assertNull("off", SocialLogic.promptCard(false, "q1", null, prompts))
        assertNull("not a current prompt", SocialLogic.promptCard(true, "q9", null, prompts))
        assertNull("not an answer", SocialLogic.promptCard(true, null, null, prompts))
    }
}
