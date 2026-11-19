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
data class PushMessage(
    val type: String, val title: String, val body: String, val ids: Map<String, String>, val key: String = "",
    /** Notification tag set by the server (greetings: `birthday:<birthday_id>:<date>`, so a newer one replaces the older). */
    val tag: String = "",
    /** Notification buttons (`snooze_month`, `fewer_reminders` on reminders; README §6.3). */
    val actions: List<String> = emptyList(),
    /** The sending server's base URL (README §6.3); empty from older servers. */
    val server: String = "",
    /**
     * The app's local account the push was routed to ([app.kinframe.family.AppGraph.routePush]); set on the phone, never
     * in the payload. Notification buttons and taps act on that account's server.
     */
    val account: String = "",
) {
    /** The push key (`subscription_request`…), falling back to the type for servers that send only one. */
    val kind: String get() = key.ifBlank { type }

    companion object {
        fun parse(bytes: ByteArray): PushMessage? = runCatching {
            val o = KinJson.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
            val ids = (o["ids"]?.jsonObject ?: emptyMap()).mapNotNull { (k, v) -> v.jsonPrimitive.contentOrNull?.let { k to it } }.toMap()
            val actions = (o["actions"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
            PushMessage(s("type"), s("title"), s("body"), ids, s("key"), s("tag"), actions, s("server"))
        }.getOrNull()

        /**
         * An FCM data message: the server flattens the payload into string fields (`type`, `key`, `title`, `body`,
         * `tag`, `actions` comma-separated, `server`, and every id at the top level; server/internal/notify/fcm.go).
         * [notificationTitle] and [notificationBody] are the FCM notification block's, for older servers.
         */
        fun fromData(data: Map<String, String>, notificationTitle: String? = null, notificationBody: String? = null): PushMessage = PushMessage(
            type = data["type"].orEmpty(),
            title = data["title"] ?: notificationTitle.orEmpty(),
            body = data["body"] ?: notificationBody.orEmpty(),
            ids = data.filterKeys { it.endsWith("_id") || it in SocialNotifications.EXTRA_ID_KEYS },
            key = data["key"].orEmpty(),
            tag = data["tag"].orEmpty(),
            actions = SocialNotifications.parseActions(data["actions"]),
            server = data["server"].orEmpty(),
        )
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

/** One account as push registration sees it: its id, preferences (endpoint and server id) and client while signed in. */
data class PushAccount(val id: String, val prefs: SharedPreferences, val api: KinApi?)

/**
 * Registers this phone for push: UnifiedPush first (no Google dependency), FCM only in builds made with
 * `-Pkinframe.fcm=true`. Each account gets its own endpoint, registered with `POST /v1/me/push-endpoints` on its own
 * server (docs/spec/multi-server.md §2.1): one UnifiedPush instance per account (the first account keeps the default
 * instance), so a push without the `server` field still tells which account it is for. FCM has one token for all.
 * Endpoints are removed again when signing out of an account.
 */
class PushRegistrar(
    private val context: Context,
    private val scope: CoroutineScope,
    private val accounts: () -> List<PushAccount>,
) {
    private val _status = MutableStateFlow<PushStatus>(PushStatus.Unavailable)
    val status: StateFlow<PushStatus> = _status.asStateFlow()

    /** Call when signed in (app start, after sign-in, an account added). Never asks the person anything. */
    fun ensureRegistered() {
        if (BuildConfig.FCM_ENABLED && UnifiedPush.getDistributors(context).isEmpty()) {
            _status.value = PushStatus.Registering
            FcmPush.start(context) { token -> onEndpoint("fcm", token, null, null) }
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
        // Re-send known endpoints in case a server lost them (idempotent on kind + endpoint).
        accounts().forEach { a ->
            a.prefs.getString(KEY_ENDPOINT, null)?.let { send(a, a.prefs.getString(KEY_KIND, KIND_UP)!!, it, null) }
        }
    }

    /** Lets the person pick the push app (system picker or the default distributor). */
    fun choose(activity: Activity) {
        UnifiedPush.tryPickDistributor(activity) { ok -> if (ok) register() }
    }

    private fun register() {
        _status.value = PushStatus.Registering
        accounts().filter { it.api != null }.forEach { a ->
            runCatching { UnifiedPush.register(context, instance = instanceOf(a.id), messageForDistributor = "kinframe") }
                .onFailure { Log.w(TAG, "UnifiedPush register failed", it); _status.value = PushStatus.Unavailable }
        }
    }

    /** A new endpoint: for one UnifiedPush [instance], or (FCM, [instance] null) for every account. */
    fun onEndpoint(kind: String, endpoint: String, keys: WebPushKeys?, instance: String?) {
        _status.value = PushStatus.Active(distributorLabel(kind))
        val targets = if (instance == null) accounts() else accounts().filter { it.id == accountOf(instance) }
        targets.forEach { a ->
            a.prefs.edit { putString(KEY_ENDPOINT, endpoint); putString(KEY_KIND, kind) }
            send(a, kind, endpoint, keys)
        }
    }

    private fun send(a: PushAccount, kind: String, endpoint: String, keys: WebPushKeys?) {
        val api = a.api ?: return
        scope.launch {
            runCatching {
                api.registerPush(PushEndpointCreate(kind, endpoint, keys, deviceName = Build.MODEL.take(60), locale = Locale.getDefault().toLanguageTag()))
            }.onSuccess { a.prefs.edit { putString(KEY_ID, it.id) } }
                .onFailure { Log.i(TAG, "push endpoint not registered yet: ${it.message}") }
        }
    }

    fun onUnregistered(instance: String) {
        val a = accounts().firstOrNull { it.id == accountOf(instance) } ?: return
        a.prefs.edit { remove(KEY_ENDPOINT) }
        if (accounts().none { it.prefs.contains(KEY_ENDPOINT) }) _status.value = PushStatus.Unavailable
        scope.launch { unregisterFromServerNow(a) }
    }

    /** Before signing out of an account: its server should stop pushing to this phone. */
    suspend fun unregisterFromServerNow(a: PushAccount, alsoInstance: Boolean = false) {
        val id = a.prefs.getString(KEY_ID, null)
        if (id != null) runCatching { a.api?.deletePush(id) }
        a.prefs.edit { remove(KEY_ID) }
        if (alsoInstance && a.id != FIRST_ACCOUNT) runCatching { UnifiedPush.unregister(context, instanceOf(a.id)) }
    }

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
        /** [app.kinframe.family.accounts.AccountStore.MAIN]: the account of the default UnifiedPush instance. */
        private const val FIRST_ACCOUNT = "main"
        private const val DEFAULT_INSTANCE = "default"

        fun instanceOf(accountId: String) = if (accountId == FIRST_ACCOUNT) DEFAULT_INSTANCE else accountId

        /** The account a UnifiedPush instance belongs to (the push endpoint's account, README §6.3). */
        fun accountOf(instance: String?) = if (instance.isNullOrBlank() || instance == DEFAULT_INSTANCE) FIRST_ACCOUNT else instance
    }
}

/** Receives UnifiedPush events from the distributor (ntfy, NextPush, …). */
class KinPushService : PushService() {
    private val graph get() = (application as FamilyApp).graph

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        graph.push.onEndpoint(PushRegistrar.KIND_UP, endpoint.url, endpoint.pubKeySet?.let { WebPushKeys(it.pubKey, it.auth) }, instance)
    }

    override fun onMessage(message: UpMessage, instance: String) {
        // The server posts plain JSON today (no Web Push encryption), so the content is used whether or not it was decrypted.
        PushMessage.parse(message.content)?.let { msg ->
            // Several accounts: the payload's `server` picks the account, else this endpoint's account (README §6.3).
            graph.receivePush(this, msg, PushRegistrar.accountOf(instance))
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Log.w("KinPush", "registration failed: $reason")
    }

    override fun onUnregistered(instance: String) = graph.push.onUnregistered(instance)
}
