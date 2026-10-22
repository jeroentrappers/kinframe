package app.kinframe.family.notify

import android.content.Context
import android.util.Log
import app.kinframe.family.FamilyApp
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM for store builds (`-Pkinframe.fcm=true`). Firebase reads its options from string resources the store build
 * supplies (google_app_id, gcm_defaultSenderId, google_api_key, project_id); none are committed.
 */
object FcmPush {
    fun start(context: Context, onToken: (String) -> Unit) {
        if (FirebaseApp.getApps(context).isEmpty() && FirebaseApp.initializeApp(context) == null) {
            Log.w("KinPush", "FCM enabled but no Firebase options in this build")
            return
        }
        FirebaseMessaging.getInstance().token.addOnSuccessListener { onToken(it) }
    }
}

class FcmService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        (application as FamilyApp).graph.push.onEndpoint("fcm", token, null)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val msg = PushMessage(
            type = data["type"].orEmpty(),
            title = data["title"] ?: message.notification?.title.orEmpty(),
            body = data["body"] ?: message.notification?.body.orEmpty(),
            ids = data.filterKeys { it.endsWith("_id") },
        )
        Notifications.showPush(this, msg)
        (application as FamilyApp).graph.onPush(msg)
    }
}
