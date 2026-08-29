package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Whether an editor tab follows the file it has open when something else writes to it.
 *
 * Persisted through plugin-scoped storage rather than `~/.boss/editor-settings.json` for the
 * same reason as [AutoSaveSettingsManager]: that file belongs to BossEditor's
 * `EditorSettingsManager`, which serializes the whole `EditorSettings` and overwrites it, so
 * an extra key there is erased the next time any editor setting changes.
 *
 * **On by default**, unlike auto save. Auto save changes the file on the user's behalf, which
 * needs asking; this only makes the tab show what is actually on disk, and a tab that quietly
 * shows something stale is the bug it exists to fix. Unsaved edits are never discarded without
 * a prompt either way.
 */
class ExternalReloadSettingsManager(
    private val storage: PluginStorageProvider?,
    private val loadTimeoutMillis: Long = DEFAULT_LOAD_TIMEOUT_MILLIS
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )

    // Set once the user touches the toggle, so a slow load cannot overwrite a deliberate
    // choice made while it was still in flight - same guard the other two managers use.
    private val touched = AtomicBoolean(false)

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _isLoaded = MutableStateFlow(storage == null)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    init {
        if (storage != null) {
            scope.launch {
                val stored = withTimeoutOrNull(loadTimeoutMillis) {
                    try {
                        storage.getString(ENABLED_KEY)?.toBooleanStrictOrNull()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        null
                    }
                }

                if (stored != null && !touched.get()) {
                    _enabled.value = stored
                }
                _isLoaded.value = true
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        touched.set(true)
        if (_enabled.value == enabled) return

        _enabled.value = enabled
        scope.launch {
            runCatching { storage?.putString(ENABLED_KEY, enabled.toString()) }
        }
    }

    fun dispose() {
        scope.cancel()
    }

    private companion object {
        const val DEFAULT_LOAD_TIMEOUT_MILLIS = 2_000L
        const val ENABLED_KEY = "editor.reloadExternalChanges"
    }
}
