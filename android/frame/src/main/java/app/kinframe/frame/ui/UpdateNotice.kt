package app.kinframe.frame.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.frame.R
import app.kinframe.frame.update.UpdateManager
import kotlinx.coroutines.delay

/** Small, calm message at the bottom of the frame about a self-update ("Updated to 0.2.0"). Tap or wait to dismiss. */
@Composable
fun UpdateNotice(update: UpdateManager) {
    val notice by update.notice.collectAsState()
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(if (notice is UpdateManager.Notice.NeedsUser) 30_000 else 8_000)
            update.dismissNotice()
        }
    }
    Box(Modifier.fillMaxSize().padding(bottom = 40.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = notice != null, enter = fadeIn(), exit = fadeOut()) {
            val text = when (val n = notice) {
                is UpdateManager.Notice.Updated -> stringResource(R.string.update_notice_updated, n.versionName)
                UpdateManager.Notice.RolledBack -> stringResource(R.string.update_notice_rolled_back)
                UpdateManager.Notice.NeedsUser -> stringResource(R.string.update_notice_needs_user)
                null -> ""
            }
            Text(
                text, style = Kin.type.body, color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 720.dp).clip(RoundedCornerShape(28.dp))
                    .background(Color.Black.copy(alpha = 0.62f)).clickable { update.dismissNotice() }
                    .padding(horizontal = 28.dp, vertical = 14.dp),
            )
        }
    }
}
