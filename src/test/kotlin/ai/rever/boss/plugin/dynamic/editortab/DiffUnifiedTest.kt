package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.DiffHunk
import ai.rever.boss.plugin.api.DiffLine
import ai.rever.boss.plugin.api.DiffLineKind
import ai.rever.boss.plugin.api.GitDiffData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The unified view: ONE document in git's own line order.
 *
 * It goes into a real editor like the side-by-side panes do, so the `+`/`-`
 * markers must be gone - a leading `+` would shift every column and break the
 * lexer. The marking lives in [DiffUnified.Unified.marks] instead.
 */
class DiffUnifiedTest {

    private fun diff(vararg lines: Pair<DiffLineKind, String>): GitDiffData {
        val parsed = lines.map { (kind, text) -> DiffLine(text, kind, null, null) }
        return GitDiffData(
            path = "a.kt",
            additions = parsed.count { it.kind == DiffLineKind.ADDED },
            deletions = parsed.count { it.kind == DiffLineKind.REMOVED },
            hunks = listOf(DiffHunk(1, parsed.size, 1, parsed.size, "@@", parsed)),
        )
    }

    @Test
    fun `every line of the hunk appears once, in order`() {
        val u = DiffUnified.of(
            diff(
                DiffLineKind.CONTEXT to "a",
                DiffLineKind.REMOVED to "old",
                DiffLineKind.ADDED to "new",
                DiffLineKind.CONTEXT to "b",
            ),
        )
        assertEquals(listOf("a", "old", "new", "b"), u.text.lines())
    }

    @Test
    fun `marks line up one for one with the text`() {
        val u = DiffUnified.of(
            diff(
                DiffLineKind.CONTEXT to "a",
                DiffLineKind.REMOVED to "old",
                DiffLineKind.ADDED to "new",
            ),
        )
        assertEquals(u.text.lines().size, u.marks.size)
        assertEquals(listOf(null, DiffLineKind.REMOVED, DiffLineKind.ADDED), u.marks)
    }

    @Test
    fun `several hunks run together as one document`() {
        val first = listOf(DiffLine("one", DiffLineKind.ADDED, null, null))
        val second = listOf(DiffLine("two", DiffLineKind.REMOVED, null, null))
        val u = DiffUnified.of(
            GitDiffData(
                path = "a.kt",
                additions = 1,
                deletions = 1,
                hunks = listOf(
                    DiffHunk(1, 1, 1, 1, "@@ a @@", first),
                    DiffHunk(9, 1, 9, 1, "@@ b @@", second),
                ),
            ),
        )
        assertEquals(listOf("one", "two"), u.text.lines())
        assertEquals(listOf(DiffLineKind.ADDED, DiffLineKind.REMOVED), u.marks)
    }

    @Test
    fun `a diff with no hunks yields nothing to render`() {
        val u = DiffUnified.of(GitDiffData(path = "a.kt", additions = 0, deletions = 0))
        assertTrue(u.marks.isEmpty())
        assertEquals("", u.text)
    }
}
