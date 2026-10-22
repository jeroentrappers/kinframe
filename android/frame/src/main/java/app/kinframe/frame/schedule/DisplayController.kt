package app.kinframe.frame.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import app.kinframe.frame.FrameApp
import app.kinframe.frame.FrameController
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime

enum class ScreenMode { Awake, Dimmed, FadingOut, Asleep }

/**
 * Sleep schedules and brightness (product spec 4.7).
 * - Scheduled sleep: black fade (1.5 s), then the backlight goes off. Touch wakes; the frame goes back to sleep after
 *   5 minutes without touch while still inside the window.
 * - Scheduled actions are deferred until 60 s after the last touch.
 * - Boundaries are timed with exact alarms (in-process timers when close), and re-evaluated every 30 s anyway.
 * - Brightness: window `screenBrightness` (and the system setting when we may write it), manual/schedule/sun.
 */
class DisplayController(private val c: FrameController) {
    private val g = c.g
    private val _mode = MutableStateFlow(ScreenMode.Awake)
    val mode: StateFlow<ScreenMode> = _mode

    /** Window brightness 0..1 (or [BRIGHTNESS_OFF]); collected by the activity. */
    private val _windowBrightness = MutableStateFlow(204f / 255f)
    val windowBrightness: StateFlow<Float> = _windowBrightness
    private val _brightness = MutableStateFlow(204)
    val brightness: StateFlow<Int> = _brightness

    private var lastTouch = 0L
    private var manualSleep = false
    private var wokeByTouchInWindow = false
    private var remoteAwakeUntil: LocalDateTime? = null
    private var safeToUnplug = false
    private val nudge = Channel<Unit>(Channel.CONFLATED)
    private var fadeJob: Job? = null
    /** Brightness preview while the settings slider is dragged. */
    @Volatile var previewBrightness: Int? = null

    fun start() {
        c.scope.launch {
            while (true) {
                runCatching { evaluate() }.onFailure { Log.w(TAG, "evaluate failed", it) }
                withTimeoutOrNull(30_000) { nudge.receive() }
            }
        }
        c.scope.launch { g.settings.settings.collect { nudge.trySend(Unit) } }
    }

    fun evaluateNow() { nudge.trySend(Unit) }

    /** `SystemClock.elapsedRealtime()` of the last touch; 0 when not touched since the app started. */
    val lastTouchElapsed: Long get() = lastTouch

    /** The person put the frame to sleep by hand (quick menu or remote `sleep`), as opposed to a sleep schedule. */
    val screenOffByHand: Boolean get() = manualSleep

    fun onUserActivity() {
        lastTouch = SystemClock.elapsedRealtime()
        if (_mode.value == ScreenMode.Asleep || _mode.value == ScreenMode.FadingOut || _mode.value == ScreenMode.Dimmed) {
            wokeByTouchInWindow = activeWindow() != null
            manualSleep = false
            wakeUp()
        }
        nudge.trySend(Unit)
    }

    fun sleepNow() {
        manualSleep = true
        evaluateNow()
    }

    fun wake(remote: Boolean = false) {
        manualSleep = false
        if (remote) remoteAwakeUntil = activeWindow()?.end
        lastTouch = SystemClock.elapsedRealtime()
        if (_mode.value != ScreenMode.Awake) wakeUp()
        evaluateNow()
    }

    /** New photos wake a frame that was put to sleep by hand (idle), never during scheduled sleep. */
    fun onNewPhotos() {
        if (manualSleep && activeWindow() == null) wake()
    }

    fun dimForSafeToUnplug() {
        safeToUnplug = true
        fadeJob?.cancel()
        _mode.value = ScreenMode.Awake
        _windowBrightness.value = 0.06f
    }

    fun screenStateName(): String = when (_mode.value) {
        ScreenMode.Awake -> "on"
        ScreenMode.Dimmed -> "dimmed"
        ScreenMode.FadingOut, ScreenMode.Asleep -> if (manualSleep) "off" else "sleeping"
    }

    private fun nowLocal(): LocalDateTime = Instant.ofEpochMilli(g.clock.nowMs()).atZone(g.clock.zone).toLocalDateTime()

    private fun rules() = SleepPlanner.fromSettings(g.settings.settings.value.display.sleepSchedules)

    private fun activeWindow() = SleepPlanner.activeWindow(rules(), nowLocal())

    private fun evaluate() {
        if (safeToUnplug) return
        val now = nowLocal()
        val window = SleepPlanner.activeWindow(rules(), now)
        if (window == null) {
            wokeByTouchInWindow = false
            remoteAwakeUntil = null
        }
        if (remoteAwakeUntil != null && window?.end != remoteAwakeUntil) remoteAwakeUntil = null
        val sinceTouch = SystemClock.elapsedRealtime() - lastTouch
        val grace = if (wokeByTouchInWindow) TOUCH_WAKE_MS else DEFER_MS
        var recheckInMs: Long? = null
        val desired = when {
            manualSleep -> ScreenMode.Asleep
            window == null -> ScreenMode.Awake
            remoteAwakeUntil != null -> ScreenMode.Awake
            lastTouch > 0 && sinceTouch < grace -> {
                recheckInMs = grace - sinceTouch + 200
                if (_mode.value == ScreenMode.Asleep) ScreenMode.Asleep else ScreenMode.Awake
            }
            window.dim -> ScreenMode.Dimmed
            else -> ScreenMode.Asleep
        }
        when (desired) {
            ScreenMode.Asleep -> if (_mode.value == ScreenMode.Awake || _mode.value == ScreenMode.Dimmed) fallAsleep()
            ScreenMode.Awake, ScreenMode.Dimmed -> {
                if (_mode.value == ScreenMode.Asleep || _mode.value == ScreenMode.FadingOut) wakeUp()
                _mode.value = desired
                applyBrightness()
            }
            ScreenMode.FadingOut -> Unit
        }
        scheduleNext(SleepPlanner.nextBoundary(rules(), now), recheckInMs)
    }

    private fun fallAsleep() {
        fadeJob?.cancel()
        _mode.value = ScreenMode.FadingOut
        fadeJob = c.scope.launch {
            delay(FADE_MS)
            _mode.value = ScreenMode.Asleep
            _windowBrightness.value = BRIGHTNESS_OFF
            if (g.root.known) {
                // Android applies its minimum backlight for the "off" window value first; switch the panel's
                // backlight fully off once that has settled. Any brightness change on wake restores it.
                delay(1_200)
                if (_mode.value == ScreenMode.Asleep) backlight(0)
            }
        }
    }

    private fun wakeUp() {
        fadeJob?.cancel()
        _mode.value = ScreenMode.Awake
        applyBrightness()
    }

    fun applyBrightness() {
        if (safeToUnplug) return
        val s = g.settings.settings.value
        val target = previewBrightness ?: when (_mode.value) {
            ScreenMode.Dimmed -> BrightnessPolicy.MIN
            else -> BrightnessPolicy.target(s.display, s.location, ZonedDateTime.ofInstant(g.clock.now(), g.clock.zone))
        }
        _brightness.value = target
        if (_mode.value == ScreenMode.Asleep) return
        _windowBrightness.value = target / 255f
        if (runCatching { Settings.System.canWrite(g.app) }.getOrDefault(false)) {
            runCatching {
                Settings.System.putInt(g.app.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(g.app.contentResolver, Settings.System.SCREEN_BRIGHTNESS, target)
            }
        }
    }

    private fun backlight(value: Int) {
        c.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            g.root.run("for f in /sys/class/backlight/*/brightness; do echo $value > \$f; done", timeoutMs = 3_000)
        }
    }

    private fun scheduleNext(boundary: LocalDateTime?, recheckInMs: Long?) {
        val am = g.app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            g.app, 0, Intent(g.app, ScheduleReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val candidates = mutableListOf<Long>()
        boundary?.let { candidates += it.atZone(g.clock.zone).toInstant().toEpochMilli() - g.clock.offsetMs }
        recheckInMs?.let { candidates += System.currentTimeMillis() + it }
        val at = candidates.minOrNull()
        if (at == null) { am.cancel(pi); return }
        if (at - System.currentTimeMillis() < 30_000) {
            c.scope.launch { delay((at - System.currentTimeMillis()).coerceAtLeast(0)); evaluateNow() }
        }
        try {
            val exactAllowed = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
            if (exactAllowed) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    companion object {
        private const val TAG = "KinDisplay"
        const val FADE_MS = 1_500L
        const val DEFER_MS = 60_000L
        const val TOUCH_WAKE_MS = 5 * 60_000L
        const val BRIGHTNESS_OFF = 0f
    }
}

/** Exact-alarm target for schedule boundaries. */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FrameApp.graph(context).controller.display.evaluateNow()
    }
}
