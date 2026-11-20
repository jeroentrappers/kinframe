package app.kinframe.family.ui.family

import androidx.compose.runtime.compositionLocalOf
import app.kinframe.core.api.Feed

/** The person's families and what every screen can do with them (docs/spec/simple-app.md §3). */
class FamilyScope(
    /** Every family, the home family first; empty before they are loaded or without one. */
    val all: List<Feed>,
    /** The family the person posts to most: the composer's default. */
    val home: Feed?,
    /** Home's filter chip (a family id), or null for All. */
    val filter: String?,
    val setFilter: (String?) -> Unit,
    /** A family's invite share sheet. */
    val invite: (Feed) -> Unit,
    /** The composer, to the filtered family (with All: the home family). */
    val compose: () -> Unit,
    /** "Start a family" (getting started's name step). */
    val start: () -> Unit = {},
) {
    /** Several families: chips on Home, a family label on posts, a list on the Family tab. */
    val several: Boolean get() = all.size > 1

    fun byId(id: String?): Feed? = all.firstOrNull { it.id == id }

    companion object {
        val NONE = FamilyScope(emptyList(), null, null, {}, {}, {})
    }
}

val LocalFamily = compositionLocalOf { FamilyScope.NONE }
