package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.bosseditor.core.EditorState
import ai.rever.bosseditor.features.GutterIcon
import ai.rever.bosseditor.features.GutterIconShape
import ai.rever.bosseditor.features.GutterIconType
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer

/**
 * Which lines of a buffer differ from its committed version.
 *
 * Diffs the LIVE buffer against HEAD rather than asking git each time, for two
 * reasons. Cost: this runs for every open buffer, continuously, and shelling
 * out per keystroke is not affordable. Accuracy: git only ever sees the file on
 * disk, so a git-derived marking is stale the moment the user types and would
 * sit at the wrong lines until the next save.
 *
 * The expensive part is avoided the way every diff does it: trim the common
 * prefix and suffix first. A normal edit touches one place, so the band left
 * over is a handful of lines even in a large file, and the LCS runs over that
 * band rather than the document. A band that is still huge - a reformat, a
 * generated file - is reported as one modified region instead, which is honest
 * and costs nothing.
 */
internal object LineDiff {

    enum class Mark {
        /** This line is new since HEAD. */
        ADDED,

        /** This line existed but its content changed. */
        MODIFIED,

        /** Lines were deleted immediately ABOVE this one; it has no row of its own. */
        DELETED,
    }

    /**
     * Above this many lines on either side of the changed band, pairing is not
     * worth an O(n*m) table - the result would be a wall of marks anyway.
     */
    const val MAX_BAND = 600

    /** @return marks keyed by 0-based line index in [new]. */
    fun of(old: List<String>, new: List<String>): Map<Int, Mark> {
        if (old == new) return emptyMap()

        var prefix = 0
        val maxPrefix = minOf(old.size, new.size)
        while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++

        var suffix = 0
        while (suffix < maxPrefix - prefix &&
            old[old.size - 1 - suffix] == new[new.size - 1 - suffix]
        ) {
            suffix++
        }

        val oldBand = old.subList(prefix, old.size - suffix)
        val newBand = new.subList(prefix, new.size - suffix)
        val marks = mutableMapOf<Int, Mark>()

        when {
            oldBand.isEmpty() && newBand.isEmpty() -> return emptyMap()

            // Pure insertion.
            oldBand.isEmpty() -> for (i in newBand.indices) marks[prefix + i] = Mark.ADDED

            // Pure deletion: nothing on the new side to mark, so it goes on the
            // line that now sits where the deleted ones were. At end of file
            // there is no such line, so it goes on the last one.
            newBand.isEmpty() -> markDeletion(marks, prefix, new.size)

            oldBand.size > MAX_BAND || newBand.size > MAX_BAND ->
                for (i in newBand.indices) marks[prefix + i] = Mark.MODIFIED

            else -> pairBand(oldBand, newBand, prefix, new.size, marks)
        }
        return marks
    }

    /**
     * Pair the band with an LCS, then read the unmatched runs as git does:
     * removals and additions that meet become a modification, and whichever
     * side is longer runs on as a plain add or delete.
     */
    private fun pairBand(
        oldBand: List<String>,
        newBand: List<String>,
        offset: Int,
        newLineCount: Int,
        marks: MutableMap<Int, Mark>,
    ) {
        val table = Array(oldBand.size + 1) { IntArray(newBand.size + 1) }
        for (i in oldBand.indices.reversed()) {
            for (j in newBand.indices.reversed()) {
                table[i][j] =
                    if (oldBand[i] == newBand[j]) {
                        table[i + 1][j + 1] + 1
                    } else {
                        maxOf(table[i + 1][j], table[i][j + 1])
                    }
            }
        }

        var i = 0
        var j = 0
        var removed = 0
        var added = mutableListOf<Int>()

        fun flush() {
            if (removed == 0 && added.isEmpty()) return
            val paired = minOf(removed, added.size)
            for (k in 0 until paired) marks[offset + added[k]] = Mark.MODIFIED
            for (k in paired until added.size) marks[offset + added[k]] = Mark.ADDED
            if (removed > added.size) {
                // More lines went than came back: the surplus deletion is
                // reported on the row that follows the block.
                val row = offset + (added.lastOrNull()?.plus(1) ?: j)
                markDeletion(marks, row, newLineCount)
            }
            removed = 0
            added = mutableListOf()
        }

        while (i < oldBand.size && j < newBand.size) {
            when {
                oldBand[i] == newBand[j] -> {
                    flush()
                    i++
                    j++
                }

                table[i + 1][j] >= table[i][j + 1] -> {
                    removed++
                    i++
                }

                else -> {
                    added.add(j)
                    j++
                }
            }
        }
        while (i < oldBand.size) {
            removed++
            i++
        }
        while (j < newBand.size) {
            added.add(j)
            j++
        }
        flush()
    }

    /**
     * Record a deletion at [row].
     *
     * A deleted line has no row of its own, so the mark belongs to the line
     * that now sits where it was - and never overwrites a real add/modify,
     * which is about content the user can actually see.
     */
    private fun markDeletion(marks: MutableMap<Int, Mark>, row: Int, newLineCount: Int) {
        if (newLineCount <= 0) return
        val slot = row.coerceIn(0, newLineCount - 1)
        if (marks[slot] == null) marks[slot] = Mark.DELETED
    }
}

/**
 * Invisible icons, so the editor reserves its gutter icon strip for us.
 *
 * `EditorCanvasRenderer` only makes room for the strip when a line has an icon,
 * and it can paint a shape but never text - `GutterIcon.customText` exists and
 * is never read. So the space is claimed this way and the glyphs are drawn on
 * top by [GitGutterMarks].
 */
internal fun reserveGitGutter(
    marks: Map<Int, LineDiff.Mark>,
    /**
     * Hold the strip open even with nothing to show. The editor only makes room
     * when some line has an icon, so without this the gutter would widen the
     * moment a file became dirty and narrow again on save - the code shifting
     * sideways under the caret as you type. VS Code keeps the column there.
     */
    reserveWhenEmpty: Boolean = false,
): List<GutterIcon> {
    val lines = if (marks.isEmpty() && reserveWhenEmpty) setOf(0) else marks.keys
    return lines.map { line ->
        GutterIcon(
            line = line,
            type = GutterIconType.CUSTOM,
            customColor = Color.Transparent,
            customShape = GutterIconShape.SQUARE,
        )
    }
}

/**
 * VS Code's dirty-diff decorations: what is uncommitted, in the left gutter.
 *
 * Drawn with the editor's OWN icon metrics - x = 4, size = lineHeight / 2,
 * centred the way `drawGutterIconForLine` centres a shape - so the glyphs land
 * exactly in the strip the editor reserved, at any font size.
 *
 * A deleted line has no row to sit on, so it is drawn as a small wedge on the
 * TOP edge of the line that now occupies its place, rather than as a glyph that
 * would claim the surviving line was itself deleted.
 */
@Composable
internal fun GitGutterMarks(
    marks: Map<Int, LineDiff.Mark>,
    state: EditorState,
) {
    if (marks.isEmpty()) return
    val viewport by state.visibleViewport.collectAsState()
    val scroll by state.scrollOffset.collectAsState()
    val mapper by state.visualLineMapper.collectAsState()
    val measurer = rememberTextMeasurer()

    Canvas(modifier = Modifier.fillMaxSize()) {
        val lineHeight = viewport.lineHeight
        if (lineHeight <= 0f) return@Canvas
        val slot = lineHeight * GIT_GUTTER_ICON_RATIO
        val style = TextStyle(
            fontSize = (lineHeight * GIT_GUTTER_GLYPH_RATIO).toSp(),
            fontFamily = FontFamily.Monospace,
        )
        val added = measurer.measure(AnnotatedString("+"), style)
        val modified = measurer.measure(AnnotatedString("~"), style)

        val first = (scroll.y / lineHeight).toInt().coerceAtLeast(0)
        val last = first + (size.height / lineHeight).toInt() + 1
        for (visual in first..last) {
            if (visual >= mapper.visibleLineCount) break
            val mark = marks[mapper.visualToDocument(visual)] ?: continue
            val top = visual * lineHeight - scroll.y
            when (mark) {
                LineDiff.Mark.DELETED -> {
                    // A wedge straddling the boundary the deleted lines left.
                    val wedge = slot * 0.5f
                    drawPath(
                        path = Path().apply {
                            moveTo(GIT_GUTTER_PADDING, top)
                            lineTo(GIT_GUTTER_PADDING + wedge, top)
                            lineTo(GIT_GUTTER_PADDING, top + wedge)
                            close()
                        },
                        color = GitRemoved,
                    )
                }

                else -> {
                    val laid = if (mark == LineDiff.Mark.ADDED) added else modified
                    drawText(
                        textLayoutResult = laid,
                        color = if (mark == LineDiff.Mark.ADDED) GitAdded else GitModified,
                        topLeft = Offset(
                            GIT_GUTTER_PADDING + (slot - laid.size.width) / 2f,
                            top + (lineHeight - laid.size.height) / 2f,
                        ),
                    )
                }
            }
        }
    }
}

/** `EditorCanvasRenderer` draws a gutter icon at this x, with this size. */
private const val GIT_GUTTER_PADDING = 4f
private const val GIT_GUTTER_ICON_RATIO = 0.5f
private const val GIT_GUTTER_GLYPH_RATIO = 0.42f

private val GitAdded = Color(0xFF3FB950)
private val GitModified = Color(0xFF58A6FF)
private val GitRemoved = Color(0xFFF85149)
