package app.kinframe.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import app.kinframe.core.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the device credential encrypted with an AES-256-GCM key that lives in the Android Keystore
 * (hardware-backed where available). The ciphertext is written crash-safely (temp file, fsync, rename).
 *
 * We don't use androidx.security EncryptedSharedPreferences: it is deprecated and pulls in Tink. Some old
 * Keystore implementations fail on first use; in that case we keep the credential in app-private storage
 * (mode 0600) rather than bricking the frame, and log it — the threat model of a frame without inbound
 * listeners makes that an acceptable fallback.
 */
class KeystoreCredentialStore(context: Context, name: String = "device_credential") : CredentialStore {
    private val file = File(context.noBackupFilesDir, "$name.bin")
    private val alias = "kinframe.$name"

    @Synchronized
    override fun load(): DeviceCredential? {
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(String(AtomicFile.read(file), Charsets.UTF_8))
            val plain: String = when (json.optString("v")) {
                "ks1" -> {
                    val iv = Base64.decode(json.getString("iv"), Base64.NO_WRAP)
                    val ct = Base64.decode(json.getString("ct"), Base64.NO_WRAP)
                    val c = Cipher.getInstance(TRANSFORM)
                    c.init(Cipher.DECRYPT_MODE, key(create = false) ?: return null, GCMParameterSpec(128, iv))
                    String(c.doFinal(ct), Charsets.UTF_8)
                }
                "plain1" -> json.getString("data")
                else -> return null
            }
            val o = JSONObject(plain)
            DeviceCredential(o.getString("device_id"), o.getString("device_secret"))
        }.onFailure { Log.w(TAG, "credential unreadable", it) }.getOrNull()
    }

    @Synchronized
    override fun save(credential: DeviceCredential) {
        val plain = JSONObject().put("device_id", credential.deviceId).put("device_secret", credential.secret).toString()
        val out = try {
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.ENCRYPT_MODE, key(create = true))
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            JSONObject().put("v", "ks1")
                .put("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
                .put("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
        } catch (e: Exception) {
            Log.w(TAG, "Keystore unavailable, storing credential in private app storage", e)
            JSONObject().put("v", "plain1").put("data", plain)
        }
        AtomicFile.write(file, out.toString().toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    override fun clear() {
        file.delete()
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(alias) }
    }

    private fun key(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val TAG = "KinCredential"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}
