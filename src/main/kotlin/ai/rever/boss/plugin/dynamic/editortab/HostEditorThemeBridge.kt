package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.ui.BossThemeColors
import ai.rever.bosseditor.theme.EditorTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Bridges the BOSS host theme into the bundled BossEditor so the editor re-skins
 * live when the user switches the host theme (Blueprint / Operator / Daylight / ...).
 *
 * The host theme system and BossEditor's theme engine are independent: BossEditor
 * picks one of its six hand-authored themes by name, so an editor tab stayed
 * IntelliJ-dark while the rest of the window went light. This mirrors what
 * `HostTerminalThemeBridge` does for the terminal: read the host's reactive
 * [BossThemeColors] tokens and derive a matching editor theme from them.
 *
 * **Surfaces are derived, syntax is inherited.** A code editor needs ~30 mutually
 * balanced token colors, and the host exposes 11 chrome tokens - synthesizing
 * syntax highlighting from those would produce a worse palette than the curated
 * one. So the derivation takes every *surface* and *status* color from the host
 * (floor, gutter, caret, selection, diagnostics, links) and inherits the syntax
 * colors from whichever curated base matches the host's light/dark polarity. Same
 * lesson terminal-tab learned: prefer a curated palette, synthesize only the parts
 * the host actually owns.
 *
 * Strategy A - no BossEditor change required: this only uses the custom-theme
 * registry and the `theme` parameter that ship in the bundled bosseditor-compose.
 */

/**
 * Name the derived theme is registered under, so BossEditor itself can resolve it -
 * its settings panel previews `EditorTheme.forName(FOLLOW_HOST_THEME_NAME)` when the
 * "Follow BOSS Theme" toggle is on.
 *
 * Kept as a literal because the bundled bosseditor predates
 * `EditorTheme.FOLLOW_HOST_THEME`; the two strings must stay equal, and the pin bump
 * that introduces the constant should replace this with it.
 */
const val FOLLOW_HOST_THEME_NAME: String = "Follow BOSS Theme"

/**
 * The live host-derived editor theme, registered with BossEditor's custom-theme
 * registry so `EditorTheme.forName(FOLLOW_HOST_THEME_NAME)` (used by the settings
 * preview card) resolves to the same colors the tabs render with.
 *
 * Reads the host tokens reactively, so switching the host theme recomposes every
 * editor tab with new colors and no restart.
 */
@Composable
fun rememberHostEditorTheme(): EditorTheme {
    val tokens = HostChromeTokens(
        ink = BossThemeColors.BackgroundColor,
        panel = BossThemeColors.SurfaceColor,
        line = BossThemeColors.BorderColor,
        textPrimary = BossThemeColors.TextPrimary,
        textSecondary = BossThemeColors.TextSecondary,
        textMuted = BossThemeColors.TextMuted,
        signal = BossThemeColors.AccentColor,
        data = BossThemeColors.SecondaryColor,
        alert = BossThemeColors.ErrorColor,
        warn = BossThemeColors.WarningColor,
    )
    return remember(tokens) {
        buildHostEditorTheme(tokens).also { EditorTheme.registerTheme(it) }
    }
}

/**
 * Resolves the theme an editor tab should render with.
 *
 * [followHost] is the switch, defaulted on and absent from any settings file written
 * before it existed - which is what makes an existing install start following the
 * host instead of staying on the "Dark" its file already records. A fixed theme still wins
 * whenever the switch is off, and a name nothing answers to falls back to the host
 * rather than to Dark, so a retired theme name cannot strand a dark editor inside a
 * light window.
 */
internal fun resolveEditorTheme(
    followHost: Boolean,
    themeName: String?,
    hostTheme: EditorTheme,
): EditorTheme = when {
    followHost -> hostTheme
    themeName.isNullOrBlank() -> hostTheme
    themeName == FOLLOW_HOST_THEME_NAME -> hostTheme
    themeName in EditorTheme.availableThemes -> EditorTheme.forName(themeName)
    else -> hostTheme
}

/** The host chrome tokens the derivation reads, captured so it can be unit-tested. */
internal data class HostChromeTokens(
    val ink: Color,
    val panel: Color,
    val line: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val signal: Color,
    val data: Color,
    val alert: Color,
    val warn: Color,
)

/**
 * Derives an [EditorTheme] from the host chrome tokens.
 *
 * Light vs dark comes from the host floor's luminance, which also picks the base
 * whose syntax colors are inherited. Everything the host has an opinion about is
 * overridden; everything else stays curated.
 */
internal fun buildHostEditorTheme(t: HostChromeTokens): EditorTheme {
    val isLight = t.ink.luminance() > 0.5f
    val base = if (isLight) EditorTheme.Light else EditorTheme.Dark

    // A surface one step off the floor, for the current line, fold placeholders
    // and inlay chips. `panel` is normally the host's chrome step above `ink`, but
    // a theme is free to make them equal, so fall back to a tint of the text color
    // rather than rendering an invisible highlight.
    val subtle = if (t.panel == t.ink) mix(t.ink, t.textPrimary, 0.06f) else t.panel

    return EditorTheme(
        name = FOLLOW_HOST_THEME_NAME,
        isDark = !isLight,
        colors = base.colors.copy(
            // Floor and caret
            background = t.ink,
            text = t.textPrimary,
            caret = t.signal,
            // Opaque blends, not alpha: selection and match highlights are painted
            // under the text, and a translucent fill over a light floor washes the
            // glyphs out at exactly the moment the user is reading them.
            selectionBackground = mix(t.ink, t.signal, 0.30f),
            currentLineHighlight = subtle,
            marginLine = t.line,
            matchedBracketBackground = mix(t.ink, t.signal, 0.22f),
            matchedBracketForeground = t.textPrimary,

            // Gutter shares the floor, as the curated themes do; the hairline and
            // the numbers come from the host.
            gutterBackground = t.ink,
            gutterBorder = t.line,
            lineNumber = t.textMuted,
            lineNumberActive = t.textPrimary,
            foldIndicator = t.textMuted,
            foldBackground = t.ink,

            foldPlaceholderBackground = subtle,
            foldPlaceholderHover = mix(subtle, t.textPrimary, 0.10f),
            foldPlaceholderBorder = t.line,
            foldPlaceholderText = t.textSecondary,
            foldGuide = t.line,
            indentGuide = t.line,
            activeIndentGuide = t.textMuted,

            // Status colors the host owns. Syntax colors deliberately stay curated.
            error = t.alert,
            searchMatchBackground = mix(t.ink, t.warn, 0.35f),
            currentSearchMatchBackground = mix(t.ink, t.signal, 0.55f),
            hyperlink = t.data,
            markOccurrences = mix(t.ink, t.signal, 0.18f),

            minimapBackground = t.ink,
            minimapForeground = t.textPrimary,
            minimapViewport = t.textMuted.copy(alpha = 0.25f),
            minimapViewportBorder = t.textMuted.copy(alpha = 0.5f),
            minimapSelection = t.signal.copy(alpha = 0.35f),
            minimapSearchHighlight = t.warn,
            minimapOccurrence = mix(t.ink, t.signal, 0.25f),
            minimapError = t.alert,
            minimapWarning = t.warn,
            minimapInfo = t.data,
            minimapHint = t.textMuted,
            minimapCurrentLine = subtle,
            minimapSliderHover = t.textMuted.copy(alpha = 0.35f),
            minimapBorder = t.line,

            errorSquiggle = t.alert,
            warningSquiggle = t.warn,
            infoSquiggle = t.data,
            hintSquiggle = t.textMuted,
            gutterError = t.alert,
            gutterWarning = t.warn,
            gutterInfo = t.data,
            gutterHint = t.textMuted,

            inlayHintParameterBackground = subtle,
            inlayHintParameterForeground = t.textSecondary,
            inlayHintTypeBackground = mix(t.ink, t.data, 0.18f),
            inlayHintTypeForeground = t.data,
        ),
    )
}

/**
 * Blends two colors in gamma-encoded sRGB component space.
 *
 * Compose's own `lerp` interpolates in Oklab, where a small step away from pure
 * black rounds back to pure black - which collapses exactly the subtle surface
 * ladder this derivation is built from on an ink-floored theme. BossTerm's
 * `UiTheme` carries the same helper for the same reason.
 */
internal fun mix(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)
