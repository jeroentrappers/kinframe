package app.kinframe.frame.slideshow

import kotlin.math.exp
import kotlin.math.ln

/** What the playlist needs to know about a playable item. */
data class PlaylistEntry(
    val id: String,
    val receivedAt: Long,
    val takenAt: Long?,
    val isNew: Boolean = false,
    /** "On this day": taken on today's date in an earlier year. Plays once, early, on that day. */
    val featured: Boolean = false,
)

enum class PlayOrder { RECEIVED, TAKEN, SHUFFLE;
    companion object {
        fun of(s: String) = when (s) { "taken" -> TAKEN; "shuffle" -> SHUFFLE; else -> RECEIVED }
    }
}

/**
 * Slideshow order (product spec 4.4):
 * - received / taken, newest first by default, `reverse` for oldest first;
 * - shuffle: a seeded permutation, weighted toward recent items, with no repeats within a cycle;
 * - new items (arrived while running, not shown yet) play first, then the normal order resumes where it was.
 *
 * Pure Kotlin and deterministic for a given seed, so it is unit-tested and survives restarts via [seed] and the
 * current id.
 */
class Playlist(seed: Long, private val now: () -> Long = System::currentTimeMillis) {
    var seed: Long = seed
        private set
    var current: String? = null
        private set
    /** The last item reached through the normal order: after new items and memories it resumes from here. */
    private var anchor: String? = null

    private var entries: Map<String, PlaylistEntry> = emptyMap()
    private var order = PlayOrder.RECEIVED
    private var reverse = false
    private var ordered: List<String> = emptyList()
    private val shownThisCycle = LinkedHashSet<String>()
    private val newShown = HashSet<String>()
    private val featuredShown = HashSet<String>()
    private var featuredIds: Set<String> = emptySet()
    private val history = ArrayDeque<String>()
    /**
     * Items stepped back over with [previous], most recent last. Going forward replays them first, like a browser's
     * forward button, so back-then-forward returns to the same photos; only then does the order continue.
     */
    private val forward = ArrayDeque<String>()

    val size: Int get() = ordered.size
    fun contains(id: String) = id in entries

    fun update(items: List<PlaylistEntry>, order: PlayOrder, reverse: Boolean) {
        val orderChanged = order != this.order || reverse != this.reverse
        entries = items.associateBy { it.id }
        this.order = order
        this.reverse = reverse
        ordered = computeOrder()
        shownThisCycle.retainAll(entries.keys)
        newShown.retainAll(entries.keys)
        val featured = items.filter { it.featured }.map { it.id }.toSet()
        // A new day brings a new set of memories; the ones already shown today stay shown.
        if (featured != featuredIds) featuredShown.retainAll(featured)
        featuredIds = featured
        if (orderChanged) shownThisCycle.clear()
        current?.let { shownThisCycle += it }
    }

    /** Restores the position after a restart (does not count as a new display). */
    fun restore(currentId: String?) {
        if (currentId != null && currentId in entries) {
            current = currentId
            anchor = currentId
            shownThisCycle += currentId
        }
    }

    fun peekNext(): String? = forwardTop() ?: choose().first

    fun next(): String? {
        forwardTop()?.let { id ->
            forward.removeLast()
            current?.let { pushHistory(it) }
            current = id
            return id
        }
        val choice = choose()
        val (id, newCycle, newSeed) = choice
        if (id == null) return null
        if (!choice.special) anchor = id
        if (newCycle) {
            shownThisCycle.clear()
            if (newSeed != seed) { seed = newSeed; ordered = computeOrder() }
        }
        current?.let { pushHistory(it) }
        current = id
        markPlayed(id)
        return id
    }

    private fun markPlayed(id: String) {
        shownThisCycle += id
        if (entries[id]?.isNew == true) newShown += id
        if (id in featuredIds) featuredShown += id
    }

    /**
     * The next [n] items after the current one, in play order, without moving (for collages and smart pairing).
     * Stays within the current cycle.
     */
    fun upcoming(n: Int): List<String> {
        val out = ArrayList<String>()
        fun add(id: String) { if (id != current && id !in out && out.size < n) out += id }
        pendingNew().forEach(::add)
        pendingFeatured().forEach(::add)
        if (ordered.isNotEmpty()) {
            val start = anchor?.let { ordered.indexOf(it) } ?: -1
            for (step in 1..ordered.size) {
                val id = ordered[(start + step).mod(ordered.size)]
                if (id !in shownThisCycle) add(id)
                if (out.size >= n) break
            }
        }
        return out
    }

    /** [id] is shown together with the current item (collage tile, pair): it counts as played this cycle. */
    fun consume(id: String) {
        if (id in entries && id != current) markPlayed(id)
    }

    /** "On this day" items not shown yet today. */
    fun pendingFeatured(): List<String> = ordered.filter { it in featuredIds && it !in featuredShown }

    /** What [previous] would return, without moving (the slide shown beside the current one while swiping back). */
    fun peekPrevious(): String? {
        for (i in history.indices.reversed()) {
            val id = history[i]
            if (id in entries && id != current) return id
        }
        if (ordered.isEmpty()) return null
        val idx = ordered.indexOf(current)
        return ordered[if (idx <= 0) ordered.size - 1 else idx - 1]
    }

    fun previous(): String? {
        while (history.isNotEmpty()) {
            val id = history.removeLast()
            if (id in entries && id != current) {
                current?.let { forward.addLast(it) }
                current = id
                return id
            }
        }
        // No history: step backwards in the order.
        if (ordered.isEmpty()) return null
        val idx = ordered.indexOf(current)
        val id = ordered[if (idx <= 0) ordered.size - 1 else idx - 1]
        current?.let { forward.addLast(it) }
        current = id
        return id
    }

    /** The next item to replay after going back, dropping ones that were removed meanwhile. */
    private fun forwardTop(): String? {
        while (forward.isNotEmpty()) {
            val id = forward.last()
            if (id in entries && id != current) return id
            forward.removeLast()
        }
        return null
    }

    /** Shows a specific item now (remote `show_placement`, tapping the "new" card). */
    fun jumpTo(id: String): Boolean {
        if (id !in entries) return false
        forward.clear()
        current?.let { if (it != id) pushHistory(it) }
        current = id
        anchor = id
        markPlayed(id)
        return true
    }

    /** New items that have not been shown yet, oldest arrival first. */
    fun pendingNew(): List<String> =
        entries.values.filter { it.isNew && it.id !in newShown }.sortedBy { it.receivedAt }.map { it.id }

    private fun pushHistory(id: String) {
        history.addLast(id)
        while (history.size > 50) history.removeFirst()
    }

    private data class Choice(val first: String?, val newCycle: Boolean, val seed: Long, val special: Boolean = false)

    private fun choose(): Choice {
        if (ordered.isEmpty()) return Choice(null, false, seed)
        pendingNew().firstOrNull { it != current }?.let { return Choice(it, false, seed, special = true) }
        pendingFeatured().firstOrNull { it != current }?.let { return Choice(it, false, seed, special = true) }
        if (ordered.size == 1) return Choice(ordered[0], true, seed)
        val start = anchor?.let { ordered.indexOf(it) } ?: -1
        for (step in 1..ordered.size) {
            val id = ordered[(start + step).mod(ordered.size)]
            if (id !in shownThisCycle) return Choice(id, false, seed)
        }
        // Cycle complete: start a new one (new permutation for shuffle) without repeating the current item.
        return if (order == PlayOrder.SHUFFLE) {
            val nextSeed = seed * 6364136223846793005L + 1442695040888963407L
            val nextOrder = computeOrder(nextSeed)
            Choice(nextOrder.first { it != current }, true, nextSeed)
        } else {
            Choice(ordered[(start + 1).mod(ordered.size)], true, seed)
        }
    }

    private fun computeOrder(s: Long = seed): List<String> {
        val list = entries.values.toList()
        val sorted = when (order) {
            PlayOrder.RECEIVED -> list.sortedWith(compareByDescending<PlaylistEntry> { it.receivedAt }.thenBy { it.id })
            PlayOrder.TAKEN -> list.sortedWith(compareByDescending<PlaylistEntry> { it.takenAt ?: it.receivedAt }.thenBy { it.id })
            PlayOrder.SHUFFLE -> {
                val t = now()
                // Efraimidis–Spirakis weighted permutation: key = -ln(u) / w, ascending.
                list.sortedBy { e ->
                    val ageDays = ((t - e.receivedAt).coerceAtLeast(0)) / 86_400_000.0
                    val w = 1.0 + RECENT_BOOST * exp(-ageDays / RECENT_DAYS)
                    -ln(uniform(s, e.id)) / w
                }
            }
        }
        val ids = sorted.map { it.id }
        return if (reverse) ids.reversed() else ids
    }

    companion object {
        const val RECENT_BOOST = 3.0
        const val RECENT_DAYS = 30.0

        /** Deterministic uniform (0, 1) from a seed and an id (SplitMix64 over the id hash). */
        fun uniform(seed: Long, id: String): Double {
            var z = seed xor (id.hashCode().toLong() * -7046029254386353131L) xor id.length.toLong()
            for (c in id) z = z * 31 + c.code
            z += -7046029254386353131L
            z = (z xor (z ushr 30)) * -4658895280553007687L
            z = (z xor (z ushr 27)) * -7723592293110705685L
            z = z xor (z ushr 31)
            return ((z ushr 11).toDouble() + 0.5) / (1L shl 53).toDouble()
        }
    }
}
