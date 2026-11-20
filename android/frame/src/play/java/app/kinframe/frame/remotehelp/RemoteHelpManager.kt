package app.kinframe.frame.remotehelp

import androidx.compose.runtime.Composable
import app.kinframe.core.api.RemoteHelpReport
import app.kinframe.frame.FrameController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * The Google Play build has no remote help (docs/spec/remote-help.md §5.5: Play policy, no root): it never lists the
 * feature, so the server never starts a session for it, and a stray `remote_help` change is ignored.
 */
@Suppress("UNUSED_PARAMETER")
class RemoteHelpManager(c: FrameController) : RemoteHelp {
    override val badge: StateFlow<Long?> = MutableStateFlow(null)
    override fun start() = Unit
    override fun onChange(payload: JsonObject?) = Unit
    override fun report(): RemoteHelpReport? = null
    override suspend fun features(): List<String> = emptyList()
    override fun stopHere() = Unit
    override suspend fun onUnpaired() = Unit
}

/** Nothing to show in the Play build. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun RemoteHelpBadge(c: FrameController) = Unit
