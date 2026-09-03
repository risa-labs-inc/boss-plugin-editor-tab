package ai.rever.boss.plugin.dynamic.editortab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which characters changed within a replaced line.
 *
 * These ranges are painted at character offsets, so an off-by-one shows up as
 * a highlight sitting one column left of the token it means - which reads as a
 * rendering glitch rather than a wrong answer, and so would survive a long
 * time unreported.
 */
class InlineDiffTest {

    private fun old(o: String, n: String) = InlineDiff.of(o, n).old
    private fun new(o: String, n: String) = InlineDiff.of(o, n).new

    private fun slice(text: String, ranges: List<IntRange>): List<String> =
        ranges.map { text.substring(it.first, it.last + 1) }

    @Test
    fun `identical lines have nothing to mark`() {
        assertTrue(InlineDiff.of("same", "same").isEmpty)
    }

    @Test
    fun `one changed word marks only that word`() {
        val o = "val greeting = \"Hello\""
        val n = "val greeting = \"Hi\""
        assertEquals(listOf("Hello"), slice(o, old(o, n)))
        assertEquals(listOf("Hi"), slice(n, new(o, n)))
    }

    @Test
    fun `a renamed identifier is marked, not the punctuation around it`() {
        val o = "fun subtract(a: Int): Int"
        val n = "fun addt(a: Int): Int"
        assertEquals(listOf("subtract"), slice(o, old(o, n)))
        assertEquals(listOf("addt"), slice(n, new(o, n)))
    }

    @Test
    fun `an operator change is marked without the spaces around it`() {
        val o = "return a + b"
        val n = "return a - b"
        assertEquals(listOf("+"), slice(o, old(o, n)))
        assertEquals(listOf("-"), slice(n, new(o, n)))
    }

    @Test
    fun `two separate changes give two separate ranges`() {
        val o = "foo(alpha, beta)"
        val n = "foo(gamma, delta)"
        assertEquals(listOf("alpha", "beta"), slice(o, old(o, n)))
        assertEquals(listOf("gamma", "delta"), slice(n, new(o, n)))
    }

    @Test
    fun `a pure insertion marks only the inserted text`() {
        val o = "a c"
        val n = "a b c"
        assertTrue(old(o, n).isEmpty(), "nothing was removed")
        assertEquals(listOf("b"), slice(n, new(o, n)), "the trailing space is not part of the change")
    }

    @Test
    fun `an empty line against content marks the whole content`() {
        assertEquals(listOf(0 until 5), InlineDiff.of("", "hello").new)
        assertTrue(InlineDiff.of("", "hello").old.isEmpty())
        assertEquals(listOf(0 until 5), InlineDiff.of("hello", "").old)
    }

    @Test
    fun `ranges are in order, disjoint and inside the line`() {
        val o = "one two three four five"
        val n = "one TWO three FOUR five"
        for ((text, ranges) in listOf(o to old(o, n), n to new(o, n))) {
            var previousEnd = -1
            for (range in ranges) {
                assertTrue(range.first > previousEnd, "ranges overlap or are unordered")
                assertTrue(range.first >= 0 && range.last < text.length, "range escapes the line")
                previousEnd = range.last
            }
        }
    }

    @Test
    fun `leading whitespace change is marked`() {
        val o = "  x = 1"
        val n = "    x = 1"
        assertEquals(listOf("  "), slice(o, old(o, n)))
        assertEquals(listOf("    "), slice(n, new(o, n)))
    }

    @Test
    fun `a moved token is recognised rather than marking the whole line`() {
        // The LCS is what buys this; a prefix/suffix trim would mark everything
        // between the first and last difference.
        val o = "call(a, b, c)"
        val n = "call(a, c)"
        assertEquals(listOf("b,"), slice(o, old(o, n)))
        assertTrue(new(o, n).isEmpty(), "nothing new was introduced")
    }

    @Test
    fun `a line with too many tokens falls back to the affix span`() {
        // The guard exists so a minified bundle on one line cannot allocate a
        // multi-million-cell LCS table.
        val o = "x " + "a ".repeat(InlineDiff.MAX_TOKENS) + "END"
        val n = "y " + "a ".repeat(InlineDiff.MAX_TOKENS) + "END"
        val ranges = InlineDiff.of(o, n)
        assertEquals(1, ranges.old.size)
        assertEquals(0 until 1, ranges.old[0])
        assertEquals(0 until 1, ranges.new[0])
    }

    @Test
    fun `the affix span keeps the common prefix and suffix out of the range`() {
        val r = InlineDiff.affixSpan("prefix_MIDDLE_suffix", "prefix_OTHER_suffix")
        assertEquals("MIDDLE", "prefix_MIDDLE_suffix".substring(r.old[0].first, r.old[0].last + 1))
        assertEquals("OTHER", "prefix_OTHER_suffix".substring(r.new[0].first, r.new[0].last + 1))
    }

    @Test
    fun `the affix span does not double count an overlapping prefix and suffix`() {
        // "aa" -> "aaa": the prefix and suffix would otherwise both claim the
        // same characters and produce a negative-length range.
        val r = InlineDiff.affixSpan("aa", "aaa")
        assertTrue(r.old.isEmpty() || r.old[0].last >= r.old[0].first)
        assertTrue(r.new.isEmpty() || r.new[0].last >= r.new[0].first)
    }

    @Test
    fun `tokenize splits word runs from everything else`() {
        val text = "a_1 + b"
        assertEquals(
            listOf("a_1", " + ", "b"),
            InlineDiff.tokenize(text).map { text.substring(it.first, it.last + 1) },
        )
    }
}
