package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.bosseditor.compose.NavigationResolveResult
import ai.rever.bosseditor.lsp.client.LspClient
import ai.rever.bosseditor.lsp.client.LspMethods
import ai.rever.bosseditor.lsp.config.LspSettingsManager
import ai.rever.bosseditor.lsp.protocol.Position
import ai.rever.bosseditor.lsp.providers.LspNavigationProvider
import ai.rever.bosseditor.lsp.server.LanguageServerConfig
import ai.rever.bosseditor.lsp.server.LanguageServerManager
import ai.rever.bosseditor.lsp.server.LanguageServerRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.net.URI

/**
 * Go-to-definition over LSP, for the languages PSI does not cover.
 *
 * The bosseditor library ships a complete LSP stack - transport, client,
 * [LanguageServerManager], [LspNavigationProvider] - and until now nothing
 * constructed any of it. `LanguageServerManager` had no call site at all, so no
 * server was ever spawned and no `initialize` was ever sent; the only code that
 * touched LSP was the settings screen, which PATH-probes the registered servers
 * and shows them as "installed". That is why the feature looked implemented.
 *
 * Cmd+Click resolution itself went to the internal PSI `NavigationManager`,
 * which returns `Unavailable` for anything that is not `.kt`/`.kts`. This class
 * is the other half: the editor keeps PSI for Kotlin (PSI is the only path that
 * can answer ShowUsages, and `navigationResolver` cannot express it - see
 * [NavigationResolveResult]) and routes every other language here.
 *
 * Servers are started lazily, on the first navigation in a file whose language
 * has a registered server, and are reused across files and panels for the life
 * of the plugin.
 */
class LspNavigation {

    /** One manager per workspace root; each manager already reuses servers by language. */
    private val managers = ConcurrentHashMap<String, LanguageServerManager>()
    @Volatile
    private var disposed = false

    /**
     * Documents announced with didOpen, keyed by the client they were announced to.
     * Each set is concurrent because different language/root locks may reach the same client.
     *
     * Keyed by the client and not only the URI, because a server that dies is
     * replaced: `LanguageServerManager.getOrStartServer` hands back a FRESH
     * process, which has never heard of the document. Sending it a didChange
     * for a document it never opened leaves that file answering NotFound for
     * the rest of the session, and nothing is logged. Comparing identity means
     * a replacement is re-announced instead.
     */
    private val opened = ConcurrentHashMap<LspClient, MutableSet<String>>()

    /**
     * Monotonic document version, shared across documents.
     *
     * Per-document monotonicity is all the protocol asks for, and one counter
     * satisfies it for every document at once. It has to be a counter: a clock
     * truncated to Int wraps, and spends half of each cycle negative - below the
     * version didOpen sent - so a server that drops non-increasing changes would
     * quietly keep answering from the snapshot taken when the file was opened.
     */
    private val documentVersion = AtomicInteger(0)

    /** Serialises start + sync per language/root without blocking unrelated servers. */
    private val mutexes = ConcurrentHashMap<ServerKey, Mutex>()

    private data class ServerKey(val languageId: String, val root: String)

    /**
     * Resolve the definition of the symbol at [offset], or [NavigationResolveResult.NotFound].
     *
     * Runs on [Dispatchers.IO]. The editor calls its resolver from a
     * `rememberCoroutineScope()`, whose dispatcher is the composition's - the UI
     * thread - and a cold first call here spawns a login shell, stats every PATH
     * entry, then spawns and initializes a server. On the UI thread that is a
     * visible freeze measured in seconds.
     *
     * Never throws except to propagate cancellation: a click that cannot be
     * answered must fall through to the editor's "not found" affordance, not
     * tear down the composable's coroutine. Other failures are logged to stderr
     * before returning NotFound so a missing or failed server is diagnosable.
     */
    suspend fun resolveDefinition(
        content: String,
        filePath: String,
        offset: Int,
        projectPath: String?,
    ): NavigationResolveResult = withContext(Dispatchers.IO) {
        if (disposed) return@withContext NavigationResolveResult.NotFound
        try {
            resolve(content, filePath, offset, projectPath)
        } catch (cancellation: CancellationException) {
            // Closing the tab mid-resolution is not a failed lookup. Swallowing it
            // would report NotFound to a caller that is already gone, and hide the
            // cancellation from the scope that raised it.
            throw cancellation
        } catch (error: Exception) {
            System.err.println(
                "[LspNavigation] ${error::class.simpleName} resolving '$filePath': ${error.message}",
            )
            NavigationResolveResult.NotFound
        }
    }

    private suspend fun resolve(
        content: String,
        filePath: String,
        offset: Int,
        projectPath: String?,
    ): NavigationResolveResult {
        if (!LspSettingsManager.instance.configuration.value.enabled) return NavigationResolveResult.NotFound
        // LspSettingsManager applies both built-in enablement and enabled custom servers to
        // the registry. Trust the selected, enabled config here; a separate disabled-language
        // check would incorrectly reject a custom replacement for a disabled built-in server.
        val config = LanguageServerRegistry.getConfigForFile(filePath) ?: return NavigationResolveResult.NotFound
        // The server binary has to exist before we try to run it. Without this a
        // missing `pylsp`/`typescript-language-server` costs a failed spawn and
        // a 60s initialize timeout on every single click.
        //
        // Resolved against the USER's PATH, not this process's - see [launchPath].
        val launchable = launchConfig(config) ?: return NavigationResolveResult.NotFound

        // A server rooted at the wrong directory resolves nothing outside the file
        // itself, so prefer the project and fall back to the file's own folder.
        val root = projectPath?.takeIf { it.isNotBlank() }
            ?: File(filePath).parentFile?.absolutePath
            ?: return NavigationResolveResult.NotFound

        val uri = File(filePath).toURI().toString()
        val key = ServerKey(config.languageId, canonicalRoot(root))
        val manager = managers.computeIfAbsent(key.root) { LanguageServerManager() }
        val client = mutexes.computeIfAbsent(key) { Mutex() }.withLock {
            if (disposed) return@withLock null
            // Whether THIS call is the one that starts the server, asked before
            // starting it - the settle below is only owed on a cold start.
            val cold = !manager.isServerRunning(config.languageId)
            val c = manager.getOrStartServer(launchable, root)
            // dispose() may have raced the suspend above after clearing the manager map.
            // Dispose this retained manager too so a late start cannot orphan a process.
            if (disposed) {
                runCatching { manager.dispose() }
                return@withLock null
            }
            syncDocument(c, uri, config.languageId, content)
            if (cold) delay(COLD_START_SETTLE_MS)
            c
        } ?: return NavigationResolveResult.NotFound

        val position = offsetToPosition(content, offset)
        val location = LspNavigationProvider(client)
            .goToDefinition(uri, position)
            .firstOrNull() ?: return NavigationResolveResult.NotFound

        val targetPath = uriToPath(location.uri) ?: return NavigationResolveResult.NotFound
        // LSP positions are 0-based; NavigationTarget - and so openFileAtPosition,
        // which both this and the PSI path feed - is 1-based on both axes.
        return NavigationResolveResult.Found(
            filePath = targetPath,
            line = location.range.start.line + 1,
            column = location.range.start.character + 1,
        )
    }

    /**
     * Give the server the buffer as it stands right now.
     *
     * Full-text sync on every navigation rather than incremental didChange: this
     * is one message on a user gesture, not a keystroke path, and sending the whole
     * buffer is what makes the answer reflect UNSAVED edits. Resolving against
     * whatever is on disk is the failure mode worth paying a few KB to avoid.
     *
     * Sent directly on [LspClient.notify] instead of through LspDocumentSyncManager,
     * which would drag in a semantic-token provider this path has no use for.
     */
    internal fun syncDocument(client: LspClient, uri: String, languageId: String, content: String) {
        // Keep the document open for the lifetime of this shared client. Editor-tab does not
        // own a global document reference count, so didClose from one tab could invalidate the
        // same URI while another split/tab is still using it.
        val known = opened.computeIfAbsent(client) { ConcurrentHashMap.newKeySet() }
        if (known.add(uri)) {
            client.notify(LspMethods.DID_OPEN, didOpen(uri, languageId, content))
        } else {
            client.notify(LspMethods.DID_CHANGE, didChange(uri, content))
        }
    }

    internal fun didOpen(uri: String, languageId: String, content: String): JsonElement =
        buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", uri)
                put("languageId", languageId)
                put("version", documentVersion.incrementAndGet())
                put("text", content)
            })
        }

    internal fun didChange(uri: String, content: String): JsonElement =
        buildJsonObject {
            put("textDocument", buildJsonObject {
                put("uri", uri)
                put("version", documentVersion.incrementAndGet())
            })
            putJsonArray("contentChanges") {
                // No `range` key: this is a whole-document replacement.
                addJsonObject { put("text", content) }
            }
        }

    /**
     * Rewrite a registry config so it can actually be launched, or null when the
     * server is not installed anywhere the user can see.
     *
     * Two separate problems, both invisible until you look at the app's environment:
     *
     * 1. BOSS is launched from the Dock, so it inherits launchd's PATH -
     *    `/usr/bin:/bin:/usr/sbin:/sbin`. Homebrew, npm-global, pyenv shims and
     *    ~/.local/bin are all absent, and the registry's command is a bare name.
     *    A perfectly installed server reads as "not found", so the command has to
     *    become an absolute path.
     * 2. The server process inherits that same stunted PATH. An absolute path
     *    settles the exec, but most of these servers are node scripts whose
     *    `#!/usr/bin/env node` line then fails to find node - which is not on the
     *    app's PATH either. The environment carries the real PATH for that.
     */
    internal fun launchConfig(
        config: LanguageServerConfig,
        path: String = launchPath,
        isWindows: Boolean = isWindowsPlatform(),
        pathExtensions: String = System.getenv("PATHEXT").orEmpty(),
        commandInterpreter: String = windowsCommandInterpreter(),
    ): LanguageServerConfig? {
        val name = config.command.firstOrNull() ?: return null
        val exe = findOnPath(name, path, isWindows, pathExtensions) ?: return null
        val arguments = config.command.drop(1)
        val command =
            if (isWindows && (exe.endsWith(".cmd", ignoreCase = true) || exe.endsWith(".bat", ignoreCase = true))) {
                listOf(commandInterpreter, "/d", "/s", "/c", windowsBatchCommand(exe, arguments))
            } else {
                listOf(exe) + arguments
            }
        return config.copy(
            command = command,
            environment = config.environment + mapOf("PATH" to path),
        )
    }

    /** Free every server process. Called when the plugin is disposed. */
    fun dispose() {
        disposed = true
        managers.values.forEach { manager -> runCatching { manager.dispose() } }
        managers.clear()
        mutexes.clear()
        opened.clear()
    }

    companion object {
        /**
         * How long a freshly started server gets before the first question.
         *
         * Servers answer as soon as they are initialized, but several keep
         * loading the project behind that - and tsserver in particular answers a
         * definition request made in the gap from the open file alone. The answer
         * is not an error and does not look like one: clicking a call resolves to
         * the local `import` binding instead of the exported declaration, which is
         * a real location in a real file, just the wrong one.
         *
         * Because servers start lazily on the click, the FIRST navigation in a
         * language always lands in that gap without this. Measured against
         * typescript-language-server, the shallow answer persists for under a
         * second; the margin is for a colder machine or a larger project. Paid
         * once per language per session, and only when this call is the one that
         * started the server.
         */
        private const val COLD_START_SETTLE_MS = 1_500L

        /**
         * One instance for the whole plugin.
         *
         * [EditorTabComponent] is per TAB, and a manager per tab would spawn one
         * language-server process per open file - a `LanguageServerManager` already
         * keys its servers by language and reuses them, which is only worth anything
         * if every tab shares the same one. Released by [disposeShared] on plugin
         * unload.
         */
        @Volatile
        private var sharedInstance: LspNavigation? = null

        val shared: LspNavigation
            get() = sharedInstance ?: synchronized(this) {
                sharedInstance ?: LspNavigation().also { sharedInstance = it }
            }

        /** Stop every language server. Plugin unload only. */
        fun disposeShared() {
            synchronized(this) {
                sharedInstance?.dispose()
                // Keep the disposed instance installed. A stale tab coroutine reaching `shared`
                // during unload then gets a fast NotFound instead of creating an orphan manager.
            }
        }

        /**
         * PATH as the user's shell sees it.
         *
         * Asks a non-interactive login shell rather than an interactive one. This reads login
         * profiles while avoiding prompts and terminal setup from `.zshrc`; common tool folders
         * are appended as a floor for tools configured only by interactive profiles.
         */
        private val launchPath: String by lazy { buildLaunchPath() }

        private fun buildLaunchPath(): String {
            val fromShell = runCatching {
                val shell = System.getenv("SHELL")?.takeIf { File(it).canExecute() } ?: "/bin/sh"
                val output = kotlin.io.path.createTempFile("boss-lsp-path", ".txt").toFile()
                try {
                    val p = ProcessBuilder(shell, "-lc", "printf '${PATH_SENTINEL}%s' \"\$PATH\"")
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(output)
                        .start()
                    if (!p.waitFor(5, TimeUnit.SECONDS)) {
                        p.destroyForcibly()
                        null
                    } else {
                        output.readText()
                            .substringAfterLast(PATH_SENTINEL, missingDelimiterValue = "")
                            .trim()
                            .takeIf { it.isNotBlank() }
                    }
                } finally {
                    output.delete()
                }
            }.getOrNull()

            val fallbacks = listOf(
                "/opt/homebrew/bin", "/opt/homebrew/sbin", "/usr/local/bin",
                System.getProperty("user.home") + "/.local/bin",
                System.getProperty("user.home") + "/.bun/bin",
                System.getProperty("user.home") + "/.cargo/bin",
                System.getProperty("user.home") + "/go/bin",
            )
            val current = System.getenv("PATH").orEmpty()
            return mergePaths(fromShell, current, fallbacks)
        }

        internal fun mergePaths(fromShell: String?, current: String, fallbacks: List<String>): String =
            (listOfNotNull(fromShell, current) + fallbacks)
                .flatMap { it.split(File.pathSeparator) }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(File.pathSeparator)

        /** First executable named [command] across [path], including PATHEXT launchers on Windows. */
        internal fun findOnPath(
            command: String,
            path: String,
            isWindows: Boolean = isWindowsPlatform(),
            pathExtensions: String = System.getenv("PATHEXT").orEmpty(),
        ): String? {
            val extensions =
                if (isWindows && File(command).extension.isEmpty()) {
                    pathExtensions.ifBlank { ".COM;.EXE;.BAT;.CMD" }
                        .split(';')
                        .filter { it.isNotBlank() }
                        .flatMap { listOf(it.lowercase(), it) }
                        .distinct()
                } else {
                    emptyList()
                }
            val names = sequenceOf(command) + extensions.asSequence().map { command + it }
            val direct = command.contains('/') || command.contains('\\')
            val directories =
                if (direct) sequenceOf<String?>(null)
                else path.split(if (isWindows) ';' else File.pathSeparatorChar).asSequence()
                    .filter { it.isNotBlank() }
                    .map { it }

            return directories
                .flatMap { directory -> names.map { name -> if (directory == null) File(name) else File(directory, name) } }
                .firstOrNull { it.isFile && (isWindows || it.canExecute()) }
                ?.absolutePath
        }

        internal fun windowsBatchCommand(executable: String, arguments: List<String>): String =
            (listOf("call", quoteForCmd(executable)) + arguments.map(::quoteForCmd)).joinToString(" ")

        private fun quoteForCmd(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

        private fun isWindowsPlatform(): Boolean =
            System.getProperty("os.name").contains("windows", ignoreCase = true)

        private fun windowsCommandInterpreter(): String =
            System.getenv("ComSpec")
                ?.takeIf { it.isNotBlank() && File(it).isFile }
                ?: System.getenv("SystemRoot")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { File(it, "System32/cmd.exe").absolutePath }
                ?: "cmd.exe"

        private fun canonicalRoot(root: String): String =
            runCatching { File(root).canonicalPath }.getOrElse { File(root).absolutePath }

        private const val PATH_SENTINEL = "__BOSS_PATH__"

        /**
         * Kotlin stays on PSI.
         *
         * `navigationResolver` REPLACES the internal NavigationManager rather than
         * layering over it, and it can only answer Found/NotFound - there is no
         * ShowUsages variant. Handing Kotlin to LSP would therefore trade a working
         * find-usages popup for a definition jump. Deciding per file keeps both.
         */
        fun usesPsi(filePath: String): Boolean =
            filePath.isBlank() || filePath.endsWith(".kt") || filePath.endsWith(".kts")

        /**
         * Offset -> zero-based line/character.
         *
         * Counts UTF-16 code units, which is what a Kotlin String index already is
         * and what LSP defaults to, so a line containing an emoji or any astral-plane
         * character still lands on the right column.
         */
        internal fun offsetToPosition(content: String, offset: Int): Position {
            val end = offset.coerceIn(0, content.length)
            var line = 0
            var lineStart = 0
            var i = 0
            while (i < end) {
                if (content[i] == '\n') {
                    line++
                    lineStart = i + 1
                }
                i++
            }
            return Position(line = line, character = end - lineStart)
        }

        /** `file:///a/b.ts` -> `/a/b.ts`, with percent-escapes resolved. */
        internal fun uriToPath(uri: String): String? =
            runCatching { File(URI(uri)).absolutePath }.getOrNull()
    }
}
