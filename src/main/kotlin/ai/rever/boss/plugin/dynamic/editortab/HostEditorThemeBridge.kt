package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.ui.BossThemeColors
import ai.rever.bosseditor.theme.ChromeColors
import ai.rever.bosseditor.theme.EditorChrome
import ai.rever.bosseditor.theme.EditorTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
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
val FOLLOW_HOST_THEME_NAME: String = EditorTheme.FOLLOW_HOST_THEME

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
    val tokens = hostChromeTokens()
    val theme = remember(tokens) { buildHostEditorTheme(tokens) }
    // SideEffect, not LaunchedEffect: both writes publish composition state into a
    // non-Compose global, and LaunchedEffect runs a frame later - long enough for
    // the settings panel, which resolves the follow-host theme through this same
    // registry in the same pass, to render one frame of Dark first. SideEffect runs
    // after the composition is applied, so it still skips an abandoned composition,
    // and both calls are idempotent (EditorChrome.apply no-ops on equal colors).
    SideEffect { EditorTheme.registerTheme(theme) }
    return theme
}

/**
 * Publishes the host theme to BossEditor without rendering anything: registers the
 * derived theme and pushes the chrome tokens.
 *
 * For UI that is BossEditor's own rather than an editor tab - the settings panel -
 * which needs both but has no use for the theme value.
 */
@Composable
fun EditorHostThemeEffects() {
    rememberHostEditorTheme()
    ApplyHostChromeToEditor()
}

/**
 * Publishes the derived theme and the host chrome once, outside composition.
 *
 * Called from plugin `register()`. `SideEffect` cannot cover the first frame on its
 * own: BossEditor's theme registry is a plain synchronized map, not snapshot state,
 * so a settings panel that reads it during the same composition pass sees nothing
 * and nothing invalidates that read. Seeding at load means the registry and the
 * chrome are never empty, and the effects keep them current from there.
 */
fun publishHostThemeToEditor() {
    val tokens = hostChromeTokensNow()
    EditorTheme.registerTheme(buildHostEditorTheme(tokens))
    EditorChrome.apply(hostChromeColors(tokens))
}

/** The host's live chrome tokens, read reactively when called from a composition. */
@Composable
private fun hostChromeTokens(): HostChromeTokens = hostChromeTokensNow()

/**
 * The host's chrome tokens as of now.
 *
 * Not `@Composable`, but snapshot reads are recorded by whatever snapshot observer is
 * active - so calling this from a composition still makes that composition follow a
 * theme switch, and calling it from `register()` simply reads the current values.
 */
private fun hostChromeTokensNow(): HostChromeTokens {
    return HostChromeTokens(
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
    val chrome = hostChromeColors(hostChromeTokens())
    // Same reasoning as the theme registration above: applied within the frame, and
    // idempotent, so a recomposition that changes nothing writes nothing.
    SideEffect { EditorChrome.apply(chrome) }
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

/**
 * BossEditor's chrome colors for a set of host tokens.
 *
 * The accent is composited before its content color is chosen: `contentFor` picks
 * light or dark by luminance, and a translucent accent read as a raw triple flips
 * that choice - the same premultiplication rule [flattenedOntoFloor] applies inside
 * the derivation. The rest are pairs the host has already balanced against each
 * other, so they pass through as they are.
 */
internal fun hostChromeColors(t: HostChromeTokens): ChromeColors {
    val accentOnPanel = t.signal.over(t.panel)
    return ChromeColors(
        surface = t.panel,
        background = t.ink,
        accent = t.signal,
        border = t.line,
        textPrimary = t.textPrimary,
        textSecondary = t.textSecondary,
        textMuted = t.textMuted,
        onAccent = ChromeColors.contentFor(accentOnPanel),
    )
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
    // Polarity comes from the pair the host balanced - unless that pair is unusable.
    // A text color that does not read on its own floor cannot decide which way up the
    // theme is (equal luminances answer "dark" for a white floor), so fall back to the
    // floor's own luminance and substitute a text color at the opposite extreme.
    val hostTextReads = contrastRatio(t.textPrimary, t.ink) >= TEXT_CONTRAST
    val isLight = if (hostTextReads) {
        t.ink.luminance() > t.textPrimary.luminance()
    } else {
        t.ink.luminance() > 0.5f
    }
    val base = if (isLight) EditorTheme.Light else EditorTheme.Dark

    // Code has to stay readable even if a host hands over a text color that sits on
    // its own floor. Resolved before anything derived from it: every fallback below
    // tints toward this, and tinting toward the raw token would carry the same
    // degeneracy forward - a host with `panel == ink` *and* `textPrimary == ink` got
    // a current-line highlight equal to the floor.
    val text = if (hostTextReads) t.textPrimary else if (isLight) Color.Black else Color.White
    // Supporting copy: line numbers, fold arrows, hints. Muted by design, but a token
    // that is muted into its own floor is just unreadable, so it falls back to a tint
    // of the vetted text color at roughly the weight a muted token should have.
    val textSecondary = t.textSecondary.orIfTooWeakToWash(t.ink, mix(t.ink, text, 0.70f))
    val textMuted = t.textMuted.orIfTooWeakToWash(t.ink, mix(t.ink, text, 0.45f))
    // A surface one step off the floor, for the current line, fold placeholders and
    // inlay chips. `panel` is normally the host's chrome step above `ink`, but a
    // theme is free to make them equal (or near enough that the difference is not
    // visible), so fall back to a tint of the text color rather than painting a
    // highlight nobody can see.
    val subtle = t.panel.orIfIndistinctFrom(t.ink, mix(t.ink, text, 0.06f))
    // A brand token has to survive being blended into the floor at 12%, which is a
    // stronger requirement than being distinguishable from it: a token 0.03 off the
    // floor clears the surface gate and then vanishes in every wash derived from it.
    // The text color is the one color the host guarantees reads on its own floor.
    // Second gate, against the surface rather than the floor: the lightest wash also
    // has to be distinguishable from the current-line highlight, which is a *surface*
    // step and so moves independently. A dim monochrome signal at 18% over ink lands
    // right on the panel color, making an occurrence highlight and a current line the
    // same thing.
    val signal = t.signal.washableOver(t.ink, alsoClearing = subtle, fallback = text)
    val warn = t.warn.washableOver(t.ink, alsoClearing = subtle, fallback = text)
    // Same gate: `data` also backs a fill (the inlay type chip), which sits next to a
    // parameter chip painted in the current-line surface.
    val data = t.data.washableOver(t.ink, alsoClearing = subtle, fallback = text)
    val alert = t.alert.orIfTooWeakToWash(t.ink, text)

    return EditorTheme(
        name = FOLLOW_HOST_THEME_NAME,
        isDark = !isLight,
        colors = base.colors.copy(
            // Floor and caret
            background = t.ink,
            text = text,
            caret = signal,
            // Opaque blends, not alpha: selection and match highlights are painted
            // under the text, and a translucent fill over a light floor washes the
            // glyphs out at exactly the moment the user is reading them.
            selectionBackground = mix(t.ink, signal, SELECTION_WASH),
            currentLineHighlight = subtle,
            marginLine = t.line,
            matchedBracketBackground = mix(t.ink, signal, BRACKET_WASH),
            matchedBracketForeground = text,

            // Gutter shares the floor, as the curated themes do; the hairline and
            // the numbers come from the host.
            gutterBackground = t.ink,
            gutterBorder = t.line,
            lineNumber = textMuted,
            lineNumberActive = text,
            foldIndicator = textMuted,
            foldBackground = t.ink,

            foldPlaceholderBackground = subtle,
            foldPlaceholderHover = mix(subtle, text, 0.10f),
            foldPlaceholderBorder = t.line,
            foldPlaceholderText = textSecondary,
            foldGuide = t.line,
            indentGuide = t.line,
            activeIndentGuide = textMuted,

            // Status colors the host owns. Syntax colors deliberately stay curated.
            error = alert,
            searchMatchBackground = mix(t.ink, warn, SEARCH_WASH),
            currentSearchMatchBackground = mix(t.ink, signal, CURRENT_SEARCH_WASH),
            hyperlink = data,
            markOccurrences = mix(t.ink, signal, OCCURRENCE_WASH),

            minimapBackground = t.ink,
            minimapForeground = text,
            minimapViewport = textMuted.copy(alpha = 0.25f),
            minimapViewportBorder = textMuted.copy(alpha = 0.5f),
            minimapSelection = signal.copy(alpha = 0.35f),
            minimapSearchHighlight = warn,
            minimapOccurrence = mix(t.ink, signal, OCCURRENCE_WASH),
            minimapError = alert,
            minimapWarning = warn,
            minimapInfo = data,
            minimapHint = textMuted,
            minimapCurrentLine = subtle,
            minimapSliderHover = textMuted.copy(alpha = 0.35f),
            minimapBorder = t.line,

            errorSquiggle = alert,
            warningSquiggle = warn,
            infoSquiggle = data,
            hintSquiggle = textMuted,
            gutterError = alert,
            gutterWarning = warn,
            gutterInfo = data,
            gutterHint = textMuted,

            inlayHintParameterBackground = subtle,
            inlayHintParameterForeground = textSecondary,
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
 * How far each fill painted on the editor floor is blended toward its brand token.
 *
 * Evenly spaced by 0.12 rather than the 0.04-to-0.05 steps this started with, because
 * a host is free to make `signal` and `warn` one color - a monochrome or amber theme
 * does - which puts every fill on a single axis. A token that only just clears
 * [WASH_CONTRAST] has a narrow channel range there, and 0.04 of it is invisible: with
 * the old spacing an occurrence highlight and a matched bracket were the same color.
 * The ceiling stays low enough that code drawn on the text-bearing four is readable,
 * with only the current match - the one the user is looking at - going near the top.
 *
 * Spacing alone is not enough (a token can collapse into the floor or into the
 * current-line surface, which is what the two gates below are for) and neither are
 * the gates alone. Both are pinned by the fixtures in `HostEditorThemeTest`.
 */
private const val OCCURRENCE_WASH = 0.12f
private const val BRACKET_WASH = 0.24f
private const val SELECTION_WASH = 0.36f
private const val SEARCH_WASH = 0.48f
private const val CURRENT_SEARCH_WASH = 0.60f

/**
 * Smallest component difference two colors can have and still read as two colors.
 *
 * Roughly 5/255 per channel. Exact inequality is not a usable test here: a panel
 * one step off the floor passes `!=` and paints an invisible highlight.
 */
private const val VISIBLE_DELTA = 0.02f

/**
 * Contrast a token needs against the floor to survive being blended into it.
 *
 * [VISIBLE_DELTA] answers "are these two colors different", which is the right
 * question for a surface step and the wrong one for a token that gets diluted to
 * 12%: a channel gate is also least honest on a light floor, where a 0.03 delta is
 * technically distinct and practically invisible. A WCAG-style ratio is what the
 * eye actually tracks, and 2:1 is roughly where a 12% wash of a color stops reading
 * as the floor.
 */
private const val WASH_CONTRAST = 2f

/** Contrast the host's own text color needs against its floor to be usable as code. */
private const val TEXT_CONTRAST = 3f

/** [this] unless it is indistinguishable from [floor], in which case [fallback]. */
private fun Color.orIfIndistinctFrom(floor: Color, fallback: Color): Color =
    if (visiblyDiffers(this, floor)) this else fallback

/** [this] unless it is too close to [floor] to survive a wash, in which case [fallback]. */
private fun Color.orIfTooWeakToWash(floor: Color, fallback: Color): Color =
    if (contrastRatio(this, floor) >= WASH_CONTRAST) this else fallback

/**
 * [this] if it can carry a wash over [floor] that is also distinguishable from
 * [alsoClearing], otherwise [fallback]. Checked at [OCCURRENCE_WASH], the lightest
 * wash and therefore the one that collides first.
 */
private fun Color.washableOver(floor: Color, alsoClearing: Color, fallback: Color): Color {
    val candidate = orIfTooWeakToWash(floor, fallback)
    val lightestWash = mix(floor, candidate, OCCURRENCE_WASH)
    return if (visiblyDiffers(lightestWash, alsoClearing)) candidate else fallback
}

/**
 * Tint for the run gutter icon: [base] unless it collapses into [floor], in which
 * case [text].
 *
 * A run icon is a click target, so "can the user find it" is the requirement, not
 * "does it look themed". Pure and internal so it can be pinned like the rest of the
 * derivation - the composable only supplies the three colors.
 */
internal fun runIconTint(base: Color, floor: Color, text: Color): Color =
    if (contrastRatio(base.over(floor), floor) >= WASH_CONTRAST) base.over(floor) else text

/** Whether [theme] is the one derived from the host, rather than a fixed choice. */
internal fun followsHostTheme(theme: EditorTheme): Boolean = theme.name == FOLLOW_HOST_THEME_NAME

/** WCAG relative-luminance contrast ratio between [a] and [b]. */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

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

/**
 * Source-over compositing of [this] onto an opaque [backdrop].
 *
 * Internal rather than private: the run-gutter icon blends a host token outside the
 * derivation, and the premultiplication guarantee has to hold there too.
 */
internal fun Color.over(backdrop: Color): Color =
    if (alpha >= 1f) this else mix(backdrop, copy(alpha = 1f), alpha)
