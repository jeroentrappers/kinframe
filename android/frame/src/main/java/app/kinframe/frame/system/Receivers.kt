package app.kinframe.frame.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.kinframe.frame.FrameApp
import app.kinframe.frame.MainActivity

/** Starts the frame after boot (it is also the HOME app, this covers plain tablets) and after an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, "android.intent.action.QUICKBOOT_POWERON" -> {
                FrameApp.graph(context) // ensures the app graph (and session) is up
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

/** An OS shutdown (long-press power → Power off) is a clean shutdown too. */
class ShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_SHUTDOWN) return
        val g = FrameApp.graph(context)
        runCatching { g.db.checkpoint() }
        g.sessionMarker.markClean()
    }
}
