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
    /**
     * The credit for a placement: [line], or "Anna · via Frameo" for a photo imported from the frame's old app. A photo a
     * photo source or a Google Photos import brought in adds the service: "Anna via Immich", "Anna · Peeters kids via
     * Immich", "Anna via Immich · shared by Ben" (photo-sources.md §9.5).
     */
    fun of(ctx: Context, p: app.kinframe.frame.data.PlacementEntity): String? = when {
        p.importedFrom != null -> imported(ctx, p.importedFrom, p.importedSenderName)
        p.creditFeedName != null -> via(ctx, feed(ctx, p.senderName, p.creditFeedName!!), p.viaLabel)
        else -> line(ctx, p.senderName, p.sharedByName, p.viaLabel)
    }

    /** "[credit] via [service]"; the service name is shown as given (not translated) and bidi-isolated. */
    fun via(ctx: Context, credit: String?, service: String?): String? {
        val v = service?.trim()?.takeIf { it.isNotEmpty() } ?: return credit
        val c = credit?.takeIf { it.isNotBlank() } ?: return null
        val locale = ConfigurationCompat.getLocales(ctx.resources.configuration)[0] ?: Locale.getDefault()
        return ctx.getString(R.string.credit_via, c, BidiFormatter.getInstance(locale).unicodeWrap(v))
    }

    /**
     * "Anna · Peeters kids" for a photo a subscribed feed placed here (feeds.md §3): the feed name takes the place of
     * "shared by". Both are names, so the separator needs no translation; each is bidi-wrapped like in [line].
     */
    fun feed(ctx: Context, sender: String?, feedName: String): String? {
        val f = feedName.trim().takeIf { it.isNotEmpty() }
        val s = sender?.trim()?.takeIf { it.isNotEmpty() }
        if (s == null) return f
        if (f == null) return s
        val locale = ConfigurationCompat.getLocales(ctx.resources.configuration)[0] ?: Locale.getDefault()
        val bidi = BidiFormatter.getInstance(locale)
        return bidi.unicodeWrap(s) + " · " + bidi.unicodeWrap(f)
    }

    /** "Anna · via Frameo", or "via Frameo" when the old app didn't know the sender; null for an unknown source. */
    fun imported(ctx: Context, source: String, sender: String?): String? {
        if (source != app.kinframe.core.api.ItemOrigin.FRAMEO) return null
        val s = sender?.trim()?.takeIf { it.isNotEmpty() } ?: return ctx.getString(R.string.credit_frameo)
        val locale = ConfigurationCompat.getLocales(ctx.resources.configuration)[0] ?: Locale.getDefault()
        return ctx.getString(R.string.credit_via_frameo, BidiFormatter.getInstance(locale).unicodeWrap(s))
    }

    fun line(ctx: Context, sender: String?, sharedBy: String?, via: String? = null): String? {
        val s = sender?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val locale = ConfigurationCompat.getLocales(ctx.resources.configuration)[0] ?: Locale.getDefault()
        val bidi = BidiFormatter.getInstance(locale)
        val b = sharedBy?.trim()?.takeIf { it.isNotEmpty() && it != s }
        if (via.isNullOrBlank()) return if (b == null) s else ctx.getString(R.string.caption_shared_by, bidi.unicodeWrap(s), bidi.unicodeWrap(b))
        val withVia = via(ctx, bidi.unicodeWrap(s), via)!!
        return if (b == null) withVia else ctx.getString(R.string.caption_shared_by, withVia, bidi.unicodeWrap(b))
    }
}
