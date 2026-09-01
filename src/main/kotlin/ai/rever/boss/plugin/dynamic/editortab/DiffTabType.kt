package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.api.TabTypeInfo
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Difference

/**
 * The diff tab, owned by this plugin (api 1.0.89).
 *
 * The type id matches the host's `DiffTabType` exactly - the host still
 * creates the tab config and persists it in workspaces; only the RENDERER
 * moves here. That is the point of the move: a diff is a variation of the
 * editor tab, and everything that makes the editor useful - the lexer, the
 * token cache, semantic tokens from language servers, the overview ruler -
 * lives in this plugin and is unreachable from the host, which is why the
 * host's own renderer could only ever draw plain text.
 */
object DiffTabType : TabTypeInfo {
    override val typeId = TabTypeId("diff")
    override val displayName = "Diff"
    override val icon = Icons.Outlined.Difference
}
