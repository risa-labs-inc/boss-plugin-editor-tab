package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.EditorTabPluginAPI
import ai.rever.boss.plugin.dynamic.editortab.settings.LspSettingsContent
import ai.rever.boss.plugin.dynamic.editortab.settings.MarkdownViewSettingsContent
import ai.rever.boss.plugin.ui.BossDarkBorder
import ai.rever.bosseditor.settings.EditorSettingsManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Editor-tab's implementation of EditorTabPluginAPI, registered with the host
 * via registerPluginAPI(). The panels render against the BossEditor bundled
 * inside this plugin's JAR — the host no longer has BossEditor on its
 * classpath (same inversion as terminal-tab's TerminalTabPluginAPIImpl).
 */
class EditorTabPluginAPIImpl(
    private val markdownSettingsManager: MarkdownViewSettingsManager
) : EditorTabPluginAPI {

    @Composable
    override fun EditorSettingsPanel(modifier: Modifier) {
        // BossEditor's singleton settings manager gives reactive updates;
        // settings persist to ~/.boss/editor-settings.json and apply to all
        // BossEditor instances immediately.
        val settingsManager = remember { EditorSettingsManager.instance }
        val currentSettings by settingsManager.settings.collectAsState()
        val markdownSettings by markdownSettingsManager.settings.collectAsState()

        Column(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                ai.rever.bosseditor.settings.EditorSettingsPanel(
                    settings = currentSettings,
                    onSettingsChange = { newSettings ->
                        settingsManager.updateSettings(newSettings)
                    },
                    onResetToDefaults = {
                        settingsManager.resetToDefaults()
                        markdownSettingsManager.resetToDefaults()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BossDarkBorder)
            )

            MarkdownViewSettingsContent(
                settings = markdownSettings,
                onDefaultViewChange = markdownSettingsManager::setDefaultView,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    @Composable
    override fun LspSettingsPanel(modifier: Modifier) {
        LspSettingsContent(modifier = modifier)
    }
}
