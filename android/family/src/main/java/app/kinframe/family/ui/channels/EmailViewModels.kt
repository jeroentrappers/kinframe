package app.kinframe.family.ui.channels

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.EmailAddress
import app.kinframe.core.api.EmailAddressCreate
import app.kinframe.core.api.EmailAddressUpdate
import app.kinframe.core.api.EmailHeldMessage
import app.kinframe.core.api.EmailMessage
import app.kinframe.core.api.EmailPendingSender
import app.kinframe.core.api.EmailSender
import app.kinframe.core.api.EmailSenderApprove
import app.kinframe.core.api.EmailSenderCreate
import app.kinframe.core.api.EmailSenderUpdate
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.ProblemCode
import app.kinframe.family.R
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.ui.Load
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

sealed interface ChannelNotice {
    data class Message(@StringRes val message: Int, val args: List<String> = emptyList()) : ChannelNotice
    data class Error(@StringRes val message: Int) : ChannelNotice
    /** The address or chat is gone (deleted here or elsewhere): leave its screen. */
    data object Gone : ChannelNotice
    /** Open a Telegram link (the app if installed, else the browser). */
    data class OpenTelegram(val url: String) : ChannelNotice
    /** Share a Telegram link with someone ("Invite someone to send by Telegram"). */
    data class ShareTelegram(val url: String) : ChannelNotice
}

// ------------------------------------------------------------------------------------------------------ the list

data class EmailInboxState(
    val addresses: Load<List<EmailAddress>> = Load.Loading,
    val busy: Set<String> = emptySet(),
    val creating: Boolean = false,
    @StringRes val createError: Int? = null,
)

/** "Email photos to this frame/feed": the target's inbox addresses, kept current by `email_address.*` events. */
class EmailInboxViewModel(
    private val api: KinApi,
    val scope: ChannelScope,
    live: Flow<LiveEvent>,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(EmailInboxState())
    val state: StateFlow<EmailInboxState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<ChannelNotice>(extraBufferCapacity = 8)
    val events: SharedFlow<ChannelNotice> = _events.asSharedFlow()

    init {
        load()
        viewModelScope.launch {
            live.collect { e ->
                when (e) {
                    is LiveEvent.EmailAddressUpdated -> if (scope.holds(e.address.target)) upsert(e.address)
                    is LiveEvent.EmailAddressRemoved -> drop(e.addressId)
                    // A new pending sender changes the address's counts: the server follows with an update, but the
                    // badge should not wait for it.
                    is LiveEvent.EmailSenderPending -> if (has(e.addressId)) refresh(e.addressId)
                    else -> Unit
                }
            }
        }
    }

    private fun has(id: String) = (state.value.addresses as? Load.Ready)?.value?.any { it.id == id } == true

    fun load() = viewModelScope.launch {
        try {
            val list = if (scope.isFeed) api.feedEmailAddresses(scope.id) else api.frameEmailAddresses(scope.id)
            _state.update { it.copy(addresses = Load.Ready(list)) }
        } catch (e: Exception) {
            if (state.value.addresses !is Load.Ready) _state.update { it.copy(addresses = Load.Error(emailProblem(e))) }
        }
    }

    private fun refresh(id: String) = viewModelScope.launch { runCatching { api.emailAddress(id) }.onSuccess { upsert(it) } }

    fun upsert(a: EmailAddress) = _state.update { st ->
        val list = (st.addresses as? Load.Ready)?.value.orEmpty()
        st.copy(addresses = Load.Ready(if (list.any { it.id == a.id }) list.map { if (it.id == a.id) a else it } else list + a))
    }

    private fun drop(id: String) = _state.update { st ->
        (st.addresses as? Load.Ready)?.let { st.copy(addresses = Load.Ready(it.value.filterNot { a -> a.id == id })) } ?: st
    }

    /** A new address; [slug] empty lets the server make one from the target's name. */
    fun create(slug: String, name: String = "", onCreated: (EmailAddress) -> Unit = {}) {
        if (state.value.creating) return
        val s = EmailLogic.cleanSlug(slug.trim()).trim('-')
        if (s.isNotEmpty() && !EmailLogic.isValidSlug(s)) { _state.update { it.copy(createError = R.string.email_slug_invalid) }; return }
        _state.update { it.copy(creating = true, createError = null) }
        viewModelScope.launch {
            try {
                val req = EmailAddressCreate(slug = s.ifEmpty { null }, name = name.trim().take(60).ifEmpty { null })
                val a = if (scope.isFeed) api.createFeedEmailAddress(scope.id, req, newKey()) else api.createFrameEmailAddress(scope.id, req, newKey())
                upsert(a)
                _events.tryEmit(ChannelNotice.Message(R.string.email_created, listOf(a.address)))
                onCreated(a)
            } catch (e: Exception) {
                _state.update { it.copy(createError = emailProblem(e)) }
            } finally {
                _state.update { it.copy(creating = false) }
            }
        }
    }

    fun clearCreateError() = _state.update { it.copy(createError = null) }

    private fun run(a: EmailAddress, @StringRes done: Int?, call: suspend () -> EmailAddress?) {
        if (a.id in state.value.busy) return
        _state.update { it.copy(busy = it.busy + a.id) }
        viewModelScope.launch {
            try {
                call()?.let { upsert(it) }
                done?.let { _events.tryEmit(ChannelNotice.Message(it, listOf(a.address))) }
            } catch (e: Exception) {
                _events.tryEmit(ChannelNotice.Error(emailProblem(e)))
            } finally {
                _state.update { it.copy(busy = it.busy - a.id) }
            }
        }
    }

    fun setPaused(a: EmailAddress, paused: Boolean) =
        run(a, if (paused) R.string.email_paused_done else R.string.email_resumed_done) { api.updateEmailAddress(a.id, EmailAddressUpdate(paused = paused)) }
}

// ------------------------------------------------------------------------------------------------------ one address

data class EmailAddressState(
    val address: Load<EmailAddress> = Load.Loading,
    val senders: Load<List<EmailSender>> = Load.Loading,
    val pending: Load<List<EmailPendingSender>> = Load.Loading,
    val messages: Load<List<EmailMessage>> = Load.Loading,
    val nextCursor: String? = null,
    val loadingMore: Boolean = false,
    /** The held mail being previewed (sender, subject, auth, thumbnails). */
    val held: EmailHeldMessage? = null,
    val heldLoading: Boolean = false,
    /** Ids (address, senders, pending senders, held mail) with a call in flight. */
    val busy: Set<String> = emptySet(),
    @StringRes val senderError: Int? = null,
) {
    val addressOrNull: EmailAddress? get() = (address as? Load.Ready)?.value
}

/** One inbox address: settings, allowed and pending senders, held mail and recent mail. */
class EmailAddressViewModel(
    private val api: KinApi,
    val addressId: String,
    live: Flow<LiveEvent>,
) : ViewModel() {
    private val _state = MutableStateFlow(EmailAddressState())
    val state: StateFlow<EmailAddressState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<ChannelNotice>(extraBufferCapacity = 8)
    val events: SharedFlow<ChannelNotice> = _events.asSharedFlow()

    init {
        load()
        viewModelScope.launch {
            live.collect { e ->
                when (e) {
                    is LiveEvent.EmailAddressUpdated -> if (e.address.id == addressId) _state.update { it.copy(address = Load.Ready(e.address)) }
                    is LiveEvent.EmailAddressRemoved -> if (e.addressId == addressId) _events.tryEmit(ChannelNotice.Gone)
                    is LiveEvent.EmailSenderPending -> if (e.addressId == addressId) upsertPending(e.pending)
                    is LiveEvent.EmailSenderDecided -> if (e.addressId == addressId) { loadSenders(); loadPending() }
                    else -> Unit
                }
            }
        }
    }

    fun load() = viewModelScope.launch {
        val a = async { runCatching { api.emailAddress(addressId) } }
        loadSenders(); loadPending(); loadMessages()
        a.await().onSuccess { v -> _state.update { it.copy(address = Load.Ready(v)) } }.onFailure { e ->
            if (e is ApiException && e.status == 404) _events.tryEmit(ChannelNotice.Gone)
            else if (state.value.address !is Load.Ready) _state.update { it.copy(address = Load.Error(emailProblem(e))) }
        }
    }

    private fun loadSenders() = viewModelScope.launch {
        runCatching { api.emailSenders(addressId) }
            .onSuccess { v -> _state.update { it.copy(senders = Load.Ready(v)) } }
            .onFailure { e -> if (state.value.senders !is Load.Ready) _state.update { it.copy(senders = Load.Error(emailProblem(e))) } }
    }

    private fun loadPending() = viewModelScope.launch {
        runCatching { api.emailPendingSenders(addressId) }
            .onSuccess { v -> _state.update { it.copy(pending = Load.Ready(v)) } }
            .onFailure { e -> if (state.value.pending !is Load.Ready) _state.update { it.copy(pending = Load.Error(emailProblem(e))) } }
    }

    private fun loadMessages() = viewModelScope.launch {
        runCatching { api.emailMessages(addressId) }
            .onSuccess { p -> _state.update { it.copy(messages = Load.Ready(p.items), nextCursor = p.nextCursor) } }
            .onFailure { e -> if (state.value.messages !is Load.Ready) _state.update { it.copy(messages = Load.Error(emailProblem(e))) } }
    }

    fun loadMoreMessages() {
        val cursor = state.value.nextCursor ?: return
        if (state.value.loadingMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            try {
                val p = api.emailMessages(addressId, cursor)
                _state.update { st -> st.copy(messages = Load.Ready((st.messages as? Load.Ready)?.value.orEmpty() + p.items), nextCursor = p.nextCursor) }
            } catch (e: Exception) {
                _events.tryEmit(ChannelNotice.Error(emailProblem(e)))
            } finally {
                _state.update { it.copy(loadingMore = false) }
            }
        }
    }

    private fun upsertPending(p: EmailPendingSender) = _state.update { st ->
        val list = (st.pending as? Load.Ready)?.value.orEmpty()
        st.copy(pending = Load.Ready(listOf(p) + list.filterNot { it.id == p.id }))
    }

    private fun dropPending(id: String) = _state.update { st ->
        (st.pending as? Load.Ready)?.let { st.copy(pending = Load.Ready(it.value.filterNot { p -> p.id == id })) } ?: st
    }

    private fun upsertSender(s: EmailSender) = _state.update { st ->
        val list = (st.senders as? Load.Ready)?.value.orEmpty()
        st.copy(senders = Load.Ready(if (list.any { it.id == s.id }) list.map { if (it.id == s.id) s else it } else list + s))
    }

    /** Runs a call for [key] once at a time; [onDone] gets its result on success. */
    private fun <T> run(key: String, @StringRes done: Int?, args: List<String> = emptyList(), call: suspend () -> T, onDone: (T) -> Unit = {}) {
        if (key in state.value.busy) return
        _state.update { it.copy(busy = it.busy + key) }
        viewModelScope.launch {
            try {
                val r = call()
                onDone(r)
                done?.let { _events.tryEmit(ChannelNotice.Message(it, args)) }
            } catch (e: Exception) {
                _events.tryEmit(ChannelNotice.Error(emailProblem(e)))
                // Another manager decided first: show what is true now.
                if ((e as? ApiException)?.code == ProblemCode.ALREADY_DECIDED) { loadSenders(); loadPending() }
            } finally {
                _state.update { it.copy(busy = it.busy - key) }
            }
        }
    }

    private fun setAddress(a: EmailAddress) = _state.update { it.copy(address = Load.Ready(a)) }

    // ---- the address

    fun update(update: EmailAddressUpdate, @StringRes done: Int? = R.string.common_saved) =
        run(addressId, done, call = { api.updateEmailAddress(addressId, update) }, onDone = ::setAddress)

    fun setPaused(paused: Boolean) = update(EmailAddressUpdate(paused = paused), if (paused) R.string.email_paused_done_short else R.string.email_resumed_done_short)

    fun rename(name: String) {
        val n = name.trim().take(60)
        if (n.isEmpty() || n == state.value.addressOrNull?.name) return
        update(EmailAddressUpdate(name = n))
    }

    /** A new friendly part: the old address stops working at once. */
    fun changeSlug(slug: String) {
        val s = EmailLogic.cleanSlug(slug.trim()).trim('-')
        if (!EmailLogic.isValidSlug(s)) { _events.tryEmit(ChannelNotice.Error(R.string.email_slug_invalid)); return }
        if (s == state.value.addressOrNull?.slug) return
        update(EmailAddressUpdate(slug = s), R.string.email_slug_changed)
    }

    fun takeOver() = update(EmailAddressUpdate(takeOver = true), R.string.email_taken_over)

    /** A new random part: the old address stops working at once (asked first). */
    fun regenerate() = run(addressId, R.string.email_regenerated, call = { api.regenerateEmailAddress(addressId) }, onDone = ::setAddress)

    fun delete() = run(addressId, R.string.email_deleted, call = { api.deleteEmailAddress(addressId) }, onDone = { _events.tryEmit(ChannelNotice.Gone) })

    // ---- allowed and blocked senders

    fun addSender(email: String, name: String, blocked: Boolean = false, onAdded: () -> Unit = {}) {
        val e = email.trim()
        if (!EmailLogic.isValidEmail(e)) { _state.update { it.copy(senderError = R.string.email_sender_invalid) }; return }
        _state.update { it.copy(senderError = null) }
        if ("add" in state.value.busy) return
        _state.update { it.copy(busy = it.busy + "add") }
        viewModelScope.launch {
            try {
                val s = api.addEmailSender(addressId, EmailSenderCreate(e, name.trim().take(40).ifEmpty { null }, blocked))
                upsertSender(s)
                // Adding a pending sender approves (or blocks) them.
                loadPending()
                _events.tryEmit(ChannelNotice.Message(if (blocked) R.string.email_sender_blocked_done else R.string.email_sender_added, listOf(s.email)))
                onAdded()
            } catch (ex: Exception) {
                _state.update { it.copy(senderError = emailProblem(ex)) }
            } finally {
                _state.update { it.copy(busy = it.busy - "add") }
            }
        }
    }

    fun clearSenderError() = _state.update { it.copy(senderError = null) }

    fun renameSender(s: EmailSender, name: String) {
        val n = name.trim()
        if (n == s.displayName.orEmpty()) return
        run(s.id, R.string.common_saved, call = { api.updateEmailSender(addressId, s.id, EmailSenderUpdate(displayName = n.ifEmpty { null }, clearName = n.isEmpty())) }, onDone = ::upsertSender)
    }

    fun setBlocked(s: EmailSender, blocked: Boolean) =
        run(s.id, if (blocked) R.string.email_sender_blocked_done else R.string.email_sender_unblocked_done, listOf(s.email),
            call = { api.updateEmailSender(addressId, s.id, EmailSenderUpdate(blocked = blocked)) }, onDone = ::upsertSender)

    fun removeSender(s: EmailSender) = run(s.id, R.string.email_sender_removed, listOf(s.email), call = { api.removeEmailSender(addressId, s.id) }, onDone = {
        _state.update { st -> (st.senders as? Load.Ready)?.let { st.copy(senders = Load.Ready(it.value.filterNot { x -> x.id == s.id })) } ?: st }
    })

    // ---- pending senders and held mail

    /** Allow the sender; [deliver] their authenticated held mail now. */
    fun approve(p: EmailPendingSender, deliver: Boolean = true) =
        run(p.id, R.string.email_approved, listOf(p.displayName ?: p.email), call = { api.approveEmailSender(addressId, p.id, EmailSenderApprove(deliver)) }, onDone = { s ->
            dropPending(p.id); upsertSender(s); closeHeld(); loadMessages()
        })

    /** Delete their held mail silently; [block] drops their later mail too. */
    fun reject(p: EmailPendingSender, block: Boolean) =
        run(p.id, if (block) R.string.email_rejected_blocked else R.string.email_rejected, listOf(p.displayName ?: p.email),
            call = { api.rejectEmailSender(addressId, p.id, block) }, onDone = { dropPending(p.id); closeHeld(); if (block) loadSenders() })

    fun openHeld(messageId: String) {
        _state.update { it.copy(heldLoading = true) }
        viewModelScope.launch {
            try {
                val m = api.emailHeldMessage(addressId, messageId)
                _state.update { it.copy(held = m) }
            } catch (e: Exception) {
                _events.tryEmit(ChannelNotice.Error(emailProblem(e)))
            } finally {
                _state.update { it.copy(heldLoading = false) }
            }
        }
    }

    fun closeHeld() = _state.update { it.copy(held = null) }

    /** Deliver this one mail (also an unverified one); the sender stays pending. */
    fun deliverHeld(m: EmailHeldMessage) = run(m.id, R.string.email_held_delivered, call = { api.deliverEmailHeldMessage(addressId, m.id) }, onDone = {
        closeHeld(); loadPending(); loadMessages()
    })

    fun deleteHeld(m: EmailHeldMessage) = run(m.id, R.string.email_held_deleted, call = { api.deleteEmailHeldMessage(addressId, m.id) }, onDone = {
        closeHeld(); loadPending()
    })
}
