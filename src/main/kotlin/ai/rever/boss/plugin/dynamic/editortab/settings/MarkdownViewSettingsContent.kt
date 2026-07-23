package ai.rever.boss.plugin.dynamic.editortab.settings

import ai.rever.boss.plugin.dynamic.editortab.MarkdownDefaultView
import ai.rever.boss.plugin.dynamic.editortab.MarkdownViewSettings
import ai.rever.boss.plugin.ui.BossDarkAccent
import ai.rever.boss.plugin.ui.BossDarkSurface
import ai.rever.boss.plugin.ui.BossDarkTextMuted
import ai.rever.boss.plugin.ui.BossDarkTextPrimary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun MarkdownViewSettingsContent(
    settings: MarkdownViewSettings,
    onDefaultViewChange: (MarkdownDefaultView) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Default Markdown view",
                    color = BossDarkTextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = settings.defaultView.description,
                    color = BossDarkTextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }

            Spacer(modifier = Modifier.width(20.dp))

            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    colors = ButtonDefaults.outlinedButtonColors(
                        backgroundColor = BossDarkSurface,
                        contentColor = BossDarkTextPrimary
                    )
                ) {
                    Text(settings.defaultView.displayName, fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("▾", color = BossDarkAccent, fontSize = 13.sp)
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    MarkdownDefaultView.entries.forEach { option ->
                        DropdownMenuItem(
                            onClick = {
                                onDefaultViewChange(option)
                                expanded = false
                            }
                        ) {
                            Text(
                                text = option.displayName,
                                color = if (option == settings.defaultView) {
                                    BossDarkAccent
                                } else {
                                    BossDarkTextPrimary
                                },
                                fontWeight = if (option == settings.defaultView) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                }
                            )
                        }
                    }
                }
            }
        }

        if (settings.defaultView == MarkdownDefaultView.LAST_SELECTED) {
            Text(
                text = "Current last selection: ${settings.lastSelectedView.displayName()}",
                color = BossDarkTextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

private fun ai.rever.boss.plugin.dynamic.editortab.MarkdownViewMode.displayName(): String {
    return name.lowercase().replaceFirstChar { it.uppercase() }
}
