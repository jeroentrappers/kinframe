package app.kinframe.family.ui.sources

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent

/** Opens Google's pages for "Add from Google Photos" the way README §5.14 asks on Android. */
object GoogleOpener {
    const val GOOGLE_PHOTOS = "com.google.android.apps.photos"

    /** Consent: a Custom Tab (a real browser session; Google refuses embedded web views). */
    fun openConsent(context: Context, url: String) = openTab(context, url)

    /** The picker: the Google Photos app handles it when installed, otherwise a Custom Tab. */
    fun openPicker(context: Context, url: String) {
        val uri = Uri.parse(url)
        val app = Intent(Intent.ACTION_VIEW, uri).setPackage(GOOGLE_PHOTOS)
        if (app.resolveActivity(context.packageManager) != null && runCatching { context.startActivity(app) }.isSuccess) return
        openTab(context, url)
    }

    private fun openTab(context: Context, url: String) {
        val uri = Uri.parse(url)
        runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri) }
            .onFailure { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}
