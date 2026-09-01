package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.dynamic.editortab.ExternalChangePolicy.FileSignature
import ai.rever.boss.plugin.dynamic.editortab.ExternalChangePolicy.Verdict
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The has-unsaved-changes x changed-on-disk x deleted state machine.
 *
 * The rule these exist to defend: a dirty buffer is NEVER reloaded. Stale
 * content is an inconvenience the user can fix; unsaved work thrown away is
 * gone. Every path that could reach [Verdict.RELOAD] with unsaved changes is
 * pinned here.
 */
class ExternalChangePolicyTest {

    private val known = FileSignature(exists = true, modifiedAt = 1_000L, size = 42L)
    private val moved = FileSignature(exists = true, modifiedAt = 2_000L, size = 43L)

    private fun decide(
        current: FileSignature = moved,
        diskText: String? = "disk",
        bufferText: String = "buffer",
        dirty: Boolean = false,
        known: FileSignature = this.known,
    ) = ExternalChangePolicy.decide(known, current, diskText, bufferText, dirty)

    @Test
    fun `an unchanged signature is left alone`() {
        assertEquals(Verdict.NONE, decide(current = known))
    }

    @Test
    fun `a clean buffer takes the new content`() {
        assertEquals(Verdict.RELOAD, decide(dirty = false))
    }

    @Test
    fun `a dirty buffer asks instead of reloading`() {
        assertEquals(Verdict.CONFLICT, decide(dirty = true))
    }

    @Test
    fun `a dirty buffer is never reloaded, whatever else is true`() {
        // The one rule. Sweep the inputs that reach a decision at all.
        for (diskText in listOf("disk", "", "other")) {
            for (size in listOf(0L, 43L, 9_999L)) {
                val verdict = decide(
                    current = moved.copy(size = size),
                    diskText = diskText,
                    bufferText = "buffer",
                    dirty = true,
                )
                assertEquals(Verdict.CONFLICT, verdict, "size=$size diskText=$diskText")
            }
        }
    }

    @Test
    fun `a deleted file is marked, not reloaded or blanked`() {
        assertEquals(Verdict.DELETED, decide(current = FileSignature.MISSING))
    }

    @Test
    fun `a deleted file with unsaved changes is still only marked`() {
        assertEquals(Verdict.DELETED, decide(current = FileSignature.MISSING, dirty = true))
    }

    @Test
    fun `a file that was already gone is not reported again`() {
        assertEquals(
            Verdict.NONE,
            decide(current = FileSignature.MISSING, known = FileSignature.MISSING),
        )
    }

    @Test
    fun `a file that comes back is picked up`() {
        assertEquals(Verdict.RELOAD, decide(current = moved, known = FileSignature.MISSING))
    }

    @Test
    fun `our own save does not look like an external change`() {
        // After we write, the disk and the buffer agree - so the signature
        // moving is not something to report. This is the self-write guard, and
        // it needs no timing.
        assertEquals(Verdict.NONE, decide(diskText = "same", bufferText = "same"))
    }

    @Test
    fun `our own save does not prompt even while the buffer is dirty`() {
        assertEquals(Verdict.NONE, decide(diskText = "same", bufferText = "same", dirty = true))
    }

    @Test
    fun `a touch that changes only the timestamp is ignored`() {
        assertEquals(
            Verdict.NONE,
            decide(current = moved.copy(size = known.size), diskText = "text", bufferText = "text"),
        )
    }

    @Test
    fun `an unreadable file says nothing rather than guessing`() {
        // A half-written file or a momentary lock: look again next poll.
        assertEquals(Verdict.NONE, decide(diskText = null))
        assertEquals(Verdict.NONE, decide(diskText = null, dirty = true))
    }

    @Test
    fun `an emptied file is a real change, not a read failure`() {
        assertEquals(Verdict.RELOAD, decide(diskText = "", bufferText = "buffer"))
        assertEquals(Verdict.CONFLICT, decide(diskText = "", bufferText = "buffer", dirty = true))
    }

    // ── caret preservation across a reload ────────────────────────────────

    @Test
    fun `the caret keeps its place when the file still has that line`() {
        assertEquals(3 to 5, ExternalChangePolicy.clampCaret(3, 5, newLineCount = 10) { 40 })
    }

    @Test
    fun `a caret past the new end of file is pulled back inside it`() {
        assertEquals(4 to 2, ExternalChangePolicy.clampCaret(99, 2, newLineCount = 5) { 10 })
    }

    @Test
    fun `a caret past the end of its line is pulled back to the line end`() {
        assertEquals(3 to 7, ExternalChangePolicy.clampCaret(3, 99, newLineCount = 10) { 7 })
    }

    @Test
    fun `an emptied file puts the caret at the start rather than out of bounds`() {
        assertEquals(0 to 0, ExternalChangePolicy.clampCaret(5, 5, newLineCount = 0) { 0 })
    }
}
