package ai.rever.boss.plugin.dynamic.editortab

import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Lays down the real preview page, its vendored libraries and a document
 * directory under `build/preview-fixture/`, so `src/test/js/preview-dom.test.mjs`
 * can open it in a headless browser and assert on the DOM the page actually
 * builds. The JS test reads exactly what ships; nothing about the page is
 * restated there.
 *
 * The assertions here are the fixture's own preconditions: the JS test's
 * relative-image case is only meaningful if the base directory really holds a
 * 1×1 PNG, and the page is only worth loading if it references all three
 * libraries.
 */
class MarkdownPreviewFixtureTest {

    // 1×1 opaque PNG. The JS test asserts naturalWidth == 1 after load, which is
    // what proves both `<base href>` resolution and the policy's `img-src file:`.
    private val onePixelPng =
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC"

    /**
     * Where to write the fixture. `build.gradle.kts` passes this as
     * `preview.fixture.dir` so the location does not silently depend on the test
     * JVM's working directory (correct under Gradle, surprising from an IDE run
     * configuration); the relative fallback keeps a bare `mvn`-style invocation
     * working.
     */
    private fun fixtureRoot(): File =
        System.getProperty("preview.fixture.dir")?.let(::File) ?: File("build/preview-fixture")

    @Test
    fun `writes the preview page fixture for the DOM test`() {
        val root = fixtureRoot()
        val libDir = File(root, "lib").apply { mkdirs() }
        val docDir = File(root, "doc").apply { mkdirs() }

        val libUris = listOf("purify.min.js", "marked.umd.js", "mermaid.min.js").associateWith { name ->
            val bytes = javaClass.classLoader.getResourceAsStream("markdown-preview/$name")
                .let { assertNotNull(it, "markdown-preview/$name missing from plugin resources") }
                .use { it.readBytes() }
            assertTrue(bytes.isNotEmpty(), "$name is empty")
            File(libDir, name).also { it.writeBytes(bytes) }.toURI().toString()
        }

        File(docDir, "pixel.png").writeBytes(Base64.getDecoder().decode(onePixelPng))

        val page = buildPreviewHtml(
            initialB64 = Base64.getEncoder().encodeToString("# fixture".toByteArray()),
            baseUri = docDir.toURI().toString(),
            markedUri = libUris.getValue("marked.umd.js"),
            mermaidUri = libUris.getValue("mermaid.min.js"),
            purifyUri = libUris.getValue("purify.min.js"),
            nonce = newScriptNonce(),
            dark = false
        )
        val pageFile = File(root, "preview.html").apply { writeText(page) }

        libUris.values.forEach { uri ->
            assertTrue(page.contains(uri), "fixture page does not reference $uri")
        }
        assertTrue(pageFile.length() > 0, "fixture page was not written")
    }
}
