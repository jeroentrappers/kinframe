package app.kinframe.family.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kinframe.core.design.Kin
import app.kinframe.family.AppGraph
import app.kinframe.family.R
import app.kinframe.family.accounts.AccountContext

/**
 * The account of the entity a screen shows ([key]: its frame, family, post, conversation, person… id), as the routing
 * index knows it (multi-server.md §2.2): that server's discovery and limits, and the person's profile there. Screens
 * use it for flag-gated sections and "is this mine" checks instead of the primary account's. Unknown ids are the
 * primary account's (the routing clients send them there too).
 */
@Composable
fun rememberAccountContext(graph: AppGraph, key: String?, ready: Phase.Ready): AccountContext {
    val version by graph.accounts.version.collectAsStateWithLifecycle()
    val account = remember(key, version) { graph.accounts.forKey(key) }
    val server by account.servers.current.collectAsStateWithLifecycle()
    val me by account.me.collectAsStateWithLifecycle()
    val primaryAccount by graph.accounts.primary.collectAsStateWithLifecycle()
    val primary = account.id == primaryAccount.id
    return AccountContext(account.id, server ?: ready.server, me ?: ready.me.takeIf { primary })
}

/** The family social flags of an account's server. */
val AccountContext.flags: SocialFlags get() = discovery?.let { SocialFlags.of(it) } ?: SocialFlags.NONE

/** One signed-in account to choose ("Which server?"): a new family, a typed frame code. */
data class ServerOption(val accountId: String, val label: String)

/** The signed-in accounts to choose from, primary first; empty with one account (nothing to choose). */
fun serverOptions(graph: AppGraph, filter: (app.kinframe.family.accounts.Account) -> Boolean = { true }): List<ServerOption> =
    graph.accounts.signedIn.filter(filter).map { ServerOption(it.id, it.label) }.takeIf { it.size > 1 }.orEmpty()

/** "Which server?" chips, shown only with more than one account. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServerPicker(options: List<ServerOption>, selected: String?, onSelect: (String) -> Unit, hint: String? = null) {
    if (options.size < 2) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.servers_pick_title), style = Kin.type.label, color = Kin.colors.inkSoft)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> ToggleChip(o.label, o.accountId == selected, { onSelect(o.accountId) }) }
        }
        hint?.let { Text(it, style = Kin.type.caption, color = Kin.colors.inkSoft) }
    }
}
