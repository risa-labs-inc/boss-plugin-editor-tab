package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.DiffHunk
import ai.rever.boss.plugin.api.DiffLine
import ai.rever.boss.plugin.api.DiffLineKind
import ai.rever.boss.plugin.api.GitDiffData
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rebuilding both file versions from a unified diff.
 *
 * The stakes here are higher than they look. The NEW side is handed to an
 * EDITABLE editor as the working-tree file, and [DiffSides.Sides.newMarks]
 * indexes lines of that file. If the reconstruction gains or loses a line, the
 * change marking points at the wrong code and - worse - the editability check
 * that compares this text against the file on disk is the only thing standing
 * between a diff tab and a corrupted source file.
 *
 * The sides are deliberately NOT padded to equal length: a padded document is
 * not the file, and writing it back would insert blank lines into the user's
 * source. Alignment is carried by the correspondence maps instead.
 */
class DiffSidesTest {

    private fun diff(vararg lines: Pair<DiffLineKind, String>): GitDiffData {
        val parsed = lines.map { (kind, text) -> DiffLine(text, kind, null, null) }
        return GitDiffData(
            path = "a.kt",
            additions = parsed.count { it.kind == DiffLineKind.ADDED },
            deletions = parsed.count { it.kind == DiffLineKind.REMOVED },
            hunks = listOf(DiffHunk(1, parsed.size, 1, parsed.size, "@@", parsed)),
        )
    }

    // ── reconstruction ────────────────────────────────────────────────────

    @Test
    fun `context appears on both sides`() {
        val s = DiffSides.of(diff(DiffLineKind.CONTEXT to "keep"))
        assertEquals("keep", s.oldText)
        assertEquals("keep", s.newText)
        assertEquals(listOf<DiffLineKind?>(null), s.oldMarks)
    }

    @Test
    fun `neither side gains a padding line`() {
        val s = DiffSides.of(
            diff(
                DiffLineKind.CONTEXT to "a",
                DiffLineKind.REMOVED to "old",
                DiffLineKind.ADDED to "new",
                DiffLineKind.CONTEXT to "b",
            ),
        )
        // The old side is exactly the pre-image and the new side exactly the
        // post-image - no blanks. This is what makes the new side writable.
        assertEquals(listOf("a", "old", "b"), s.oldText.lines())
        assertEquals(listOf("a", "new", "b"), s.newText.lines())
    }

    @Test
    fun `a pure deletion shortens the new side rather than padding it`() {
        val s = DiffSides.of(diff(DiffLineKind.REMOVED to "gone", DiffLineKind.CONTEXT to "keep"))
        assertEquals(listOf("gone", "keep"), s.oldText.lines())
        assertEquals(listOf("keep"), s.newText.lines())
    }

    @Test
    fun `a pure insertion lengthens the new side only`() {
        val s = DiffSides.of(diff(DiffLineKind.CONTEXT to "keep", DiffLineKind.ADDED to "fresh"))
        assertEquals(listOf("keep"), s.oldText.lines())
        assertEquals(listOf("keep", "fresh"), s.newText.lines())
    }

    @Test
    fun `marks index the reconstructed lines one for one`() {
        val s = DiffSides.of(
            diff(
                DiffLineKind.CONTEXT to "a",
                DiffLineKind.REMOVED to "x",
                DiffLineKind.REMOVED to "y",
                DiffLineKind.ADDED to "z",
                DiffLineKind.CONTEXT to "b",
            ),
        )
        assertEquals(s.oldText.lines().size, s.oldMarks.size)
        assertEquals(s.newText.lines().size, s.newMarks.size)
        assertEquals(
            listOf(null, DiffLineKind.REMOVED, DiffLineKind.REMOVED, null),
            s.oldMarks,
        )
        assertEquals(listOf(null, DiffLineKind.ADDED, null), s.newMarks)
    }

    @Test
    fun `an unknown line kind is treated as context on both sides`() {
        // DiffLineKind is an open set across the plugin boundary; a kind this
        // build does not know must not silently vanish from one side.
        val s = DiffSides.of(diff(DiffLineKind.CONTEXT to "a"))
        assertEquals(s.oldText.lines().size, s.newText.lines().size)
    }

    @Test
    fun `an empty diff yields empty sides`() {
        val s = DiffSides.of(GitDiffData(path = "a.kt", additions = 0, deletions = 0))
        assertTrue(s.oldMarks.isEmpty())
        assertTrue(s.newMarks.isEmpty())
        assertTrue(s.overviewMarks.isEmpty())
    }

    // ── correspondence maps (the scroll lock) ─────────────────────────────

    @Test
    fun `context lines map to themselves`() {
        val s = DiffSides.of(
            diff(DiffLineKind.CONTEXT to "a", DiffLineKind.CONTEXT to "b"),
        )
        assertContentEquals(intArrayOf(0, 1), s.oldToNew)
        assertContentEquals(intArrayOf(0, 1), s.newToOld)
    }

    @Test
    fun `an insertion shifts every later old line to its new position`() {
        val s = DiffSides.of(
            diff(
                DiffLineKind.CONTEXT to "a",
                DiffLineKind.ADDED to "ins",
                DiffLineKind.CONTEXT to "b",
            ),
        )
        // old: a, b     new: a, ins, b
        assertContentEquals(intArrayOf(0, 2), s.oldToNew)
        // The inserted line points at the old line that follows it, the same
        // convention a deletion uses in the other direction.
        assertContentEquals(intArrayOf(0, 1, 1), s.newToOld)
    }

    @Test
    fun `a deletion shifts every later new line to its old position`() {
        val s = DiffSides.of(
            diff(
                DiffLineKind.CONTEXT to "a",
                DiffLineKind.REMOVED to "del",
                DiffLineKind.CONTEXT to "b",
            ),
        )
        // old: a, del, b    new: a, b
        assertContentEquals(intArrayOf(0, 1, 1), s.oldToNew)
        assertContentEquals(intArrayOf(0, 2), s.newToOld)
    }

    @Test
    fun `a replacement puts both sides on the same row`() {
        val s = DiffSides.of(
            diff(
                DiffLineKind.CONTEXT to "a",
                DiffLineKind.REMOVED to "old",
                DiffLineKind.ADDED to "new",
                DiffLineKind.CONTEXT to "b",
            ),
        )
        assertEquals(1, s.oldToNew[1], "the removed line points at the line that replaced it")
        assertEquals(1, s.newToOld[1], "and back again")
        assertEquals(2, s.oldToNew[2])
        assertEquals(2, s.newToOld[2])
    }

    @Test
    fun `the maps cover every line of their own side`() {
        val s = DiffSides.of(
            diff(
                DiffLineKind.REMOVED to "x",
                DiffLineKind.REMOVED to "y",
                DiffLineKind.ADDED to "z",
                DiffLineKind.CONTEXT to "c",
            ),
        )
        assertEquals(s.oldMarks.size, s.oldToNew.size)
        assertEquals(s.newMarks.size, s.newToOld.size)
    }

    @Test
    fun `the maps never go backwards`() {
        // A non-monotonic map would make the follower pane jump backwards while
        // the leader scrolls forwards.
        val s = DiffSides.of(
            diff(
                DiffLineKind.CONTEXT to "a",
                DiffLineKind.REMOVED to "r1",
                DiffLineKind.REMOVED to "r2",
                DiffLineKind.ADDED to "a1",
                DiffLineKind.CONTEXT to "b",
                DiffLineKind.ADDED to "a2",
                DiffLineKind.CONTEXT to "c",
            ),
        )
        for (i in 1 until s.oldToNew.size) {
            assertTrue(s.oldToNew[i] >= s.oldToNew[i - 1], "oldToNew went backwards at $i")
        }
        for (i in 1 until s.newToOld.size) {
            assertTrue(s.newToOld[i] >= s.newToOld[i - 1], "newToOld went backwards at $i")
        }
    }

    @Test
    fun `a map entry is always a line the other side actually has`() {
        val s = DiffSides.of(
            diff(
                DiffLineKind.CONTEXT to "a",
                DiffLineKind.REMOVED to "r",
                DiffLineKind.ADDED to "x",
                DiffLineKind.ADDED to "y",
                DiffLineKind.CONTEXT to "b",
            ),
        )
        for (target in s.oldToNew) {
            assertTrue(target in 0..s.newMarks.size, "oldToNew pointed outside the new side: $target")
        }
        for (target in s.newToOld) {
            assertTrue(target in 0..s.oldMarks.size, "newToOld pointed outside the old side: $target")
        }
    }

    // ── the minimap overview axis ─────────────────────────────────────────

    @Test
    fun `the overview is on the new side's axis and carries both kinds`() {
        val s = DiffSides.of(
            diff(
                DiffLineKind.REMOVED to "gone",
                DiffLineKind.CONTEXT to "keep",
                DiffLineKind.ADDED to "fresh",
            ),
        )
        // new side: keep, fresh
        assertEquals(s.newMarks.size, s.overviewMarks.size)
        assertEquals(
            DiffSides.OverviewMark.REMOVED,
            s.overviewMarks[0],
            "the deletion lands where it was removed from",
        )
        assertEquals(DiffSides.OverviewMark.ADDED, s.overviewMarks[1])
    }

    @Test
    fun `a replacement carries BOTH, so the red survives`() {
        // The regression this fixes: the overview kept only one mark per row,
        // so on a replacement the addition won and the deletion vanished -
        // a file whose only change was a replacement showed no red at all.
        val s = DiffSides.of(diff(DiffLineKind.REMOVED to "old", DiffLineKind.ADDED to "new"))
        assertEquals(listOf(DiffSides.OverviewMark.BOTH), s.overviewMarks)
    }

    @Test
    fun `every row of an uneven replacement carries BOTH`() {
        // 4 removed, 2 added: there is no spare new-side row to hang the two
        // extra deletions on, so the whole block reads as "changed".
        val s = DiffSides.of(
            diff(
                DiffLineKind.CONTEXT to "a",
                DiffLineKind.REMOVED to "r1",
                DiffLineKind.REMOVED to "r2",
                DiffLineKind.REMOVED to "r3",
                DiffLineKind.REMOVED to "r4",
                DiffLineKind.ADDED to "n1",
                DiffLineKind.ADDED to "n2",
                DiffLineKind.CONTEXT to "b",
            ),
        )
        assertEquals(
            listOf(null, DiffSides.OverviewMark.BOTH, DiffSides.OverviewMark.BOTH, null),
            s.overviewMarks,
        )
    }

    @Test
    fun `a pure insertion stays green and a pure deletion stays red`() {
        val insertion = DiffSides.of(
            diff(DiffLineKind.CONTEXT to "a", DiffLineKind.ADDED to "new"),
        )
        assertEquals(listOf(null, DiffSides.OverviewMark.ADDED), insertion.overviewMarks)

        val deletion = DiffSides.of(
            diff(DiffLineKind.REMOVED to "gone", DiffLineKind.CONTEXT to "b"),
        )
        assertEquals(listOf(DiffSides.OverviewMark.REMOVED), deletion.overviewMarks)
    }

    @Test
    fun `the overview never indexes past the document the minimap is scaled to`() {
        // The minimap is scaled to the NEW side's line count; a mark beyond it
        // would be drawn at a y that has no code under it.
        val s = DiffSides.of(
            diff(
                DiffLineKind.CONTEXT to "a",
                DiffLineKind.REMOVED to "r1",
                DiffLineKind.REMOVED to "r2",
                DiffLineKind.ADDED to "n1",
                DiffLineKind.CONTEXT to "b",
            ),
        )
        assertEquals(s.newMarks.size, s.overviewMarks.size)
    }

    @Test
    fun `a deletion at the very end of the file still appears`() {
        val s = DiffSides.of(diff(DiffLineKind.CONTEXT to "a", DiffLineKind.REMOVED to "tail"))
        // The new side has only "a"; the deletion has no row of its own, so it
        // clamps onto the last one rather than being dropped.
        assertEquals(1, s.overviewMarks.size)
        assertEquals(DiffSides.OverviewMark.REMOVED, s.overviewMarks[0])
    }

    // ── intra-line ranges ─────────────────────────────────────────────────

    @Test
    fun `a replaced line carries the characters that changed`() {
        val s = DiffSides.of(
            diff(
                DiffLineKind.REMOVED to "val x = 1",
                DiffLineKind.ADDED to "val x = 2",
            ),
        )
        assertEquals(listOf(8 until 9), s.newInline[0])
        assertEquals(listOf(8 until 9), s.oldInline[0])
    }

    @Test
    fun `an unpaired line has no intra-line ranges`() {
        // A pure insertion is entirely new; marking every character adds
        // nothing over the line band already under it.
        val s = DiffSides.of(diff(DiffLineKind.CONTEXT to "a", DiffLineKind.ADDED to "brand new"))
        assertTrue(s.newInline.isEmpty())
    }

    @Test
    fun `intra-line ranges are keyed by the row they belong to`() {
        val s = DiffSides.of(
            diff(
                DiffLineKind.CONTEXT to "a",
                DiffLineKind.CONTEXT to "b",
                DiffLineKind.REMOVED to "one two",
                DiffLineKind.ADDED to "one three",
            ),
        )
        assertTrue(s.newInline.containsKey(2), "the change is on row 2, after two context lines")
        assertTrue(s.newInline[0] == null && s.newInline[1] == null)
    }

    // ── the aligned (padded) form ─────────────────────────────────────────

    /** A diff whose lines carry git's real 1-based line numbers. */
    private fun numbered(vararg lines: Pair<DiffLineKind, String>): GitDiffData {
        var oldNo = 1
        var newNo = 1
        val parsed = lines.map { (kind, text) ->
            when (kind) {
                DiffLineKind.ADDED -> DiffLine(text, kind, null, newNo++)
                DiffLineKind.REMOVED -> DiffLine(text, kind, oldNo++, null)
                else -> DiffLine(text, kind, oldNo++, newNo++)
            }
        }
        return GitDiffData(
            path = "a.kt",
            additions = parsed.count { it.kind == DiffLineKind.ADDED },
            deletions = parsed.count { it.kind == DiffLineKind.REMOVED },
            hunks = listOf(DiffHunk(1, parsed.size, 1, parsed.size, "@@", parsed)),
        )
    }

    @Test
    fun `the aligned panes always have the same number of rows`() {
        // The whole point of the padded form, and what lets the scroll lock
        // mirror the raw pixel offset with no translation.
        val s = DiffSides.of(
            diff(
                DiffLineKind.CONTEXT to "a",
                DiffLineKind.REMOVED to "x",
                DiffLineKind.REMOVED to "y",
                DiffLineKind.ADDED to "z",
                DiffLineKind.CONTEXT to "b",
            ),
        )
        assertEquals(s.alignedOld.text.lines().size, s.alignedNew.text.lines().size)
        assertEquals(s.alignedOld.rowCount, s.alignedNew.rowCount)
        assertEquals(s.alignedOld.rowCount, s.alignedOld.text.lines().size)
        assertEquals(s.alignedOld.rowCount, s.alignedOld.lineNumbers.size)
    }

    @Test
    fun `a deletion is faced by filler, so matching code stays on the same row`() {
        val s = DiffSides.of(
            diff(
                DiffLineKind.CONTEXT to "a",
                DiffLineKind.REMOVED to "gone",
                DiffLineKind.CONTEXT to "b",
            ),
        )
        assertEquals(listOf("a", "gone", "b"), s.alignedOld.text.lines())
        assertEquals(listOf("a", "", "b"), s.alignedNew.text.lines())
        assertEquals(setOf(1), s.alignedNew.filler)
        // "b" faces "b".
        assertEquals(2, s.alignedOld.text.lines().indexOf("b"))
        assertEquals(2, s.alignedNew.text.lines().indexOf("b"))
    }

    @Test
    fun `an insertion is faced by filler on the old side`() {
        val s = DiffSides.of(diff(DiffLineKind.CONTEXT to "keep", DiffLineKind.ADDED to "fresh"))
        assertEquals(listOf("keep", ""), s.alignedOld.text.lines())
        assertEquals(listOf("keep", "fresh"), s.alignedNew.text.lines())
        assertEquals(setOf(1), s.alignedOld.filler)
    }

    @Test
    fun `uneven blocks pair what they can and fill the rest`() {
        val s = DiffSides.of(
            diff(
                DiffLineKind.REMOVED to "x",
                DiffLineKind.REMOVED to "y",
                DiffLineKind.ADDED to "z",
            ),
        )
        assertEquals(listOf("x", "y"), s.alignedOld.text.lines())
        assertEquals(listOf("z", ""), s.alignedNew.text.lines())
        assertEquals(setOf(1), s.alignedNew.filler)
    }

    @Test
    fun `filler rows are never real file content`() {
        val s = DiffSides.of(
            diff(
                DiffLineKind.REMOVED to "a",
                DiffLineKind.REMOVED to "b",
                DiffLineKind.ADDED to "c",
                DiffLineKind.CONTEXT to "d",
            ),
        )
        val rows = s.alignedNew.text.lines()
        for (row in s.alignedNew.filler) assertEquals("", rows[row])
    }

    @Test
    fun `the aligned pane is longer than the file whenever anything was deleted`() {
        // The reason it must never be written back to disk.
        val s = DiffSides.of(diff(DiffLineKind.REMOVED to "gone", DiffLineKind.CONTEXT to "keep"))
        assertTrue(
            s.alignedNew.text.lines().size > s.newText.lines().size,
            "the aligned document must be longer than the file it pads",
        )
    }

    // ── line numbers in the aligned form ──────────────────────────────────

    @Test
    fun `a filler row takes no line number`() {
        // The bug the whole custom gutter exists to avoid: a blank padding row
        // that carries a number shifts every number after it.
        val s = DiffSides.of(
            numbered(
                DiffLineKind.CONTEXT to "a",
                DiffLineKind.REMOVED to "gone",
                DiffLineKind.CONTEXT to "b",
            ),
        )
        assertEquals(listOf(1, null, 2), s.alignedNew.lineNumbers)
    }

    @Test
    fun `numbers after a filler row continue from the file, not from the row index`() {
        val s = DiffSides.of(
            numbered(
                DiffLineKind.CONTEXT to "a",
                DiffLineKind.REMOVED to "r1",
                DiffLineKind.REMOVED to "r2",
                DiffLineKind.CONTEXT to "b",
                DiffLineKind.CONTEXT to "c",
            ),
        )
        // new file is a, b, c -> 1, 2, 3, with two filler rows between.
        assertEquals(listOf(1, null, null, 2, 3), s.alignedNew.lineNumbers)
        // old file is a, r1, r2, b, c -> 1..5, no filler.
        assertEquals(listOf(1, 2, 3, 4, 5), s.alignedOld.lineNumbers)
        assertTrue(s.alignedOld.filler.isEmpty())
    }

    @Test
    fun `the two sides' numbers diverge after a change, as they should`() {
        val s = DiffSides.of(
            numbered(
                DiffLineKind.CONTEXT to "a",
                DiffLineKind.REMOVED to "r1",
                DiffLineKind.REMOVED to "r2",
                DiffLineKind.ADDED to "n1",
                DiffLineKind.CONTEXT to "b",
            ),
        )
        // rows: 0 context, 1 paired r1/n1, 2 deleted r2 vs filler, 3 context.
        assertEquals(listOf(1, 2, 3, 4), s.alignedOld.lineNumbers)
        assertEquals(listOf(1, 2, null, 3), s.alignedNew.lineNumbers)
        // The divergence the reference screenshot shows: old line 4 sits
        // opposite new line 3, and both are the file's real numbers.
        assertEquals(4, s.alignedOld.lineNumbers[3])
        assertEquals(3, s.alignedNew.lineNumbers[3])
    }

    @Test
    fun `the unpadded panes carry the file's numbers too`() {
        val s = DiffSides.of(
            numbered(
                DiffLineKind.CONTEXT to "a",
                DiffLineKind.ADDED to "new",
                DiffLineKind.CONTEXT to "b",
            ),
        )
        assertEquals(listOf(1, 2, 3), s.new.lineNumbers)
        assertEquals(listOf(1, 2), s.old.lineNumbers)
        assertTrue(s.new.lineNumbers.none { it == null })
    }

    // ── the aligned overview (the minimap in aligned mode) ────────────────

    @Test
    fun `the aligned overview is indexed on the padded axis`() {
        // The minimap is scaled to the document it sits on; an overview on a
        // different axis would put marks at the wrong y.
        val s = DiffSides.of(
            diff(
                DiffLineKind.CONTEXT to "a",
                DiffLineKind.REMOVED to "r1",
                DiffLineKind.REMOVED to "r2",
                DiffLineKind.ADDED to "n1",
                DiffLineKind.CONTEXT to "b",
            ),
        )
        assertEquals(s.alignedNew.rowCount, s.alignedOverview.size)
    }

    @Test
    fun `every deletion has its own overview row once padded`() {
        // Unlike the unpadded axis, the padded form HAS a row per deletion, so
        // the extra deletions of an uneven replacement show as red rather than
        // being folded into a BOTH.
        val s = DiffSides.of(
            diff(
                DiffLineKind.REMOVED to "r1",
                DiffLineKind.REMOVED to "r2",
                DiffLineKind.ADDED to "n1",
            ),
        )
        assertEquals(
            listOf(DiffSides.OverviewMark.BOTH, DiffSides.OverviewMark.REMOVED),
            s.alignedOverview,
        )
    }

    @Test
    fun `a pure insertion and a pure deletion keep their own colour`() {
        val insertion = DiffSides.of(diff(DiffLineKind.CONTEXT to "a", DiffLineKind.ADDED to "n"))
        assertEquals(listOf(null, DiffSides.OverviewMark.ADDED), insertion.alignedOverview)

        val deletion = DiffSides.of(diff(DiffLineKind.CONTEXT to "a", DiffLineKind.REMOVED to "r"))
        assertEquals(listOf(null, DiffSides.OverviewMark.REMOVED), deletion.alignedOverview)
    }
}
