package app.kinframe.frame.data

import android.content.Context
import androidx.core.content.edit

/** Small key/value settings that must be available before the database (server, language, setup progress). */
class FramePrefs(context: Context) {
    private val sp = context.getSharedPreferences("frame", Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = sp.getString("server_url", null)
        set(v) = sp.edit(commit = true) { putString("server_url", v) }

    var apiBase: String?
        get() = sp.getString("api_base", null)
        set(v) = sp.edit(commit = true) { putString("api_base", v) }

    var webUrl: String?
        get() = sp.getString("web_url", null)
        set(v) = sp.edit(commit = true) { putString("web_url", v) }

    var serverName: String?
        get() = sp.getString("server_name", null)
        set(v) = sp.edit(commit = true) { putString("server_name", v) }

    var language: String?
        get() = sp.getString("language", null)
        set(v) = sp.edit(commit = true) { putString("language", v) }

    /** Last `locale.language` seen in the server settings (null until the frame is claimed). */
    var serverLanguage: String?
        get() = sp.getString("server_language", null)
        set(v) = sp.edit(commit = true) { putString("server_language", v) }

    /** Highest setup step the person reached (so a power cut mid-setup resumes there). */
    var setupStep: Int
        get() = sp.getInt("setup_step", 0)
        set(v) = sp.edit(commit = true) { putInt("setup_step", v) }

    var claimed: Boolean
        get() = sp.getBoolean("claimed", false)
        set(v) = sp.edit(commit = true) { putBoolean("claimed", v) }

    var ownerName: String?
        get() = sp.getString("owner_name", null)
        set(v) = sp.edit(commit = true) { putString("owner_name", v) }

    var playlistSeed: Long
        get() = sp.getLong("playlist_seed", 0L)
        set(v) = sp.edit { putLong("playlist_seed", v) }

    var playlistCurrent: String?
        get() = sp.getString("playlist_current", null)
        set(v) = sp.edit { putString("playlist_current", v) }

    /** Forget everything tied to the device credential; keeps language and server choice. */
    fun wipeDevice() = sp.edit(commit = true) {
        remove("claimed"); remove("owner_name"); remove("server_language"); remove("playlist_seed"); remove("playlist_current")
        putInt("setup_step", 0)
    }
}
