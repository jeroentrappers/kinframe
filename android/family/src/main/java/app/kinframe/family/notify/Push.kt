package app.kinframe.family.notify

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.PushEndpointCreate
import app.kinframe.core.api.WebPushKeys
import app.kinframe.family.BuildConfig
import app.kinframe.family.FamilyApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage as UpMessage
import java.util.Locale

/** A push payload from the server: only ids plus a localized title and body (README §6.3). */
data class PushMessage(val type: String, val title: String, val body: String, val ids: Map<String, String>) {
    companion object {
        fun parse(bytes: ByteArray): PushMessage? = runCatching {
            val o = KinJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
            val ids = (o["ids"]?.jsonObject ?: emptyMap()).mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }.toMap()
            PushMessage(s("type"), s("title"), s("body"), ids)
        }.getOrNull()
    }
}

sealed interface PushStatus {
    /** No UnifiedPush distributor is installed (and no FCM in this build): notifications only while the app is open. */
    data object Unavailable : PushStatus
    /** Several distributors are installed and none is chosen yet. */
    data object NeedsChoice : PushStatus
    data object Registering : PushStatus
    data class Active(val via: String) : PushStatus
}

/**
 * Registers this phone for push: UnifiedPush first (no Google dependency), FCM only in builds made with
 * `-Pkinframe.fcm=true`. The endpoint goes to `POST /v1/me/push-endpoints` and is removed again on sign-out.
 */
class PushRegistrar(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val api: () -> KinApi?,
) {
    private val _status = MutableStateFlow<PushStatus>(PushStatus.Unavailable)
    val status: StateFlow<PushStatus> = _status.asStateFlow()

    /** Call when signed in (app start, after sign-in). Never asks the person anything. */
    fun ensureRegistered() {
        if (BuildConfig.FCM_ENABLED && UnifiedPush.getDistributors(context).isEmpty()) {
            _status.value = PushStatus.Registering
            FcmPush.start(context) { token -> onEndpoint("fcm", token, null) }
            return
        }
        val saved = UnifiedPush.getAckDistributor(context) ?: UnifiedPush.getSavedDistributor(context)
        val all = UnifiedPush.getDistributors(context)
        when {
            saved != null -> register()
            all.size == 1 -> { UnifiedPush.saveDistributor(context, all.single()); register() }
            all.size > 1 -> _status.value = PushStatus.NeedsChoice
            else -> _status.value = PushStatus.Unavailable
        }
        // Re-send a known endpoint in case the server lost it (it is idempotent on kind + endpoint).
        prefs.getString(KEY_ENDPOINT, null)?.let { onEndpoint(prefs.getString(KEY_KIND, KIND_UP)!!, it, null) }
    }

    /** Lets the person pick the push app (system picker or the default distributor). */
    fun choose(activity: Activity) {
        UnifiedPush.tryPickDistributor(activity) { ok -> if (ok) register() }
    }

    private fun register() {
        _status.value = PushStatus.Registering
        runCatching { UnifiedPush.register(context, messageForDistributor = "kinframe") }
            .onFailure { Log.w(TAG, "UnifiedPush register failed", it); _status.value = PushStatus.Unavailable }
    }

    fun onEndpoint(kind: String, endpoint: String, keys: WebPushKeys?) {
        prefs.edit { putString(KEY_ENDPOINT, endpoint); putString(KEY_KIND, kind) }
        _status.value = PushStatus.Active(distributorLabel(kind))
        val a = api() ?: return
        scope.launch {
            runCatching {
                a.registerPush(PushEndpointCreate(kind, endpoint, keys, deviceName = Build.MODEL.take(60), locale = Locale.getDefault().toLanguageTag()))
            }.onSuccess { prefs.edit { putString(KEY_ID, it.id) } }
                .onFailure { Log.i(TAG, "push endpoint not registered yet: ${it.message}") }
        }
    }

    fun onUnregistered() {
        prefs.edit { remove(KEY_ENDPOINT) }
        _status.value = PushStatus.Unavailable
        unregisterFromServer()
    }

    /** Before signing out: the server should stop pushing to this phone. */
    suspend fun unregisterFromServerNow() {
        val id = prefs.getString(KEY_ID, null) ?: return
        runCatching { api()?.deletePush(id) }
        prefs.edit { remove(KEY_ID) }
    }

    private fun unregisterFromServer() = scope.launch { unregisterFromServerNow() }

    private fun distributorLabel(kind: String): String {
        if (kind == "fcm") return "Firebase Cloud Messaging"
        val pkg = UnifiedPush.getAckDistributor(context) ?: return "UnifiedPush"
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    companion object {
        private const val TAG = "KinPush"
        private const val KEY_ENDPOINT = "push_endpoint"
        private const val KEY_KIND = "push_kind"
        private const val KEY_ID = "push_endpoint_id"
        const val KIND_UP = "unifiedpush"
    }
}

/** Receives UnifiedPush events from the distributor (ntfy, NextPush, …). */
class KinPushService : PushService() {
    private val graph get() = (application as FamilyApp).graph

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        graph.push.onEndpoint(PushRegistrar.KIND_UP, endpoint.url, endpoint.pubKeySet?.let { WebPushKeys(it.pubKey, it.auth) })
    }

    override fun onMessage(message: UpMessage, instance: String) {
        // The server posts plain JSON today (no Web Push encryption), so the content is used whether or not it was decrypted.
        PushMessage.parse(message.content)?.let { msg ->
            Notifications.showPush(this, msg)
            graph.onPush(msg)
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w("KinPush", "registration failed: $reason")
    }

    override fun onUnregistered(instance: String) = graph.push.onUnregistered()
}
