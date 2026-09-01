package ai.rever.boss.plugin.dynamic.editortab

/**
 * Which characters actually changed within a replaced line.
 *
 * A flat band over the whole line says "something here is different"; it does
 * not say what. VS Code tints the line faintly and the changed TOKENS strongly,
 * which is what makes a one-token edit readable at a glance - and the
 * difference between the two is entirely this function.
 *
 * Word level rather than character level on purpose: a character diff of
 * `getUserName` against `getUserId` marks a ragged `N`/`I`, `a`/`d` scatter,
 * while a word diff marks the one identifier that changed. Runs of word
 * characters and runs of everything else are the tokens; an LCS over those
 * gives the ranges.
 *
 * Pure, and tested, because character offsets painted at the wrong column look
 * like a rendering glitch rather than a wrong answer.
 */
internal object InlineDiff {

    data class Ranges(
        /** Character ranges (end-exclusive) that only the old line has. */
        val old: List<IntRange>,
        val new: List<IntRange>,
    ) {
        val isEmpty: Boolean get() = old.isEmpty() && new.isEmpty()
    }

    val NONE = Ranges(emptyList(), emptyList())

    /**
     * Above this many tokens on a side the LCS table is not worth building -
     * a minified bundle on one line would allocate millions of cells for a
     * highlight nobody can read. Those lines fall back to the common
     * prefix/suffix span, which is O(n) and still useful.
     */
    const val MAX_TOKENS = 400

    fun of(old: String, new: String): Ranges {
        if (old == new) return NONE
        if (old.isEmpty()) return Ranges(emptyList(), listOf(0 until new.length))
        if (new.isEmpty()) return Ranges(listOf(0 until old.length), emptyList())

        val oldTokens = tokenize(old)
        val newTokens = tokenize(new)
        if (oldTokens.size > MAX_TOKENS || newTokens.size > MAX_TOKENS) {
            return affixSpan(old, new)
        }

        val keptOld = BooleanArray(oldTokens.size)
        val keptNew = BooleanArray(newTokens.size)
        markCommon(old, oldTokens, new, newTokens, keptOld, keptNew)

        return Ranges(
            old = changedRanges(old, oldTokens, keptOld),
            new = changedRanges(new, newTokens, keptNew),
        )
    }

    /** A token is a run of word characters, or a run of non-word characters. */
    internal fun tokenize(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = 0
        while (start < text.length) {
            val word = isWordChar(text[start])
            var end = start + 1
            while (end < text.length && isWordChar(text[end]) == word) end++
            out.add(start until end)
            start = end
        }
        return out
    }

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /**
     * Standard LCS backtrack, marking the tokens both sides keep.
     *
     * Tokens are compared by CONTENT, not by position - the whole point is to
     * recognise the token that survived the edit even though it moved.
     */
    private fun markCommon(
        oldText: String,
        oldTokens: List<IntRange>,
        newText: String,
        newTokens: List<IntRange>,
        keptOld: BooleanArray,
        keptNew: BooleanArray,
    ) {
        fun same(i: Int, j: Int): Boolean {
            val a = oldTokens[i]
            val b = newTokens[j]
            val length = a.last - a.first
            if (length != b.last - b.first) return false
            for (k in 0..length) {
                if (oldText[a.first + k] != newText[b.first + k]) return false
            }
            return true
        }

        val table = Array(oldTokens.size + 1) { IntArray(newTokens.size + 1) }
        for (i in oldTokens.indices.reversed()) {
            for (j in newTokens.indices.reversed()) {
                table[i][j] =
                    if (same(i, j)) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
            }
        }
        var i = 0
        var j = 0
        while (i < oldTokens.size && j < newTokens.size) {
            when {
                same(i, j) -> {
                    keptOld[i] = true
                    keptNew[j] = true
                    i++
                    j++
                }

                table[i + 1][j] >= table[i][j + 1] -> i++
                else -> j++
            }
        }
    }

    /** Merge the runs of dropped tokens into contiguous character ranges. */
    private fun changedRanges(
        text: String,
        tokens: List<IntRange>,
        kept: BooleanArray,
    ): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var start = -1
        var end = -1

        fun emit() {
            if (start < 0) return
            trimSurroundingSpace(text, start, end)?.let { out.add(it) }
            start = -1
        }

        for (index in tokens.indices) {
            if (kept[index]) {
                emit()
            } else {
                if (start < 0) start = tokens[index].first
                end = tokens[index].last + 1
            }
        }
        emit()
        return out
    }

    /**
     * Shrink a range off the whitespace at its edges.
     *
     * Non-word characters tokenize as one run, so changing `+` to `-` in
     * `a + b` yields the range `" + "` and would tint the spaces either side of
     * the operator - three columns of highlight for a one-character change. A
     * range that is ENTIRELY whitespace is kept as it is: an indentation change
     * is a real change and the whitespace is the whole of it.
     */
    private fun trimSurroundingSpace(text: String, start: Int, end: Int): IntRange? {
        if (end <= start) return null
        var first = start
        var last = end - 1
        while (first <= last && text[first].isWhitespace()) first++
        while (last >= first && text[last].isWhitespace()) last--
        if (first > last) return start until end
        return first..last
    }

    /** The span between the common prefix and the common suffix. */
    internal fun affixSpan(old: String, new: String): Ranges {
        var prefix = 0
        val maxPrefix = minOf(old.length, new.length)
        while (prefix < maxPrefix && old[prefix] == new[prefix]) prefix++
        var suffix = 0
        while (suffix < maxPrefix - prefix &&
            old[old.length - 1 - suffix] == new[new.length - 1 - suffix]
        ) {
            suffix++
        }
        val oldEnd = old.length - suffix
        val newEnd = new.length - suffix
        return Ranges(
            old = if (oldEnd > prefix) listOf(prefix until oldEnd) else emptyList(),
            new = if (newEnd > prefix) listOf(prefix until newEnd) else emptyList(),
        )
    }
}
