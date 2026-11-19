package app.kinframe.family

import app.kinframe.core.api.ApiException
import app.kinframe.core.api.ChannelOwner
import app.kinframe.core.api.ChannelTarget
import app.kinframe.core.api.EmailAddress
import app.kinframe.core.api.EmailAddressCounts
import app.kinframe.core.api.EmailAddressCreate
import app.kinframe.core.api.EmailAddressUpdate
import app.kinframe.core.api.EmailHeldMessage
import app.kinframe.core.api.EmailHeldMessageSummary
import app.kinframe.core.api.EmailHeldReason
import app.kinframe.core.api.EmailMessage
import app.kinframe.core.api.EmailMessagePage
import app.kinframe.core.api.EmailOutcome
import app.kinframe.core.api.EmailPendingSender
import app.kinframe.core.api.EmailSender
import app.kinframe.core.api.EmailSenderApprove
import app.kinframe.core.api.EmailSenderCreate
import app.kinframe.core.api.EmailSenderState
import app.kinframe.core.api.EmailSenderUpdate
import app.kinframe.core.api.ItemVia
import app.kinframe.core.api.JoinRequest
import app.kinframe.core.api.Problem
import app.kinframe.core.api.ProblemCode
import app.kinframe.core.api.TelegramChat
import app.kinframe.core.api.TelegramChatStatus
import app.kinframe.core.api.TelegramChatUpdate
import app.kinframe.core.api.TelegramCode
import app.kinframe.core.api.TelegramCodeStatus
import app.kinframe.core.api.TelegramConnectCodeCreate
import app.kinframe.core.api.TelegramContributor
import app.kinframe.core.api.TelegramLink
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.notify.Notifications
import app.kinframe.family.notify.PushMessage
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.channels.ChannelNotice
import app.kinframe.family.ui.channels.ChannelScope
import app.kinframe.family.ui.channels.ChatTone
import app.kinframe.family.ui.channels.ConnectKind
import app.kinframe.family.ui.channels.EmailAddressViewModel
import app.kinframe.family.ui.channels.EmailInboxViewModel
import app.kinframe.family.ui.channels.EmailLogic
import app.kinframe.family.ui.channels.JoinRequestVia
import app.kinframe.family.ui.channels.TelegramChatViewModel
import app.kinframe.family.ui.channels.TelegramChatsViewModel
import app.kinframe.family.ui.channels.TelegramLinkViewModel
import app.kinframe.family.ui.channels.TelegramLogic
import app.kinframe.family.ui.channels.emailProblem
import app.kinframe.family.ui.sources.SourceCredit
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

private val frameTarget = ChannelTarget(ChannelTarget.KIND_FRAME, frameId = "f1", frameName = "Oma")
private val feedTarget = ChannelTarget(ChannelTarget.KIND_FEED, feedId = "fd1", feedName = "Peeters kids")

private fun address(id: String = "ea1", target: ChannelTarget = frameTarget, paused: Boolean = false, anyone: Boolean = false, pending: Int = 0) = EmailAddress(
    id, "oma-k7q2m9@in.kinframe.appmire.be", "oma", "Oma's frame", target, ChannelOwner("u1", "Anna"),
    status = if (paused) "paused" else "active", anyoneMaySend = anyone, counts = EmailAddressCounts(pendingSenders = pending), canManage = true, isOwner = true,
)

private fun pending(id: String = "p1", authenticated: Boolean = true) = EmailPendingSender(
    id, "rita@gmail.com", "Rita", authenticated,
    messages = listOf(EmailHeldMessageSummary("m1", if (authenticated) EmailHeldReason.UNKNOWN_SENDER else EmailHeldReason.UNVERIFIED_SENDER,
        "Garden party", mediaCount = 3, authenticated = authenticated, thumbUrls = listOf("https://x/1"))),
)

private fun chat(id: String = "tc1", target: ChannelTarget = frameTarget, status: String = TelegramChatStatus.ACTIVE, error: String? = null, sees: Boolean = true) =
    TelegramChat(id, title = "Peeters family", target = target, status = status, errorCode = error, seesAllMessages = sees, canManage = true, isOwner = true)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ChannelsTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    // ---------------------------------------------------------------------------------------------- logic

    @Test fun slugsAndEmails() {
        assertEquals("oma", EmailLogic.slugOf("Oma"))
        assertEquals("oma-s-frame", EmailLogic.slugOf("Oma's frame"))
        assertEquals("cafe-ete", EmailLogic.slugOf("Café Été!"))
        assertEquals(24, EmailLogic.slugOf("a".repeat(40)).length)
        assertEquals("typing keeps a trailing dash; it is trimmed on save", "oma-frame-", EmailLogic.cleanSlug("Oma--Frame!"))
        assertEquals("cafe-ete", EmailLogic.cleanSlug("Café Été"))
        assertTrue(EmailLogic.isValidSlug("oma"))
        assertFalse(EmailLogic.isValidSlug("-oma"))
        assertFalse(EmailLogic.isValidSlug(""))
        assertTrue(EmailLogic.isValidEmail(" jan.peeters@telenet.be "))
        assertFalse(EmailLogic.isValidEmail("jan@"))
        assertFalse(EmailLogic.isValidEmail("jan peeters@x.be"))
    }

    @Test fun shareTextNamesTheApprover() {
        val with = EmailLogic.shareText(address(), "Me", { n, a, o -> "$n|$a|$o" }, { n, a -> "$n|$a" })
        assertEquals("Oma's frame|oma-k7q2m9@in.kinframe.appmire.be|Anna", with)
        val open = EmailLogic.shareText(address(anyone = true), "Me", { n, a, o -> "$n|$a|$o" }, { n, a -> "$n|$a" })
        assertEquals("no approval sentence when anyone may send", "Oma's frame|oma-k7q2m9@in.kinframe.appmire.be", open)
        val noOwner = EmailLogic.shareText(address().copy(owner = null), "Me", { _, _, o -> o }, { _, _ -> "" })
        assertEquals("Me", noOwner)
    }

    @Test fun outcomesAndProblems() {
        assertEquals(R.string.email_outcome_no_media, EmailLogic.outcome(EmailOutcome.NO_MEDIA))
        assertEquals(R.string.email_outcome_failed, EmailLogic.outcome("something_new"))
        assertTrue(EmailLogic.outcomeHasSender(EmailOutcome.HELD))
        assertFalse(EmailLogic.outcomeHasSender(EmailOutcome.BLOCKED))
        assertEquals(R.string.email_problem_duplicate_sender, emailProblem(ApiException(409, Problem(code = ProblemCode.DUPLICATE_SENDER))))
        assertEquals(R.string.email_problem_already_decided, emailProblem(ApiException(409, Problem(code = ProblemCode.ALREADY_DECIDED))))
    }

    @Test fun chatStatusTexts() {
        assertEquals(ChatTone.Good, TelegramLogic.status(chat()).tone)
        val privacy = TelegramLogic.status(chat(status = TelegramChatStatus.NEEDS_ATTENTION, error = "privacy_mode", sees = false))
        assertEquals(ChatTone.Warn, privacy.tone)
        assertEquals(R.string.tg_err_privacy_mode, privacy.problem)
        assertEquals(listOf("Peeters family"), privacy.problemArgs)
        assertTrue(TelegramLogic.status(chat(status = TelegramChatStatus.NEEDS_ATTENTION, error = "bot_changed")).reconnect)
        assertEquals("an active group that can't see photos still says so", R.string.tg_err_privacy_mode, TelegramLogic.status(chat(sees = false)).problem)
        val ended = TelegramLogic.status(chat(status = TelegramChatStatus.DISCONNECTED).copy(endedReason = "stopped", endedByName = "Ben"))
        assertEquals(ChatTone.Ended, ended.tone)
        assertEquals(R.string.tg_ended_stopped, ended.problem)
        assertEquals(listOf("Ben"), ended.problemArgs)
        assertEquals(listOf(R.string.tg_warn_cannot_post), TelegramLogic.status(chat().copy(warnings = listOf("cannot_post", "new_one"))).warnings)
        assertEquals(R.string.tg_code_already_connected, TelegramLogic.codeError("already_connected"))
        assertEquals(R.string.tg_code_failed, TelegramLogic.codeError("brand_new"))
        assertNull(TelegramLogic.codeError(null))
    }

    @Test fun creditsAndJoinRequests() {
        val fmt: (String, String) -> String = { n, v -> "$n via $v" }
        val channel: (String, String) -> String = { p, s -> "$p via ${if (s == "telegram") "Telegram" else "email"}" }
        assertEquals("the person replaces the owner", "Jan via email", SourceCredit.text("Anna", ItemVia("email", "Jan", addressId = "ea1"), fmt, channel))
        assertEquals("Ben via Telegram", SourceCredit.text("Anna", ItemVia("telegram", "Ben"), fmt, channel))
        assertEquals("empty label: the sender", "Anna via email", SourceCredit.text("Anna", ItemVia("email", " "), fmt, channel))
        assertEquals("Anna via Immich", SourceCredit.text("Anna", ItemVia("immich", "Immich"), fmt, channel))

        val email = JoinRequest("jr1", via = "email", viaName = "Jan · jan.peeters@telenet.be", viaMatches = true)
        assertEquals(R.string.people_via_email, JoinRequestVia.line(email))
        assertEquals("Jan · jan.peeters@telenet.be" to true, JoinRequestVia.detail(email))
        val tg = JoinRequest("jr2", via = "telegram", viaName = "Ben · Telegram, Peeters family")
        assertEquals(R.string.people_via_telegram, JoinRequestVia.line(tg))
        assertEquals("Ben · Telegram, Peeters family" to null, JoinRequestVia.detail(tg))
        assertNull(JoinRequestVia.detail(JoinRequest("jr3", via = "invite", viaName = "x")))
        assertEquals(R.string.people_via_code, JoinRequestVia.line(JoinRequest("jr4")))
    }

    @Test fun parsesEventsAndRoutesPushes() {
        val up = LiveEvent.parse("""{"type":"email_address.updated","address":{"id":"ea1","address":"a@b.c","slug":"a","name":"A","target":{"kind":"feed","feed_id":"fd1"},"status":"active","anyone_may_send":false,"send_confirmations":true,"offer_app_invite":true,"notify_reactions":true,"counts":{"allowed_senders":0,"pending_senders":1,"held_messages":1},"created_at":"2026-11-19T10:00:00Z"}}""")
        assertEquals("fd1", (up as LiveEvent.EmailAddressUpdated).address.target.feedId)
        assertEquals(LiveEvent.EmailAddressRemoved("ea1", "f1", null), LiveEvent.parse("""{"type":"email_address.removed","address_id":"ea1","frame_id":"f1"}"""))
        val p = LiveEvent.parse("""{"type":"email_sender.pending","address_id":"ea1","pending":{"id":"p1","email":"rita@gmail.com","authenticated":true,"first_held_at":"x","last_held_at":"x","messages":[]}}""")
        assertEquals("p1", (p as LiveEvent.EmailSenderPending).pending.id)
        assertEquals(LiveEvent.EmailSenderDecided("ea1", "p1", "approved"), LiveEvent.parse("""{"type":"email_sender.decided","address_id":"ea1","sender_id":"p1","decision":"approved"}"""))
        val c = LiveEvent.parse("""{"type":"telegram_chat.updated","chat":{"id":"tc1","chat_kind":"group","title":"G","target":{"kind":"frame","frame_id":"f1"},"status":"active","contributors":"everyone","frame_replies":false,"notify_reactions":false,"offer_app_invite":true,"counts":{"sent":1,"contributors":1},"created_at":"x"}}""")
        assertEquals("tc1", (c as LiveEvent.TelegramChatUpdated).chat.id)
        assertEquals(LiveEvent.TelegramChatRemoved("tc1", null, "fd1"), LiveEvent.parse("""{"type":"telegram_chat.removed","chat_id":"tc1","feed_id":"fd1"}"""))
        val code = LiveEvent.parse("""{"type":"telegram_code.updated","code":{"id":"cd1","purpose":"connect","status":"used","chat_id":"tc1","expires_at":"x"}}""")
        assertEquals("tc1", (code as LiveEvent.TelegramCodeUpdated).code.chatId)
        assertEquals(LiveEvent.TelegramLinkUpdated(null), LiveEvent.parse("""{"type":"telegram_link.updated","link":null}"""))
        assertEquals("Anna", (LiveEvent.parse("""{"type":"telegram_link.updated","link":{"display_name":"Anna","can_message":true,"linked_at":"x"}}""") as LiveEvent.TelegramLinkUpdated).link!!.displayName)

        fun push(key: String, ids: Map<String, String>) = PushMessage("push", "", "", ids, key)
        assertEquals(Notifications.TARGET_EMAIL_PENDING, Notifications.targetOf(push("email_pending_sender", mapOf("address_id" to "ea1", "sender_id" to "p1", "frame_id" to "f1"))))
        assertEquals(Notifications.TARGET_EMAIL_PENDING, Notifications.targetOf(push("email_pending_expiring", mapOf("address_id" to "ea1", "feed_id" to "fd1"))))
        assertEquals(Notifications.TARGET_TELEGRAM_CHAT, Notifications.targetOf(push("telegram_attention", mapOf("chat_id" to "tc1", "frame_id" to "f1"))))
        assertEquals("without ids: the history", Notifications.TARGET_HISTORY, Notifications.targetOf(push("telegram_attention", emptyMap())))
    }

    // ---------------------------------------------------------------------------------------------- email view models

    @Test fun inboxListCreatesAndFollowsEvents() = runTest {
        val live = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 8)
        val calls = mutableListOf<String>()
        val vm = EmailInboxViewModel(object : FakeApi() {
            override suspend fun frameEmailAddresses(frameId: String) = listOf(address())
            override suspend fun createFrameEmailAddress(frameId: String, request: EmailAddressCreate, idempotencyKey: String): EmailAddress {
                calls += "create ${request.slug} $idempotencyKey"; return address(id = "ea2")
            }
            override suspend fun updateEmailAddress(addressId: String, update: EmailAddressUpdate): EmailAddress {
                calls += "patch $addressId ${update.paused}"; return address(paused = update.paused == true)
            }
            override suspend fun emailAddress(addressId: String) = address(pending = 2)
        }, ChannelScope.frame("f1", "Oma"), live, newKey = { "k1" })
        val notices = mutableListOf<ChannelNotice>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { notices += it } }
        assertEquals(1, (vm.state.value.addresses as Load.Ready).value.size)

        vm.create("-Bad Slug-")
        assertEquals("cleaned to a valid slug", "create bad-slug k1", calls.last())
        assertEquals(listOf("ea1", "ea2"), (vm.state.value.addresses as Load.Ready).value.map { it.id })
        vm.create("")
        assertEquals("empty lets the server choose", "create null k1", calls.last())

        vm.setPaused(address(), true)
        assertEquals("patch ea1 true", calls.last())
        assertTrue((vm.state.value.addresses as Load.Ready).value.first().isPaused)

        live.emit(LiveEvent.EmailAddressUpdated(address(id = "other", target = feedTarget)))
        assertEquals("another target's address is ignored", 2, (vm.state.value.addresses as Load.Ready).value.size)
        live.emit(LiveEvent.EmailSenderPending("ea1", pending()))
        assertEquals("a new pending sender refreshes the badge", 2, (vm.state.value.addresses as Load.Ready).value.first().counts.pendingSenders)
        live.emit(LiveEvent.EmailAddressRemoved("ea2", "f1", null))
        assertEquals(listOf("ea1"), (vm.state.value.addresses as Load.Ready).value.map { it.id })
        assertTrue(notices.any { it is ChannelNotice.Message && it.message == R.string.email_paused_done })
        job.cancel()
    }

    @Test fun createLimitIsExplained() = runTest {
        val vm = EmailInboxViewModel(object : FakeApi() {
            override suspend fun feedEmailAddresses(feedId: String) = emptyList<EmailAddress>()
            override suspend fun createFeedEmailAddress(feedId: String, request: EmailAddressCreate, idempotencyKey: String): EmailAddress =
                throw ApiException(409, Problem(code = ProblemCode.LIMIT_REACHED))
        }, ChannelScope.feed("fd1", "Peeters kids"), emptyFlow())
        vm.create("peeters")
        assertEquals(R.string.email_problem_limit, vm.state.value.createError)
        assertFalse(vm.state.value.creating)
    }

    private inner class AddressApi : FakeApi() {
        val calls = mutableListOf<String>()
        var pendingList = listOf(pending(), pending("p2", authenticated = false))
        var approveFails = false
        override suspend fun emailAddress(addressId: String) = address()
        override suspend fun emailSenders(addressId: String) = listOf(EmailSender("s1", "jan@x.be", "Jan"))
        override suspend fun emailPendingSenders(addressId: String) = pendingList
        override suspend fun emailMessages(addressId: String, cursor: String?, limit: Int) =
            if (cursor == null) EmailMessagePage(listOf(EmailMessage("m9", outcome = EmailOutcome.DELIVERED, items = 4)), "c2")
            else EmailMessagePage(listOf(EmailMessage("m10", outcome = EmailOutcome.NO_MEDIA)), null)
        override suspend fun updateEmailAddress(addressId: String, update: EmailAddressUpdate): EmailAddress {
            calls += "patch slug=${update.slug} take=${update.takeOver} anyone=${update.anyoneMaySend}"; return address().copy(slug = update.slug ?: "oma")
        }
        override suspend fun regenerateEmailAddress(addressId: String): EmailAddress { calls += "regenerate"; return address().copy(address = "oma-p3wd8h@in") }
        override suspend fun deleteEmailAddress(addressId: String) { calls += "delete" }
        override suspend fun addEmailSender(addressId: String, request: EmailSenderCreate): EmailSender {
            calls += "add ${request.email} ${request.displayName} ${request.blocked}"
            return EmailSender("s2", request.email, request.displayName, if (request.blocked) EmailSenderState.BLOCKED else EmailSenderState.ALLOWED)
        }
        override suspend fun updateEmailSender(addressId: String, senderId: String, update: EmailSenderUpdate): EmailSender {
            calls += "sender $senderId ${update.toJson()}"; return EmailSender(senderId, "jan@x.be", null, if (update.blocked == true) EmailSenderState.BLOCKED else EmailSenderState.ALLOWED)
        }
        override suspend fun removeEmailSender(addressId: String, senderId: String) { calls += "remove $senderId" }
        override suspend fun approveEmailSender(addressId: String, senderId: String, request: EmailSenderApprove): EmailSender {
            if (approveFails) throw ApiException(409, Problem(code = ProblemCode.ALREADY_DECIDED))
            calls += "approve $senderId ${request.deliver}"; pendingList = pendingList.filterNot { it.id == senderId }
            return EmailSender(senderId, "rita@gmail.com", "Rita", origin = "approved")
        }
        override suspend fun rejectEmailSender(addressId: String, senderId: String, block: Boolean) {
            calls += "reject $senderId $block"; pendingList = pendingList.filterNot { it.id == senderId }
        }
        override suspend fun emailHeldMessage(addressId: String, messageId: String) =
            EmailHeldMessage(messageId, addressId, "p2", "rita@gmail.com", reason = EmailHeldReason.UNVERIFIED_SENDER)
        override suspend fun deliverEmailHeldMessage(addressId: String, messageId: String): EmailMessage { calls += "deliver $messageId"; return EmailMessage(messageId) }
        override suspend fun deleteEmailHeldMessage(addressId: String, messageId: String) { calls += "drop $messageId" }
    }

    @Test fun addressDetailManagesSendersAndHeldMail() = runTest {
        val api = AddressApi()
        val live = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 8)
        val vm = EmailAddressViewModel(api, "ea1", live)
        val notices = mutableListOf<ChannelNotice>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { notices += it } }
        val st = vm.state.value
        assertEquals("ea1", st.addressOrNull?.id)
        assertEquals(1, (st.senders as Load.Ready).value.size)
        assertEquals(2, (st.pending as Load.Ready).value.size)
        assertEquals("c2", st.nextCursor)
        vm.loadMoreMessages()
        assertEquals(listOf("m9", "m10"), (vm.state.value.messages as Load.Ready).value.map { it.id })
        assertNull(vm.state.value.nextCursor)

        // Senders
        vm.addSender("nope", "")
        assertEquals(R.string.email_sender_invalid, vm.state.value.senderError)
        var added = false
        vm.addSender(" jan.peeters@telenet.be ", " Jan ") { added = true }
        assertEquals("add jan.peeters@telenet.be Jan false", api.calls.last())
        assertTrue(added)
        assertEquals(2, (vm.state.value.senders as Load.Ready).value.size)
        vm.setBlocked(EmailSender("s1", "jan@x.be"), true)
        assertEquals("""sender s1 {"blocked":true}""", api.calls.last())
        vm.renameSender(EmailSender("s1", "jan@x.be", "Jan"), "")
        assertEquals("""sender s1 {"display_name":null}""", api.calls.last())
        vm.removeSender(EmailSender("s1", "jan@x.be"))
        assertEquals(listOf("s2"), (vm.state.value.senders as Load.Ready).value.map { it.id })

        // Pending: approve delivers, reject can block
        vm.approve(pending())
        assertEquals("approve p1 true", api.calls.last())
        assertEquals(listOf("p2"), (vm.state.value.pending as Load.Ready).value.map { it.id })
        vm.openHeld("m1")
        assertTrue(vm.state.value.held!!.isUnverified)
        vm.deliverHeld(vm.state.value.held!!)
        assertEquals("deliver m1", api.calls.last())
        assertNull(vm.state.value.held)
        vm.reject(pending("p2", false), block = true)
        assertEquals("reject p2 true", api.calls.last())
        assertTrue((vm.state.value.pending as Load.Ready).value.isEmpty())

        // Someone else was first: say so and show what is true now.
        api.pendingList = listOf(pending("p3"))
        live.emit(LiveEvent.EmailSenderPending("ea1", pending("p3")))
        api.approveFails = true
        vm.approve(pending("p3"))
        assertTrue(notices.contains(ChannelNotice.Error(R.string.email_problem_already_decided)))
        assertEquals(listOf("p3"), (vm.state.value.pending as Load.Ready).value.map { it.id })

        // The address
        vm.changeSlug("Opa")
        assertEquals("patch slug=opa take=null anyone=null", api.calls.last())
        vm.changeSlug("-")
        assertTrue(notices.contains(ChannelNotice.Error(R.string.email_slug_invalid)))
        vm.update(EmailAddressUpdate(anyoneMaySend = true))
        assertEquals("patch slug=null take=null anyone=true", api.calls.last())
        vm.regenerate()
        assertEquals("oma-p3wd8h@in", vm.state.value.addressOrNull?.address)
        vm.delete()
        assertEquals("delete", api.calls.last())
        assertTrue(notices.last() is ChannelNotice.Gone || notices.contains(ChannelNotice.Gone))
        job.cancel()
    }

    @Test fun addressRemovedElsewhereLeaves() = runTest {
        val live = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 8)
        val vm = EmailAddressViewModel(AddressApi(), "ea1", live)
        val notices = mutableListOf<ChannelNotice>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { notices += it } }
        live.emit(LiveEvent.EmailAddressRemoved("other", "f1", null))
        assertTrue(notices.isEmpty())
        live.emit(LiveEvent.EmailAddressRemoved("ea1", "f1", null))
        assertEquals(listOf<ChannelNotice>(ChannelNotice.Gone), notices)
        job.cancel()
    }

    // ---------------------------------------------------------------------------------------------- telegram view models

    @Test fun connectGroupOpensTelegramAndFollowsTheCode() = runTest {
        val live = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 8)
        val calls = mutableListOf<String>()
        var chats = listOf<TelegramChat>()
        val vm = TelegramChatsViewModel(object : FakeApi() {
            override suspend fun frameTelegramChats(frameId: String) = chats
            override suspend fun createFrameTelegramCode(frameId: String, request: TelegramConnectCodeCreate, idempotencyKey: String): TelegramCode {
                calls += "code ${request.chatKind} ${request.linkAccount}"
                return TelegramCode("cd1", chatKind = request.chatKind, url = "https://t.me/kinframe_bot?startgroup=abc", status = TelegramCodeStatus.OPEN)
            }
            override suspend fun telegramChat(chatId: String) = chat(chatId)
            override suspend fun revokeTelegramCode(codeId: String) { calls += "revoke $codeId" }
            override suspend fun updateTelegramChat(chatId: String, update: TelegramChatUpdate): TelegramChat {
                calls += "patch ${update.toJson()}"; return chat(chatId, status = if (update.paused == true) TelegramChatStatus.PAUSED else TelegramChatStatus.ACTIVE)
            }
        }, ChannelScope.frame("f1", "Oma"), live, pollMs = 3_600_000)
        val notices = mutableListOf<ChannelNotice>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { notices += it } }

        vm.connect(ConnectKind.Group)
        assertEquals("code group false", calls.last())
        assertEquals(ChannelNotice.OpenTelegram("https://t.me/kinframe_bot?startgroup=abc"), notices.last())
        assertTrue(vm.state.value.connect!!.waiting)

        // Redeeming failed in Telegram: say why, keep waiting for another try.
        live.emit(LiveEvent.TelegramCodeUpdated(TelegramCode("cd1", status = TelegramCodeStatus.OPEN, errorCode = "already_connected")))
        assertEquals(R.string.tg_code_already_connected, vm.state.value.connect!!.error)
        assertEquals("the link from the create response is kept", "https://t.me/kinframe_bot?startgroup=abc", vm.state.value.connect!!.url)
        live.emit(LiveEvent.TelegramCodeUpdated(TelegramCode("other", status = TelegramCodeStatus.USED, chatId = "x")))
        assertFalse("someone else's code", vm.state.value.connect!!.connected)

        live.emit(LiveEvent.TelegramCodeUpdated(TelegramCode("cd1", status = TelegramCodeStatus.USED, chatId = "tc1")))
        val k = vm.state.value.connect!!
        assertTrue(k.connected)
        assertEquals("tc1", k.chat?.id)
        assertNull(k.error)
        assertEquals(listOf("tc1"), (vm.state.value.chats as Load.Ready).value.map { it.id })
        vm.closeConnect()
        assertFalse("a used code isn't revoked", calls.any { it.startsWith("revoke") })

        vm.connect(ConnectKind.Mine)
        assertEquals("code private true", calls.last())
        vm.closeConnect()
        assertEquals("an open code is revoked on close", "revoke cd1", calls.last())

        vm.connect(ConnectKind.Invite)
        assertEquals("code private false", calls.last())
        assertTrue("an invite link is shared, not opened", notices.last() is ChannelNotice.ShareTelegram)
        vm.closeConnect()

        live.emit(LiveEvent.TelegramChatUpdated(chat("tc9", target = feedTarget)))
        assertEquals("another target's chat is ignored", listOf("tc1"), (vm.state.value.chats as Load.Ready).value.map { it.id })
        vm.setPaused(chat("tc1"), true)
        assertEquals("""patch {"paused":true}""", calls.last())
        assertTrue((vm.state.value.chats as Load.Ready).value.single().isPaused)
        live.emit(LiveEvent.TelegramChatRemoved("tc1", "f1", null))
        assertTrue((vm.state.value.chats as Load.Ready).value.isEmpty())
        job.cancel()
    }

    @Test fun connectLimitIsExplained() = runTest {
        val vm = TelegramChatsViewModel(object : FakeApi() {
            override suspend fun feedTelegramChats(feedId: String) = emptyList<TelegramChat>()
            override suspend fun createFeedTelegramCode(feedId: String, request: TelegramConnectCodeCreate, idempotencyKey: String): TelegramCode =
                throw ApiException(409, Problem(code = ProblemCode.LIMIT_REACHED))
        }, ChannelScope.feed("fd1", "Peeters kids"), emptyFlow(), pollMs = 3_600_000)
        vm.connect(ConnectKind.Group)
        assertEquals(R.string.tg_problem_limit, vm.state.value.connect!!.error)
        assertFalse(vm.state.value.connect!!.creating)
    }

    @Test fun chatDetailSettingsContributorsAndDisconnect() = runTest {
        val calls = mutableListOf<String>()
        var current = chat(status = TelegramChatStatus.NEEDS_ATTENTION, error = "privacy_mode", sees = false)
        val live = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 8)
        val vm = TelegramChatViewModel(object : FakeApi() {
            override suspend fun telegramChat(chatId: String) = current
            override suspend fun telegramContributors(chatId: String) = listOf(TelegramContributor("ct1", "Cousin", sentCount = 30))
            override suspend fun updateTelegramChat(chatId: String, update: TelegramChatUpdate): TelegramChat { calls += "patch ${update.toJson()}"; return current }
            override suspend fun updateTelegramContributor(chatId: String, contributorId: String, blocked: Boolean): TelegramContributor {
                calls += "block $contributorId $blocked"; return TelegramContributor(contributorId, "Cousin", blocked = blocked)
            }
            override suspend fun disconnectTelegramChat(chatId: String) {
                calls += "disconnect"; current = current.copy(status = TelegramChatStatus.DISCONNECTED, endedReason = "disconnected", endedByName = "Anna")
            }
        }, "tc1", live)
        assertEquals(R.string.tg_err_privacy_mode, TelegramLogic.status(vm.state.value.chatOrNull!!).problem)
        // The bot was made an admin: the chat turns active by itself.
        live.emit(LiveEvent.TelegramChatUpdated(chat()))
        assertEquals(ChatTone.Good, TelegramLogic.status(vm.state.value.chatOrNull!!).tone)

        vm.update(TelegramChatUpdate(contributors = "linked_only"))
        assertEquals("""patch {"contributors":"linked_only"}""", calls.last())
        vm.setTopic(null)
        assertEquals("""patch {"topic_id":null}""", calls.last())
        vm.setTopic(42)
        assertEquals("""patch {"topic_id":42}""", calls.last())
        vm.takeOver()
        assertEquals("""patch {"take_over":true}""", calls.last())
        vm.update(TelegramChatUpdate())
        assertEquals("an empty update sends nothing", """patch {"take_over":true}""", calls.last())

        vm.setBlocked(TelegramContributor("ct1", "Cousin"), true)
        assertEquals("block ct1 true", calls.last())
        assertTrue((vm.state.value.contributors as Load.Ready).value.single().blocked)

        vm.disconnect()
        assertEquals("disconnect", calls.last())
        assertEquals(TelegramChatStatus.DISCONNECTED, vm.state.value.chatOrNull!!.status)
        assertEquals(R.string.tg_ended_disconnected, TelegramLogic.status(vm.state.value.chatOrNull!!).problem)
    }

    @Test fun linkMyTelegram() = runTest {
        val live = MutableSharedFlow<LiveEvent>(extraBufferCapacity = 8)
        var link: TelegramLink? = null
        val calls = mutableListOf<String>()
        val vm = TelegramLinkViewModel(object : FakeApi() {
            override suspend fun telegramLink() = link
            override suspend fun createTelegramLinkCode(idempotencyKey: String): TelegramCode {
                calls += "code"; return TelegramCode("lc1", purpose = "link", url = "https://t.me/kinframe_bot?start=xyz")
            }
            override suspend fun unlinkTelegram() { calls += "unlink"; link = null }
        }, live)
        val notices = mutableListOf<ChannelNotice>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { notices += it } }
        assertEquals(Load.Ready(null), vm.state.value.link)
        vm.link()
        assertEquals(ChannelNotice.OpenTelegram("https://t.me/kinframe_bot?start=xyz"), notices.last())
        assertEquals("lc1", vm.state.value.code?.id)
        link = TelegramLink("Anna P", "annap")
        live.emit(LiveEvent.TelegramLinkUpdated(link))
        assertEquals(Load.Ready(link), vm.state.value.link)
        assertNull(vm.state.value.code)
        assertEquals(ChannelNotice.Message(R.string.tg_linked_done, listOf("Anna P")), notices.last())
        vm.unlink()
        assertEquals("unlink", calls.last())
        assertEquals(Load.Ready(null), vm.state.value.link)
        job.cancel()
    }
}
