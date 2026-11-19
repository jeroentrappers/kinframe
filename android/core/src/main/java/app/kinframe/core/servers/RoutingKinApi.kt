package app.kinframe.core.servers

import app.kinframe.core.api.*
import kotlinx.serialization.json.JsonObject

/**
 * The family app's [KinApi] when it is signed in to several servers (docs/spec/multi-server.md §2.2): the frame,
 * family and history lists are merged across accounts, and every call about one frame, family, send, invite… goes to
 * the server that id came from ([AccountIndex]); anything else goes to the primary account ([primary], by
 * delegation). With one account it is a thin pass-through.
 */
class RoutingKinApi(private val routes: Routes, primary: KinApi) : KinApi by primary {

    private suspend fun <T> on(key: String?, block: suspend KinApi.() -> T): T {
        val r = routes.forKey(key)
        return r.kin.block().also { routes.index.learn(r.id, it) }
    }

    // ---- merged lists

    override suspend fun frames(): List<Frame> = routes.each { it.kin.frames() }.flatMap { it.second }

    override suspend fun feeds(): List<Feed> =
        routes.each(routes.all().filter { it.discovery.feeds }) { it.kin.feeds() }.flatMap { it.second }

    /** The history of every account, interleaved by time; the cursor holds each account's own cursor. */
    override suspend fun sends(cursor: String?, limit: Int, state: String): SendsPage {
        val cursors = Merging.decodeCursor(cursor)
        val targets = routes.all().filter { cursors == null || it.id in cursors }
        if (targets.size == 1 && cursors == null) return targets.single().kin.sends(null, limit, state).also { routes.index.learn(targets.single().id, it) }
        val pages = routes.each(targets) { it.kin.sends(cursors?.get(it.id), limit, state) }
        return SendsPage(
            sends = Merging.interleave(pages.map { it.second.sends }) { it.createdAt },
            nextCursor = Merging.encodeCursor(pages.associate { it.first.id to it.second.nextCursor }),
        )
    }

    // ---- routed by id (generated from the KinApi interface)

    override suspend fun frame(frameId: String): Frame = on(frameId) { frame(frameId) }
    override suspend fun updateFrame(frameId: String, patch: JsonObject): Frame = on(frameId) { updateFrame(frameId, patch) }
    override suspend fun removeFrame(frameId: String): Unit = on(frameId) { removeFrame(frameId) }
    override suspend fun leaveFrame(frameId: String, removeMyItems: Boolean): Unit = on(frameId) { leaveFrame(frameId, removeMyItems) }
    override suspend fun redeem(code: String, idempotencyKey: String): RedeemResult = on(code) { redeem(code, idempotencyKey) }
    override suspend fun health(frameId: String): FrameHealth = on(frameId) { health(frameId) }
    override suspend fun command(frameId: String, command: FrameCommand): CommandResult = on(frameId) { command(frameId, command) }
    override suspend fun transferOwnership(frameId: String, userId: String): Unit = on(frameId) { transferOwnership(frameId, userId) }
    override suspend fun replaceDevice(frameId: String, code: String): Frame = on(frameId) { replaceDevice(frameId, code) }
    override suspend fun members(frameId: String): List<Member> = on(frameId) { members(frameId) }
    override suspend fun updateMember(frameId: String, userId: String, role: String?, permissions: Permissions?): Member = on(frameId) { updateMember(frameId, userId, role, permissions) }
    override suspend fun removeMember(frameId: String, userId: String, removeItems: Boolean): Unit = on(frameId) { removeMember(frameId, userId, removeItems) }
    override suspend fun joinRequests(frameId: String): List<JoinRequest> = on(frameId) { joinRequests(frameId) }
    override suspend fun decideJoinRequest(frameId: String, requestId: String, approve: Boolean): Unit = on(frameId) { decideJoinRequest(frameId, requestId, approve) }
    override suspend fun invites(frameId: String): List<Invite> = on(frameId) { invites(frameId) }
    override suspend fun createInvite(frameId: String, request: InviteCreate, idempotencyKey: String): Invite = on(frameId) { createInvite(frameId, request, idempotencyKey) }
    override suspend fun revokeInvite(frameId: String, inviteId: String): Unit = on(frameId) { revokeInvite(frameId, inviteId) }
    override suspend fun previewInvite(token: String): InvitePreview = on(token) { previewInvite(token) }
    override suspend fun acceptInvite(token: String): RedeemResult = on(token) { acceptInvite(token) }
    override suspend fun send(sendId: String): Send = on(sendId) { send(sendId) }
    override suspend fun unsend(sendId: String): Unit = on(sendId) { unsend(sendId) }
    override suspend fun forwardSend(sendId: String, frameIds: List<String>, feedIds: List<String>): Send = on(sendId) { forwardSend(sendId, frameIds, feedIds) }
    override suspend fun updateItem(itemId: String, patch: JsonObject): Item = on(itemId) { updateItem(itemId, patch) }
    override suspend fun placements(frameId: String, visibility: String, cursor: String?, limit: Int, albumId: String?, onThisDay: String?, sort: String?): PlacementsPage = on(frameId) { placements(frameId, visibility, cursor, limit, albumId, onThisDay, sort) }
    override suspend fun updatePlacement(frameId: String, placementId: String, patch: JsonObject): Placement = on(frameId) { updatePlacement(frameId, placementId, patch) }
    override suspend fun bulkPlacements(frameId: String, placementIds: List<String>, action: String, albumId: String?): BulkResult = on(frameId) { bulkPlacements(frameId, placementIds, action, albumId) }
    override suspend fun sharePlacements(frameId: String, placementIds: List<String>, frameIds: List<String>, dryRun: Boolean, idempotencyKey: String?): ShareResult = on(frameId) { sharePlacements(frameId, placementIds, frameIds, dryRun, idempotencyKey) }
    override suspend fun settings(frameId: String): SettingsDocument = on(frameId) { settings(frameId) }
    override suspend fun patchSettings(frameId: String, version: Int, patch: JsonObject): SettingsDocument = on(frameId) { patchSettings(frameId, version, patch) }
    override suspend fun framePlugins(frameId: String): FramePlugins = on(frameId) { framePlugins(frameId) }
    override suspend fun albums(frameId: String): List<Album> = on(frameId) { albums(frameId) }
    override suspend fun createAlbum(frameId: String, name: String, placementIds: List<String>): Album = on(frameId) { createAlbum(frameId, name, placementIds) }
    override suspend fun updateAlbum(frameId: String, albumId: String, version: Int, patch: JsonObject): Album = on(frameId) { updateAlbum(frameId, albumId, version, patch) }
    override suspend fun deleteAlbum(frameId: String, albumId: String, removeSourceItems: Boolean): Unit = on(frameId) { deleteAlbum(frameId, albumId, removeSourceItems) }
    override suspend fun frameCalendar(frameId: String): CalendarView = on(frameId) { frameCalendar(frameId) }
    override suspend fun calendars(frameId: String): List<CalendarSource> = on(frameId) { calendars(frameId) }
    override suspend fun createCalendar(frameId: String, request: CalendarSourceCreate, idempotencyKey: String): CalendarSource = on(frameId) { createCalendar(frameId, request, idempotencyKey) }
    override suspend fun updateCalendar(frameId: String, calendarId: String, patch: JsonObject): CalendarSource = on(frameId) { updateCalendar(frameId, calendarId, patch) }
    override suspend fun deleteCalendar(frameId: String, calendarId: String): Unit = on(frameId) { deleteCalendar(frameId, calendarId) }
    override suspend fun syncCalendar(frameId: String, calendarId: String): CalendarSource = on(frameId) { syncCalendar(frameId, calendarId) }
    override suspend fun testCalendar(frameId: String, request: CalendarSourceCreate): CalendarTestResult = on(frameId) { testCalendar(frameId, request) }
    override suspend fun setCountdown(frameId: String, eventId: String): Unit = on(frameId) { setCountdown(frameId, eventId) }
    override suspend fun clearCountdown(frameId: String, eventId: String): Unit = on(frameId) { clearCountdown(frameId, eventId) }
    override suspend fun eventInvitations(frameId: String): List<EventInvitation> = on(frameId) { eventInvitations(frameId) }
    override suspend fun sendEvent(frameId: String, request: EventInvitationCreate, idempotencyKey: String): EventInvitation = on(frameId) { sendEvent(frameId, request, idempotencyKey) }
    override suspend fun withdrawEvent(frameId: String, invitationId: String): Unit = on(frameId) { withdrawEvent(frameId, invitationId) }
    override suspend fun eventPhoto(frameId: String, invitationId: String): ByteArray? = on(frameId) { eventPhoto(frameId, invitationId) }
    override suspend fun tvLinks(frameId: String): List<TvLink> = on(frameId) { tvLinks(frameId) }
    override suspend fun createTvLink(frameId: String, request: TvLinkCreate): TvLink = on(frameId) { createTvLink(frameId, request) }
    override suspend fun revokeTvLink(frameId: String, linkId: String): Unit = on(frameId) { revokeTvLink(frameId, linkId) }
    override suspend fun feed(feedId: String): Feed = on(feedId) { feed(feedId) }
    override suspend fun updateFeed(feedId: String, patch: JsonObject): Feed = on(feedId) { updateFeed(feedId, patch) }
    override suspend fun deleteFeed(feedId: String): Unit = on(feedId) { deleteFeed(feedId) }
    override suspend fun leaveFeed(feedId: String, removeMyPosts: Boolean): Unit = on(feedId) { leaveFeed(feedId, removeMyPosts) }
    override suspend fun transferFeed(feedId: String, userId: String): Unit = on(feedId) { transferFeed(feedId, userId) }
    override suspend fun feedMembers(feedId: String): List<FeedMember> = on(feedId) { feedMembers(feedId) }
    override suspend fun updateFeedMember(feedId: String, userId: String, role: String): FeedMember = on(feedId) { updateFeedMember(feedId, userId, role) }
    override suspend fun removeFeedMember(feedId: String, userId: String, removePosts: Boolean): Unit = on(feedId) { removeFeedMember(feedId, userId, removePosts) }
    override suspend fun feedJoinRequests(feedId: String): List<JoinRequest> = on(feedId) { feedJoinRequests(feedId) }
    override suspend fun decideFeedJoinRequest(feedId: String, requestId: String, approve: Boolean): Unit = on(feedId) { decideFeedJoinRequest(feedId, requestId, approve) }
    override suspend fun feedInvites(feedId: String): List<FeedInvite> = on(feedId) { feedInvites(feedId) }
    override suspend fun createFeedInvite(feedId: String, request: FeedInviteCreate, idempotencyKey: String): FeedInvite = on(feedId) { createFeedInvite(feedId, request, idempotencyKey) }
    override suspend fun revokeFeedInvite(feedId: String, inviteId: String): Unit = on(feedId) { revokeFeedInvite(feedId, inviteId) }
    override suspend fun lookupFeedInvite(code: String): FeedInvitePreview = on(code) { lookupFeedInvite(code) }
    override suspend fun previewFeedInvite(token: String): FeedInvitePreview = on(token) { previewFeedInvite(token) }
    override suspend fun acceptFeedInvite(token: String, accept: FeedInviteAccept?, idempotencyKey: String): FeedInviteResult = on(token) { acceptFeedInvite(token, accept, idempotencyKey) }
    override suspend fun feedPosts(feedId: String, cursor: String?, limit: Int, posterId: String?): FeedPostsPage = on(feedId) { feedPosts(feedId, cursor, limit, posterId) }
    override suspend fun feedPost(feedId: String, postId: String): FeedPost = on(feedId) { feedPost(feedId, postId) }
    override suspend fun removeFeedPost(feedId: String, postId: String): Unit = on(feedId) { removeFeedPost(feedId, postId) }
    override suspend fun removeFeedPostItem(feedId: String, postId: String, itemId: String): Unit = on(feedId) { removeFeedPostItem(feedId, postId, itemId) }
    override suspend fun feedPostReceipts(feedId: String, postId: String): FeedPostReceipts = on(feedId) { feedPostReceipts(feedId, postId) }
    override suspend fun feedSubscriptions(feedId: String, status: String?): List<Subscription> = on(feedId) { feedSubscriptions(feedId, status) }
    override suspend fun requestSubscription(feedId: String, request: SubscriptionRequest, idempotencyKey: String): Subscription = on(feedId) { requestSubscription(feedId, request, idempotencyKey) }
    override suspend fun frameSubscriptions(frameId: String, status: String?): List<Subscription> = on(frameId) { frameSubscriptions(frameId, status) }
    override suspend fun subscription(subscriptionId: String): Subscription = on(subscriptionId) { subscription(subscriptionId) }
    override suspend fun updateSubscription(subscriptionId: String, update: SubscriptionUpdate): Subscription = on(subscriptionId) { updateSubscription(subscriptionId, update) }
    override suspend fun removeSubscription(subscriptionId: String): Unit = on(subscriptionId) { removeSubscription(subscriptionId) }
    override suspend fun approveSubscription(subscriptionId: String): Subscription = on(subscriptionId) { approveSubscription(subscriptionId) }
    override suspend fun rejectSubscription(subscriptionId: String): Subscription = on(subscriptionId) { rejectSubscription(subscriptionId) }
    override suspend fun pauseSubscription(subscriptionId: String): Subscription = on(subscriptionId) { pauseSubscription(subscriptionId) }
    override suspend fun resumeSubscription(subscriptionId: String): Subscription = on(subscriptionId) { resumeSubscription(subscriptionId) }
    override suspend fun feedBirthdays(feedId: String, status: String?): List<BirthdayPerson> = on(feedId) { feedBirthdays(feedId, status) }
    override suspend fun createFeedBirthday(feedId: String, request: BirthdayPersonCreate, idempotencyKey: String): BirthdayPerson = on(feedId) { createFeedBirthday(feedId, request, idempotencyKey) }
    override suspend fun feedBirthday(feedId: String, birthdayId: String): BirthdayPerson = on(feedId) { feedBirthday(feedId, birthdayId) }
    override suspend fun updateFeedBirthday(feedId: String, birthdayId: String, update: BirthdayPersonUpdate): BirthdayPerson = on(feedId) { updateFeedBirthday(feedId, birthdayId, update) }
    override suspend fun removeFeedBirthday(feedId: String, birthdayId: String): Unit = on(feedId) { removeFeedBirthday(feedId, birthdayId) }
    override suspend fun approveFeedBirthday(feedId: String, birthdayId: String): BirthdayPerson = on(feedId) { approveFeedBirthday(feedId, birthdayId) }
    override suspend fun rejectFeedBirthday(feedId: String, birthdayId: String): BirthdayPerson = on(feedId) { rejectFeedBirthday(feedId, birthdayId) }
    override suspend fun putFeedBirthdayPhoto(feedId: String, birthdayId: String, bytes: ByteArray, mime: String): BirthdayPerson = on(feedId) { putFeedBirthdayPhoto(feedId, birthdayId, bytes, mime) }
    override suspend fun deleteFeedBirthdayPhoto(feedId: String, birthdayId: String): BirthdayPerson = on(feedId) { deleteFeedBirthdayPhoto(feedId, birthdayId) }
    override suspend fun feedBirthdayGreetings(feedId: String, birthdayId: String?, date: String?, cursor: String?, limit: Int): FeedBirthdayGreetingsPage = on(feedId) { feedBirthdayGreetings(feedId, birthdayId, date, cursor, limit) }
    override suspend fun updateFeedPreferences(feedId: String, update: FeedPreferencesUpdate): Feed = on(feedId) { updateFeedPreferences(feedId, update) }
    override suspend fun albumPhotoSources(frameId: String, albumId: String): List<PhotoSource> = on(frameId) { albumPhotoSources(frameId, albumId) }
    override suspend fun feedPhotoSources(feedId: String): List<PhotoSource> = on(feedId) { feedPhotoSources(feedId) }
    override suspend fun photoSource(sourceId: String): PhotoSource = on(sourceId) { photoSource(sourceId) }
    override suspend fun updatePhotoSource(sourceId: String, update: PhotoSourceUpdate): PhotoSource = on(sourceId) { updatePhotoSource(sourceId, update) }
    override suspend fun deletePhotoSource(sourceId: String, removeItems: Boolean): Unit = on(sourceId) { deletePhotoSource(sourceId, removeItems) }
    override suspend fun syncPhotoSource(sourceId: String, confirmRemovals: Boolean): PhotoSource = on(sourceId) { syncPhotoSource(sourceId, confirmRemovals) }
    override suspend fun googleImport(importId: String): GoogleImport = on(importId) { googleImport(importId) }
    override suspend fun cancelGoogleImport(importId: String): GoogleImport = on(importId) { cancelGoogleImport(importId) }
    override suspend fun frameEmailAddresses(frameId: String): List<EmailAddress> = on(frameId) { frameEmailAddresses(frameId) }
    override suspend fun feedEmailAddresses(feedId: String): List<EmailAddress> = on(feedId) { feedEmailAddresses(feedId) }
    override suspend fun createFrameEmailAddress(frameId: String, request: EmailAddressCreate, idempotencyKey: String): EmailAddress = on(frameId) { createFrameEmailAddress(frameId, request, idempotencyKey) }
    override suspend fun createFeedEmailAddress(feedId: String, request: EmailAddressCreate, idempotencyKey: String): EmailAddress = on(feedId) { createFeedEmailAddress(feedId, request, idempotencyKey) }
    override suspend fun emailAddress(addressId: String): EmailAddress = on(addressId) { emailAddress(addressId) }
    override suspend fun updateEmailAddress(addressId: String, update: EmailAddressUpdate): EmailAddress = on(addressId) { updateEmailAddress(addressId, update) }
    override suspend fun deleteEmailAddress(addressId: String): Unit = on(addressId) { deleteEmailAddress(addressId) }
    override suspend fun regenerateEmailAddress(addressId: String): EmailAddress = on(addressId) { regenerateEmailAddress(addressId) }
    override suspend fun emailSenders(addressId: String): List<EmailSender> = on(addressId) { emailSenders(addressId) }
    override suspend fun addEmailSender(addressId: String, request: EmailSenderCreate): EmailSender = on(addressId) { addEmailSender(addressId, request) }
    override suspend fun updateEmailSender(addressId: String, senderId: String, update: EmailSenderUpdate): EmailSender = on(addressId) { updateEmailSender(addressId, senderId, update) }
    override suspend fun removeEmailSender(addressId: String, senderId: String): Unit = on(addressId) { removeEmailSender(addressId, senderId) }
    override suspend fun emailPendingSenders(addressId: String): List<EmailPendingSender> = on(addressId) { emailPendingSenders(addressId) }
    override suspend fun approveEmailSender(addressId: String, senderId: String, request: EmailSenderApprove): EmailSender = on(addressId) { approveEmailSender(addressId, senderId, request) }
    override suspend fun rejectEmailSender(addressId: String, senderId: String, block: Boolean): Unit = on(addressId) { rejectEmailSender(addressId, senderId, block) }
    override suspend fun emailHeldMessage(addressId: String, messageId: String): EmailHeldMessage = on(addressId) { emailHeldMessage(addressId, messageId) }
    override suspend fun deleteEmailHeldMessage(addressId: String, messageId: String): Unit = on(addressId) { deleteEmailHeldMessage(addressId, messageId) }
    override suspend fun deliverEmailHeldMessage(addressId: String, messageId: String): EmailMessage = on(addressId) { deliverEmailHeldMessage(addressId, messageId) }
    override suspend fun emailMessages(addressId: String, cursor: String?, limit: Int): EmailMessagePage = on(addressId) { emailMessages(addressId, cursor, limit) }
    override suspend fun frameTelegramChats(frameId: String): List<TelegramChat> = on(frameId) { frameTelegramChats(frameId) }
    override suspend fun feedTelegramChats(feedId: String): List<TelegramChat> = on(feedId) { feedTelegramChats(feedId) }
    override suspend fun createFrameTelegramCode(frameId: String, request: TelegramConnectCodeCreate, idempotencyKey: String): TelegramCode = on(frameId) { createFrameTelegramCode(frameId, request, idempotencyKey) }
    override suspend fun createFeedTelegramCode(feedId: String, request: TelegramConnectCodeCreate, idempotencyKey: String): TelegramCode = on(feedId) { createFeedTelegramCode(feedId, request, idempotencyKey) }
    override suspend fun telegramCode(codeId: String): TelegramCode = on(codeId) { telegramCode(codeId) }
    override suspend fun revokeTelegramCode(codeId: String): Unit = on(codeId) { revokeTelegramCode(codeId) }
    override suspend fun telegramChat(chatId: String): TelegramChat = on(chatId) { telegramChat(chatId) }
    override suspend fun updateTelegramChat(chatId: String, update: TelegramChatUpdate): TelegramChat = on(chatId) { updateTelegramChat(chatId, update) }
    override suspend fun disconnectTelegramChat(chatId: String): Unit = on(chatId) { disconnectTelegramChat(chatId) }
    override suspend fun telegramContributors(chatId: String): List<TelegramContributor> = on(chatId) { telegramContributors(chatId) }
    override suspend fun updateTelegramContributor(chatId: String, contributorId: String, blocked: Boolean): TelegramContributor = on(chatId) { updateTelegramContributor(chatId, contributorId, blocked) }
}
