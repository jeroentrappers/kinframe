package app.kinframe.core

import app.kinframe.core.social.Mention
import app.kinframe.core.social.MentionText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MentionsTest {
    @Test fun queryFindsTheAtWordBeforeTheCursor() {
        val t = MentionText("Look @An")
        assertEquals(MentionText.Query(5, 8, "An"), t.query(8))
        assertEquals("", MentionText("@").query(1)!!.text)
        assertNull("an email address is not a mention", MentionText("mail ben@x").query(10))
        assertNull(MentionText("no at here").query(5))
        assertNull("past a line break", MentionText("@Anna\nhi").query(8))
    }

    @Test fun insertReplacesTheQueryAndRecordsTheSpan() {
        val t = MentionText("Hi @an how")
        val q = t.query(6)!!
        val (next, cursor) = t.insert(q, "u1", "Anna")
        assertEquals("Hi @Anna  how", next.text)
        assertEquals(listOf(Mention("u1", 3, 5)), next.mentions)
        assertEquals(9, cursor)
        assertNull("the finished mention isn't a query any more", next.query(8))
    }

    @Test fun offsetsAreUtf16UnitsEvenAfterEmoji() {
        val t = MentionText("😂😂 @B")
        val (next, _) = t.insert(t.query(t.text.length)!!, "u2", "Ben")
        // Each emoji is two UTF-16 units, so "@Ben" starts at 5.
        assertEquals(Mention("u2", 5, 4), next.mentions.single())
        assertEquals("@Ben", next.text.substring(5, 9))
    }

    @Test fun editsShiftLaterMentionsAndDropTouchedOnes() {
        val t = MentionText("@Anna and @Ben", listOf(Mention("a", 0, 5), Mention("b", 10, 4)))
        val prefixed = t.edited("Hey @Anna and @Ben")
        assertEquals(listOf(Mention("a", 4, 5), Mention("b", 14, 4)), prefixed.mentions)
        val broken = t.edited("@Ana and @Ben")
        assertEquals(listOf(Mention("b", 9, 4)), broken.mentions)
        val appended = t.edited("@Anna and @Ben!")
        assertEquals(t.mentions, appended.mentions)
    }

    @Test fun trimmedMovesSpansWithTheText() {
        val t = MentionText("  @Anna hi  ", listOf(Mention("a", 2, 5)))
        val s = t.trimmed()
        assertEquals("@Anna hi", s.text)
        assertEquals(listOf(Mention("a", 0, 5)), s.mentions)
    }

    @Test fun spansSkipInvalidAndOverlappingOnes() {
        val ms = listOf(Mention("b", 4, 3), Mention("a", 0, 5), Mention("c", 20, 2))
        assertEquals(listOf(Mention("a", 0, 5)), MentionText.spans("@Anna x", ms))
        assertEquals(3, MentionText.codePoints("a😂b"))
    }
}
