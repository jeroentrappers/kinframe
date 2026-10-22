package app.kinframe.frame

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.kinframe.frame.schedule.ScreenMode
import app.kinframe.frame.ui.FrameRoot
import kotlinx.coroutines.launch

/**
 * The frame's only activity: a HOME launcher in immersive kiosk mode that keeps the screen on.
 * Leaving it requires the owner PIN ([app.kinframe.frame.kiosk.OwnerGate]) when one is set, or adb.
 */
class MainActivity : ComponentActivity() {
    private val graph by lazy { FrameApp.graph(this) }
    private var swallowGesture = false

    /** Storage permission prompt and the folder picker for SD/USB export (see [app.kinframe.frame.removable.RemovableStorage]). */
    private val storagePermissions = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { res ->
        graph.controller.removable.onPermissionResult(res.values.all { it })
    }
    private var accessVolume: app.kinframe.frame.removable.Volume? = null
    private val folderPicker = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
        accessVolume?.let { graph.controller.removable.onAccessGranted(it, uri) }
        accessVolume = null
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(Locales.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
        )
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        handleProvisioning(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Kiosk: back never leaves the frame app; it closes the settings screen if open.
                if (graph.controller.settingsOpen.value) graph.controller.closeSettings()
            }
        })

        setContent { FrameRoot(graph.controller) }
        // Compose paints every pixel; skipping the window background saves a full-screen fill per frame.
        window.setBackgroundDrawable(null)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                graph.controller.display.windowBrightness.collect { b ->
                    window.attributes = window.attributes.apply { screenBrightness = b }
                }
            }
        }
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    graph.controller.removable.permissionRequest.collect { ask ->
                        if (ask) storagePermissions.launch(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE))
                    }
                }
                graph.controller.removable.accessRequest.collect { v ->
                    if (v != null && accessVolume == null) {
                        accessVolume = v
                        // No picker on some frame firmwares: report "no access" instead of crashing.
                        runCatching { folderPicker.launch(null) }.onFailure { graph.controller.removable.onAccessGranted(v, null) }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleProvisioning(intent)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOffReceiver) }
        super.onDestroy()
    }

    /** Volume keys set the video volume (the frame has no other use for them). */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val delta = when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> 10
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> -10
            else -> return super.onKeyDown(keyCode, event)
        }
        if (graph.controller.screen.value == AppScreen.Slideshow) {
            graph.controller.slideshow.nudgeVolume(delta)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    /**
     * Touch while asleep only wakes the frame: the gesture is swallowed so it doesn't also open the quick menu.
     * Every touch counts as activity for the "defer scheduled actions" rule.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val display = graph.controller.display
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val mode = display.mode.value
            swallowGesture = mode == ScreenMode.Asleep || mode == ScreenMode.FadingOut
            display.onUserActivity()
        }
        if (swallowGesture) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallowGesture = false
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Provisioning over adb (product spec 4.8), e.g.
     * `adb shell am start -n app.kinframe.frame/.MainActivity --es server_url https://frames.example.org --es language nl`.
     * Only honoured while the frame is not enrolled; the server is never changeable remotely.
     */
    private fun handleProvisioning(intent: Intent?) {
        val server = intent?.getStringExtra("server_url")
        val language = intent?.getStringExtra("language")
        if (language != null && Locales.supported.any { it.first == language } && graph.prefs.language != language) {
            graph.prefs.language = language
            recreate()
        }
        if (server != null && graph.credentials.load() == null) {
            graph.controller.setup.setUseCustom(server.trimEnd('/') != BuildConfig.DEFAULT_SERVER)
            graph.controller.setup.setCustomUrl(server)
        }
    }

    /** On the "safe to unplug" screen, the power button restarts the frame (kinframe-additions.md). */
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (graph.controller.screen.value is AppScreen.SafeToUnplug) {
                graph.controller.scope.launch { graph.controller.power.restart() }
            }
        }
    }
}
