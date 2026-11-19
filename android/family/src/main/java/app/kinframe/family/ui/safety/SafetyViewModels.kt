package app.kinframe.family.ui.safety

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kinframe.core.api.ApiException
import app.kinframe.core.api.ProblemCode
import app.kinframe.core.social.Block
import app.kinframe.core.social.Report
import app.kinframe.core.social.SocialApi
import app.kinframe.family.R
import app.kinframe.family.data.LiveEvent
import app.kinframe.family.data.SocialLive
import app.kinframe.family.ui.Load
import app.kinframe.family.ui.social.SocialProblems
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface SafetyNotice {
    data class Message(@StringRes val message: Int, val arg: String? = null) : SafetyNotice
}

/** Settings → Blocked people. */
class BlocksViewModel(private val api: SocialApi) : ViewModel() {
    private val _state = MutableStateFlow<Load<List<Block>>>(Load.Loading)
    val state: StateFlow<Load<List<Block>>> = _state.asStateFlow()
    private val _events = MutableSharedFlow<SafetyNotice>(extraBufferCapacity = 4)
    val events: SharedFlow<SafetyNotice> = _events.asSharedFlow()

    init { load() }

    fun load() = viewModelScope.launch {
        try {
            _state.value = Load.Ready(api.blocks().sortedBy { it.person.displayName.lowercase() })
        } catch (e: Exception) {
            _state.value = Load.Error(SocialProblems.message(e))
        }
    }

    fun unblock(b: Block) = viewModelScope.launch {
        try {
            api.unblock(b.person.userId)
            _state.update { s -> (s as? Load.Ready)?.let { Load.Ready(it.value.filterNot { x -> x.person.userId == b.person.userId }) } ?: s }
            _events.tryEmit(SafetyNotice.Message(R.string.chat_unblocked_name, b.person.displayName))
        } catch (e: Exception) {
            _events.tryEmit(SafetyNotice.Message(SocialProblems.message(e)))
        }
    }
}

data class ReportsState(val reports: Load<List<Report>> = Load.Loading, val status: String = "open", val busy: Set<String> = emptySet())

/** A family's reports for its owner and admins, or ([feedId] null) the operator's queue. */
class ReportsViewModel(private val api: SocialApi, private val feedId: String?, live: Flow<LiveEvent> = emptyFlow()) : ViewModel() {
    private val _state = MutableStateFlow(ReportsState())
    val state: StateFlow<ReportsState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<SafetyNotice>(extraBufferCapacity = 4)
    val events: SharedFlow<SafetyNotice> = _events.asSharedFlow()

    init {
        load()
        viewModelScope.launch {
            live.collect { e -> if (e is SocialLive.ReportCreated && e.report.feedId == feedId) load() }
        }
    }

    fun load() = viewModelScope.launch {
        try {
            val all = if (feedId != null) api.feedReports(feedId) else api.operatorReports(state.value.status)
            val shown = if (feedId != null && state.value.status == "open") all.filter { it.isOpen } else all
            _state.update { it.copy(reports = Load.Ready(shown.sortedByDescending { r -> r.createdAt })) }
        } catch (e: Exception) {
            _state.update { it.copy(reports = Load.Error(SocialProblems.message(e))) }
        }
    }

    fun setStatus(status: String) {
        _state.update { it.copy(status = status) }
        load()
    }

    /** [action] `remove` (a moderator delete) or `dismiss`. */
    fun resolve(r: Report, action: String) = viewModelScope.launch {
        _state.update { it.copy(busy = it.busy + r.id) }
        try {
            val done = api.resolveReport(r.id, action)
            _state.update { s ->
                val list = (s.reports as? Load.Ready)?.value.orEmpty()
                s.copy(reports = Load.Ready(if (s.status == "open") list.filterNot { it.id == r.id } else list.map { if (it.id == r.id) done else it }))
            }
            _events.tryEmit(SafetyNotice.Message(if (action == "remove") R.string.chat_report_removed else R.string.chat_report_dismissed))
        } catch (e: Exception) {
            val code = (e as? ApiException)?.code
            _events.tryEmit(SafetyNotice.Message(if (code == ProblemCode.ALREADY_DECIDED) R.string.chat_report_already else SocialProblems.message(e)))
            if (code == ProblemCode.ALREADY_DECIDED) load()
        } finally {
            _state.update { it.copy(busy = it.busy - r.id) }
        }
    }
}
