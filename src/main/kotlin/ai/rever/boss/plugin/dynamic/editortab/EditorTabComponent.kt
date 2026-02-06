package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.EditorContentProvider
import ai.rever.boss.plugin.api.FileReadResult
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.api.TabUpdateProviderFactory
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType
import ai.rever.boss.plugin.tab.codeeditor.EditorTabInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Code editor tab component with syntax highlighting and editing capabilities.
 *
 * This component renders a full-featured code editor using the host's
 * EditorContentProvider. It supports file loading/saving, syntax highlighting
 * for multiple languages, and modification tracking.
 */
class EditorTabComponent(
    private val ctx: ComponentContext,
    override val config: TabInfo,
    private val editorContentProvider: EditorContentProvider,
    private val tabUpdateProviderFactory: TabUpdateProviderFactory?
) : TabComponentWithUI, ComponentContext by ctx {

    override val tabTypeInfo: TabTypeInfo = CodeEditorTabType

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Content state
    private val _content = MutableStateFlow("")
    val content: StateFlow<String> = _content

    // Language state
    private val _language = MutableStateFlow("text")
    val language: StateFlow<String> = _language

    // Error state
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        lifecycle.subscribe(
            callbacks = object : Lifecycle.Callbacks {
                override fun onDestroy() {
                    coroutineScope.cancel()
                }
            }
        )

        // Load file content if path is provided
        val editorConfig = config as? EditorTabInfo
        if (editorConfig != null && editorConfig.filePath.isNotEmpty()) {
            loadFile(editorConfig.filePath)
        } else {
            // Default content if no file path
            _content.value = "// New file\n// Start typing...\n"
        }
    }

    private fun loadFile(filePath: String) {
        when (val result = editorContentProvider.readFileContent(filePath)) {
            is FileReadResult.Success -> {
                _content.value = result.content
                _language.value = editorContentProvider.detectLanguage(filePath)
                _error.value = null
            }
            is FileReadResult.FileTooLarge -> {
                _error.value = "File too large (${formatSize(result.sizeBytes)}). Maximum: ${formatSize(result.maxSizeBytes)}"
            }
            is FileReadResult.Error -> {
                _error.value = result.message
            }
            is FileReadResult.FileNotFound -> {
                _error.value = "File not found: $filePath"
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
            bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
            else -> "$bytes bytes"
        }
    }

    @Composable
    override fun Content() {
        // Get tab update provider for this tab
        val tabUpdateProvider = remember(config.id) {
            tabUpdateProviderFactory?.getProvider(config.id)
        }

        // Collect states
        var currentContent by remember { mutableStateOf(_content.value) }
        var currentLanguage by remember { mutableStateOf(_language.value) }
        var currentError by remember { mutableStateOf(_error.value) }
        var isModified by remember { mutableStateOf(false) }

        // Update from state flows
        LaunchedEffect(Unit) {
            _content.collect { currentContent = it }
        }
        LaunchedEffect(Unit) {
            _language.collect { currentLanguage = it }
        }
        LaunchedEffect(Unit) {
            _error.collect { currentError = it }
        }

        // Get file path and project path from config
        val editorConfig = config as? EditorTabInfo
        val filePath = editorConfig?.filePath ?: ""
        val projectPath = filePath.substringBeforeLast('/')

        // Cleanup when component is disposed
        DisposableEffect(config.id) {
            onDispose {
                // Editor cleanup if needed
            }
        }

        // Show error if any
        if (currentError != null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = currentError ?: "",
                    color = Color(0xFFFF6B6B)
                )
            }
            return
        }

        // Render editor content using the provider
        editorContentProvider.CodeEditorContent(
            content = currentContent,
            onContentChange = { newContent ->
                currentContent = newContent
                _content.value = newContent
                if (!isModified) {
                    isModified = true
                }
            },
            language = currentLanguage,
            filePath = filePath,
            projectPath = projectPath,
            modifier = Modifier.fillMaxSize(),
            onModifiedStateChange = { modified ->
                isModified = modified
                // Update tab title to show modification state
                val baseTitle = filePath.substringAfterLast('/').ifEmpty { "Untitled" }
                val displayTitle = if (modified) "$baseTitle *" else baseTitle
                tabUpdateProvider?.updateTitle(displayTitle)
            },
            onSaveRequested = {
                if (filePath.isNotEmpty()) {
                    val success = editorContentProvider.writeFileContent(filePath, currentContent)
                    if (success) {
                        isModified = false
                        // Update tab title to remove modification indicator
                        val baseTitle = filePath.substringAfterLast('/').ifEmpty { "Untitled" }
                        tabUpdateProvider?.updateTitle(baseTitle)
                    }
                    success
                } else {
                    false
                }
            }
        )
    }
}
