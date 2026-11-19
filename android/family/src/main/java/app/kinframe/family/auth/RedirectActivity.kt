package app.kinframe.family.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.kinframe.family.MainActivity
import app.kinframe.family.ui.sources.GoogleImportFlow

/**
 * Receives every `app.kinframe:` redirect and hands it on. Intent filters can't tell custom-scheme paths apart without a
 * host, so one activity owns the scheme: `app.kinframe:/google-import` (the Google Photos import, README §5.14) goes
 * to [MainActivity], on top of which the Custom Tab was opened (clearing the tab); everything else (`/callback`,
 * `/logout`) is the sign-in redirect and goes to Logto's receiver exactly as before.
 */
class RedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val next = if (GoogleImportFlow.isReturn(data?.toString())) {
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } else {
            Intent().setClassName(this, LOGTO_RECEIVER)
        }
        startActivity(next.setAction(Intent.ACTION_VIEW).setData(data))
        finish()
    }

    companion object {
        const val LOGTO_RECEIVER = "io.logto.sdk.android.auth.logto.LogtoRedirectReceiverActivity"
    }
}
