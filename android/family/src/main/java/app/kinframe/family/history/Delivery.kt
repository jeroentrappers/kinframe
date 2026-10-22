package app.kinframe.family.history

import androidx.annotation.StringRes
import app.kinframe.core.api.MediaKind
import app.kinframe.core.api.PlacementStatus
import app.kinframe.core.api.Reaction
import app.kinframe.core.api.Send
import app.kinframe.family.R

/** What the sender sees per placement: processing → on its way → delivered → seen (product spec 4.3). */
enum class Stage(@StringRes val label: Int, val rank: Int, val progress: Float) {
    Failed(R.string.stage_failed, 0, 0f),
    Processing(R.string.stage_processing, 1, 0f),
    OnItsWay(R.string.stage_on_its_way, 2, 1f / 3),
    Delivered(R.string.stage_delivered, 3, 2f / 3),
    Seen(R.string.stage_seen, 4, 1f),
    Removed(R.string.stage_removed, 5, 0f),
}

data class StageCounts(val counts: Map<Stage, Int>) {
    operator fun get(s: Stage) = counts[s] ?: 0
    val total: Int get() = counts.values.sum()
}

data class FrameDelivery(
    val frameId: String,
    val frameName: String,
    val online: Boolean,
    val stage: Stage,
    val counts: StageCounts,
    val progress: Float,
    val reactions: List<String?>,
    val replies: List<String>,
    /** Members who shared this send's photos to the frame (README §5.3). */
    val sharedBy: List<String> = emptyList(),
)

object Delivery {
    fun stageOf(status: String, itemStatus: String? = null): Stage = when {
        itemStatus == "failed" || status == PlacementStatus.FAILED -> Stage.Failed
        status == PlacementStatus.REMOVED -> Stage.Removed
        itemStatus == "processing" -> Stage.Processing
        status == PlacementStatus.PENDING -> Stage.Processing
        status == PlacementStatus.READY -> Stage.OnItsWay
        status == PlacementStatus.DELIVERED -> Stage.Delivered
        status == PlacementStatus.SEEN -> Stage.Seen
        else -> Stage.Processing
    }

    fun count(stages: List<Stage>) = StageCounts(stages.groupingBy { it }.eachCount())

    /** The headline stage of a group: the least-advanced live one (failures win so they are noticed). */
    fun overall(stages: List<Stage>): Stage {
        val live = stages.filter { it != Stage.Removed }
        if (live.isEmpty()) return Stage.Removed
        return live.minBy { it.rank }
    }

    /** 0..1 progress of a group (processing 0, on its way ⅓, delivered ⅔, seen 1). */
    fun progress(stages: List<Stage>): Float {
        val live = stages.filter { it != Stage.Removed && it != Stage.Failed }
        if (live.isEmpty()) return 0f
        return live.sumOf { it.progress.toDouble() }.toFloat() / live.size
    }

    fun frames(send: Send): List<FrameDelivery> {
        val itemStatus = send.items.associate { it.id to it.status }
        return send.frames.map { f ->
            val stages = f.placements.map { stageOf(it.status, itemStatus[it.itemId]) }
            FrameDelivery(
                frameId = f.frameId,
                frameName = f.frameName,
                online = f.online,
                stage = overall(stages),
                counts = count(stages),
                progress = progress(stages),
                reactions = f.placements.map { it.reaction },
                replies = f.placements.flatMap { it.replies },
                sharedBy = f.placements.filter { it.status != PlacementStatus.REMOVED }.mapNotNull { it.sharedByName }.distinct(),
            )
        }
    }

    /** Overall stage of a whole send (all frames). */
    fun sendStage(send: Send): Stage {
        val itemStatus = send.items.associate { it.id to it.status }
        return overall(send.frames.flatMap { f -> f.placements.map { stageOf(it.status, itemStatus[it.itemId]) } })
    }

    /** A live reaction or reply applied to a send; null when the placement isn't in it. */
    fun applyReaction(send: Send, placementId: String, reaction: String?): Send? = update(send, placementId) { it.copy(reaction = reaction) }

    fun applyReply(send: Send, placementId: String, preset: String): Send? = update(send, placementId) { it.copy(replies = it.replies + preset) }

    private fun update(send: Send, placementId: String, f: (app.kinframe.core.api.SendPlacement) -> app.kinframe.core.api.SendPlacement): Send? {
        if (send.frames.none { fr -> fr.placements.any { it.placementId == placementId } }) return null
        return send.copy(frames = send.frames.map { fr -> fr.copy(placements = fr.placements.map { if (it.placementId == placementId) f(it) else it }) })
    }

    /** [(love, 2), (wow, 1)] in a stable order. */
    fun tally(reactions: List<String?>): List<Pair<String, Int>> {
        val counts = reactions.filterNotNull().groupingBy { it }.eachCount()
        return Reaction.all.filter { it in counts }.map { it to counts.getValue(it) }
    }

    fun kindCounts(send: Send): Pair<Int, Int> {
        val videos = send.items.count { it.kind == MediaKind.VIDEO }
        return (send.items.size - videos) to videos
    }

    val EMOJI = mapOf(Reaction.LOVE to "❤️", Reaction.LAUGH to "😂", Reaction.WOW to "😮", Reaction.SAD to "😢")

    @StringRes
    fun reactionLabel(r: String): Int = when (r) {
        Reaction.LOVE -> R.string.reaction_love
        Reaction.LAUGH -> R.string.reaction_laugh
        Reaction.WOW -> R.string.reaction_wow
        else -> R.string.reaction_sad
    }

    @StringRes
    fun replyLabel(preset: String): Int = when (preset) {
        "beautiful" -> R.string.reply_beautiful
        "call_me" -> R.string.reply_call_me
        "miss_you" -> R.string.reply_miss_you
        else -> R.string.reply_thanks
    }
}
