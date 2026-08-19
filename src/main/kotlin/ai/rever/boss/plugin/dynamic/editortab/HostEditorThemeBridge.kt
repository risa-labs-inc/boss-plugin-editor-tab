package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.ui.BossThemeColors
import ai.rever.bosseditor.theme.ChromeColors
import ai.rever.bosseditor.theme.EditorChrome
import ai.rever.bosseditor.theme.EditorTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

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
 * Name the derived theme is registered under, so BossEditor itself can resolve it:
 * its settings panel previews `EditorTheme.forSettings(...)` through the same
 * registry entry the tabs render with.
 *
 * Aliases the library's constant rather than repeating the string - the two halves
 * have to agree or the preview silently shows Dark.
 */
const val FOLLOW_HOST_THEME_NAME: String = EditorTheme.FOLLOW_HOST_THEME

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
    val theme = remember(tokens) { buildHostEditorTheme(tokens) }
    // Registering writes a process-global registry, so it belongs in an effect: a
    // `remember {}` calculation also runs for a composition that is then abandoned,
    // and it would publish a theme from a tab that never appeared.
    LaunchedEffect(theme) { EditorTheme.registerTheme(theme) }
    return theme
}

/**
 * Pushes the host's chrome tokens into the bundled BossEditor, so its settings
 * panel, dropdowns and color picker follow the host instead of painting the fixed
 * dark surface with a blue accent they carried as constants.
 *
 * Call this from anywhere the plugin composes BossEditor UI: the chrome holder is
 * process-global (per plugin classloader, so per window), and the settings panel can
 * be open with no editor tab in sight.
 */
@Composable
fun ApplyHostChromeToEditor() {
    val chrome = ChromeColors(
        surface = BossThemeColors.SurfaceColor,
        background = BossThemeColors.BackgroundColor,
        accent = BossThemeColors.AccentColor,
        border = BossThemeColors.BorderColor,
        textPrimary = BossThemeColors.TextPrimary,
        textSecondary = BossThemeColors.TextSecondary,
        textMuted = BossThemeColors.TextMuted,
        // Content on an accent fill: the host has no token for it, and neither white
        // nor the panel's own text color is right for every theme's accent.
        onAccent = ChromeColors.contentFor(BossThemeColors.AccentColor),
    )
    LaunchedEffect(chrome) { EditorChrome.apply(chrome) }
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
 * Every token is first composited onto the floor, so a translucent chrome token
 * ("white at 8% over the background" is a normal way to define a step) cannot
 * reach [mix] as a non-premultiplied triple and land as an opaque near-white fill.
 * Light vs dark is then taken from the floor against the host's own text color
 * rather than an absolute luminance bar: a mid-tone floor sits at ~0.2 relative
 * luminance, so a fixed `> 0.5f` test would put dark-theme pastels on a mid grey,
 * while the pair the host already balanced always answers correctly.
 *
 * Everything the host has an opinion about is overridden; everything else stays
 * curated.
 */
internal fun buildHostEditorTheme(raw: HostChromeTokens): EditorTheme {
    val t = raw.flattenedOntoFloor()
    val isLight = t.ink.luminance() > t.textPrimary.luminance()
    val base = if (isLight) EditorTheme.Light else EditorTheme.Dark

    // A surface one step off the floor, for the current line, fold placeholders and
    // inlay chips. `panel` is normally the host's chrome step above `ink`, but a
    // theme is free to make them equal (or near enough that the difference is not
    // visible), so fall back to a tint of the text color rather than painting a
    // highlight nobody can see.
    val subtle = t.panel.orIfIndistinctFrom(t.ink, mix(t.ink, t.textPrimary, 0.06f))
    // A brand token that collapses into the floor cannot carry a caret, a selection
    // or a match. The text color is the one color the host guarantees is readable
    // on its own floor, so it is the fallback for each of them.
    val signal = t.signal.orIfIndistinctFrom(t.ink, t.textPrimary)
    val warn = t.warn.orIfIndistinctFrom(t.ink, t.textPrimary)
    val data = t.data.orIfIndistinctFrom(t.ink, t.textPrimary)
    val alert = t.alert.orIfIndistinctFrom(t.ink, t.textPrimary)

    return EditorTheme(
        name = FOLLOW_HOST_THEME_NAME,
        isDark = !isLight,
        colors = base.colors.copy(
            // Floor and caret
            background = t.ink,
            text = t.textPrimary,
            caret = signal,
            // Opaque blends, not alpha: selection and match highlights are painted
            // under the text, and a translucent fill over a light floor washes the
            // glyphs out at exactly the moment the user is reading them.
            selectionBackground = mix(t.ink, signal, 0.30f),
            currentLineHighlight = subtle,
            marginLine = t.line,
            matchedBracketBackground = mix(t.ink, signal, 0.22f),
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
            error = alert,
            searchMatchBackground = mix(t.ink, warn, 0.35f),
            currentSearchMatchBackground = mix(t.ink, signal, 0.55f),
            hyperlink = data,
            markOccurrences = mix(t.ink, signal, 0.18f),

            minimapBackground = t.ink,
            minimapForeground = t.textPrimary,
            minimapViewport = t.textMuted.copy(alpha = 0.25f),
            minimapViewportBorder = t.textMuted.copy(alpha = 0.5f),
            minimapSelection = signal.copy(alpha = 0.35f),
            minimapSearchHighlight = warn,
            minimapOccurrence = mix(t.ink, signal, 0.25f),
            minimapError = alert,
            minimapWarning = warn,
            minimapInfo = data,
            minimapHint = t.textMuted,
            minimapCurrentLine = subtle,
            minimapSliderHover = t.textMuted.copy(alpha = 0.35f),
            minimapBorder = t.line,

            errorSquiggle = alert,
            warningSquiggle = warn,
            infoSquiggle = data,
            hintSquiggle = t.textMuted,
            gutterError = alert,
            gutterWarning = warn,
            gutterInfo = data,
            gutterHint = t.textMuted,

            inlayHintParameterBackground = subtle,
            inlayHintParameterForeground = t.textSecondary,
            inlayHintTypeBackground = mix(t.ink, data, 0.18f),
            inlayHintTypeForeground = data,
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

/**
 * Smallest component difference two colors can have and still read as two colors.
 *
 * Roughly 5/255 per channel. Exact inequality is not a usable test here: a panel
 * one step off the floor passes `!=` and paints an invisible highlight.
 */
private const val VISIBLE_DELTA = 0.02f

/** [this] unless it is indistinguishable from [floor], in which case [fallback]. */
private fun Color.orIfIndistinctFrom(floor: Color, fallback: Color): Color =
    if (visiblyDiffers(this, floor)) this else fallback

/** Whether [a] and [b] differ by at least [VISIBLE_DELTA] in any channel. */
internal fun visiblyDiffers(a: Color, b: Color): Boolean =
    abs(a.red - b.red) >= VISIBLE_DELTA ||
        abs(a.green - b.green) >= VISIBLE_DELTA ||
        abs(a.blue - b.blue) >= VISIBLE_DELTA

/**
 * Every token composited onto the floor, so the rest of the derivation only ever
 * blends opaque colors. The floor itself is composited onto black - it is the
 * bottom of the stack, and there is nothing behind it to show through.
 */
private fun HostChromeTokens.flattenedOntoFloor(): HostChromeTokens {
    val floor = ink.over(Color.Black)
    return HostChromeTokens(
        ink = floor,
        panel = panel.over(floor),
        line = line.over(floor),
        textPrimary = textPrimary.over(floor),
        textSecondary = textSecondary.over(floor),
        textMuted = textMuted.over(floor),
        signal = signal.over(floor),
        data = data.over(floor),
        alert = alert.over(floor),
        warn = warn.over(floor),
    )
}

/** Source-over compositing of [this] onto an opaque [backdrop]. */
private fun Color.over(backdrop: Color): Color =
    if (alpha >= 1f) this else mix(backdrop, copy(alpha = 1f), alpha)
