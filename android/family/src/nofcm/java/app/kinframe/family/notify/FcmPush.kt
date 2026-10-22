package app.kinframe.family.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/** Default builds have no Firebase: push goes through UnifiedPush only. */
object FcmPush {
    @Suppress("UNUSED_PARAMETER")
    fun start(context: Context, onToken: (String) -> Unit) = Unit
}

/** Placeholder for the manifest entry; disabled unless the build has FCM (`-Pkinframe.fcm=true`). */
class FcmService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
