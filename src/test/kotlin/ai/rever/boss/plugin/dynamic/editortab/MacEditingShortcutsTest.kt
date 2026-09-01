package ai.rever.boss.plugin.dynamic.editortab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Ranges for the platform editing shortcuts. Each case is written as the
 * resulting text, since that is what a wrong boundary actually costs.
 */
class MacEditingShortcutsTest {

    private fun apply(text: String, caret: Int, op: (String, Int) -> IntRange?): String {
        val range = op(text, caret) ?: return text
        return text.removeRange(range)
    }

    // ---- delete to line start (Cmd+Backspace) -----------------------------

    @Test
    fun `deletes from the line start up to the caret`() {
        assertEquals("world", apply("hello world", 6, MacEditingShortcuts::deleteToLineStart))
    }

    @Test
    fun `only touches the caret's own line`() {
        assertEquals("one\nthree", apply("one\ntwo three", 8, MacEditingShortcuts::deleteToLineStart))
    }

    @Test
    fun `at the line start it is a no-op, not a newline delete`() {
        // Returning an empty range matters: an edit here would join the lines
        // and still cost an undo step.
        assertNull(MacEditingShortcuts.deleteToLineStart("one\ntwo", 4))
        assertNull(MacEditingShortcuts.deleteToLineStart("abc", 0))
    }

    @Test
    fun `at the end of a single line it clears the line`() {
        assertEquals("", apply("hello", 5, MacEditingShortcuts::deleteToLineStart))
    }

    // ---- delete to line end -----------------------------------------------

    @Test
    fun `deletes from the caret to the end of the line only`() {
        assertEquals("one\ntwo", apply("one two\ntwo", 3, MacEditingShortcuts::deleteToLineEnd))
        assertNull(MacEditingShortcuts.deleteToLineEnd("one\ntwo", 3), "already at the line end")
    }

    // ---- delete word (Option+Backspace / Option+Delete) --------------------

    @Test
    fun `deletes the word behind the caret`() {
        assertEquals("hello ", apply("hello world", 11, MacEditingShortcuts::deletePreviousWord))
    }

    @Test
    fun `separators behind the caret go with the word`() {
        // macOS deletes the run of spaces AND the word before them, so a caret
        // just after "hello " leaves "world".
        assertEquals("world", apply("hello world", 6, MacEditingShortcuts::deletePreviousWord))
    }

    @Test
    fun `word delete never runs past the line it started on`() {
        // From the end of "two" it takes that word and stops at the newline,
        // rather than continuing into the line above.
        assertEquals("one\n", apply("one\ntwo", 7, MacEditingShortcuts::deletePreviousWord))
    }

    @Test
    fun `at a line start it falls back to deleting the newline`() {
        // What plain Backspace would do, and what macOS does here: join the
        // lines rather than doing nothing.
        assertEquals("onetwo", apply("one\ntwo", 4, MacEditingShortcuts::deletePreviousWord))
    }

    @Test
    fun `deletes the word ahead of the caret`() {
        assertEquals(" world", apply("hello world", 0, MacEditingShortcuts::deleteNextWord))
    }

    @Test
    fun `treats underscores and digits as part of a word`() {
        assertEquals("", apply("my_var2", 7, MacEditingShortcuts::deletePreviousWord))
    }

    // ---- caret targets -----------------------------------------------------

    @Test
    fun `line start and end are found from anywhere on the line`() {
        val text = "alpha\nbeta gamma\ndelta"
        assertEquals(6, MacEditingShortcuts.lineStart(text, 10))
        assertEquals(16, MacEditingShortcuts.lineEnd(text, 10))
        assertEquals(0, MacEditingShortcuts.lineStart(text, 0))
        assertEquals(text.length, MacEditingShortcuts.lineEnd(text, text.length))
    }

    @Test
    fun `offsets outside the document are clamped`() {
        assertEquals(0, MacEditingShortcuts.lineStart("abc", -5))
        assertEquals(3, MacEditingShortcuts.lineEnd("abc", 99))
        assertNull(MacEditingShortcuts.deleteToLineEnd("abc", 99))
    }

    @Test
    fun `an empty document produces no ranges`() {
        assertNull(MacEditingShortcuts.deleteToLineStart("", 0))
        assertNull(MacEditingShortcuts.deleteToLineEnd("", 0))
        assertNull(MacEditingShortcuts.deletePreviousWord("", 0))
        assertNull(MacEditingShortcuts.deleteNextWord("", 0))
    }
}
