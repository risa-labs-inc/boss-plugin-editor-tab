package ai.rever.boss.plugin.dynamic.editortab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The range math `editor_inline_edit` uses to build its selection.
 *
 * The original `lineLength` was off by one (1-based line tested against
 * `lines.indices`), so the whole-file default range became `1:1 - N:1` and
 * `rangeText` clamped the end to column 0 of the last line - dropping the
 * last line from the selection, and giving the model an EMPTY selection for
 * a single-line file.
 */
class McpRangeMathTest {

    private val lines = listOf("val a = 1", "val b = 2", "val c = 3")

    @Test
    fun `line length covers the whole line including the last one`() {
        assertEquals(10, lineLength(lines, 1)) // "val a = 1" is 9 chars + 1
        assertEquals(10, lineLength(lines, 3)) // the last line: the bug's case
    }

    @Test
    fun `whole file range keeps the last line`() {
        val text = rangeText(lines, 1, 1, lines.size, lineLength(lines, lines.size))
        assertEquals(lines.joinToString("\n"), text)
    }

    @Test
    fun `single line file range is the line, not empty`() {
        val one = listOf("only line")
        val text = rangeText(one, 1, 1, 1, lineLength(one, 1))
        assertEquals("only line", text)
    }

    @Test
    fun `a range ending on the last line without end col covers it`() {
        // The handler defaults a missing end_col on the last line to the line
        // end; the model must still receive that line.
        val text = rangeText(lines, 2, 1, 3, lineLength(lines, 3))
        assertEquals("val b = 2\nval c = 3", text)
    }

    @Test
    fun `partial line range trims at both ends`() {
        // end_col is the EXCLUSIVE end (editor semantics: see the tool's
        // schema and lineLength = length + 1), so 2..6 covers columns 2-5.
        assertEquals("al a", rangeText(lines, 1, 2, 1, 6))
        assertEquals(null, rangeText(lines, 0, 1, 1, 5))
        assertEquals(null, rangeText(lines, 1, 1, 4, 5))
    }
}