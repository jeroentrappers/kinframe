package app.kinframe.family.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
    /** Pick a new profile photo; null hides the profile photo buttons. */
    onChangePhoto: (() -> Unit)? = null,
    onRemovePhoto: (() -> Unit)? = null,
    savingAvatar: Boolean = false,
    /** The person's language; null hides Preferences. */
    onLanguage: ((String) -> Unit)? = null,
    /** Opens a page of the server's website (`/privacy`, `/terms`). */
    onOpenWeb: ((String) -> Unit)? = null,
    /** Settings → My photo sources; null when the server has no photo sources. */
    onPhotoSources: (() -> Unit)? = null,
    /** Settings → Telegram ("Link my Telegram"); null when the server has no Telegram bot. */
    telegram: (@Composable () -> Unit)? = null,
    /** Names of the frames the person owns; they hand those over or remove them before deleting the account. */
    ownedFrames: List<String> = emptyList(),
    /** Deletes the account (removeItems: also what they sent); null hides the button. */
    onDeleteAccount: ((Boolean) -> Unit)? = null,
    deletingAccount: Boolean = false,
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
            if (onChangePhoto != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SecondaryButton(stringResource(R.string.settings_change_photo), onChangePhoto, enabled = !savingAvatar, icon = KinIcons.Image)
                    if (me.avatarUrl != null && onRemovePhoto != null) QuietButton(stringResource(R.string.settings_remove_photo), onRemovePhoto, enabled = !savingAvatar)
                }
            }
            KinTextField(name, { name = it.take(40) }, stringResource(R.string.settings_display_name),
                supporting = stringResource(R.string.settings_display_name_hint),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words))
            if (name.trim() != me.displayName && name.isNotBlank()) PrimaryButton(stringResource(R.string.common_save), { onSaveName(name.trim()) }, busy = savingName)
        }

        if (onLanguage != null) {
            SectionTitle(stringResource(R.string.settings_preferences))
            Surface(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_language), style = Kin.type.label, color = c.ink)
                app.kinframe.family.ui.frame.LanguagePicker(me.locale ?: app.kinframe.family.ui.currentLocale().toLanguageTag(), onLanguage)
                Text(stringResource(R.string.settings_language_hint_app), style = Kin.type.caption, color = c.inkSoft)
            }
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

        if (onPhotoSources != null) {
            SectionTitle(stringResource(R.string.source_settings_title))
            Surface(Modifier.fillMaxWidth(), onClick = onPhotoSources) {
                Text(stringResource(R.string.source_settings_open), style = Kin.type.bodyStrong, color = c.ink)
                Text(stringResource(R.string.source_settings_hint), style = Kin.type.caption, color = c.inkSoft)
            }
        }

        telegram?.invoke()

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
        if (onDeleteAccount != null) {
            var asking by rememberSaveable { mutableStateOf(false) }
            QuietButton(stringResource(R.string.account_delete), { asking = true }, enabled = !deletingAccount)
            if (asking) DeleteAccountDialog(ownedFrames, deletingAccount, onDismiss = { asking = false }, onDelete = onDeleteAccount)
        }
        Text(stringResource(R.string.settings_about, BuildConfig.VERSION_NAME), style = Kin.type.caption, color = c.inkFaint)
        if (onOpenWeb != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuietButton(stringResource(R.string.legal_privacy), { onOpenWeb("/privacy") })
                QuietButton(stringResource(R.string.legal_terms), { onOpenWeb("/terms") })
            }
        }
    }
}

@Composable
private fun DeleteAccountDialog(owned: List<String>, deleting: Boolean, onDismiss: () -> Unit, onDelete: (Boolean) -> Unit) {
    val c = Kin.colors
    var removeItems by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text(stringResource(R.string.account_delete_title)) },
        text = {
            if (owned.isNotEmpty()) {
                Text(stringResource(R.string.account_delete_owner, owned.joinToString(", ")))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.account_delete_body))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                        .toggleable(removeItems, enabled = !deleting, role = Role.Checkbox) { removeItems = it }) {
                        Checkbox(removeItems, onCheckedChange = null)
                        Text(stringResource(R.string.account_delete_items), Modifier.padding(start = 8.dp))
                    }
                    if (!removeItems) Text(stringResource(R.string.account_delete_items_hint), style = Kin.type.caption, color = c.inkSoft)
                }
            }
        },
        confirmButton = {
            if (owned.isEmpty()) TextButton({ onDelete(removeItems) }, enabled = !deleting) {
                Text(stringResource(if (deleting) R.string.common_loading else R.string.account_delete), color = c.danger)
            }
        },
        dismissButton = { TextButton(onDismiss, enabled = !deleting) { Text(stringResource(if (owned.isEmpty()) R.string.common_cancel else R.string.common_done)) } },
    )
}

@Composable
private fun Row2(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = Kin.type.body, color = Kin.colors.inkSoft, modifier = Modifier.weight(1f))
        Text(value, style = Kin.type.bodyStrong, color = Kin.colors.ink)
    }
}
