package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.bosseditor.psi.PSIBootstrap
import ai.rever.bosseditor.psi.PSIThreadBridge
import ai.rever.bosseditor.psi.ProjectIndexer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    override val version: String = "1.0.4"
    override val description: String = "Code editor tab with syntax highlighting, code folding, and run gutter icons"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-editor-tab"

    private var pluginContext: PluginContext? = null

    override fun register(context: PluginContext) {
        pluginContext = context

        // Register as a main panel TAB TYPE (not a sidebar panel!)
        context.tabRegistry.registerTabType(EditorTabType) { tabInfo, ctx ->
            EditorTabComponent(ctx, tabInfo, context)
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
            context.registerPluginAPI(EditorTabPluginAPIImpl())
        } catch (e: LinkageError) {
            // Host predates EditorTabPluginAPI — skip; everything else works.
        }

        // Warm up the bundled PSI stack off the UI thread. The host did this at
        // startup while BossEditor was on its classpath; the plugin owns it now.
        // Semantic analysis skips gracefully until initialization completes.
        CoroutineScope(Dispatchers.Default).launch {
            runCatching { PSIBootstrap.initialize() }
        }
    }

    override fun dispose() {
        // Unregister tab type when plugin is unloaded
        pluginContext?.tabRegistry?.unregisterTabType(EditorTabType.typeId)
        pluginContext = null

        // Tear down the bundled PSI stack (previously the host main.kt shutdown
        // hook's job, when BossEditor lived on the host classpath).
        runCatching { ProjectIndexer.shutdownGlobal() }
        runCatching { PSIBootstrap.shutdown() }
        runCatching { PSIThreadBridge.shutdown() }
    }
}
