package ai.rever.boss.plugin.dynamic.editortab

import kotlin.test.Test
import kotlin.test.assertEquals

class MarkdownDefaultViewTest {

    @Test
    fun `fixed preferences resolve to their corresponding view modes`() {
        assertEquals(
            MarkdownViewMode.EDIT,
            MarkdownDefaultView.EDIT.resolve(MarkdownViewMode.PREVIEW)
        )
        assertEquals(
            MarkdownViewMode.SPLIT,
            MarkdownDefaultView.SPLIT.resolve(MarkdownViewMode.PREVIEW)
        )
        assertEquals(
            MarkdownViewMode.PREVIEW,
            MarkdownDefaultView.PREVIEW.resolve(MarkdownViewMode.EDIT)
        )
    }

    @Test
    fun `last selected preference resolves to the remembered view mode`() {
        MarkdownViewMode.entries.forEach { viewMode ->
            assertEquals(
                viewMode,
                MarkdownDefaultView.LAST_SELECTED.resolve(viewMode)
            )
        }
    }

    @Test
    fun `new settings default to preview mode`() {
        assertEquals(
            MarkdownViewMode.PREVIEW,
            MarkdownViewSettings().initialViewMode()
        )
    }
}
