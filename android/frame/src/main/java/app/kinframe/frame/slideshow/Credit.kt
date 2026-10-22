package app.kinframe.frame.slideshow

import android.content.Context
import androidx.core.os.ConfigurationCompat
import androidx.core.text.BidiFormatter
import app.kinframe.frame.R
import java.util.Locale

/** Who a photo is from, as the caption line shows it (product spec 4.4, README §5.3). */
object Credit {
    /**
     * The sender's name, or "Anna · shared by Ben" (localized) when another member shared the photo to this frame
     * from a different one. Each name is bidi-wrapped for the UI language, so a Latin name in an Arabic or Hebrew
     * caption (or the reverse) keeps its place and the separator stays between the names.
     */
    fun line(ctx: Context, sender: String?, sharedBy: String?): String? {
        val s = sender?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val b = sharedBy?.trim()?.takeIf { it.isNotEmpty() && it != s } ?: return s
        val locale = ConfigurationCompat.getLocales(ctx.resources.configuration)[0] ?: Locale.getDefault()
        val bidi = BidiFormatter.getInstance(locale)
        return ctx.getString(R.string.caption_shared_by, bidi.unicodeWrap(s), bidi.unicodeWrap(b))
    }
}
