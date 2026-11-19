package app.kinframe.core.servers

import app.kinframe.core.api.AccessTokenSource
import app.kinframe.core.api.Feed
import app.kinframe.core.api.FeedInvite
import app.kinframe.core.api.FeedInviteCreate
import app.kinframe.core.leaving.AuthedClient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.HttpUrl
import okhttp3.OkHttpClient

/**
 * Families on other servers (README §5.22; openapi tag `remote`, plus `allow_remote_frames` on feeds and
 * `allow_remote` on subscribe invites). Hidden while discovery `remote_families` is false.
 */
interface RemoteApi {
    /** B fetches A's well-known document and checks the invite; nothing is recorded. */
    suspend fun previewInvite(inviteUrl: String): RemoteInvitePreview
    suspend fun remoteSubscriptions(frameId: String): List<RemoteSubscription>
    suspend fun createRemoteSubscription(frameId: String, create: RemoteSubscriptionCreate, idempotencyKey: String): RemoteSubscription
    suspend fun updateRemoteSubscription(frameId: String, id: String, update: RemoteSubscriptionUpdate): RemoteSubscription
    /** [placements] `keep` or `remove`; null = the follow's `on_end`. */
    suspend fun deleteRemoteSubscription(frameId: String, id: String, placements: String? = null)
    suspend fun blockedServers(feedId: String): List<BlockedServer>
    suspend fun blockServer(feedId: String, create: BlockedServerCreate): BlockedServer
    suspend fun unblockServer(feedId: String, blockedServerId: String)
    /** "Allow frames on other servers" (owner and admins). */
    suspend fun setAllowRemoteFrames(feedId: String, allow: Boolean): Feed
    /** A `subscribe` invite with "Frames on other servers may use this link". */
    suspend fun createFeedInvite(feedId: String, create: FeedInviteCreate, idempotencyKey: String): FeedInvite
}

class HttpRemoteApi(
    http: OkHttpClient,
    apiBase: HttpUrl,
    tokens: AccessTokenSource,
    userAgent: String = "kinframe-family",
    appHeader: String? = null,
    acceptLanguage: () -> String? = { null },
) : RemoteApi {
    private val c = AuthedClient(http, apiBase, tokens, userAgent, appHeader, acceptLanguage)

    override suspend fun previewInvite(inviteUrl: String): RemoteInvitePreview =
        c.decode(c.call { url(c.ep("remote-invites:preview")).post(c.json(RemoteInviteRef(inviteUrl), RemoteInviteRef.serializer())) }, RemoteInvitePreview.serializer())

    override suspend fun remoteSubscriptions(frameId: String): List<RemoteSubscription> =
        c.decode(c.call { url(c.ep("frames/$frameId/remote-subscriptions")).get() }, ListSerializer(RemoteSubscription.serializer()))

    override suspend fun createRemoteSubscription(frameId: String, create: RemoteSubscriptionCreate, idempotencyKey: String): RemoteSubscription =
        c.decode(c.call {
            url(c.ep("frames/$frameId/remote-subscriptions")).header("Idempotency-Key", idempotencyKey)
                .post(c.json(create, RemoteSubscriptionCreate.serializer()))
        }, RemoteSubscription.serializer())

    override suspend fun updateRemoteSubscription(frameId: String, id: String, update: RemoteSubscriptionUpdate): RemoteSubscription =
        c.decode(c.call {
            url(c.ep("frames/$frameId/remote-subscriptions/$id")).patch(c.json(update, RemoteSubscriptionUpdate.serializer()))
        }, RemoteSubscription.serializer())

    override suspend fun deleteRemoteSubscription(frameId: String, id: String, placements: String?) =
        c.expectOk(c.call { url(c.ep("frames/$frameId/remote-subscriptions/$id", mapOf("placements" to placements))).delete() })

    override suspend fun blockedServers(feedId: String): List<BlockedServer> =
        c.decode(c.call { url(c.ep("feeds/$feedId/blocked-servers")).get() }, ListSerializer(BlockedServer.serializer()))

    override suspend fun blockServer(feedId: String, create: BlockedServerCreate): BlockedServer =
        c.decode(c.call { url(c.ep("feeds/$feedId/blocked-servers")).post(c.json(create, BlockedServerCreate.serializer())) }, BlockedServer.serializer())

    override suspend fun unblockServer(feedId: String, blockedServerId: String) =
        c.expectOk(c.call { url(c.ep("feeds/$feedId/blocked-servers/$blockedServerId")).delete() })

    override suspend fun setAllowRemoteFrames(feedId: String, allow: Boolean): Feed =
        c.decode(c.call {
            url(c.ep("feeds/$feedId")).patch(c.json(buildJsonObject { put("allow_remote_frames", JsonPrimitive(allow)) }))
        }, Feed.serializer())

    override suspend fun createFeedInvite(feedId: String, create: FeedInviteCreate, idempotencyKey: String): FeedInvite =
        c.decode(c.call {
            url(c.ep("feeds/$feedId/invites")).header("Idempotency-Key", idempotencyKey).post(c.json(create, FeedInviteCreate.serializer()))
        }, FeedInvite.serializer())
}
