package app.kinframe.frame.social

import android.util.Log
import app.kinframe.core.api.FramePhotoRequest
import app.kinframe.core.api.FramePrompt
import app.kinframe.core.api.FramePrompts
import app.kinframe.core.api.FrameSocialSettings
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.PlacementComment
import app.kinframe.core.api.PlacementComments
import app.kinframe.frame.FrameController
import app.kinframe.frame.birthdays.BirthdayLogic
import app.kinframe.frame.data.CommentCardShownEntity
import app.kinframe.frame.schedule.ScreenMode
import app.kinframe.frame.sync.FrameSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject

/** The cards that may show over the photo that just appeared (family-social.md §8, §9.3). */
data class SlideCards(
    /** The stage they belong to; they go when another slide comes in. */
    val seq: Long,
    /** "This week in Peeters: Your view right now" before the first answer of a run. */
    val prompt: FramePrompt? = null,
    /** The newest comment, at most once per photo per day. */
    val comment: PlacementComment? = null,
)

/** "New photos from Ben" (and Anna, and others): shown on the next wake after they arrived. */
data class NewPhotosNote(val names: List<String>)

/**
 * The frame side of family social: "Ask for new photos" in the quick menu, the `photo_request` it follows, "New photos
 * from Ben" on the next wake, the comment card and the weekly prompt's title card. Everything it shows comes from the
 * change feed mirror in Room, so it works offline after a reboot. Nothing here changes the display mode or decodes
 * anything: the cards are plain text on the slideshow's overlay layer.
 */
class SocialController(private val c: FrameController) {
    private val g = c.g

    val settings: StateFlow<FrameSocialSettings> by lazy {
        g.settings.raw.map { FrameSocialSettings.of(it) }.distinctUntilChanged().stateIn(c.scope, SharingStarted.Eagerly, FrameSocialSettings.DEFAULT)
    }

    /** The frame's latest request (`photo_request` change or the answer to our own tap). */
    val photoRequest: StateFlow<FramePhotoRequest?> by lazy {
        c.syncState.map { it?.photoRequestJson }.distinctUntilChanged()
            .map { json -> json?.let { runCatching { FramePhotoRequest.parse(KinJson.parseToJsonElement(it).jsonObject) }.getOrNull() } }
            .stateIn(c.scope, SharingStarted.Eagerly, null)
    }

    /** The current weekly prompts of the frame's families, by prompt id. */
    val prompts: StateFlow<Map<String, FramePrompt>> by lazy {
        c.syncState.map { it?.promptsJson }.distinctUntilChanged()
            .map { json ->
                json?.let { runCatching { FramePrompts.parse(KinJson.parseToJsonElement(it).jsonObject) }.getOrNull() }
                    ?.prompts?.associateBy { it.promptId } ?: emptyMap()
            }
            .stateIn(c.scope, SharingStarted.Eagerly, emptyMap())
    }

    /** Re-evaluated every minute, so "Asked today 💛" turns back into the button when the day is over. */
    private val minute = MutableStateFlow(0L)

    /**
     * Until when the button stays hidden because the server answered `404`/`501` (it has no "Ask for new photos" yet;
     * the device API has no discovery flag to tell beforehand).
     */
    private val unavailableUntil = MutableStateFlow(0L)

    /** The quick menu button, or null when it is hidden (setting off, server without it, or no session). */
    val ask: StateFlow<AskState?> by lazy {
        combine(settings, photoRequest, c.syncState.map { it?.photoRequestAskedAt }.distinctUntilChanged(), c.session, minute) { s, req, askedAt, session, _ ->
            val now = g.clock.nowMs()
            if (!s.askForPhotos || session == null || now < unavailableUntil.value) null else SocialLogic.askState(req, askedAt, now)
        }.distinctUntilChanged().stateIn(c.scope, SharingStarted.Eagerly, null)
    }

    /** The calm line after a tap: asked (online) or will ask (offline); null when there is nothing to say. */
    private val _confirmation = MutableStateFlow<AskConfirmation?>(null)
    val confirmation: StateFlow<AskConfirmation?> = _confirmation
    private var confirmationJob: Job? = null

    private val _cards = MutableStateFlow<SlideCards?>(null)
    val cards: StateFlow<SlideCards?> = _cards

    private val _note = MutableStateFlow<NewPhotosNote?>(null)
    val note: StateFlow<NewPhotosNote?> = _note

    fun start() {
        c.scope.launch {
            while (true) {
                minute.value = g.clock.nowMs()
                delay(60_000 - g.clock.nowMs() % 60_000 + 50)
            }
        }
        c.scope.launch {
            c.session.collect { s ->
                s?.outbox?.onPhotoRequestUnavailable = {
                    unavailableUntil.value = g.clock.nowMs() + UNAVAILABLE_HIDE_MS
                    minute.value = g.clock.nowMs()
                }
            }
        }
        watchSlides()
        watchNote()
    }

    /** "Ask for new photos": queued through the outbox (sent at once when online), then a short confirmation. */
    fun askForPhotos() {
        if (ask.value != AskState.Ready) return
        val session = c.session.value ?: return
        c.scope.launch {
            val queued = session.outbox.photoRequest()
            if (!queued) return@launch
            val online = c.connection.value == FrameSession.Connection.Online
            confirmationJob?.cancel()
            _confirmation.value = if (online) AskConfirmation.Asked else AskConfirmation.WillAsk
            confirmationJob = c.scope.launch { delay(CONFIRMATION_MS); _confirmation.value = null }
        }
    }

    fun dismissConfirmation() {
        confirmationJob?.cancel()
        _confirmation.value = null
    }

    // ------------------------------------------------------------------------------------ cards on the photo

    /** Prompt of the slide before the current one: a run of answers to the same prompt gets one title card. */
    private var previousPromptId: String? = null
    private var lastSeq = -1L

    private fun watchSlides() {
        c.scope.launch {
            // A slide counts as "appeared" once its transition has finished (no outgoing slide any more).
            c.slideshow.stage.map { st -> st.incoming?.takeIf { st.outgoing == null }?.let { st.seq to it } }
                .distinctUntilChanged { a, b -> a?.first == b?.first }
                .collectLatest { shown ->
                    _cards.value = null
                    val (seq, slide) = shown ?: return@collectLatest
                    if (seq == lastSeq) return@collectLatest
                    lastSeq = seq
                    val p = slide.placement
                    // Collages and pairs show several photos: no card that could be read as belonging to the wrong one.
                    val single = slide.tiles.isEmpty()
                    val prompt = if (single) SocialLogic.promptCard(settings.value.promptCards, p.promptId, previousPromptId, prompts.value) else null
                    previousPromptId = if (single) p.promptId else null
                    val comment = if (single && settings.value.showComments) commentFor(p.id) else null
                    if (prompt == null && comment == null) return@collectLatest
                    _cards.value = SlideCards(seq, prompt, comment)
                    delay(SocialLogic.CARD_MS)
                    if (_cards.value?.seq == seq) _cards.value = null
                }
        }
    }

    /** The newest comment of [placementId] when its card may show now; records that it did (once per photo per day). */
    private suspend fun commentFor(placementId: String): PlacementComment? {
        if (c.display.mode.value != ScreenMode.Awake || c.slideshow.paused.value) return null
        val date = BirthdayLogic.trustedDate(g.clock.nowMs(), g.clock.zone, g.time.clockWrong.value) ?: return null
        return withContext(Dispatchers.IO) {
            val doc = g.db.social().comments(placementId) ?: return@withContext null
            val newest = runCatching { PlacementComments.parse(KinJson.parseToJsonElement(doc.json).jsonObject) }.getOrNull()?.newest
                ?: return@withContext null
            val day = date.toString()
            val fresh = g.db.tx {
                g.db.social().pruneCardsShown(date.minusDays(1).toString())
                g.db.social().markCardShown(CommentCardShownEntity(placementId, day)) != -1L
            }
            if (fresh) newest else null
        }
    }

    // ------------------------------------------------------------------------------------ "New photos from Ben"

    private fun watchNote() {
        c.scope.launch {
            combine(
                c.syncState.map { it?.newPhotosFrom }.distinctUntilChanged(),
                c.display.mode.map { it == ScreenMode.Awake }.distinctUntilChanged(),
                settings.map { it.newPhotosFrom }.distinctUntilChanged(),
            ) { json, awake, enabled -> Triple(SocialLogic.names(json), awake, enabled) }
                .collectLatest { (names, awake, enabled) ->
                    if (names.isEmpty()) { _note.value = null; return@collectLatest }
                    if (!enabled) { clearNames(names); return@collectLatest }
                    // Asleep: keep them for the wake. Awake: give the wake (or the new photo) a moment, then say who.
                    if (!awake) { _note.value = null; return@collectLatest }
                    delay(NOTE_DELAY_MS)
                    _note.value = NewPhotosNote(names)
                    delay(SocialLogic.NOTE_MS)
                    _note.value = null
                    clearNames(names)
                }
        }
    }

    private suspend fun clearNames(shown: List<String>) {
        runCatching {
            withContext(Dispatchers.IO) {
                g.db.tx {
                    val st = g.db.syncState().get() ?: return@tx
                    g.db.syncState().put(st.copy(newPhotosFrom = SocialLogic.removeNames(st.newPhotosFrom, shown)))
                }
            }
        }.onFailure { Log.w(TAG, "clearing the new photos note", it) }
    }

    fun clear() {
        _cards.value = null
        _note.value = null
        dismissConfirmation()
        previousPromptId = null
    }

    companion object {
        private const val TAG = "KinSocial"
        const val CONFIRMATION_MS = 6_000L
        private const val NOTE_DELAY_MS = 1_500L
        /** A server without the feature is asked again after this long (it may have been updated meanwhile). */
        private const val UNAVAILABLE_HIDE_MS = 6 * 3_600_000L
    }
}

enum class AskConfirmation { Asked, WillAsk }
