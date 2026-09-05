package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.bosseditor.lsp.client.LspClient
import ai.rever.bosseditor.lsp.client.LspClientState
import ai.rever.bosseditor.lsp.client.LspMethods
import ai.rever.bosseditor.lsp.protocol.InitializeParams
import ai.rever.bosseditor.lsp.protocol.InitializeResult
import ai.rever.bosseditor.lsp.protocol.ServerCapabilities
import ai.rever.bosseditor.lsp.server.LanguageServerConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three things that decide whether a click reaches a running server at all:
 * how the command is rewritten, what version a sync claims, and whether a
 * replaced server is told about the document again.
 */
class LspNavigationLaunchTest {

    private fun tempDir(): File =
        java.nio.file.Files.createTempDirectory("lspnav").toFile().also { it.deleteOnExit() }

    private fun serverOn(dir: File, name: String): File =
        File(dir, name).apply { writeText("#!/bin/sh\nexit 0\n"); setExecutable(true) }

    private fun config(vararg command: String) = LanguageServerConfig(
        id = "test", displayName = "Test", languageId = "test",
        command = command.toList(), fileExtensions = listOf("test"),
    )

    // ---- launchConfig ---------------------------------------------------
    //
    // BOSS is Dock-launched, so its PATH is /usr/bin:/bin:/usr/sbin:/sbin and
    // the registry's bare command name resolves to nothing. Both halves matter:
    // the absolute path is what lets the server be exec'd, and the PATH in the
    // environment is what lets a `#!/usr/bin/env node` server find its own
    // interpreter once it is running.

    @Test
    fun `the bare command name becomes an absolute path`() {
        val dir = tempDir()
        val exe = serverOn(dir, "fake-language-server")
        val launched = LspNavigation().launchConfig(config("fake-language-server"), dir.absolutePath)
        assertEquals(listOf(exe.absolutePath), launched?.command)
    }

    @Test
    fun `arguments are preserved after the rewritten command`() {
        val dir = tempDir()
        val exe = serverOn(dir, "srv")
        val launched = LspNavigation().launchConfig(config("srv", "--stdio", "-v"), dir.absolutePath)
        assertEquals(listOf(exe.absolutePath, "--stdio", "-v"), launched?.command)
    }

    @Test
    fun `the resolved PATH is handed to the server process`() {
        val dir = tempDir()
        serverOn(dir, "srv")
        val launched = LspNavigation().launchConfig(config("srv"), dir.absolutePath)
        assertEquals(dir.absolutePath, launched?.environment?.get("PATH"))
    }

    @Test
    fun `an existing environment survives, PATH wins on conflict`() {
        val dir = tempDir()
        serverOn(dir, "srv")
        val base = config("srv").copy(environment = mapOf("NODE_ENV" to "test", "PATH" to "/ignored"))
        val launched = LspNavigation().launchConfig(base, dir.absolutePath)
        assertEquals("test", launched?.environment?.get("NODE_ENV"))
        assertEquals(dir.absolutePath, launched?.environment?.get("PATH"))
    }

    @Test
    fun `a server that is not installed yields null rather than a spawn`() {
        // The guard that keeps a missing pylsp from costing a 60s initialize
        // timeout on every single click.
        assertNull(LspNavigation().launchConfig(config("definitely-not-installed-xyz"), tempDir().absolutePath))
    }

    @Test
    fun `an empty command yields null`() {
        assertNull(LspNavigation().launchConfig(config(), tempDir().absolutePath))
    }

    // ---- document versions ----------------------------------------------

    @Test
    fun `versions increase and never go negative`() {
        // The version used to be System.currentTimeMillis().toInt(), which wraps
        // every ~49 days and is negative for half of each cycle - i.e. BELOW the
        // version didOpen sent. A server that drops non-increasing changes would
        // then keep answering from the opened snapshot, so unsaved edits stop
        // being reflected, silently.
        val nav = LspNavigation()
        val versions = buildList {
            add(nav.didOpen("file:///a.ts", "typescript", "x").version())
            repeat(2000) { add(nav.didChange("file:///a.ts", "x$it").version()) }
        }
        assertTrue(versions.all { it > 0 }, "a version must never be negative or zero")
        assertEquals(versions.sorted(), versions, "versions must be non-decreasing")
        assertEquals(versions.distinct().size, versions.size, "versions must be distinct")
    }

    private fun JsonElement.version(): Int =
        jsonObject.getValue("textDocument").jsonObject.getValue("version").jsonPrimitive.int

    // ---- didOpen vs didChange -------------------------------------------

    @Test
    fun `the same document on the same client opens once, then changes`() {
        val nav = LspNavigation()
        val client = RecordingClient()
        nav.syncDocument(client, "file:///a.ts", "typescript", "one")
        nav.syncDocument(client, "file:///a.ts", "typescript", "two")
        nav.syncDocument(client, "file:///a.ts", "typescript", "three")
        assertEquals(listOf(LspMethods.DID_OPEN, LspMethods.DID_CHANGE, LspMethods.DID_CHANGE), client.methods)
    }

    @Test
    fun `a replaced server is told about the document again`() {
        // LanguageServerManager hands back a FRESH process when a server has
        // died, and that process has never heard of the document. Sending it a
        // didChange leaves the file answering NotFound for the rest of the
        // session, with nothing logged anywhere.
        val nav = LspNavigation()
        val first = RecordingClient()
        nav.syncDocument(first, "file:///a.ts", "typescript", "one")
        nav.syncDocument(first, "file:///a.ts", "typescript", "two")

        val restarted = RecordingClient()
        nav.syncDocument(restarted, "file:///a.ts", "typescript", "three")
        nav.syncDocument(restarted, "file:///a.ts", "typescript", "four")

        assertEquals(listOf(LspMethods.DID_OPEN, LspMethods.DID_CHANGE), first.methods)
        assertEquals(listOf(LspMethods.DID_OPEN, LspMethods.DID_CHANGE), restarted.methods)
    }

    @Test
    fun `each document is opened on its own`() {
        val nav = LspNavigation()
        val client = RecordingClient()
        nav.syncDocument(client, "file:///a.ts", "typescript", "a")
        nav.syncDocument(client, "file:///b.ts", "typescript", "b")
        nav.syncDocument(client, "file:///a.ts", "typescript", "a2")
        assertEquals(listOf(LspMethods.DID_OPEN, LspMethods.DID_OPEN, LspMethods.DID_CHANGE), client.methods)
    }

    @Test
    fun `languages do not share an open-document set`() {
        val nav = LspNavigation()
        val ts = RecordingClient()
        val py = RecordingClient()
        nav.syncDocument(ts, "file:///a", "typescript", "a")
        nav.syncDocument(py, "file:///a", "python", "a")
        assertEquals(listOf(LspMethods.DID_OPEN), ts.methods)
        assertEquals(listOf(LspMethods.DID_OPEN), py.methods)
    }

    private class RecordingClient : LspClient {
        val methods = mutableListOf<String>()
        override val state = LspClientState.INITIALIZED
        override val isInitialized = true
        override val serverCapabilities: ServerCapabilities? = null
        override suspend fun request(method: String, params: JsonElement?): JsonElement? = null
        override fun notify(method: String, params: JsonElement?) { methods += method }
        override fun onNotification(method: String?, handler: (String, JsonElement?) -> Unit) = Unit
        override fun onRequest(method: String, handler: suspend (JsonElement?) -> JsonElement?) = Unit
        override suspend fun initialize(params: InitializeParams) = InitializeResult(ServerCapabilities())
        override fun initialized() = Unit
        override suspend fun shutdown() = Unit
        override fun exit() = Unit
        override fun dispose() = Unit
    }
}
