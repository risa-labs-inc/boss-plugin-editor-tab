package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.PluginContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Composer session model and persistence (IDE batch P4.3).
 *
 * A session is one composer tab: the task, its chat, and the set of proposed
 * edits the agent accumulated. State persists in the plugin's storage under
 * `composer-session-<id>` so a restored tab (workspace or restart) reloads its
 * history. Proposals are applied through the editor buffer API only on accept,
 * each version-guarded.
 */
@Serializable
data class ComposerProposal(
    val id: String,
    val path: String,
    val startLine: Int,
    val startCol: Int,
    val endLine: Int,
    val endCol: Int,
    val newText: String,
    val expectedVersion: Long,
    val status: String = "pending",
    val statusMessage: String = "",
)

/**
 * The editor selection a composer session works against.
 *
 * Captured from the focused editor when the session is opened or a task is
 * started, so the chat says which lines it is about - and so the agent is
 * pointed at them instead of guessing from the task text. [text] is capped:
 * it rides in the prompt, and a whole-file "selection" is not context, it is
 * the file (which the agent can read for itself with read_buffer).
 */
@Serializable
data class ComposerSelection(
    val path: String,
    val startLine: Int,
    val endLine: Int,
    val text: String,
    val language: String = "",
) {
    val lineCount: Int get() = (endLine - startLine + 1).coerceAtLeast(1)

    /** "Editor.kt:120-148", the label the chat and the chip both show. */
    fun label(): String =
        path.substringAfterLast('/') +
            ":" + startLine + (if (endLine != startLine) "-$endLine" else "")

    companion object {
        const val MAX_TEXT_CHARS = 8000
    }
}

@Serializable
data class ComposerMessage(
    val role: String,
    val text: String,
)

@Serializable
data class ComposerSessionData(
    val sessionId: String,
    val task: String,
    val title: String,
    val status: String = "idle",
    val statusMessage: String = "",
    val pendingStart: Boolean = false,
    val messages: List<ComposerMessage> = emptyList(),
    val proposals: List<ComposerProposal> = emptyList(),
    val selection: ComposerSelection? = null,
)

/**
 * Loads and saves composer sessions in the plugin's storage.
 *
 * [load] returns null for unknown or corrupt sessions - a restored tab then
 * starts empty rather than failing.
 */
class ComposerSessionStore(
    private val storage: ai.rever.boss.plugin.api.PluginStorageProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private fun key(sessionId: String) = "composer-session-$sessionId"

    suspend fun load(sessionId: String): ComposerSessionData? =
        runCatching {
            storage.getString(key(sessionId))?.let {
                json.decodeFromString<ComposerSessionData>(it)
            }
        }.getOrNull()

    suspend fun save(session: ComposerSessionData) {
        runCatching {
            storage.putString(key(sessionId = session.sessionId), json.encodeToString(session))
        }
    }

    suspend fun delete(sessionId: String) {
        runCatching { storage.remove(key(sessionId)) }
    }
}

/** New-session id, the same shape the host's ComposerTabInfo uses. */
fun newComposerSessionId(): String =
    "composer-${System.nanoTime()}-${(0..2147483647).random()}"

/** Truncate a task to a tab title. */
fun composerTitleFor(task: String): String {
    val t = task.trim().replace('\n', ' ')
    return if (t.length > 32) t.take(31) + "…" else t.ifEmpty { "Composer" }
}