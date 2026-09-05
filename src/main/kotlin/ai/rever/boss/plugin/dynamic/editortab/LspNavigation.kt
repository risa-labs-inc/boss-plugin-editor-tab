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

    private val manager = LanguageServerManager()

    /**
     * Documents announced with didOpen, per language, together with the client
     * they were announced to. Guarded by [mutex].
     *
     * Keyed by the client and not only the URI, because a server that dies is
     * replaced: `LanguageServerManager.getOrStartServer` hands back a FRESH
     * process, which has never heard of the document. Sending it a didChange
     * for a document it never opened leaves that file answering NotFound for
     * the rest of the session, and nothing is logged. Comparing identity means
     * a replacement is re-announced instead.
     */
    private val opened = mutableMapOf<String, OpenDocuments>()

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

    /** Serialises start + sync so two fast clicks cannot open the same doc twice. */
    private val mutex = Mutex()

    private class OpenDocuments(val client: LspClient) {
        val uris = mutableSetOf<String>()
    }

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
     * tear down the composable's coroutine. Every other failure is therefore a
     * silent NotFound, so when navigation is "not working" the only way to see
     * WHY is the LSP file log, which `LspSettingsManager` ships disabled
     * (`logging.fileLoggingEnabled`).
     */
    suspend fun resolveDefinition(
        content: String,
        filePath: String,
        offset: Int,
        projectPath: String?,
    ): NavigationResolveResult = withContext(Dispatchers.IO) {
        try {
            resolve(content, filePath, offset, projectPath)
        } catch (cancellation: CancellationException) {
            // Closing the tab mid-resolution is not a failed lookup. Swallowing it
            // would report NotFound to a caller that is already gone, and hide the
            // cancellation from the scope that raised it.
            throw cancellation
        } catch (_: Throwable) {
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
        // No registered server for this extension: answer immediately rather
        // than paying a process spawn to find out.
        val config = LanguageServerRegistry.getConfigForFile(filePath) ?: return NavigationResolveResult.NotFound
        if (LspSettingsManager.instance.isBuiltInServerDisabled(config.languageId)) return NavigationResolveResult.NotFound
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
        val client = mutex.withLock {
            // Whether THIS call is the one that starts the server, asked before
            // starting it - the settle below is only owed on a cold start.
            val cold = !manager.isServerRunning(config.languageId)
            val c = manager.getOrStartServer(launchable, root)
            syncDocument(c, uri, config.languageId, content)
            if (cold) delay(COLD_START_SETTLE_MS)
            c
        }

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
        val known = opened[languageId]?.takeIf { it.client === client }
            ?: OpenDocuments(client).also { opened[languageId] = it }
        if (known.uris.add(uri)) {
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
    ): LanguageServerConfig? {
        val name = config.command.firstOrNull() ?: return null
        val exe = findOnPath(name, path) ?: return null
        return config.copy(
            command = listOf(exe) + config.command.drop(1),
            environment = config.environment + mapOf("PATH" to path),
        )
    }

    /** Free every server process. Called when the plugin is disposed. */
    fun dispose() {
        runCatching { manager.dispose() }
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
                sharedInstance = null
            }
        }

        /**
         * PATH as the user's shell sees it.
         *
         * Asks the login shell rather than guessing, because the answer is whatever
         * their profile builds - pyenv shims, bun, nvm, a Homebrew prefix that differs
         * on Intel vs Apple silicon. The guessed directories are appended as a floor,
         * not used as the answer, so an unusual setup still works and a missing shell
         * is not fatal. Resolved once: it costs a shell spawn.
         */
        private val launchPath: String by lazy { buildLaunchPath() }

        private fun buildLaunchPath(): String {
            val fromShell = runCatching {
                val shell = System.getenv("SHELL")?.takeIf { File(it).canExecute() } ?: "/bin/zsh"
                val p = ProcessBuilder(shell, "-lc", "printf %s \"\$PATH\"")
                    .redirectErrorStream(false)
                    .start()
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    p.destroyForcibly()
                    null
                } else {
                    p.inputStream.bufferedReader().readText().trim().takeIf { it.isNotBlank() }
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
            return (listOfNotNull(fromShell, current) + fallbacks)
                .flatMap { it.split(File.pathSeparator) }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(File.pathSeparator)
        }

        /** First executable named [command] across [path], or null. */
        internal fun findOnPath(command: String, path: String): String? =
            path.split(File.pathSeparator)
                .asSequence()
                .filter { it.isNotBlank() }
                .map { File(it, command) }
                .firstOrNull { it.isFile && it.canExecute() }
                ?.absolutePath

        /**
         * Kotlin stays on PSI.
         *
         * `navigationResolver` REPLACES the internal NavigationManager rather than
         * layering over it, and it can only answer Found/NotFound - there is no
         * ShowUsages variant. Handing Kotlin to LSP would therefore trade a working
         * find-usages popup for a definition jump. Deciding per file keeps both.
         */
        fun usesPsi(filePath: String): Boolean =
            filePath.endsWith(".kt") || filePath.endsWith(".kts")

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
                ?: uri.removePrefix("file://").takeIf { it.startsWith("/") }
    }
}
