package ai.rever.boss.plugin.dynamic.editortab

/**
 * Offset math for the platform text-editing shortcuts the bundled editor does
 * not implement: delete-to-line-start (⌘⌫), delete-to-line-end, delete by word
 * (⌥⌫ / ⌥⌦), and the matching caret moves.
 *
 * Pure, and its own file, because every one of these is an edit range: an
 * off-by-one deletes a character the user meant to keep, and that is not
 * something to discover by hand-testing a key at a time.
 *
 * Offsets are absolute indices into the document text; every result is
 * clamped to it, so a caret at either end never produces a range outside the
 * document.
 */
internal object MacEditingShortcuts {

    /** Start of the line containing [offset] - just after the previous newline. */
    fun lineStart(text: String, offset: Int): Int {
        val at = offset.coerceIn(0, text.length)
        val prev = text.lastIndexOf('\n', (at - 1).coerceAtLeast(0))
        return when {
            at == 0 -> 0
            prev < 0 -> 0
            prev >= at -> 0
            else -> prev + 1
        }
    }

    /** End of the line containing [offset] - the next newline, or the end. */
    fun lineEnd(text: String, offset: Int): Int {
        val at = offset.coerceIn(0, text.length)
        val next = text.indexOf('\n', at)
        return if (next < 0) text.length else next
    }

    /**
     * Start of the word before [offset], the way ⌥⌫ works: skip any run of
     * separators immediately behind the caret, then the word itself - so a
     * caret after `"hello "` takes the space and `hello` together.
     *
     * The scan stops at the line start, so one press never eats a word on the
     * line above. With the caret already there it falls back to one character,
     * i.e. the newline, which is what both macOS and plain Backspace do.
     */
    fun previousWordStart(text: String, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        val floor = lineStart(text, i)
        if (i <= floor) return (i - 1).coerceAtLeast(0)
        while (i > floor && !isWordChar(text[i - 1])) i--
        while (i > floor && isWordChar(text[i - 1])) i--
        return i
    }

    /** End of the word after [offset]; the mirror of [previousWordStart]. */
    fun nextWordEnd(text: String, offset: Int): Int {
        var i = offset.coerceIn(0, text.length)
        val ceiling = lineEnd(text, i)
        if (i >= ceiling) return (i + 1).coerceAtMost(text.length)
        while (i < ceiling && !isWordChar(text[i])) i++
        while (i < ceiling && isWordChar(text[i])) i++
        return i
    }

    /**
     * The range ⌘⌫ removes: line start to caret.
     *
     * Empty when the caret is already at the line start - the caller must not
     * issue a no-op edit, which would still push an undo step.
     */
    fun deleteToLineStart(text: String, offset: Int): IntRange? =
        rangeOrNull(lineStart(text, offset), offset.coerceIn(0, text.length))

    /** The range ⌘⌦ removes: caret to line end. */
    fun deleteToLineEnd(text: String, offset: Int): IntRange? =
        rangeOrNull(offset.coerceIn(0, text.length), lineEnd(text, offset))

    /** The range ⌥⌫ removes. */
    fun deletePreviousWord(text: String, offset: Int): IntRange? =
        rangeOrNull(previousWordStart(text, offset), offset.coerceIn(0, text.length))

    /** The range ⌥⌦ removes. */
    fun deleteNextWord(text: String, offset: Int): IntRange? =
        rangeOrNull(offset.coerceIn(0, text.length), nextWordEnd(text, offset))

    private fun rangeOrNull(start: Int, end: Int): IntRange? =
        if (end > start) start until end else null

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
}
