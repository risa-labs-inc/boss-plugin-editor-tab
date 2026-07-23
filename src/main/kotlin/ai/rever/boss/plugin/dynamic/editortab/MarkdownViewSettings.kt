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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Controls which mode a newly opened Markdown tab uses.
 */
enum class MarkdownDefaultView(
    val displayName: String,
    val description: String
) {
    EDIT(
        displayName = MarkdownViewMode.EDIT.displayName,
        description = "Open Markdown files in the source editor."
    ),
    SPLIT(
        displayName = MarkdownViewMode.SPLIT.displayName,
        description = "Open the source editor and rendered preview side by side."
    ),
    PREVIEW(
        displayName = MarkdownViewMode.PREVIEW.displayName,
        description = "Open Markdown files in the rendered preview."
    ),
    LAST_SELECTED(
        displayName = "Last selected",
        description = "Reuse the most recently selected Markdown view."
    );

    fun resolve(lastSelectedView: MarkdownViewMode): MarkdownViewMode = when (this) {
        EDIT -> MarkdownViewMode.EDIT
        SPLIT -> MarkdownViewMode.SPLIT
        PREVIEW -> MarkdownViewMode.PREVIEW
        LAST_SELECTED -> lastSelectedView
    }
}

data class MarkdownViewSettings(
    val defaultView: MarkdownDefaultView = MarkdownDefaultView.PREVIEW,
    val lastSelectedView: MarkdownViewMode = MarkdownViewMode.PREVIEW
) {
    fun initialViewMode(): MarkdownViewMode = defaultView.resolve(lastSelectedView)
}

/**
 * Persists Markdown view preferences in host-provided plugin-scoped storage.
 *
 * The host routes this storage to the active BOSS data directory, so normal
 * and debug profiles keep independent settings. When storage is unavailable
 * or does not respond before the bounded load timeout, defaults keep the UI
 * usable for the current plugin session.
 */

class MarkdownViewSettingsManager(
    private val storage: PluginStorageProvider?,
    private val loadTimeoutMillis: Long = DEFAULT_LOAD_TIMEOUT_MILLIS
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )
    private val defaultSettings = MarkdownViewSettings()
    private val defaultViewTouched = AtomicBoolean(false)
    private val lastSelectedViewTouched = AtomicBoolean(false)
    private val _settings = MutableStateFlow(defaultSettings)
    private val _isLoaded = MutableStateFlow(storage == null)

    val settings: StateFlow<MarkdownViewSettings> = _settings.asStateFlow()
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    init {
        if (storage != null) {
            scope.launch {
                val loadedSettings = withTimeoutOrNull(loadTimeoutMillis) {
                    try {
                        MarkdownViewSettings(
                            defaultView = storage.getString(DEFAULT_VIEW_KEY)
                                .toEnumOrDefault(defaultSettings.defaultView),
                            lastSelectedView = storage.getString(LAST_SELECTED_VIEW_KEY)
                                .toEnumOrDefault(defaultSettings.lastSelectedView)
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Throwable) {
                        defaultSettings
                    }
                } ?: defaultSettings

                _settings.update { current ->
                    current.copy(
                        defaultView = if (defaultViewTouched.get()) {
                            current.defaultView
                        } else {
                            loadedSettings.defaultView
                        },
                        lastSelectedView = if (lastSelectedViewTouched.get()) {
                            current.lastSelectedView
                        } else {
                            loadedSettings.lastSelectedView
                        }
                    )
                }
                _isLoaded.value = true
            }
        }
    }

    fun setDefaultView(defaultView: MarkdownDefaultView) {
        if (_settings.value.defaultView == defaultView) return

        defaultViewTouched.set(true)
        _settings.update { it.copy(defaultView = defaultView) }
        scope.launch {
            runCatching {
                storage?.putString(DEFAULT_VIEW_KEY, defaultView.name)
            }
        }
    }

    /**
     * Tracks every mode selection so choosing "Last selected" later can reuse
     * the user's actual latest mode, even while a fixed default is configured.
     */
    fun recordSelectedView(viewMode: MarkdownViewMode) {
        if (_settings.value.lastSelectedView == viewMode) return

        lastSelectedViewTouched.set(true)
        _settings.update { it.copy(lastSelectedView = viewMode) }
        scope.launch {
            runCatching {
                storage?.putString(LAST_SELECTED_VIEW_KEY, viewMode.name)
            }
        }
    }

    fun dispose() {
        scope.cancel()
    }

    private inline fun <reified T : Enum<T>> String?.toEnumOrDefault(default: T): T {
        return enumValues<T>().firstOrNull { it.name == this } ?: default
    }

    private companion object {
        const val DEFAULT_LOAD_TIMEOUT_MILLIS = 2_000L
        const val DEFAULT_VIEW_KEY = "markdown.defaultView"
        const val LAST_SELECTED_VIEW_KEY = "markdown.lastSelectedView"
    }
}
