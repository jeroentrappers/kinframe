package app.kinframe.family

import app.kinframe.core.api.Item
import app.kinframe.core.api.Send
import app.kinframe.core.api.SendFrame
import app.kinframe.core.api.SendPlacement
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.history.Delivery
import app.kinframe.family.history.Stage
import app.kinframe.family.notify.PushMessage
import app.kinframe.family.send.Framing
import app.kinframe.family.send.TrimHandle
import app.kinframe.family.ui.frame.SettingsMerge
import app.kinframe.family.util.DeepLink
import app.kinframe.family.util.DeepLinks
import app.kinframe.family.util.FrameCodes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCodesTest {
    @Test fun keepsDigitsOnly() {
        assertEquals("12345678", FrameCodes.digits("1234 5678"))
        assertEquals("12345678", FrameCodes.digits(" 1234-5678 "))
        assertEquals("12345678", FrameCodes.digits("123456789"))
        assertEquals("", FrameCodes.digits("abc"))
    }

    @Test fun pastedJoinLinkYieldsItsCode() {
        assertEquals("48213907", FrameCodes.digits("https://kinframe.appmire.be/f/48213907"))
        assertEquals("48213907", FrameCodes.normalize("Scan: https://x.org/f/48213907".substringAfter(' ')))
    }

    @Test fun formatsAsTwoGroups() {
        assertEquals("", FrameCodes.format(""))
        assertEquals("1234", FrameCodes.format("1234"))
        assertEquals("1234 5", FrameCodes.format("12345"))
        assertEquals("1234 5678", FrameCodes.format("12345678"))
    }

    @Test fun normalizeNeedsEightDigits() {
        assertNull(FrameCodes.normalize("1234"))
        assertEquals("12345678", FrameCodes.normalize("1234 5678"))
    }

    @Test fun offsetMappingSkipsTheSpace() {
        val m = FrameCodes.CodeOffsetMapping
        assertEquals(4, m.originalToTransformed(4))
        assertEquals(6, m.originalToTransformed(5))
        assertEquals(9, m.originalToTransformed(8))
        assertEquals(4, m.transformedToOriginal(5).coerceAtMost(4))
        assertEquals(8, m.transformedToOriginal(9))
    }

    @Test fun spokenForScreenReaders() = assertEquals("1 2 3 4, 5 6 7 8", FrameCodes.spoken("12345678"))
}

class DeepLinksTest {
    @Test fun frameCodeLink() {
        val l = DeepLinks.parse("https://kinframe.appmire.be/f/48213907") as DeepLink.FrameCode
        assertEquals("48213907", l.code)
        assertEquals("https://kinframe.appmire.be/", l.server.toString())
    }

    @Test fun inviteLinkBelowAPathPrefix() {
        val token = "AbCdEfGhIjKlMnOpQrStUv_-12"
        val l = DeepLinks.parse("https://example.org/kinframe/i/$token?utm=x") as DeepLink.Invite
        assertEquals(token, l.token)
        assertEquals("https://example.org/kinframe/", l.server.toString())
    }

    @Test fun rejectsOtherLinks() {
        assertNull(DeepLinks.parse("https://kinframe.appmire.be/frames"))
        assertNull(DeepLinks.parse("https://kinframe.appmire.be/f/123"))
        assertNull(DeepLinks.parse("https://kinframe.appmire.be/i/short"))
        assertNull(DeepLinks.parse("not a url"))
        assertNull(DeepLinks.parse(null))
    }

    @Test fun sameServerIgnoresTrailingSlashAndCase() {
        assertTrue(DeepLinks.sameServer("https://Kinframe.appmire.be/".toHttpUrl(), "https://kinframe.appmire.be".toHttpUrl()))
        assertFalse(DeepLinks.sameServer("https://a.org/".toHttpUrl(), "https://b.org/".toHttpUrl()))
        assertFalse(DeepLinks.sameServer("https://a.org/".toHttpUrl(), "http://a.org/".toHttpUrl()))
    }
}

class DeliveryTest {
    private fun send(vararg placements: Pair<String, String>, itemStatus: String = "ready", reaction: String? = null) = Send(
        id = "s", createdAt = "2026-10-22T10:00:00Z",
        items = listOf(Item("i1", status = itemStatus)),
        frames = listOf(SendFrame("f1", "Oma", placements = placements.map { (id, st) -> SendPlacement(id, "i1", st, reaction) })),
    )

    @Test fun stages() {
        assertEquals(Stage.Processing, Delivery.stageOf("pending"))
        assertEquals(Stage.Processing, Delivery.stageOf("ready", "processing"))
        assertEquals(Stage.OnItsWay, Delivery.stageOf("ready"))
        assertEquals(Stage.Failed, Delivery.stageOf("ready", "failed"))
        assertEquals(Stage.Seen, Delivery.stageOf("seen"))
    }

    @Test fun overallIsTheLeastAdvancedButFailuresWin() {
        assertEquals(Stage.Delivered, Delivery.overall(listOf(Stage.Seen, Stage.Delivered)))
        assertEquals(Stage.Failed, Delivery.overall(listOf(Stage.Seen, Stage.Failed)))
        assertEquals(Stage.Removed, Delivery.overall(listOf(Stage.Removed)))
    }

    @Test fun progress() {
        assertEquals(1f, Delivery.progress(listOf(Stage.Seen, Stage.Seen)), 0.001f)
        assertEquals(0.5f, Delivery.progress(listOf(Stage.Seen, Stage.Processing)), 0.001f)
    }

    @Test fun perFrameDelivery() {
        val d = Delivery.frames(send("p1" to "seen", "p2" to "delivered")).single()
        assertEquals(Stage.Delivered, d.stage)
        assertEquals(1, d.counts[Stage.Seen])
    }

    @Test fun liveReactionAndReply() {
        val s = send("p1" to "seen")
        val r = Delivery.applyReaction(s, "p1", "love")!!
        assertEquals("love", r.frames[0].placements[0].reaction)
        assertEquals(listOf("call_me"), Delivery.applyReply(r, "p1", "call_me")!!.frames[0].placements[0].replies)
        assertNull(Delivery.applyReaction(s, "other", "wow"))
    }

    @Test fun tallyInStableOrder() =
        assertEquals(listOf("love" to 2, "wow" to 1), Delivery.tally(listOf("wow", null, "love", "love")))
}

class FramingTest {
    @Test fun centredFocalNeedsNoShift() {
        val (x, y) = Framing.coverBias(0.5f, 0.5f, 4f / 3f, Framing.LANDSCAPE)
        assertEquals(0f, x, 0.001f); assertEquals(0f, y, 0.001f)
    }

    @Test fun focalAtTheEdgeIsClamped() {
        // A wide photo in a portrait box: only the horizontal axis can move.
        val (x, y) = Framing.coverBias(0f, 0.5f, 16f / 9f, Framing.PORTRAIT)
        assertEquals(-1f, x, 0.001f); assertEquals(0f, y, 0.001f)
        assertEquals(1f, Framing.coverBias(1f, 0.5f, 16f / 9f, Framing.PORTRAIT).first, 0.001f)
    }

    @Test fun clampFocalRoundsAndBounds() {
        assertEquals(0f to 1f, Framing.clampFocal(-0.2f, 1.7f))
        assertEquals(0.333f to 0.5f, Framing.clampFocal(0.33333f, 0.5f))
    }

    @Test fun trimKeepsMinimumLengthByMovingTheOtherHandle() {
        assertEquals(10_000L to 13_000L, Framing.clampTrim(10_000, 11_000, 60_000, 300_000, TrimHandle.Start))
        assertEquals(7_000L to 10_000L, Framing.clampTrim(9_000, 10_000, 60_000, 300_000, TrimHandle.End))
        assertEquals(57_000L to 60_000L, Framing.clampTrim(59_000, 60_000, 60_000, 300_000, TrimHandle.Start))
    }

    @Test fun trimRespectsMaximumLength() {
        assertEquals(0L to 300_000L, Framing.clampTrim(0, 400_000, 600_000, 300_000, TrimHandle.Start))
        assertEquals(100_000L to 400_000L, Framing.clampTrim(0, 400_000, 600_000, 300_000, TrimHandle.End))
    }

    @Test fun defaultTrimOnlyForLongVideos() {
        assertNull(Framing.defaultTrim(10_000, 300_000))
        assertEquals(0L to 300_000L, Framing.defaultTrim(400_000, 300_000))
    }
}

class SettingsMergeTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun diffProducesMergePatch() {
        val base = obj("""{"version":3,"slideshow":{"interval_s":30,"order":"received"},"location":{"lat":1,"lon":2}}""")
        val edit = obj("""{"version":3,"slideshow":{"interval_s":60,"order":"received"}}""")
        assertEquals(obj("""{"slideshow":{"interval_s":60},"location":null}"""), SettingsMerge.diff(base, edit))
    }

    @Test fun applyRemovesNulls() {
        val r = SettingsMerge.apply(obj("""{"a":{"b":1,"c":2}}"""), obj("""{"a":{"b":null,"d":3}}"""))
        assertEquals(obj("""{"a":{"c":2,"d":3}}"""), r)
    }

    @Test fun rebaseRetriesWhenFieldsDontOverlap() {
        val base = obj("""{"version":1,"slideshow":{"interval_s":30},"display":{"brightness":200}}""")
        val latest = obj("""{"version":2,"slideshow":{"interval_s":30},"display":{"brightness":120}}""")
        val ours = obj("""{"slideshow":{"interval_s":60}}""")
        assertEquals(SettingsMerge.Rebase.Retry(ours), SettingsMerge.rebase(base, latest, ours))
    }

    @Test fun rebaseAsksForReviewOnSameField() {
        val base = obj("""{"version":1,"display":{"brightness":200}}""")
        val latest = obj("""{"version":2,"display":{"brightness":120}}""")
        val ours = obj("""{"display":{"brightness":90}}""")
        val r = SettingsMerge.rebase(base, latest, ours) as SettingsMerge.Rebase.Review
        assertEquals(JsonPrimitive(90), r.merged["display"]!!.jsonObject["brightness"])
        assertEquals(JsonPrimitive(2), r.merged["version"])
    }

    @Test fun setCreatesPaths() {
        val r = SettingsMerge.set(JsonObject(emptyMap()), listOf("widgets", "weather"), JsonPrimitive(true))
        assertEquals(obj("""{"widgets":{"weather":true}}"""), r)
        assertEquals(obj("""{"widgets":{}}"""), SettingsMerge.set(r, listOf("widgets", "weather"), null))
        assertTrue(SettingsMerge.overlap(obj("""{"display":{"sleep_schedules":[]}}"""), obj("""{"display":null}""")))
        assertFalse(SettingsMerge.overlap(obj("""{"a":{"b":1}}"""), obj("""{"a":{"c":null}}""")))
    }
}

class EventParsingTest {
    @Test fun parsesUserWebsocketEvents() {
        assertEquals(LiveEvent.FrameStatus("f1", true, "2026-10-22T10:00:00Z"),
            LiveEvent.parse("""{"type":"frame.status","frame_id":"f1","online":true,"last_seen_at":"2026-10-22T10:00:00Z"}"""))
        assertEquals(LiveEvent.Reaction("p", "f", null, "Oma"), LiveEvent.parse("""{"type":"reaction","placement_id":"p","frame_id":"f","reaction":null,"by_frame_name":"Oma"}"""))
        val s = LiveEvent.parse("""{"type":"send.updated","send":{"id":"s1","created_at":"x","items":[],"frames":[],"extra":1}}""") as LiveEvent.SendUpdated
        assertEquals("s1", s.send.id)
        assertNull(LiveEvent.parse("""{"type":"something.new"}"""))
        assertNull(LiveEvent.parse("garbage"))
    }

    @Test fun parsesPushPayload() {
        val m = PushMessage.parse("""{"type":"reaction","title":"Nieuwe reactie","body":"Oma reageerde","ids":{"placement_id":"p","frame_id":"f"}}""".toByteArray())!!
        assertEquals("reaction", m.type)
        assertEquals(mapOf("placement_id" to "p", "frame_id" to "f"), m.ids)
        assertNull(PushMessage.parse("nope".toByteArray()))
    }
}
