package app.kinframe.frame.remotehelp

import app.kinframe.core.api.RemoteHelpReport
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * Remote help (docs/spec/remote-help.md): a temporary tailnet tunnel the server operator starts so they can use ADB
 * on the frame. Each distribution (build flavor) has its own `RemoteHelpManager`:
 *
 * - `github`: on a rooted frame, runs Tailscale in user-space mode and opens ADB over TCP for the session (§5.4).
 * - `play`: never offered; everything here does nothing.
 */
interface RemoteHelp {
    /** While a session runs: when it ends (epoch ms), for the badge "Remote help is on until 18:00". */
    val badge: StateFlow<Long?>

    /** Starts the worker: the boot-time clean-up or restart (§5.4 step 7) and the timer for `ends_at`. Call once. */
    fun start()

    /** A `remote_help` change from the feed (null = no session). Called inside the feed's transaction: only records it. */
    fun onChange(payload: JsonObject?)

    /** Heartbeat `remote_help`. */
    fun report(): RemoteHelpReport?

    /** Heartbeat `features` this build adds: `remote_help` when it can do it. */
    suspend fun features(): List<String>

    /** Heartbeat `serial`, sent only while the frame offers remote help: the server names the tailnet host after it. */
    fun serial(): String? = null

    /** Stop tapped at the frame (after the PIN, if one is set): tell the server, then clean up. */
    fun stopHere()

    /** The frame was unpaired: end any session and clean up. */
    suspend fun onUnpaired()
}
