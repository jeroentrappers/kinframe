package app.kinframe.family.ui.family

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R
import app.kinframe.family.ui.ContentMaxWidth
import app.kinframe.family.ui.KinTextField
import app.kinframe.family.ui.KinTopBar
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.feeds.FeedsViewModel

/** Where the person is in "getting started" (docs/spec/simple-app.md §8). */
sealed interface SetupStep : java.io.Serializable {
    /** "Start a family" or "Join my family". */
    data object Choice : SetupStep { private fun readResolve(): Any = Choice }
    /** The family's name. */
    data object Name : SetupStep { private fun readResolve(): Any = Name }
    /** "Add Oma's frame now?" for the family just made. */
    data class Frame(val feedId: String, val name: String) : SetupStep
    /** The invite share sheet. */
    data class Invite(val feedId: String, val name: String) : SetupStep
}

/** What getting started does; wired by the root. */
class SetupActions(
    val onStep: (SetupStep?) -> Unit,
    val onCreate: (String) -> Unit,
    val onJoin: () -> Unit,
    val onPair: (feedId: String) -> Unit,
    val onShareInvite: (feedId: String, name: String) -> Unit,
)

/**
 * First start, signed in without a family: Start a family (name, Oma's frame now or later, invite) or Join my family
 * (a link or code). Someone without a family sees nothing else.
 */
@Composable
fun GettingStartedScreen(step: SetupStep, defaultName: String, creating: Boolean, error: Int?, a: SetupActions) {
    val c = Kin.colors
    Column(Modifier.fillMaxSize().background(c.paper).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val back: (() -> Unit)? = when (step) {
            SetupStep.Choice -> null
            SetupStep.Name -> ({ a.onStep(SetupStep.Choice) })
            else -> null
        }
        if (back != null) KinTopBar("", onBack = back)
        Box(Modifier.weight(1f).imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                when (step) {
                    SetupStep.Choice -> Choice(a)
                    SetupStep.Name -> NameStep(defaultName, creating, error, a)
                    is SetupStep.Frame -> FrameStep(step, a)
                    is SetupStep.Invite -> InviteStep(step, a)
                }
            }
        }
    }
}

@Composable
private fun Heading(title: String, lede: String?) {
    val c = Kin.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(c.accent), contentAlignment = Alignment.Center) {
            Icon(KinIcons.HeartFilled, null, Modifier.size(30.dp), tint = c.onAccent)
        }
        Text(title, style = Kin.type.display, color = c.ink, modifier = Modifier.semantics { heading() })
        lede?.let { Text(it, style = Kin.type.body, color = c.inkSoft) }
    }
}

@Composable
private fun Choice(a: SetupActions) {
    Heading(stringResource(R.string.start_title), stringResource(R.string.start_lede))
    ChoiceCard(KinIcons.Frame, stringResource(R.string.start_family), stringResource(R.string.start_family_body), primary = true) { a.onStep(SetupStep.Name) }
    ChoiceCard(KinIcons.People, stringResource(R.string.start_join), stringResource(R.string.start_join_body), primary = false, onClick = a.onJoin)
}

@Composable
fun ChoiceCard(icon: ImageVector, title: String, body: String, primary: Boolean, onClick: () -> Unit) {
    val c = Kin.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface)
            .border(if (primary) 2.dp else 1.dp, if (primary) c.accent else c.line, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if (primary) c.accentSoft else c.sageSoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (primary) c.onAccentSoft else c.onSageSoft)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = Kin.type.title, color = c.ink)
            Text(body, style = Kin.type.body, color = c.inkSoft)
        }
    }
}

@Composable
private fun NameStep(defaultName: String, creating: Boolean, error: Int?, a: SetupActions) {
    var name by rememberSaveable { mutableStateOf(defaultName) }
    Heading(stringResource(R.string.start_name_title), null)
    KinTextField(name, { name = it.take(FeedsViewModel.NAME_MAX) }, stringResource(R.string.feed_name),
        placeholder = stringResource(R.string.start_name_placeholder),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        error = error?.let { stringResource(it) })
    PrimaryButton(stringResource(R.string.common_continue), { a.onCreate(name) }, Modifier.fillMaxWidth(), enabled = name.isNotBlank() && !creating, busy = creating)
}

@Composable
private fun FrameStep(step: SetupStep.Frame, a: SetupActions) {
    Heading(stringResource(R.string.start_frame_title), stringResource(R.string.start_frame_body))
    PrimaryButton(stringResource(R.string.start_frame_pair), { a.onPair(step.feedId) }, Modifier.fillMaxWidth(), icon = KinIcons.Frame)
    QuietButton(stringResource(R.string.start_later), { a.onStep(SetupStep.Invite(step.feedId, step.name)) }, Modifier.fillMaxWidth())
}

@Composable
private fun InviteStep(step: SetupStep.Invite, a: SetupActions) {
    Heading(stringResource(R.string.start_invite_title), stringResource(R.string.start_invite_body, step.name))
    PrimaryButton(stringResource(R.string.start_invite_share), { a.onShareInvite(step.feedId, step.name) }, Modifier.fillMaxWidth(), icon = KinIcons.Share)
    QuietButton(stringResource(R.string.common_done), { a.onStep(null) }, Modifier.fillMaxWidth())
}

/** Asked once after joining someone else's family (docs/spec/simple-app.md §6). */
@Composable
fun ViewQuestionDialog(onNormal: () -> Unit, onSimple: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNormal,
        title = { Text(stringResource(R.string.view_question_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceCard(KinIcons.Home, stringResource(R.string.simple_normal_view), stringResource(R.string.view_normal_body), primary = true, onClick = onNormal)
                ChoiceCard(KinIcons.Heart, stringResource(R.string.simple_badge), stringResource(R.string.view_simple_body), primary = false, onClick = onSimple)
            }
        },
        confirmButton = {},
        containerColor = Kin.colors.paper,
    )
}
