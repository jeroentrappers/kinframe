package app.kinframe.frame.sync

import android.util.Log
import app.kinframe.core.api.Change
import app.kinframe.core.api.ChangeType
import app.kinframe.core.api.DeviceApi
import app.kinframe.core.api.FramePhotoRequest
import app.kinframe.core.api.KinJson
import app.kinframe.core.api.Placement
import app.kinframe.frame.data.AlbumFlagEntity
import app.kinframe.frame.data.FeedTombstoneEntity
import app.kinframe.frame.data.FrameDatabase
import app.kinframe.frame.data.MediaCache
import app.kinframe.frame.data.MemberEntity
import app.kinframe.frame.data.OutboxEntity
import app.kinframe.frame.data.PlacementCommentsEntity
import app.kinframe.frame.data.PlacementEntity
import app.kinframe.frame.data.SyncStateEntity
import app.kinframe.frame.social.SocialLogic
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * Follows the per-frame change feed (README §5.4). Each page is applied in one SQLite transaction together with
 * the new `next_seq`, so a power cut never leaves the mirror and the cursor out of step.
 */
class SyncEngine(
    private val api: DeviceApi,
    private val db: FrameDatabase,
    private val media: MediaCache,
    private val now: () -> Long,
) {
    private val mutex = Mutex()

    data class PullResult(val applied: Int, val newItems: Int, val resets: Int)

    /** Pulls until `has_more` is false. Serialised: concurrent callers wait and then pull again (cheap). */
    suspend fun pull(): PullResult = mutex.withLock {
        var applied = 0
        var newItems = 0
        var resets = 0
        var pages = 0
        while (true) {
            val since = db.syncState().get()?.nextSeq ?: 0L
            val page = api.changes(since)
            val r = applyPage(page.changes, page.nextSeq, initial = since == 0L)
            applied += r.applied
            newItems += r.newItems
            orphanCleanup(r.orphanCandidates)
            if (r.reset) {
                resets++
                if (resets > 3) error("server keeps sending reset")
                continue
            }
            if (!page.hasMore || page.changes.isEmpty()) break
            if (++pages > 10_000) break
        }
        PullResult(applied, newItems, resets)
    }

    internal data class PageResult(val applied: Int, val newItems: Int, val reset: Boolean, val orphanCandidates: Set<String>)

    internal suspend fun applyPage(changes: List<Change>, nextSeq: Long, initial: Boolean): PageResult = db.tx {
        val placements = db.placements()
        var state = db.syncState().get() ?: SyncStateEntity()
        var applied = 0
        var newItems = 0
        val orphans = mutableSetOf<String>()
        // Family social: who sent this page's new photos ("New photos from Ben" on the next wake).
        val newFrom = ArrayList<String>()
        var answeredBy: String? = null
        for (c in changes) {
            when (c.type) {
                ChangeType.PLACEMENT_UPSERT -> {
                    val p = c.placement ?: continue
                    val existing = placements.get(p.id)
                    if (p.status == "removed") {
                        if (existing != null) {
                            existing.localFile?.let { orphans += it }
                            placements.delete(p.id)
                            rememberFeedRemoval(existing)
                        }
                        db.social().deleteComments(p.id)
                    } else {
                        // Back after a pause (feeds.md §5.1): restored, not new.
                        val restored = existing == null && db.feedTombstones().has(p.id)
                        if (restored) db.feedTombstones().delete(p.id)
                        val merged = withPendingLocalActions(merge(existing, p, initial, restored))
                        if (existing?.localFile != null && merged.localFile == null) orphans += existing.localFile
                        if (existing == null && merged.isNew) {
                            newItems++
                            SocialLogic.senderName(p)?.let { newFrom += it }
                        }
                        placements.upsert(merged)
                    }
                }
                ChangeType.PLACEMENT_REMOVE -> {
                    val id = c.id ?: c.placement?.id ?: continue
                    val gone = placements.get(id)
                    gone?.localFile?.let { orphans += it }
                    placements.delete(id)
                    gone?.let { rememberFeedRemoval(it) }
                    db.social().deleteComments(id)
                    db.outbox().forPlacement(id).forEach { db.outbox().delete(it.id) }
                }
                ChangeType.SETTINGS -> c.settings?.let { state = state.copy(settingsJson = KinJson.encodeToString(JsonObject.serializer(), it)) }
                ChangeType.MEMBER_UPSERT -> c.member?.let {
                    db.members().upsert(MemberEntity(it.userId, it.displayName, it.avatarUrl, it.role))
                }
                ChangeType.MEMBER_REMOVE -> (c.member?.userId ?: c.id)?.let { db.members().delete(it) }
                ChangeType.FRAME -> c.frame?.let {
                    state = state.copy(
                        frameName = it.name ?: state.frameName,
                        frameLocation = it.location,
                        ownerName = it.ownerName ?: state.ownerName,
                    )
                }
                // `weather: null` means no weather any more (widget off, location removed): drop what we have.
                ChangeType.WEATHER -> state = state.copy(weatherJson = c.weather?.let { KinJson.encodeToString(JsonObject.serializer(), it) })
                // `calendar: null` means the frame has no calendars any more.
                ChangeType.CALENDAR -> state = state.copy(calendarJson = c.calendar?.let { KinJson.encodeToString(JsonObject.serializer(), it) })
                // `birthdays: null` means no birthday subscription lists anyone any more (birthdays.md §11).
                ChangeType.BIRTHDAYS -> state = state.copy(birthdaysJson = c.birthdays?.let { KinJson.encodeToString(JsonObject.serializer(), it) })
                // Family social (README §5.20). `comments: null` drops the placement's comments (setting off, removed).
                ChangeType.COMMENTS -> {
                    val id = c.id ?: (c.comments?.get("placement_id") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                    if (id != null) {
                        val doc = c.comments
                        if (doc == null) db.social().deleteComments(id)
                        else db.social().putComments(PlacementCommentsEntity(id, KinJson.encodeToString(JsonObject.serializer(), doc), now()))
                    }
                }
                ChangeType.PHOTO_REQUEST -> {
                    val before = state.photoRequestJson?.let { runCatching { KinJson.parseToJsonElement(it).jsonObject }.getOrNull() }
                        .let { FramePhotoRequest.parse(it) }
                    val after = FramePhotoRequest.parse(c.photoRequest)
                    // Someone answered "Ask for new photos": the frame says who on its next wake (§9.3).
                    if (!initial && after != null && after.isAnswered && (before == null || before.id != after.id || !before.isAnswered)) {
                        answeredBy = after.answeredByName?.trim()?.takeIf { it.isNotEmpty() }
                    }
                    state = state.copy(photoRequestJson = c.photoRequest?.let { KinJson.encodeToString(JsonObject.serializer(), it) })
                }
                ChangeType.PROMPTS -> state = state.copy(promptsJson = c.prompts?.let { KinJson.encodeToString(JsonObject.serializer(), it) })
                // Albums: only the "Only in album mode" flag matters to playback (photo-sources.md §9.1).
                ChangeType.ALBUM_UPSERT -> c.album?.let { a ->
                    val id = (a["id"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: return@let
                    val only = (a["only_in_album_mode"] as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false
                    db.albumFlags().put(AlbumFlagEntity(id, only))
                }
                ChangeType.ALBUM_REMOVE -> (c.id ?: (c.album?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull)?.let { db.albumFlags().delete(it) }
                ChangeType.RESET -> {
                    Log.i(TAG, "reset at seq ${c.seq}: re-syncing from 0")
                    placements.deleteAll()
                    db.albumFlags().deleteAll()
                    db.members().deleteAll()
                    db.acks().deleteAll()
                    db.feedTombstones().deleteAll()
                    db.outbox().ofKind(OutboxEntity.PLACEMENT_PATCH).forEach { db.outbox().delete(it.id) }
                    db.outbox().ofKind(OutboxEntity.REACTION).forEach { db.outbox().delete(it.id) }
                    db.social().deleteAllComments()
                    // Media files stay: they are content-addressed and re-linked by the downloader.
                    // Snapshots omit `birthdays` when there are none, so a stale list must not survive the reset.
                    // Likewise the prompts and the photo request: the snapshot brings the current ones back.
                    db.syncState().put(state.copy(nextSeq = 0, birthdaysJson = null, promptsJson = null, photoRequestJson = null))
                    return@tx PageResult(applied, 0, true, emptySet())
                }
                else -> Unit // unknown types are ignored (README §7)
            }
            applied++
        }
        if (newFrom.isNotEmpty()) state = state.copy(newPhotosFrom = SocialLogic.addNames(state.newPhotosFrom, newFrom))
        answeredBy?.let { state = state.copy(newPhotosFrom = SocialLogic.addNames(state.newPhotosFrom, listOf(it), first = true)) }
        db.syncState().put(state.copy(nextSeq = nextSeq))
        db.feedTombstones().prune(now() - FeedTombstoneEntity.KEEP_MS)
        PageResult(applied, newItems, false, orphans)
    }

    /** A feed-held placement left the frame (a pause renders as a remove): remember it so a resume isn't "new". */
    private suspend fun rememberFeedRemoval(e: PlacementEntity) {
        if (e.origin == "feed" || e.feedIdList.isNotEmpty()) db.feedTombstones().put(FeedTombstoneEntity(e.id, now()))
    }

    private fun merge(existing: PlacementEntity?, p: Placement, initial: Boolean, restored: Boolean = false): PlacementEntity {
        val item = p.item
        val m = p.media
        val sameMedia = existing != null && existing.mediaSha256 != null && existing.mediaSha256.equals(m?.sha256, ignoreCase = true)
        val mo = p.motion
        val sameMotion = existing != null && existing.motionSha256 != null && existing.motionSha256.equals(mo?.sha256, ignoreCase = true)
        val focal = p.focalOverride ?: item.focalPoint
        return PlacementEntity(
            id = p.id,
            itemId = item.id,
            kind = item.kind,
            status = p.status,
            senderId = item.senderId,
            senderName = item.senderName,
            sharedByName = p.sharedByName,
            importedFrom = item.importedFrom,
            importedSenderName = item.importedSenderName,
            viaLabel = item.via?.let { v -> if (v.isChannel) v.personLabel else v.displayLabel },
            viaService = item.via?.service,
            promptId = p.promptId,
            origin = p.origin,
            originFeedId = p.originFeedId,
            originFeedName = p.originFeedName,
            feedIds = p.feedIds.joinToString(",").ifEmpty { null },
            backfill = p.backfill,
            caption = item.caption,
            captionEdited = item.captionEdited,
            capturedAt = parseTime(item.capturedAt),
            receivedAt = parseTime(p.receivedAt) ?: parseTime(item.createdAt) ?: existing?.receivedAt ?: now(),
            focalX = focal?.x,
            focalY = focal?.y,
            fit = p.fitOverride ?: item.fit,
            rotation = p.rotation,
            hidden = p.hidden,
            trashed = p.trashed,
            pendingModeration = p.pendingModeration,
            reaction = p.reaction,
            width = m?.width ?: item.width,
            height = m?.height ?: item.height,
            durationMs = item.durationMs,
            hasAudio = item.hasAudio,
            mediaUrl = m?.url,
            mediaSha256 = m?.sha256?.lowercase(),
            mediaBytes = m?.bytes ?: 0,
            mediaMime = m?.mime,
            localFile = if (sameMedia) existing?.localFile else null,
            storedAt = if (sameMedia) existing?.storedAt else null,
            downloadAttempts = if (sameMedia) existing?.downloadAttempts ?: 0 else 0,
            nextAttemptAt = if (sameMedia) existing?.nextAttemptAt ?: 0 else 0,
            lastError = if (sameMedia) existing?.lastError else null,
            firstShownAt = existing?.firstShownAt,
            lastShownAt = existing?.lastShownAt,
            // Photos imported from the frame's old app were seen there already: they don't jump the queue as new.
            // Feed history (`backfill`) joins quietly in its original order: never the new-photo card or a screen wake.
            isNew = existing?.isNew ?: (!initial && !restored && !p.backfill && item.importedFrom == null && p.seenAt == null && p.status != "seen"),
            evictedAt = if (sameMedia) existing?.evictedAt else null,
            albumIds = p.albumIds.joinToString(",").ifEmpty { null },
            motionUrl = mo?.url,
            motionSha256 = mo?.sha256?.lowercase(),
            motionBytes = mo?.bytes ?: 0,
            motionMime = mo?.mime,
            motionDurationMs = mo?.durationMs,
            motionHasAudio = mo?.hasAudio ?: false,
            motionLocalFile = if (sameMotion) existing?.motionLocalFile else null,
            motionAttempts = if (sameMotion) existing?.motionAttempts ?: 0 else 0,
            motionNextAttemptAt = if (sameMotion) existing?.motionNextAttemptAt ?: 0 else 0,
        )
    }

    /** Local actions not yet sent (e.g. a delete inside its undo window) win over the server's echo. */
    private suspend fun withPendingLocalActions(e: PlacementEntity): PlacementEntity {
        var r = e
        for (o in db.outbox().forPlacement(e.id)) {
            val payload = runCatching { KinJson.parseToJsonElement(o.payload).jsonObject }.getOrNull() ?: continue
            when (o.kind) {
                OutboxEntity.PLACEMENT_PATCH -> {
                    payload["hidden"]?.jsonPrimitive?.booleanOrNull?.let { r = r.copy(hidden = it) }
                    payload["trashed"]?.jsonPrimitive?.booleanOrNull?.let { r = r.copy(trashed = it) }
                    payload["rotation"]?.jsonPrimitive?.intOrNull?.let { r = r.copy(rotation = it) }
                    payload["fit"]?.jsonPrimitive?.let { if (it.isString) r = r.copy(fit = it.content) }
                }
                OutboxEntity.REACTION -> r = r.copy(reaction = payload["reaction"]?.jsonPrimitive?.let { if (it.isString) it.content else null })
            }
        }
        return r
    }

    private suspend fun orphanCleanup(candidates: Set<String>) {
        if (candidates.isEmpty()) return
        val still = db.placements().allLocalFiles().toSet()
        candidates.filter { it !in still }.forEach { media.file(it).delete() }
    }

    /** Deletes media files no placement refers to (after resets, or leftovers from a power cut). */
    suspend fun garbageCollect() {
        val keep = db.placements().all().flatMap { p ->
            listOfNotNull(p.mediaSha256?.let { media.fileName(it, p.mediaMime) }, p.motionSha256?.let { media.fileName(it, p.motionMime) })
        }.toSet()
        media.dir.listFiles()?.filter { it.isFile && it.name !in keep }?.forEach { it.delete() }
        val shas = keep.map { it.substringBefore('.') }.toSet()
        media.partialDir.listFiles()?.filter { it.name.substringBefore('.') !in shas }?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "KinSync"

        fun parseTime(s: String?): Long? = s?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    }
}
