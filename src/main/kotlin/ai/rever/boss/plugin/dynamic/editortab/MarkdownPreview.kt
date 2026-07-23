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

/** Writes the per-preview HTML shell to a temp file and returns it. */
private fun writePreviewShell(markdown: String, baseDir: String, dark: Boolean): File {
    val initialB64 = Base64.getEncoder().encodeToString(markdown.toByteArray(Charsets.UTF_8))
    val baseUri = File(baseDir).toURI().toString() // directory URI, ends with '/'
    val html = buildPreviewHtml(initialB64, baseUri, markedJsUri, mermaidJsUri, dark)
    val file = File.createTempFile("preview-", ".html", previewTmpDir)
    file.deleteOnExit()
    file.writeText(html)
    return file
}

private fun buildPreviewHtml(
    initialB64: String,
    baseUri: String,
    markedUri: String,
    mermaidUri: String,
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
    return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<base href="$baseUri">
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
<script src="$markedUri"></script>
<script src="$mermaidUri"></script>
</head>
<body>
<article id="content" class="markdown-body"></article>
<script>
  if (window.mermaid) {
    mermaid.initialize({ startOnLoad: false, theme: ${if (dark) "'dark'" else "'default'"} });
  }
  window.__setMarkdownB64 = function(b64) {
    try {
      var bytes = Uint8Array.from(atob(b64), function(c) { return c.charCodeAt(0); });
      var md = new TextDecoder('utf-8').decode(bytes);
      var el = document.getElementById('content');
      el.innerHTML = marked.parse(md, { gfm: true });
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
        // On a bad diagram mermaid shows its error bomb in place; the catch just
        // keeps the rejection from surfacing as an unhandled-promise error.
        mermaid.run({ nodes: el.querySelectorAll('pre.mermaid') }).catch(function() {});
      }
    } catch (e) {
      document.getElementById('content').innerHTML =
        '<pre>Markdown render error: ' + String(e) + '</pre>';
    }
  };
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
