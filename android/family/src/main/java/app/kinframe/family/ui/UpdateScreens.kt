package app.kinframe.family.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R

/** Below the server's minimum build: nothing else is usable until the app is updated (README §5.15). */
@Composable
fun UpdateRequiredScreen(checking: Boolean, onUpdate: () -> Unit, onCheckAgain: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Kin.colors.paper).windowInsetsPadding(WindowInsets.safeDrawing), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FrameArt()
            Text(stringResource(R.string.update_required_title), style = Kin.type.headline, color = Kin.colors.ink,
                textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Text(stringResource(R.string.update_required_body), style = Kin.type.body, color = Kin.colors.inkSoft, textAlign = TextAlign.Center)
            PrimaryButton(stringResource(R.string.update_action), onUpdate, Modifier.fillMaxWidth().padding(top = 8.dp), icon = KinIcons.Download)
            QuietButton(if (checking) stringResource(R.string.update_checking) else stringResource(R.string.update_check_again),
                onCheckAgain, Modifier.fillMaxWidth(), enabled = !checking)
        }
    }
}

/** Below the recommended build: "A new version of kinframe is available" · Update · ×. */
@Composable
fun UpdateBanner(onUpdate: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(Kin.colors.accentSoft).padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.update_available), style = Kin.type.label, color = Kin.colors.onAccentSoft, modifier = Modifier.weight(1f))
        TextButton(onUpdate) { Text(stringResource(R.string.update_action), style = Kin.type.label, color = Kin.colors.onAccentSoft) }
        IconButton(onDismiss) { Icon(KinIcons.Close, stringResource(R.string.update_dismiss), tint = Kin.colors.onAccentSoft) }
    }
}
