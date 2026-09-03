package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.DiffLine
import ai.rever.boss.plugin.api.DiffLineKind
import ai.rever.boss.plugin.api.GitDiffData

/**
 * The two file versions a side-by-side diff shows, rebuilt from a unified diff.
 *
 * Built TWICE from one walk, because alignment and editability cannot come from
 * the same document:
 *
 * - [Sides.old]/[Sides.new] are UNPADDED - exactly the pre- and post-image. The
 *   new side IS the working-tree file, which is the only reason it can be
 *   handed to an editable editor: there is no filler in it to write back into
 *   the user's source. The panes do not sit row-for-row, so
 *   [Sides.oldToNew]/[Sides.newToOld] carry the alignment to the scroll lock.
 * - [Sides.alignedOld]/[Sides.alignedNew] are padded with filler rows so a
 *   deletion on the left is faced by blank space on the right and matching code
 *   sits on the same row. That document is nobody's file and is only ever shown
 *   read-only.
 *
 * VS Code gets both at once with view zones - vertical space that is not a
 * document line. BossEditor has none: `lineHeight` is one float for the whole
 * editor, `EditorCanvasRenderer` draws every row at
 * `visualLine * lineHeight - scrollY`, and `VisualLineMapper` is built only
 * from folds, which HIDE document lines and can never introduce a row with
 * none. So the filler has to be real rows - and the price, that a blank row
 * would take a line number and shift every number after it, is paid by
 * [Pane.lineNumbers]: the numbers come from git's own `oldLine`/`newLine`, the
 * editor's own numbering is switched off, and the gutter is drawn from these.
 *
 * Pure, because "which line ended up opposite which" is exactly the sort of
 * thing that looks plausible and is quietly off by one - and here being wrong
 * would mean writing to the wrong line of a real file.
 */
internal object DiffSides {

    /**
     * A change as the whole-file overview sees it.
     *
     * [BOTH] exists for the unpadded form, where a replacement removed lines
     * AND added them at the same place and there is no spare row on the new
     * side to put the deletion on. Marking such a row [BOTH] beats inventing a
     * row (wrong line) or dropping the deletion (which is why the red went
     * missing from every replacement).
     */
    enum class OverviewMark { ADDED, REMOVED, BOTH }

    /** One pane's rows. */
    data class Pane(
        val text: String,
        /** Per row; null means unchanged context, or filler. */
        val marks: List<DiffLineKind?>,
        /** Row -> the character ranges that differ from the paired row. */
        val inline: Map<Int, List<IntRange>>,
        /**
         * The file line number to show for each row, 1-based, as git reported
         * it. Null on a filler row, which is not a line of any file and must
         * not take a number - that is the whole reason the editor's own
         * numbering is off.
         */
        val lineNumbers: List<Int?>,
        /** Rows that are inserted blank space, not file content. */
        val filler: Set<Int>,
    ) {
        val rowCount: Int get() = marks.size
    }

    data class Sides(
        // ── unpadded: real file text, safe to edit ────────────────────────
        val old: Pane,
        val new: Pane,
        val oldToNew: IntArray,
        val newToOld: IntArray,
        /** Every change on the NEW unpadded axis, for the minimap. */
        val overviewMarks: List<OverviewMark?>,
        // ── padded: rows line up, so it is nobody's file ──────────────────
        val alignedOld: Pane,
        val alignedNew: Pane,
        /** Every change on the PADDED axis, for the minimap in aligned mode. */
        val alignedOverview: List<OverviewMark?>,
    ) {
        val oldText: String get() = old.text
        val newText: String get() = new.text
        val oldMarks: List<DiffLineKind?> get() = old.marks
        val newMarks: List<DiffLineKind?> get() = new.marks
        val oldInline: Map<Int, List<IntRange>> get() = old.inline
        val newInline: Map<Int, List<IntRange>> get() = new.inline
    }

    fun of(diff: GitDiffData): Sides {
        val oldLines = mutableListOf<String>()
        val newLines = mutableListOf<String>()
        val oldMarks = mutableListOf<DiffLineKind?>()
        val newMarks = mutableListOf<DiffLineKind?>()
        val oldNumbers = mutableListOf<Int?>()
        val newNumbers = mutableListOf<Int?>()
        val oldInline = mutableMapOf<Int, List<IntRange>>()
        val newInline = mutableMapOf<Int, List<IntRange>>()
        val oldToNew = mutableListOf<Int>()
        val newToOld = mutableListOf<Int>()
        val overview = mutableListOf<OverviewMark?>()
        val deletedOnlyAtNewLine = mutableListOf<Int>()

        // The padded form, built from the same blocks so the two can never
        // disagree about what changed - only about where it sits.
        val padOld = mutableListOf<String>()
        val padNew = mutableListOf<String>()
        val padOldMarks = mutableListOf<DiffLineKind?>()
        val padNewMarks = mutableListOf<DiffLineKind?>()
        val padOldNumbers = mutableListOf<Int?>()
        val padNewNumbers = mutableListOf<Int?>()
        val padOldInline = mutableMapOf<Int, List<IntRange>>()
        val padNewInline = mutableMapOf<Int, List<IntRange>>()
        val padOldFiller = mutableSetOf<Int>()
        val padNewFiller = mutableSetOf<Int>()
        val padOverview = mutableListOf<OverviewMark?>()

        val pendingRemoved = mutableListOf<DiffLine>()
        val pendingAdded = mutableListOf<DiffLine>()

        /**
         * Emit a buffered removed/added block.
         *
         * Buffering is what makes a REPLACEMENT pair element-wise: git emits
         * every `-` of a block before its `+`, so pairing them as they arrive
         * pointed the first added line at the line AFTER the block it replaced.
         * Where the counts differ the shorter side's last line absorbs the
         * remainder, which keeps both correspondence maps non-decreasing - a
         * map that went backwards would make the follower pane jump up while
         * the leader scrolls down.
         */
        fun flush() {
            if (pendingRemoved.isEmpty() && pendingAdded.isEmpty()) return
            val oldBase = oldLines.size
            val newBase = newLines.size
            val paired = minOf(pendingRemoved.size, pendingAdded.size)

            for (i in pendingRemoved.indices) {
                oldLines.add(pendingRemoved[i].text)
                oldMarks.add(DiffLineKind.REMOVED)
                oldNumbers.add(pendingRemoved[i].oldLine)
                oldToNew.add(newBase + if (pendingAdded.isEmpty()) 0 else minOf(i, pendingAdded.size - 1))
            }
            for (j in pendingAdded.indices) {
                newLines.add(pendingAdded[j].text)
                newMarks.add(DiffLineKind.ADDED)
                newNumbers.add(pendingAdded[j].newLine)
                newToOld.add(oldBase + if (pendingRemoved.isEmpty()) 0 else minOf(j, pendingRemoved.size - 1))
                // A block that also removed lines is a replacement: on the
                // unpadded axis it has no row of its own for the deletion, so
                // every row of it carries both colours.
                overview.add(if (pendingRemoved.isEmpty()) OverviewMark.ADDED else OverviewMark.BOTH)
            }
            if (pendingRemoved.isNotEmpty() && pendingAdded.isEmpty()) {
                deletedOnlyAtNewLine.add(newBase)
            }

            // Only a PAIRED row has a counterpart to diff against; a pure
            // insertion or deletion is entirely new or entirely gone, and
            // marking every character of it adds nothing.
            val pairedRanges = (0 until paired).map {
                InlineDiff.of(pendingRemoved[it].text, pendingAdded[it].text)
            }
            for (k in 0 until paired) {
                if (pairedRanges[k].old.isNotEmpty()) oldInline[oldBase + k] = pairedRanges[k].old
                if (pairedRanges[k].new.isNotEmpty()) newInline[newBase + k] = pairedRanges[k].new
            }

            // Padded: pairs share a row, then the longer side runs on against
            // filler - deletions first, as every diff viewer shows them.
            val padBase = padOld.size
            for (k in 0 until paired) {
                padOld.add(pendingRemoved[k].text)
                padOldMarks.add(DiffLineKind.REMOVED)
                padOldNumbers.add(pendingRemoved[k].oldLine)
                padNew.add(pendingAdded[k].text)
                padNewMarks.add(DiffLineKind.ADDED)
                padNewNumbers.add(pendingAdded[k].newLine)
                if (pairedRanges[k].old.isNotEmpty()) padOldInline[padBase + k] = pairedRanges[k].old
                if (pairedRanges[k].new.isNotEmpty()) padNewInline[padBase + k] = pairedRanges[k].new
                padOverview.add(OverviewMark.BOTH)
            }
            for (i in paired until pendingRemoved.size) {
                padNewFiller.add(padNew.size)
                padOld.add(pendingRemoved[i].text)
                padOldMarks.add(DiffLineKind.REMOVED)
                padOldNumbers.add(pendingRemoved[i].oldLine)
                padNew.add("")
                padNewMarks.add(null)
                padNewNumbers.add(null)
                padOverview.add(OverviewMark.REMOVED)
            }
            for (j in paired until pendingAdded.size) {
                padOldFiller.add(padOld.size)
                padOld.add("")
                padOldMarks.add(null)
                padOldNumbers.add(null)
                padNew.add(pendingAdded[j].text)
                padNewMarks.add(DiffLineKind.ADDED)
                padNewNumbers.add(pendingAdded[j].newLine)
                padOverview.add(OverviewMark.ADDED)
            }

            pendingRemoved.clear()
            pendingAdded.clear()
        }

        for (hunk in diff.hunks) {
            for (line in hunk.lines) {
                when (line.kind) {
                    DiffLineKind.REMOVED -> pendingRemoved.add(line)
                    DiffLineKind.ADDED -> pendingAdded.add(line)
                    else -> {
                        // CONTEXT, and any kind a newer host adds: the same
                        // line on both sides. DiffLineKind is an open set
                        // across the plugin boundary, so this must not be an
                        // exhaustive `when`.
                        flush()
                        oldToNew.add(newLines.size)
                        newToOld.add(oldLines.size)
                        oldLines.add(line.text)
                        newLines.add(line.text)
                        oldMarks.add(null)
                        newMarks.add(null)
                        oldNumbers.add(line.oldLine)
                        newNumbers.add(line.newLine)
                        overview.add(null)
                        padOld.add(line.text)
                        padNew.add(line.text)
                        padOldMarks.add(null)
                        padNewMarks.add(null)
                        padOldNumbers.add(line.oldLine)
                        padNewNumbers.add(line.newLine)
                        padOverview.add(null)
                    }
                }
            }
            flush()
        }
        flush()

        // A pure deletion at the very end of the file has no following row on
        // the unpadded new side; clamp it onto the last one so it still appears
        // rather than vanishing. (The padded form needs none of this: there,
        // every deletion has a row.)
        for (newLine in deletedOnlyAtNewLine) {
            val slot = newLine.coerceAtMost(overview.size - 1)
            if (slot < 0) continue
            overview[slot] = when (overview[slot]) {
                null -> OverviewMark.REMOVED
                OverviewMark.ADDED -> OverviewMark.BOTH
                else -> overview[slot]
            }
        }

        return Sides(
            old = Pane(oldLines.joinToString("\n"), oldMarks, oldInline, oldNumbers, emptySet()),
            new = Pane(newLines.joinToString("\n"), newMarks, newInline, newNumbers, emptySet()),
            oldToNew = oldToNew.toIntArray(),
            newToOld = newToOld.toIntArray(),
            overviewMarks = overview,
            alignedOld = Pane(padOld.joinToString("\n"), padOldMarks, padOldInline, padOldNumbers, padOldFiller),
            alignedNew = Pane(padNew.joinToString("\n"), padNewMarks, padNewInline, padNewNumbers, padNewFiller),
            alignedOverview = padOverview,
        )
    }
}

/**
 * The same diff as ONE document: git's own line order, with the `+`/`-`
 * markers stripped so the text is still lexable source.
 *
 * A unified view is a single editor, not a side-by-side with one pane hidden -
 * that is what makes it usable in a narrow pane, and it is how every diff
 * viewer worth copying does it.
 */
internal object DiffUnified {

    data class Unified(
        val text: String,
        /** Per rendered line; null means unchanged context. */
        val marks: List<DiffLineKind?>,
        /** Row -> the character ranges that differ from the row it replaced. */
        val inline: Map<Int, List<IntRange>>,
        /** The file line each row came from: the new side's, or the old for a removal. */
        val lineNumbers: List<Int?>,
    )

    fun of(diff: GitDiffData): Unified {
        val lines = mutableListOf<String>()
        val marks = mutableListOf<DiffLineKind?>()
        val numbers = mutableListOf<Int?>()
        val inline = mutableMapOf<Int, List<IntRange>>()

        val pendingRemoved = mutableListOf<DiffLine>()
        val pendingAdded = mutableListOf<DiffLine>()

        fun flush() {
            if (pendingRemoved.isEmpty() && pendingAdded.isEmpty()) return
            val removedBase = lines.size
            for (line in pendingRemoved) {
                lines.add(line.text)
                marks.add(DiffLineKind.REMOVED)
                numbers.add(line.oldLine)
            }
            val addedBase = lines.size
            for (line in pendingAdded) {
                lines.add(line.text)
                marks.add(DiffLineKind.ADDED)
                numbers.add(line.newLine)
            }
            // Same pairing as the side-by-side view, so the two modes never
            // disagree about which characters changed.
            for (k in 0 until minOf(pendingRemoved.size, pendingAdded.size)) {
                val ranges = InlineDiff.of(pendingRemoved[k].text, pendingAdded[k].text)
                if (ranges.old.isNotEmpty()) inline[removedBase + k] = ranges.old
                if (ranges.new.isNotEmpty()) inline[addedBase + k] = ranges.new
            }
            pendingRemoved.clear()
            pendingAdded.clear()
        }

        for (hunk in diff.hunks) {
            for (line in hunk.lines) {
                when (line.kind) {
                    DiffLineKind.REMOVED -> pendingRemoved.add(line)
                    DiffLineKind.ADDED -> pendingAdded.add(line)
                    else -> {
                        flush()
                        lines.add(line.text)
                        marks.add(null)
                        numbers.add(line.newLine ?: line.oldLine)
                    }
                }
            }
            flush()
        }
        flush()

        return Unified(lines.joinToString("\n"), marks, inline, numbers)
    }
}
