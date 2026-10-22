package app.kinframe.family.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.R
import app.kinframe.family.data.ServerChoice
import app.kinframe.family.ui.FrameArt
import app.kinframe.family.ui.KinTextField
import app.kinframe.family.ui.KinTopBar
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.NoteTone
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.SecondaryButton
import app.kinframe.family.ui.Surface

/** Full-screen, centred, scrollable column for the onboarding steps. */
@Composable
private fun OnboardingPage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Kin.colors.paper).safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) { content() }
    }
}

@Composable
fun WelcomeScreen(state: OnboardingState, onUseDefault: () -> Unit, onOwnServer: () -> Unit) {
    val c = Kin.colors
    OnboardingPage {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { FrameArt(width = 190.dp) }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.welcome_title), style = Kin.type.display, color = c.ink, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.welcome_lede), style = Kin.type.body, color = c.inkSoft)
        Spacer(Modifier.height(8.dp))
        val label = state.defaultServer?.discovery?.name?.takeIf { it.isNotBlank() } ?: state.defaultServer?.display ?: "kinframe.appmire.be"
        PrimaryButton(stringResource(R.string.welcome_default_server, label), onUseDefault, Modifier.fillMaxWidth())
        SecondaryButton(stringResource(R.string.welcome_own_server), onOwnServer, Modifier.fillMaxWidth(), icon = KinIcons.Server)
        state.customError?.let { Note(stringResource(it), tone = NoteTone.Danger) }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.welcome_footer), style = Kin.type.caption, color = c.inkFaint, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun ServerScreen(state: OnboardingState, onInput: (String) -> Unit, onCheck: () -> Unit, onUse: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Kin.colors.paper)) {
        KinTopBar(stringResource(R.string.server_title), onBack = onBack)
        Box(Modifier.weight(1f).imePadding()) {
            OnboardingPage {
                Text(stringResource(R.string.server_body), style = Kin.type.body, color = Kin.colors.inkSoft)
                KinTextField(
                    value = state.customInput,
                    onValueChange = onInput,
                    label = stringResource(R.string.server_label),
                    placeholder = stringResource(R.string.server_placeholder),
                    error = state.customError?.let { stringResource(it) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false, capitalization = KeyboardCapitalization.None),
                    keyboardActions = KeyboardActions(onGo = { onCheck() }),
                )
                val found = state.customFound
                if (found == null) {
                    PrimaryButton(
                        stringResource(if (state.customChecking) R.string.server_checking else R.string.server_check), onCheck,
                        Modifier.fillMaxWidth(), enabled = state.customInput.isNotBlank(), busy = state.customChecking,
                    )
                } else {
                    ServerCard(found)
                    PrimaryButton(stringResource(R.string.server_use), onUse, Modifier.fillMaxWidth(), icon = KinIcons.ArrowRight)
                }
            }
        }
    }
}

@Composable
fun ServerCard(s: ServerChoice) {
    val c = Kin.colors
    Surface(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).background(c.sageSoft, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
                Icon(KinIcons.Check, null, tint = c.onSageSoft)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.server_found), style = Kin.type.caption, color = c.onSageSoft)
                Text(s.discovery.name, style = Kin.type.title, color = c.ink)
                Text(s.display + " · " + stringResource(R.string.server_version, s.discovery.version), style = Kin.type.caption, color = c.inkSoft)
            }
        }
    }
}

enum class SignInReason { None, Frame, Invite }

@Composable
fun SignInScreen(
    server: ServerChoice,
    state: OnboardingState,
    expired: Boolean,
    reason: SignInReason,
    onSignIn: () -> Unit,
    onDevEmail: (String) -> Unit,
    onDevName: (String) -> Unit,
    onDevSignIn: () -> Unit,
    onChangeServer: () -> Unit,
) {
    val c = Kin.colors
    OnboardingPage {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { FrameArt(width = 150.dp, tilt = 2f) }
        Text(stringResource(R.string.signin_connected, server.discovery.name), style = Kin.type.caption, color = c.onSageSoft,
            modifier = Modifier.background(c.sageSoft, androidx.compose.foundation.shape.CircleShape).padding(horizontal = 12.dp, vertical = 4.dp))
        Text(stringResource(R.string.welcome_title), style = Kin.type.headline, color = c.ink, modifier = Modifier.semantics { heading() })
        when {
            expired -> Note(stringResource(R.string.signin_expired), tone = NoteTone.Warn)
            reason == SignInReason.Frame -> Note(stringResource(R.string.signin_reason_frame))
            reason == SignInReason.Invite -> Note(stringResource(R.string.signin_reason_invite))
        }
        if (server.hasLogto) {
            PrimaryButton(
                stringResource(if (state.signingIn) R.string.signin_working else R.string.signin_button), onSignIn,
                Modifier.fillMaxWidth(), busy = state.signingIn, icon = KinIcons.User,
            )
            Text(stringResource(R.string.signin_hint), style = Kin.type.caption, color = c.inkSoft)
        } else if (!server.discovery.devAuth) {
            Note(stringResource(R.string.signin_no_auth), tone = NoteTone.Warn)
        }
        state.signInError?.let { Note(stringResource(it), tone = NoteTone.Danger) }
        if (server.discovery.devAuth) {
            HorizontalDivider(color = c.line)
            Text(stringResource(R.string.signin_dev_title), style = Kin.type.title, color = c.ink)
            Text(stringResource(R.string.signin_dev_hint), style = Kin.type.caption, color = c.inkSoft)
            KinTextField(state.devEmail, onDevEmail, stringResource(R.string.signin_dev_email),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
            KinTextField(state.devName, onDevName, stringResource(R.string.signin_dev_name),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onDevSignIn() }))
            SecondaryButton(stringResource(R.string.common_continue), onDevSignIn, Modifier.fillMaxWidth(), enabled = state.devEmail.contains('@') && !state.signingIn)
        }
        Spacer(Modifier.height(8.dp))
        QuietButton(stringResource(R.string.server_change) + " · " + server.display, onChangeServer, Modifier.fillMaxWidth())
    }
}

@Composable
fun NameScreen(name: String, busy: Boolean, error: Int?, onName: (String) -> Unit, onSave: () -> Unit) {
    val c = Kin.colors
    OnboardingPage {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { FrameArt(width = 150.dp) }
        Text(stringResource(R.string.name_title), style = Kin.type.headline, color = c.ink, modifier = Modifier.semantics { heading() })
        Text(stringResource(R.string.name_body), style = Kin.type.body, color = c.inkSoft)
        KinTextField(
            name, { if (it.length <= 40) onName(it) }, stringResource(R.string.name_label),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave() }),
            error = error?.let { stringResource(it) },
        )
        PrimaryButton(stringResource(R.string.common_continue), onSave, Modifier.fillMaxWidth(), enabled = name.isNotBlank(), busy = busy)
    }
}
