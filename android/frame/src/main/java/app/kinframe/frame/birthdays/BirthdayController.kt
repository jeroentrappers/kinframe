package app.kinframe.frame.birthdays

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import app.kinframe.core.api.FrameBirthday
import app.kinframe.core.api.FrameBirthdays
import app.kinframe.core.util.AtomicFile
import app.kinframe.frame.FrameController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Collections

/**
 * The frame side of birthdays (docs/spec/birthdays.md §6, §7). Keeps the last `birthdays` change (persisted with the
 * change feed, like the calendar), works out "today" in the frame's time zone at every minute boundary and on clock
 * or time-zone changes, caches the photos by URL, and sends greetings through the outbox.
 *
 * Nothing here wakes the screen: a birthday never changes the display mode.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BirthdayController(private val c: FrameController) {
    private val g = c.g

    /** The people on this frame (merged by the server across feeds), sorted by month and day. */
    val people: StateFlow<List<FrameBirthday>> by lazy {
        c.syncState.map { it?.birthdaysJson }.distinctUntilChanged()
            .map { FrameBirthdays.parse(it)?.people.orEmpty() }
            .flowOn(Dispatchers.Default).stateIn(c.scope, SharingStarted.Eagerly, emptyList())
    }

    /** Re-evaluated at every minute boundary and on clock or zone changes. */
    private val tick = MutableStateFlow(0L)

    /** The frame's local date, or null while its clock is not trusted (then nothing birthday-related shows). */
    val today: StateFlow<LocalDate?> by lazy {
        combine(tick, g.time.clockWrong) { _, wrong -> BirthdayLogic.trustedDate(g.clock.nowMs(), g.clock.zone, wrong) }
            .distinctUntilChanged().stateIn(c.scope, SharingStarted.Eagerly, null)
    }

    /** Today's people in card order; empty on other days or while the clock is wrong. */
    val todays: StateFlow<List<BirthdayOccurrence>> by lazy {
        combine(people, today) { p, d -> BirthdayLogic.card(p, d) }
            .distinctUntilChanged().stateIn(c.scope, SharingStarted.Eagerly, emptyList())
    }

    /** Birthdays 1–7 days ahead, nearest first; empty while the clock is wrong. */
    val upcoming: StateFlow<List<BirthdayDay>> by lazy {
        combine(people, today) { p, d -> BirthdayLogic.countdowns(p, d) }
            .distinctUntilChanged().stateIn(c.scope, SharingStarted.Eagerly, emptyList())
    }

    /** Ids greeted from this frame today (persisted per local date: "Sent ✓" for the rest of the day). */
    val sent: StateFlow<Set<String>> by lazy {
        today.flatMapLatest { d -> if (d == null) flowOf(emptyList()) else g.db.birthdayGreetings().observeForDate(d.toString()) }
            .map { it.toSet() }.stateIn(c.scope, SharingStarted.Eagerly, emptySet())
    }

    private val _sheetOpen = MutableStateFlow(false)
    /** The greeting sheet (one button per person on today's card). */
    val sheetOpen: StateFlow<Boolean> = _sheetOpen

    private val nudge = Channel<Unit>(Channel.CONFLATED)

    fun start() {
        c.scope.launch {
            while (true) {
                val now = g.clock.nowMs()
                tick.value = now
                withTimeoutOrNull(60_000 - now % 60_000 + 50) { nudge.receive() }
            }
        }
        // The settings' time zone is applied to the clock elsewhere; re-evaluate right away when it changes.
        c.scope.launch {
            g.settings.settings.map { it.locale.timezone }.distinctUntilChanged().collect {
                kotlinx.coroutines.delay(200)
                nudge.trySend(Unit)
            }
        }
        c.scope.launch { g.time.clockWrong.collect { nudge.trySend(Unit) } }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { nudge.trySend(Unit) }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        runCatching { ContextCompat.registerReceiver(g.app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED) }
        c.scope.launch { people.collect { list -> withContext(Dispatchers.IO) { syncPhotos(list) } } }
        // The card's people changed (removed on the day, the clock became wrong): an open sheet follows.
        c.scope.launch { todays.collect { if (it.isEmpty()) _sheetOpen.value = false } }
    }

    fun openSheet() {
        if (todays.value.isNotEmpty()) _sheetOpen.value = true
    }

    fun closeSheet() { _sheetOpen.value = false }

    /** "Say happy birthday to Oma": once per person and local date; queued while offline. */
    fun greet(o: BirthdayOccurrence) {
        val date = today.value ?: return
        if (o.date != date) return
        c.scope.launch { c.session.value?.outbox?.birthdayGreeting(o.id, date.toString()) }
    }

    // ------------------------------------------------------------------------------------ photos

    private val dir: File by lazy { File(g.app.filesDir, "birthdays").apply { mkdirs() } }
    private val attempted = Collections.synchronizedSet(HashSet<String>())
    private val memory = Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 12
    })

    private fun fileFor(url: String) = File(dir, fileName(url))

    /** Downloads photos the list needs and deletes the ones no entry references any more (§11). */
    private fun syncPhotos(list: List<FrameBirthday>) {
        val keep = list.mapNotNull { it.photoUrl?.takeIf { u -> u.isNotBlank() }?.let { u -> fileName(u) } }.toSet()
        dir.listFiles()?.filter { it.isFile && it.name !in keep }?.forEach { it.delete() }
        memory.keys.retainAll(list.mapNotNull { it.photoUrl }.toSet())
    }

    /** The person's photo, cached on disk by URL and decoded at most 160 px wide. */
    suspend fun photo(url: String?): ImageBitmap? {
        if (url.isNullOrBlank()) return null
        memory[url]?.let { return it }
        return withContext(Dispatchers.IO) {
            val f = fileFor(url)
            if (!f.exists() && attempted.add(url)) {
                runCatching {
                    val target = g.api.resolve(url) ?: return@runCatching
                    g.api.media(target).use { r -> if (r.isSuccessful) AtomicFile.write(f, r.body.bytes()) }
                }.onFailure { attempted.remove(url); Log.i(TAG, "birthday photo: ${it.message}") }
            }
            if (!f.exists()) return@withContext null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 160) sample *= 2
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()?.also { memory[url] = it }
        }
    }

    companion object {
        private const val TAG = "KinBirthdays"

        fun fileName(url: String): String =
            MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }.take(32) + ".webp"
    }
}
