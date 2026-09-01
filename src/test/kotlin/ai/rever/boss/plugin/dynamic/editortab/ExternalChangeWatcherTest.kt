package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.bosseditor.core.EditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The watcher against a REAL file on disk.
 *
 * The policy tests pin the decision; these pin that the decision is actually
 * carried out - that a clean buffer really takes the new bytes, and above all
 * that a dirty one really does not. The failure mode being defended against is
 * losing work the user typed, so it is worth exercising against the filesystem
 * rather than a mock of it.
 */
class ExternalChangeWatcherTest {

    private val watcher = ExternalChangeWatcher(
        scope = CoroutineScope(Dispatchers.Unconfined),
        // No Compose main thread in a unit test, and no need for one: the
        // document is a plain object.
        applyOn = Dispatchers.Unconfined,
    )

    private fun scratch(contents: String): File =
        File.createTempFile("watcher", ".txt").apply {
            deleteOnExit()
            writeText(contents)
        }

    private fun bufferFor(file: File): EditorBuffer =
        EditorBuffer(file.absolutePath, EditorState(file.readText(), file.absolutePath), "text")
            .also { it.knownSignature = signatureOf(file) }

    /** Write and push the mtime forward, so the change is visible whatever the clock granularity. */
    private fun writeExternally(file: File, text: String) {
        file.writeText(text)
        file.setLastModified(System.currentTimeMillis() + 10_000L)
    }

    @Test
    fun `an untouched file is left alone`() = runBlocking {
        val file = scratch("alpha\nbravo\n")
        val buffer = bufferFor(file)
        watcher.checkOnce(buffer)
        assertEquals("alpha\nbravo\n", buffer.content)
        assertEquals(ExternalState.IN_SYNC, buffer.externalState.value)
    }

    @Test
    fun `a clean buffer picks up an external edit`() = runBlocking {
        val file = scratch("alpha\nbravo\n")
        val buffer = bufferFor(file)
        writeExternally(file, "alpha\nCHANGED\ncharlie\n")

        watcher.checkOnce(buffer)

        assertEquals("alpha\nCHANGED\ncharlie\n", buffer.content, "the buffer did not reload")
        assertEquals(ExternalState.IN_SYNC, buffer.externalState.value)
        assertFalse(buffer.editorState.isModified.value, "a reloaded buffer is not dirty")
    }

    @Test
    fun `a dirty buffer keeps the user's edits and raises a conflict`() = runBlocking {
        // THE test. If this ever fails, the watcher is eating unsaved work.
        val file = scratch("alpha\nbravo\n")
        val buffer = bufferFor(file)
        buffer.editorState.insertText("MY UNSAVED EDIT")
        val mine = buffer.content
        assertTrue(buffer.editorState.isModified.value, "precondition: the buffer is dirty")

        writeExternally(file, "something else entirely\n")
        watcher.checkOnce(buffer)

        assertEquals(mine, buffer.content, "the user's unsaved edit was overwritten")
        assertEquals(ExternalState.CONFLICT, buffer.externalState.value)
    }

    @Test
    fun `a conflict survives further polls until it is resolved`() = runBlocking {
        val file = scratch("alpha\n")
        val buffer = bufferFor(file)
        buffer.editorState.insertText("mine")
        val mine = buffer.content
        writeExternally(file, "theirs\n")

        repeat(3) { watcher.checkOnce(buffer) }

        assertEquals(mine, buffer.content)
        assertEquals(ExternalState.CONFLICT, buffer.externalState.value)
    }

    @Test
    fun `resolving by reloading takes the disk's version`() = runBlocking {
        val file = scratch("alpha\n")
        val buffer = bufferFor(file)
        buffer.editorState.insertText("mine")
        writeExternally(file, "theirs\n")
        watcher.checkOnce(buffer)

        watcher.resolveByReloading(buffer)

        assertEquals("theirs\n", buffer.content)
        assertEquals(ExternalState.IN_SYNC, buffer.externalState.value)
    }

    @Test
    fun `resolving by keeping mine leaves the buffer untouched and stops asking`() = runBlocking {
        val file = scratch("alpha\n")
        val buffer = bufferFor(file)
        buffer.editorState.insertText("mine")
        val mine = buffer.content
        writeExternally(file, "theirs\n")
        watcher.checkOnce(buffer)

        watcher.resolveByKeepingMine(buffer)
        watcher.checkOnce(buffer)

        assertEquals(mine, buffer.content, "keeping mine must not later be undone by a poll")
        assertEquals(ExternalState.IN_SYNC, buffer.externalState.value)
    }

    @Test
    fun `our own save is not reported as an external change`() = runBlocking {
        val file = scratch("alpha\n")
        val buffer = bufferFor(file)
        buffer.editorState.insertText("mine")

        // What a save does: write the buffer out, then record it.
        file.writeText(buffer.content)
        file.setLastModified(System.currentTimeMillis() + 10_000L)
        buffer.editorState.markAsSaved()
        buffer.noteWrittenByUs()

        watcher.checkOnce(buffer)

        assertEquals(ExternalState.IN_SYNC, buffer.externalState.value, "our own write prompted")
    }

    @Test
    fun `a save made without the bookkeeping still does not prompt`() = runBlocking {
        // The content comparison is the real guard, so even a save that forgets
        // to call noteWrittenByUs cannot produce a spurious conflict.
        val file = scratch("alpha\n")
        val buffer = bufferFor(file)
        buffer.editorState.insertText("mine")
        file.writeText(buffer.content)
        file.setLastModified(System.currentTimeMillis() + 10_000L)

        watcher.checkOnce(buffer)

        assertEquals(ExternalState.IN_SYNC, buffer.externalState.value)
    }

    @Test
    fun `a deleted file is marked and the content is kept`() = runBlocking {
        val file = scratch("alpha\nbravo\n")
        val buffer = bufferFor(file)
        assertTrue(file.delete())

        watcher.checkOnce(buffer)

        assertEquals(ExternalState.DELETED, buffer.externalState.value)
        assertEquals("alpha\nbravo\n", buffer.content, "a deleted file must not blank the buffer")
    }

    @Test
    fun `a file that reappears is picked up again`() = runBlocking {
        val file = scratch("alpha\n")
        val buffer = bufferFor(file)
        assertTrue(file.delete())
        watcher.checkOnce(buffer)
        assertEquals(ExternalState.DELETED, buffer.externalState.value)

        writeExternally(file, "back again\n")
        watcher.checkOnce(buffer)

        assertEquals("back again\n", buffer.content)
        assertEquals(ExternalState.IN_SYNC, buffer.externalState.value)
    }

    @Test
    fun `a reload keeps the caret inside the new, shorter file`() = runBlocking {
        val file = scratch("one\ntwo\nthree\nfour\nfive\n")
        val buffer = bufferFor(file)
        buffer.editorState.moveCaret(ai.rever.bosseditor.core.EditorPosition(4, 2), false)

        writeExternally(file, "one\n")
        watcher.checkOnce(buffer)

        val caret = buffer.editorState.caretPosition.value
        assertTrue(caret.line < buffer.editorState.document.lineCount, "caret left past the end of file")
    }
}
