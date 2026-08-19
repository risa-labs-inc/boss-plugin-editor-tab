package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

internal class FakePluginStorageProvider(
    initialStrings: Map<String, String> = emptyMap(),
    private val allowReads: CompletableDeferred<Unit>? = null
) : PluginStorageProvider {
    private val strings = ConcurrentHashMap(initialStrings)
    val stringWrites = Collections.synchronizedList(
        mutableListOf<Pair<String, String>>()
    )

    override fun getPluginId(): String = "test.editor-tab"

    override suspend fun putString(key: String, value: String) {
        strings[key] = value
        stringWrites += key to value
    }

    override suspend fun getString(key: String, defaultValue: String?): String? {
        allowReads?.await()
        return strings[key] ?: defaultValue
    }

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

    override suspend fun contains(key: String): Boolean = strings.containsKey(key)

    override suspend fun remove(key: String) {
        strings.remove(key)
    }

    override suspend fun getAllKeys(): Set<String> = strings.keys.toSet()

    override suspend fun clear() {
        strings.clear()
    }

    override fun observeString(key: String): Flow<String?> = flowOf(strings[key])

    override fun observeChanges(): Flow<String> = emptyFlow()

    suspend fun awaitWriteCount(expected: Int) {
        withTimeout(2_000) {
            while (snapshotWrites().size < expected) {
                delay(10)
            }
        }
    }

    fun snapshotWrites(): List<Pair<String, String>> {
        return synchronized(stringWrites) {
            stringWrites.toList()
        }
    }

    private fun unsupported(): Nothing = error("Not used by these tests")
}
