package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.bosseditor.core.VisibleViewport
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Navigation centres a line by handing `scrollToLine` a line height and a viewport height.
 * Those used to be a font-metric estimate and a hardcoded 600px, which put the "centred"
 * line a quarter of the way down a 1200px pane. The canvas publishes real measurements on
 * `visibleViewport` once it has been laid out; these pin which number wins when.
 */
class ScrollMetricsTest {

    private fun viewport(lineHeight: Float, viewportHeight: Float) = VisibleViewport(
        firstVisibleLine = 0,
        visibleLineCount = 30,
        lineHeight = lineHeight,
        viewportHeight = viewportHeight
    )

    @Test
    fun `measured viewport wins over the estimate`() {
        val metrics = scrollMetrics(viewport(lineHeight = 18.5f, viewportHeight = 1200f), fallbackLineHeight = 16.8f)

        assertEquals(18.5f, metrics.lineHeight)
        assertEquals(1200f, metrics.viewportHeight)
    }

    @Test
    fun `an unmeasured viewport falls back to the estimate`() {
        // What a freshly composed tab reports, before EditorCanvas has laid out.
        val metrics = scrollMetrics(viewport(lineHeight = 0f, viewportHeight = 0f), fallbackLineHeight = 16.8f)

        assertEquals(16.8f, metrics.lineHeight)
        assertEquals(UNMEASURED_VIEWPORT_HEIGHT_PX, metrics.viewportHeight)
    }

    @Test
    fun `each metric is guarded on its own`() {
        // scrollToLine divides by lineHeight. A zero line height must not ride in on the
        // strength of a measured viewport height - that is a divide-by-zero, not a bad scroll.
        val halfMeasured = scrollMetrics(viewport(lineHeight = 0f, viewportHeight = 1200f), fallbackLineHeight = 16.8f)

        assertEquals(16.8f, halfMeasured.lineHeight)
        assertEquals(1200f, halfMeasured.viewportHeight)

        // And the mirror image, which a single is-it-measured flag would also get wrong.
        val otherHalf = scrollMetrics(viewport(lineHeight = 18.5f, viewportHeight = 0f), fallbackLineHeight = 16.8f)

        assertEquals(18.5f, otherHalf.lineHeight)
        assertEquals(UNMEASURED_VIEWPORT_HEIGHT_PX, otherHalf.viewportHeight)
    }

    @Test
    fun `a negative measurement is treated as unmeasured`() {
        val metrics = scrollMetrics(viewport(lineHeight = -1f, viewportHeight = -1f), fallbackLineHeight = 16.8f)

        assertEquals(16.8f, metrics.lineHeight)
        assertEquals(UNMEASURED_VIEWPORT_HEIGHT_PX, metrics.viewportHeight)
    }
}
