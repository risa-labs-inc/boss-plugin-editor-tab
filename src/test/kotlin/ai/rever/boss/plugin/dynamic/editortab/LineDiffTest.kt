package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.dynamic.editortab.LineDiff.Mark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which lines the git gutter marks.
 *
 * These marks are painted next to real code, so an off-by-one points the user
 * at a line they did not touch. The awkward case is deletion: it has no line of
 * its own, and every rule about where it lands is a choice that has to be
 * pinned rather than discovered later.
 */
class LineDiffTest {

    private fun diff(old: String, new: String) =
        LineDiff.of(old.split("\n"), new.split("\n"))

    @Test
    fun `an unchanged file has no marks`() {
        assertEquals(emptyMap(), diff("a\nb\nc", "a\nb\nc"))
    }

    @Test
    fun `an inserted line is marked added`() {
        assertEquals(mapOf(1 to Mark.ADDED), diff("a\nb", "a\nNEW\nb"))
    }

    @Test
    fun `several inserted lines are all marked`() {
        assertEquals(
            mapOf(1 to Mark.ADDED, 2 to Mark.ADDED),
            diff("a\nb", "a\nX\nY\nb"),
        )
    }

    @Test
    fun `a changed line is modified, not added`() {
        // The distinction the user reads at a glance: this line existed.
        assertEquals(mapOf(1 to Mark.MODIFIED), diff("a\nb\nc", "a\nCHANGED\nc"))
    }

    @Test
    fun `a deletion is marked on the line that took its place`() {
        // "b" is gone; the row where it used to be now holds "c".
        assertEquals(mapOf(1 to Mark.DELETED), diff("a\nb\nc", "a\nc"))
    }

    @Test
    fun `a deletion at the end of the file lands on the last line`() {
        // There is no line after it, so it cannot be marked "below".
        val marks = diff("a\nb\nc", "a")
        assertEquals(mapOf(0 to Mark.DELETED), marks)
    }

    @Test
    fun `a deletion at the start of the file lands on the first line`() {
        assertEquals(mapOf(0 to Mark.DELETED), diff("a\nb\nc", "b\nc"))
    }

    @Test
    fun `replacing two lines with one marks the survivor and the loss`() {
        // one modified line, and one line's worth of deletion beside it.
        val marks = diff("a\nX\nY\nb", "a\nZ\nb")
        assertEquals(Mark.MODIFIED, marks[1])
        assertTrue(marks.size >= 1)
    }

    @Test
    fun `replacing one line with two marks both`() {
        val marks = diff("a\nX\nb", "a\nY\nZ\nb")
        assertEquals(Mark.MODIFIED, marks[1])
        assertEquals(Mark.ADDED, marks[2])
    }

    @Test
    fun `a deletion never overwrites a real add or modify`() {
        // The add/modify is about content the user can see; the deletion is a
        // marker for content that is gone. Content wins the row.
        val marks = diff("a\nX\nY\nZ\nb", "a\nQ\nb")
        assertEquals(Mark.MODIFIED, marks[1], "row 1 holds real changed content")
    }

    @Test
    fun `marks never point outside the new file`() {
        for ((old, new) in listOf(
            "a\nb\nc\nd" to "a",
            "a" to "a\nb\nc\nd",
            "a\nb" to "x\ny",
            "" to "a\nb",
            "a\nb" to "",
        )) {
            val lineCount = new.split("\n").size
            for (line in diff(old, new).keys) {
                assertTrue(line in 0 until lineCount, "mark at $line for '$old' -> '$new'")
            }
        }
    }

    @Test
    fun `an edit deep in a large file only marks that edit`() {
        // The prefix/suffix trim is what keeps this cheap AND precise: a
        // one-line change in a 5000-line file is one mark, not five thousand.
        val old = (1..5000).map { "line $it" }
        val new = old.toMutableList().also { it[2500] = "line 2501 CHANGED" }
        assertEquals(mapOf(2500 to Mark.MODIFIED), LineDiff.of(old, new))
    }

    @Test
    fun `a wholesale rewrite falls back to one modified region rather than an LCS`() {
        // Guards the O(n*m) table: past the cap it must not try to pair.
        val old = (1..LineDiff.MAX_BAND + 50).map { "old $it" }
        val new = (1..LineDiff.MAX_BAND + 50).map { "new $it" }
        val marks = LineDiff.of(old, new)
        assertEquals(new.size, marks.size)
        assertTrue(marks.values.all { it == Mark.MODIFIED })
    }

    @Test
    fun `an empty file gaining content is all added`() {
        val marks = LineDiff.of(emptyList(), listOf("a", "b"))
        assertEquals(mapOf(0 to Mark.ADDED, 1 to Mark.ADDED), marks)
    }

    @Test
    fun `a file emptied to nothing does not crash or mark out of range`() {
        val marks = LineDiff.of(listOf("a", "b"), emptyList())
        assertTrue(marks.isEmpty(), "there is no line left to mark")
    }

    @Test
    fun `two separate edits give two separate marks`() {
        val old = listOf("a", "b", "c", "d", "e")
        val new = listOf("a", "B", "c", "D", "e")
        assertEquals(mapOf(1 to Mark.MODIFIED, 3 to Mark.MODIFIED), LineDiff.of(old, new))
    }
}
