package app.kinframe.frame.kiosk

import android.util.Base64
import app.kinframe.core.api.PinHashDto
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Owner PIN hashing (FrameSettings `kiosk.owner_pin_hash`, schema `PinHash`): PBKDF2-HMAC-SHA256 (RFC 8018) over the
 * PIN digits with a random salt. Implemented on `HmacSHA256` because `PBKDF2WithHmacSHA256` only exists from API 26.
 * The PIN itself is never stored or sent anywhere.
 */
object OwnerPin {
    const val ALG = "pbkdf2-sha256"
    const val ITERATIONS = 20_000
    const val MIN_LENGTH = 4
    const val MAX_LENGTH = 6
    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16

    fun isValid(pin: String) = pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }

    /** Hashes [pin] with a fresh salt (slow by design: call off the main thread). */
    fun hash(pin: String, random: SecureRandom = SecureRandom(), iterations: Int = ITERATIONS, b64: Codec = Codec.Android): PinHashDto {
        require(isValid(pin)) { "PIN must be 4–6 digits" }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val key = pbkdf2Sha256(pin.toByteArray(Charsets.UTF_8), salt, iterations, KEY_BYTES)
        return PinHashDto(ALG, iterations, b64.encode(salt), b64.encode(key))
    }

    /** Constant-time check of [pin] against [h]; false for unknown algorithms or malformed records. */
    fun verify(h: PinHashDto, pin: String, b64: Codec = Codec.Android): Boolean {
        if (h.alg != ALG || h.iterations !in 1..1_000_000 || pin.isEmpty()) return false
        val salt = runCatching { b64.decode(h.salt) }.getOrNull() ?: return false
        val want = runCatching { b64.decode(h.hash) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return false
        val got = pbkdf2Sha256(pin.toByteArray(Charsets.UTF_8), salt, h.iterations, want.size)
        return MessageDigest.isEqual(got, want)
    }

    /** RFC 8018 §5.2 with HMAC-SHA256 as the PRF. */
    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, keyBytes: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // An empty key is not allowed by SecretKeySpec; PINs are never empty, test vectors neither.
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = mac.macLength
        val blocks = (keyBytes + hLen - 1) / hLen
        val out = ByteArray(keyBytes)
        val u = ByteArray(hLen)
        val t = ByteArray(hLen)
        for (i in 1..blocks) {
            mac.update(salt)
            mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            mac.doFinal(u, 0)
            u.copyInto(t)
            repeat(iterations - 1) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (k in 0 until hLen) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            val off = (i - 1) * hLen
            t.copyInto(out, off, 0, minOf(hLen, keyBytes - off))
        }
        return out
    }

    /** Base64 codec: android.util.Base64 on the device, java.util.Base64 in JVM unit tests. */
    interface Codec {
        fun encode(b: ByteArray): String
        fun decode(s: String): ByteArray

        object Android : Codec {
            override fun encode(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
            override fun decode(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)
        }
    }
}

/**
 * Rate limit for PIN attempts at the frame: [FREE_ATTEMPTS] wrong PINs in a row are free, then every further wrong
 * PIN locks the keypad for 30 s, doubling up to 15 min. A correct PIN resets it. The state is persisted, so
 * restarting the frame doesn't reset the count.
 */
class PinAttempts(private val store: Store, private val now: () -> Long) {
    interface Store {
        var failures: Int
        var lockedUntil: Long
    }

    sealed interface State {
        /** [left] wrong tries remain before the first lockout (0 = every wrong try now locks). */
        data class Open(val left: Int) : State
        data class Locked(val untilMs: Long) : State
    }

    fun state(): State {
        val until = store.lockedUntil
        return if (until > now()) State.Locked(until) else State.Open((FREE_ATTEMPTS - store.failures).coerceAtLeast(0))
    }

    fun onFailure(): State {
        val f = store.failures + 1
        store.failures = f
        if (f >= FREE_ATTEMPTS) store.lockedUntil = now() + lockoutMs(f)
        return state()
    }

    fun onSuccess() {
        store.failures = 0
        store.lockedUntil = 0
    }

    companion object {
        const val FREE_ATTEMPTS = 5
        const val BASE_LOCK_MS = 30_000L
        const val MAX_LOCK_MS = 15 * 60_000L

        /** Lockout after the [failures]-th wrong PIN in a row (only called once failures ≥ [FREE_ATTEMPTS]). */
        fun lockoutMs(failures: Int): Long {
            val steps = (failures - FREE_ATTEMPTS).coerceIn(0, 10)
            return (BASE_LOCK_MS shl steps).coerceAtMost(MAX_LOCK_MS)
        }
    }
}
