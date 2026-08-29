package ai.rever.boss.plugin.dynamic.editortab

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A tab that shows a file has to notice when something else rewrites it, without acting on a
 * write that is still in progress and without calling a rename in flight a deletion. Those
 * rules are decided from snapshots alone, so they can be pinned here without touching a disk.
 */
class ExternalChangeDetectorTest {

    private fun snapshot(lastModified: Long, size: Long) =
        DiskSnapshot(exists = true, lastModified = lastModified, size = size)

    private val baseline = snapshot(lastModified = 1_000L, size = 40L)

    @Test
    fun `a file nobody touched is unchanged`() {
        val detector = ExternalChangeDetector(baseline)

        assertEquals(DiskState.UNCHANGED, detector.observe(baseline))
        assertEquals(DiskState.UNCHANGED, detector.observe(baseline))
    }

    @Test
    fun `a write in flight settles before it counts as a change`() {
        val detector = ExternalChangeDetector(baseline)

        // Each tick catches the file at a different length: a writer is still writing, and
        // reading now would hand the editor a torn file.
        assertEquals(DiskState.SETTLING, detector.observe(snapshot(2_000L, 120L)))
        assertEquals(DiskState.SETTLING, detector.observe(snapshot(2_000L, 400L)))
        assertEquals(DiskState.SETTLING, detector.observe(snapshot(2_000L, 900L)))

        // It stopped growing. Now it is safe to read.
        assertEquals(DiskState.CHANGED, detector.observe(snapshot(2_000L, 900L)))
    }

    @Test
    fun `a single settled write is reported on the next look`() {
        val detector = ExternalChangeDetector(baseline)

        assertEquals(DiskState.SETTLING, detector.observe(snapshot(2_000L, 41L)))
        assertEquals(DiskState.CHANGED, detector.observe(snapshot(2_000L, 41L)))
    }

    @Test
    fun `a bare mtime bump still reaches the content check`() {
        val detector = ExternalChangeDetector(baseline)

        // touch, or a formatter that had nothing to reformat: same size, new mtime. The
        // detector cannot tell those from a real edit and must not try - it reports the
        // change and reloadAction decides there is nothing to do.
        val touched = snapshot(lastModified = 9_000L, size = baseline.size)
        assertEquals(DiskState.SETTLING, detector.observe(touched))
        assertEquals(DiskState.CHANGED, detector.observe(touched))

        assertEquals(ReloadAction.NONE, reloadAction("same", "same", bufferModified = false))
    }

    @Test
    fun `a rename in flight is not a deletion`() {
        val detector = ExternalChangeDetector(baseline)

        // An atomic save is a temp file plus a rename, and a poll can land in the gap.
        assertEquals(DiskState.SETTLING, detector.observe(DiskSnapshot.MISSING))
        assertEquals(DiskState.SETTLING, detector.observe(DiskSnapshot.MISSING))

        // The file reappeared: what follows is a change, never a deletion.
        assertEquals(DiskState.SETTLING, detector.observe(snapshot(3_000L, 80L)))
        assertEquals(DiskState.CHANGED, detector.observe(snapshot(3_000L, 80L)))
    }

    @Test
    fun `a file that stays gone is a deletion`() {
        val detector = ExternalChangeDetector(baseline)

        assertEquals(DiskState.SETTLING, detector.observe(DiskSnapshot.MISSING))
        assertEquals(DiskState.SETTLING, detector.observe(DiskSnapshot.MISSING))
        assertEquals(DiskState.DELETED, detector.observe(DiskSnapshot.MISSING))
        // Still gone, still deleted: the caller's notice is idempotent.
        assertEquals(DiskState.DELETED, detector.observe(DiskSnapshot.MISSING))
    }

    @Test
    fun `accepting a snapshot stops it being news`() {
        val detector = ExternalChangeDetector(baseline)
        val written = snapshot(4_000L, 200L)

        assertEquals(DiskState.SETTLING, detector.observe(written))
        assertEquals(DiskState.CHANGED, detector.observe(written))

        // What a save, a reload, or "keep my version" does.
        detector.accept(written)

        assertEquals(DiskState.UNCHANGED, detector.observe(written))
    }

    @Test
    fun `a change arriving after an accept is reported again`() {
        val detector = ExternalChangeDetector(baseline)
        val ours = snapshot(4_000L, 200L)
        detector.accept(ours)

        val theirs = snapshot(5_000L, 260L)
        assertEquals(DiskState.SETTLING, detector.observe(theirs))
        assertEquals(DiskState.CHANGED, detector.observe(theirs))
    }
}

/**
 * Once a settled change has been read, what happens to it depends entirely on whether the
 * tab has anything of the user's in it.
 */
class ReloadActionTest {

    @Test
    fun `identical content is not worth a word`() {
        assertEquals(
            ReloadAction.NONE,
            reloadAction(diskContent = "fun main() {}", bufferContent = "fun main() {}", bufferModified = false)
        )
    }

    @Test
    fun `identical content stays quiet even with unsaved edits`() {
        // The user typed, auto save wrote, and the poll noticed our own write a beat late.
        assertEquals(
            ReloadAction.NONE,
            reloadAction(diskContent = "typed", bufferContent = "typed", bufferModified = true)
        )
    }

    @Test
    fun `a clean tab just follows the file`() {
        assertEquals(
            ReloadAction.RELOAD,
            reloadAction(diskContent = "new", bufferContent = "old", bufferModified = false)
        )
    }

    @Test
    fun `unsaved edits are never replaced without asking`() {
        assertEquals(
            ReloadAction.PROMPT,
            reloadAction(diskContent = "theirs", bufferContent = "mine", bufferModified = true)
        )
    }
}

/**
 * The rules above are decided from snapshots, which is only worth anything if a real write to
 * a real file actually produces a different one. Everything else here is synthetic; this is
 * the part that would quietly stop working if `File` reported what we assume it does not.
 */
class DiskSnapshotFileTest {

    private fun snapshotOf(file: File) =
        DiskSnapshot(exists = file.exists(), lastModified = file.lastModified(), size = file.length())

    @Test
    fun `a write to a real file moves the snapshot`() {
        val file = File.createTempFile("editor-tab-reload", ".txt")
        try {
            file.writeText("first")
            val before = snapshotOf(file)

            file.writeText("second, and rather longer")
            val after = snapshotOf(file)

            assertNotEquals(before, after)

            val detector = ExternalChangeDetector(before)
            assertEquals(DiskState.SETTLING, detector.observe(after))
            assertEquals(DiskState.CHANGED, detector.observe(after))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a deleted real file reads as missing`() {
        val file = File.createTempFile("editor-tab-reload", ".txt")
        file.writeText("here")
        val before = snapshotOf(file)
        file.delete()

        assertEquals(DiskSnapshot.MISSING, snapshotOf(file))

        val detector = ExternalChangeDetector(before)
        repeat(2) { assertEquals(DiskState.SETTLING, detector.observe(snapshotOf(file))) }
        assertEquals(DiskState.DELETED, detector.observe(snapshotOf(file)))
    }
}
