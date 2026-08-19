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

    @Test
    fun `an empty file opens in edit whatever the configured default`() {
        // Nothing to render, so Preview would be a blank pane with no way to start typing -
        // which is what a file just created from the codebase tree looks like.
        MarkdownDefaultView.entries.forEach { defaultView ->
            MarkdownViewMode.entries.forEach { lastSelected ->
                val settings = MarkdownViewSettings(
                    defaultView = defaultView,
                    lastSelectedView = lastSelected
                )
                assertEquals(
                    MarkdownViewMode.EDIT,
                    settings.initialViewMode(isDocumentEmpty = true),
                    "default=$defaultView lastSelected=$lastSelected"
                )
            }
        }
    }

    @Test
    fun `a file with content still opens the way the user configured`() {
        MarkdownDefaultView.entries.forEach { defaultView ->
            MarkdownViewMode.entries.forEach { lastSelected ->
                val settings = MarkdownViewSettings(
                    defaultView = defaultView,
                    lastSelectedView = lastSelected
                )
                assertEquals(
                    defaultView.resolve(lastSelected),
                    settings.initialViewMode(isDocumentEmpty = false),
                    "default=$defaultView lastSelected=$lastSelected"
                )
            }
        }
    }

    @Test
    fun `the empty override is not recorded as a preference`() {
        // It is a per-tab override; the setting itself must be untouched so the next Markdown
        // file with content in it still opens in the configured mode.
        val settings = MarkdownViewSettings(defaultView = MarkdownDefaultView.PREVIEW)

        assertEquals(MarkdownViewMode.EDIT, settings.initialViewMode(isDocumentEmpty = true))
        assertEquals(MarkdownDefaultView.PREVIEW, settings.defaultView)
        assertEquals(MarkdownViewMode.PREVIEW, settings.initialViewMode())
    }
}
