package app.kinframe.family.ui.chats

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.core.social.VoiceClip
import app.kinframe.core.social.VoiceStatus
import app.kinframe.family.R
import app.kinframe.family.util.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Waveform helpers: the local preview while recording, and the server's 64 values (README §5.19). */
object Waveform {
    /** Down-samples loudness samples (0–100) to [n] bars by taking the peak of each bucket. */
    fun bars(samples: List<Int>, n: Int = 64): List<Int> {
        if (samples.isEmpty()) return emptyList()
        if (samples.size <= n) return samples
        return (0 until n).map { i ->
            val from = i * samples.size / n
            val to = ((i + 1) * samples.size / n).coerceAtLeast(from + 1)
            samples.subList(from, to).max()
        }
    }

    /** `MediaRecorder.getMaxAmplitude` (0–32767) to 0–100 on a rough log scale, so speech fills the bars. */
    fun level(amplitude: Int): Int {
        if (amplitude <= 0) return 0
        val db = 20 * kotlin.math.log10(amplitude / 32767.0)
        return ((db + 50) * 2).toInt().coerceIn(0, 100)
    }
}

/**
 * Records a voice message as AAC-LC, mono, 48 kHz, 64 kbit/s in MPEG-4 (`.m4a`): what the server keeps, so it copies
 * the stream instead of transcoding (README §5.19).
 */
class VoiceRecorder(private val context: Context, private val dir: File, private val maxMs: Long) {
    sealed interface State {
        data object Idle : State
        data class Recording(val startedAt: Long, val levels: List<Int>) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var ticker: Job? = null

    /** False when the microphone can't be opened. [onLimit] fires when [maxMs] is reached (the recording stops). */
    fun start(scope: CoroutineScope, onLimit: () -> Unit): Boolean {
        stopQuietly()
        val f = File(dir, "rec-${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(48_000)
            r.setAudioEncodingBitRate(64_000)
            r.setMaxDuration(maxMs.toInt())
            r.setOutputFile(f.absolutePath)
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onLimit() }
            r.prepare()
            r.start()
            recorder = r
            file = f
            val started = System.currentTimeMillis()
            _state.value = State.Recording(started, emptyList())
            ticker = scope.launch {
                while (isActive) {
                    delay(100)
                    val level = Waveform.level(runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0))
                    val s = _state.value as? State.Recording ?: break
                    _state.value = s.copy(levels = (s.levels + level).takeLast(600))
                }
            }
            true
        } catch (e: Exception) {
            Log.w("KinVoice", "cannot record", e)
            runCatching { r.release() }
            f.delete()
            false
        }
    }

    /** Stops and returns the file and its length, or null when nothing usable was recorded (under 1 s). */
    fun finish(): Pair<File, Long>? {
        val s = _state.value as? State.Recording ?: return null
        val f = file
        val ok = runCatching { recorder?.stop() }.isSuccess
        release()
        val ms = System.currentTimeMillis() - s.startedAt
        if (!ok || f == null || ms < MIN_MS) { f?.delete(); return null }
        return f to ms.coerceAtMost(maxMs)
    }

    fun cancel() {
        val f = file
        stopQuietly()
        f?.delete()
    }

    private fun stopQuietly() {
        runCatching { recorder?.stop() }
        release()
    }

    private fun release() {
        ticker?.cancel()
        runCatching { recorder?.release() }
        recorder = null
        file = null
        _state.value = State.Idle
    }

    companion object {
        const val MIN_MS = 1000L
    }
}

/** One player for the screen: starting a voice message stops the one that was playing. */
class VoicePlayer {
    data class Playing(val itemId: String, val positionMs: Long, val durationMs: Long, val preparing: Boolean)

    private val _state = MutableStateFlow<Playing?>(null)
    val state: StateFlow<Playing?> = _state.asStateFlow()
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    fun toggle(scope: CoroutineScope, itemId: String, url: suspend () -> String, onError: () -> Unit) {
        val cur = _state.value
        if (cur?.itemId == itemId) {
            val p = player ?: return
            if (p.isPlaying) { p.pause(); _state.value = cur.copy(preparing = false) } else { p.start(); tick(scope) }
            return
        }
        stop()
        _state.value = Playing(itemId, 0, 0, preparing = true)
        scope.launch {
            val u = runCatching { url() }.getOrNull()
            if (u == null || _state.value?.itemId != itemId) { if (u == null) { stop(); onError() }; return@launch }
            val p = MediaPlayer()
            player = p
            try {
                p.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                p.setDataSource(u)
                p.setOnPreparedListener { mp -> mp.start(); _state.value = Playing(itemId, 0, mp.duration.toLong(), false); tick(scope) }
                p.setOnCompletionListener { stop() }
                p.setOnErrorListener { _, _, _ -> stop(); onError(); true }
                p.prepareAsync()
            } catch (e: Exception) {
                stop(); onError()
            }
        }
    }

    private fun tick(scope: CoroutineScope) {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val p = player ?: break
                val s = _state.value ?: break
                _state.value = s.copy(positionMs = runCatching { p.currentPosition.toLong() }.getOrDefault(0), preparing = false)
                delay(200)
            }
        }
    }

    fun isPlaying(itemId: String): Boolean = _state.value?.itemId == itemId && player?.isPlaying == true

    fun stop() {
        ticker?.cancel()
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        _state.value = null
    }
}

val LocalVoicePlayer = staticCompositionLocalOf { VoicePlayer() }

/** Bars of a waveform; the part already played in the accent colour. */
@Composable
fun WaveformBars(levels: List<Int>, progress: Float, modifier: Modifier = Modifier, color: Color = Kin.colors.inkSoft, played: Color = Kin.colors.accent) {
    val bars = remember(levels) { Waveform.bars(levels, 48).ifEmpty { List(48) { 8 } } }
    Canvas(modifier) {
        val n = bars.size
        val gap = 2.dp.toPx()
        val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        bars.forEachIndexed { i, v ->
            val h = (size.height * (0.12f + 0.88f * v / 100f))
            val x = i * (w + gap)
            drawRoundRect(if (i.toFloat() / n < progress) played else color, Offset(x, (size.height - h) / 2), Size(w, h), CornerRadius(w / 2))
        }
    }
}

/** A voice message bubble's player: play/pause, waveform with progress, length. */
@Composable
fun VoiceMessage(clip: VoiceClip, url: suspend () -> String, onError: () -> Unit) {
    val c = Kin.colors
    val player = LocalVoicePlayer.current
    val scope = rememberCoroutineScope()
    val st by player.state.collectAsState()
    val mine = st?.takeIf { it.itemId == clip.itemId }
    val duration = (mine?.durationMs?.takeIf { it > 0 } ?: clip.durationMs ?: 0L)
    val progress = if (mine != null && duration > 0) mine.positionMs.toFloat() / duration else 0f
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            clip.status == VoiceStatus.PROCESSING -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                Text(stringResource(R.string.chat_voice_processing), style = Kin.type.caption, color = c.inkSoft)
            }
            clip.status == VoiceStatus.FAILED -> Text(stringResource(R.string.chat_voice_failed), style = Kin.type.caption, color = c.danger)
            else -> {
                IconButton({ player.toggle(scope, clip.itemId, url, onError) }) {
                    if (mine?.preparing == true) CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                    else Icon(if (mine != null && player.isPlaying(clip.itemId)) KinIcons.Pause else KinIcons.Play,
                        stringResource(if (mine != null && player.isPlaying(clip.itemId)) R.string.chat_voice_pause else R.string.chat_voice_play), tint = c.accent)
                }
                WaveformBars(clip.waveform, progress, Modifier.width(150.dp).height(28.dp))
                Text(Format.clock(if (mine != null) mine.positionMs.coerceAtLeast(0) else duration), style = Kin.type.caption, color = c.inkSoft)
            }
        }
    }
}

/** Keeps one [VoicePlayer] per screen and stops it when the screen goes. */
@Composable
fun rememberVoicePlayer(): VoicePlayer {
    val p = remember { VoicePlayer() }
    DisposableEffect(p) { onDispose { p.stop() } }
    return p
}

@Composable
fun RecordingBar(levels: List<Int>, elapsedMs: Long, onCancel: () -> Unit, onSend: () -> Unit) {
    val c = Kin.colors
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(onCancel) { Icon(KinIcons.Trash, stringResource(R.string.chat_voice_cancel), tint = c.danger) }
        Text("● " + Format.clock(elapsedMs), style = Kin.type.label, color = c.danger)
        WaveformBars(levels.takeLast(48), 0f, Modifier.weight(1f).height(28.dp), color = c.accent)
        IconButton(onSend) { Icon(KinIcons.Send, stringResource(R.string.chat_voice_send), tint = c.accent) }
    }
}
