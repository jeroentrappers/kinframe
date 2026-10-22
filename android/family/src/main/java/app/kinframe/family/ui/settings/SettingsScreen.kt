package app.kinframe.family.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.kinframe.core.api.User
import app.kinframe.core.design.Kin
import app.kinframe.core.design.KinIcons
import app.kinframe.family.BuildConfig
import app.kinframe.family.R
import app.kinframe.family.data.ServerChoice
import app.kinframe.family.notify.PushStatus
import app.kinframe.family.ui.Avatar
import app.kinframe.family.ui.KinTextField
import app.kinframe.family.ui.Note
import app.kinframe.family.ui.NoteTone
import app.kinframe.family.ui.PageColumn
import app.kinframe.family.ui.PrimaryButton
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.SecondaryButton
import app.kinframe.family.ui.SectionTitle
import app.kinframe.family.ui.Surface
import app.kinframe.family.util.Format

@Composable
fun SettingsScreen(
    me: User,
    server: ServerChoice,
    devMode: Boolean,
    push: PushStatus,
    notificationsAllowed: Boolean,
    savingName: Boolean,
    onSaveName: (String) -> Unit,
    onChoosePush: () -> Unit,
    onAllowNotifications: () -> Unit,
    onSignOut: () -> Unit,
    onChangeServer: () -> Unit,
) {
    val c = Kin.colors
    val context = LocalContext.current
    var name by rememberSaveable(me.displayName) { mutableStateOf(me.displayName) }
    PageColumn {
        Text(stringResource(R.string.settings_title), style = Kin.type.headline, color = c.ink, modifier = Modifier.semantics { heading() })

        SectionTitle(stringResource(R.string.settings_profile))
        Surface(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(me.displayName, me.avatarUrl, 52.dp)
                Column(Modifier.weight(1f)) {
                    Text(me.displayName, style = Kin.type.title, color = c.ink)
                    me.email?.let { Text(it, style = Kin.type.caption, color = c.inkSoft) }
                }
            }
            KinTextField(name, { name = it.take(40) }, stringResource(R.string.settings_display_name),
                supporting = stringResource(R.string.settings_display_name_hint),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            if (name.trim() != me.displayName && name.isNotBlank()) PrimaryButton(stringResource(R.string.common_save), { onSaveName(name.trim()) }, busy = savingName)
        }

        SectionTitle(stringResource(R.string.settings_notifications))
        Surface(Modifier.fillMaxWidth()) {
            if (!notificationsAllowed) {
                Note(stringResource(R.string.settings_notifications_blocked), tone = NoteTone.Warn, icon = KinIcons.Bell)
                SecondaryButton(stringResource(R.string.settings_push_enable), onAllowNotifications, icon = KinIcons.Bell)
            }
            when (push) {
                is PushStatus.Active -> Text(
                    if (push.via == "Firebase Cloud Messaging") stringResource(R.string.settings_push_fcm) else stringResource(R.string.settings_push_on, push.via),
                    style = Kin.type.body, color = c.inkSoft,
                )
                PushStatus.NeedsChoice -> SecondaryButton(stringResource(R.string.settings_push_pick), onChoosePush, icon = KinIcons.Bell)
                PushStatus.Registering -> Text(stringResource(R.string.common_loading), style = Kin.type.body, color = c.inkSoft)
                PushStatus.Unavailable -> Text(stringResource(R.string.settings_push_none), style = Kin.type.body, color = c.inkSoft)
            }
        }

        SectionTitle(stringResource(R.string.settings_server))
        Surface(Modifier.fillMaxWidth()) {
            Row2(stringResource(R.string.settings_server_name), server.discovery.name)
            Row2(stringResource(R.string.settings_address), server.display)
            Row2(stringResource(R.string.settings_version), server.discovery.version)
            Row2(stringResource(R.string.settings_max_items), server.limits.maxItemsPerSend.toString())
            Row2(stringResource(R.string.settings_max_video), Format.duration(context, server.limits.maxVideoSeconds.toLong()))
            Row2(stringResource(R.string.settings_max_upload), Format.bytes(context, server.limits.maxUploadBytes))
            if (devMode) Note(stringResource(R.string.settings_dev_mode), tone = NoteTone.Warn)
            QuietButton(stringResource(R.string.server_change), onChangeServer)
            Text(stringResource(R.string.settings_change_server_hint), style = Kin.type.caption, color = c.inkFaint)
        }

        Text(stringResource(R.string.settings_signed_in_as, me.displayName), style = Kin.type.caption, color = c.inkSoft)
        SecondaryButton(stringResource(R.string.settings_sign_out), onSignOut, Modifier.fillMaxWidth(), icon = KinIcons.SignOut)
        Text(stringResource(R.string.settings_about, BuildConfig.VERSION_NAME), style = Kin.type.caption, color = c.inkFaint)
    }
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = Kin.type.body, color = Kin.colors.inkSoft, modifier = Modifier.weight(1f))
        Text(value, style = Kin.type.bodyStrong, color = Kin.colors.ink)
    }
}
