package app.kinframe.family.ui.sources

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.ImmichAlbum
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.PhotoSource
import app.kinframe.core.api.PhotoSourceConnection
import app.kinframe.core.api.PhotoSourceCreate
import app.kinframe.core.api.PhotoSourceDeletePolicy
import app.kinframe.core.api.PhotoSourceErrorCode
import app.kinframe.core.api.PhotoSourceHistory
import app.kinframe.core.api.PhotoSourceLinks
import app.kinframe.core.api.PhotoSourceProvider
import app.kinframe.core.api.PhotoSourceTestResult
import app.kinframe.core.api.ProblemCode
import app.kinframe.core.api.ServerAddress
import app.kinframe.core.api.SourceLink
import app.kinframe.family.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** The steps of "Sync from…": which service, how to reach it, (Immich server) which album, and the settings. */
enum class SourceStep { Provider, Connection, Album, Options }

data class AddSourceState(
    val target: SourceTarget,
    /** Providers the server allows (discovery `photo_source_providers`). */
    val providers: List<String>,
    /** The server may reach home-network addresses (discovery `photo_sources_private_networks`). */
    val privateNetworks: Boolean,
    val step: SourceStep = SourceStep.Provider,
    val provider: String? = null,
    val link: String = "",
    val password: String = "",
    val serverUrl: String = "",
    val apiKey: String = "",
    val testing: Boolean = false,
    val test: PhotoSourceTestResult? = null,
    @StringRes val testError: Int? = null,
    val testErrorArgs: List<Any> = emptyList(),
    val albums: List<ImmichAlbum> = emptyList(),
    val albumId: String? = null,
    val name: String = "",
    val history: String = if (target.isFeed) PhotoSourceHistory.LAST_N else PhotoSourceHistory.ALL,
    val historyCount: Int = PhotoSourceHistory.FEED_DEFAULT_COUNT,
    val onUpstreamDelete: String = if (target.isFeed) PhotoSourceDeletePolicy.KEEP else PhotoSourceDeletePolicy.MIRROR,
    val includeVideos: Boolean = true,
    val notifyReactions: Boolean = true,
    /** Turn on "Only in album mode" for the target album when connecting (suggested for big first imports). */
    val albumOnly: Boolean = false,
    val creating: Boolean = false,
    @StringRes val createError: Int? = null,
    val created: PhotoSource? = null,
) {
    /** What is wrong with the typed connection, before anything is sent; null when it looks usable. */
    @get:StringRes
    val connectionIssue: Int?
        get() = when (provider) {
            PhotoSourceProvider.IMMICH_LINK -> when (val l = PhotoSourceLinks.parse(link)) {
                null -> if (link.isBlank()) R.string.source_need_link else R.string.source_link_invalid
                is SourceLink.ImmichShare -> null
                is SourceLink.ICloudSharedAlbum -> R.string.source_link_is_icloud
                else -> R.string.source_link_not_immich
            }
            PhotoSourceProvider.ICLOUD_LINK -> when (PhotoSourceLinks.parse(link)) {
                null -> if (link.isBlank()) R.string.source_need_link else R.string.source_link_invalid
                is SourceLink.ICloudSharedAlbum -> null
                is SourceLink.ICloudPhotosLink -> R.string.source_err_unsupported_link
                is SourceLink.ICloudIncomplete -> R.string.source_link_icloud_incomplete
                is SourceLink.ImmichShare -> R.string.source_link_is_immich
                else -> R.string.source_link_not_icloud
            }
            PhotoSourceProvider.IMMICH_API -> when (PhotoSourceLinks.serverAddress(serverUrl)) {
                ServerAddress.Invalid -> if (serverUrl.isBlank()) R.string.source_need_server else R.string.source_server_invalid
                ServerAddress.NeedsHttps -> R.string.source_server_https
                is ServerAddress.Ok -> if (apiKey.trim().length < 8) R.string.source_need_key else null
            }
            else -> R.string.source_need_provider
        }

    /** A home-network address on a server that can't reach it: explained, not blocked (the server decides). */
    val privateHint: Boolean
        get() = !privateNetworks && when (provider) {
            PhotoSourceProvider.IMMICH_LINK -> (PhotoSourceLinks.parse(link) as? SourceLink.ImmichShare)?.privateHost == true
            PhotoSourceProvider.IMMICH_API -> (PhotoSourceLinks.serverAddress(serverUrl) as? ServerAddress.Ok)?.privateHost == true
            else -> false
        }

    val bestEffort: Boolean get() = provider == PhotoSourceProvider.ICLOUD_LINK

    val connection: PhotoSourceConnection?
        get() = when (provider) {
            PhotoSourceProvider.IMMICH_LINK -> PhotoSourceConnection(provider, url = PhotoSourceLinks.firstUrl(link), password = password.ifEmpty { null })
            PhotoSourceProvider.ICLOUD_LINK -> PhotoSourceConnection(provider, url = PhotoSourceLinks.firstUrl(link))
            PhotoSourceProvider.IMMICH_API -> (PhotoSourceLinks.serverAddress(serverUrl) as? ServerAddress.Ok)?.let {
                PhotoSourceConnection(provider, serverUrl = it.url, apiKey = apiKey.trim(), immichAlbumId = albumId)
            }
            else -> null
        }

    val album: ImmichAlbum? get() = albums.firstOrNull { it.id == albumId }

    /** How many photos the first sync would bring in, when the test told us the album's size. */
    val firstImportCount: Int?
        get() {
            val total = album?.assetCount ?: test?.let { t -> if (t.photos == null && t.videos == null) null else (t.photos ?: 0) + (if (includeVideos) t.videos ?: 0 else 0) } ?: return null
            return when (history) {
                PhotoSourceHistory.NONE -> 0
                PhotoSourceHistory.LAST_N -> minOf(total, historyCount)
                else -> total
            }
        }

    /**
     * Suggest "Only in album mode" when the first import would add more than [ALBUM_ONLY_SUGGEST_ABOVE] photos to an
     * album that isn't album-only yet, so a big archive doesn't flood the everyday slideshow (photo-sources.md §9.1).
     */
    val suggestAlbumOnly: Boolean
        get() = !target.isFeed && !target.albumOnly && (firstImportCount ?: 0) > ALBUM_ONLY_SUGGEST_ABOVE

    companion object {
        const val ALBUM_ONLY_SUGGEST_ABOVE = 200
    }
    val canCreate: Boolean get() = step == SourceStep.Options && !creating && connectionIssue == null
}

/**
 * "Sync from…" on an album (album managers) or "Add source" on a feed (owner/admins): pick the service, paste a link or
 * enter a server and key, test it, choose an album (Immich server), set what the first sync imports and what happens
 * when a photo disappears, then connect. [link] pre-fills a link shared into the app from another app.
 */
class AddSourceViewModel(
    private val api: KinApi,
    target: SourceTarget,
    providers: List<String>,
    privateNetworks: Boolean,
    link: String? = null,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(AddSourceState(target, providers.filter { it in PhotoSourceProvider.all }, privateNetworks))
    val state: StateFlow<AddSourceState> = _state.asStateFlow()

    /** One key for the whole wizard: a retried "Connect" never makes two sources. */
    private val idempotencyKey by lazy(newKey)

    init {
        val p = state.value.providers
        val parsed = PhotoSourceLinks.parse(link)
        val detected = parsed?.provider?.takeIf { it in p }
        when {
            detected != null -> _state.update { it.copy(provider = detected, link = parsed.url, step = SourceStep.Connection) }
            p.size == 1 -> _state.update { it.copy(provider = p.single(), step = SourceStep.Connection, link = parsed?.url.orEmpty()) }
            parsed != null -> _state.update { it.copy(link = parsed.url) }
        }
    }

    fun choose(provider: String) {
        if (provider !in state.value.providers) return
        _state.update { it.copy(provider = provider, step = SourceStep.Connection, test = null, testError = null, albums = emptyList(), albumId = null) }
    }

    /** One step back; false when already at the first step (the screen closes). */
    fun back(): Boolean {
        val s = state.value
        val prev = when (s.step) {
            SourceStep.Provider -> return false
            SourceStep.Connection -> if (s.providers.size > 1) SourceStep.Provider else return false
            SourceStep.Album -> SourceStep.Connection
            SourceStep.Options -> if (s.provider == PhotoSourceProvider.IMMICH_API) SourceStep.Album else SourceStep.Connection
        }
        _state.update { it.copy(step = prev, createError = null) }
        return true
    }

    private fun edited(f: (AddSourceState) -> AddSourceState) = _state.update { f(it).copy(test = null, testError = null, testErrorArgs = emptyList()) }

    fun onLink(v: String) = edited { it.copy(link = v.take(2048)) }
    fun onPassword(v: String) = edited { it.copy(password = v.take(200)) }
    fun onServer(v: String) = edited { it.copy(serverUrl = v.take(2048)) }
    fun onKey(v: String) = edited { it.copy(apiKey = v.take(200)) }

    /** Test connection: reads the album's name and size; for an Immich server, the albums to pick from. */
    fun test() {
        val s = state.value
        if (s.testing || s.connectionIssue != null) return
        val conn = s.connection?.copy(immichAlbumId = null) ?: return
        _state.update { it.copy(testing = true, testError = null, testErrorArgs = emptyList()) }
        viewModelScope.launch {
            try {
                val r = api.testPhotoSource(conn)
                if (!r.ok) {
                    val (msg, args) = SourceStatus.errorText(r.errorCode ?: "", null)
                    _state.update { it.copy(testing = false, test = null, testError = if (r.errorCode == null) R.string.source_err_unknown else msg, testErrorArgs = args) }
                    return@launch
                }
                _state.update {
                    if (it.provider == PhotoSourceProvider.IMMICH_API) {
                        it.copy(testing = false, test = r, albums = r.albums, albumId = it.albumId?.takeIf { id -> r.albums.any { a -> a.id == id } }, step = SourceStep.Album)
                    } else {
                        it.copy(testing = false, test = r, name = it.name.ifBlank { r.albumName.orEmpty() }, step = SourceStep.Options)
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(testing = false, testError = sourceProblem(e)) }
            }
        }
    }

    /** Immich server: which album. */
    fun pickAlbum(id: String) {
        val a = state.value.albums.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(albumId = a.id, name = a.name) }
    }

    fun continueFromAlbum() {
        if (state.value.albumId == null) return
        _state.update { it.copy(step = SourceStep.Options) }
    }

    fun setName(v: String) = _state.update { it.copy(name = v.take(60)) }

    fun setHistory(option: String, count: Int? = null) = _state.update {
        it.copy(history = option, historyCount = (count ?: it.historyCount).coerceIn(1, PhotoSourceHistory.MAX_COUNT))
    }

    fun setDeletePolicy(v: String) = _state.update { it.copy(onUpstreamDelete = v) }
    fun setIncludeVideos(v: Boolean) = _state.update { it.copy(includeVideos = v) }
    fun setNotifyReactions(v: Boolean) = _state.update { it.copy(notifyReactions = v) }
    fun setAlbumOnly(v: Boolean) = _state.update { it.copy(albumOnly = v) }

    /** Turns on "Only in album mode" for the target album (best effort: the source is connected either way). */
    private suspend fun markAlbumOnly(t: SourceTarget) {
        val frameId = t.frameId ?: return
        val albumId = t.albumId ?: return
        runCatching {
            val a = api.albums(frameId).firstOrNull { it.id == albumId } ?: return
            if (!a.onlyInAlbumMode) api.updateAlbum(frameId, albumId, a.version, app.kinframe.core.api.AlbumWrite(onlyInAlbumMode = true).toJson())
        }
    }

    /** Connect: the server tests again and starts the first sync. */
    fun create() {
        val s = state.value
        if (!s.canCreate) return
        val conn = s.connection ?: return
        if (conn.provider == PhotoSourceProvider.IMMICH_API && conn.immichAlbumId == null) return
        _state.update { it.copy(creating = true, createError = null) }
        viewModelScope.launch {
            try {
                val created = api.createPhotoSource(PhotoSourceCreate(
                    conn, albumId = s.target.albumId.takeIf { !s.target.isFeed }, feedId = s.target.feedId,
                    name = s.name.trim().takeIf { it.isNotEmpty() && it != (s.album?.name ?: s.test?.albumName) },
                    history = s.history, historyCount = s.historyCount.takeIf { s.history == PhotoSourceHistory.LAST_N },
                    onUpstreamDelete = s.onUpstreamDelete, includeVideos = s.includeVideos, notifyReactions = s.notifyReactions,
                ), idempotencyKey)
                if (s.albumOnly && s.suggestAlbumOnly) markAlbumOnly(s.target)
                _state.update { it.copy(creating = false, created = created) }
            } catch (e: Exception) {
                val a = e as? ApiException
                when {
                    // The link stopped working between the test and now, or a private address is blocked: back to it.
                    a?.code == ProblemCode.SOURCE_TEST_FAILED -> {
                        val (msg, args) = SourceStatus.errorText(a.problem?.detail ?: "", null)
                        _state.update { it.copy(creating = false, step = SourceStep.Connection, test = null, testError = msg, testErrorArgs = args) }
                    }
                    a?.code == ProblemCode.VALIDATION_FAILED -> _state.update {
                        it.copy(creating = false, step = SourceStep.Connection, test = null,
                            testError = if (it.privateNetworks) R.string.source_problem_address else SourceStatus.simpleError(PhotoSourceErrorCode.PRIVATE_ADDRESS_BLOCKED))
                    }
                    else -> _state.update { it.copy(creating = false, createError = sourceProblem(e)) }
                }
            }
        }
    }
}
