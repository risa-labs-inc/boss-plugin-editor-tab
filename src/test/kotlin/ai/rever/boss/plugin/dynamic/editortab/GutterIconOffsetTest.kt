package ai.rever.boss.plugin.dynamic.editortab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The run gutter used to window its icons on a hardcoded 50 lines and a 2000px cutoff, both
 * computed against its own font-metric line height. A pane taller than 50 lines dropped the
 * icons near its bottom. These pin the offset against the measured viewport instead.
 */
class GutterIconOffsetTest {

    private val metrics = ScrollMetrics(lineHeight = 20f, viewportHeight = 1200f)

    @Test
    fun `a line inside the viewport gets its offset`() {
        assertEquals(200f, gutterIconOffsetPx(visualLine = 10, metrics = metrics, scrollOffsetY = 0))
        assertEquals(100f, gutterIconOffsetPx(visualLine = 10, metrics = metrics, scrollOffsetY = 100))
    }

    @Test
    fun `a line hidden by a collapsed fold has no offset`() {
        // documentToVisual returns -1 for a line inside a collapsed fold.
        assertNull(gutterIconOffsetPx(visualLine = -1, metrics = metrics, scrollOffsetY = 0))
    }

    @Test
    fun `a line scrolled off the top has no offset, but a partially scrolled one still draws`() {
        // Exactly one line above the top edge: gone.
        assertNull(gutterIconOffsetPx(visualLine = 0, metrics = metrics, scrollOffsetY = 20))
        // Half a line above: still drawn, so the row does not blink out mid-scroll.
        assertEquals(-10f, gutterIconOffsetPx(visualLine = 0, metrics = metrics, scrollOffsetY = 10))
    }

    @Test
    fun `a line past the bottom edge has no offset`() {
        // Viewport is 1200px, so visual line 60 starts exactly at the bottom edge.
        assertNull(gutterIconOffsetPx(visualLine = 60, metrics = metrics, scrollOffsetY = 0))
        assertEquals(1180f, gutterIconOffsetPx(visualLine = 59, metrics = metrics, scrollOffsetY = 0))
    }

    @Test
    fun `an icon past line 50 in a tall pane still draws`() {
        // The regression: at 20px per line a 1200px pane shows 60 lines, but the old window
        // stopped at 50 (plus a 2 line buffer), so this icon was filtered out before it was
        // ever positioned.
        assertEquals(1120f, gutterIconOffsetPx(visualLine = 56, metrics = metrics, scrollOffsetY = 0))
    }

    @Test
    fun `folding shifts an icon up by the lines it hides`() {
        // Document line 40 sits at visual line 12 once a fold above it collapses 28 lines.
        // The gutter follows what is painted, so the icon moves with it.
        assertEquals(240f, gutterIconOffsetPx(visualLine = 12, metrics = metrics, scrollOffsetY = 0))
    }

    @Test
    fun `an unmeasured viewport still lets the top of the pane draw`() {
        // Before first layout scrollMetrics hands back the assumed height; icons near the top
        // must still appear rather than the gutter going blank.
        val unmeasured = ScrollMetrics(lineHeight = 20f, viewportHeight = UNMEASURED_VIEWPORT_HEIGHT_PX)

        assertEquals(200f, gutterIconOffsetPx(visualLine = 10, metrics = unmeasured, scrollOffsetY = 0))
        assertNull(gutterIconOffsetPx(visualLine = 40, metrics = unmeasured, scrollOffsetY = 0))
    }
}
