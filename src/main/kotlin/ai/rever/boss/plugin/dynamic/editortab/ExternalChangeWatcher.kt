package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.GitDataProvider
import ai.rever.bosseditor.core.EditorPosition
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * Keeps open buffers honest about the files behind them.
 *
 * A file edited outside BOSS - a `git checkout`, a rebase, a formatter, another
 * editor - used to leave the tab showing content that no longer existed, which
 * meant the user could be reading, or typing into, a version of the file that
 * was gone. `EditorTabComponent` had `hasExternalChanges()` and
 * `reloadFileFromDisk()` for exactly this and nothing ever called them.
 *
 * Polling rather than a `WatchService`: it is what the codebase plugin's
 * FileWatcherService already does here, it needs no per-directory registration
 * or macOS-specific tuning, and the set being watched is tiny - only paths that
 * have an open buffer, which is a handful of files, not a project tree.
 *
 * The decision itself is not here. It lives in [ExternalChangePolicy], pure and
 * unit tested, because "reload or ask" is a small state machine whose failure
 * mode is eating unsaved work.
 */
internal class ExternalChangeWatcher(
    private val scope: CoroutineScope,
    /**
     * Git, when the host has it. Supplied lazily because the provider is
     * per-window and may appear after the plugin registers.
     */
    private val gitProvider: () -> GitDataProvider? = { null },
    private val pollInterval: Long = POLL_INTERVAL_MS,
    /**
     * Where the document is mutated. The editor's document is UI state, so a
     * reload lands on the main thread; tests inject an immediate dispatcher.
     */
    private val applyOn: CoroutineContext = Dispatchers.Main,
) {
    private var job: Job? = null
    private var tick = 0

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                delay(pollInterval)
                runCatching { sweep() }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun sweep() {
        tick++
        for (buffer in EditorBufferRegistry.all()) {
            if (buffer.path.isBlank()) continue
            runCatching { checkOnce(buffer) }
            runCatching { refreshGitMarks(buffer) }
        }
    }

    /**
     * Recompute the editor's git gutter for one buffer.
     *
     * Hung off the same sweep as the file watch so there is ONE timer for all
     * of this, and the 1.5s tick doubles as the debounce - the marks follow
     * typing within a tick without a request per keystroke.
     *
     * The git call is the expensive part, so it is made only when something
     * could have moved it: the first sight of the file, a save, or the slow
     * periodic refresh that catches a stage or commit made elsewhere. In
     * between, the recompute is a line diff against the cached HEAD text, and
     * it is skipped entirely when the document has not changed.
     */
    internal suspend fun refreshGitMarks(buffer: EditorBuffer) {
        val git = gitProvider() ?: return
        if (!git.isGitRepository.value) return

        val refreshHead = buffer.headText == null || buffer.headStale ||
            tick % HEAD_REFRESH_TICKS == 0
        if (refreshHead) {
            buffer.headText = headTextFor(git, buffer.path)
            buffer.headStale = false
        }
        val head = buffer.headText ?: return

        val version = buffer.version
        if (!refreshHead && version == buffer.gitMarksVersion) return
        buffer.gitMarksVersion = version
        val marks = LineDiff.of(head.split("\n"), buffer.content.split("\n"))
        buffer.setGitMarks(marks)
    }

    /**
     * The committed text of [path].
     *
     * Taken from the working-tree diff rather than a `git show`: the host asks
     * git for that diff with full context, so its pre-image IS the whole HEAD
     * version, and [GitDataProvider] exposes no way to read a blob directly.
     * No diff means the file on disk already matches HEAD.
     */
    private suspend fun headTextFor(git: GitDataProvider, path: String): String? =
        runCatching {
            val diff = git.diffFile(path, staged = false).firstOrNull()
            if (diff == null || diff.hunks.isEmpty()) {
                withContext(Dispatchers.IO) { File(path).takeIf { it.exists() }?.readText() }
            } else {
                DiffSides.of(diff).oldText
            }
        }.getOrNull()

    /** One pass over a single buffer. Internal so the tests can drive it. */
    internal suspend fun checkOnce(buffer: EditorBuffer) {
        val file = File(buffer.path)
        val known = buffer.knownSignature
        val current = withContext(Dispatchers.IO) { signatureOf(file) }
        if (current == known && current.exists) return

        // Only read once the cheap screen has already flagged something.
        val diskText = withContext(Dispatchers.IO) {
            if (current.exists) runCatching { file.readText() }.getOrNull() else null
        }
        val bufferText = buffer.content
        val verdict = ExternalChangePolicy.decide(
            known = known,
            current = current,
            diskText = diskText,
            bufferText = bufferText,
            hasUnsavedChanges = buffer.editorState.isModified.value,
        )

        when (verdict) {
            ExternalChangePolicy.Verdict.NONE -> {
                // Take the new signature so an unchanged file is not re-read on
                // every tick.
                buffer.knownSignature = current
            }

            ExternalChangePolicy.Verdict.DELETED -> {
                buffer.knownSignature = current
                buffer.setExternalState(ExternalState.DELETED)
            }

            ExternalChangePolicy.Verdict.CONFLICT -> {
                // Note NOTHING else: the buffer keeps the user's edits and the
                // UI asks. Deliberately does not update knownSignature, so the
                // conflict survives until it is resolved one way or the other.
                buffer.setExternalState(ExternalState.CONFLICT)
            }

            ExternalChangePolicy.Verdict.RELOAD -> {
                val text = diskText ?: return
                withContext(applyOn) { applyReload(buffer, text) }
                buffer.knownSignature = current
                buffer.setExternalState(ExternalState.IN_SYNC)
            }
        }
    }

    /**
     * Replace the buffer's content in place, keeping the caret and the scroll.
     *
     * IN PLACE is the point: one [EditorBuffer] can be shown by an editor tab
     * and by the diff tab's editable pane at once, and they hold the same
     * [ai.rever.bosseditor.core.EditorState]. Swapping the state out would
     * leave one of them pointed at a document nothing else can see, so the
     * document's own `setText` is used and every viewport follows.
     */
    fun applyReload(buffer: EditorBuffer, text: String) {
        val state = buffer.editorState
        val caret = state.caretPosition.value
        val scroll = state.scrollOffset.value

        state.setText(text)

        val document = state.document
        val (line, column) = ExternalChangePolicy.clampCaret(
            line = caret.line,
            column = caret.column,
            newLineCount = document.lineCount,
        ) { runCatching { document.getLineText(it).length }.getOrDefault(0) }
        runCatching { state.moveCaret(EditorPosition(line, column), false) }
        runCatching { state.setScrollOffset(scroll) }
        // The buffer now matches the disk, so it is not "modified".
        state.markAsSaved()
    }

    /** The user chose to take the disk's version, losing their edits. */
    fun resolveByReloading(buffer: EditorBuffer) {
        val file = File(buffer.path)
        val text = runCatching { file.readText() }.getOrNull() ?: return
        applyReload(buffer, text)
        buffer.knownSignature = signatureOf(file)
        buffer.setExternalState(ExternalState.IN_SYNC)
    }

    /** The user chose to keep their edits; the disk's version is theirs to overwrite later. */
    fun resolveByKeepingMine(buffer: EditorBuffer) {
        buffer.knownSignature = signatureOf(File(buffer.path))
        buffer.setExternalState(ExternalState.IN_SYNC)
    }

    companion object {
        /**
         * Slow enough to cost nothing (a handful of `stat` calls), fast enough
         * that a `git checkout` shows up before the user has read a screenful.
         */
        const val POLL_INTERVAL_MS = 1_500L

        /**
         * How many sweeps between git calls for an idle file - 15s at the
         * default tick. Enough to notice a commit or a stage made in a
         * terminal without shelling out for every open buffer every second.
         */
        const val HEAD_REFRESH_TICKS = 10

        @Volatile
        private var instance: ExternalChangeWatcher? = null

        fun install(
            scope: CoroutineScope,
            gitProvider: () -> GitDataProvider? = { null },
        ): ExternalChangeWatcher =
            (instance ?: ExternalChangeWatcher(scope, gitProvider).also { instance = it })
                .also { it.start() }

        fun current(): ExternalChangeWatcher? = instance

        fun uninstall() {
            instance?.stop()
            instance = null
        }
    }
}

/**
 * The bar shown when the file behind a buffer diverged or disappeared.
 *
 * A conflict is offered as a CHOICE, never resolved for the user: "reload"
 * discards their unsaved edits and is unrecoverable, so it has to be something
 * they pick. Doing nothing keeps their work, which is the safe default and what
 * happens if they simply ignore the bar.
 */
@androidx.compose.runtime.Composable
internal fun ExternalChangeBar(
    buffer: EditorBuffer,
    onReload: () -> Unit,
    onKeepMine: () -> Unit,
) {
    val state by buffer.externalState.collectAsState()
    if (state == ExternalState.IN_SYNC) return

    val deleted = state == ExternalState.DELETED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (deleted) ExternalDeletedBg else ExternalConflictBg)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (deleted) {
                "This file has been deleted on disk."
            } else {
                "This file changed on disk and you have unsaved changes."
            },
            fontSize = 12.sp,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (!deleted) {
            Text(
                text = "Reload (lose my changes)",
                fontSize = 12.sp,
                color = Color.White,
                modifier = Modifier.clickable { onReload() }.padding(horizontal = 6.dp),
            )
            Text(
                text = "Keep mine",
                fontSize = 12.sp,
                color = Color.White,
                modifier = Modifier.clickable { onKeepMine() }.padding(horizontal = 6.dp),
            )
        } else {
            Text(
                text = "Dismiss",
                fontSize = 12.sp,
                color = Color.White,
                modifier = Modifier.clickable { onKeepMine() }.padding(horizontal = 6.dp),
            )
        }
    }
}

private val ExternalConflictBg = Color(0xFF7A5C1E)
private val ExternalDeletedBg = Color(0xFF7A2B2B)
