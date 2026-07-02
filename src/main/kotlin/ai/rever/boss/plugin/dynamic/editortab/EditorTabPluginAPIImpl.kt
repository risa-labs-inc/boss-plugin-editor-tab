package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.EditorTabPluginAPI
import ai.rever.boss.plugin.dynamic.editortab.settings.LspSettingsContent
import ai.rever.bosseditor.settings.EditorSettingsManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * Editor-tab's implementation of EditorTabPluginAPI, registered with the host
 * via registerPluginAPI(). The panels render against the BossEditor bundled
 * inside this plugin's JAR — the host no longer has BossEditor on its
 * classpath (same inversion as terminal-tab's TerminalTabPluginAPIImpl).
 */
class EditorTabPluginAPIImpl : EditorTabPluginAPI {

    @Composable
    override fun EditorSettingsPanel(modifier: Modifier) {
        // BossEditor's singleton settings manager gives reactive updates;
        // settings persist to ~/.boss/editor-settings.json and apply to all
        // BossEditor instances immediately.
        val settingsManager = remember { EditorSettingsManager.instance }
        val currentSettings by settingsManager.settings.collectAsState()

        ai.rever.bosseditor.settings.EditorSettingsPanel(
            settings = currentSettings,
            onSettingsChange = { newSettings ->
                settingsManager.updateSettings(newSettings)
            },
            onResetToDefaults = {
                settingsManager.resetToDefaults()
            },
            modifier = modifier
        )
    }

    @Composable
    override fun LspSettingsPanel(modifier: Modifier) {
        LspSettingsContent(modifier = modifier)
    }
}
