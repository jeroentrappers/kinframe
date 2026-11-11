package app.kinframe.family

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import app.kinframe.family.auth.AuthState
import app.kinframe.family.ui.FrameTab
import app.kinframe.family.ui.Navigator
import app.kinframe.family.ui.Route
import app.kinframe.family.ui.send.ComposerViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Debug-build launch extras for screenshots and manual testing against a dev server, e.g.
 * `adb shell am start -n app.kinframe.family/.MainActivity --es kfServer http://localhost:8110 --es kfDevEmail me@example.org`.
 * Release builds ignore them ([BuildConfig.DEBUG]).
 *
 * - `kfReset true` (`--ez`): forget the server and session first.
 * - `kfServer <url>` with `kfDevEmail <email>` [`kfDevName <name>`]: sign in with a dev token (dev servers only).
 * - `kfLanguage en|nl|system`: the app language (Android 13+).
 * - `kfTab frames|send|history|settings`.  `kfSend <id>`: open a send in History.
 * - `kfFrame <id>` [`kfSection overview|photos|people|calendar|settings`]: open a frame.
 * - `kfCompose <uri,uri,…>` (file or content URIs the app can read), `kfCaption <text>` (the first item),
 *   `kfComposeFrames <id,id,…>`: fill the composer.
 */
object DebugLaunch {
    data class Request(
        val tab: Route?,
        val push: List<Route>,
        val compose: List<Uri>,
        val caption: String?,
        val composeFrames: Set<String>,
    )

    /** Waiting for the home screen (after sign-in). */
    val pending = MutableStateFlow<Request?>(null)

    fun handle(graph: AppGraph, intent: Intent) {
        if (!BuildConfig.DEBUG) return
        val keys = intent.extras?.keySet().orEmpty()
        if (keys.none { it.startsWith("kf") }) return
        intent.getStringExtra("kfLanguage")?.let { setLanguage(graph, it) }
        val request = request(intent)
        graph.scope.launch {
            if (intent.getBooleanExtra("kfReset", false)) graph.forgetServer()
            intent.getStringExtra("kfServer")?.let { raw ->
                val choice = runCatching { graph.servers.check(raw) }.getOrNull() ?: return@launch
                val current = graph.servers.current.value
                if (current != null && current.url != choice.url) graph.forgetServer()
                graph.servers.choose(choice)
            }
            intent.getStringExtra("kfDevEmail")?.let { email ->
                if (graph.auth.state.value !is AuthState.SignedIn) {
                    runCatching { graph.auth.signInDev(email, intent.getStringExtra("kfDevName").orEmpty()) }
                }
            }
            pending.value = request
        }
    }

    private fun setLanguage(graph: AppGraph, tag: String) {
        if (Build.VERSION.SDK_INT < 33) return
        val manager = graph.app.getSystemService(android.app.LocaleManager::class.java) ?: return
        val locales = if (tag == "system") LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        if (manager.applicationLocales != locales) manager.applicationLocales = locales
    }

    private fun request(intent: Intent): Request {
        val tab = when (intent.getStringExtra("kfTab")) {
            "frames" -> Route.Frames
            "send" -> Route.Send
            "history" -> Route.History
            "settings" -> Route.Settings
            else -> null
        }
        val push = buildList {
            intent.getStringExtra("kfSend")?.let { add(Route.SendDetail(it)) }
            intent.getStringExtra("kfFrame")?.let { id ->
                val section = when (intent.getStringExtra("kfSection")) {
                    "photos" -> FrameTab.Library
                    "people" -> FrameTab.People
                    "calendar" -> FrameTab.Calendar
                    "settings" -> FrameTab.Settings
                    else -> FrameTab.Overview
                }
                add(Route.FrameDetail(id, section))
            }
        }
        fun list(key: String) = intent.getStringExtra(key)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return Request(tab, push, list("kfCompose").map(Uri::parse), intent.getStringExtra("kfCaption"), list("kfComposeFrames").toSet())
    }

    /** Applies a pending request once the home screen is up. */
    @Composable
    fun Effect(nav: Navigator, composer: ComposerViewModel) {
        if (!BuildConfig.DEBUG) return
        LaunchedEffect(nav, composer) {
            val r = pending.filterNotNull().first()
            pending.value = null
            r.tab?.let(nav::selectTab)
            r.push.forEach(nav::push)
            if (r.compose.isNotEmpty()) {
                composer.clear()
                composer.add(r.compose)
                val s = composer.state.first { !it.adding && it.items.isNotEmpty() }
                r.caption?.let { composer.setCaption(s.items.first().id, it) }
            }
            r.composeFrames.forEach { id -> if (id !in composer.state.value.selected) composer.toggleFrame(id) }
        }
    }
}
