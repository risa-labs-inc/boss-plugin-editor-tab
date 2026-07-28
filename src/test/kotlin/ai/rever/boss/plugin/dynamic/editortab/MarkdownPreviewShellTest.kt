package ai.rever.boss.plugin.dynamic.editortab

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the preview page the plugin hands to the browser: what the page is
 * allowed to do, and how markdown-derived HTML reaches its DOM.
 *
 * The page's behaviour is asserted separately, against a real DOM, by
 * `src/test/js/preview-dom.test.mjs`, which loads the page in a headless browser
 * — see that file's header for how to run it.
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

    /**
     * Scope note: this greps **the shell**, which is the code that decides how
     * markdown-derived HTML reaches the document. It says nothing about the
     * vendored libraries — `mermaid.min.js` writes markup strings internally, as
     * its own business. What keeps *mermaid's* output from carrying markup a
     * diagram supplied is `htmlLabels: false` plus the policy plus `scrubRendered`,
     * asserted separately below and in `preview-dom.test.mjs`.
     */
    @Test
    fun `the shell never assigns a markup string to the DOM`() {
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
        // Whitespace-tolerant: the guarantee is that marked's output is the
        // sanitizer's argument, not the exact formatting of the call.
        assertTrue(
            Regex("""DOMPurify\.sanitize\(\s*marked\.parse\(""").containsMatchIn(html),
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

    /**
     * No count assertion here on purpose: the guarantee is that *every* script the
     * page carries is nonced, which holds for any number of them. The count is
     * load-bearing only in the interpolation test below, where it is what shows an
     * injected script tag did not appear.
     */
    @Test
    fun `every script the page carries is nonced`() {
        val html = shell()
        val scriptTags = Regex("""<script(\s[^>]*)?>""").findAll(html).toList()
        assertTrue(scriptTags.isNotEmpty(), "page carries no script tags at all")
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
    fun `mermaid draws labels as svg text rather than html`() {
        val html = shell()
        // Verified in preview-dom.test.mjs: with HTML labels on, 'strict' still lets
        // a quoted label become live HTML in a foreignObject.
        assertTrue(html.contains("htmlLabels: false"), "label HTML must be off globally")
        assertTrue(
            html.contains("flowchart: { htmlLabels: false }"),
            "the flowchart default re-enables label HTML, so it must be set too"
        )
    }

    @Test
    fun `rendered diagrams are scrubbed on both mermaid outcomes`() {
        val html = shell()
        assertTrue(
            Regex("""mermaid\.run\(\{\s*nodes:\s*diagrams\s*\}\)\.then\(scrub,\s*scrub\)""")
                .containsMatchIn(html),
            "mermaid's output must be scrubbed whether the run resolves or rejects"
        )
        assertTrue(html.contains("function scrubRendered(root)"))
        // Scoped to the diagram roots, not the whole article: that is the output
        // path the scrub exists for, and it keeps a document with one mermaid fence
        // from re-walking every element in it on every debounced re-render.
        assertTrue(
            html.contains("var diagrams = el.querySelectorAll('pre.mermaid')") &&
                html.contains("diagrams.forEach(function(d) { scrubRendered(d); })"),
            "the scrub must run over the diagram roots rather than all of #content"
        )
    }

    @Test
    fun `the scrub drops the elements that can re-point an attribute later`() {
        val html = shell()
        // SMIL can set an attribute after a one-pass scrub has been over the tree,
        // so <animate>/<set> and friends are removed outright. Verified against a
        // real DOM in preview-dom.test.mjs.
        listOf("'animate'", "'animatemotion'", "'animatetransform'", "'set'").forEach { name ->
            assertTrue(html.contains("name === $name"), "scrub does not remove $name elements")
        }
    }

    @Test
    fun `the scrub tests the scheme with url whitespace removed`() {
        val html = shell()
        // A browser strips TAB/LF/CR out of a URL before parsing its scheme, so
        // `java<TAB>script:` runs while `/^javascript:/` does not match it.
        assertTrue(
            html.contains("""replace(/[\u0000-\u0020]/g, '')"""),
            "the scheme test must run against a value with control characters removed"
        )
        assertTrue(html.contains("""/^(?:javascript|vbscript):/i"""))
    }

    @Test
    fun `theme choice does not change the policy`() {
        assertEquals(cspDirectives(shell(dark = false)), cspDirectives(shell(dark = true)))
    }

    // ---------- what a clicked link may reach ----------

    @Test
    fun `only outward-pointing schemes are handed to the desktop`() {
        listOf(
            "https://example.invalid/x",
            "http://example.invalid/x",
            "HTTPS://example.invalid/x",
            "mailto:someone@example.invalid"
        ).forEach { assertTrue(isBrowsableLink(it), "$it should open in the system browser") }
    }

    @Test
    fun `a resolved local path is not handed to the desktop`() {
        // Every link in the preview is routed through the new-tab callback, and a
        // relative href — which DOMPurify keeps by design — has already resolved
        // against <base href> into an absolute file: URL by the time it arrives.
        // Desktop.browse on one of these is a local-file open, and on Windows goes
        // through ShellExecute, which for some target types launches rather than
        // views.
        listOf(
            "file:///Users/someone/.ssh/id_rsa",
            "file:///Users/someone/docs/setup.exe",
            "file:///etc/passwd",
            "jar:file:///tmp/x.jar!/y",
            "smb://host/share/x",
            "javascript:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "vbscript:msgbox(1)",
            "custom-handler:payload",
            "",
            "not a uri at all",
            "./OTHER.md"
        ).forEach { assertFalse(isBrowsableLink(it), "$it must not be handed to the desktop") }
    }

    // ---------- where the sanitizer is extracted to ----------

    @Test
    fun `the extraction directory is unguessable and owner-only`() {
        // The sanitizer every guarantee above rests on is extracted to this
        // directory. A fixed name under the system temp dir is one another local
        // account can create first — mkdirs() succeeds silently on a directory it
        // does not own — and populate with a passthrough purify.min.js that still
        // reports isSupported.
        val first = createPreviewTmpDir()
        val second = createPreviewTmpDir()
        try {
            assertTrue(first.isDirectory, "temp dir was not created")
            assertNotEquals(
                first.name, second.name,
                "the extraction directory name must not be predictable"
            )
            val posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
            if (posix) {
                assertEquals(
                    "rwx------",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(first.toPath())),
                    "nothing but this user may write into the directory holding the sanitizer"
                )
            }
        } finally {
            first.delete()
            second.delete()
        }
    }
}
