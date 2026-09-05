package ai.rever.boss.plugin.dynamic.editortab

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure halves of [LspNavigation]: the offset/position conversion the whole
 * feature's correctness rests on, and the PSI-vs-LSP routing decision.
 *
 * `resolveDefinition` itself is not covered - it spawns a real language-server
 * process, and there is no seam to fake `LanguageServerManager` through (its
 * constructor takes a config and a scope, not a transport). What CAN go wrong
 * silently is the arithmetic, so that is what is pinned here.
 */
class LspNavigationTest {

    // ---- offsetToPosition ----------------------------------------------

    @Test
    fun `offset zero is the start of the first line`() {
        val p = LspNavigation.offsetToPosition("hello\nworld", 0)
        assertEquals(0, p.line)
        assertEquals(0, p.character)
    }

    @Test
    fun `character counts from the start of its own line`() {
        // 'w' of "world" is offset 6, which is line 1 character 0 - not character 6.
        val p = LspNavigation.offsetToPosition("hello\nworld", 6)
        assertEquals(1, p.line)
        assertEquals(0, p.character)
    }

    @Test
    fun `offset on the newline itself stays on the line it terminates`() {
        val p = LspNavigation.offsetToPosition("hello\nworld", 5)
        assertEquals(0, p.line)
        assertEquals(5, p.character)
    }

    @Test
    fun `blank lines still advance the line count`() {
        val p = LspNavigation.offsetToPosition("a\n\n\nb", 4)
        assertEquals(3, p.line)
        assertEquals(0, p.character)
    }

    @Test
    fun `crlf leaves the carriage return on the preceding line`() {
        // Only '\n' increments. The \r is a character of line 0, so "b" at offset 3
        // is line 1 character 0 - the same answer a \n-only file gives.
        val p = LspNavigation.offsetToPosition("a\r\nb", 3)
        assertEquals(1, p.line)
        assertEquals(0, p.character)
    }

    @Test
    fun `astral characters count as utf16 code units`() {
        // LSP defaults to UTF-16 code units, which is what a Kotlin String index
        // already is. The emoji is a surrogate pair, so the char after it is at 2.
        val content = "😀x"
        val p = LspNavigation.offsetToPosition(content, 2)
        assertEquals(0, p.line)
        assertEquals(2, p.character)
    }

    @Test
    fun `out of range offsets are clamped rather than throwing`() {
        val content = "abc"
        assertEquals(3, LspNavigation.offsetToPosition(content, 99).character)
        assertEquals(0, LspNavigation.offsetToPosition(content, -5).character)
    }

    @Test
    fun `empty content resolves to the origin`() {
        val p = LspNavigation.offsetToPosition("", 0)
        assertEquals(0, p.line)
        assertEquals(0, p.character)
    }

    // ---- uriToPath ------------------------------------------------------

    @Test
    fun `file uri becomes an absolute path`() {
        assertEquals("/tmp/a.ts", LspNavigation.uriToPath("file:///tmp/a.ts"))
    }

    @Test
    fun `percent escapes are decoded`() {
        assertEquals("/tmp/a b.ts", LspNavigation.uriToPath("file:///tmp/a%20b.ts"))
    }

    @Test
    fun `a non-file uri resolves to null rather than a bogus path`() {
        assertNull(LspNavigation.uriToPath("untitled:Untitled-1"))
    }

    // ---- findOnPath -----------------------------------------------------
    //
    // BOSS is Dock-launched, so its own PATH is /usr/bin:/bin:/usr/sbin:/sbin and
    // every server installed under Homebrew, npm-global or pyenv is invisible to
    // System.getenv("PATH"). This is the lookup that has to see past that.

    @Test
    fun `finds an executable in a later path entry`() {
        val dir = createTempDir()
        val exe = File(dir, "fake-language-server").apply {
            writeText("#!/bin/sh\nexit 0\n")
            setExecutable(true)
        }
        val path = listOf("/nonexistent-a", dir.absolutePath, "/nonexistent-b")
            .joinToString(File.pathSeparator)
        assertEquals(exe.absolutePath, LspNavigation.findOnPath("fake-language-server", path))
    }

    @Test
    fun `earlier path entries win`() {
        val first = createTempDir()
        val second = createTempDir()
        for (d in listOf(first, second)) {
            File(d, "dupe").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        }
        val path = listOf(first.absolutePath, second.absolutePath).joinToString(File.pathSeparator)
        assertEquals(File(first, "dupe").absolutePath, LspNavigation.findOnPath("dupe", path))
    }

    @Test
    fun `a non-executable file is not a match`() {
        val dir = createTempDir()
        File(dir, "not-exec").apply { writeText("x"); setExecutable(false) }
        assertNull(LspNavigation.findOnPath("not-exec", dir.absolutePath))
    }

    @Test
    fun `a directory with the right name is not a match`() {
        // Directories are executable in the POSIX sense, so isFile is what excludes
        // them - without it a `bin/pylsp/` folder would resolve and then fail to exec.
        val dir = createTempDir()
        File(dir, "pylsp").mkdirs()
        assertNull(LspNavigation.findOnPath("pylsp", dir.absolutePath))
    }

    @Test
    fun `blank and empty path segments are skipped`() {
        val dir = createTempDir()
        File(dir, "srv").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        val path = listOf("", "  ", dir.absolutePath).joinToString(File.pathSeparator)
        assertEquals(File(dir, "srv").absolutePath, LspNavigation.findOnPath("srv", path))
    }

    @Test
    fun `missing command resolves to null`() {
        assertNull(LspNavigation.findOnPath("definitely-not-installed-xyz", "/usr/bin${File.pathSeparator}/bin"))
    }

    private fun createTempDir(): File =
        java.nio.file.Files.createTempDirectory("lspnav").toFile().also { it.deleteOnExit() }

    // ---- usesPsi --------------------------------------------------------

    @Test
    fun `kotlin files stay on psi so ShowUsages survives`() {
        assertTrue(LspNavigation.usesPsi("/x/Main.kt"))
        assertTrue(LspNavigation.usesPsi("/x/build.gradle.kts"))
    }

    @Test
    fun `everything else routes to lsp`() {
        // These are exactly the files where PSI answered Unavailable and Cmd+Click
        // did nothing at all.
        assertFalse(LspNavigation.usesPsi("/x/main.ts"))
        assertFalse(LspNavigation.usesPsi("/x/main.py"))
        assertFalse(LspNavigation.usesPsi("/x/main.rs"))
        assertFalse(LspNavigation.usesPsi("/x/Main.java"))
        // No extension at all must not be mistaken for Kotlin.
        assertFalse(LspNavigation.usesPsi("/x/Makefile"))
    }
}
