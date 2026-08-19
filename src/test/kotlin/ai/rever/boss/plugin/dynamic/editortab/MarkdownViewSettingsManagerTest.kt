package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownViewSettingsManagerTest {

    @Test
    fun `loads persisted settings and transitions to loaded`() = runBlocking {
        val allowReads = CompletableDeferred<Unit>()
        val storage = FakePluginStorageProvider(
            initialStrings = mapOf(
                "markdown.defaultView" to "SPLIT",
                "markdown.lastSelectedView" to "EDIT"
            ),
            allowReads = allowReads
        )
        val manager = MarkdownViewSettingsManager(storage)

        try {
            assertFalse(manager.isLoaded.value)

            allowReads.complete(Unit)
            manager.awaitLoaded()

            assertTrue(manager.isLoaded.value)
            assertEquals(
                MarkdownViewSettings(
                    defaultView = MarkdownDefaultView.SPLIT,
                    lastSelectedView = MarkdownViewMode.EDIT
                ),
                manager.settings.value
            )
        } finally {
            manager.dispose()
        }
    }

    @Test
    fun `unknown persisted enum values fall back to defaults`() = runBlocking {
        val storage = FakePluginStorageProvider(
            initialStrings = mapOf(
                "markdown.defaultView" to "REMOVED_DEFAULT",
                "markdown.lastSelectedView" to "REMOVED_MODE"
            )
        )
        val manager = MarkdownViewSettingsManager(storage)

        try {
            manager.awaitLoaded()

            assertEquals(MarkdownViewSettings(), manager.settings.value)
        } finally {
            manager.dispose()
        }
    }

    @Test
    fun `matching updates do not write storage`() = runBlocking {
        val storage = FakePluginStorageProvider(
            initialStrings = mapOf(
                "markdown.defaultView" to "SPLIT",
                "markdown.lastSelectedView" to "EDIT"
            )
        )
        val manager = MarkdownViewSettingsManager(storage)

        try {
            manager.awaitLoaded()

            manager.setDefaultView(MarkdownDefaultView.SPLIT)
            manager.recordSelectedView(MarkdownViewMode.EDIT)

            assertTrue(storage.stringWrites.isEmpty())
        } finally {
            manager.dispose()
        }
    }

    @Test
    fun `changed settings update state and storage`() = runBlocking {
        val storage = FakePluginStorageProvider()
        val manager = MarkdownViewSettingsManager(storage)

        try {
            manager.awaitLoaded()

            manager.setDefaultView(MarkdownDefaultView.LAST_SELECTED)
            manager.recordSelectedView(MarkdownViewMode.SPLIT)
            storage.awaitWriteCount(2)

            assertEquals(
                MarkdownViewSettings(
                    defaultView = MarkdownDefaultView.LAST_SELECTED,
                    lastSelectedView = MarkdownViewMode.SPLIT
                ),
                manager.settings.value
            )
            assertEquals(
                listOf(
                    "markdown.defaultView" to "LAST_SELECTED",
                    "markdown.lastSelectedView" to "SPLIT"
                ),
                storage.snapshotWrites()
            )
        } finally {
            manager.dispose()
        }
    }

    @Test
    fun `pre-load default change preserves persisted last selection`() = runBlocking {
        val allowReads = CompletableDeferred<Unit>()
        val storage = FakePluginStorageProvider(
            initialStrings = mapOf(
                "markdown.defaultView" to "SPLIT",
                "markdown.lastSelectedView" to "EDIT"
            ),
            allowReads = allowReads
        )
        val manager = MarkdownViewSettingsManager(storage)

        try {
            manager.setDefaultView(MarkdownDefaultView.EDIT)
            allowReads.complete(Unit)
            manager.awaitLoaded()

            assertEquals(
                MarkdownViewSettings(
                    defaultView = MarkdownDefaultView.EDIT,
                    lastSelectedView = MarkdownViewMode.EDIT
                ),
                manager.settings.value
            )
        } finally {
            manager.dispose()
        }
    }

    @Test
    fun `pre-load mode change preserves persisted default`() = runBlocking {
        val allowReads = CompletableDeferred<Unit>()
        val storage = FakePluginStorageProvider(
            initialStrings = mapOf(
                "markdown.defaultView" to "SPLIT",
                "markdown.lastSelectedView" to "EDIT"
            ),
            allowReads = allowReads
        )
        val manager = MarkdownViewSettingsManager(storage)

        try {
            manager.recordSelectedView(MarkdownViewMode.SPLIT)
            allowReads.complete(Unit)
            manager.awaitLoaded()

            assertEquals(
                MarkdownViewSettings(
                    defaultView = MarkdownDefaultView.SPLIT,
                    lastSelectedView = MarkdownViewMode.SPLIT
                ),
                manager.settings.value
            )
        } finally {
            manager.dispose()
        }
    }

    @Test
    fun `load timeout releases UI with defaults`() = runBlocking {
        val storage = FakePluginStorageProvider(
            initialStrings = mapOf(
                "markdown.defaultView" to "SPLIT",
                "markdown.lastSelectedView" to "EDIT"
            ),
            allowReads = CompletableDeferred()
        )
        val manager = MarkdownViewSettingsManager(
            storage = storage,
            loadTimeoutMillis = 50
        )

        try {
            manager.awaitLoaded()

            assertTrue(manager.isLoaded.value)
            assertEquals(MarkdownViewSettings(), manager.settings.value)
        } finally {
            manager.dispose()
        }
    }

    @Test
    fun `fixed default still tracks last selected view`() = runBlocking {
        val storage = FakePluginStorageProvider()
        val manager = MarkdownViewSettingsManager(storage)

        try {
            manager.awaitLoaded()
            assertEquals(MarkdownDefaultView.PREVIEW, manager.settings.value.defaultView)

            manager.recordSelectedView(MarkdownViewMode.EDIT)
            storage.awaitWriteCount(1)

            assertEquals(MarkdownDefaultView.PREVIEW, manager.settings.value.defaultView)
            assertEquals(MarkdownViewMode.EDIT, manager.settings.value.lastSelectedView)
            assertEquals(
                listOf("markdown.lastSelectedView" to "EDIT"),
                storage.snapshotWrites()
            )
        } finally {
            manager.dispose()
        }
    }

    private suspend fun MarkdownViewSettingsManager.awaitLoaded() {
        withTimeout(2_000) {
            isLoaded.first { it }
        }
    }
}
