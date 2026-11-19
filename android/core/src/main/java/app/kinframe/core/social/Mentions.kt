package app.kinframe.core.social

/**
 * Text with @mentions for comments and messages (README §5.18 "Mentions"). Offsets are UTF-16 code units, which is
 * what a Kotlin [String] index is, so they can be used on the body directly.
 */
data class MentionText(val text: String, val mentions: List<Mention> = emptyList()) {

    /** The "@que" being typed right before [cursor], for the autocomplete; null when the cursor isn't in one. */
    fun query(cursor: Int): Query? {
        if (cursor < 1 || cursor > text.length) return null
        val at = text.lastIndexOf('@', cursor - 1)
        if (at < 0 || cursor - at > MAX_QUERY + 1) return null
        if (at > 0 && !text[at - 1].isWhitespace() && text[at - 1] !in "([{\"'") return null
        val q = text.substring(at + 1, cursor)
        if (q.any { it == '\n' || it == '@' }) return null
        // Already inside a finished mention.
        if (mentions.any { at >= it.start && at < it.start + it.length }) return null
        // A word and a half at most: "@Ann", "@Anna Pe".
        if (q.count { it == ' ' } > 1) return null
        return Query(at, cursor, q)
    }

    data class Query(val start: Int, val end: Int, val text: String)

    /** Replaces [query] with "@[name] " and records the mention. Returns the new text and the cursor after it. */
    fun insert(query: Query, userId: String, name: String): Pair<MentionText, Int> {
        val label = "@$name"
        val replaced = text.substring(0, query.start) + label + " " + text.substring(query.end)
        val delta = label.length + 1 - (query.end - query.start)
        val shifted = mentions.mapNotNull { m ->
            when {
                m.start + m.length <= query.start -> m
                m.start >= query.end -> m.copy(start = m.start + delta)
                else -> null
            }
        }
        val all = (shifted + Mention(userId, query.start, label.length)).sortedBy { it.start }
        return MentionText(replaced, all) to (query.start + label.length + 1)
    }

    /**
     * After a free edit to [newText]: mentions before the change stay, those after it shift, and a mention whose text
     * was touched is dropped (it no longer says "@Name").
     */
    fun edited(newText: String): MentionText {
        if (newText == text) return this
        var prefix = 0
        val maxPrefix = minOf(text.length, newText.length)
        while (prefix < maxPrefix && text[prefix] == newText[prefix]) prefix++
        var suffix = 0
        while (suffix < text.length - prefix && suffix < newText.length - prefix &&
            text[text.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++
        val oldEnd = text.length - suffix
        val delta = newText.length - text.length
        val kept = mentions.mapNotNull { m ->
            when {
                m.start + m.length <= prefix -> m
                m.start >= oldEnd -> m.copy(start = m.start + delta)
                else -> null
            }
        }
        return MentionText(newText, kept)
    }

    /** What goes over the wire: trimmed like the server trims it, with the spans moved to match. */
    fun trimmed(): MentionText {
        val lead = text.length - text.trimStart().length
        val t = text.trim()
        val ms = mentions.mapNotNull { m ->
            val s = m.start - lead
            if (s < 0 || s + m.length > t.length) null else m.copy(start = s)
        }
        return MentionText(t, ms)
    }

    companion object {
        const val MAX_QUERY = 30

        /** The valid, non-overlapping spans of [mentions] in [body], in order (for drawing them highlighted). */
        fun spans(body: String, mentions: List<Mention>): List<Mention> {
            var end = 0
            return mentions.sortedBy { it.start }.filter { m ->
                val ok = m.start >= end && m.length > 0 && m.start + m.length <= body.length
                if (ok) end = m.start + m.length
                ok
            }
        }

        /** Unicode code points, the way the server counts the length limits. */
        fun codePoints(s: String): Int = s.codePointCount(0, s.length)
    }
}
