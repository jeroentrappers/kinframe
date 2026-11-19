package app.kinframe.family.accounts

import android.content.SharedPreferences
import androidx.core.content.edit
import app.kinframe.core.api.KinJson
import app.kinframe.core.servers.AccountIndex
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The list of accounts (docs/spec/multi-server.md §2.1): their local ids in order, and which one is primary. Each
 * account keeps its server, discovery and push endpoint in its own preferences file and its tokens in its own
 * encrypted store ([prefsName], [secureName]).
 *
 * Installs from before multi-account have one server in the app's own `kinframe` preferences and `kinframe_secure`
 * store: that becomes the account [MAIN] without moving anything, so nobody signs in again.
 */
class AccountStore(private val prefs: SharedPreferences) {

    /** Account ids, in the order they were added. */
    fun ids(): List<String> {
        migrate()
        return prefs.getString(KEY_IDS, null)?.let { runCatching { KinJson.decodeFromString(LIST, it) }.getOrNull() }.orEmpty()
    }

    fun primaryId(): String? = prefs.getString(KEY_PRIMARY, null)?.takeIf { it in ids() } ?: ids().firstOrNull()

    fun add(id: String) {
        val list = ids()
        if (id in list) return
        save(list + id, primaryId() ?: id)
    }

    fun remove(id: String) {
        val list = ids() - id
        save(list, primaryId().takeIf { it != id && it in list } ?: list.firstOrNull())
    }

    fun setPrimary(id: String) {
        if (id in ids()) prefs.edit(commit = true) { putString(KEY_PRIMARY, id) }
    }

    /** Ids primary first, then the others in the order they were added. */
    fun ordered(): List<String> {
        val p = primaryId() ?: return emptyList()
        return listOf(p) + (ids() - p)
    }

    private fun save(list: List<String>, primary: String?) = prefs.edit(commit = true) {
        putString(KEY_IDS, KinJson.encodeToString(LIST, list))
        if (primary != null) putString(KEY_PRIMARY, primary) else remove(KEY_PRIMARY)
    }

    /** A single-server install: its server becomes the account [MAIN]. */
    private fun migrate() {
        if (prefs.contains(KEY_IDS)) return
        val legacy = prefs.getString(LEGACY_SERVER_URL, null) != null
        save(if (legacy) listOf(MAIN) else emptyList(), if (legacy) MAIN else null)
    }

    /** The account index ([AccountIndex]) across restarts, so a notification tap after the process died still finds its server. */
    fun loadIndex(index: AccountIndex) {
        prefs.getString(KEY_INDEX, null)?.let { s -> runCatching { KinJson.decodeFromString(MAP, s) }.getOrNull()?.let(index::restore) }
    }

    fun saveIndex(index: AccountIndex) = prefs.edit { putString(KEY_INDEX, KinJson.encodeToString(MAP, index.snapshot())) }

    companion object {
        /** The account of an install from before multi-account (its files keep their old names). */
        const val MAIN = "main"
        private const val KEY_IDS = "accounts"
        private const val KEY_PRIMARY = "primary_account"
        private const val KEY_INDEX = "account_index"
        /** [app.kinframe.family.data.ServerRepository]'s key. */
        private const val LEGACY_SERVER_URL = "server_url"
        private val LIST = ListSerializer(String.serializer())
        private val MAP = MapSerializer(String.serializer(), String.serializer())

        fun prefsName(id: String) = if (id == MAIN) "kinframe" else "kinframe_account_$id"
        fun secureName(id: String) = if (id == MAIN) "kinframe_secure" else "kinframe_secure_$id"
    }
}
