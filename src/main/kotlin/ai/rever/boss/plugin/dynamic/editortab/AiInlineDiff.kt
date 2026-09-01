package ai.rever.boss.plugin.dynamic.editortab

/**
 * A line-level diff of the inline-edit proposal, for the review step.
 *
 * Cursor shows the model's rewrite as an inline red/green diff over the
 * selection rather than as a wall of replacement text, so accepting is a
 * judgement about specific lines. This computes that diff from the two
 * strings the session already holds - no library dependency, and pure, so the
 * shape can be pinned by tests.
 */
internal object AiInlineDiff {

    enum class Kind { CONTEXT, REMOVED, ADDED }

    data class Line(val kind: Kind, val text: String)

    /** Cap on the lines a review card renders; longer proposals are elided. */
    const val MAX_LINES = 400

    /**
     * Diff [before] against [after], line by line.
     *
     * Common head and tail are matched first (an inline edit usually keeps the
     * outer lines), then the divergent middle is aligned with an LCS. That
     * keeps the interesting change tight instead of reporting the whole
     * selection as replaced.
     */
    fun of(before: String, after: String): List<Line> {
        val a = splitLines(before)
        val b = splitLines(after)

        var head = 0
        while (head < a.size && head < b.size && a[head] == b[head]) head++

        var tail = 0
        while (tail < a.size - head && tail < b.size - head &&
            a[a.size - 1 - tail] == b[b.size - 1 - tail]
        ) {
            tail++
        }

        val out = mutableListOf<Line>()
        for (i in 0 until head) out.add(Line(Kind.CONTEXT, a[i]))

        val midA = a.subList(head, a.size - tail)
        val midB = b.subList(head, b.size - tail)
        out.addAll(alignMiddle(midA, midB))

        for (i in a.size - tail until a.size) out.add(Line(Kind.CONTEXT, a[i]))
        return if (out.size <= MAX_LINES) out else out.take(MAX_LINES)
    }

    /**
     * Lines, minus a single trailing blank.
     *
     * `"a\nb\n".lines()` ends in an empty string, so a rewrite that only
     * changes the trailing newline would render as a changed line - and an
     * empty replacement would show one spurious added blank instead of
     * reading as "everything removed".
     */
    private fun splitLines(text: String): List<String> {
        // Empty text is zero lines, not one blank one: an empty replacement
        // has to read as "everything removed".
        if (text.isEmpty()) return emptyList()
        val lines = text.lines()
        return if (lines.size > 1 && lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    /**
     * LCS alignment of the divergent middle. Guarded by [LCS_CELL_BUDGET]: the
     * table is O(n*m), and a large selection against a large rewrite would
     * otherwise allocate millions of cells on the UI's own coroutine. Past the
     * budget it degrades to "all removed, then all added", which is what a
     * whole-block rewrite looks like anyway.
     */
    private fun alignMiddle(a: List<String>, b: List<String>): List<Line> {
        if (a.isEmpty() && b.isEmpty()) return emptyList()
        if (a.isEmpty()) return b.map { Line(Kind.ADDED, it) }
        if (b.isEmpty()) return a.map { Line(Kind.REMOVED, it) }
        if (a.size.toLong() * b.size.toLong() > LCS_CELL_BUDGET) {
            return a.map { Line(Kind.REMOVED, it) } + b.map { Line(Kind.ADDED, it) }
        }

        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                lcs[i][j] =
                    if (a[i] == b[j]) lcs[i + 1][j + 1] + 1
                    else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }

        val out = mutableListOf<Line>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> {
                    out.add(Line(Kind.CONTEXT, a[i])); i++; j++
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> {
                    out.add(Line(Kind.REMOVED, a[i])); i++
                }
                else -> {
                    out.add(Line(Kind.ADDED, b[j])); j++
                }
            }
        }
        while (i < a.size) out.add(Line(Kind.REMOVED, a[i++]))
        while (j < b.size) out.add(Line(Kind.ADDED, b[j++]))
        return out
    }

    /** Counts for the review header ("+3 −1"). */
    fun stats(lines: List<Line>): Pair<Int, Int> =
        lines.count { it.kind == Kind.ADDED } to lines.count { it.kind == Kind.REMOVED }

    private const val LCS_CELL_BUDGET = 250_000L
}
