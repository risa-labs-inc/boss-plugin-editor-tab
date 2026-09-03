package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy

/**
 * Composer tab type (plugin side).
 *
 * The host's plugin-api-core carries the persistable mirror (ComposerTabInfo,
 * type id "composer"); this object registers the component factory under the
 * same type id, so a workspace-restored tab built from the host's class lands
 * here.
 */
object ComposerTabType : TabTypeInfo {
    override val typeId = TabTypeId("composer")
    override val displayName = "Composer"
    override val icon = Icons.Filled.SmartToy
}
