package app.kinframe.family.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinframe.core.design.Kin
import app.kinframe.family.AppGraph
import app.kinframe.family.R
import app.kinframe.family.ui.AppViewModel
import app.kinframe.family.ui.KinTopBar
import app.kinframe.family.ui.LocalToaster
import app.kinframe.family.ui.MemoriesRoute
import app.kinframe.family.ui.Navigator
import app.kinframe.family.ui.PageColumn
import app.kinframe.family.ui.Phase
import app.kinframe.family.ui.QuietButton
import app.kinframe.family.ui.SectionTitle
import app.kinframe.family.ui.SocialFlags
import app.kinframe.family.ui.SocialSettingsSection
import app.kinframe.family.ui.Surface
import app.kinframe.family.ui.channels.TelegramLinkSettings
import app.kinframe.family.util.Format

/**
 * Settings → Accounts → one server (multi-server.md §2.1): what is that server's own, with several accounts —
 * reminders and chats (email digest and read receipts are in Reminders), blocked people, the operator's reports,
 * memories, the Telegram link, the server's limits and deleting the account there. Every call goes to that server.
 * [ownedFrames] are the names of that server's frames the person owns (handed over or removed before deleting).
 */
@Composable
fun AccountSettingsScreen(graph: AppGraph, ready: Phase.Ready, nav: Navigator, app: AppViewModel, accountId: String, ownedFrames: List<String>) {
    val c = Kin.colors
    val context = LocalContext.current
    val res = LocalResources.current
    val toaster = LocalToaster.current
    val accounts by graph.accounts.accounts.collectAsStateWithLifecycle()
    val account = accounts.firstOrNull { it.id == accountId }
    // Signed out of (or deleted) elsewhere: back to Settings.
    LaunchedEffect(account) { if (account == null) nav.pop() }
    if (account == null) return
    val server by account.servers.current.collectAsStateWithLifecycle()
    val me by account.me.collectAsStateWithLifecycle()
    val s = server ?: return
    val d = s.discovery
    val flags = SocialFlags.of(d)
    var deleting by rememberSaveable { mutableStateOf(false) }
    var asking by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(c.paper)) {
        KinTopBar(account.label, onBack = { nav.pop() }, subtitle = account.host)
        PageColumn {
            me?.let { Text(stringResource(R.string.settings_signed_in_as, it.displayName), style = Kin.type.caption, color = c.inkSoft) }
            SocialSettingsSection(flags, me?.isOperator == true, account.id, nav)
            if (d.leaving) {
                SectionTitle(stringResource(R.string.leaving_memories_title))
                Surface(Modifier.fillMaxWidth(), onClick = { nav.push(MemoriesRoute(account.id)) }) {
                    Text(stringResource(R.string.leaving_memories_on, account.label), style = Kin.type.bodyStrong, color = c.ink)
                    Text(stringResource(R.string.leaving_memories_entry_hint), style = Kin.type.caption, color = c.inkSoft)
                }
            }
            if (d.telegram) TelegramLinkSettings(graph, ready, account.id)

            SectionTitle(stringResource(R.string.settings_server))
            Surface(Modifier.fillMaxWidth()) {
                Row2(stringResource(R.string.settings_server_name), d.name)
                Row2(stringResource(R.string.settings_address), s.display)
                Row2(stringResource(R.string.settings_version), d.version)
                Row2(stringResource(R.string.settings_max_items), s.limits.maxItemsPerSend.toString())
                Row2(stringResource(R.string.settings_max_video), Format.duration(context, s.limits.maxVideoSeconds.toLong()))
                Row2(stringResource(R.string.settings_max_upload), Format.bytes(context, s.limits.maxUploadBytes))
            }

            QuietButton(stringResource(R.string.account_delete), { asking = true }, enabled = !deleting, danger = true)
            Text(stringResource(R.string.servers_account_delete_hint, account.label), style = Kin.type.caption, color = c.inkSoft)
            if (asking) DeleteAccountDialog(ownedFrames, deleting, onDismiss = { asking = false }, choice = d.leaving, onDelete = { removeItems ->
                deleting = true
                app.deleteAccount(removeItems, account) { err -> deleting = false; toaster.show(res.getString(err)) }
            })
        }
    }
}
