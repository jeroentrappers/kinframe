package app.kinframe.family.ui.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Presentation
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import app.kinframe.core.api.CommandType
import app.kinframe.core.api.FrameCommand
import app.kinframe.core.api.KinApi
import app.kinframe.core.api.TvLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference

/**
 * "Present on a screen", Android's way. The web app uses the browser's Presentation API or the Google Cast web SDK;
 * here the phone shows the TV page (`/tv/<frame>?t=…`, a short-lived `cast` or `mirror` link) on a presentation
 * display: a TV connected with the system's Cast screen (Chromecast, Google TV) or Miracast. No Google Play services
 * are needed. While no such display is connected, it opens the system Cast settings and starts as soon as one appears.
 * Next, previous and pause go to the page as the keys a TV remote sends; while mirroring they move the frame itself.
 */
class TvPresenter(private val app: Context, private val scope: CoroutineScope, private val api: () -> KinApi?) {
    enum class Status { Idle, Waiting, Live }

    data class State(
        val status: Status = Status.Idle,
        val device: String? = null,
        val frameId: String? = null,
        val frameName: String = "",
        val mirroring: Boolean = false,
        val playing: Boolean = true,
        val link: TvLink? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val displays = app.getSystemService(DisplayManager::class.java)
    private var activity: WeakReference<Activity>? = null
    private var presentation: TvPresentation? = null
    private val main = Handler(Looper.getMainLooper())

    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = show()
        override fun onDisplayChanged(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) {
            if (presentation?.display?.displayId == displayId) stop()
        }
    }
    private var listening = false

    /** A TV is connected as a presentation display right now. */
    fun displayAvailable(): Boolean = presentationDisplay() != null

    private fun presentationDisplay(): Display? = displays?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.firstOrNull()

    /** The activity the presentation window belongs to; called by the app's root screen. */
    fun attach(a: Activity) {
        activity = WeakReference(a)
        if (state.value.status != Status.Idle) show()
    }

    /** The activity goes away (rotation, closing): its window goes too, the show resumes on the next [attach]. */
    fun detach(a: Activity) {
        if (activity?.get() === a) {
            presentation?.dismiss()
            presentation = null
            activity = null
        }
    }

    /** Start showing [link] (purpose `cast` or `mirror`); waits for a TV when none is connected yet. */
    fun start(link: TvLink, frameName: String, interval: Int) {
        stop(revoke = true)
        _state.value = State(Status.Waiting, frameId = link.frameId, frameName = frameName, mirroring = link.purpose == "mirror", link = link.copy(url = pageUrl(link, interval)))
        if (!listening) { displays?.registerDisplayListener(listener, main); listening = true }
        if (presentationDisplay() == null) openCastSettings() else show()
    }

    private fun pageUrl(link: TvLink, interval: Int): String =
        if (link.purpose == "mirror") link.url else link.url + (if ('?' in link.url) "&" else "?") + "i=$interval"

    private fun show() {
        val s = state.value
        val link = s.link ?: return
        if (s.status == Status.Idle || presentation != null) return
        val a = activity?.get() ?: return
        val d = presentationDisplay() ?: return
        val p = TvPresentation(a, d, link.url)
        try {
            p.show()
        } catch (e: WindowManager.InvalidDisplayException) {
            return
        }
        presentation = p
        _state.update { it.copy(status = Status.Live, device = d.name) }
    }

    private fun openCastSettings() {
        val a = activity?.get() ?: app
        for (action in listOf(Settings.ACTION_CAST_SETTINGS, "android.settings.WIFI_DISPLAY_SETTINGS")) {
            try {
                a.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
    }

    fun next() = move(1)
    fun previous() = move(-1)

    private fun move(dir: Int) {
        val s = state.value
        if (s.mirroring) {
            val id = s.frameId ?: return
            scope.launch { runCatching { api()?.command(id, FrameCommand(if (dir > 0) CommandType.NEXT else CommandType.PREVIOUS)) } }
        } else {
            presentation?.key(if (dir > 0) "ArrowRight" else "ArrowLeft")
        }
    }

    fun toggle() {
        presentation?.key("Enter")
        _state.update { it.copy(playing = !it.playing) }
    }

    /** Stop showing and turn the session's link off. */
    fun stop(revoke: Boolean = true) {
        val s = state.value
        presentation?.dismiss()
        presentation = null
        if (listening) { displays?.unregisterDisplayListener(listener); listening = false }
        _state.value = State()
        val link = s.link
        if (revoke && link != null) scope.launch { runCatching { api()?.revokeTvLink(link.frameId, link.id) } }
    }
}

/** The TV page in a WebView on the presentation display. */
private class TvPresentation(outer: Context, display: Display, private val url: String) : Presentation(outer, display) {
    private var web: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val w = WebView(context)
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.mediaPlaybackRequiresUserGesture = false
        w.webViewClient = WebViewClient()
        w.setBackgroundColor(android.graphics.Color.BLACK)
        setContentView(w)
        w.loadUrl(url)
        web = w
    }

    /** The keys the TV page handles (← → OK), as a remote would send them. */
    fun key(key: String) {
        web?.evaluateJavascript("window.dispatchEvent(new KeyboardEvent('keydown',{key:'$key'}))", null)
    }

    override fun onStop() {
        web?.destroy()
        web = null
        super.onStop()
    }
}
