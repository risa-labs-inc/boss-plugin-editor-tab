package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.api.TabComponentWithUI
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.bosseditor.compose.BossEditor
import ai.rever.bosseditor.features.UsagesPopup
import ai.rever.bosseditor.features.UsagesPopupState
import ai.rever.bosseditor.features.NavigationFeedbackPopup
import ai.rever.bosseditor.features.NavigationFeedbackState
import ai.rever.bosseditor.features.NavigationFailureReason
import ai.rever.bosseditor.features.SearchManager
import ai.rever.bosseditor.features.SearchOptions
import ai.rever.bosseditor.ui.SearchBar
import ai.rever.bosseditor.ui.GoToLineDialog
import ai.rever.bosseditor.largefile.LargeFileDocument
import ai.rever.bosseditor.largefile.LargeFileLimitationsDialog
import ai.rever.bosseditor.psi.ReferenceLocation
import ai.rever.bosseditor.psi.DefinitionInfo
import ai.rever.bosseditor.refactoring.RefactorContext
import ai.rever.bosseditor.refactoring.RefactorResult
import ai.rever.bosseditor.refactoring.SymbolKind
import ai.rever.bosseditor.refactoring.WorkspaceEditApplier
import ai.rever.bosseditor.refactoring.psi.ExtractVariableRefactoring
import ai.rever.bosseditor.refactoring.psi.ExtractMethodRefactoring
import ai.rever.bosseditor.refactoring.psi.InlineRefactoring
import ai.rever.bosseditor.refactoring.ExtractVariableParams
import ai.rever.bosseditor.refactoring.ExtractMethodParams
import ai.rever.bosseditor.ui.RenameDialog
import ai.rever.bosseditor.ui.ExtractVariableDialog
import ai.rever.bosseditor.ui.ExtractMethodDialog
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import ai.rever.bosseditor.core.EditorPosition
import ai.rever.bosseditor.core.EditorRange
import ai.rever.bosseditor.core.EditorState
import ai.rever.bosseditor.highlight.Token
import ai.rever.bosseditor.highlight.TokenCache
import ai.rever.bosseditor.highlight.TokenType
import ai.rever.bosseditor.highlight.lexers.BaseLexer
import ai.rever.bosseditor.psi.SemanticCache
import ai.rever.bosseditor.psi.SemanticType
import ai.rever.bosseditor.highlight.lexers.KotlinLexer
import ai.rever.bosseditor.highlight.lexers.JavaLexer
import ai.rever.bosseditor.highlight.lexers.JavaScriptLexer
import ai.rever.bosseditor.highlight.lexers.TypeScriptLexer
import ai.rever.bosseditor.highlight.lexers.PythonLexer
import ai.rever.bosseditor.highlight.lexers.GoLexer
import ai.rever.bosseditor.highlight.lexers.RustLexer
import ai.rever.bosseditor.highlight.lexers.SwiftLexer
import ai.rever.bosseditor.highlight.lexers.JsonLexer
import ai.rever.bosseditor.highlight.lexers.YamlLexer
import ai.rever.bosseditor.highlight.lexers.XmlLexer
import ai.rever.bosseditor.highlight.lexers.HtmlLexer
import ai.rever.bosseditor.highlight.lexers.CssLexer
import ai.rever.bosseditor.highlight.lexers.SqlLexer
import ai.rever.bosseditor.highlight.lexers.ShellLexer
import ai.rever.bosseditor.highlight.lexers.MarkdownLexer
import ai.rever.bosseditor.highlight.lexers.TomlLexer
import ai.rever.bosseditor.highlight.lexers.CLexer
import ai.rever.bosseditor.highlight.lexers.CSharpLexer
import ai.rever.bosseditor.highlight.lexers.GroovyLexer
import ai.rever.bosseditor.highlight.lexers.ScalaLexer
import ai.rever.bosseditor.highlight.lexers.RubyLexer
import ai.rever.bosseditor.highlight.lexers.PHPLexer
import ai.rever.bosseditor.highlight.lexers.PerlLexer
import ai.rever.bosseditor.highlight.lexers.LuaLexer
import ai.rever.bosseditor.rendering.EditorToken
import ai.rever.bosseditor.theme.EditorTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.Text
import compose.icons.FeatherIcons
import compose.icons.feathericons.Play
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.reflect.full.memberProperties

/**
 * Code editor tab component with syntax highlighting and editing capabilities.
 *
 * This component renders a full-featured code editor using the BossEditor library
 * from Maven Central. It supports file loading/saving, syntax highlighting
 * for multiple languages, code folding, and modification tracking.
 *
 * **Features match the bundled BOSS editor:**
 * - Settings loaded from ~/.boss/code-editor-settings.json
 * - Theme support (Dark, Light, Dracula, Monokai, Solarized)
 * - Font customization (family, size, ligatures, line spacing)
 * - Status bar with cursor position, language, save status
 * - Minimap (optional)
 * - Advanced toggles (mark occurrences, highlight current line, navigation)
 */
class EditorTabComponent(
    private val ctx: ComponentContext,
    override val config: TabInfo,
    private val context: PluginContext
) : TabComponentWithUI, ComponentContext by ctx {

    override val tabTypeInfo: TabTypeInfo = EditorTabType

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // File path from config - handle both EditorTabData (from plugin) and EditorTabInfo (from host)
    private val filePath: String = extractFilePath(config)
    // Use project path from context if available, otherwise derive from file path
    private val projectPath: String = context.projectPath ?: filePath.substringBeforeLast('/')

    /**
     * Extracts file path from config, handling both:
     * - EditorTabData (dynamic plugin's own class)
     * - EditorTabInfo (host app's bundled class)
     *
     * Uses reflection to access filePath property since the classes are different.
     */
    private fun extractFilePath(config: TabInfo): String {
        // First try our own EditorTabData
        if (config is EditorTabData) {
            return config.filePath
        }

        // Try to get filePath via reflection (for EditorTabInfo from host)
        return try {
            val filePathProperty = config::class.memberProperties.find { it.name == "filePath" }
            filePathProperty?.getter?.call(config) as? String ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    // Initial content and error state
    private var initialContent: String = ""
    private var loadError: String? = null
    private var isLargeFile: Boolean = false
    private var fileSizeBytes: Long = 0L

    // File modification tracking (matches bundled FileModificationTracker)
    private var lastKnownModifiedTime: Long = 0L
    private var originalContent: String = ""

    // Language detection
    private val language: String = detectLanguage(filePath)

    init {
        lifecycle.subscribe(
            callbacks = object : Lifecycle.Callbacks {
                override fun onDestroy() {
                    coroutineScope.cancel()
                }
            }
        )

        // Load file content synchronously during init
        if (filePath.isNotEmpty()) {
            loadFileContent()
        } else {
            initialContent = "// New file\n// Start typing...\n"
        }
    }

    private fun loadFileContent() {
        try {
            val file = File(filePath)
            if (!file.exists()) {
                loadError = "File not found: $filePath"
                return
            }

            fileSizeBytes = file.length()
            lastKnownModifiedTime = file.lastModified()

            // Check if this is a large file (>10MB)
            if (LargeFileDocument.shouldUseLargeFileAdapter(file)) {
                isLargeFile = true
                // For large files, we'll show a dialog and load in read-only mode
                // Load first 1MB for preview
                val previewSize = 1024 * 1024L
                initialContent = if (fileSizeBytes <= previewSize) {
                    file.readText()
                } else {
                    file.inputStream().bufferedReader().use { reader ->
                        val buffer = CharArray(previewSize.toInt())
                        val read = reader.read(buffer)
                        if (read > 0) String(buffer, 0, read) + "\n\n// ... [File truncated - ${formatSize(fileSizeBytes)} total] ..."
                        else ""
                    }
                }
                originalContent = initialContent
                loadError = null
                return
            }

            isLargeFile = false
            initialContent = file.readText()
            originalContent = initialContent
            loadError = null
        } catch (e: Exception) {
            loadError = "Error loading file: ${e.message}"
            System.err.println("[EditorTabComponent] Failed to load file '$filePath': ${e.message}")
        }
    }

    private fun saveFile(content: String): Boolean {
        if (filePath.isEmpty()) return false

        return try {
            val file = File(filePath)
            // Create parent directories if they don't exist (matches bundled editor)
            file.parentFile?.mkdirs()
            file.writeText(content)
            // Update tracking state after successful save
            lastKnownModifiedTime = file.lastModified()
            originalContent = content
            true
        } catch (e: Exception) {
            System.err.println("[EditorTabComponent] Failed to save file '$filePath': ${e.message}")
            false
        }
    }

    /**
     * Checks if the file on disk has been modified externally since we last loaded/saved.
     * Matches bundled FileModificationTracker.hasExternalChanges().
     */
    private suspend fun hasExternalChanges(): Boolean {
        if (filePath.isEmpty()) return false

        return withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) return@withContext false

                // First check modification time (fast check)
                val currentModTime = file.lastModified()
                if (currentModTime == lastKnownModifiedTime) return@withContext false

                // If time changed, verify content actually differs
                val diskContent = file.readText()
                diskContent != originalContent
            } catch (e: Exception) {
                System.err.println("[EditorTabComponent] Error checking external changes: ${e.message}")
                false
            }
        }
    }

    /**
     * Reloads the file content from disk.
     * Matches bundled FileModificationTracker.reload().
     */
    private suspend fun reloadFileFromDisk(): String? {
        if (filePath.isEmpty()) return null

        return withContext(Dispatchers.IO) {
            try {
                val file = File(filePath)
                if (!file.exists()) return@withContext null

                val newContent = file.readText()
                lastKnownModifiedTime = file.lastModified()
                originalContent = newContent
                newContent
            } catch (e: Exception) {
                System.err.println("[EditorTabComponent] Error reloading file: ${e.message}")
                null
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
        val scope = rememberCoroutineScope()

        // Reactive settings - updates automatically when settings file changes (like bundled editor)
        val settings by PluginEditorSettings.settings.collectAsState()

        // Get tab update provider for title updates
        val tabUpdateProviderFactory = context.tabUpdateProviderFactory
        val tabUpdateProvider = remember(config.id) {
            tabUpdateProviderFactory?.createProvider(config.id, EditorTabType.typeId)
        }

        // Show error if any
        if (loadError != null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = loadError ?: "",
                    color = Color(0xFFFF6B6B)
                )
            }
            return
        }

        // Create editor state
        val editorState = remember(filePath) {
            EditorState(initialContent, filePath.ifEmpty { null })
        }

        // Get window ID for filtering navigation events (exactly like bundled editor)
        val windowId = context.windowId ?: ""

        // Get font family from settings (matches bundled editor exactly)
        val composeFontFamily = remember(settings.fontFamily) {
            try {
                FontFamily.Monospace
            } catch (e: Exception) {
                FontFamily.Monospace
            }
        }

        // Calculate line height for scrolling (same calculation as bundled EditorCanvas)
        val textMeasurer = rememberTextMeasurer()
        val lineHeightPx = remember(settings.fontSize, composeFontFamily, settings.lineSpacing) {
            val style = TextStyle(
                fontFamily = composeFontFamily,
                fontSize = settings.fontSize.sp
            )
            textMeasurer.measure("M", style).size.height.toFloat() * settings.lineSpacing
        }

        // Listen for navigation targets (cursor positioning after navigation)
        // Matches bundled BossEditorIntegration exactly
        LaunchedEffect(filePath, editorState, lineHeightPx, windowId) {
            val navProvider = context.navigationTargetProvider ?: return@LaunchedEffect
            navProvider.targets.collect { target ->
                // Only process if this editor is showing the target file and event is for this window
                val isForThisWindow = target.sourceWindowId == windowId
                if (isForThisWindow && target.filePath == filePath && target.line > 0) {
                    try {
                        // Convert 1-based line/column to 0-based EditorPosition
                        val line = (target.line - 1).coerceAtLeast(0)
                        val column = (target.column - 1).coerceAtLeast(0)

                        // Position cursor
                        val position = EditorPosition(line, column)
                        editorState.moveCaret(position)
                        editorState.clearSelection()

                        // Scroll to make the line visible (estimate viewport as 600px)
                        editorState.scrollToLine(line, lineHeightPx, 600f)

                        // Clear replay cache after consumption to avoid re-triggering
                        navProvider.clearCache()
                    } catch (e: Exception) {
                        // Log navigation errors without crashing
                        System.err.println("[EditorTabComponent] Navigation target processing failed: ${e.message}")
                    }
                }
            }
        }

        // Track cursor position for status bar
        var cursorLine by remember { mutableStateOf(1) }
        var cursorColumn by remember { mutableStateOf(1) }

        // Track modification state
        val isModified by editorState.isModified.collectAsState()

        // Track save state
        var isSaving by remember { mutableStateOf(false) }
        var saveError by remember { mutableStateOf<String?>(null) }

        // Search state
        var showSearchBar by remember { mutableStateOf(false) }
        var showReplaceInSearchBar by remember { mutableStateOf(false) }
        val searchManager = remember(editorState.document) { SearchManager(editorState.document) }
        var searchMatches by remember { mutableStateOf<List<EditorRange>>(emptyList()) }
        var currentSearchMatchIndex by remember { mutableStateOf(-1) }

        // Go-to-line dialog state
        var showGoToLineDialog by remember { mutableStateOf(false) }

        // Large file dialog state
        var showLargeFileDialog by remember { mutableStateOf(isLargeFile) }

        // Focus requester for keyboard handling
        val editorFocusRequester = remember { FocusRequester() }

        // State for detected main functions (for run gutter)
        var detectedMainFunctions by remember { mutableStateOf<List<DetectedMainFunction>>(emptyList()) }

        // Detect main functions when content changes
        LaunchedEffect(initialContent, filePath, language) {
            if (filePath.isNotEmpty() && initialContent.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    try {
                        val detected = MainFunctionDetector.detectInContent(initialContent, language, filePath)
                        withContext(Dispatchers.Main) {
                            detectedMainFunctions = detected
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            detectedMainFunctions = emptyList()
                        }
                    }
                }
            } else {
                detectedMainFunctions = emptyList()
            }
        }

        // State for usages popup
        var usagesPopupState by remember { mutableStateOf(UsagesPopupState.Hidden) }

        // State for navigation feedback popup
        var navigationFeedbackState: NavigationFeedbackState by remember { mutableStateOf(NavigationFeedbackState.Hidden) }

        // State for rename dialog
        var showRenameDialog by remember { mutableStateOf(false) }
        var renameSymbolName by remember { mutableStateOf("") }
        var renameSymbolKind by remember { mutableStateOf<SymbolKind?>(null) }
        var renamePosition by remember { mutableStateOf(EditorPosition(0, 0)) }
        var renameDialogOffset by remember { mutableStateOf(IntOffset.Zero) }

        // State for extract variable dialog
        var showExtractVariableDialog by remember { mutableStateOf(false) }
        var extractVariableSuggestedName by remember { mutableStateOf("") }
        var extractVariableExpression by remember { mutableStateOf("") }
        var extractVariableSelection by remember { mutableStateOf<EditorRange?>(null) }

        // State for extract method dialog
        var showExtractMethodDialog by remember { mutableStateOf(false) }
        var extractMethodSuggestedName by remember { mutableStateOf("") }
        var extractMethodCode by remember { mutableStateOf("") }
        var extractMethodSelection by remember { mutableStateOf<EditorRange?>(null) }

        // Create refactoring instances (RenameRefactoring needs NavigationService but we'll use simple text-based rename)
        val extractVariableRefactoring = remember { ExtractVariableRefactoring() }
        val extractMethodRefactoring = remember { ExtractMethodRefactoring() }
        val inlineRefactoring = remember { InlineRefactoring() }

        // Update tab title when modification state changes
        LaunchedEffect(isModified) {
            val baseTitle = filePath.substringAfterLast('/').ifEmpty { "Untitled" }
            val displayTitle = if (isModified) "$baseTitle *" else baseTitle
            tabUpdateProvider?.updateTitle(displayTitle)
        }

        // Create lexer based on language (matches bundled editor exactly)
        val lexer = remember(language) {
            getLexerForLanguage(language.lowercase())
        }

        // Create token cache for multi-line state tracking (only if lexer is available)
        // Matches bundled BossEditorIntegration exactly
        val tokenCache = remember(lexer, editorState.document) {
            lexer?.let { TokenCache(editorState.document, it) }
        }

        // Dispose token cache when composable is disposed
        DisposableEffect(tokenCache) {
            onDispose {
                tokenCache?.dispose()
            }
        }

        // Token provider for lexer-based + semantic syntax highlighting (matches bundled editor exactly)
        // Uses TokenCache for proper multi-line state tracking (block comments, raw strings, etc.)
        // Merges with SemanticCache for PSI-based semantic highlighting (function calls, properties, etc.)
        val tokenProvider: (Int) -> List<EditorToken> = remember(tokenCache, filePath) {
            { lineNumber ->
                // Get lexer-based tokens (cached, handles multi-line state)
                val lexerTokens: List<Token> = tokenCache?.getLineTokens(lineNumber) ?: emptyList()

                // Get semantic tokens from PSI analysis (if available for this file)
                val semanticTokens = getSemanticTokensForLine(editorState.document, filePath, lineNumber)

                // Merge tokens (semantic takes precedence for overlapping ranges)
                val mergedTokens = if (semanticTokens.isNotEmpty()) {
                    mergeTokens(lexerTokens, semanticTokens)
                } else {
                    lexerTokens
                }

                EditorToken.fromTokens(mergedTokens)
            }
        }

        // Get theme from settings (matches bundled editor exactly)
        val editorTheme = remember(settings.themeName) {
            when (settings.themeName) {
                "Light" -> EditorTheme.Light
                "Dracula" -> EditorTheme.Dracula
                "Monokai" -> EditorTheme.Monokai
                "Solarized Dark" -> EditorTheme.SolarizedDark
                "Solarized Light" -> EditorTheme.SolarizedLight
                else -> EditorTheme.Dark
            }
        }

        // Parse minimap custom colors from settings (matches bundled editor exactly)
        val minimapBgColor = remember(settings.minimapBackgroundColor) {
            settings.minimapBackgroundColor?.let { parseHexColor(it) }
        }
        val minimapFgColor = remember(settings.minimapForegroundColor) {
            settings.minimapForegroundColor?.let { parseHexColor(it) }
        }

        // Helper function to perform search and update state
        fun performSearch(query: String, options: SearchOptions) {
            if (query.isEmpty()) {
                searchMatches = emptyList()
                currentSearchMatchIndex = -1
                return
            }
            searchManager.search(query, options)
            // Convert OffsetRange to EditorRange for highlighting
            searchMatches = searchManager.allMatches.map { match ->
                val startPos = editorState.document.offsetToPosition(match.startOffset)
                val endPos = editorState.document.offsetToPosition(match.endOffset)
                EditorRange(startPos, endPos)
            }
            currentSearchMatchIndex = searchManager.currentIndex
        }

        // Helper to navigate to current match
        fun navigateToCurrentMatch() {
            val match = searchManager.currentMatch ?: return
            val pos = editorState.document.offsetToPosition(match.startOffset)
            editorState.moveCaret(EditorPosition(pos.line, pos.column))
            editorState.scrollToLine(pos.line, lineHeightPx, 600f)
            currentSearchMatchIndex = searchManager.currentIndex
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(editorFocusRequester)
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown) {
                        val isMeta = event.isMetaPressed || event.isCtrlPressed
                        when {
                            // Cmd+F or Ctrl+F: Show find
                            isMeta && event.key == Key.F -> {
                                showSearchBar = true
                                showReplaceInSearchBar = false
                                true
                            }
                            // Cmd+H or Ctrl+H: Show find and replace
                            isMeta && event.key == Key.H -> {
                                showSearchBar = true
                                showReplaceInSearchBar = true
                                true
                            }
                            // Cmd+G or Ctrl+G or Cmd+L: Go to line
                            isMeta && (event.key == Key.G || event.key == Key.L) -> {
                                showGoToLineDialog = true
                                true
                            }
                            // Cmd+Y: Redo (alternative to Cmd+Shift+Z)
                            isMeta && event.key == Key.Y -> {
                                editorState.redo()
                                true
                            }
                            // Cmd+S or Ctrl+S: Save file
                            isMeta && event.key == Key.S && !isLargeFile -> {
                                if (editorState.isModified.value) {
                                    scope.launch {
                                        isSaving = true
                                        saveError = null
                                        val content = editorState.document.getText()
                                        val success = withContext(Dispatchers.IO) {
                                            saveFile(content)
                                        }
                                        if (success) {
                                            editorState.markAsSaved()
                                        } else {
                                            saveError = "Failed to save file"
                                        }
                                        isSaving = false
                                    }
                                }
                                true
                            }
                            // F3: Find next
                            event.key == Key.F3 && !event.isShiftPressed -> {
                                if (searchManager.matchCount > 0) {
                                    searchManager.findNext()
                                    navigateToCurrentMatch()
                                }
                                true
                            }
                            // Shift+F3: Find previous
                            event.key == Key.F3 && event.isShiftPressed -> {
                                if (searchManager.matchCount > 0) {
                                    searchManager.findPrevious()
                                    navigateToCurrentMatch()
                                }
                                true
                            }
                            // Escape: Close search bar
                            event.key == Key.Escape && showSearchBar -> {
                                showSearchBar = false
                                searchMatches = emptyList()
                                currentSearchMatchIndex = -1
                                searchManager.clearSearch()
                                true
                            }
                            else -> false
                        }
                    } else false
                }
        ) {
            // Search bar (shown above editor when active)
            if (showSearchBar) {
                SearchBar(
                    showReplace = showReplaceInSearchBar,
                    matchCount = searchManager.matchCount,
                    currentMatchIndex = currentSearchMatchIndex,
                    onSearch = { query, options ->
                        performSearch(query, options)
                    },
                    onFindNext = {
                        searchManager.findNext()
                        navigateToCurrentMatch()
                    },
                    onFindPrevious = {
                        searchManager.findPrevious()
                        navigateToCurrentMatch()
                    },
                    onReplace = { replacement ->
                        if (searchManager.replace(replacement)) {
                            performSearch(searchManager.query, searchManager.options)
                            navigateToCurrentMatch()
                        }
                    },
                    onReplaceAll = { replacement ->
                        searchManager.replaceAll(replacement)
                        searchMatches = emptyList()
                        currentSearchMatchIndex = -1
                    },
                    onClose = {
                        showSearchBar = false
                        searchMatches = emptyList()
                        currentSearchMatchIndex = -1
                        searchManager.clearSearch()
                    }
                )
            }

            // Main editor area with run gutter
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // Run gutter (for detected main functions)
                if (detectedMainFunctions.isNotEmpty()) {
                    EditorRunGutter(
                        detectedMainFunctions = detectedMainFunctions,
                        editorState = editorState,
                        fontSize = settings.fontSize,
                        fontFamily = composeFontFamily,
                        lineSpacing = settings.lineSpacing,
                        onRun = { mainFunc ->
                            // Use runConfigurationDataProvider if available (matches bundled executeDetectedMainFunction exactly)
                            val windowId = context.windowId ?: return@EditorRunGutter
                            context.runConfigurationDataProvider?.let { provider ->
                                scope.launch {
                                    // Find actual project root and generate command (matches bundled editor exactly)
                                    val actualProjectRoot = MainFunctionDetector.findProjectRoot(mainFunc.filePath)
                                    val command = MainFunctionDetector.generateCommand(mainFunc, actualProjectRoot)

                                    // Create config name exactly like bundled editor
                                    val configName = mainFunc.toShortNameWithProject(actualProjectRoot)

                                    // Map language string to LanguageData (matches bundled detected.language)
                                    val langData = mainFunc.toLanguageData()

                                    // Create run configuration exactly like bundled executeDetectedMainFunction
                                    val config = ai.rever.boss.plugin.api.RunConfigurationData(
                                        id = java.util.UUID.randomUUID().toString(),
                                        name = configName,
                                        type = ai.rever.boss.plugin.api.RunConfigurationTypeData.MAIN_FUNCTION,
                                        filePath = mainFunc.filePath,
                                        lineNumber = mainFunc.lineNumber,
                                        language = langData,
                                        command = command,
                                        workingDirectory = actualProjectRoot,
                                        isAutoDetected = true
                                    )

                                    provider.execute(config, windowId)
                                }
                            }
                        },
                        modifier = Modifier
                            .width(28.dp)
                            .fillMaxHeight()
                            .background(editorTheme.colors.gutterBackground)
                    )
                }

                // Editor content
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    // Main editor (matches bundled BossEditorIntegration exactly)
                    BossEditor(
                    state = editorState,
                    modifier = Modifier.fillMaxSize(),
                    theme = editorTheme,
                    fontFamily = composeFontFamily,
                    fontSize = settings.fontSize,
                    lineSpacing = settings.lineSpacing,
                    showLineNumbers = settings.showLineNumbers,
                    highlightCurrentLine = settings.highlightCurrentLine,
                    readOnly = isLargeFile, // Large files are read-only
                    filePath = filePath,
                    projectPath = projectPath,
                    showMinimap = settings.showMinimap,
                    minimapWidth = settings.minimapWidth,
                    minimapUseEditorColors = settings.minimapUseEditorColors,
                    minimapBackgroundColor = minimapBgColor,
                    minimapForegroundColor = minimapFgColor,
                    tokenProvider = tokenProvider,
                    searchMatches = searchMatches,
                    currentSearchMatchIndex = currentSearchMatchIndex,
                    // Don't use custom navigationResolver - let BossEditor use internal NavigationManager
                    // which has ShowUsages support for clicking on definitions
                    navigationResolver = null,
                    onTextChanged = {
                        // Text change is automatically tracked by EditorState.isModified
                    },
                    onCaretPositionChanged = { position ->
                        // Convert to 1-based line/column for compatibility
                        cursorLine = position.line + 1
                        cursorColumn = position.column + 1
                    },
                    onSelectionChanged = { _ ->
                        // Selection changed - could integrate with mark occurrences
                    },
                    onNavigate = { navFilePath, line, column ->
                        // Use openFileAtPosition for ALL navigation (same as bundled editor)
                        // This ensures consistent behavior through FileEventBus → NavigationTargetBus
                        val fileName = navFilePath.substringAfterLast('/')
                        context.splitViewOperations?.openFileAtPosition(navFilePath, fileName, line, column)
                    },
                    onShowUsages = { references, definition, clickPosition ->
                        usagesPopupState = UsagesPopupState(
                            isVisible = true,
                            references = references,
                            definition = definition,
                            anchorOffset = IntOffset(clickPosition.x.toInt(), clickPosition.y.toInt())
                        )
                    },
                    onNavigationFailed = { reason, clickPosition ->
                        navigationFeedbackState = NavigationFeedbackState.Visible(
                            reason = reason,
                            anchorOffset = IntOffset(clickPosition.x.toInt(), clickPosition.y.toInt())
                        )
                    },
                    onRename = {
                        // Get current position and extract word at cursor for rename
                        val position = editorState.caretPosition.value
                        renamePosition = position

                        // Only support Kotlin files for now
                        if (filePath.endsWith(".kt") || filePath.endsWith(".kts")) {
                            // Extract word at cursor position
                            val lineStart = editorState.document.getLineStartOffset(position.line)
                            val lineEnd = editorState.document.getLineEndOffset(position.line)
                            val lineText = editorState.document.getText(lineStart, lineEnd)
                            val wordAtCursor = extractWordAtPosition(lineText, position.column)

                            if (wordAtCursor.isNotEmpty()) {
                                renameSymbolName = wordAtCursor
                                renameSymbolKind = null // We don't know the kind without PSI

                                // Calculate dialog position near the caret
                                val viewport = editorState.visibleViewport.value
                                val lineHeight = viewport.lineHeight.takeIf { it > 0 } ?: 20f
                                val charWidth = viewport.charWidth.takeIf { it > 0 } ?: 8f
                                val firstVisibleLine = viewport.firstVisibleLine
                                val gutterWidth = 60
                                val visualY = ((position.line - firstVisibleLine) * lineHeight).toInt()
                                val visualX = (position.column * charWidth).toInt() + gutterWidth
                                renameDialogOffset = IntOffset(visualX, visualY)
                                showRenameDialog = true
                            }
                        }
                    },
                    onExtractVariable = {
                        // Get current selection for extract variable
                        val selection = editorState.selection.value
                        if (selection == null || selection.isEmpty) {
                            return@BossEditor
                        }

                        val selectedText = editorState.document.getText(
                            editorState.document.positionToOffset(selection.start.line, selection.start.column),
                            editorState.document.positionToOffset(selection.end.line, selection.end.column)
                        )

                        extractVariableSelection = selection
                        extractVariableExpression = selectedText

                        // Launch coroutine to get suggested name
                        scope.launch(Dispatchers.IO) {
                            try {
                                if (filePath.endsWith(".kt") || filePath.endsWith(".kts")) {
                                    val context = RefactorContext(
                                        fileUri = WorkspaceEditApplier.filePathToUri(filePath),
                                        filePath = filePath,
                                        position = selection.start,
                                        selection = selection
                                    )
                                    val suggestedName = extractVariableRefactoring.suggestVariableName(context)

                                    withContext(Dispatchers.Main) {
                                        extractVariableSuggestedName = suggestedName
                                        showExtractVariableDialog = true
                                    }
                                }
                            } catch (e: Exception) {
                                System.err.println("[EditorTabComponent] Extract variable failed: ${e.message}")
                            }
                        }
                    },
                    onExtractMethod = {
                        // Check for selection and execute extract method
                        scope.launch(Dispatchers.IO) {
                            try {
                                val selection = editorState.selection.value
                                if (selection == null || selection.isEmpty) {
                                    return@launch
                                }

                                // Store the selection and selected code
                                extractMethodSelection = selection

                                // Get the selected text from the document
                                val document = editorState.document
                                val startOffset = document.positionToOffset(selection.start)
                                val endOffset = document.positionToOffset(selection.end)
                                val selectedText = document.getText(startOffset, endOffset)
                                extractMethodCode = selectedText

                                // Only support Kotlin files for now
                                if (filePath.endsWith(".kt") || filePath.endsWith(".kts")) {
                                    val context = RefactorContext(
                                        fileUri = WorkspaceEditApplier.filePathToUri(filePath),
                                        filePath = filePath,
                                        position = selection.start,
                                        selection = selection
                                    )
                                    val suggestedName = extractMethodRefactoring.suggestMethodName(context)

                                    withContext(Dispatchers.Main) {
                                        extractMethodSuggestedName = suggestedName
                                        showExtractMethodDialog = true
                                    }
                                }
                            } catch (e: Exception) {
                                System.err.println("[EditorTabComponent] Extract method failed: ${e.message}")
                            }
                        }
                    },
                    onInline = {
                        // Execute inline refactoring at current position
                        scope.launch(Dispatchers.IO) {
                            try {
                                // Only support Kotlin files for now
                                if (filePath.endsWith(".kt") || filePath.endsWith(".kts")) {
                                    val position = editorState.caretPosition.value
                                    val context = RefactorContext(
                                        fileUri = WorkspaceEditApplier.filePathToUri(filePath),
                                        filePath = filePath,
                                        position = position,
                                        selection = null
                                    )

                                    val result = inlineRefactoring.execute(context)
                                    when (result) {
                                        is RefactorResult.Success -> {
                                            // Apply the workspace edit
                                            val applier = WorkspaceEditApplier(
                                                documentProvider = { uri ->
                                                    val targetPath = WorkspaceEditApplier.uriToFilePath(uri)
                                                    if (targetPath == filePath) editorState.document else null
                                                },
                                                undoManagerProvider = { uri ->
                                                    val targetPath = WorkspaceEditApplier.uriToFilePath(uri)
                                                    if (targetPath == filePath) editorState.undoManager else null
                                                },
                                                onFileModified = { _ -> }
                                            )
                                            applier.apply(result.edit)
                                        }
                                        is RefactorResult.Error -> {
                                            // Could show error notification
                                        }
                                        else -> { }
                                    }
                                }
                            } catch (e: Exception) {
                                System.err.println("[EditorTabComponent] Inline refactoring failed: ${e.message}")
                            }
                        }
                    }
                )

                // Usages popup overlay (exactly like bundled editor)
                if (usagesPopupState.isVisible && usagesPopupState.definition != null) {
                    UsagesPopup(
                        references = usagesPopupState.references,
                        definition = usagesPopupState.definition!!,
                        anchorOffset = usagesPopupState.anchorOffset,
                        onNavigate = { navFilePath, navLine, navColumn ->
                            // Use openFileAtPosition for ALL navigation (same as bundled editor)
                            // This ensures consistent behavior through FileEventBus → NavigationTargetBus
                            // The cursor positioning is handled by the LaunchedEffect listening to NavigationTargetProvider
                            val fileName = navFilePath.substringAfterLast('/')
                            context.splitViewOperations?.openFileAtPosition(navFilePath, fileName, navLine, navColumn)
                        },
                        onDismiss = {
                            usagesPopupState = UsagesPopupState.Hidden
                        },
                        theme = editorTheme
                    )
                }

                // Navigation feedback popup (when navigation fails)
                val feedbackState = navigationFeedbackState
                if (feedbackState is NavigationFeedbackState.Visible) {
                    NavigationFeedbackPopup(
                        reason = feedbackState.reason,
                        anchorOffset = feedbackState.anchorOffset,
                        onDismiss = {
                            navigationFeedbackState = NavigationFeedbackState.Hidden
                        },
                        theme = editorTheme
                    )
                }

                // Rename dialog
                if (showRenameDialog && renameSymbolName.isNotEmpty()) {
                    RenameDialog(
                        currentName = renameSymbolName,
                        symbolKind = renameSymbolKind,
                        anchorOffset = renameDialogOffset,
                        onRename = { newName ->
                            showRenameDialog = false
                            // Execute simple text-based rename (replace all occurrences)
                            try {
                                val currentContent = editorState.document.getText()
                                // Use word boundary matching to avoid partial replacements
                                val pattern = "\\b${Regex.escape(renameSymbolName)}\\b".toRegex()
                                val newContent = pattern.replace(currentContent, newName)
                                if (newContent != currentContent) {
                                    editorState.document.setText(newContent)
                                }
                            } catch (e: Exception) {
                                System.err.println("[EditorTabComponent] Rename symbol failed: ${e.message}")
                            }
                        },
                        onCancel = {
                            showRenameDialog = false
                        },
                        onValidate = { newName ->
                            // Simple validation for identifier names
                            when {
                                newName.isBlank() -> "Name cannot be empty"
                                !newName.first().isLetter() && newName.first() != '_' -> "Name must start with a letter or underscore"
                                !newName.all { it.isLetterOrDigit() || it == '_' } -> "Name contains invalid characters"
                                newName == renameSymbolName -> "Name is unchanged"
                                else -> null
                            }
                        }
                    )
                }

                // Extract variable dialog
                if (showExtractVariableDialog && extractVariableExpression.isNotEmpty()) {
                    ExtractVariableDialog(
                        suggestedName = extractVariableSuggestedName,
                        selectedExpression = extractVariableExpression,
                        onExtract = { variableName, replaceAll, isVal ->
                            showExtractVariableDialog = false
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val selection = extractVariableSelection
                                    if (selection == null) return@launch

                                    val refactorContext = RefactorContext(
                                        fileUri = WorkspaceEditApplier.filePathToUri(filePath),
                                        filePath = filePath,
                                        position = selection.start,
                                        selection = selection
                                    )

                                    val params = ExtractVariableParams(
                                        variableName = variableName,
                                        replaceAll = replaceAll,
                                        isVal = isVal
                                    )

                                    val result = extractVariableRefactoring.execute(refactorContext, params)

                                    when (result) {
                                        is RefactorResult.Success -> {
                                            val applier = WorkspaceEditApplier(
                                                documentProvider = { uri ->
                                                    val targetPath = WorkspaceEditApplier.uriToFilePath(uri)
                                                    if (targetPath == filePath) editorState.document else null
                                                },
                                                undoManagerProvider = { uri ->
                                                    val targetPath = WorkspaceEditApplier.uriToFilePath(uri)
                                                    if (targetPath == filePath) editorState.undoManager else null
                                                },
                                                onFileModified = { _ -> }
                                            )
                                            applier.apply(result.edit)
                                        }
                                        is RefactorResult.Error -> { }
                                        else -> { }
                                    }
                                } catch (e: Exception) {
                                    System.err.println("[EditorTabComponent] Extract variable apply failed: ${e.message}")
                                }
                            }
                        },
                        onCancel = {
                            showExtractVariableDialog = false
                        },
                        onValidate = { variableName ->
                            when {
                                variableName.isBlank() -> "Variable name cannot be empty"
                                !variableName.first().isLetter() && variableName.first() != '_' -> "Variable name must start with a letter or underscore"
                                !variableName.all { it.isLetterOrDigit() || it == '_' } -> "Variable name contains invalid characters"
                                else -> null
                            }
                        }
                    )
                }

                // Extract method dialog
                if (showExtractMethodDialog && extractMethodCode.isNotEmpty()) {
                    ExtractMethodDialog(
                        suggestedName = extractMethodSuggestedName,
                        selectedCode = extractMethodCode,
                        onExtract = { methodName, visibility, makeStatic ->
                            showExtractMethodDialog = false
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val selection = extractMethodSelection
                                    if (selection == null) return@launch

                                    val refactorContext = RefactorContext(
                                        fileUri = WorkspaceEditApplier.filePathToUri(filePath),
                                        filePath = filePath,
                                        position = selection.start,
                                        selection = selection
                                    )

                                    val params = ExtractMethodParams(
                                        methodName = methodName,
                                        visibility = visibility,
                                        makeStatic = makeStatic
                                    )

                                    val result = extractMethodRefactoring.execute(refactorContext, params)
                                    when (result) {
                                        is RefactorResult.Success -> {
                                            val applier = WorkspaceEditApplier(
                                                documentProvider = { uri ->
                                                    val targetPath = WorkspaceEditApplier.uriToFilePath(uri)
                                                    if (targetPath == filePath) editorState.document else null
                                                },
                                                undoManagerProvider = { uri ->
                                                    val targetPath = WorkspaceEditApplier.uriToFilePath(uri)
                                                    if (targetPath == filePath) editorState.undoManager else null
                                                },
                                                onFileModified = { _ -> }
                                            )
                                            applier.apply(result.edit)
                                        }
                                        is RefactorResult.Error -> { }
                                        else -> { }
                                    }
                                } catch (e: Exception) {
                                    System.err.println("[EditorTabComponent] Extract method apply failed: ${e.message}")
                                }
                            }
                        },
                        onCancel = {
                            showExtractMethodDialog = false
                        },
                        onValidate = { methodName ->
                            when {
                                methodName.isBlank() -> "Method name cannot be empty"
                                !methodName.first().isLetter() && methodName.first() != '_' -> "Method name must start with a letter or underscore"
                                !methodName.all { it.isLetterOrDigit() || it == '_' } -> "Method name contains invalid characters"
                                else -> null
                            }
                        }
                    )
                }

                // Go to line dialog
                if (showGoToLineDialog) {
                    GoToLineDialog(
                        currentLine = cursorLine,
                        totalLines = editorState.document.lineCount,
                        currentColumn = cursorColumn,
                        onGoTo = { line, column ->
                            // Convert to 0-based
                            val targetLine = (line - 1).coerceAtLeast(0)
                            val targetColumn = (column - 1).coerceAtLeast(0)
                            editorState.moveCaret(EditorPosition(targetLine, targetColumn))
                            editorState.scrollToLine(targetLine, lineHeightPx, 600f)
                            showGoToLineDialog = false
                        },
                        onDismiss = {
                            showGoToLineDialog = false
                        }
                    )
                }

                // Large file limitations dialog
                if (showLargeFileDialog && isLargeFile) {
                    LargeFileLimitationsDialog(
                        fileName = filePath.substringAfterLast('/'),
                        fileSize = formatSize(fileSizeBytes),
                        onDismiss = {
                            showLargeFileDialog = false
                        },
                        onOpenInEditor = {
                            // Already showing in editor (read-only mode)
                            showLargeFileDialog = false
                        }
                    )
                }

            }  // End Box
            }  // End Row

            // Status bar (matches bundled editor)
            EditorStatusBar(
                filePath = filePath,
                language = language,
                line = cursorLine,
                column = cursorColumn,
                isModified = isModified,
                isSaving = isSaving,
                error = saveError
            )
        }
    }

    companion object {
        /**
         * Extracts the word (identifier) at the given column position in a line.
         */
        private fun extractWordAtPosition(line: String, column: Int): String {
            if (line.isEmpty() || column < 0 || column > line.length) return ""

            // Find the start of the word
            var start = column
            while (start > 0 && (line[start - 1].isLetterOrDigit() || line[start - 1] == '_')) {
                start--
            }

            // Find the end of the word
            var end = column
            while (end < line.length && (line[end].isLetterOrDigit() || line[end] == '_')) {
                end++
            }

            return if (start < end) line.substring(start, end) else ""
        }

        /**
         * Detects the programming language from file extension.
         */
        private fun detectLanguage(filePath: String): String {
            val extension = filePath.substringAfterLast('.', "").lowercase()
            return when (extension) {
                "kt", "kts" -> "kotlin"
                "java" -> "java"
                "js", "jsx", "mjs", "cjs" -> "javascript"
                "ts", "tsx" -> "typescript"
                "py", "pyw" -> "python"
                "json" -> "json"
                "xml" -> "xml"
                "html", "htm" -> "html"
                "css", "scss", "sass" -> "css"
                "md", "markdown" -> "markdown"
                "toml" -> "toml"
                "gradle" -> "groovy"
                "swift" -> "swift"
                "c", "h" -> "c"
                "cpp", "cc", "cxx", "hpp" -> "cpp"
                "cs" -> "csharp"
                "rs" -> "rust"
                "go" -> "go"
                "rb" -> "ruby"
                "php" -> "php"
                "pl", "pm" -> "perl"
                "lua" -> "lua"
                "sh", "bash", "zsh" -> "shell"
                "yml", "yaml" -> "yaml"
                "sql" -> "sql"
                "r" -> "r"
                "scala" -> "scala"
                else -> "text"
            }
        }

        /**
         * Returns the appropriate lexer for the given language.
         * Matches bundled editor's getLexerForLanguage exactly.
         */
        private fun getLexerForLanguage(language: String): BaseLexer? {
            return when (language) {
                // Kotlin
                "kotlin", "kt", "kts" -> KotlinLexer()

                // Java
                "java" -> JavaLexer()

                // JavaScript
                "javascript", "js", "jsx", "mjs", "cjs" -> JavaScriptLexer()

                // TypeScript
                "typescript", "ts", "tsx", "mts", "cts" -> TypeScriptLexer()

                // Python
                "python", "py", "pyw", "pyi", "pyx" -> PythonLexer()

                // JSON
                "json", "jsonc", "json5" -> JsonLexer()

                // XML
                "xml", "pom", "fxml", "xsd", "xsl", "xslt" -> XmlLexer()

                // HTML
                "html", "htm", "xhtml" -> HtmlLexer()

                // CSS
                "css", "scss", "sass", "less" -> CssLexer()

                // YAML
                "yaml", "yml" -> YamlLexer()

                // Markdown
                "markdown", "md", "mdx" -> MarkdownLexer()

                // Shell
                "shell", "sh", "bash", "zsh", "fish" -> ShellLexer()

                // SQL
                "sql", "mysql", "postgresql", "sqlite" -> SqlLexer()

                // Go
                "go", "golang" -> GoLexer()

                // Rust
                "rust", "rs" -> RustLexer()

                // Swift
                "swift" -> SwiftLexer()

                // C/C++
                "c", "h" -> CLexer()
                "cpp", "cc", "cxx", "hpp", "hxx", "c++" -> CLexer()

                // C#
                "csharp", "cs" -> CSharpLexer()

                // Groovy
                "groovy", "gradle", "gvy", "gy", "gsh" -> GroovyLexer()

                // Scala
                "scala", "sc" -> ScalaLexer()

                // Ruby
                "ruby", "rb", "erb", "rake" -> RubyLexer()

                // PHP
                "php", "php3", "php4", "php5", "phtml" -> PHPLexer()

                // Perl
                "perl", "pl", "pm", "pod" -> PerlLexer()

                // Lua
                "lua" -> LuaLexer()

                // TOML
                "toml" -> TomlLexer()

                // Unknown
                else -> null
            }
        }
    }
}

/**
 * Status bar for the editor showing file info, cursor position, and save status.
 * Matches the bundled BOSS editor status bar exactly.
 */
@Composable
private fun EditorStatusBar(
    filePath: String,
    language: String,
    line: Int,
    column: Int,
    isModified: Boolean,
    isSaving: Boolean,
    error: String?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(Color(0xFF_007ACC).copy(alpha = 0.8f))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Left: File info
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // File name with modification indicator
            val fileName = filePath.substringAfterLast('/').ifEmpty { "Untitled" }
            Text(
                text = if (isModified) "$fileName *" else fileName,
                color = Color.White,
                fontSize = 12.sp
            )

            // Language
            Text(
                text = language.uppercase(),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 11.sp
            )
        }

        // Right: Cursor position and status
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Error message
            if (error != null) {
                Text(
                    text = error,
                    color = Color(0xFF_FF6B6B),
                    fontSize = 11.sp
                )
            }

            // Saving indicator
            if (isSaving) {
                Text(
                    text = "Saving...",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp
                )
            }

            // Cursor position
            Text(
                text = "Ln $line, Col $column",
                color = Color.White,
                fontSize = 12.sp
            )
        }
    }
}

// ========== Settings ==========

/**
 * Settings data class matching the bosseditor EditorSettings format exactly.
 * This ensures compatibility with ~/.boss/editor-settings.json
 */
@Serializable
data class PluginEditorSettingsData(
    // Visual Settings
    val fontFamily: String? = null,
    val fontSize: Float = 14f,
    val lineSpacing: Float = 1.2f,
    val themeName: String = "Dark",
    val showLineNumbers: Boolean = true,
    val highlightCurrentLine: Boolean = true,
    // Behavior Settings
    val scrollSpeed: Float = 1.5f,
    val tabSize: Int = 4,
    val useSpacesForTabs: Boolean = true,
    val wordWrap: Boolean = false,
    // Feature Settings
    val foldingEnabled: Boolean = true,
    val rainbowBracketsEnabled: Boolean = true,
    val indentGuidesEnabled: Boolean = true,
    val bracketMatchingEnabled: Boolean = true,
    val markOccurrencesEnabled: Boolean = true,
    // Caret Settings
    val caretBlinkRate: Int = 530,
    val caretStyle: String = "line",
    // Minimap Settings
    val showMinimap: Boolean = true,
    val minimapWidth: Int = 80,
    val minimapUseEditorColors: Boolean = true,
    val minimapBackgroundColor: String? = null,
    val minimapForegroundColor: String? = null
)

/**
 * Reactive settings manager that reads from ~/.boss/editor-settings.json
 * (the same file used by the bosseditor library).
 *
 * Provides a StateFlow that updates when settings change, matching the
 * bundled editor's EditorSettingsManager behavior.
 */
object PluginEditorSettings {
    private val settingsFile = File(System.getProperty("user.home"), ".boss/editor-settings.json")
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val _settings = kotlinx.coroutines.flow.MutableStateFlow(loadFromFile())
    val settings: kotlinx.coroutines.flow.StateFlow<PluginEditorSettingsData> = _settings

    private var lastModified: Long = settingsFile.lastModified()

    init {
        // Start file watcher in background
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (true) {
                kotlinx.coroutines.delay(500) // Check every 500ms
                try {
                    val currentModified = settingsFile.lastModified()
                    if (currentModified != lastModified) {
                        lastModified = currentModified
                        val newSettings = loadFromFile()
                        _settings.value = newSettings
                    }
                } catch (e: Exception) {
                    // Ignore errors during file watch
                }
            }
        }
    }

    private fun loadFromFile(): PluginEditorSettingsData {
        return try {
            if (settingsFile.exists()) {
                val content = settingsFile.readText()
                json.decodeFromString<PluginEditorSettingsData>(content)
            } else {
                PluginEditorSettingsData()
            }
        } catch (e: Exception) {
            PluginEditorSettingsData()
        }
    }
}

// ========== Color Parsing Helper ==========

/**
 * Parses a hex color string (ARGB format like "FF1E1F22") to a Compose Color.
 * Returns null if the string is invalid.
 * Matches bundled BossEditorIntegration exactly.
 */
private fun parseHexColor(hex: String): Color? {
    return try {
        val cleanHex = hex.removePrefix("#").removePrefix("0x")
        when (cleanHex.length) {
            6 -> {
                // RGB format - add full alpha
                val color = cleanHex.toLong(16)
                Color(
                    red = ((color shr 16) and 0xFF).toInt() / 255f,
                    green = ((color shr 8) and 0xFF).toInt() / 255f,
                    blue = (color and 0xFF).toInt() / 255f,
                    alpha = 1f
                )
            }
            8 -> {
                // ARGB format
                val color = cleanHex.toLong(16)
                Color(
                    alpha = ((color shr 24) and 0xFF).toInt() / 255f,
                    red = ((color shr 16) and 0xFF).toInt() / 255f,
                    green = ((color shr 8) and 0xFF).toInt() / 255f,
                    blue = (color and 0xFF).toInt() / 255f
                )
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

// ========== Semantic Highlighting Helpers ==========
// These functions are copied from BossEditorIntegration.kt to provide
// the same PSI-based semantic highlighting as the bundled editor.

/**
 * Gets semantic tokens for a specific line from the PSI-based SemanticCache.
 *
 * @param document The editor document
 * @param filePath The current file path
 * @param lineNumber The line number (0-based)
 * @return List of semantic tokens for the line, empty if not available
 */
private fun getSemanticTokensForLine(
    document: ai.rever.bosseditor.core.EditorDocument,
    filePath: String,
    lineNumber: Int
): List<Token> {
    // Only Kotlin files have semantic highlighting
    if (!filePath.endsWith(".kt") && !filePath.endsWith(".kts")) {
        return emptyList()
    }

    // Get all semantic elements for this file from the cache
    val allElements = SemanticCache.get(filePath) ?: return emptyList()
    if (allElements.isEmpty()) return emptyList()

    // Get the line range in the document
    if (lineNumber < 0 || lineNumber >= document.lineCount) {
        return emptyList()
    }
    val lineStart = document.getLineStartOffset(lineNumber)
    val lineEnd = document.getLineEndOffset(lineNumber)

    // Filter elements that fall within this line
    val lineElements = allElements.filter { element ->
        element.startOffset >= lineStart && element.endOffset <= lineEnd
    }

    if (lineElements.isEmpty()) return emptyList()

    // Convert SemanticElements to Tokens with line-relative offsets
    return lineElements.map { element ->
        Token(
            startOffset = element.startOffset - lineStart,
            endOffset = element.endOffset - lineStart,
            type = mapSemanticType(element.type)
        )
    }.sortedBy { it.startOffset }
}

/**
 * Maps PSI SemanticType to BossEditor TokenType.
 */
private fun mapSemanticType(type: SemanticType): TokenType = when (type) {
    SemanticType.FUNCTION_CALL -> TokenType.FUNCTION_CALL
    SemanticType.PROPERTY_ACCESS -> TokenType.PROPERTY
    SemanticType.CLASS_REFERENCE -> TokenType.TYPE
    SemanticType.OBJECT_REFERENCE -> TokenType.VARIABLE
    SemanticType.PARAMETER -> TokenType.PARAMETER
    SemanticType.LOCAL_VARIABLE -> TokenType.LOCAL_VARIABLE
    SemanticType.ANNOTATION -> TokenType.ANNOTATION
    SemanticType.LABEL -> TokenType.LABEL
    SemanticType.TYPE_PARAMETER -> TokenType.TYPE_PARAMETER
}

/**
 * Merges lexer tokens with semantic tokens, where semantic tokens take precedence.
 *
 * @param base Lexer-based tokens (always available)
 * @param overlay Semantic tokens from PSI analysis
 * @return Merged token list
 */
private fun mergeTokens(base: List<Token>, overlay: List<Token>): List<Token> {
    if (base.isEmpty()) return overlay
    if (overlay.isEmpty()) return base

    val result = mutableListOf<Token>()
    var baseIndex = 0
    var overlayIndex = 0

    while (baseIndex < base.size || overlayIndex < overlay.size) {
        // If no more overlay tokens, add remaining base tokens
        if (overlayIndex >= overlay.size) {
            result.addAll(base.subList(baseIndex, base.size))
            break
        }

        // If no more base tokens, add remaining overlay tokens
        if (baseIndex >= base.size) {
            result.addAll(overlay.subList(overlayIndex, overlay.size))
            break
        }

        val baseToken = base[baseIndex]
        val overlayToken = overlay[overlayIndex]

        when {
            // Base token comes completely before overlay - keep it
            baseToken.endOffset <= overlayToken.startOffset -> {
                result.add(baseToken)
                baseIndex++
            }

            // Overlay token comes completely before base - add it
            overlayToken.endOffset <= baseToken.startOffset -> {
                result.add(overlayToken)
                overlayIndex++
            }

            // Tokens overlap - overlay takes precedence
            else -> {
                // Add part of base before overlay (if any)
                if (baseToken.startOffset < overlayToken.startOffset) {
                    result.add(
                        Token(
                            baseToken.startOffset,
                            overlayToken.startOffset,
                            baseToken.type,
                            baseToken.modifiers
                        )
                    )
                }

                // Add overlay token
                result.add(overlayToken)

                // Handle remaining part of base token
                if (baseToken.endOffset > overlayToken.endOffset) {
                    // Create remaining part after overlay
                    val remaining = Token(
                        overlayToken.endOffset,
                        baseToken.endOffset,
                        baseToken.type,
                        baseToken.modifiers
                    )
                    overlayIndex++
                    // Check if remaining part overlaps with next overlay
                    if (overlayIndex < overlay.size &&
                        remaining.startOffset < overlay[overlayIndex].startOffset
                    ) {
                        val nextOverlay = overlay[overlayIndex]
                        if (remaining.endOffset <= nextOverlay.startOffset) {
                            result.add(remaining)
                        } else {
                            result.add(
                                Token(
                                    remaining.startOffset,
                                    nextOverlay.startOffset,
                                    remaining.type,
                                    remaining.modifiers
                                )
                            )
                        }
                    } else if (overlayIndex >= overlay.size) {
                        result.add(remaining)
                    }
                    baseIndex++
                } else {
                    // Base token completely covered by overlay
                    baseIndex++
                    if (overlayToken.endOffset >= base.getOrNull(baseIndex)?.startOffset ?: Int.MAX_VALUE) {
                        // Overlay covers next base token too
                    } else {
                        overlayIndex++
                    }
                }
            }
        }
    }

    return result.sortedBy { it.startOffset }
}

// ========== Run Gutter ==========

/**
 * Represents a detected main function in the code.
 * Matches the bundled DetectedMainFunction structure exactly.
 */
data class DetectedMainFunction(
    val lineNumber: Int,        // 0-based line number
    val functionName: String,   // Name of the function (e.g., "main")
    val filePath: String,       // Path to the file
    val language: String,       // Programming language
    val packageName: String? = null,  // Package name (for Kotlin/Java/Go)
    val className: String? = null     // Class name (for Java)
) {
    /**
     * Creates a display name for this detected function.
     */
    fun toDisplayName(): String {
        return when {
            className != null && packageName != null -> "$packageName.$className.$functionName"
            className != null -> "$className.$functionName"
            packageName != null -> "$packageName.$functionName"
            else -> functionName
        }
    }

    /**
     * Creates a short display name for UI.
     * Matches bundled DetectedMainFunction.toShortName() exactly.
     */
    fun toShortName(): String {
        val fileName = filePath.substringAfterLast('/')
        return when {
            className != null -> "$className.$functionName ($fileName)"
            else -> "$functionName ($fileName)"
        }
    }

    /**
     * Creates a short name with project info.
     * Matches bundled DetectedMainFunction.toShortNameWithProject() exactly.
     */
    fun toShortNameWithProject(projectRoot: String?): String {
        val fileName = filePath.substringAfterLast('/')
        val projectName = projectRoot?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

        val nameWithFile = when {
            className != null -> "$className.$functionName"
            else -> functionName
        }

        return if (projectName != null) {
            "$nameWithFile ($fileName [$projectName])"
        } else {
            "$nameWithFile ($fileName)"
        }
    }

    /**
     * Converts the language string to LanguageData enum.
     * Matches bundled DetectedMainFunction.language (Language enum).
     */
    fun toLanguageData(): ai.rever.boss.plugin.api.LanguageData {
        return when (language.lowercase()) {
            "kotlin", "kt", "kts" -> ai.rever.boss.plugin.api.LanguageData.KOTLIN
            "java" -> ai.rever.boss.plugin.api.LanguageData.JAVA
            "python", "py" -> ai.rever.boss.plugin.api.LanguageData.PYTHON
            "javascript", "js", "jsx", "mjs" -> ai.rever.boss.plugin.api.LanguageData.JAVASCRIPT
            "typescript", "ts", "tsx" -> ai.rever.boss.plugin.api.LanguageData.TYPESCRIPT
            "go", "golang" -> ai.rever.boss.plugin.api.LanguageData.GO
            "rust", "rs" -> ai.rever.boss.plugin.api.LanguageData.RUST
            else -> ai.rever.boss.plugin.api.LanguageData.UNKNOWN
        }
    }
}

/**
 * Main function detector and command generator.
 * Matches the bundled DesktopMainFunctionDetector logic exactly.
 */
object MainFunctionDetector {

    /**
     * Whether we're running on Windows.
     */
    private val isWindows: Boolean = System.getProperty("os.name").lowercase().contains("windows")

    /**
     * Command separator for chaining commands.
     * - Unix/macOS/Linux: && (run second command only if first succeeds)
     * - Windows PowerShell: ; (sequential execution)
     */
    private val commandSeparator: String = if (isWindows) "; " else " && "

    /**
     * Escape a string for safe use in shell commands.
     * Uses single quotes and escapes embedded single quotes with '\''
     * This prevents command injection attacks from malicious file paths.
     * Matches bundled DesktopMainFunctionDetector.shellEscape exactly.
     */
    private fun shellEscape(str: String): String {
        // Single quotes prevent all shell expansion except for single quotes themselves
        // To include a single quote: end the string, add escaped quote, start new string
        // e.g., "it's" becomes 'it'\''s'
        return "'" + str.replace("'", "'\\''") + "'"
    }

    /**
     * Chain multiple commands together using platform-appropriate separator.
     * Matches bundled ShellUtils.chainCommands exactly.
     */
    private fun chainCommands(vararg commands: String): String {
        return commands.joinToString(commandSeparator)
    }

    /**
     * Public interface - finds project root from a file path.
     * Matches bundled DesktopMainFunctionDetector.findProjectRoot exactly.
     */
    fun findProjectRoot(filePath: String): String {
        val fileDir = File(filePath).parentFile
        return findProjectRootInternal(fileDir)?.absolutePath ?: fileDir?.absolutePath ?: filePath
    }

    /**
     * Find the project root by walking up the directory tree looking for project markers.
     * Matches bundled DesktopMainFunctionDetector.findProjectRootInternal exactly.
     * Markers: gradlew, build.gradle.kts, pom.xml, Cargo.toml, package.json, .git
     */
    private fun findProjectRootInternal(startDir: File?): File? {
        var current = startDir
        while (current != null && current.exists()) {
            // Check for Gradle project (prioritize this)
            if (File(current, "gradlew").exists() || File(current, "gradlew.bat").exists()) {
                return current
            }
            // Check for standalone Gradle build file
            if (File(current, "build.gradle.kts").exists() || File(current, "build.gradle").exists()) {
                // Only use this if no gradlew found above - might be a submodule
                if (current.parentFile?.let { findProjectRootInternal(it) } == null) {
                    return current
                }
            }
            // Check for Maven project
            if (File(current, "pom.xml").exists()) {
                return current
            }
            // Check for Cargo project
            if (File(current, "Cargo.toml").exists()) {
                return current
            }
            // Check for Node.js project
            if (File(current, "package.json").exists()) {
                return current
            }
            // Check for Git root (last resort)
            if (File(current, ".git").exists()) {
                return current
            }
            current = current.parentFile
        }
        return null
    }

    /**
     * Generate run command for detected main function.
     * Matches bundled DesktopMainFunctionDetector.generateCommand exactly.
     */
    fun generateCommand(detected: DetectedMainFunction, projectPath: String): String {
        val projectDir = File(findProjectRoot(detected.filePath))

        return when (detected.language.lowercase()) {
            "kotlin", "kt", "kts" -> generateKotlinCommand(detected, projectDir)
            "java" -> generateJavaCommand(detected, projectDir)
            "python", "py" -> "python3 ${shellEscape(detected.filePath)}"
            "go", "golang" -> "go run ${shellEscape(detected.filePath)}"
            "rust", "rs" -> generateRustCommand(detected, projectDir)
            "javascript", "js" -> "node ${shellEscape(detected.filePath)}"
            "typescript", "ts" -> "npx ts-node ${shellEscape(detected.filePath)}"
            else -> "echo 'Unknown language: ${detected.language}'"
        }
    }

    /**
     * Check if project has Gradle wrapper.
     * Matches bundled DesktopMainFunctionDetector.hasGradleWrapper exactly.
     */
    private fun hasGradleWrapper(projectDir: File): Boolean {
        return File(projectDir, "gradlew").exists() ||
               File(projectDir, "gradlew.bat").exists()
    }

    private fun generateKotlinCommand(detected: DetectedMainFunction, projectDir: File): String {
        val filePath = detected.filePath

        // For .kts scripts, use kotlinc -script
        if (filePath.endsWith(".kts")) {
            return "kotlinc -script ${shellEscape(filePath)}"
        }

        // For Gradle projects, use ./gradlew :moduleName:run
        if (hasGradleWrapper(projectDir)) {
            val moduleName = detectModuleName(filePath, projectDir)
            if (moduleName != null) {
                return "./gradlew :$moduleName:run"
            }
            // Root project run task as fallback
            return "./gradlew run"
        }

        // Fallback: compile and run with kotlinc (for simple standalone files)
        val jarName = File(filePath).nameWithoutExtension.replace("'", "_")
        val compileCmd = "kotlinc ${shellEscape(filePath)} -include-runtime -d ${shellEscape("/tmp/$jarName.jar")}"
        val runCmd = "java -jar ${shellEscape("/tmp/$jarName.jar")}"
        return chainCommands(compileCmd, runCmd)
    }

    private fun generateJavaCommand(detected: DetectedMainFunction, projectDir: File): String {
        val filePath = detected.filePath

        // For Gradle projects, use ./gradlew :moduleName:run
        if (hasGradleWrapper(projectDir)) {
            val moduleName = detectModuleName(filePath, projectDir)
            if (moduleName != null) {
                return "./gradlew :$moduleName:run"
            }
            // Root project run task as fallback
            return "./gradlew run"
        }

        // For Maven projects, use mvn exec:java
        if (File(projectDir, "pom.xml").exists()) {
            val className = buildClassName(detected)
            // Class names are validated by compiler, so they should be safe
            return "mvn exec:java -Dexec.mainClass=${shellEscape(className)}"
        }

        // Fallback: Java 11+ single-file source-code execution
        return "java ${shellEscape(filePath)}"
    }

    private fun generateRustCommand(detected: DetectedMainFunction, projectDir: File): String {
        val filePath = detected.filePath

        // For Cargo projects
        if (File(projectDir, "Cargo.toml").exists()) {
            // Check if it's in a workspace member
            val moduleName = detectCargoModule(filePath, projectDir)
            if (moduleName != null) {
                return "cargo run -p ${shellEscape(moduleName)}"
            }
            return "cargo run"
        }

        // Fallback: Compile and run the specific Rust file directly
        val outputName = File(filePath).nameWithoutExtension.replace("'", "_")
        val compileCmd = "rustc ${shellEscape(filePath)} -o ${shellEscape("/tmp/$outputName")}"
        val runCmd = shellEscape("/tmp/$outputName")
        return chainCommands(compileCmd, runCmd)
    }

    /**
     * Build the fully qualified class name from detected function info.
     */
    private fun buildClassName(detected: DetectedMainFunction): String {
        val pkg = detected.packageName
        val cls = detected.className
        return when {
            pkg != null && cls != null -> "$pkg.$cls"
            cls != null -> cls
            else -> "Main"
        }
    }

    private fun detectModuleName(filePath: String, projectDir: File): String? {
        // Use File API to properly handle path separators on all platforms
        val file = File(filePath)
        val projectPath = projectDir.absolutePath

        // Get relative path using File API (handles both / and \ properly)
        val relativePath = file.absolutePath.removePrefix(projectPath)
            .removePrefix(File.separator)
            .removePrefix("/") // Remove Unix separator if present
            .removePrefix("\\") // Remove Windows separator if present

        // Pattern: moduleName/src/... (split by platform separator)
        val parts = relativePath.split(File.separator, "/", "\\")
            .filter { it.isNotEmpty() } // Remove empty parts

        if (parts.size >= 2 && parts[1] == "src") {
            val potentialModule = parts[0]
            // Verify it's a valid module by checking for build.gradle(.kts)
            val moduleDir = File(projectDir, potentialModule)
            if (moduleDir.isDirectory &&
                (File(moduleDir, "build.gradle.kts").exists() || File(moduleDir, "build.gradle").exists())) {
                return potentialModule
            }
        }

        return null
    }

    /**
     * Detect Cargo workspace member name from file path.
     */
    private fun detectCargoModule(filePath: String, projectDir: File): String? {
        // Use File API to properly handle path separators on all platforms
        val file = File(filePath)
        val projectPath = projectDir.absolutePath

        // Get relative path using File API (handles both / and \ properly)
        val relativePath = file.absolutePath.removePrefix(projectPath)
            .removePrefix(File.separator)
            .removePrefix("/") // Remove Unix separator if present
            .removePrefix("\\") // Remove Windows separator if present

        // Pattern: crate-name/src/... (split by platform separator)
        val parts = relativePath.split(File.separator, "/", "\\")
            .filter { it.isNotEmpty() } // Remove empty parts

        if (parts.size >= 2 && parts[1] == "src") {
            val potentialCrate = parts[0]
            // Verify it's a valid crate by checking for Cargo.toml
            val crateDir = File(projectDir, potentialCrate)
            if (crateDir.isDirectory && File(crateDir, "Cargo.toml").exists()) {
                return potentialCrate
            }
        }

        return null
    }

    // Regex patterns matching bundled DesktopMainFunctionDetector exactly
    private val KOTLIN_MAIN_PATTERN = Regex(
        """^\s*(?:@JvmStatic\s+)?fun\s+main\s*\(""",
        RegexOption.MULTILINE
    )
    private val KOTLIN_PACKAGE_PATTERN = Regex(
        """^\s*package\s+([\w.]+)""",
        RegexOption.MULTILINE
    )

    private val JAVA_MAIN_PATTERN = Regex(
        """^\s*public\s+static\s+void\s+main\s*\(\s*String\s*\[?\s*\]?\s*\w*\s*\)""",
        RegexOption.MULTILINE
    )
    private val JAVA_CLASS_PATTERN = Regex(
        """^\s*(?:public\s+)?class\s+(\w+)""",
        RegexOption.MULTILINE
    )
    private val JAVA_PACKAGE_PATTERN = Regex(
        """^\s*package\s+([\w.]+)\s*;""",
        RegexOption.MULTILINE
    )

    private val PYTHON_MAIN_PATTERN = Regex(
        """^if\s+__name__\s*==\s*['""]__main__['""]""",
        RegexOption.MULTILINE
    )

    private val GO_MAIN_PATTERN = Regex(
        """^\s*func\s+main\s*\(\s*\)""",
        RegexOption.MULTILINE
    )
    private val GO_PACKAGE_MAIN_PATTERN = Regex(
        """^\s*package\s+main\b""",
        RegexOption.MULTILINE
    )

    private val RUST_MAIN_PATTERN = Regex(
        """^\s*fn\s+main\s*\(\s*\)""",
        RegexOption.MULTILINE
    )

    private val C_MAIN_PATTERN = Regex(
        """^\s*(int|void)\s+main\s*\(""",
        RegexOption.MULTILINE
    )

    fun detectInContent(content: String, language: String, filePath: String): List<DetectedMainFunction> {
        return when (language.lowercase()) {
            "kotlin", "kt", "kts" -> detectKotlinMain(filePath, content)
            "java" -> detectJavaMain(filePath, content)
            "python", "py" -> detectPythonMain(filePath, content)
            "go", "golang" -> detectGoMain(filePath, content)
            "rust", "rs" -> detectRustMain(filePath, content)
            "c", "cpp", "c++" -> detectCMain(filePath, content, language)
            else -> emptyList()
        }
    }

    private fun detectKotlinMain(filePath: String, content: String): List<DetectedMainFunction> {
        val results = mutableListOf<DetectedMainFunction>()
        val lines = content.lines()

        // Extract package name
        val packageMatch = KOTLIN_PACKAGE_PATTERN.find(content)
        val packageName = packageMatch?.groupValues?.get(1)

        lines.forEachIndexed { index, line ->
            if (KOTLIN_MAIN_PATTERN.containsMatchIn(line)) {
                results.add(DetectedMainFunction(
                    lineNumber = index,
                    functionName = "main",
                    filePath = filePath,
                    language = "kotlin",
                    packageName = packageName,
                    className = null
                ))
            }
        }

        return results
    }

    private fun detectJavaMain(filePath: String, content: String): List<DetectedMainFunction> {
        val results = mutableListOf<DetectedMainFunction>()
        val lines = content.lines()

        // Extract package and class names
        val packageMatch = JAVA_PACKAGE_PATTERN.find(content)
        val packageName = packageMatch?.groupValues?.get(1)

        val classMatch = JAVA_CLASS_PATTERN.find(content)
        val className = classMatch?.groupValues?.get(1)

        lines.forEachIndexed { index, line ->
            if (JAVA_MAIN_PATTERN.containsMatchIn(line)) {
                results.add(DetectedMainFunction(
                    lineNumber = index,
                    functionName = "main",
                    filePath = filePath,
                    language = "java",
                    packageName = packageName,
                    className = className
                ))
            }
        }

        return results
    }

    private fun detectPythonMain(filePath: String, content: String): List<DetectedMainFunction> {
        val results = mutableListOf<DetectedMainFunction>()
        val lines = content.lines()

        lines.forEachIndexed { index, line ->
            if (PYTHON_MAIN_PATTERN.containsMatchIn(line)) {
                results.add(DetectedMainFunction(
                    lineNumber = index,
                    functionName = "__main__",
                    filePath = filePath,
                    language = "python",
                    packageName = null,
                    className = null
                ))
            }
        }

        return results
    }

    private fun detectGoMain(filePath: String, content: String): List<DetectedMainFunction> {
        // Go requires both 'package main' and 'func main()'
        if (!GO_PACKAGE_MAIN_PATTERN.containsMatchIn(content)) {
            return emptyList()
        }

        val results = mutableListOf<DetectedMainFunction>()
        val lines = content.lines()

        lines.forEachIndexed { index, line ->
            if (GO_MAIN_PATTERN.containsMatchIn(line)) {
                results.add(DetectedMainFunction(
                    lineNumber = index,
                    functionName = "main",
                    filePath = filePath,
                    language = "go",
                    packageName = "main",
                    className = null
                ))
            }
        }

        return results
    }

    private fun detectRustMain(filePath: String, content: String): List<DetectedMainFunction> {
        val results = mutableListOf<DetectedMainFunction>()
        val lines = content.lines()

        lines.forEachIndexed { index, line ->
            if (RUST_MAIN_PATTERN.containsMatchIn(line)) {
                results.add(DetectedMainFunction(
                    lineNumber = index,
                    functionName = "main",
                    filePath = filePath,
                    language = "rust",
                    packageName = null,
                    className = null
                ))
            }
        }

        return results
    }

    private fun detectCMain(filePath: String, content: String, language: String): List<DetectedMainFunction> {
        val results = mutableListOf<DetectedMainFunction>()
        val lines = content.lines()

        lines.forEachIndexed { index, line ->
            if (C_MAIN_PATTERN.containsMatchIn(line)) {
                results.add(DetectedMainFunction(
                    lineNumber = index,
                    functionName = "main",
                    filePath = filePath,
                    language = language,
                    packageName = null,
                    className = null
                ))
            }
        }

        return results
    }
}

/**
 * Run gutter component that displays play buttons next to main functions.
 * Matches the bundled BossEditorRunGutter behavior exactly.
 */
@Composable
private fun EditorRunGutter(
    detectedMainFunctions: List<DetectedMainFunction>,
    editorState: EditorState,
    fontSize: Float,
    fontFamily: FontFamily,
    lineSpacing: Float,
    onRun: (DetectedMainFunction) -> Unit,
    modifier: Modifier = Modifier
) {
    // Collect scroll offset from editor state
    val scrollOffset by editorState.scrollOffset.collectAsState()
    // Collect visual line mapper for folding support
    val visualLineMapper by editorState.visualLineMapper.collectAsState()
    val density = androidx.compose.ui.platform.LocalDensity.current

    // Measure line height to match EditorCanvas exactly
    val textMeasurer = rememberTextMeasurer()
    val lineHeightPx = remember(fontSize, fontFamily, lineSpacing) {
        val style = TextStyle(
            fontFamily = fontFamily,
            fontSize = fontSize.sp
        )
        textMeasurer.measure("M", style).size.height.toFloat() * lineSpacing
    }

    // Convert pixel height to dp for sizing
    val lineHeightDp = with(density) { lineHeightPx.toDp() }

    // Create a map for fast lookup
    val runnableLines = remember(detectedMainFunctions) {
        detectedMainFunctions.associateBy { it.lineNumber }
    }

    // Calculate visible range with buffer
    val firstVisibleLine = (scrollOffset.y / lineHeightPx).toInt().coerceAtLeast(0)
    val visibleLineCount = 50 // Generous buffer for smooth scrolling
    val visibleRange = remember(firstVisibleLine, visibleLineCount, editorState.document.lineCount) {
        val start = (firstVisibleLine - 2).coerceAtLeast(0)
        val end = (firstVisibleLine + visibleLineCount + 2).coerceAtMost(editorState.document.lineCount)
        start until end
    }

    Box(modifier = modifier) {
        // Render run icons for detected main functions in visible range
        detectedMainFunctions
            .filter { it.lineNumber in visibleRange }
            .forEach { detected ->
                // lineNumber from detector is 0-based document line
                val documentLine = detected.lineNumber

                // Convert document line to visual line (accounts for folding)
                val visualLine = visualLineMapper.documentToVisual(documentLine)

                // Skip if line is hidden (inside a collapsed fold)
                if (visualLine < 0) return@forEach

                // Calculate Y position using visual line
                val yOffsetPx = (visualLine * lineHeightPx) - scrollOffset.y

                // Only render if within viewport
                if (yOffsetPx >= -lineHeightPx && yOffsetPx < 2000f) {
                    Box(
                        modifier = Modifier
                            // Use pixel-based offset to match EditorCanvas rendering
                            .offset { IntOffset(0, yOffsetPx.toInt()) }
                            .height(lineHeightDp)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        GutterRunIcon(
                            detected = detected,
                            onRun = onRun,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
    }
}

/**
 * Run icon with hover effect matching IntelliJ style.
 */
@Composable
private fun GutterRunIcon(
    detected: DetectedMainFunction,
    onRun: (DetectedMainFunction) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val iconColor = if (isHovered) {
        Color(0xFF6BBF78) // Brighter green when hovered
    } else {
        Color(0xFF59A869) // IntelliJ's run icon green
    }

    Icon(
        imageVector = FeatherIcons.Play,
        contentDescription = "Run ${detected.functionName}",
        tint = iconColor,
        modifier = modifier
            .size(20.dp)
            .hoverable(interactionSource)
            .clickable { onRun(detected) }
    )
}
