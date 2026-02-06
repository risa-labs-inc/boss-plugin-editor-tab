package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.DynamicPlugin
import ai.rever.boss.plugin.api.PluginContext
import ai.rever.boss.plugin.tab.codeeditor.CodeEditorTabType

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
    override val version: String = "1.0.0"
    override val description: String = "Code editor tab with syntax highlighting, code folding, and run gutter icons"
    override val author: String = "Risa Labs"
    override val url: String = "https://github.com/risa-labs-inc/boss-plugin-editor-tab"

    private var pluginContext: PluginContext? = null

    override fun register(context: PluginContext) {
        pluginContext = context

        val editorContentProvider = context.editorContentProvider
        val tabUpdateProviderFactory = context.tabUpdateProviderFactory

        if (editorContentProvider == null) {
            // Editor content provider not available - cannot register
            return
        }

        // Register as a main panel TAB TYPE
        context.tabRegistry.registerTabType(CodeEditorTabType) { config, componentContext ->
            EditorTabComponent(
                ctx = componentContext,
                config = config,
                editorContentProvider = editorContentProvider,
                tabUpdateProviderFactory = tabUpdateProviderFactory
            )
        }
    }

    override fun dispose() {
        // Unregister tab type when plugin is unloaded
        pluginContext?.tabRegistry?.unregisterTabType(CodeEditorTabType.typeId)
        pluginContext = null
    }
}
