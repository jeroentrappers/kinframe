package app.kinframe.core

import app.kinframe.core.api.AccessTokenSource
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.Discovery
import app.kinframe.core.api.EmailAddressCreate
import app.kinframe.core.api.EmailAddressUpdate
import app.kinframe.core.api.EmailHeldReason
import app.kinframe.core.api.EmailOutcome
import app.kinframe.core.api.EmailSenderApprove
import app.kinframe.core.api.EmailSenderCreate
import app.kinframe.core.api.EmailSenderState
import app.kinframe.core.api.EmailSenderUpdate
import app.kinframe.core.api.HttpKinApi
import app.kinframe.core.api.Item
import app.kinframe.core.api.JoinRequest
import app.kinframe.core.api.JoinVia
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.PhotoService
import app.kinframe.core.api.ProblemCode
import app.kinframe.core.api.TelegramChatKind
import app.kinframe.core.api.TelegramChatStatus
import app.kinframe.core.api.TelegramChatUpdate
import app.kinframe.core.api.TelegramCodeStatus
import app.kinframe.core.api.TelegramConnectCodeCreate
import app.kinframe.core.api.TelegramContributorsMode
import app.kinframe.core.api.TelegramLinks
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** Email to frame (README §5.16) and Telegram (README §5.17): decoding and requests. */
class ChannelsApiTest {
    private val server = MockWebServer()
    private val tokens = object : AccessTokenSource {
        override suspend fun accessToken(forceRefresh: Boolean) = "t"
    }
    private lateinit var api: HttpKinApi

    @Before
    fun setUp() {
        server.start()
        api = HttpKinApi(OkHttpClient(), server.url("/v1"), tokens)
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body)
        .addHeader("Content-Type", if (code >= 400) "application/problem+json" else "application/json").build()

    private fun noContent() = MockResponse.Builder().code(204).build()

    private val addressJson = """{"id":"ea1","address":"oma-k7q2m9@in.kinframe.appmire.be","slug":"oma","name":"Oma's frame",
        "target":{"kind":"frame","frame_id":"f1","frame_name":"Oma","feed_id":null,"feed_name":null},
        "owner":{"user_id":"u1","display_name":"Anna","avatar_url":null},"status":"paused","anyone_may_send":false,
        "send_confirmations":true,"offer_app_invite":true,"notify_reactions":false,"replies_available":false,
        "counts":{"allowed_senders":2,"blocked_senders":1,"pending_senders":1,"held_messages":3,"delivered_30d":14},
        "last_received_at":"2026-11-19T09:00:00Z","regenerated_at":null,"can_manage":true,"is_owner":true,
        "created_at":"2026-11-01T10:00:00Z","future":{"x":1}}"""

    private val chatJson = """{"id":"tc1","chat_kind":"group","title":"Peeters family","is_forum":true,
        "target":{"kind":"feed","frame_id":null,"frame_name":null,"feed_id":"fd1","feed_name":"Peeters kids"},
        "owner":{"user_id":"u1","display_name":"Anna"},"status":"needs_attention","error_code":"privacy_mode",
        "warnings":["cannot_post","something_new"],"ended_reason":null,"bot_is_admin":false,"sees_all_messages":false,
        "contributors":"linked_only","frame_replies":false,"notify_reactions":false,"offer_app_invite":true,
        "topic_id":null,"topics":[{"id":1,"name":"General"},{"id":42,"name":"Photos"}],
        "counts":{"sent":120,"duplicates":2,"skipped":1,"failed":0,"contributors":4},"last_delivery_at":null,
        "can_manage":true,"is_owner":false,"created_at":"2026-11-01T10:00:00Z"}"""

    // ---------------------------------------------------------------------------------------------- discovery

    @Test
    fun discoveryAndLimits() {
        val d = KinJson.decodeFromString(Discovery.serializer(), """{"name":"kinframe","email_inbox":true,"email_inbox_domain":"in.kinframe.appmire.be",
            "telegram":true,"telegram_bot_username":"kinframe_bot","telegram_privacy_mode":true,"telegram_max_file_bytes":20971520,
            "limits":{"max_email_addresses_per_target":3,"max_email_senders_per_address":50,"max_telegram_chats_per_target":2}}""")
        assertTrue(d.emailInbox)
        assertEquals("in.kinframe.appmire.be", d.emailInboxDomain)
        assertTrue(d.telegram && d.telegramPrivacyMode)
        assertEquals("kinframe_bot", d.telegramBotUsername)
        assertEquals(20971520L, d.telegramMaxFileBytes)
        assertEquals(3, d.limits!!.maxEmailAddressesPerTarget)
        assertEquals(50, d.limits!!.maxEmailSendersPerAddress)
        assertEquals(2, d.limits!!.maxTelegramChatsPerTarget)
        assertEquals("defaults", 20, d.limits!!.maxTelegramChatsPerUser)
        val old = KinJson.decodeFromString(Discovery.serializer(), """{"name":"kinframe"}""")
        assertFalse(old.emailInbox || old.telegram)
        assertNull(old.telegramBotUsername)
    }

    @Test
    fun itemViaAndJoinRequests() {
        val item = KinJson.decodeFromString(Item.serializer(), """{"id":"i1","kind":"photo","sender_id":"u1","sender_name":"Anna",
            "via":{"service":"email","label":"Jan","address_id":"ea1","source_id":null}}""")
        val via = item.via!!
        assertEquals(PhotoService.EMAIL, via.service)
        assertTrue(via.isChannel)
        assertEquals("Jan", via.personLabel)
        assertEquals("ea1", via.addressId)
        val tg = KinJson.decodeFromString(Item.serializer(), """{"id":"i2","kind":"photo","via":{"service":"telegram","label":"Ben","telegram_chat_id":"tc1"}}""").via!!
        assertEquals("tc1", tg.telegramChatId)
        assertTrue(tg.isChannel)
        val immich = KinJson.decodeFromString(Item.serializer(), """{"id":"i3","kind":"photo","via":{"service":"immich","label":""}}""").via!!
        assertFalse(immich.isChannel)
        assertNull(immich.personLabel)
        assertEquals("Immich", immich.displayLabel)

        val r = KinJson.decodeFromString(JoinRequest.serializer(), """{"id":"jr1","user":{"id":"u9","display_name":"Jan"},"via":"email",
            "via_name":"Jan · jan.peeters@telenet.be","via_matches":true,"created_at":"2026-11-19T09:00:00Z"}""")
        assertTrue(r.isChannel)
        assertEquals("Jan · jan.peeters@telenet.be", r.viaName)
        assertEquals(true, r.viaMatches)
        val t = KinJson.decodeFromString(JoinRequest.serializer(), """{"id":"jr2","via":"telegram","via_name":"Ben · Telegram, Peeters family","via_matches":null}""")
        assertEquals(JoinVia.TELEGRAM, t.via)
        assertNull(t.viaMatches)
        assertFalse(KinJson.decodeFromString(JoinRequest.serializer(), """{"id":"jr3","via":"invite"}""").isChannel)
    }

    // ---------------------------------------------------------------------------------------------- email

    @Test
    fun decodesEmailAddress() = runBlocking {
        server.enqueue(json("[$addressJson]"))
        val a = api.frameEmailAddresses("f1").single()
        assertEquals("/v1/frames/f1/email-addresses", server.takeRequest().url.encodedPath)
        assertEquals("oma-k7q2m9@in.kinframe.appmire.be", a.address)
        assertTrue(a.isPaused)
        assertFalse(a.repliesAvailable)
        assertEquals(14, a.counts.delivered30d)
        assertEquals(1, a.counts.pendingSenders)
        assertEquals("Oma", a.target.name)
        assertFalse(a.target.isFeed)
        assertEquals("Anna", a.owner!!.displayName)
    }

    @Test
    fun emailAddressRequests() = runBlocking {
        server.enqueue(json(addressJson, 201))
        api.createFrameEmailAddress("f1", EmailAddressCreate(slug = "oma"), "key-1")
        val c = server.takeRequest()
        assertEquals("POST", c.method)
        assertEquals("/v1/frames/f1/email-addresses", c.url.encodedPath)
        assertEquals("key-1", c.headers["Idempotency-Key"])
        assertEquals("""{"slug":"oma"}""", c.body!!.utf8())

        server.enqueue(json(addressJson, 201))
        api.createFeedEmailAddress("fd1", EmailAddressCreate(), "key-2")
        val fc = server.takeRequest()
        assertEquals("/v1/feeds/fd1/email-addresses", fc.url.encodedPath)
        assertEquals("{}", fc.body!!.utf8())

        server.enqueue(json(addressJson))
        api.updateEmailAddress("ea1", EmailAddressUpdate(paused = true))
        val p = server.takeRequest()
        assertEquals("PATCH", p.method)
        assertEquals("/v1/email-addresses/ea1", p.url.encodedPath)
        assertEquals("""{"paused":true}""", p.body!!.utf8())

        server.enqueue(json(addressJson))
        api.regenerateEmailAddress("ea1")
        val g = server.takeRequest()
        assertEquals("POST", g.method)
        assertEquals("/v1/email-addresses/ea1/regenerate", g.url.encodedPath)

        server.enqueue(noContent())
        api.deleteEmailAddress("ea1")
        assertEquals("DELETE", server.takeRequest().method)

        server.enqueue(json("[$addressJson]"))
        api.feedEmailAddresses("fd1")
        assertEquals("/v1/feeds/fd1/email-addresses", server.takeRequest().url.encodedPath)
    }

    @Test
    fun sendersPendingAndHeldMail() = runBlocking {
        server.enqueue(json("""[{"id":"s1","email":"jan.peeters@telenet.be","display_name":"Jan","state":"blocked","origin":"manual","added_by_name":"Anna","created_at":"2026-11-01T10:00:00Z"}]"""))
        val s = api.emailSenders("ea1").single()
        assertEquals(EmailSenderState.BLOCKED, s.state)
        assertTrue(s.isBlocked)
        server.takeRequest()

        server.enqueue(json("""{"id":"s2","email":"rita@gmail.com","state":"allowed","origin":"manual","created_at":"2026-11-01T10:00:00Z"}""", 201))
        api.addEmailSender("ea1", EmailSenderCreate(" rita@gmail.com ", " Rita "))
        val add = server.takeRequest()
        assertEquals("/v1/email-addresses/ea1/senders", add.url.encodedPath)
        assertEquals("""{"email":"rita@gmail.com","display_name":"Rita","blocked":false}""", add.body!!.utf8())

        server.enqueue(json("""{"id":"s2","email":"rita@gmail.com","state":"allowed","origin":"manual"}"""))
        api.updateEmailSender("ea1", "s2", EmailSenderUpdate(clearName = true, blocked = false))
        val up = server.takeRequest()
        assertEquals("PATCH", up.method)
        assertEquals("""{"display_name":null,"blocked":false}""", up.body!!.utf8())

        server.enqueue(noContent())
        api.removeEmailSender("ea1", "s2")
        assertEquals("/v1/email-addresses/ea1/senders/s2", server.takeRequest().url.encodedPath)

        server.enqueue(json("""[{"id":"p1","email":"rita@gmail.com","display_name":"Rita","authenticated":true,"first_held_at":"2026-11-18T10:42:00Z",
            "last_held_at":"2026-11-19T10:42:00Z","messages":[{"id":"m1","reason":"unknown_sender","subject":"Garden party","received_at":"2026-11-19T10:42:00Z",
            "expires_at":"2026-12-03T10:42:00Z","media_count":3,"authenticated":true,"thumb_urls":["https://x/1","https://x/2"]},
            {"id":"m2","reason":"unverified_sender","subject":null,"received_at":"2026-11-18T10:42:00Z","expires_at":"2026-12-02T10:42:00Z","media_count":2,"authenticated":false}]}]"""))
        val pending = api.emailPendingSenders("ea1").single()
        assertEquals(5, pending.mediaCount)
        assertEquals(2, pending.messages[0].thumbUrls.size)
        assertFalse(pending.messages[0].isUnverified)
        assertTrue(pending.messages[1].isUnverified)
        server.takeRequest()

        server.enqueue(json("""{"id":"p1","email":"rita@gmail.com","state":"allowed","origin":"approved"}"""))
        api.approveEmailSender("ea1", "p1", EmailSenderApprove(deliver = true))
        val ap = server.takeRequest()
        assertEquals("/v1/email-addresses/ea1/pending-senders/p1/approve", ap.url.encodedPath)
        assertEquals("""{"deliver":true}""", ap.body!!.utf8())

        server.enqueue(noContent())
        api.rejectEmailSender("ea1", "p1", block = true)
        val rj = server.takeRequest()
        assertEquals("/v1/email-addresses/ea1/pending-senders/p1/reject", rj.url.encodedPath)
        assertEquals("""{"block":true}""", rj.body!!.utf8())

        server.enqueue(json("""{"id":"m2","address_id":"ea1","sender_id":"p1","sender_email":"rita@gmail.com","sender_name":"Rita","reason":"unverified_sender",
            "subject":"Hi","caption":"Hi","received_at":"2026-11-18T10:42:00Z","expires_at":"2026-12-02T10:42:00Z",
            "auth":{"authenticated":false,"from_domain":"gmail.com","spf":"fail","dkim":"none","dmarc":"fail","dmarc_policy":"none","aligned":null},
            "media":[{"kind":"photo","width":4032,"height":3024,"thumb_url":"https://x/t"},{"kind":"video","duration_ms":12000,"thumb_url":null}],
            "skipped":{"too_small":2}}"""))
        val held = api.emailHeldMessage("ea1", "m2")
        assertTrue(held.isUnverified)
        assertEquals("fail", held.auth.spf)
        assertEquals(2, held.media.size)
        assertEquals(2, held.skipped!!.total)
        assertEquals("/v1/email-addresses/ea1/held-messages/m2", server.takeRequest().url.encodedPath)

        server.enqueue(json("""{"id":"m2","received_at":"2026-11-18T10:42:00Z","authenticated":false,"outcome":"delivered","items":2,"send_ids":["s9"]}"""))
        val delivered = api.deliverEmailHeldMessage("ea1", "m2")
        assertEquals(EmailOutcome.DELIVERED, delivered.outcome)
        assertEquals("/v1/email-addresses/ea1/held-messages/m2/deliver", server.takeRequest().url.encodedPath)

        server.enqueue(noContent())
        api.deleteEmailHeldMessage("ea1", "m2")
        assertEquals("DELETE", server.takeRequest().method)

        server.enqueue(json("""{"items":[{"id":"m3","received_at":"2026-11-18T18:03:00Z","sender_email":"jan@x.be","sender_name":"Jan","authenticated":true,
            "outcome":"no_media","items":0,"reply":"no_media"}],"next_cursor":"c2"}"""))
        val page = api.emailMessages("ea1", cursor = "c1", limit = 20)
        assertEquals("c2", page.nextCursor)
        assertEquals("no_media", page.items.single().reply)
        val mr = server.takeRequest()
        assertEquals("c1", mr.url.queryParameter("cursor"))
        assertEquals("20", mr.url.queryParameter("limit"))
        assertEquals(EmailHeldReason.UNKNOWN_SENDER, pending.messages[0].reason)
    }

    @Test
    fun duplicateSenderProblem() = runBlocking {
        server.enqueue(json("""{"status":409,"code":"duplicate_sender","title":"Already listed","detail":"s1"}""", 409))
        try {
            api.addEmailSender("ea1", EmailSenderCreate("jan@x.be"))
            fail()
        } catch (e: ApiException) {
            assertEquals(ProblemCode.DUPLICATE_SENDER, e.code)
        }
    }

    // ---------------------------------------------------------------------------------------------- telegram

    @Test
    fun decodesTelegramChat() = runBlocking {
        server.enqueue(json("[$chatJson]"))
        val c = api.feedTelegramChats("fd1").single()
        assertEquals("/v1/feeds/fd1/telegram-chats", server.takeRequest().url.encodedPath)
        assertEquals(TelegramChatStatus.NEEDS_ATTENTION, c.status)
        assertEquals("privacy_mode", c.errorCode)
        assertFalse(c.seesAllMessages)
        assertEquals(listOf("cannot_post", "something_new"), c.warnings)
        assertEquals(TelegramContributorsMode.LINKED_ONLY, c.contributors)
        assertNull(c.topicId)
        assertEquals(42L, c.topics[1].id)
        assertTrue(c.target.isFeed)
        assertTrue(c.isLive && c.isGroup)
        assertEquals(4, c.counts.contributors)
    }

    @Test
    fun telegramRequests() = runBlocking {
        server.enqueue(json("""{"id":"cd1","purpose":"connect","chat_kind":"group","link_account":false,"code":"cA7f","url":"https://t.me/kinframe_bot?startgroup=cA7f",
            "status":"open","expires_at":"2026-11-19T10:30:00Z","chat_id":null,"error_code":null,
            "target":{"kind":"frame","frame_id":"f1","frame_name":"Oma"}}""", 201))
        val code = api.createFrameTelegramCode("f1", TelegramConnectCodeCreate(TelegramChatKind.GROUP), "k1")
        val r = server.takeRequest()
        assertEquals("/v1/frames/f1/telegram-codes", r.url.encodedPath)
        assertEquals("k1", r.headers["Idempotency-Key"])
        assertEquals("""{"chat_kind":"group","link_account":false}""", r.body!!.utf8())
        assertEquals("https://t.me/kinframe_bot?startgroup=cA7f", code.url)
        assertTrue(code.isOpen)

        server.enqueue(json("""{"id":"cd2","purpose":"connect","chat_kind":"private","link_account":true,"status":"open","expires_at":"2026-11-19T10:30:00Z"}""", 201))
        api.createFeedTelegramCode("fd1", TelegramConnectCodeCreate(TelegramChatKind.PRIVATE, linkAccount = true), "k2")
        val fr = server.takeRequest()
        assertEquals("/v1/feeds/fd1/telegram-codes", fr.url.encodedPath)
        assertEquals("""{"chat_kind":"private","link_account":true}""", fr.body!!.utf8())

        server.enqueue(json("""{"id":"cd1","purpose":"connect","status":"used","chat_id":"tc1","expires_at":"2026-11-19T10:30:00Z"}"""))
        val used = api.telegramCode("cd1")
        assertEquals(TelegramCodeStatus.USED, used.status)
        assertEquals("tc1", used.chatId)
        assertNull("only in the create response", used.url)
        assertEquals("/v1/telegram-codes/cd1", server.takeRequest().url.encodedPath)

        server.enqueue(noContent())
        api.revokeTelegramCode("cd1")
        assertEquals("DELETE", server.takeRequest().method)

        server.enqueue(json(chatJson))
        api.updateTelegramChat("tc1", TelegramChatUpdate(contributors = TelegramContributorsMode.EVERYONE, allTopics = true))
        val up = server.takeRequest()
        assertEquals("PATCH", up.method)
        assertEquals("""{"contributors":"everyone","topic_id":null}""", up.body!!.utf8())
        server.enqueue(json(chatJson))
        api.updateTelegramChat("tc1", TelegramChatUpdate(topicId = 42, takeOver = true))
        assertEquals("""{"topic_id":42,"take_over":true}""", server.takeRequest().body!!.utf8())
        assertTrue(TelegramChatUpdate().isEmpty)

        server.enqueue(noContent())
        api.disconnectTelegramChat("tc1")
        val d = server.takeRequest()
        assertEquals("DELETE", d.method)
        assertEquals("/v1/telegram-chats/tc1", d.url.encodedPath)

        server.enqueue(json("""[{"id":"ct1","display_name":"Cousin","linked":false,"blocked":false,"sent_count":30,"last_seen_at":"2026-11-19T09:00:00Z"}]"""))
        assertEquals(30, api.telegramContributors("tc1").single().sentCount)
        server.takeRequest()
        server.enqueue(json("""{"id":"ct1","display_name":"Cousin","linked":false,"blocked":true,"sent_count":30,"last_seen_at":"2026-11-19T09:00:00Z"}"""))
        assertTrue(api.updateTelegramContributor("tc1", "ct1", true).blocked)
        val cb = server.takeRequest()
        assertEquals("/v1/telegram-chats/tc1/contributors/ct1", cb.url.encodedPath)
        assertEquals("""{"blocked":true}""", cb.body!!.utf8())
    }

    @Test
    fun linkedAccount() = runBlocking {
        server.enqueue(json("""{"status":404,"code":"not_found"}""", 404))
        assertNull(api.telegramLink())
        assertEquals("/v1/me/telegram", server.takeRequest().url.encodedPath)
        server.enqueue(json("""{"display_name":"Anna P","username":"annap","can_message":false,"linked_at":"2026-11-19T09:00:00Z"}"""))
        val l = api.telegramLink()!!
        assertEquals("annap", l.username)
        assertFalse(l.canMessage)
        server.takeRequest()
        server.enqueue(json("""{"id":"lc1","purpose":"link","status":"open","url":"https://t.me/kinframe_bot?start=xyz","expires_at":"2026-11-19T10:30:00Z"}""", 201))
        assertEquals("https://t.me/kinframe_bot?start=xyz", api.createTelegramLinkCode("k3").url)
        val lc = server.takeRequest()
        assertEquals("POST", lc.method)
        assertEquals("/v1/me/telegram/link-code", lc.url.encodedPath)
        server.enqueue(noContent())
        api.unlinkTelegram()
        assertEquals("DELETE", server.takeRequest().method)
        server.enqueue(json("""{"status":409,"code":"telegram_already_linked"}""", 409))
        try { api.createTelegramLinkCode("k4"); fail() } catch (e: ApiException) { assertEquals(ProblemCode.TELEGRAM_ALREADY_LINKED, e.code) }
    }

    @Test
    fun deepLinks() {
        assertEquals("tg://resolve?domain=kinframe_bot&startgroup=cA7f", TelegramLinks.appUri("https://t.me/kinframe_bot?startgroup=cA7f"))
        assertEquals("tg://resolve?domain=kinframe_bot&start=xyz", TelegramLinks.appUri("https://t.me/kinframe_bot?start=xyz"))
        assertNull(TelegramLinks.appUri("https://example.org/kinframe_bot?start=xyz"))
        assertNull(TelegramLinks.appUri("not a link"))
        assertEquals("https://t.me/kinframe_bot", TelegramLinks.bot("@kinframe_bot"))
        assertNull(TelegramLinks.bot(" "))
    }
}
