package app.kinframe.family.ui.family

import android.content.SharedPreferences
import app.kinframe.core.api.Feed
import app.kinframe.core.api.FeedRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What this device remembers about the family app (docs/spec/simple-app.md §3.1, §6): Home's family filter, and the
 * simple view with whether it was asked about yet. Per device, not per account.
 */
class FamilyPrefs(private val prefs: SharedPreferences) {
    private val _filter = MutableStateFlow(prefs.getString(KEY_FILTER, null))
    /** Home's filter chip: one family's id, or null for All. */
    val homeFilter: StateFlow<String?> = _filter.asStateFlow()

    private val _simple = MutableStateFlow(prefs.getBoolean(KEY_SIMPLE, false))
    val simpleView: StateFlow<Boolean> = _simple.asStateFlow()

    private val _asked = MutableStateFlow(prefs.getBoolean(KEY_ASKED, false))
    /** "Normal view or simple view?" was answered (asked once after joining a family). */
    val viewAsked: StateFlow<Boolean> = _asked.asStateFlow()

    fun setHomeFilter(feedId: String?) {
        _filter.value = feedId
        prefs.edit().putString(KEY_FILTER, feedId).apply()
    }

    fun setSimpleView(on: Boolean) {
        _simple.value = on
        _asked.value = true
        prefs.edit().putBoolean(KEY_SIMPLE, on).putBoolean(KEY_ASKED, true).apply()
    }

    companion object {
        const val NAME = "kinframe_ui"
        private const val KEY_FILTER = "home_filter"
        private const val KEY_SIMPLE = "simple_view"
        private const val KEY_ASKED = "simple_view_asked"
    }
}

/** The person's families (docs/spec/simple-app.md §2.1, §3): all of them together, the home family first. */
object Families {
    /**
     * The home family (docs/spec/simple-app.md §3): the one the person posts to most (`FeedSummary.my_post_count`);
     * ties go to the one they created (they own it), else the oldest membership. `FeedSummary` has no join date,
     * so the family's own age stands in for it (nobody joins a family before it exists); then the name, so the
     * choice is stable.
     */
    fun home(feeds: List<Feed>): Feed? = feeds.sortedWith(
        compareByDescending<Feed> { it.myPostCount }
            .thenByDescending { it.role == FeedRole.OWNER }
            .thenBy { it.createdAt ?: "\uFFFF" }
            .thenBy { it.name.lowercase() }
            .thenBy { it.id },
    ).firstOrNull()

    /** The home family first, then the others by name. */
    fun ordered(feeds: List<Feed>, home: Feed?): List<Feed> =
        listOfNotNull(home) + feeds.filter { it.id != home?.id }.sortedBy { it.name.lowercase() }

    /** Home's filter while the person is still in that family; null is All. */
    fun filter(feeds: List<Feed>, saved: String?): String? = saved?.takeIf { id -> feeds.any { it.id == id } }

    /** The family of Home's people and frames row: the filtered one, with All the home family. */
    fun rowFamily(feeds: List<Feed>, filter: String?, home: Feed?): Feed? = feeds.firstOrNull { it.id == filter } ?: home

    /** A post sent to several families shows once on Home (one send, a post per family). */
    fun once(posts: List<app.kinframe.core.social.TimelinePost>): List<app.kinframe.core.social.TimelinePost> =
        posts.distinctBy { it.sendId ?: it.id }

    /** "Peeters family" from "Jan Peeters"; null when the name has no surname to go on. */
    fun surname(displayName: String?): String? {
        val parts = displayName.orEmpty().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        // "Jan van den Berg": keep the particles with the surname.
        val particles = setOf("van", "de", "der", "den", "het", "ter", "ten", "te", "von", "da", "di", "du", "le", "la", "'t")
        var start = parts.lastIndex
        while (start > 1 && parts[start - 1].lowercase() in particles) start--
        return parts.subList(start, parts.size).joinToString(" ")
    }

    /** The other people of a family by name, for the profile's names line. */
    fun names(names: List<String>, max: Int = 5): Pair<List<String>, Int> =
        names.take(max) to (names.size - max).coerceAtLeast(0)
}
