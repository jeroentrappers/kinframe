package app.kinframe.core.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/*
 * Photo sources and Google Photos imports (openapi tag `photo-sources`: `PhotoSource`, `PhotoSourceCreate`,
 * `PhotoSourceUpdate`, `PhotoSourceTestResult`, `ImmichAlbum(List)`, `GoogleImport`, `ItemVia`; README §5.14,
 * docs/spec/photo-sources.md). Enum-like values stay strings so a newer server never breaks parsing.
 *
 * Secrets (`url`, `api_key`, `password`) are write-only: they exist only in the request builders below, which encode
 * them by hand; no response model has a field for them, so they are never decoded or kept.
 */

object PhotoSourceProvider {
    /** An Immich shared link (`https://<immich>/share/<key>`), optional password. Recommended. */
    const val IMMICH_LINK = "immich_link"
    /** An Immich server URL with an API key and a chosen album. */
    const val IMMICH_API = "immich_api"
    /** An iCloud shared album's public website link. Best effort. */
    const val ICLOUD_LINK = "icloud_link"
    val all = listOf(IMMICH_LINK, IMMICH_API, ICLOUD_LINK)
}

object PhotoSourceStatus {
    const val ACTIVE = "active"
    const val PAUSED = "paused"
    const val ERROR = "error"
    const val NEEDS_ATTENTION = "needs_attention"
}

/** `PhotoSourceErrorCode` (photo-sources.md §13). */
object PhotoSourceErrorCode {
    const val UNREACHABLE = "unreachable"
    const val TLS_ERROR = "tls_error"
    const val PRIVATE_ADDRESS_BLOCKED = "private_address_blocked"
    const val AUTH_FAILED = "auth_failed"
    const val PASSWORD_REQUIRED = "password_required"
    const val PERMISSION_MISSING = "permission_missing"
    const val LINK_NOT_FOUND = "link_not_found"
    const val LINK_EXPIRED = "link_expired"
    const val ALBUM_NOT_FOUND = "album_not_found"
    const val UNSUPPORTED_LINK = "unsupported_link"
    const val TOO_LARGE = "too_large"
    const val FORMAT_CHANGED = "format_changed"
    const val RATE_LIMITED_UPSTREAM = "rate_limited_upstream"
    const val UPSTREAM_ERROR = "upstream_error"
    const val OWNER_LOST_ACCESS = "owner_lost_access"
    const val CREDENTIALS_UNREADABLE = "credentials_unreadable"
    const val MASS_REMOVAL = "mass_removal"
}

/** Conditions that don't stop syncing (`PhotoSourceWarning`). */
object PhotoSourceWarning {
    const val DOWNLOADS_DISABLED = "downloads_disabled"
    const val METADATA_HIDDEN = "metadata_hidden"
    const val ITEM_LIMIT_REACHED = "item_limit_reached"
}

/** What the first run imports (`PhotoSourceHistory`). Defaults: `all` for albums, `last_n` 20 for feeds. */
object PhotoSourceHistory {
    const val NONE = "none"
    const val LAST_N = "last_n"
    const val ALL = "all"
    const val MAX_COUNT = 10_000
    const val FEED_DEFAULT_COUNT = 20
}

/** What happens when a photo disappears upstream (`PhotoSourceDeletePolicy`). `mirror` for albums, `keep` for feeds. */
object PhotoSourceDeletePolicy {
    const val MIRROR = "mirror"
    const val KEEP = "keep"
}

/** The outside service a photo came from (`PhotoService`). */
object PhotoService {
    const val IMMICH = "immich"
    const val ICLOUD = "icloud"
    const val GOOGLE_PHOTOS = "google_photos"
    /** Mailed to an inbox address (docs/spec/email-inbox.md). */
    const val EMAIL = "email"
    /** Posted in a connected Telegram chat (docs/spec/telegram.md). */
    const val TELEGRAM = "telegram"
}

/** Websocket message types of the user websocket for photo sources (README §6.2). */
object PhotoSourceEvents {
    const val UPDATED = "photo_source.updated"
    const val REMOVED = "photo_source.removed"
    const val GOOGLE_IMPORT_UPDATED = "google_import.updated"
}

/** Push keys for photo sources (README §6.3). */
object PhotoSourcePush {
    /** The source's owner only: it needs attention, or has been in `error` for 3 days. */
    const val ATTENTION = "photo_source_attention"
    /** The importer, once, when an import finished while they had no websocket (`ids.import_id`). */
    const val GOOGLE_IMPORT_DONE = "google_import_done"
}

/**
 * The credit on items, sends and posts a source, a Google import, a mail or a Telegram chat made: "Anna via Immich".
 * For photo services [label] is the service name to show, not translated. For `email` and `telegram` ([isChannel])
 * it is the person who mailed or posted it, shown **instead of** the sender: "Jan via email" (the sender may be the
 * address's or chat's owner sending on Jan's behalf). Optional everywhere: clients that don't know it show the sender.
 */
@Serializable
data class ItemVia(
    val service: String,
    val label: String = "",
    /** The photo source that synced it, while it exists (null for Google imports and deleted sources). */
    val sourceId: String? = null,
    /** `email`: the inbox address it was mailed to, while it exists. */
    val addressId: String? = null,
    /** `telegram`: the Telegram chat it was posted in, while it exists. */
    val telegramChatId: String? = null,
) {
    /** Mail or Telegram: [label] names the person, the service is a localized word ("via email"). */
    val isChannel: Boolean get() = service == PhotoService.EMAIL || service == PhotoService.TELEGRAM

    /** The person to credit for [isChannel] credits; null for photo services or an empty label. */
    val personLabel: String? get() = if (isChannel) label.trim().takeIf { it.isNotEmpty() } else null

    /** The service name to show for photo services: the server's label, else a known service name. */
    val displayLabel: String
        get() = if (isChannel) label.trim() else label.trim().ifEmpty {
            when (service) {
                PhotoService.IMMICH -> "Immich"
                PhotoService.ICLOUD -> "iCloud"
                PhotoService.GOOGLE_PHOTOS -> "Google Photos"
                else -> service
            }
        }
}

/** Where a source's (or an import's) photos go: exactly one of an album (with its frame) or a feed. */
@Serializable
data class PhotoSourceTarget(
    val kind: String = KIND_ALBUM,
    val frameId: String? = null,
    val frameName: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    val feedId: String? = null,
    val feedName: String? = null,
) {
    val isFeed: Boolean get() = kind == KIND_FEED

    companion object {
        const val KIND_ALBUM = "album"
        const val KIND_FEED = "feed"
    }
}

@Serializable
data class PhotoSourceOwner(val userId: String, val displayName: String, val avatarUrl: String? = null)

@Serializable
data class PhotoSourceCounts(
    val upstream: Int? = null,
    val imported: Int = 0,
    val duplicates: Int = 0,
    val pending: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val removedLocally: Int = 0,
    val goneUpstream: Int = 0,
)

@Serializable
data class PhotoSourceRun(
    val startedAt: String,
    val finishedAt: String? = null,
    val added: Int = 0,
    val updated: Int = 0,
    val removed: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val bytes: Long = 0,
    val errorCode: String? = null,
)

/** A connection from one outside album to one album or feed. Never carries a secret. */
@Serializable
data class PhotoSource(
    val id: String,
    val provider: String,
    val name: String,
    val target: PhotoSourceTarget = PhotoSourceTarget(),
    /** Who set it up; imported photos are sent by them. Null when that account is gone. */
    val owner: PhotoSourceOwner? = null,
    val bestEffort: Boolean = false,
    /** Redacted link for display (links only, managers only). */
    val urlHint: String? = null,
    val serverUrl: String? = null,
    val immichAlbumId: String? = null,
    val hasApiKey: Boolean = false,
    val hasPassword: Boolean = false,
    val status: String = PhotoSourceStatus.ACTIVE,
    val running: Boolean = false,
    val warnings: List<String> = emptyList(),
    val lastErrorCode: String? = null,
    /** Short English detail for support; clients show their own text for [lastErrorCode]. */
    val lastError: String? = null,
    val errorSince: String? = null,
    /** Removals held back by the mass-removal guard. */
    val pendingRemovals: Int = 0,
    val history: String = PhotoSourceHistory.ALL,
    val historyCount: Int? = null,
    val onUpstreamDelete: String = PhotoSourceDeletePolicy.MIRROR,
    val includeVideos: Boolean = true,
    val notifyReactions: Boolean = true,
    val counts: PhotoSourceCounts = PhotoSourceCounts(),
    val lastRun: PhotoSourceRun? = null,
    val lastSyncedAt: String? = null,
    val lastAttemptAt: String? = null,
    /** Null while paused or waiting for a person. */
    val nextSyncAt: String? = null,
    val canManage: Boolean = false,
    val isOwner: Boolean = false,
    val createdAt: String? = null,
) {
    val isPaused: Boolean get() = status == PhotoSourceStatus.PAUSED
    val isImmich: Boolean get() = provider == PhotoSourceProvider.IMMICH_LINK || provider == PhotoSourceProvider.IMMICH_API
    val isLink: Boolean get() = provider == PhotoSourceProvider.IMMICH_LINK || provider == PhotoSourceProvider.ICLOUD_LINK
    /** The service name shown in credits: "Immich" or "iCloud". */
    val serviceLabel: String get() = if (provider == PhotoSourceProvider.ICLOUD_LINK) "iCloud" else "Immich"
}

/**
 * `PhotoSourceConnection`: how to reach the outside album. Encoded by hand so the write-only secrets are only sent
 * when set, and so this type can never be decoded from a response.
 */
data class PhotoSourceConnection(
    val provider: String,
    /** The shared link (links only). It is the secret. */
    val url: String? = null,
    /** `immich_api` only. */
    val serverUrl: String? = null,
    val apiKey: String? = null,
    /** `immich_link` only: the link password. */
    val password: String? = null,
    val immichAlbumId: String? = null,
) {
    fun toJson(): JsonObject = buildJsonObject { putConnection(this@PhotoSourceConnection) }

    override fun toString(): String =
        "PhotoSourceConnection(provider=$provider, serverUrl=$serverUrl, immichAlbumId=$immichAlbumId, secrets=hidden)"
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putConnection(c: PhotoSourceConnection) {
    put("provider", JsonPrimitive(c.provider))
    c.url?.takeIf { it.isNotBlank() }?.let { put("url", JsonPrimitive(it.trim())) }
    c.serverUrl?.takeIf { it.isNotBlank() }?.let { put("server_url", JsonPrimitive(it.trim())) }
    c.apiKey?.takeIf { it.isNotBlank() }?.let { put("api_key", JsonPrimitive(it.trim())) }
    c.password?.takeIf { it.isNotEmpty() }?.let { put("password", JsonPrimitive(it)) }
    c.immichAlbumId?.takeIf { it.isNotBlank() }?.let { put("immich_album_id", JsonPrimitive(it)) }
}

/** `PhotoSourceCreate`: the connection plus exactly one of [albumId] / [feedId] and the settings. */
data class PhotoSourceCreate(
    val connection: PhotoSourceConnection,
    val albumId: String? = null,
    val feedId: String? = null,
    val name: String? = null,
    val history: String? = null,
    /** Required with `history: last_n`. */
    val historyCount: Int? = null,
    val onUpstreamDelete: String? = null,
    val includeVideos: Boolean = true,
    val notifyReactions: Boolean = true,
) {
    fun toJson(): JsonObject = buildJsonObject {
        putConnection(connection)
        albumId?.let { put("album_id", JsonPrimitive(it)) }
        feedId?.let { put("feed_id", JsonPrimitive(it)) }
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { put("name", JsonPrimitive(it.take(60))) }
        history?.let { put("history", JsonPrimitive(it)) }
        if (history == PhotoSourceHistory.LAST_N) put("history_count", JsonPrimitive((historyCount ?: PhotoSourceHistory.FEED_DEFAULT_COUNT).coerceIn(1, PhotoSourceHistory.MAX_COUNT)))
        onUpstreamDelete?.let { put("on_upstream_delete", JsonPrimitive(it)) }
        put("include_videos", JsonPrimitive(includeVideos))
        put("notify_reactions", JsonPrimitive(notifyReactions))
    }
}

/**
 * `PhotoSourceUpdate`: omitted fields are kept. [removePassword] sends an explicit `password: null` (removes the link
 * password), which a plain data class encoding would drop.
 */
data class PhotoSourceUpdate(
    val name: String? = null,
    val paused: Boolean? = null,
    val url: String? = null,
    val serverUrl: String? = null,
    val apiKey: String? = null,
    val password: String? = null,
    val removePassword: Boolean = false,
    val immichAlbumId: String? = null,
    val onUpstreamDelete: String? = null,
    val includeVideos: Boolean? = null,
    val notifyReactions: Boolean? = null,
    val takeOver: Boolean? = null,
) {
    fun toJson(): JsonObject = buildJsonObject {
        name?.trim()?.takeIf { it.isNotEmpty() }?.let { put("name", JsonPrimitive(it.take(60))) }
        paused?.let { put("paused", JsonPrimitive(it)) }
        url?.takeIf { it.isNotBlank() }?.let { put("url", JsonPrimitive(it.trim())) }
        serverUrl?.takeIf { it.isNotBlank() }?.let { put("server_url", JsonPrimitive(it.trim())) }
        apiKey?.takeIf { it.isNotBlank() }?.let { put("api_key", JsonPrimitive(it.trim())) }
        when {
            !password.isNullOrEmpty() -> put("password", JsonPrimitive(password))
            removePassword -> put("password", JsonNull)
        }
        immichAlbumId?.takeIf { it.isNotBlank() }?.let { put("immich_album_id", JsonPrimitive(it)) }
        onUpstreamDelete?.let { put("on_upstream_delete", JsonPrimitive(it)) }
        includeVideos?.let { put("include_videos", JsonPrimitive(it)) }
        notifyReactions?.let { put("notify_reactions", JsonPrimitive(it)) }
        takeOver?.let { put("take_over", JsonPrimitive(it)) }
    }

    val isEmpty: Boolean get() = toJson().isEmpty()

    override fun toString(): String = "PhotoSourceUpdate(${toJson().keys})"
}

@Serializable
data class ImmichAlbum(
    val id: String,
    val name: String,
    val assetCount: Int = 0,
    val updatedAt: String? = null,
    val ownerName: String? = null,
    val shared: Boolean = false,
    /** A short-lived kinframe URL of the album thumbnail (proxied; the key never reaches the client). */
    val thumbnailUrl: String? = null,
)

@Serializable
data class PhotoSourceTestResult(
    val ok: Boolean,
    val errorCode: String? = null,
    val error: String? = null,
    val albumName: String? = null,
    val photos: Int? = null,
    val videos: Int? = null,
    val estimateBytes: Long? = null,
    val warnings: List<String> = emptyList(),
    val bestEffort: Boolean = false,
    val serverVersion: String? = null,
    /** `immich_api` without `immich_album_id`: the albums the key can read. */
    val albums: List<ImmichAlbum> = emptyList(),
)

@Serializable
data class ImmichAlbumList(
    val ok: Boolean,
    val errorCode: String? = null,
    val error: String? = null,
    val serverVersion: String? = null,
    val albums: List<ImmichAlbum> = emptyList(),
)

// ---------------------------------------------------------------------------------------------- Google Photos import

object GoogleImportStatus {
    const val NEEDS_AUTH = "needs_auth"
    const val PICKING = "picking"
    const val IMPORTING = "importing"
    const val DONE = "done"
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"
    const val EXPIRED = "expired"

    fun isFinal(status: String): Boolean = status == DONE || status == FAILED || status == CANCELLED || status == EXPIRED
}

object GoogleImportErrorCode {
    const val CONSENT_DENIED = "consent_denied"
    const val AUTH_FAILED = "auth_failed"
    const val PICKER_EXPIRED = "picker_expired"
    const val GOOGLE_UNAVAILABLE = "google_unavailable"
    const val TARGET_GONE = "target_gone"
    const val DOWNLOAD_FAILED = "download_failed"
    const val SERVER_ERROR = "server_error"
}

/** `GoogleImportCreate`: exactly one of [albumId] / [feedId]; [returnUri] gets `?import_id=…&status=…` added. */
@Serializable
data class GoogleImportCreate(
    val albumId: String? = null,
    val feedId: String? = null,
    val returnUri: String = RETURN_URI,
) {
    companion object {
        /** The apps' custom-scheme return (Android and iOS), like the OIDC redirect `app.kinframe:/callback`. */
        const val RETURN_URI = "app.kinframe:/google-import"
    }
}

@Serializable
data class GoogleImportProgress(
    val picked: Int = 0,
    val imported: Int = 0,
    val duplicates: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val bytes: Long = 0,
) {
    /** Items handled so far, for "9 of 23". */
    val handled: Int get() = imported + duplicates + skipped + failed
}

@Serializable
data class GoogleImport(
    val id: String,
    val status: String,
    val target: PhotoSourceTarget = PhotoSourceTarget(),
    /** `needs_auth` only: Google's consent page, to open in a browser session (never an embedded web view). */
    val authUrl: String? = null,
    /** `picking` only: Google's picker. */
    val pickerUri: String? = null,
    val pickerExpiresAt: String? = null,
    val progress: GoogleImportProgress = GoogleImportProgress(),
    /** The sends (and thus posts) the import made; unsend them to undo. */
    val sendIds: List<String> = emptyList(),
    val errorCode: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val finishedAt: String? = null,
) {
    val isFinal: Boolean get() = GoogleImportStatus.isFinal(status)
}
