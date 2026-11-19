package app.kinframe.frame

import app.kinframe.core.api.FrameFeatures

/**
 * Where this build comes from: the `distribution` build flavor (docs/play-frame.md).
 *
 * - `github`: GitHub releases, put on converted frames by the convert tool. Self-updates from the kinframe server and
 *   uses root where the frame has it.
 * - `play`: Google Play, for ordinary Android tablets. Updates through Play, never installs APKs and never runs `su`.
 */
object Distribution {
    const val GITHUB = "github"
    const val PLAY = "play"

    val name: String get() = BuildConfig.DISTRIBUTION
    val isPlay: Boolean get() = BuildConfig.DISTRIBUTION == PLAY

    /** Root may be tried at all ([app.kinframe.frame.system.RootShell]); false in the Play build. */
    val rootAllowed: Boolean get() = BuildConfig.ROOT_ALLOWED

    /** Heartbeat `features`: what this app supports, plus where it was installed from (additive, ignored by old servers). */
    fun heartbeatFeatures(): List<String> = FrameFeatures.forDistribution(BuildConfig.DISTRIBUTION)
}
