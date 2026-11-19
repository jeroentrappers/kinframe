package app.kinframe.family.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.core.content.edit
import app.kinframe.core.api.AppVersionPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the version policy asks of this build (README §5.15). */
sealed interface UpdateState {
    data object None : UpdateState
    /** Below `recommended_build`: a banner that can be dismissed (per recommended build). */
    data class Available(val recommendedBuild: Int, val storeUrl: String?) : UpdateState
    /** Below `minimum_build` (or the server answered 426): a full-screen page; nothing else is usable. */
    data class Required(val storeUrl: String?) : UpdateState
}

object UpdatePolicy {
    /**
     * [forced]: the server answered `426 app_update_required` and no discovery read since cleared it.
     * [dismissedBuild]: the recommended build whose banner the person dismissed.
     */
    fun decide(policy: AppVersionPolicy?, build: Int, dismissedBuild: Int?, forced: Boolean = false): UpdateState {
        val store = policy?.storeUrl?.takeIf { it.isNotBlank() }
        val min = policy?.minimumBuild
        if (forced || (min != null && build < min)) return UpdateState.Required(store)
        val rec = policy?.recommendedBuild
        if (rec != null && build < rec && dismissedBuild != rec) return UpdateState.Available(rec, store)
        return UpdateState.None
    }

    /** Where Update goes: the operator's store URL, else the Play Store app, else the Play Store web page. */
    fun storeUris(storeUrl: String?, applicationId: String): List<String> = listOfNotNull(
        storeUrl?.takeIf { it.isNotBlank() },
        "market://details?id=$applicationId",
        "https://play.google.com/store/apps/details?id=$applicationId",
    )
}

/** Remembers which recommended build's banner was dismissed. */
interface DismissStore {
    var dismissedBuild: Int?
}

class PrefsDismissStore(private val prefs: SharedPreferences) : DismissStore {
    override var dismissedBuild: Int?
        get() = prefs.getInt(KEY, -1).takeIf { it >= 0 }
        set(v) = prefs.edit { if (v == null) remove(KEY) else putInt(KEY, v) }

    private companion object { const val KEY = "update_banner_dismissed_build" }
}

/**
 * The family app's version policy: discovery `app_versions.android` against [build]. Discovery is re-read on start, on
 * returning to the foreground (at most every [throttleMs]) and right after any 426 (README §5.15).
 */
class AppUpdates(
    private val build: Int,
    policy: StateFlow<AppVersionPolicy?>,
    private val dismissals: DismissStore,
    /** Re-reads discovery; true when it was read. */
    private val refreshDiscovery: suspend () -> Boolean,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val throttleMs: Long = 60 * 60 * 1000L,
) {
    private val forced = MutableStateFlow(false)
    private val dismissed = MutableStateFlow(dismissals.dismissedBuild)
    private val _checking = MutableStateFlow(false)
    /** "Check again" is reading discovery. */
    val checking: StateFlow<Boolean> = _checking.asStateFlow()
    @Volatile private var lastCheck: Long? = null
    /** Bumped on every 426, so a discovery read that started before it does not lift the block. */
    private val forcedAt = java.util.concurrent.atomic.AtomicInteger()

    val state: StateFlow<UpdateState> = combine(policy, dismissed, forced) { p, d, f -> UpdatePolicy.decide(p, build, d, f) }
        .stateIn(scope, SharingStarted.Eagerly, UpdatePolicy.decide(policy.value, build, dismissed.value, false))

    /** App start and every return to the foreground; throttled. */
    fun onForeground() {
        val t = now()
        // While blocked every return counts: the operator may have lowered the minimum.
        if (state.value !is UpdateState.Required) lastCheck?.let { if (t - it < throttleMs) return }
        lastCheck = t
        scope.launch { check() }
    }

    /** A request got `426 app_update_required`: block now, and re-read discovery for the store link. */
    fun onUpdateRequired() {
        forcedAt.incrementAndGet()
        val already = forced.value
        forced.value = true
        lastCheck = now()
        if (!already || !_checking.value) scope.launch { check() }
    }

    /** "Check again" on the update page. */
    fun checkAgain() {
        if (_checking.value) return
        lastCheck = now()
        scope.launch { check() }
    }

    private suspend fun check() {
        _checking.value = true
        val gen = forcedAt.get()
        try {
            // A fresh discovery document is the authority again: it says whether this build is still below the minimum.
            if (refreshDiscovery() && forcedAt.get() == gen) forced.value = false
        } finally {
            _checking.value = false
        }
    }

    /** × on the banner: hidden until a newer recommended build. */
    fun dismiss() {
        val s = state.value as? UpdateState.Available ?: return
        dismissals.dismissedBuild = s.recommendedBuild
        dismissed.update { s.recommendedBuild }
    }
}

/** Opens the first of [UpdatePolicy.storeUris] that something on the phone can handle. */
fun Context.openStore(storeUrl: String?) {
    for (uri in UpdatePolicy.storeUris(storeUrl, packageName)) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}
