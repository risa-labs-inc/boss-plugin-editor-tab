package ai.rever.boss.plugin.dynamic.editortab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoSaveSettingsManagerTest {

    @Test
    fun `defaults to off`() = runBlocking {
        // Auto save writes to disk without being asked each time, so it must not arrive on.
        val manager = AutoSaveSettingsManager(storage = null)

        try {
            assertFalse(manager.enabled.value)
            assertTrue(manager.isLoaded.value, "with no storage there is nothing to wait for")
        } finally {
            manager.dispose()
        }
    }

    @Test
    fun `restores a stored preference`() = runBlocking {
        val storage = FakePluginStorageProvider(mapOf("editor.autoSave" to "true"))
        val manager = AutoSaveSettingsManager(storage)

        try {
            manager.awaitLoaded()
            assertTrue(manager.enabled.value)
        } finally {
            manager.dispose()
        }
    }

    @Test
    fun `persists a change`() = runBlocking {
        val storage = FakePluginStorageProvider()
        val manager = AutoSaveSettingsManager(storage)

        try {
            manager.awaitLoaded()
            manager.setEnabled(true)
            storage.awaitWriteCount(1)

            assertEquals(listOf("editor.autoSave" to "true"), storage.snapshotWrites())
            assertTrue(manager.enabled.value)
        } finally {
            manager.dispose()
        }
    }

    @Test
    fun `setting the value it already has writes nothing`() = runBlocking {
        val storage = FakePluginStorageProvider()
        val manager = AutoSaveSettingsManager(storage)

        try {
            manager.awaitLoaded()
            manager.setEnabled(false)

            assertEquals(emptyList(), storage.snapshotWrites())
        } finally {
            manager.dispose()
        }
    }

    @Test
    fun `a garbled stored value falls back to off`() = runBlocking {
        // Anything that is not exactly "true"/"false" - a hand-edited file, a older format.
        val storage = FakePluginStorageProvider(mapOf("editor.autoSave" to "yes-please"))
        val manager = AutoSaveSettingsManager(storage)

        try {
            manager.awaitLoaded()
            assertFalse(manager.enabled.value)
        } finally {
            manager.dispose()
        }
    }

    @Test
    fun `a choice made while the load is still in flight survives it`() = runBlocking {
        // The read is async and the toggle is reachable immediately. A slow load must not
        // stomp a deliberate choice - the same guard MarkdownViewSettingsManager carries.
        val allowReads = CompletableDeferred<Unit>()
        val storage = FakePluginStorageProvider(
            initialStrings = mapOf("editor.autoSave" to "false"),
            allowReads = allowReads
        )
        val manager = AutoSaveSettingsManager(storage)

        try {
            assertFalse(manager.isLoaded.value)
            manager.setEnabled(true)

            allowReads.complete(Unit)
            manager.awaitLoaded()

            assertTrue(manager.enabled.value, "the stored false overwrote the user's choice")
        } finally {
            manager.dispose()
        }
    }

    private suspend fun AutoSaveSettingsManager.awaitLoaded() {
        withTimeout(2_000) {
            isLoaded.first { it }
        }
    }
}
