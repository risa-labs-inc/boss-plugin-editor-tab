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
 * Whether the editor writes modified files back to disk on its own.
 *
 * Persisted through plugin-scoped storage rather than `~/.boss/editor-settings.json`:
 * that file is owned by BossEditor's `EditorSettingsManager`, which serializes the whole
 * `EditorSettings` and overwrites the file, so an extra key there would be erased the next
 * time the user changed any editor setting.
 *
 * Off by default. Auto save writes to disk without asking, which is not a behaviour to turn
 * on for someone silently.
 */
class AutoSaveSettingsManager(
    private val storage: PluginStorageProvider?,
    private val loadTimeoutMillis: Long = DEFAULT_LOAD_TIMEOUT_MILLIS
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )

    // Set once the user touches the toggle, so a slow load cannot overwrite a deliberate
    // choice made while it was still in flight - same guard MarkdownViewSettingsManager uses.
    private val touched = AtomicBoolean(false)

    private val _enabled = MutableStateFlow(false)
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
        const val ENABLED_KEY = "editor.autoSave"
    }
}

/**
 * How long the editor waits after the last keystroke before writing an auto save.
 *
 * Long enough that a burst of typing produces one write rather than dozens, short enough that
 * the file on disk is never far behind what is on screen.
 */
const val AUTO_SAVE_DEBOUNCE_MILLIS = 1_000L
