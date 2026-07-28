package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.browser.BrowserConfig
import ai.rever.boss.plugin.browser.BrowserHandle
import ai.rever.boss.plugin.browser.BrowserService
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import compose.icons.FeatherIcons
import compose.icons.feathericons.EyeOff
import kotlinx.coroutines.delay
import java.io.File
import java.util.Base64

/** View mode for Markdown files: plain editor, side-by-side, or rendered preview only. */
enum class MarkdownViewMode(val displayName: String) {
    EDIT("Edit"),
    SPLIT("Split"),
    PREVIEW("Preview")
}

/**
 * Rendered markdown preview pane backed by the host's BrowserService (JxBrowser),
 * following the same create/dispose pattern as the fluck-chatgpt plugin.
 *
 * Rendering happens inside the browser page with a vendored marked.js
 * (plugin resource `markdown-preview/marked.umd.js`), so the plugin JAR stays thin
 * and no markdown library is needed on the host classloader.
 *
 * Content is handed to the page base64-encoded (initial content embedded in the
 * HTML shell, live updates via [BrowserHandle.executeJavaScript]) which sidesteps
 * all JS/HTML escaping concerns. Updates replace `#content` in place, so the
 * scroll position survives re-renders.
 *
 * The previewed document is not the operator's own writing — it is whatever the
 * repository they opened happens to contain — so the page treats it as data
 * throughout. marked emits HTML verbatim, including any HTML the markdown carried,
 * so its output is passed through DOMPurify (vendored as
 * `markdown-preview/purify.min.js`) and the resulting nodes are attached to the
 * document; nothing in the page assigns a markup string to the DOM. The shell also
 * carries a Content-Security-Policy that admits only what the preview itself needs:
 * the three vendored libraries plus the shell's own inline script, matched by a
 * per-page nonce. See [buildPreviewHtml].
 *
 * @param markdown Current markdown source; changes are debounced then pushed to the page.
 * @param baseDir Directory of the .md file — becomes the page's `<base href>` so
 *   relative image/link paths resolve.
 * @param darkTheme Chooses the dark or light preview stylesheet.
 */
@Composable
fun MarkdownPreviewPane(
    browserService: BrowserService?,
    markdown: String,
    baseDir: String,
    darkTheme: Boolean,
    modifier: Modifier = Modifier
) {
    if (browserService == null || !browserService.isAvailable()) {
        PreviewUnavailableMessage(modifier)
        return
    }

    var browserHandle by remember { mutableStateOf<BrowserHandle?>(null) }
    var pageReady by remember { mutableStateOf(false) }

    // (Re)create the browser when the file's directory or theme changes.
    LaunchedEffect(baseDir, darkTheme) {
        pageReady = false
        browserHandle?.dispose()

        val htmlFile = writePreviewShell(markdown, baseDir, darkTheme)
        val handle = browserService.createBrowser(
            BrowserConfig(
                url = htmlFile.toURI().toString(),
                enableDevTools = false,
                enableDownloads = false,
                enableFullscreen = false
            )
        )
        if (handle != null) {
            // External links (forced to target=_blank by the shell's JS) open in the
            // system browser instead of navigating the preview away.
            handle.setOpenInNewTabCallback { url -> openInSystemBrowser(url) }
            browserHandle = handle
            pageReady = true
        }
    }

    // Push edits into the live page, debounced so typing doesn't spam the bridge.
    LaunchedEffect(markdown, pageReady) {
        if (!pageReady) return@LaunchedEffect
        delay(200)
        val handle = browserHandle ?: return@LaunchedEffect
        if (handle.isValid) {
            val b64 = Base64.getEncoder().encodeToString(markdown.toByteArray(Charsets.UTF_8))
            handle.executeJavaScript("window.__setMarkdownB64 && window.__setMarkdownB64(\"$b64\")")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            browserHandle?.dispose()
            browserHandle = null
        }
    }

    Box(modifier = modifier) {
        val handle = browserHandle
        if (handle != null && handle.isValid) {
            handle.Content()
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun PreviewUnavailableMessage(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = FeatherIcons.EyeOff,
            contentDescription = null,
            modifier = Modifier.size(32.dp),
            tint = MaterialTheme.colors.onBackground.copy(alpha = 0.4f)
        )
        Box(modifier = Modifier.height(8.dp))
        Text(
            text = "Markdown preview unavailable",
            fontSize = 13.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
        )
        Text(
            text = "Browser service (JxBrowser) is not available",
            fontSize = 11.sp,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.4f)
        )
    }
}

private fun openInSystemBrowser(url: String) {
    try {
        if (java.awt.Desktop.isDesktopSupported()) {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        }
    } catch (e: Exception) {
        System.err.println("[MarkdownPreview] Failed to open link: ${e.message}")
    }
}

// ========== HTML shell generation ==========

private val previewTmpDir: File by lazy {
    File(System.getProperty("java.io.tmpdir"), "boss-md-preview").apply { mkdirs() }
}

/**
 * Extracts a vendored JS library from plugin resources to a shared temp file
 * (one copy for all tabs) and returns its file:// URI. The page references it by
 * absolute URI because `<base href>` points at the markdown file's directory.
 */
private fun extractJsResource(name: String): String {
    val target = File(previewTmpDir, name)
    val resource = object {}.javaClass.classLoader.getResourceAsStream("markdown-preview/$name")
        ?: error("markdown-preview/$name missing from plugin resources")
    resource.use { input ->
        val bytes = input.readBytes()
        if (!target.exists() || target.length() != bytes.size.toLong()) {
            target.writeBytes(bytes)
        }
    }
    return target.toURI().toString()
}

private val markedJsUri: String by lazy { extractJsResource("marked.umd.js") }
private val mermaidJsUri: String by lazy { extractJsResource("mermaid.min.js") }

/**
 * DOMPurify, the sanitizer marked's own documentation points callers at (marked
 * emits the HTML a document carries as-is and has no sanitize option of its own).
 *
 * Vendored verbatim from the published npm artifact — `dompurify@3.4.11`,
 * `dist/purify.min.js`, tarball
 * `https://registry.npmjs.org/dompurify/-/dompurify-3.4.11.tgz`
 * (sha512-zhlUV12GsaRzMsf9q5M254YhA4+VuF0fG+QFqu6aYpoGlKtz+w8//jBcGVYBgQkR5GHjUomejY84AV+/uPbWdw==),
 * so the file still matches `npm pack dompurify@3.4.11` byte for byte and its own
 * banner records the version and upstream, the way `marked.umd.js` does.
 */
private val purifyJsUri: String by lazy { extractJsResource("purify.min.js") }

/**
 * Fresh nonce per preview page. The shell's Content-Security-Policy names this
 * value as the only way a script may run, and every `<script>` the shell writes
 * carries it — so a script tag that arrives from anywhere else has no way to
 * execute, whatever put it in the document.
 */
internal fun newScriptNonce(): String {
    val bytes = ByteArray(16)
    java.security.SecureRandom().nextBytes(bytes)
    // Base64url: the CSP nonce grammar accepts '-' and '_', and dropping the
    // padding keeps the value free of '=' too.
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Escapes a value for use inside a double-quoted HTML attribute. Every attribute
 * the shell interpolates into is double-quoted, so an apostrophe is left alone —
 * which keeps the policy's `'none'` and `'nonce-…'` readable in the emitted page.
 *
 * The URIs the shell interpolates come from [File.toURI], which already
 * percent-encodes the characters that would end an attribute. Escaping here as
 * well keeps that invariant local to the template instead of resting on the
 * encoding rules of a type two layers away.
 */
private fun htmlAttr(value: String): String = value
    .replace("&", "&amp;")
    .replace("\"", "&quot;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

/** Writes the per-preview HTML shell to a temp file and returns it. */
private fun writePreviewShell(markdown: String, baseDir: String, dark: Boolean): File {
    val initialB64 = Base64.getEncoder().encodeToString(markdown.toByteArray(Charsets.UTF_8))
    val baseUri = File(baseDir).toURI().toString() // directory URI, ends with '/'
    val html = buildPreviewHtml(
        initialB64 = initialB64,
        baseUri = baseUri,
        markedUri = markedJsUri,
        mermaidUri = mermaidJsUri,
        purifyUri = purifyJsUri,
        nonce = newScriptNonce(),
        dark = dark
    )
    val file = File.createTempFile("preview-", ".html", previewTmpDir)
    file.deleteOnExit()
    file.writeText(html)
    return file
}

/**
 * Builds the preview page.
 *
 * Two properties of the result are load-bearing and covered by
 * `MarkdownPreviewShellTest`:
 *
 * 1. **Nothing in the page assigns a markup string to the DOM.** marked's output
 *    goes through `DOMPurify.sanitize(..., RETURN_DOM_FRAGMENT)` and the returned
 *    nodes are appended; the render-error path writes `textContent`. So the page
 *    contains no `innerHTML` write at all, and if DOMPurify is missing the render
 *    reports an error rather than falling back to the raw HTML.
 * 2. **The Content-Security-Policy admits only what the preview needs.**
 *    `default-src 'none'` denies by default, then:
 *    - `script-src 'nonce-…'` — the three vendored libraries and the shell's own
 *      inline script all carry the nonce. No `'unsafe-inline'`, which is also what
 *      makes an `on…=` attribute inert, and no host source, so nothing can be
 *      pulled in over the network.
 *    - `style-src 'unsafe-inline'` — required: mermaid styles a rendered diagram by
 *      inserting a `<style>` element into the SVG it builds, and the shell's own
 *      stylesheet is inline. Styles are not script, and with the fetch directives
 *      below a stylesheet has nowhere to send anything.
 *    - `img-src`/`media-src file: data:` — a document's relative image paths resolve
 *      against `<base href>` to `file:` URLs, and marked passes `data:` images
 *      through. Remote schemes are deliberately absent: a preview must not become a
 *      callback for the repository being read, so a remote `<img>` (a README badge,
 *      say) does not load.
 *    - `base-uri file:` — keeps the shell's own `<base href>`, which relative image
 *      and link paths depend on, while refusing to let the document re-point it
 *      somewhere else.
 *    - `object-src`/`frame-src`/`connect-src`/`font-src 'none'` and
 *      `form-action 'none'` — none of them are things this page does.
 *
 *    `require-trusted-types-for` is deliberately not set: mermaid builds its SVG
 *    with plain-string DOM writes, and enforcing Trusted Types would stop diagrams
 *    from rendering at all.
 */
internal fun buildPreviewHtml(
    initialB64: String,
    baseUri: String,
    markedUri: String,
    mermaidUri: String,
    purifyUri: String,
    nonce: String,
    dark: Boolean
): String {
    val colors = if (dark) {
        // GitHub-dark-inspired palette
        PreviewColors(
            bg = "#0d1117", fg = "#e6edf3", border = "#30363d",
            codeBg = "#161b22", link = "#4493f8", muted = "#8d96a0"
        )
    } else {
        PreviewColors(
            bg = "#ffffff", fg = "#1f2328", border = "#d1d9e0",
            codeBg = "#f6f8fa", link = "#0969da", muted = "#59636e"
        )
    }
    val csp = listOf(
        "default-src 'none'",
        "script-src 'nonce-$nonce'",
        "style-src 'unsafe-inline'",
        "img-src file: data:",
        "media-src file: data:",
        "font-src 'none'",
        "connect-src 'none'",
        "frame-src 'none'",
        "object-src 'none'",
        "base-uri file:",
        "form-action 'none'"
    ).joinToString("; ")
    return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="${htmlAttr(csp)}">
<base href="${htmlAttr(baseUri)}">
<style>
  * { box-sizing: border-box; }
  html, body { margin: 0; padding: 0; background: ${colors.bg}; }
  .markdown-body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Helvetica, Arial, sans-serif;
    font-size: 15px; line-height: 1.6; color: ${colors.fg};
    max-width: 860px; margin: 0 auto; padding: 24px 32px 64px;
    word-wrap: break-word;
  }
  .markdown-body h1, .markdown-body h2 {
    padding-bottom: .3em; border-bottom: 1px solid ${colors.border};
  }
  .markdown-body h1, .markdown-body h2, .markdown-body h3,
  .markdown-body h4, .markdown-body h5, .markdown-body h6 {
    margin-top: 24px; margin-bottom: 16px; font-weight: 600; line-height: 1.25;
  }
  .markdown-body a { color: ${colors.link}; text-decoration: none; }
  .markdown-body a:hover { text-decoration: underline; }
  .markdown-body code, .markdown-body pre {
    font-family: ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, monospace;
    font-size: 85%;
  }
  .markdown-body code { background: ${colors.codeBg}; padding: .2em .4em; border-radius: 6px; }
  .markdown-body pre {
    background: ${colors.codeBg}; padding: 16px; border-radius: 6px;
    overflow-x: auto; line-height: 1.45;
  }
  .markdown-body pre code { background: transparent; padding: 0; }
  .markdown-body blockquote {
    margin: 0 0 16px; padding: 0 1em; color: ${colors.muted};
    border-left: .25em solid ${colors.border};
  }
  .markdown-body table { border-collapse: collapse; margin-bottom: 16px; display: block; overflow-x: auto; }
  .markdown-body th, .markdown-body td { border: 1px solid ${colors.border}; padding: 6px 13px; }
  .markdown-body th { font-weight: 600; background: ${colors.codeBg}; }
  .markdown-body img { max-width: 100%; }
  .markdown-body hr { height: 1px; border: 0; background: ${colors.border}; margin: 24px 0; }
  .markdown-body ul, .markdown-body ol { padding-left: 2em; }
  .markdown-body li + li { margin-top: .25em; }
  .markdown-body input[type="checkbox"] { margin-right: .5em; }
  .markdown-body pre.mermaid {
    background: transparent; padding: 8px 0; text-align: center;
  }
</style>
<script nonce="${htmlAttr(nonce)}" src="${htmlAttr(purifyUri)}"></script>
<script nonce="${htmlAttr(nonce)}" src="${htmlAttr(markedUri)}"></script>
<script nonce="${htmlAttr(nonce)}" src="${htmlAttr(mermaidUri)}"></script>
</head>
<body>
<article id="content" class="markdown-body"></article>
<script nonce="${htmlAttr(nonce)}">
  // The document being previewed is untrusted input: it is whatever the opened
  // repository contains. marked reproduces any HTML the markdown carried, so its
  // output is data until DOMPurify has been over it.
  //
  // The profile is HTML only — the element set GitHub renders markdown into, which
  // also leaves out inline SVG and MathML. Tables, task-list checkboxes, footnote
  // anchors, <details>, alignment and width/height attributes and relative image
  // paths all survive it. FORBID_TAGS then names two the profile would otherwise
  // decide: <style>, so the page's own stylesheet stays the only one, and <form>,
  // because a preview has nothing to submit. RETURN_DOM_FRAGMENT hands back nodes
  // instead of a markup string, so the sanitized result is never re-parsed and no
  // markup string is ever assigned to this document.
  var PURIFY_CONFIG = {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['style', 'form'],
    RETURN_DOM_FRAGMENT: true
  };

  if (window.mermaid) {
    mermaid.initialize({
      startOnLoad: false,
      // Mermaid renders a diagram by building an SVG string and writing it into
      // the page itself — a path the sanitize call above never sees. Two settings
      // keep what it writes to drawing instructions:
      //
      // securityLevel 'strict' entity-encodes label text, runs it through
      // mermaid's own bundled DOMPurify and ignores click directives. It is also
      // mermaid's default, but it is stated rather than assumed because a diagram
      // may carry an `%%{init: …}%%` directive; mermaid keeps securityLevel in its
      // 'secure' list, so a level set here cannot be lowered from inside a diagram.
      securityLevel: 'strict',
      // htmlLabels false is the one that settles it. With HTML labels on — the
      // flowchart default — a quoted label is placed in a foreignObject as live
      // HTML, and 'strict' does not prevent that: a label of
      // `A["<img src=x onerror=…>"]` really does become an img element in the
      // page. With them off, every label is drawn as SVG <text>, so a label can
      // carry no markup at all. Diagrams render the same either way; labels lose
      // only HTML formatting inside them.
      htmlLabels: false,
      flowchart: { htmlLabels: false },
      theme: ${if (dark) "'dark'" else "'default'"}
    });
  }

  // Second line on mermaid's output path: whatever the SVG turned out to contain,
  // no script element, inline handler or script-bearing URL survives in it. In
  // normal operation this removes nothing (mermaid emits neither), and it leaves
  // the drawing alone — geometry, foreignObject content, diagram-internal `#id`
  // references (markers and <use> need them) and data: images.
  function scrubRendered(root) {
    var all = root.querySelectorAll('*');
    for (var i = 0; i < all.length; i++) {
      var node = all[i];
      var name = (node.nodeName || '').toLowerCase();
      if (name === 'script' || name === 'iframe' || name === 'object' || name === 'embed') {
        node.remove();
        continue;
      }
      var attrs = node.attributes;
      for (var j = attrs.length - 1; j >= 0; j--) {
        var attrName = attrs[j].name;
        var lower = attrName.toLowerCase();
        var value = attrs[j].value || '';
        if (lower.indexOf('on') === 0) {
          node.removeAttribute(attrName);
        } else if (/^\s*(?:javascript|vbscript)\s*:/i.test(value)) {
          node.removeAttribute(attrName);
        } else if ((lower === 'href' || lower === 'xlink:href') && /^\s*data\s*:/i.test(value)) {
          node.removeAttribute(attrName);
        }
      }
    }
  }

  function showRenderError(message) {
    var el = document.getElementById('content');
    if (!el) return;
    el.textContent = '';
    var pre = document.createElement('pre');
    pre.textContent = message;
    el.appendChild(pre);
  }

  window.__setMarkdownB64 = function(b64) {
    try {
      // No sanitizer, no render: an unsanitized document must never be the
      // fallback for a library that failed to load. The isSupported half is the
      // load-bearing one — DOMPurify that reports itself unsupported returns its
      // input untouched rather than throwing, so without this check a render would
      // proceed on unsanitized HTML and look like it had worked.
      if (!window.DOMPurify || !window.DOMPurify.isSupported) {
        throw new Error('HTML sanitizer unavailable');
      }
      var bytes = Uint8Array.from(atob(b64), function(c) { return c.charCodeAt(0); });
      var md = new TextDecoder('utf-8').decode(bytes);
      var el = document.getElementById('content');
      var fragment = DOMPurify.sanitize(marked.parse(md, { gfm: true }), PURIFY_CONFIG);
      el.textContent = '';
      el.appendChild(fragment);
      // Route every link through the new-tab path so the host can hand it to
      // the system browser instead of navigating the preview away.
      el.querySelectorAll('a[href]').forEach(function(a) { a.target = '_blank'; });
      // Turn ```mermaid fences into rendered diagrams. textContent un-escapes the
      // HTML entities marked produced, giving mermaid the raw diagram source.
      var fences = el.querySelectorAll('pre > code.language-mermaid');
      if (fences.length && window.mermaid) {
        fences.forEach(function(code) {
          var diagram = document.createElement('pre');
          diagram.className = 'mermaid';
          diagram.textContent = code.textContent;
          code.parentElement.replaceWith(diagram);
        });
        var scrub = function() { scrubRendered(el); };
        // On a bad diagram mermaid shows its error bomb in place; the rejection
        // handler both keeps that from surfacing as an unhandled promise error and
        // scrubs whatever did land.
        mermaid.run({ nodes: el.querySelectorAll('pre.mermaid') }).then(scrub, scrub);
      }
    } catch (e) {
      showRenderError('Markdown render error: ' + String(e));
    }
  };

  // Test seam: `src/test/js/preview-dom.test.mjs` drives these directly so the
  // sanitize and scrub paths are asserted against a real DOM.
  window.__previewInternals = { purifyConfig: PURIFY_CONFIG, scrubRendered: scrubRendered };

  window.__setMarkdownB64("$initialB64");
</script>
</body>
</html>
""".trimIndent()
}

private data class PreviewColors(
    val bg: String,
    val fg: String,
    val border: String,
    val codeBg: String,
    val link: String,
    val muted: String
)
