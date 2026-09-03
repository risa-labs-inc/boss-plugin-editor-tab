package ai.rever.boss.plugin.dynamic.editortab

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The registry's refcount is what buffer sharing stands on: an over-release
 * drops a sibling viewport's live buffer (the load-failed tab that releases
 * without ever acquiring), and an under-release leaks the buffer and its
 * whole file content forever.
 *
 * There was no test for any of this, which is why the over-release shipped.
 */
class EditorBufferRegistryTest {

    @Test
    fun `two acquires for one path share one buffer`() {
        val a = EditorBufferRegistry.acquire("/tmp/et-registry-test/Foo.kt", "val a = 1\n", "kotlin")
        val b = EditorBufferRegistry.acquire("/tmp/et-registry-test/Foo.kt", "val a = 2\n", "kotlin")

        assertSame(a, b)
        assertEquals(2, a.refCount)
        // The second acquire must not replace the first viewport's document.
        assertEquals("val a = 1\n", a.editorState.document.getText())

        EditorBufferRegistry.release(a.path)
        EditorBufferRegistry.release(b.path)
        assertNull(EditorBufferRegistry.find(a.path))
    }

    @Test
    fun `releasing one of two viewports keeps the buffer alive`() {
        val a = EditorBufferRegistry.acquire("/tmp/et-registry-test/Bar.kt", "x", "kotlin")
        val b = EditorBufferRegistry.acquire("/tmp/et-registry-test/Bar.kt", "y", "kotlin")

        EditorBufferRegistry.release(a.path)

        assertSame(b, EditorBufferRegistry.find(b.path))
        assertEquals(1, b.refCount)

        EditorBufferRegistry.release(b.path)
        assertNull(EditorBufferRegistry.find(b.path))
    }

    @Test
    fun `releasing an unknown path is a no-op`() {
        val a = EditorBufferRegistry.acquire("/tmp/et-registry-test/Baz.kt", "z", "kotlin")

        // The registry-level shape of the bug: a release that does not pair
        // with an acquire. It must not touch a buffer someone else holds.
        assertFalse(EditorBufferRegistry.release("/tmp/et-registry-test/Other.kt"))
        assertSame(a, EditorBufferRegistry.find(a.path))
        assertEquals(1, a.refCount)

        EditorBufferRegistry.release(a.path)
        assertNull(EditorBufferRegistry.find(a.path))
    }

    @Test
    fun `keyFor expands tilde, the way API paths arrive`() {
        val home = System.getProperty("user.home")

        assertEquals(File("$home/a.kt").absolutePath, EditorBufferRegistry.keyFor("~/a.kt"))
        assertEquals(File(home).absolutePath, EditorBufferRegistry.keyFor("~"))
        // Already-absolute paths are normalized but not moved.
        assertEquals(File("/tmp/a.kt").absolutePath, EditorBufferRegistry.keyFor("/tmp/a.kt"))
    }
}
