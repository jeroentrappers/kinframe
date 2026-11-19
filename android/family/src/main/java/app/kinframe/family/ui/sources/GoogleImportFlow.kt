package app.kinframe.family.ui.sources

import androidx.annotation.StringRes
import app.kinframe.core.api.GoogleImport
import app.kinframe.core.api.GoogleImportCreate
import app.kinframe.core.api.GoogleImportErrorCode
import app.kinframe.core.api.GoogleImportStatus
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.ProblemCode
import app.kinframe.family.R
import app.kinframe.family.data.LiveEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URLDecoder
import java.util.UUID

/** Where "Add from Google Photos" is (photo-sources.md §5.3, §7.3). */
enum class GooglePhase { Idle, Starting, Consent, Picking, Importing, Done, Failed, Expired, Cancelled, Error }

data class GoogleImportState(
    val target: SourceTarget? = null,
    val import: GoogleImport? = null,
    val starting: Boolean = false,
    val cancelling: Boolean = false,
    /** Couldn't start (feature off, rate limited, offline). */
    @StringRes val error: Int? = null,
    /** The import's sends were unsent ("Undo"). */
    val undone: Boolean = false,
) {
    val phase: GooglePhase
        get() = when {
            error != null -> GooglePhase.Error
            starting -> GooglePhase.Starting
            import == null -> GooglePhase.Idle
            else -> when (import.status) {
                GoogleImportStatus.NEEDS_AUTH -> GooglePhase.Consent
                GoogleImportStatus.PICKING -> GooglePhase.Picking
                GoogleImportStatus.IMPORTING -> GooglePhase.Importing
                GoogleImportStatus.DONE -> GooglePhase.Done
                GoogleImportStatus.FAILED -> GooglePhase.Failed
                GoogleImportStatus.EXPIRED -> GooglePhase.Expired
                GoogleImportStatus.CANCELLED -> GooglePhase.Cancelled
                else -> GooglePhase.Importing
            }
        }

    val active: Boolean get() = phase != GooglePhase.Idle
}

/** A browser session or app to open; the screen decides how (Custom Tab, the Google Photos app). */
sealed interface GoogleOpen {
    /** Google's consent page: always a real browser session (Google refuses embedded web views). */
    data class Consent(val url: String) : GoogleOpen
    /** Google's picker: the Google Photos app when installed, else a Custom Tab. */
    data class Picker(val url: String) : GoogleOpen
}

/**
 * One "Add from Google Photos" at a time, for the whole app (it outlives screens: the person leaves for the browser and
 * Google Photos and comes back through the `app.kinframe:/google-import` redirect or by switching apps).
 *
 * start → `needs_auth` (open the consent page) → redirect back → `picking` (open the picker; the server waits for
 * Google) → `importing` (progress, by polling and `google_import.updated`) → `done` / `failed` / `expired`;
 * cancel from any non-final state.
 */
class GoogleImportFlow(
    private val api: () -> KinApi?,
    private val scope: CoroutineScope,
    live: Flow<LiveEvent>,
    private val pollMs: Long = 3_000,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    private val _state = MutableStateFlow(GoogleImportState())
    val state: StateFlow<GoogleImportState> = _state.asStateFlow()

    private val _opens = MutableSharedFlow<GoogleOpen>(extraBufferCapacity = 4)
    val opens: SharedFlow<GoogleOpen> = _opens.asSharedFlow()

    private val _finished = MutableSharedFlow<GoogleImport>(extraBufferCapacity = 4)
    /** Imports that just finished with photos: the album or feed reloads. */
    val finished: SharedFlow<GoogleImport> = _finished.asSharedFlow()

    private var consentOpened: String? = null
    private var pickerOpened: String? = null
    private var poller: Job? = null

    init {
        scope.launch {
            live.collect { e -> if (e is LiveEvent.GoogleImportUpdated && e.import.id == state.value.import?.id) apply(e.import) }
        }
    }

    /** "Add from Google Photos" into an album or a feed. */
    fun start(target: SourceTarget) {
        val a = api() ?: return
        if (state.value.starting) return
        stopPolling()
        consentOpened = null; pickerOpened = null
        _state.value = GoogleImportState(target = target, starting = true)
        scope.launch {
            try {
                val g = a.startGoogleImport(GoogleImportCreate(albumId = target.albumId.takeIf { !target.isFeed }, feedId = target.feedId), newKey())
                _state.update { it.copy(starting = false) }
                apply(g)
            } catch (e: Exception) {
                val code = (e as? app.kinframe.core.api.ApiException)?.code
                _state.update {
                    it.copy(starting = false, error = when (code) {
                        ProblemCode.FEATURE_DISABLED -> R.string.google_error_disabled
                        else -> sourceProblem(e)
                    })
                }
            }
        }
    }

    /**
     * Back from Google's consent through `app.kinframe:/google-import?import_id=…&status=…`. The query is only a hint:
     * the import is fetched again (and is ours only if the server says so).
     */
    fun onReturn(uri: String) {
        val q = query(uri)
        val id = q["import_id"] ?: state.value.import?.id ?: return
        val current = state.value.import
        if (current != null && current.id != id) return
        refresh(id)
    }

    /** The app is in front again (back from the browser or Google Photos): catch up. */
    fun onResume() {
        val g = state.value.import ?: return
        if (!g.isFinal) refresh(g.id)
    }

    /** A notification tap (`google_import_done`) or a restart: show that import. */
    fun resume(importId: String) {
        if (state.value.import?.id != importId) {
            // Don't jump into Google again for an import someone left: they can tap "Open Google Photos".
            consentOpened = importId; pickerOpened = importId
            _state.value = GoogleImportState()
        }
        refresh(importId)
    }

    /** After a restart: the newest unfinished import of the last hour, if the app was closed while picking. */
    fun recover() {
        if (state.value.active) return
        val a = api() ?: return
        scope.launch {
            val g = runCatching { a.myGoogleImports() }.getOrNull()
                ?.firstOrNull { it.status == GoogleImportStatus.PICKING || it.status == GoogleImportStatus.IMPORTING } ?: return@launch
            if (state.value.active) return@launch
            consentOpened = g.id; pickerOpened = g.id
            apply(g)
        }
    }

    /** "Open Google Photos" / "Sign in with Google" again (the person closed the tab, or nothing happened). */
    fun reopen() {
        val g = state.value.import ?: return
        when (g.status) {
            GoogleImportStatus.NEEDS_AUTH -> g.authUrl?.let { _opens.tryEmit(GoogleOpen.Consent(it)) }
            GoogleImportStatus.PICKING -> g.pickerUri?.let { _opens.tryEmit(GoogleOpen.Picker(it)) }
        }
    }

    fun cancel() {
        val g = state.value.import ?: run { dismiss(); return }
        if (g.isFinal || state.value.cancelling) return
        val a = api() ?: return
        _state.update { it.copy(cancelling = true) }
        scope.launch {
            try {
                apply(a.cancelGoogleImport(g.id))
            } catch (e: Exception) {
                // Finished meanwhile (409): show what it became.
                runCatching { a.googleImport(g.id) }.getOrNull()?.let { apply(it) }
            } finally {
                _state.update { it.copy(cancelling = false) }
            }
        }
    }

    /** Undo a finished import: unsend its sends (photo-sources.md §7.2 "Cancel"). */
    fun undo() {
        val g = state.value.import ?: return
        val a = api() ?: return
        if (g.sendIds.isEmpty() || state.value.undone) return
        scope.launch {
            g.sendIds.forEach { runCatching { a.unsend(it) } }
            _state.update { it.copy(undone = true) }
        }
    }

    /** Close the sheet. A running import goes on at the server; its result shows next time or as a notification. */
    fun dismiss() {
        stopPolling()
        _state.value = GoogleImportState()
    }

    private fun refresh(id: String) {
        val a = api() ?: return
        scope.launch { runCatching { a.googleImport(id) }.onSuccess { apply(it) } }
    }

    private fun apply(g: GoogleImport) {
        val before = state.value.import
        if (before != null && before.id == g.id && before.isFinal && !g.isFinal) return // never go back from a final state
        _state.update { it.copy(import = g, target = it.target ?: SourceTarget.of(g.target), error = null) }
        when (g.status) {
            GoogleImportStatus.NEEDS_AUTH -> if (consentOpened != g.id) g.authUrl?.let { consentOpened = g.id; _opens.tryEmit(GoogleOpen.Consent(it)) }
            GoogleImportStatus.PICKING -> if (pickerOpened != g.id) g.pickerUri?.let { pickerOpened = g.id; _opens.tryEmit(GoogleOpen.Picker(it)) }
        }
        if (g.isFinal) {
            stopPolling()
            if (before?.isFinal != true && g.progress.imported > 0) _finished.tryEmit(g)
        } else if (g.status == GoogleImportStatus.PICKING || g.status == GoogleImportStatus.IMPORTING) {
            ensurePolling(g.id)
        }
    }

    private fun ensurePolling(id: String) {
        if (poller?.isActive == true) return
        poller = scope.launch {
            while (isActive) {
                delay(pollMs)
                val cur = state.value.import ?: break
                if (cur.id != id || cur.isFinal) break
                val a = api() ?: break
                runCatching { a.googleImport(id) }.onSuccess { apply(it) }
            }
        }
    }

    private fun stopPolling() {
        poller?.cancel()
        poller = null
    }

    companion object {
        /** `app.kinframe:/google-import`, the return the server redirects to (README §5.14). */
        fun isReturn(uri: String?): Boolean =
            uri != null && uri.startsWith("app.kinframe:", ignoreCase = true) && uri.substringAfter(':').trimStart('/').startsWith("google-import")

        internal fun query(uri: String): Map<String, String> =
            uri.substringAfter('?', "").substringBefore('#').split('&').filter { it.contains('=') }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }

        /** The words for a failed import. */
        @StringRes
        fun errorText(code: String?): Int = when (code) {
            GoogleImportErrorCode.CONSENT_DENIED -> R.string.google_error_consent
            GoogleImportErrorCode.PICKER_EXPIRED -> R.string.google_error_expired
            GoogleImportErrorCode.TARGET_GONE -> R.string.google_error_target_gone
            GoogleImportErrorCode.DOWNLOAD_FAILED -> R.string.google_error_download
            GoogleImportErrorCode.GOOGLE_UNAVAILABLE -> R.string.google_error_unavailable
            GoogleImportErrorCode.AUTH_FAILED -> R.string.google_error_auth
            else -> R.string.google_error_generic
        }
    }
}
