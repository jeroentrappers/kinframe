package app.kinframe.frame.kiosk

import android.os.SystemClock
import android.util.Log
import app.kinframe.core.api.PinHashDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * The owner gate (product spec §5 Security: "the user cannot leave the app without an owner PIN or adb").
 *
 * Without a PIN everything is granted at once. With a PIN, opening settings, leaving the kiosk, power off/restart and
 * Wi-Fi changes ask for it via [request], which the UI shows as the PIN keypad. A correct PIN keeps the gate open
 * for [GRACE_MS] (extended by [touch] while settings are in use) so one visit to settings asks only once.
 */
class OwnerGate(
    private val pinHash: () -> PinHashDto?,
    private val attempts: PinAttempts,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val verifier: suspend (PinHashDto, String) -> Boolean = { h, p -> withContext(Dispatchers.Default) { OwnerPin.verify(h, p) } },
) {
    enum class Reason { OpenSettings, ExitKiosk, Wifi, PowerOff, Restart, ChangeServer, FactoryReset, ExternalStorage }

    class Request(val reason: Reason, val onGranted: () -> Unit)

    sealed interface Result {
        data object Granted : Result
        data class Wrong(val left: Int) : Result
        data class Locked(val untilMs: Long) : Result
    }

    private val _request = MutableStateFlow<Request?>(null)
    val request: StateFlow<Request?> = _request
    private var unlockedUntil = 0L

    val pinSet: Boolean get() = pinHash() != null
    fun attemptState(): PinAttempts.State = attempts.state()

    fun authorize(reason: Reason, onGranted: () -> Unit) {
        if (!pinSet || isUnlocked()) {
            onGranted()
            return
        }
        Log.i(TAG, "PIN needed for $reason")
        _request.value = Request(reason, onGranted)
    }

    fun isUnlocked() = unlockedUntil != 0L && elapsed() < unlockedUntil

    /** Checks [pin] for the pending request; runs its action when correct. */
    suspend fun submit(pin: String): Result {
        val h = pinHash()
        val pending = _request.value
        if (h == null) {
            // The PIN was removed remotely while the keypad was open.
            grant(pending)
            return Result.Granted
        }
        (attempts.state() as? PinAttempts.State.Locked)?.let { return Result.Locked(it.untilMs) }
        return if (verifier(h, pin)) {
            attempts.onSuccess()
            grant(pending)
            Result.Granted
        } else when (val s = attempts.onFailure()) {
            is PinAttempts.State.Locked -> Result.Locked(s.untilMs)
            is PinAttempts.State.Open -> Result.Wrong(s.left)
        }
    }

    private fun grant(pending: Request?) {
        unlockedUntil = elapsed() + GRACE_MS
        _request.value = null
        pending?.onGranted?.invoke()
    }

    fun cancel() { _request.value = null }

    /** Activity in settings keeps an open gate open. */
    fun touch() { if (isUnlocked()) unlockedUntil = elapsed() + GRACE_MS }

    /** Closing settings locks again. */
    fun lock() { unlockedUntil = 0L }

    companion object {
        private const val TAG = "KinOwnerGate"
        const val GRACE_MS = 2 * 60_000L
    }
}
