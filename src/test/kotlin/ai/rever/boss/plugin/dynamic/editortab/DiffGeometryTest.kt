package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.DiffLineKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where the change bands land.
 *
 * The port drew them at `(line - firstVisibleLine) * lineHeight`, which drops
 * the sub-line remainder of the scroll offset - the marks drifted off the code
 * they belonged to and there was nothing on screen to say so. These pin the
 * editor's own formula: `visualLine * lineHeight - scrollY`.
 */
class DiffGeometryTest {

    private val A = DiffLineKind.ADDED
    private val R = DiffLineKind.REMOVED

    private fun marks(vararg kinds: DiffLineKind?): List<DiffLineKind?> = kinds.toList()

    @Test
    fun `a band sits exactly on its line when unscrolled`() {
        val bands = DiffGeometry.bands(
            marks = marks(null, A, null),
            scrollY = 0f,
            lineHeight = 20f,
            viewportHeight = 100f,
        )
        assertEquals(1, bands.size)
        assertEquals(20f, bands[0].topPx)
        assertEquals(20f, bands[0].heightPx)
        assertEquals(A, bands[0].kind)
    }

    @Test
    fun `a sub-line scroll offset moves the band by that many pixels`() {
        // The bug: firstVisibleLine is still 0 at scrollY=7, so a line-based
        // overlay would draw at 20 rather than 13.
        val bands = DiffGeometry.bands(
            marks = marks(null, A),
            scrollY = 7f,
            lineHeight = 20f,
            viewportHeight = 100f,
        )
        assertEquals(13f, bands[0].topPx)
    }

    @Test
    fun `a band above the viewport is not drawn`() {
        val bands = DiffGeometry.bands(
            marks = marks(A, null, null, null, null, null),
            scrollY = 60f,
            lineHeight = 20f,
            viewportHeight = 40f,
        )
        assertTrue(bands.isEmpty())
    }

    @Test
    fun `a band below the viewport is not drawn`() {
        val bands = DiffGeometry.bands(
            marks = marks(null, null, null, null, null, null, null, null, null, null, A),
            scrollY = 0f,
            lineHeight = 20f,
            viewportHeight = 40f,
        )
        assertTrue(bands.isEmpty())
    }

    @Test
    fun `the line straddling the top edge is still drawn, with a negative top`() {
        val bands = DiffGeometry.bands(
            marks = marks(null, A, null, null),
            scrollY = 25f,
            lineHeight = 20f,
            viewportHeight = 40f,
        )
        assertEquals(1, bands.size)
        assertEquals(-5f, bands[0].topPx)
    }

    @Test
    fun `consecutive lines of the same kind merge into one band`() {
        val bands = DiffGeometry.bands(
            marks = marks(A, A, A, null),
            scrollY = 0f,
            lineHeight = 10f,
            viewportHeight = 100f,
        )
        assertEquals(1, bands.size)
        assertEquals(0f, bands[0].topPx)
        assertEquals(30f, bands[0].heightPx)
    }

    @Test
    fun `adjacent runs of different kinds stay separate`() {
        val bands = DiffGeometry.bands(
            marks = marks(R, R, A, A),
            scrollY = 0f,
            lineHeight = 10f,
            viewportHeight = 100f,
        )
        assertEquals(2, bands.size)
        assertEquals(R, bands[0].kind)
        assertEquals(0f, bands[0].topPx)
        assertEquals(20f, bands[0].heightPx)
        assertEquals(A, bands[1].kind)
        assertEquals(20f, bands[1].topPx)
        assertEquals(20f, bands[1].heightPx)
    }

    @Test
    fun `a gap between runs of the same kind is not merged`() {
        val bands = DiffGeometry.bands(
            marks = marks(A, null, A),
            scrollY = 0f,
            lineHeight = 10f,
            viewportHeight = 100f,
        )
        assertEquals(2, bands.size)
        assertEquals(0f, bands[0].topPx)
        assertEquals(20f, bands[1].topPx)
    }

    @Test
    fun `folding is honoured - marks index document lines, the screen shows visual ones`() {
        // Document lines 1..3 are collapsed away; visual line 1 IS document 4.
        val documentOf = mapOf(0 to 0, 1 to 4)
        val bands = DiffGeometry.bands(
            marks = marks(null, null, null, null, A),
            scrollY = 0f,
            lineHeight = 10f,
            viewportHeight = 100f,
            visualLineCount = 2,
            visualToDocument = { documentOf[it] ?: it },
        )
        assertEquals(1, bands.size)
        assertEquals(10f, bands[0].topPx, "the change draws where the editor put it, not at line 4")
    }

    @Test
    fun `a zero line height draws nothing rather than dividing by zero`() {
        assertTrue(
            DiffGeometry.bands(marks(A), scrollY = 0f, lineHeight = 0f, viewportHeight = 100f).isEmpty(),
        )
    }

    @Test
    fun `an unmeasured viewport draws nothing`() {
        assertTrue(
            DiffGeometry.bands(marks(A), scrollY = 0f, lineHeight = 20f, viewportHeight = 0f).isEmpty(),
        )
    }

    @Test
    fun `firstChangedLine finds the first add or remove`() {
        assertEquals(2, DiffGeometry.firstChangedLine(marks(null, null, R, A)))
        assertEquals(0, DiffGeometry.firstChangedLine(marks(A)))
        assertNull(DiffGeometry.firstChangedLine(marks(null, null)))
        assertNull(DiffGeometry.firstChangedLine(emptyList()))
    }

    // ── whole-document overview (painted on the minimap) ──────────────────

    private fun overview(marks: List<DiffLineKind?>, height: Float, lineCount: Int = marks.size) =
        DiffGeometry.overviewBands(
            marks = marks,
            height = height,
            lineHeight = DiffGeometry.minimapLineHeight(height, lineCount, 2f),
            lineCount = lineCount,
        )

    @Test
    fun `a file that fits uses the minimap's fixed per-line height, not the full strip`() {
        // THE bug: 100 lines at 2px occupy the top 200px of a 1000px minimap.
        // Spreading them over 1000px put every mark at five times its true
        // depth, out past the end of the code overview.
        assertEquals(2f, DiffGeometry.minimapLineHeight(height = 1000f, lineCount = 100, configLineHeight = 2f))
        val marks = MutableList<DiffLineKind?>(100) { null }
        marks[50] = A
        val bands = overview(marks, height = 1000f)
        assertEquals(100f, bands[0].topPx, "line 50 sits at 50 x 2px, not halfway down the strip")
    }

    @Test
    fun `a file that overflows compresses to fit, as the minimap does`() {
        assertEquals(0.5f, DiffGeometry.minimapLineHeight(height = 1000f, lineCount = 2000, configLineHeight = 2f))
        val marks = MutableList<DiffLineKind?>(2000) { null }
        marks[1000] = R
        val bands = overview(marks, height = 1000f)
        assertEquals(500f, bands[0].topPx)
    }

    @Test
    fun `a mark's position does not depend on scroll - there is no scroll input at all`() {
        // The user's report was marks tracking the viewport instead of the
        // content. overviewBands takes no scroll parameter; this pins that it
        // stays that way, by computing the same mark under two viewports.
        val marks = MutableList<DiffLineKind?>(500) { null }
        marks[300] = A
        val atTop = overview(marks, height = 1200f)
        val atBottom = overview(marks, height = 1200f)
        assertEquals(atTop[0].topPx, atBottom[0].topPx)
        assertEquals(600f, atTop[0].topPx, "300 lines x 2px, whatever is on screen")
    }

    @Test
    fun `no mark is drawn past the end of the code overview`() {
        // Below lineCount * lineHeight the minimap is empty background; a mark
        // there points at nothing.
        val marks = MutableList<DiffLineKind?>(100) { null }
        marks[99] = A
        val bands = overview(marks, height = 1000f)
        val contentBottom = 100 * 2f
        assertTrue(
            bands[0].topPx + bands[0].heightPx <= contentBottom,
            "band ran to ${bands[0].topPx + bands[0].heightPx}, past the overview at $contentBottom",
        )
    }

    @Test
    fun `a single line in a long file is still at least the minimum band`() {
        val marks = MutableList<DiffLineKind?>(5000) { null }
        marks[10] = A
        val bands = DiffGeometry.overviewBands(
            marks = marks, height = 500f,
            lineHeight = DiffGeometry.minimapLineHeight(500f, 5000, 2f),
            minBandPx = 2f, lineCount = 5000,
        )
        assertEquals(1, bands.size)
        assertEquals(2f, bands[0].heightPx)
    }

    @Test
    fun `an overview merges runs and keeps kinds apart`() {
        val marks = listOf<DiffLineKind?>(R, R, A, A, null, null, null, null, null, null)
        val bands = overview(marks, height = 1000f)
        assertEquals(2, bands.size)
        assertEquals(R, bands[0].kind)
        assertEquals(0f, bands[0].topPx)
        assertEquals(4f, bands[0].heightPx)
        assertEquals(A, bands[1].kind)
        assertEquals(4f, bands[1].topPx)
    }

    @Test
    fun `the live document line count decides when the overview compresses`() {
        // The pane is editable, so the file can gain lines before the diff
        // reloads - and the line count is what tips the minimap from its fixed
        // per-line height into compressing. Reading a stale count would put the
        // marks on a different scale from the code overview under them.
        val marks = MutableList<DiffLineKind?>(100) { null }
        marks[50] = A
        // 100 lines x 2px fits in 300px: fixed height, line 50 at 100px.
        assertEquals(100f, overview(marks, height = 300f, lineCount = 100)[0].topPx)
        // 200 lines x 2px does not: compressed to 1.5px, line 50 at 75px.
        assertEquals(75f, overview(marks, height = 300f, lineCount = 200)[0].topPx)
    }

    @Test
    fun `an unmeasured or empty overview draws nothing`() {
        assertTrue(overview(marks(A), height = 0f).isEmpty())
        assertTrue(overview(emptyList(), height = 100f).isEmpty())
        assertTrue(
            DiffGeometry.overviewBands(marks(A), height = 100f, lineHeight = 0f).isEmpty(),
        )
    }

    // ── minimap width (BossEditor's own formula) ──────────────────────────

    @Test
    fun `the minimap is as wide as its longest line needs`() {
        // 60 chars x 1.5 = 90, inside [50, 120].
        assertEquals(90f, DiffGeometry.minimapWidthDp(longestLineChars = 60, maxWidth = 120f))
    }

    @Test
    fun `a short file gets the floor, not the setting`() {
        // THE bug an overlay would hit: a file whose longest line is 10 chars
        // gives a 50dp minimap, not the 80dp minimapWidth setting - an overlay
        // sized from the setting would sit 30dp to the left of the minimap.
        assertEquals(50f, DiffGeometry.minimapWidthDp(longestLineChars = 10, maxWidth = 80f))
    }

    @Test
    fun `a long file is capped by the minimapWidth setting`() {
        assertEquals(80f, DiffGeometry.minimapWidthDp(longestLineChars = 400, maxWidth = 80f))
    }

    @Test
    fun `a minimapWidth setting below the floor does not invert the clamp`() {
        // coerceIn throws when min > max; the floor has to win.
        assertEquals(50f, DiffGeometry.minimapWidthDp(longestLineChars = 200, maxWidth = 20f))
    }

    @Test
    fun `an empty document still gets the floor`() {
        assertEquals(50f, DiffGeometry.minimapWidthDp(longestLineChars = 0, maxWidth = 80f))
    }

    // ── scroll translation across unequal sides ───────────────────────────

    @Test
    fun `an identity map mirrors the offset unchanged`() {
        val map = IntArray(100) { it }
        assertEquals(1234, DiffGeometry.mirroredScrollY(1234, 20f, map))
    }

    @Test
    fun `the sub-line remainder is carried over, not rounded away`() {
        // Line 5 of 20px, 7px in. The follower shows line 8, same 7px in.
        val map = IntArray(20) { it + 3 }
        assertEquals(8 * 20 + 7, DiffGeometry.mirroredScrollY(5 * 20 + 7, 20f, map))
    }

    @Test
    fun `an insertion above pushes the follower down by that many lines`() {
        // old: a, b  ->  new: a, ins, b   (oldToNew = [0, 2])
        val oldToNew = intArrayOf(0, 2)
        assertEquals(0, DiffGeometry.mirroredScrollY(0, 10f, oldToNew))
        assertEquals(20, DiffGeometry.mirroredScrollY(10, 10f, oldToNew))
    }

    @Test
    fun `scrolling past the end of the map keeps the overhang`() {
        // Without this the follower snaps back to the last mapped line and the
        // panes stick at the bottom of a longer file.
        val map = intArrayOf(0, 1, 2)
        assertEquals(50, DiffGeometry.mirroredScrollY(50, 10f, map))
    }

    @Test
    fun `an unmeasured line height mirrors verbatim rather than dividing by zero`() {
        assertEquals(700, DiffGeometry.mirroredScrollY(700, 0f, intArrayOf(0, 5)))
    }

    @Test
    fun `an empty map mirrors verbatim`() {
        assertEquals(700, DiffGeometry.mirroredScrollY(700, 20f, IntArray(0)))
    }

    @Test
    fun `a mirrored offset is never negative`() {
        assertTrue(DiffGeometry.mirroredScrollY(0, 20f, intArrayOf(0)) >= 0)
    }

    // ── horizontal spans (the intra-line highlight) ───────────────────────

    @Test
    fun `a span sits at gutter plus column times char width`() {
        val rect = DiffGeometry.spanRect(
            startColumn = 4, endColumn = 8,
            charWidth = 10f, gutterWidth = 50f, scrollX = 0f, viewportWidth = 500f,
        )
        assertEquals(90f, rect!!.first)
        assertEquals(40f, rect.second)
    }

    @Test
    fun `horizontal scroll moves the span left`() {
        val rect = DiffGeometry.spanRect(
            startColumn = 10, endColumn = 12,
            charWidth = 10f, gutterWidth = 50f, scrollX = 30f, viewportWidth = 500f,
        )
        assertEquals(120f, rect!!.first)
    }

    @Test
    fun `a span scrolled behind the gutter is clipped, never painted over the line numbers`() {
        val rect = DiffGeometry.spanRect(
            startColumn = 0, endColumn = 4,
            charWidth = 10f, gutterWidth = 50f, scrollX = 25f, viewportWidth = 500f,
        )
        assertEquals(50f, rect!!.first, "clipped to the gutter edge")
        assertEquals(15f, rect.second)
    }

    @Test
    fun `a span entirely off to the left draws nothing`() {
        assertNull(
            DiffGeometry.spanRect(
                startColumn = 0, endColumn = 2,
                charWidth = 10f, gutterWidth = 50f, scrollX = 500f, viewportWidth = 500f,
            ),
        )
    }

    @Test
    fun `a span past the right edge is clipped to the viewport`() {
        val rect = DiffGeometry.spanRect(
            startColumn = 40, endColumn = 60,
            charWidth = 10f, gutterWidth = 50f, scrollX = 0f, viewportWidth = 500f,
        )
        assertEquals(450f, rect!!.first)
        assertEquals(50f, rect.second)
    }

    @Test
    fun `an empty or unmeasured span draws nothing`() {
        assertNull(DiffGeometry.spanRect(4, 4, 10f, 50f, 0f, 500f))
        assertNull(DiffGeometry.spanRect(4, 8, 0f, 50f, 0f, 500f))
    }

    // ── the text area's right edge ────────────────────────────────────────

    @Test
    fun `the text area ends a gutter's width past the reported viewport width`() {
        // Measured live: the pane without a minimap reported gutter 118 and
        // viewportWidth 1188 in a 1306px box, and 118 + 1188 is the box exactly.
        // viewportWidth is a WIDTH, not a right edge; reading it as an edge left
        // every band 118px short, which is the "stops short" that was reported.
        assertEquals(1306f, DiffGeometry.textAreaRight(gutterWidth = 118f, viewportWidth = 1188f, boxWidth = 1306f))
    }

    @Test
    fun `the pane with a minimap stops at the divider, not over the minimap`() {
        // Measured live: gutter 118, viewportWidth 1026, box 1306. The 162px
        // left over is the 160px minimap plus its 1dp divider.
        assertEquals(1144f, DiffGeometry.textAreaRight(gutterWidth = 118f, viewportWidth = 1026f, boxWidth = 1306f))
    }

    @Test
    fun `the text area never runs past the pane box`() {
        assertEquals(1306f, DiffGeometry.textAreaRight(gutterWidth = 118f, viewportWidth = 9000f, boxWidth = 1306f))
    }

    @Test
    fun `an unmeasured viewport falls back to the whole box rather than collapsing`() {
        assertEquals(1306f, DiffGeometry.textAreaRight(gutterWidth = 118f, viewportWidth = 0f, boxWidth = 1306f))
    }

    @Test
    fun `a gutter wider than the box cannot produce a negative width band`() {
        val right = DiffGeometry.textAreaRight(gutterWidth = 2000f, viewportWidth = 10f, boxWidth = 1306f)
        assertTrue(right <= 1306f)
    }
}
