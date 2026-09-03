package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.DiffLineKind
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Where the change bands land on screen, in the editor's own coordinates.
 *
 * BossEditor draws visual line *v* at `v * lineHeight - scrollOffset.y` (see
 * `EditorCanvasRenderer.drawCurrentLineHighlight`), so any overlay that wants
 * to sit on a line has to use exactly that. The port used
 * `(line - firstVisibleLine) * lineHeight` instead, which throws away the
 * sub-line remainder of the scroll offset and drifts by up to a full line -
 * the marks never sat on the code they belonged to.
 *
 * Pure, because this is precisely the kind of arithmetic that looks right and
 * is off by one.
 */
internal object DiffGeometry {

    /**
     * A run of consecutive changed lines, in px relative to the editor's
     * top-left.
     *
     * Generic in the mark because the two callers do not share one: the line
     * tint marks rows [DiffLineKind.ADDED]/[DiffLineKind.REMOVED], while the
     * minimap overview needs a mark that can say BOTH.
     */
    data class Band<T>(
        val topPx: Float,
        val heightPx: Float,
        val kind: T,
    )

    /**
     * The change bands intersecting the viewport.
     *
     * [marks] is indexed by DOCUMENT line; the editor scrolls in VISUAL lines
     * (folding collapses documents lines away), so the two are bridged by
     * [visualToDocument] rather than assumed equal.
     */
    fun <T : Any> bands(
        marks: List<T?>,
        scrollY: Float,
        lineHeight: Float,
        viewportHeight: Float,
        visualLineCount: Int = marks.size,
        visualToDocument: (Int) -> Int = { it },
    ): List<Band<T>> {
        if (lineHeight <= 0f || viewportHeight <= 0f || marks.isEmpty() || visualLineCount <= 0) {
            return emptyList()
        }
        val first = floor(scrollY / lineHeight).toInt().coerceAtLeast(0)
        val last =
            ceil((scrollY + viewportHeight) / lineHeight).toInt().coerceAtMost(visualLineCount - 1)
        if (last < first) return emptyList()

        val out = mutableListOf<Band<T>>()
        var runKind: T? = null
        var runStart = first
        var runEnd = first

        fun flush() {
            val kind = runKind ?: return
            out.add(
                Band(
                    topPx = runStart * lineHeight - scrollY,
                    heightPx = (runEnd - runStart + 1) * lineHeight,
                    kind = kind,
                ),
            )
            runKind = null
        }

        for (visual in first..last) {
            val document = visualToDocument(visual)
            val kind = marks.getOrNull(document)
            if (kind != null && kind == runKind && visual == runEnd + 1) {
                runEnd = visual
            } else {
                flush()
                if (kind != null) {
                    runKind = kind
                    runStart = visual
                    runEnd = visual
                }
            }
        }
        flush()
        return out
    }

    /**
     * The height BossEditor gives ONE line of its minimap.
     *
     * Replicated from `MinimapRenderer.calculateLineHeight`, and the whole of
     * the bug it fixes: the minimap does NOT stretch the document to fill its
     * height. It draws a fixed [configLineHeight] px per line, and only
     * compresses to `height / lineCount` once the file is long enough to
     * overflow. A 377-line file in a 1510px minimap therefore occupies the top
     * 754px, and marks placed at `line / lineCount * height` land at twice
     * their true depth - out past the end of the code overview entirely, next
     * to nothing.
     */
    fun minimapLineHeight(
        height: Float,
        lineCount: Int,
        configLineHeight: Float,
    ): Float {
        if (lineCount <= 0 || height <= 0f) return 0f
        val total = lineCount * configLineHeight
        return if (total > height) height / lineCount else configLineHeight
    }

    /**
     * The change bands for a WHOLE-DOCUMENT overview strip.
     *
     * Different arithmetic from [bands] and easy to conflate: an overview maps
     * every line of the document onto the strip at the minimap's own per-line
     * height, where [bands] maps the handful of lines currently on screen.
     * Nothing here reads the scroll offset - a change at line N sits at the
     * same y whatever the user is looking at, which is the entire point of an
     * overview.
     *
     * A one-line change rounds to a fraction of a pixel in a long file, so a
     * band is never thinner than [minBandPx] - an overview whose marks round
     * away is not an overview.
     */
    fun <T : Any> overviewBands(
        marks: List<T?>,
        height: Float,
        lineHeight: Float,
        minBandPx: Float = 2f,
        lineCount: Int = marks.size,
    ): List<Band<T>> {
        if (height <= 0f || marks.isEmpty() || lineCount <= 0 || lineHeight <= 0f) return emptyList()
        // Never past the end of the drawn overview: below this the minimap is
        // empty background, and a mark there points at nothing.
        val contentHeight = minOf(lineCount * lineHeight, height)
        val out = mutableListOf<Band<T>>()
        var runKind: T? = null
        var runStart = 0
        var runEnd = 0

        fun flush() {
            val kind = runKind ?: return
            val top = runStart * lineHeight
            val raw = (runEnd - runStart + 1) * lineHeight
            val tall = maxOf(raw, minBandPx)
            out.add(
                Band(
                    topPx = minOf(top, (contentHeight - tall).coerceAtLeast(0f)),
                    heightPx = minOf(tall, contentHeight),
                    kind = kind,
                ),
            )
            runKind = null
        }

        for (line in marks.indices) {
            if (line >= lineCount) break
            val kind = marks[line]
            if (kind != null && kind == runKind && line == runEnd + 1) {
                runEnd = line
            } else {
                flush()
                if (kind != null) {
                    runKind = kind
                    runStart = line
                    runEnd = line
                }
            }
        }
        flush()
        return out
    }

    /**
     * The right edge of the editor's text area, in the pane box's coordinates.
     *
     * `VisibleViewport.viewportWidth` is the WIDTH of the text area, not its
     * right edge - the editor draws its own current-line highlight from
     * `gutterWidth` with exactly that width (see
     * `EditorCanvasRenderer.drawCurrentLineHighlight`). Using it as an absolute
     * x left every change band one whole gutter short of the edge: measured at
     * 118px on both panes, which is precisely the "stops short" that was
     * reported.
     *
     * Clamped to the pane box, so on the pane that carries a minimap the band
     * still stops at the divider rather than washing over the minimap.
     */
    fun textAreaRight(
        gutterWidth: Float,
        viewportWidth: Float,
        boxWidth: Float,
    ): Float =
        if (viewportWidth <= 0f) {
            boxWidth
        } else {
            (gutterWidth + viewportWidth).coerceIn(minOf(gutterWidth, boxWidth), boxWidth)
        }

    /**
     * Where a character range sits horizontally, in the editor's coordinates.
     *
     * The editor lays text out monospaced from its gutter:
     * `x = gutterWidth + column * charWidth - scrollX` (the same arithmetic
     * `EditorCanvasRenderer.drawSelection` uses). Clipped to the text area so a
     * range scrolled off to the left cannot paint over the line numbers.
     *
     * @return left edge and width in px, or null when nothing is visible.
     */
    fun spanRect(
        startColumn: Int,
        endColumn: Int,
        charWidth: Float,
        gutterWidth: Float,
        scrollX: Float,
        viewportWidth: Float,
    ): Pair<Float, Float>? {
        if (charWidth <= 0f || endColumn <= startColumn) return null
        val rawLeft = gutterWidth + startColumn * charWidth - scrollX
        val rawRight = gutterWidth + endColumn * charWidth - scrollX
        val left = rawLeft.coerceIn(gutterWidth, maxOf(gutterWidth, viewportWidth))
        val right = rawRight.coerceIn(gutterWidth, maxOf(gutterWidth, viewportWidth))
        if (right <= left) return null
        return left to (right - left)
    }

    /**
     * The width BossEditor gives its minimap, in dp.
     *
     * Replicated from `MinimapRenderer.calculateOptimalWidth`, which is
     * `coerceIn(longestLine * charWidth, minWidth, maxWidth)` - so the minimap
     * is as wide as the file's longest line needs, NOT the `minimapWidth`
     * setting, which is only the upper bound. Anything overlaid on the minimap
     * has to compute this; assuming `minimapWidth` puts the overlay beside the
     * minimap on any file whose lines are short.
     *
     * The defaults are BossEditor's own: charWidth 1.5, minWidth 50, and
     * maxWidth the `minimapWidth` setting (see the MinimapConfig it builds).
     */
    fun minimapWidthDp(
        longestLineChars: Int,
        charWidth: Float = MINIMAP_CHAR_WIDTH,
        minWidth: Float = MINIMAP_MIN_WIDTH,
        maxWidth: Float,
    ): Float = (longestLineChars * charWidth).coerceIn(minWidth, maxOf(maxWidth, minWidth))

    /** BossEditor's MinimapConfig default. */
    const val MINIMAP_CHAR_WIDTH = 1.5f

    /** BossEditor passes this explicitly when it builds the minimap config. */
    const val MINIMAP_MIN_WIDTH = 50f

    /** `calculateOptimalWidth` only scans this many lines before giving up. */
    const val MINIMAP_SCAN_LINES = 1000

    /**
     * Where the follower pane must scroll so it shows the same code as the
     * leader at [sourceY].
     *
     * The sides are no longer padded to equal length, so mirroring the raw
     * pixel offset would drift by one line per net insertion. The leader's
     * offset is split into a line and a sub-line remainder; the LINE is
     * translated through [correspondence] and the remainder is carried over
     * untouched, so a smooth scroll stays smooth.
     */
    fun mirroredScrollY(
        sourceY: Int,
        lineHeight: Float,
        correspondence: IntArray,
    ): Int {
        if (lineHeight <= 0f || correspondence.isEmpty()) return sourceY
        val line = floor(sourceY / lineHeight).toInt()
        if (line < 0) return sourceY
        val remainder = sourceY - line * lineHeight
        val target = if (line < correspondence.size) {
            correspondence[line]
        } else {
            // Past the end of the map (the tail of a longer file): keep the
            // overhang rather than snapping back to the last mapped line.
            correspondence.last() + (line - correspondence.size + 1)
        }
        return (target * lineHeight + remainder).roundToInt().coerceAtLeast(0)
    }

    /**
     * The first line carrying a change, or null when nothing changed.
     *
     * The host asks git for the diff with `-U100000`, so a diff is the WHOLE
     * file with a handful of marked lines in it. Opening at line 1 therefore
     * shows two identical screens of unchanged code - which is exactly the
     * "it looks like one file in two tabs" the port was reported for.
     */
    fun firstChangedLine(marks: List<DiffLineKind?>): Int? =
        marks
            .indexOfFirst { it == DiffLineKind.ADDED || it == DiffLineKind.REMOVED }
            .takeIf { it >= 0 }
}
