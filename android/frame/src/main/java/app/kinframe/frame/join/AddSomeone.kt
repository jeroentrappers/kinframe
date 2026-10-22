package app.kinframe.frame.join

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinButton
import app.kinframe.core.design.KinHeading
import app.kinframe.core.design.KinIcon
import app.kinframe.core.design.KinIcons
import app.kinframe.frame.FrameController
import app.kinframe.frame.R
import app.kinframe.frame.setup.CodeReadyContent
import app.kinframe.frame.setup.CodeState
import app.kinframe.frame.ui.Format
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * "Add someone" from the quick menu: the frame's code and QR code on an already set-up frame (same code as during
 * setup; the server turns an entry on a claimed frame into a join request). Closes when that request arrives, so
 * the frame's owner sees the request right away, or after [OPEN_MS] untouched.
 */
@Composable
fun AddSomeoneOverlay(c: FrameController, visible: Boolean, pendingRequests: Int, onClose: () -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(tween(220)) + slideInVertically(tween(280)) { it / 12 }, exit = fadeOut(tween(200))) {
        var code by remember { mutableStateOf<CodeState>(CodeState.Loading) }
        var attempt by remember { mutableStateOf(0) }
        var now by remember { mutableLongStateOf(c.g.clock.nowMs()) }
        val requestsAtOpen = remember { pendingRequests }
        LaunchedEffect(pendingRequests) { if (pendingRequests > requestsAtOpen) onClose() }
        LaunchedEffect(Unit) { delay(OPEN_MS); onClose() }
        LaunchedEffect(attempt) {
            while (true) {
                now = c.g.clock.nowMs()
                val current = code as? CodeState.Ready
                if (current == null || current.expiresAtMs - now < REFRESH_BEFORE_MS) {
                    code = try {
                        val fc = c.g.api.code()
                        val exp = runCatching { Instant.parse(fc.expiresAt).toEpochMilli() }.getOrDefault(now + 12 * 3600_000L)
                        CodeState.Ready(fc, exp)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        CodeState.Error(offline = !c.g.network.status.value.connected)
                    }
                    if (code is CodeState.Error) break
                }
                delay(15_000)
            }
        }
        val context = LocalContext.current
        Box(Modifier.fillMaxSize().background(Kin.colors.paper).clickable(enabled = false) {}) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 40.dp)) {
                val landscape = maxWidth > maxHeight
                when (val s = code) {
                    CodeState.Loading -> CircularProgressIndicator(Modifier.size(48.dp).align(Alignment.Center), color = Kin.colors.accent, strokeWidth = 3.dp)
                    is CodeState.Error -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        KinHeading(
                            stringResource(if (s.offline) R.string.code_offline_title else R.string.code_error_title),
                            stringResource(if (s.offline) R.string.code_offline_body else R.string.code_error_body), center = true,
                        )
                        Spacer(Modifier.height(24.dp))
                        KinButton(stringResource(R.string.action_try_again), { code = CodeState.Loading; attempt++ })
                    }
                    is CodeState.Ready -> CodeReadyContent(
                        code = Format.code(s.code.code),
                        joinUrl = s.code.joinUrl,
                        webHost = c.g.webHostDisplay(),
                        validFor = Format.remaining(context, s.expiresAtMs - now),
                        landscape = landscape,
                        title = R.string.add_someone_title,
                    )
                }
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(24.dp).size(56.dp).clip(CircleShape)
                    .background(Kin.colors.ink.copy(alpha = 0.08f)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { KinIcon(KinIcons.Close, stringResource(R.string.weather_close), tint = Kin.colors.ink, size = 24.dp) }
        }
    }
}

private const val OPEN_MS = 10 * 60_000L
private const val REFRESH_BEFORE_MS = 5 * 60_000L
