package ai.rever.boss.plugin.dynamic.editortab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The diff pane acquires its buffer asynchronously - a whole-file read must
 * not run on the composition thread - but is disposed synchronously, so the
 * acquire and the dispose can land in either order. Every ordering has to
 * leave the registry with exactly one release per acquire: an orphaned
 * reference holds the buffer's whole file content for the process lifetime
 * and keeps [ExternalChangeWatcher] stat-ing and git-diffing a file no
 * viewport is showing.
 *
 * `EditorBufferRegistryTest` pins the registry's own arithmetic; this pins the
 * pairing the pane is responsible for, which is where the surviving leak was.
 */
class DiffPaneBufferSlotTest {

    private fun acquire(path: String) = EditorBufferRegistry.acquire(path, "val a = 1\n", "kotlin")

    @Test
    fun `dispose releases the published reference`() {
        val path = "/tmp/et-slot-test/Published.kt"
        val slot = BufferSlot()
        val buffer = slot.publish(acquire(path))

        assertSame(EditorBufferRegistry.find(path), buffer)
        assertEquals(1, buffer!!.refCount)

        slot.dispose()
        assertNull(EditorBufferRegistry.find(path))
    }

    @Test
    fun `an acquire that lands after dispose is released, not orphaned`() {
        val path = "/tmp/et-slot-test/Cancelled.kt"
        // The pane left the composition while the disk read was still in
        // flight: dispose runs first, and the acquire completes into a slot
        // nobody is watching any more.
        val slot = BufferSlot()
        slot.dispose()

        assertNull(slot.publish(acquire(path)))
        assertNull(EditorBufferRegistry.find(path))
        assertTrue(slot.isDisposed)
    }

    @Test
    fun `a live sibling viewport survives a late acquire into a disposed slot`() {
        val path = "/tmp/et-slot-test/Shared.kt"
        val editorTab = acquire(path)
        val slot = BufferSlot()
        slot.dispose()

        slot.publish(acquire(path))

        // The late release drops the diff pane's own reference and nothing else.
        assertSame(editorTab, EditorBufferRegistry.find(path))
        assertEquals(1, editorTab.refCount)

        EditorBufferRegistry.release(path)
        assertNull(EditorBufferRegistry.find(path))
    }

    @Test
    fun `re-publishing the same buffer instance does not strand the first reference`() {
        val path = "/tmp/et-slot-test/Rekeyed.kt"
        val slot = BufferSlot()
        val first = slot.publish(acquire(path))
        // Keying the release on the RESOLVED buffer missed exactly this:
        // EditorBuffer has no equals, so a re-resolve to the same instance
        // acquired a second time without ever re-running the release.
        val second = slot.publish(acquire(path))

        assertSame(first, second)
        assertEquals(1, second!!.refCount)

        slot.dispose()
        assertNull(slot.current)
        assertNull(EditorBufferRegistry.find(path))
    }
}
