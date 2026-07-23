package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.PluginStorageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Controls which mode a newly opened Markdown tab uses.
 */
enum class MarkdownDefaultView(
    val displayName: String,
    val description: String
) {
    EDIT(
        displayName = "Edit",
        description = "Open Markdown files in the source editor."
    ),
    SPLIT(
        displayName = "Split",
        description = "Open the source editor and rendered preview side by side."
    ),
    PREVIEW(
        displayName = "Preview",
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
 * and debug profiles keep independent settings. When storage is unavailable,
 * preferences remain usable for the current plugin session.
 */
class MarkdownViewSettingsManager(
    private val storage: PluginStorageProvider?
) {
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )
    private val defaultSettings = MarkdownViewSettings()
    private val _settings = MutableStateFlow(defaultSettings)
    private val _isLoaded = MutableStateFlow(storage == null)

    val settings: StateFlow<MarkdownViewSettings> = _settings.asStateFlow()
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    init {
        if (storage != null) {
            scope.launch {
                val loadedSettings = runCatching {
                    MarkdownViewSettings(
                        defaultView = storage.getString(DEFAULT_VIEW_KEY)
                            .toEnumOrDefault(defaultSettings.defaultView),
                        lastSelectedView = storage.getString(LAST_SELECTED_VIEW_KEY)
                            .toEnumOrDefault(defaultSettings.lastSelectedView)
                    )
                }.getOrDefault(defaultSettings)

                _settings.compareAndSet(defaultSettings, loadedSettings)
                _isLoaded.value = true
            }
        }
    }

    fun setDefaultView(defaultView: MarkdownDefaultView) {
        if (_settings.value.defaultView == defaultView) return

        _settings.value = _settings.value.copy(defaultView = defaultView)
        scope.launch {
            runCatching {
                storage?.putString(DEFAULT_VIEW_KEY, defaultView.name)
            }
        }
    }

    fun recordSelectedView(viewMode: MarkdownViewMode) {
        if (_settings.value.lastSelectedView == viewMode) return

        _settings.value = _settings.value.copy(lastSelectedView = viewMode)
        scope.launch {
            runCatching {
                storage?.putString(LAST_SELECTED_VIEW_KEY, viewMode.name)
            }
        }
    }

    fun resetToDefaults() {
        _settings.value = defaultSettings
        scope.launch {
            runCatching {
                storage?.putString(DEFAULT_VIEW_KEY, defaultSettings.defaultView.name)
                storage?.putString(LAST_SELECTED_VIEW_KEY, defaultSettings.lastSelectedView.name)
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
        const val DEFAULT_VIEW_KEY = "markdown.defaultView"
        const val LAST_SELECTED_VIEW_KEY = "markdown.lastSelectedView"
    }
}
