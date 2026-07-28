package ai.rever.boss.plugin.dynamic.editortab

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Guards the path→language→lexer chain.
 *
 * This chain had a silent hole: BossEditor shipped working lexers for Dockerfile,
 * Makefile and others, but nothing mapped a file onto them, so those files opened
 * as plain text. A mapping gap produces no error — just quietly unhighlighted
 * files — so it needs a test rather than a glance.
 */
class LanguageDetectionTest {

    // ------------------------------------------------------ extension-less files

    @Test
    fun `bare Dockerfile is detected`() {
        assertEquals("dockerfile", LanguageDetection.detect("/srv/app/Dockerfile"))
    }

    @Test
    fun `Dockerfile with a stage suffix is detected`() {
        // `Dockerfile.dev` must win on the filename, not lose to the `.dev` extension.
        assertEquals("dockerfile", LanguageDetection.detect("/srv/app/Dockerfile.dev"))
        assertEquals("dockerfile", LanguageDetection.detect("/srv/app/Dockerfile.prod"))
    }

    @Test
    fun `dockerfile extension form is detected`() {
        assertEquals("dockerfile", LanguageDetection.detect("/srv/app/build.dockerfile"))
    }

    @Test
    fun `Containerfile is treated as a Dockerfile`() {
        assertEquals("dockerfile", LanguageDetection.detect("/srv/app/Containerfile"))
    }

    @Test
    fun `Makefile spellings are detected`() {
        assertEquals("makefile", LanguageDetection.detect("/srv/app/Makefile"))
        assertEquals("makefile", LanguageDetection.detect("/srv/app/GNUmakefile"))
        assertEquals("makefile", LanguageDetection.detect("/srv/app/build.mk"))
    }

    @Test
    fun `dotenv files map to properties`() {
        assertEquals("properties", LanguageDetection.detect("/srv/app/.env"))
        assertEquals("properties", LanguageDetection.detect("/srv/app/.env.local"))
    }

    // ------------------------------------------------------------ path handling

    @Test
    fun `a dot in a parent directory does not leak into the extension`() {
        // Regression: the extension used to be read from the whole path, so
        // `/srv/v1.2/Makefile` yielded the "extension" `2/Makefile`.
        assertEquals("makefile", LanguageDetection.detect("/srv/v1.2/Makefile"))
        assertEquals("yaml", LanguageDetection.detect("/srv/v1.2/values.yaml"))
        assertEquals("text", LanguageDetection.detect("/srv/v1.2/README"))
    }

    @Test
    fun `windows separators are handled`() {
        assertEquals("dockerfile", LanguageDetection.detect("C:\\src\\app\\Dockerfile"))
    }

    // -------------------------------------------------------------- still-correct

    @Test
    fun `existing mappings are unchanged`() {
        assertEquals("kotlin", LanguageDetection.detect("/a/B.kt"))
        assertEquals("yaml", LanguageDetection.detect("/a/Chart.yaml"))
        assertEquals("yaml", LanguageDetection.detect("/a/values-prod.yml"))
        assertEquals("json", LanguageDetection.detect("/a/package.json"))
        assertEquals("markdown", LanguageDetection.detect("/a/README.md"))
        assertEquals("text", LanguageDetection.detect("/a/notes.unknownext"))
    }

    // ------------------------------------------------- every language has a lexer

    @Test
    fun `newly wired languages resolve to a lexer`() {
        val wired = listOf(
            "dockerfile", "makefile", "properties", "diff", "batch", "clojure",
            "latex", "lisp", "tcl", "fortran", "d", "delphi", "visualbasic",
            "actionscript", "jsp",
        )
        wired.forEach { language ->
            assertNotNull(
                LanguageDetection.lexerFor(language),
                "no lexer wired for '$language' — it would fall back to plain text",
            )
        }
    }

    /**
     * The point of the fix: a detected language must actually reach a lexer.
     * Detection returning "dockerfile" while the factory returns null would look
     * fixed and highlight nothing.
     */
    @Test
    fun `detected language for these files resolves to a lexer`() {
        val paths = listOf(
            "/srv/app/Dockerfile",
            "/srv/app/Dockerfile.dev",
            "/srv/app/Containerfile",
            "/srv/app/Makefile",
            "/srv/app/GNUmakefile",
            "/srv/app/app.properties",
            "/srv/app/change.patch",
            "/srv/app/Chart.yaml",
        )
        paths.forEach { path ->
            val language = LanguageDetection.detect(path)
            assertNotNull(
                LanguageDetection.lexerFor(language),
                "$path detected as '$language' but no lexer handles it",
            )
        }
    }

    @Test
    fun `unknown languages still return no lexer`() {
        assertNull(LanguageDetection.lexerFor("text"))
        assertNull(LanguageDetection.lexerFor("brainfuck"))
    }
}
