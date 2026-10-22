package app.kinframe.family.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Small key/value store for secrets (refresh and ID tokens, dev tokens). */
interface SecureStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
    fun clear()
}

class MemorySecureStore : SecureStore {
    private val map = mutableMapOf<String, String>()
    @Synchronized override fun get(key: String) = map[key]
    @Synchronized override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    @Synchronized override fun clear() = map.clear()
}

/**
 * Tokens in EncryptedSharedPreferences (AES-256 keys in the Android Keystore). If the Keystore is broken on a
 * device, tokens stay in memory only: the person signs in again after a restart, which beats storing them in clear.
 */
class EncryptedSecureStore(context: Context, name: String = "kinframe_secure") : SecureStore {
    private val prefs: SharedPreferences? = try {
        @Suppress("DEPRECATION")
        EncryptedSharedPreferences.create(
            context,
            name,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.w("KinSecureStore", "encrypted preferences unavailable, keeping tokens in memory", e)
        null
    }
    private val fallback = MemorySecureStore()

    override fun get(key: String): String? = prefs?.getString(key, null) ?: fallback.get(key)

    override fun put(key: String, value: String?) {
        val p = prefs ?: return fallback.put(key, value)
        p.edit(commit = true) { if (value == null) remove(key) else putString(key, value) }
    }

    override fun clear() {
        prefs?.edit(commit = true) { clear() }
        fallback.clear()
    }
}
