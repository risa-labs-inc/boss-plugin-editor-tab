package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What these pin: a composer session belongs to the plugin, not to the tab
 * that happens to be showing it.
 *
 * The two failures behind them - progress written from a snapshot the run
 * captured at its start, and a tab re-reading the persisted copy when it came
 * back - both looked the same from the outside: the agent's output vanished.
 */
class ComposerSessionsTest {

    private fun sessions(storage: FakeStorage = FakeStorage()): Pair<ComposerSessions, CoroutineScope> {
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        return ComposerSessions(ComposerSessionStore(storage), scope) to scope
    }

    private suspend fun StateFlow<ComposerSessionData?>.loaded(): ComposerSessionData =
        withTimeout(2_000) { first { it != null }!! }

    @Test
    fun `each update sees the previous one, so a run accumulates its output`() = runBlocking {
        val (sessions, scope) = sessions()
        try {
            sessions.put(ComposerSessionData("s1", task = "t", title = "t"))

            sessions.update("s1") { it.copy(messages = it.messages + ComposerMessage("tool", "read_buffer")) }
            sessions.update("s1") { it.copy(proposals = it.proposals + proposal("p1")) }
            sessions.update("s1") { it.copy(messages = it.messages + ComposerMessage("assistant", "done")) }

            val s = sessions.snapshot("s1")!!
            assertEquals(listOf("read_buffer", "done"), s.messages.map { it.text })
            assertEquals(listOf("p1"), s.proposals.map { it.id })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `reopening a session returns the live state, not the persisted copy`() = runBlocking {
        val (sessions, scope) = sessions()
        try {
            sessions.open("s2").loaded()
            sessions.update("s2") { it.copy(status = "running", messages = listOf(ComposerMessage("user", "go"))) }

            // The tab was switched away from and came back: same session id,
            // a second open. Nothing is re-read, and nothing is lost.
            val reopened = sessions.open("s2").loaded()
            assertEquals("running", reopened.status)
            assertEquals(listOf("go"), reopened.messages.map { it.text })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a session persisted mid-run says the run did not survive the restart`() = runBlocking {
        val stored =
            ComposerSessionData("s3", task = "t", title = "t", status = "running", statusMessage = "Thinking...")
        val storage = FakeStorage(mapOf("composer-session-s3" to Json.encodeToString(stored)))
        val (sessions, scope) = sessions(storage)
        try {
            val restored = sessions.open("s3").loaded()
            assertEquals("idle", restored.status)
            assertTrue(restored.statusMessage.contains("restarted"), restored.statusMessage)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `updating a session nobody opened invents nothing`() = runBlocking {
        val (sessions, scope) = sessions()
        try {
            assertNull(sessions.update("nope") { it.copy(status = "running") })
            assertNull(sessions.snapshot("nope"))
        } finally {
            scope.cancel()
        }
    }

    private fun proposal(id: String) =
        ComposerProposal(
            id = id,
            path = "/tmp/A.kt",
            startLine = 1,
            startCol = 1,
            endLine = 2,
            endCol = 1,
            newText = "x",
            expectedVersion = 1,
        )
}

/** Only the string operations [ComposerSessionStore] uses are real. */
private class FakeStorage(initial: Map<String, String> = emptyMap()) : PluginStorageProvider {
    private val strings = ConcurrentHashMap(initial)

    override fun getPluginId(): String = "test.editor-tab"

    override suspend fun putString(key: String, value: String) {
        strings[key] = value
    }

    override suspend fun getString(key: String, defaultValue: String?): String? = strings[key] ?: defaultValue

    override suspend fun remove(key: String) {
        strings.remove(key)
    }

    override suspend fun contains(key: String): Boolean = strings.containsKey(key)

    override suspend fun getAllKeys(): Set<String> = strings.keys.toSet()

    override suspend fun clear() = strings.clear()

    override fun observeString(key: String): Flow<String?> = flowOf(strings[key])

    override fun observeChanges(): Flow<String> = emptyFlow()

    override suspend fun putInt(key: String, value: Int) = unsupported()

    override suspend fun getInt(key: String, defaultValue: Int): Int = unsupported()

    override suspend fun putLong(key: String, value: Long) = unsupported()

    override suspend fun getLong(key: String, defaultValue: Long): Long = unsupported()

    override suspend fun putBoolean(key: String, value: Boolean) = unsupported()

    override suspend fun getBoolean(key: String, defaultValue: Boolean): Boolean = unsupported()

    override suspend fun putFloat(key: String, value: Float) = unsupported()

    override suspend fun getFloat(key: String, defaultValue: Float): Float = unsupported()

    override suspend fun putJson(key: String, jsonValue: String) = unsupported()

    override suspend fun getJson(key: String): String? = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("not used by ComposerSessionStore")
}
