package app.kinframe.core.crash

import android.content.Context
import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.android.core.SentryAndroidOptions
import io.sentry.protocol.Request

/**
 * Crash reports to kinframe's GlitchTip (docs/spec/crash-reporting.md). Only builds given a DSN
 * (`-Pkinframe.sentryDsn`, the official release builds) report; without one the SDK is never started.
 * The manifest's auto-init is removed (core's AndroidManifest.xml), so [start] is the only way in.
 */
object CrashReports {
    /**
     * @param project the GlitchTip project, which is also the release's prefix (`family-android`, `frame-android`)
     * @param tags added to every report (the frame's `flavor`)
     */
    class Setup(
        val dsn: String,
        val project: String,
        val versionName: String,
        val versionCode: Int,
        val tags: Map<String, String> = emptyMap(),
    ) {
        /** `<project>@<version>+<build>` (spec §3). */
        val release: String get() = "$project@$versionName+$versionCode"
    }

    /** Starts the SDK when [Setup.dsn] is set; returns whether it did. */
    fun start(context: Context, setup: Setup): Boolean {
        if (setup.dsn.isBlank()) return false
        SentryAndroid.init(context) { options -> configure(options, setup) }
        return true
    }

    /** Stops reporting until the next start (the family app's opt-out). */
    fun stop() {
        if (Sentry.isEnabled()) Sentry.close()
    }

    /** What a report may contain (spec §3), set explicitly so a later SDK default can't widen it. */
    fun configure(o: SentryAndroidOptions, setup: Setup) {
        o.dsn = setup.dsn
        o.release = setup.release
        o.environment = "production"
        setup.tags.forEach { (k, v) -> o.setTag(k, v) }

        o.isSendDefaultPii = false
        o.isAttachServerName = false
        o.isAttachScreenshot = false
        o.isAttachViewHierarchy = false
        o.isAttachAnrThreadDump = false
        o.isAttachRawTombstone = false
        o.sessionReplay.sessionSampleRate = 0.0
        o.sessionReplay.onErrorSampleRate = 0.0
        // Release health would send a stable installation id with every session.
        o.isEnableAutoSessionTracking = false
        // Clicks would name the views tapped; navigation and lifecycle breadcrumbs stay.
        o.isEnableUserInteractionBreadcrumbs = false

        // No performance tracing.
        o.tracesSampleRate = 0.0
        o.isEnableAutoActivityLifecycleTracing = false
        o.isEnableUserInteractionTracing = false
        o.isEnableFramesTracking = false
        o.isEnableAppStartProfiling = false
        o.profileSessionSampleRate = 0.0

        // ANRs; native crashes from Android 12's tombstones, which go through beforeSend like any other event.
        o.isAnrEnabled = true
        o.isTombstoneEnabled = true

        o.beforeSend = SentryOptions.BeforeSendCallback { event, hint -> scrub(event, hint) }
        o.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { crumb, _ -> scrub(crumb) }
    }

    /** Removes what must never leave the device (spec §3) from an event about to be sent. */
    fun scrub(event: SentryEvent, hint: Hint? = null): SentryEvent {
        event.user = null
        event.serverName = null
        event.contexts.device?.id = null
        event.request = event.request?.let { r ->
            Request().apply {
                method = r.method
                url = r.url?.let(::withoutQuery)
            }
        }
        event.contexts.response?.apply {
            headers = null
            cookies = null
            data = null
        }
        event.message?.apply {
            message = message?.let(::withoutQueries)
            formatted = formatted?.let(::withoutQueries)
        }
        event.exceptions?.forEach { e -> e.value = e.value?.let(::withoutQueries) }
        event.breadcrumbs?.forEach { scrub(it) }
        hint?.clearAttachments()
        hint?.screenshot = null
        hint?.viewHierarchy = null
        hint?.threadDump = null
        hint?.tombstone = null
        return event
    }

    /** HTTP breadcrumbs keep method, host, path and status: no query strings, bodies, headers or cookies. */
    fun scrub(crumb: Breadcrumb): Breadcrumb {
        crumb.message = crumb.message?.let(::withoutQueries)
        for (key in crumb.data.keys.toList()) {
            val k = key.lowercase()
            val dropped = k.contains("header") || k.contains("cookie") || k.contains("authorization") ||
                k.contains("query") || k.contains("fragment") || (k.contains("body") && !k.endsWith("_size"))
            if (dropped) crumb.removeData(key)
            else (crumb.getData(key) as? String)?.let { crumb.setData(key, withoutQueries(it)) }
        }
        return crumb
    }

    /** A URL without its query string and fragment. */
    fun withoutQuery(url: String): String = url.substringBefore('#').substringBefore('?')

    // A URL (absolute, or a path with a slash) followed by ?query and/or #fragment; the URL part is kept.
    private val urlWithQuery = Regex("""((?:[a-zA-Z][a-zA-Z0-9+.-]*://|/)[^\s?#"'<>]*)[?#][^\s"'<>]*""")

    /** Free text (an exception message) with the query string and fragment cut from every URL in it. */
    fun withoutQueries(text: String): String = urlWithQuery.replace(text) { it.groupValues[1] }
}
