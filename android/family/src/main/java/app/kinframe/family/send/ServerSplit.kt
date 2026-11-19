package app.kinframe.family.send

import java.util.UUID

/**
 * A send to frames and families on several servers becomes one outbox send per server (multi-server.md §2.2): each
 * uploads to its own server and has its own progress and errors. The first group keeps the send's id.
 */
object ServerSplit {
    /**
     * [accountOf] tells the account of a frame, family or circle id (null: unknown, the [primary] account);
     * [serverOf] an account's server base URL.
     */
    fun split(
        send: OutboxSend,
        primary: String,
        accountOf: (String) -> String?,
        serverOf: (String) -> String,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): List<OutboxSend> {
        if (send.chat != null) return listOf(send)
        val frames = send.frameIds.zip(send.frameNames.padTo(send.frameIds.size)).groupBy { accountOf(it.first) ?: primary }
        val feeds = send.feedIds.zip(send.feedNames.padTo(send.feedIds.size)).groupBy { accountOf(it.first) ?: primary }
        val accounts = (frames.keys + feeds.keys).distinct().sortedBy { if (it == primary) 0 else 1 }
        if (accounts.size <= 1) return listOf(send.copy(server = accounts.firstOrNull()?.let(serverOf) ?: send.server))
        val firstFeeds = accounts.firstOrNull { feeds[it] != null } ?: primary
        return accounts.mapIndexed { i, a ->
            val fr = frames[a].orEmpty()
            val fd = feeds[a].orEmpty()
            send.copy(
                id = if (i == 0) send.id else newId(),
                server = serverOf(a),
                frameIds = fr.map { it.first }, frameNames = fr.map { it.second },
                feedIds = fd.map { it.first }, feedNames = fd.map { it.second },
                circleIds = send.circleIds.filter { (accountOf(it) ?: firstFeeds) == a },
                // A weekly prompt belongs to one family's server.
                promptId = send.promptId?.takeIf { p -> (accountOf(p) ?: firstFeeds) == a },
                // Each server gets its own copy of the queue entry (upload state is per server); the preparation is
                // shared (prepOf), so the media is prepared once.
                items = if (i == 0) send.items else send.items.map { it.copy(id = newId(), uploadId = null, uploadUrl = null, uploadedBytes = 0, prepOf = it.id) },
                group = send.id,
            )
        }
    }

    private fun List<String>.padTo(n: Int) = if (size >= n) this else this + List(n - size) { "" }
}
