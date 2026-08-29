package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.bosseditor.psi.PSIBootstrap
import ai.rever.bosseditor.psi.PSIThreadBridge
import ai.rever.bosseditor.psi.ProjectIndexer

/**
 * Code Editor Tab dynamic plugin - Loaded from external JAR.
 *
 * Provides code editor tabs in the main panel area using the host's
 * editor infrastructure (RSyntaxTextArea or BossEditor).
 *
 * Features:
 * - Syntax highlighting for 50+ languages
 * - Code folding
 * - Bracket matching
 * - Line numbers with fold indicators
 * - Run gutter icons for detected main functions
 * - File modification tracking with save support (Cmd+S)
 *
 * NOTE: This is a main panel TAB plugin, not a sidebar panel.
 * It registers as a TabType via tabRegistry.registerTabType().
 */
class EditorTabDynamicPlugin : DynamicPlugin {
    override val pluginId: String = "ai.rever.boss.plugin.dynamic.editortab"
    override val displayName: String = "Code Editor Tab"
    override val version: String = "1.4.0"
    override val description: String = "Code editor tab with syntax highlighting, code folding, and run gutter icons"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-editor-tab"

    private var pluginContext: PluginContext? = null
    private var markdownSettingsManager: MarkdownViewSettingsManager? = null
    private var autoSaveSettingsManager: AutoSaveSettingsManager? = null
    private var externalReloadSettingsManager: ExternalReloadSettingsManager? = null

    override fun register(context: PluginContext) {
        pluginContext = context

        markdownSettingsManager?.dispose()
        autoSaveSettingsManager?.dispose()
        externalReloadSettingsManager?.dispose()
        val storage = runCatching {
            context.pluginStorageFactory?.createStorage(pluginId)
        }.getOrNull()
        val markdownSettings = MarkdownViewSettingsManager(storage = storage)
        markdownSettingsManager = markdownSettings
        val autoSaveSettings = AutoSaveSettingsManager(storage = storage)
        autoSaveSettingsManager = autoSaveSettings
        val externalReloadSettings = ExternalReloadSettingsManager(storage = storage)
        externalReloadSettingsManager = externalReloadSettings

        // Seed BossEditor with the host theme before anything composes: the settings
        // panel can be the first thing to render, and it resolves the follow-host
        // theme through a registry that is a plain map, not snapshot state.
        publishHostThemeToEditor()

        // Register as a main panel TAB TYPE (not a sidebar panel!)
        context.tabRegistry.registerTabType(EditorTabType) { tabInfo, ctx ->
            EditorTabComponent(ctx, tabInfo, context, markdownSettings, autoSaveSettings, externalReloadSettings)
        }

        // Contribute editor_read_file/write_file/detect_language MCP tools; auto-removed on disable/unload.
        context.registerMcpToolProvider(EditorTabMcpToolProvider(pluginId, context.editorContentProvider))

        // Serve editor + LSP settings panels to the host: the Settings window's
        // BOSS_EDITOR and LANGUAGE_SERVERS sections delegate through this API.
        //
        // Guarded: EditorTabPluginAPI is a shared-package (parent-first) class,
        // so on hosts that predate it the impl class fails to link. Those hosts
        // still render their own editor settings from their own BossEditor
        // dependency, so skipping registration degrades nothing there.
        try {
            context.registerPluginAPI(
                EditorTabPluginAPIImpl(markdownSettings, autoSaveSettings, externalReloadSettings)
            )
        } catch (e: LinkageError) {
            // Host predates EditorTabPluginAPI — skip; everything else works.
        }

        // Warm up the bundled PSI stack off the UI thread. The host did this at
        // startup while BossEditor was on its classpath; the plugin owns it now.
        // Single-flight: semantic analysis awaits the same deferred, so files
        // opened while the warm-up runs still get colors when it completes.
        PluginSemanticTokenProvider.warmUp()
    }

    override fun dispose() {
        // Unregister tab type when plugin is unloaded
        pluginContext?.tabRegistry?.unregisterTabType(EditorTabType.typeId)
        pluginContext = null

        markdownSettingsManager?.dispose()
        markdownSettingsManager = null
        autoSaveSettingsManager?.dispose()
        autoSaveSettingsManager = null
        externalReloadSettingsManager?.dispose()
        externalReloadSettingsManager = null

        // Undo what register() published. The registry and the chrome holder live in
        // this plugin's classloader, so a reload replaces them anyway - symmetry is
        // cheaper than depending on that reasoning staying true.
        runCatching { unpublishHostThemeFromEditor() }

        // Tear down the bundled PSI stack (previously the host main.kt shutdown
        // hook's job, when BossEditor lived on the host classpath).
        runCatching { ProjectIndexer.shutdownGlobal() }
        runCatching { PSIBootstrap.shutdown() }
        runCatching { PSIThreadBridge.shutdown() }
    }
}
