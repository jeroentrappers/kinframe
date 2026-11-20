package app.kinframe.frame.remotehelp

import app.kinframe.core.api.KinJson
import app.kinframe.core.api.RemoteHelpReport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import java.time.Instant

/*
 * The `remote_help` change payload (docs/spec/remote-help.md §5.2). Hand-written like the other device models; it
 * should move to the generated openapi model once the schema is in docs/api/openapi.yaml.
 */

/** One `remote_help` change: the session the operator started for this frame. */
@Serializable
data class RemoteHelpPayload(
    val sessionId: String,
    /** `active`; anything else is treated like `remote_help: null`. */
    val state: String = ACTIVE,
    val endsAt: String,
    val loginServer: String,
    /** The single-use pre-auth key, present only until the frame reported `on` or `failed`. */
    val authKey: String? = null,
    val hostname: String,
    /** The operator's ADB public key: one line of `adbkey.pub`. */
    val adbKey: String,
    val tailscale: TailscaleRelease,
) {
    val endsAtMs: Long get() = runCatching { Instant.parse(endsAt).toEpochMilli() }.getOrDefault(0L)

    /**
     * Why this payload can't be used, or null. Everything here ends up as an argument of a root shell script, so
     * besides the shape it rejects anything a shell or a config line could misread.
     */
    fun problem(): String? = when {
        !SAFE_ID.matches(sessionId) -> "bad session_id"
        state != ACTIVE -> "not active"
        endsAtMs == 0L -> "bad ends_at"
        !URL.matches(loginServer) -> "bad login_server"
        authKey != null && !SAFE_KEY.matches(authKey) -> "bad auth_key"
        !HOSTNAME.matches(hostname) -> "bad hostname"
        adbKey.isBlank() || adbKey.any { it == '\n' || it == '\r' || it == '\u0000' } -> "bad adb_key"
        !VERSION.matches(tailscale.version) -> "bad tailscale version"
        listOfNotNull(tailscale.arm, tailscale.arm64).isEmpty() -> "no tailscale binaries"
        listOfNotNull(tailscale.arm, tailscale.arm64).any { !URL.matches(it.url) || !SHA256.matches(it.sha256) } -> "bad tailscale binary"
        else -> null
    }

    companion object {
        const val ACTIVE = "active"
        private val SAFE_ID = Regex("[A-Za-z0-9_.:-]{1,128}")
        private val SAFE_KEY = Regex("[A-Za-z0-9_.:+/=-]{1,512}")
        private val HOSTNAME = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")
        private val VERSION = Regex("[0-9][0-9A-Za-z.+_-]{0,40}")
        private val URL = Regex("https?://[A-Za-z0-9._~:/?#\\[\\]@!$&()*+,;=%-]+")
        private val SHA256 = Regex("[0-9a-fA-F]{64}")

        /** Parses a change payload; null when it is absent or unreadable (both mean "no usable session"). */
        fun parse(raw: JsonObject?): RemoteHelpPayload? =
            raw?.let { runCatching { KinJson.decodeFromJsonElement<RemoteHelpPayload>(it) }.getOrNull() }
    }
}

@Serializable
data class TailscaleRelease(
    val version: String,
    val arm: TailscaleBinary? = null,
    val arm64: TailscaleBinary? = null,
) {
    fun forAbi(abi: String): TailscaleBinary? = when (abi) {
        TailscaleAbi.ARM64 -> arm64
        TailscaleAbi.ARM -> arm
        else -> null
    }
}

@Serializable
data class TailscaleBinary(val url: String, val sha256: String)

/** Tailscale's names for the two CPU architectures frames have. */
object TailscaleAbi {
    const val ARM = "arm"
    const val ARM64 = "arm64"

    /** From `Build.SUPPORTED_ABIS` (most preferred first): what the frame's user space runs best. */
    fun pick(supportedAbis: List<String>): String? = supportedAbis.firstNotNullOfOrNull {
        when (it) {
            "arm64-v8a" -> ARM64
            "armeabi-v7a", "armeabi" -> ARM
            else -> null
        }
    }
}

/** What the frame keeps across restarts (`files/remote-help/state.json`). */
@Serializable
data class RemoteHelpRecord(
    /** The session the server wants (the last `remote_help` change), without its auth key; null when none. */
    val session: RemoteHelpPayload? = null,
    /** The pre-auth key until it was used (or the session failed); never longer. */
    val authKey: String? = null,
    /** What the heartbeat says. */
    val report: RemoteHelpReport? = null,
    /** Sessions that ended here (Stop, failed, expired): a late change for one of them doesn't start it again. */
    val ended: List<String> = emptyList(),
    /** When the downloaded binaries in /data/local/tmp/kinframe-ts may go (7 days after the last session); 0 = keep. */
    val cacheUntil: Long = 0,
)
