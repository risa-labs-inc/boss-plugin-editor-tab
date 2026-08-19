package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.bosseditor.theme.EditorTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * An editor tab used to render one of six hand-authored themes regardless of the
 * host theme, so a light window contained a dark editor. These pin the derivation
 * that fixes it: which colors come from the host, which stay curated, and which
 * `themeName` values follow the host.
 */
class HostEditorThemeTest {

    /** Blueprint, the default host theme: electric blue on near-black ink. */
    private val blueprint = HostChromeTokens(
        ink = Color(0xFF05070B),
        panel = Color(0xFF0B0F16),
        line = Color(0xFF1B2430),
        textPrimary = Color(0xFFE6EDF6),
        textSecondary = Color(0xFF9FB0C4),
        textMuted = Color(0xFF6B7C90),
        signal = Color(0xFF0F5BFF),
        data = Color(0xFF56C7E0),
        alert = Color(0xFFFF5C5C),
        warn = Color(0xFFFFB84D),
    )

    /** Blueprint Light, the light half: paper floor, dark text. */
    private val blueprintLight = HostChromeTokens(
        ink = Color(0xFFFFFFFF),
        panel = Color(0xFFF3F5F9),
        line = Color(0xFFD6DDE7),
        textPrimary = Color(0xFF10151C),
        textSecondary = Color(0xFF41505F),
        textMuted = Color(0xFF6B7C90),
        signal = Color(0xFF0F5BFF),
        data = Color(0xFF1B7C83),
        alert = Color(0xFFCF222E),
        warn = Color(0xFF95580A),
    )

    @Test
    fun `the floor and the caret come from the host`() {
        val colors = buildHostEditorTheme(blueprint).colors

        assertEquals(blueprint.ink, colors.background)
        assertEquals(blueprint.ink, colors.gutterBackground)
        assertEquals(blueprint.ink, colors.minimapBackground)
        assertEquals(blueprint.textPrimary, colors.text)
        assertEquals(blueprint.signal, colors.caret)
        assertEquals(blueprint.line, colors.gutterBorder)
        assertEquals(blueprint.textMuted, colors.lineNumber)
    }

    @Test
    fun `status colors come from the host, syntax colors stay curated`() {
        val colors = buildHostEditorTheme(blueprint).colors

        assertEquals(blueprint.alert, colors.error)
        assertEquals(blueprint.alert, colors.errorSquiggle)
        assertEquals(blueprint.warn, colors.warningSquiggle)
        assertEquals(blueprint.data, colors.infoSquiggle)
        assertEquals(blueprint.data, colors.hyperlink)

        // The host has no opinion about keywords or strings, and 11 chrome tokens
        // cannot produce a balanced syntax palette - those stay with the base.
        val base = EditorTheme.Dark.colors
        assertEquals(base.keyword, colors.keyword)
        assertEquals(base.string, colors.string)
        assertEquals(base.comment, colors.comment)
        assertEquals(base.function, colors.function)
        assertEquals(base.rainbowBracket1, colors.rainbowBracket1)
    }

    @Test
    fun `a light host yields a light editor`() {
        val theme = buildHostEditorTheme(blueprintLight)

        assertEquals(false, theme.isDark)
        assertEquals(blueprintLight.ink, theme.colors.background)
        // Light syntax colors, or code would be dark-theme pastels on paper.
        assertEquals(EditorTheme.Light.colors.keyword, theme.colors.keyword)
        assertTrue(theme.colors.text.luminance() < theme.colors.background.luminance())
    }

    @Test
    fun `a dark host yields a dark editor`() {
        val theme = buildHostEditorTheme(blueprint)

        assertEquals(true, theme.isDark)
        assertEquals(EditorTheme.Dark.colors.keyword, theme.colors.keyword)
        assertTrue(theme.colors.text.luminance() > theme.colors.background.luminance())
    }

    @Test
    fun `highlights stay distinct from the floor and from each other`() {
        // Three floors, including pure black, because the blend behaves worst at the
        // extremes: what stays visible on near-black ink can still vanish on black.
        val pureBlack = blueprint.copy(ink = Color(0xFF000000), panel = Color(0xFF000000))
        for (tokens in listOf(blueprint, blueprintLight, pureBlack)) {
            val c = buildHostEditorTheme(tokens).colors
            val what = "floor ${tokens.ink}"

            // Each of these is a fill painted on the floor. Equal to the floor means
            // the user sees nothing happen when they select, search or click a line.
            assertNotEquals(c.background, c.currentLineHighlight, "current line vs $what")
            assertNotEquals(c.background, c.selectionBackground, "selection vs $what")
            assertNotEquals(c.background, c.searchMatchBackground, "search match vs $what")
            assertNotEquals(c.background, c.markOccurrences, "occurrences vs $what")
            assertNotEquals(c.background, c.matchedBracketBackground, "bracket vs $what")

            // The current match has to be findable among the other matches, and
            // neither may read as the selection.
            assertNotEquals(c.searchMatchBackground, c.currentSearchMatchBackground, "match pair on $what")
            assertNotEquals(c.selectionBackground, c.currentSearchMatchBackground, "selection vs match on $what")
        }
    }

    @Test
    fun `a floor with no chrome step above it still gets a visible highlight`() {
        // A host theme is free to make panel equal ink; the current-line highlight
        // must not vanish when it does.
        val flat = blueprint.copy(panel = blueprint.ink)
        val colors = buildHostEditorTheme(flat).colors

        assertNotEquals(colors.background, colors.currentLineHighlight)
        assertNotEquals(colors.background, colors.foldPlaceholderBackground)
    }

    @Test
    fun `the derived theme carries the follow-host name`() {
        assertEquals(FOLLOW_HOST_THEME_NAME, buildHostEditorTheme(blueprint).name)
    }

    @Test
    fun `mixing does not collapse near the extremes`() {
        // Compose's own lerp interpolates in Oklab, where a 5% step from pure black
        // rounds back to pure black. This is the assertion that fails if the
        // derivation ever swaps the gamma-space mix for it.
        val black = Color(0xFF000000)
        val white = Color(0xFFFFFFFF)

        assertNotEquals(black, mix(black, white, 0.05f))
        assertNotEquals(white, mix(white, black, 0.05f))
        assertEquals(1f, mix(black, white.copy(alpha = 0.2f), 0.5f).alpha)
    }

    @Test
    fun `following the host ignores whatever theme name is on file`() {
        val host = buildHostEditorTheme(blueprint)

        // The switch is on by default and old settings files all carry "Dark", so
        // this is the path an existing install takes.
        assertSame(host, resolveEditorTheme(followHost = true, themeName = "Dark", hostTheme = host))
        assertSame(host, resolveEditorTheme(followHost = true, themeName = "Dracula", hostTheme = host))
        assertSame(host, resolveEditorTheme(followHost = true, themeName = null, hostTheme = host))
    }

    @Test
    fun `the plugin default follows the host`() {
        // The two halves of this default live in different repos (this mirror and
        // bosseditor's EditorSettings), and they read the same file.
        assertEquals(true, PluginEditorSettingsData().followHostTheme)
    }

    @Test
    fun `an unset theme name follows the host`() {
        val host = buildHostEditorTheme(blueprint)

        assertSame(host, resolveEditorTheme(false, null, host))
        assertSame(host, resolveEditorTheme(false, "", host))
        assertSame(host, resolveEditorTheme(false, "   ", host))
        assertSame(host, resolveEditorTheme(false, FOLLOW_HOST_THEME_NAME, host))
    }

    @Test
    fun `an explicit theme name wins once following is off`() {
        val host = buildHostEditorTheme(blueprint)

        assertEquals(EditorTheme.Dracula, resolveEditorTheme(false, "Dracula", host))
        assertEquals(EditorTheme.Light, resolveEditorTheme(false, "Light", host))
        assertEquals(EditorTheme.Dark, resolveEditorTheme(false, "Dark", host))
        assertEquals(EditorTheme.SolarizedLight, resolveEditorTheme(false, "Solarized Light", host))
    }

    @Test
    fun `a name no theme answers to follows the host rather than falling back to Dark`() {
        val host = buildHostEditorTheme(blueprint)

        // EditorTheme.forName resolves anything unknown to Dark, which is how a
        // typo or a retired theme name would put a dark editor in a light window.
        assertEquals(EditorTheme.Dark, EditorTheme.forName("Gruvbox"))
        assertSame(host, resolveEditorTheme(false, "Gruvbox", host))
    }

    @Test
    fun `every builtin name resolves to itself`() {
        val host = buildHostEditorTheme(blueprint)

        for (name in EditorTheme.availableThemes) {
            assertEquals(name, resolveEditorTheme(false, name, host).name, "builtin $name")
        }
    }
}
