package org.osada.ui.briefing

import kotlin.test.Test
import kotlin.test.assertEquals

/** Imported OG prose is HTML — 6,945 `<br>` across the shipped campaign data. The briefing sets
 *  its text with `textContent`, so before [plainText] those tags rendered as a visible "<br><br>"
 *  and the whole passage collapsed into one paragraph. */
class BriefingPlainTextTest {
    @Test
    fun turnsBreakTagsIntoParagraphBreaks() {
        assertEquals(
            "KHALKHIN GOL - 3 JUL 1939\n\nMongolian People's Republic",
            plainText("KHALKHIN GOL - 3 JUL 1939<br><br>Mongolian People's Republic"),
        )
    }

    @Test
    fun acceptsEveryBreakSpelling() {
        assertEquals("a\nb\nc\nd", plainText("a<br>b<BR>c<br/>d"))
        assertEquals("a\nb", plainText("a<br />b"))
    }

    @Test
    fun collapsesRunsAndTrimsEdges() {
        // OG text routinely ends on a trailing <br>, and stacks three or more between blocks.
        assertEquals("ORDERS\n\nCapture all VH!", plainText("<br>ORDERS<br><br><br><br>Capture all VH!<br>"))
        assertEquals("a\nb", plainText("a   <br>b"), "trailing spaces before a break must not survive")
    }

    @Test
    fun stripsAnyOtherTagRatherThanShowingIt() {
        assertEquals("bold plan", plainText("<b>bold</b> plan"))
    }

    @Test
    fun leavesOrdinaryProseAlone() {
        val prose = "Good morning comrade Lieutenant-General. STAVKA has decided."
        assertEquals(prose, plainText(prose))
        assertEquals("", plainText("   "))
    }
}
