package app.kinframe.frame.slideshow

import app.kinframe.frame.data.PlacementEntity

/** Which visible placements the slideshow plays: everything, one album (album mode) or one feed (feed mode). */
object SlideshowSource {
    /**
     * Feed mode (`slideshow.feed_id`) plays the placements a subscribed feed holds (`feed_ids`); album mode
     * (`slideshow.album_id`) one album. The server keeps them mutually exclusive; should both arrive, the feed wins
     * because it is the newer choice. An empty or unknown album or feed plays everything.
     *
     * The normal slideshow leaves out photos of "Only in album mode" albums ([albumOnly], photo-sources.md §9.1): a
     * placement is left out when it is in at least one such album and in no album without the flag. If that leaves
     * nothing, everything plays rather than nothing. Album and feed mode are unchanged.
     */
    fun select(visible: List<PlacementEntity>, albumId: String?, feedId: String?, albumOnly: Set<String> = emptySet()): List<PlacementEntity> {
        val chosen = when {
            feedId != null -> visible.filter { feedId in it.feedIdList }
            albumId != null -> visible.filter { albumId in it.albumIdList }
            albumOnly.isEmpty() -> return visible
            else -> visible.filterNot { isAlbumOnly(it, albumOnly) }
        }
        return chosen.ifEmpty { visible }
    }

    /** In at least one album-only album and in no other album. */
    fun isAlbumOnly(p: PlacementEntity, albumOnly: Set<String>): Boolean {
        val albums = p.albumIdList
        return albums.isNotEmpty() && albums.any { it in albumOnly } && albums.all { it in albumOnly }
    }
}
