package ai.rever.boss.plugin.dynamic.editortab

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the preview page the plugin hands to the browser: what the page is
 * allowed to do, and how markdown-derived HTML reaches its DOM.
 *
 * The page's behaviour is asserted separately, against a real DOM, by
 * `src/test/js/preview-dom.test.mjs` — see AGENTS.md.
 */
class MarkdownPreviewShellTest {

    private val nonce = "TESTNONCEvalue123"

    private fun shell(
        markdown: String = "# hello",
        baseUri: String = "file:///docs/",
        dark: Boolean = false
    ): String = buildPreviewHtml(
        initialB64 = Base64.getEncoder().encodeToString(markdown.toByteArray()),
        baseUri = baseUri,
        markedUri = "file:///lib/marked.umd.js",
        mermaidUri = "file:///lib/mermaid.min.js",
        purifyUri = "file:///lib/purify.min.js",
        nonce = nonce,
        dark = dark
    )

    /** `content` of the page's Content-Security-Policy meta tag, split by directive. */
    private fun cspDirectives(html: String): Map<String, String> {
        val meta = Regex("""<meta http-equiv="Content-Security-Policy" content="([^"]*)">""")
            .find(html)
        assertNotNull(meta, "page carries no Content-Security-Policy")
        return meta.groupValues[1]
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .associate { directive ->
                val name = directive.substringBefore(' ')
                name to directive.removePrefix(name).trim()
            }
    }

    // ---------- how markdown-derived HTML reaches the DOM ----------

    @Test
    fun `page never assigns a markup string to the DOM`() {
        val html = shell()
        listOf("innerHTML", "outerHTML", "insertAdjacentHTML", "document.write", "createContextualFragment")
            .forEach { sink ->
                assertFalse(
                    html.contains(sink),
                    "preview page writes markup through $sink; markdown-derived HTML must be " +
                        "attached as sanitized nodes instead"
                )
            }
    }

    @Test
    fun `marked output is sanitized before it is attached`() {
        val html = shell()
        val parses = Regex("""marked\.parse\(""").findAll(html).count()
        assertEquals(1, parses, "expected exactly one marked.parse call to audit")
        assertTrue(
            html.contains("DOMPurify.sanitize(marked.parse(md, { gfm: true }), PURIFY_CONFIG)"),
            "marked's output must be handed straight to DOMPurify"
        )
        assertTrue(html.contains("RETURN_DOM_FRAGMENT: true"), "sanitizer must return nodes, not markup")
        assertTrue(html.contains("USE_PROFILES: { html: true }"), "sanitizer must use the HTML-only profile")
        assertTrue(html.contains("FORBID_TAGS: ['style', 'form']"))
    }

    @Test
    fun `render fails closed when the sanitizer is unavailable`() {
        val html = shell()
        assertTrue(
            html.contains("if (!window.DOMPurify || !window.DOMPurify.isSupported)"),
            "the render path must refuse to run without a working sanitizer"
        )
        assertTrue(html.contains("throw new Error('HTML sanitizer unavailable')"))
    }

    @Test
    fun `the render error path writes text, not markup`() {
        val html = shell()
        assertTrue(html.contains("pre.textContent = message"))
        assertTrue(html.contains("showRenderError('Markdown render error: ' + String(e))"))
    }

    @Test
    fun `the sanitizer ships as a plugin resource at a pinned version`() {
        val stream = javaClass.classLoader.getResourceAsStream("markdown-preview/purify.min.js")
        assertNotNull(stream, "markdown-preview/purify.min.js is missing from plugin resources")
        val banner = stream.use { it.readBytes() }.decodeToString().lineSequence().first()
        assertTrue(
            banner.contains("DOMPurify 3.4.11"),
            "vendored sanitizer banner should pin the version; was: $banner"
        )
    }

    // ---------- Content-Security-Policy ----------

    @Test
    fun `policy denies by default and allows only the preview's own machinery`() {
        val directives = cspDirectives(shell())
        assertEquals("'none'", directives["default-src"])
        assertEquals("'nonce-$nonce'", directives["script-src"])
        // mermaid styles a diagram by inserting a <style> element into its SVG, and
        // the shell's own stylesheet is inline.
        assertEquals("'unsafe-inline'", directives["style-src"])
        // Relative image paths resolve against <base href> to file: URLs.
        assertEquals("file: data:", directives["img-src"])
        assertEquals("file: data:", directives["media-src"])
        assertEquals("file:", directives["base-uri"])
        listOf("font-src", "connect-src", "frame-src", "object-src", "form-action").forEach {
            assertEquals("'none'", directives[it], "$it should be denied")
        }
    }

    @Test
    fun `policy permits no remote load and no inline script`() {
        val directives = cspDirectives(shell())
        val scriptSrc = directives.getValue("script-src")
        assertFalse(scriptSrc.contains("unsafe-inline"), "inline scripts and on… handlers must stay inert")
        assertFalse(scriptSrc.contains("unsafe-eval"))
        directives.forEach { (name, value) ->
            listOf("http:", "https:", "ws:", "wss:", "//", "*").forEach { remote ->
                assertFalse(
                    value.contains(remote),
                    "$name allows a remote source ($remote); the preview loads nothing over the network"
                )
            }
        }
    }

    @Test
    fun `every script the page carries is nonced`() {
        val html = shell()
        val scriptTags = Regex("""<script(\s[^>]*)?>""").findAll(html).toList()
        assertEquals(4, scriptTags.size, "expected the three vendored libraries plus the shell's own script")
        scriptTags.forEach { tag ->
            assertTrue(
                tag.value.contains("""nonce="$nonce""""),
                "script tag without the page nonce cannot run: ${tag.value}"
            )
        }
    }

    @Test
    fun `each page gets a fresh nonce`() {
        val nonces = (1..200).map { newScriptNonce() }
        assertEquals(nonces.size, nonces.toSet().size, "nonces must not repeat across pages")
        nonces.forEach {
            assertTrue(it.length >= 16, "nonce too short to be unguessable: $it")
            assertTrue(Regex("""^[A-Za-z0-9_-]+$""").matches(it), "nonce is not a CSP base64 value: $it")
        }
    }

    // ---------- template interpolation ----------

    @Test
    fun `interpolated attribute values cannot end their attribute`() {
        val html = shell(baseUri = """file:///docs/a"><script>x</script><b foo='y'/""")
        assertFalse(html.contains("""<base href="file:///docs/a">"""), "base attribute was cut short")
        assertTrue(html.contains("&quot;"), "quote in an interpolated URI should be escaped")
        assertTrue(html.contains("&lt;script&gt;"), "angle brackets in an interpolated URI should be escaped")
        // Still exactly the four script tags the page is supposed to have.
        assertEquals(4, Regex("""<script(\s[^>]*)?>""").findAll(html).count())
    }

    // ---------- mermaid ----------

    @Test
    fun `mermaid is initialized at its strict security level`() {
        val html = shell()
        assertTrue(
            html.contains("securityLevel: 'strict'"),
            "mermaid writes its SVG into the page itself, so its own escaping must be on"
        )
    }

    @Test
    fun `rendered diagrams are scrubbed on both mermaid outcomes`() {
        val html = shell()
        assertTrue(
            html.contains("mermaid.run({ nodes: el.querySelectorAll('pre.mermaid') }).then(scrub, scrub)"),
            "mermaid's output must be scrubbed whether the run resolves or rejects"
        )
        assertTrue(html.contains("function scrubRendered(root)"))
    }

    @Test
    fun `theme choice does not change the policy`() {
        assertEquals(cspDirectives(shell(dark = false)), cspDirectives(shell(dark = true)))
    }
}
