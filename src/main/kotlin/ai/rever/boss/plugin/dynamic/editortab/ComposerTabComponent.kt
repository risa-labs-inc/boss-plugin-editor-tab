package ai.rever.boss.plugin.dynamic.editortab

import ai.rever.boss.plugin.api.TabIcon
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeInfo
import ai.rever.boss.plugin.api.TabComponentWithUI
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.essenty.lifecycle.Lifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The plugin-side tab info for composer tabs opened from within the plugin
 * (MCP ai_compose, or any future in-app affordance).
 *
 * Workspace persistence matches on the type id (the host cannot see this
 * class - the api jar filters it out), and the tab id is the session id so
 * both this class and the host's restore-time class expose the same value
 * through the plain TabInfo interface.
 */
data class ComposerTabData(
    override val id: String,
    override val title: String,
) : TabInfo {
    override val typeId: ai.rever.boss.plugin.api.TabTypeId = ComposerTabType.typeId
    override val icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.SmartToy
    override val tabIcon: TabIcon? = null
}

/**
 * Composer tab (IDE batch P4.3).
 *
 * A task-in / diffs-out AI work session: the left pane is the chat with the
 * agent (its tool activity is logged there), the right pane is the worklist of
 * proposed edits with per-proposal accept/reject. Accept routes through the
 * version-guarded buffer API; nothing is applied behind the user's back.
 *
 * Session state persists in plugin storage under the session id (the tab id),
 * so a workspace-restored tab reloads its history. Without a gateway or
 * without the buffer API the tab renders an empty state instead of breaking.
 */
class ComposerTabComponent(
    private val ctx: ComponentContext,
    override val config: TabInfo,
    private val agent: ComposerAgent?,
    private val sessions: ComposerSessions?,
) : TabComponentWithUI, ComponentContext by ctx {

    override val tabTypeInfo: TabTypeInfo = ComposerTabType

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val sessionId: String = config.id

    /**
     * The session state, owned by [ComposerSessions] for the plugin's
     * lifetime. This component holds no copy: a run started here keeps
     * writing to the session while the tab sits in the background, and coming
     * back re-reads the live flow rather than the last persisted snapshot.
     */
    private val session: StateFlow<ComposerSessionData?> =
        sessions?.open(sessionId) ?: MutableStateFlow(null)

    /** Set by the chip's dismiss; re-armed when the next task starts. */
    private var selectionDismissed = false

    init {
        lifecycle.subscribe(
            callbacks = object : Lifecycle.Callbacks {
                override fun onDestroy() {
                    coroutineScope.cancel()
                    // Pair for the open() in the session's init. The session
                    // itself may keep living (a run in flight, or proposals
                    // still pending) - that is [ComposerSessions] to decide.
                    sessions?.detach(sessionId)
                }
            },
        )
        // A run requested through MCP before this tab existed, or one the app
        // was closed on. The flag is cleared by start(), so it fires once.
        coroutineScope.launch {
            val loaded = session.first { it != null } ?: return@launch
            if (loaded.pendingStart && loaded.task.isNotEmpty() && agent?.gateway() != null) {
                runTask(loaded.task)
            }
        }
    }

    /**
     * Point the session at what is selected in the editor right now.
     *
     * Called when the tab is shown and after a run: the chip is a statement
     * about the CURRENT selection, and a stale range would send the agent at
     * the wrong lines. Dismissing the chip suppresses this until the next run.
     */
    fun refreshSelection() {
        if (selectionDismissed) return
        val a = agent ?: return
        // focusedDocument() is suspend (full-document read across the plugin
        // boundary), so the chip refresh is fire-and-forget on the component
        // scope: it never blocks the tab's own UI thread.
        coroutineScope.launch {
            val selection = a.captureSelection()
            sessions?.update(sessionId) { it.copy(selection = selection) }
        }
    }

    fun clearSelection() {
        selectionDismissed = true
        sessions?.update(sessionId) { it.copy(selection = null) }
    }

    fun runTask(task: String) {
        selectionDismissed = false
        agent?.start(sessionId, task)
    }

    fun stopRun() {
        agent?.stop(sessionId)
    }

    // Per path, the buffer version this tab's last accepted proposal produced
    // - applyEdit's own answer, not arithmetic over how many accepts have
    // happened. Read in [acceptProposal].
    private val lastAppliedVersion = HashMap<String, Long>()

    fun acceptProposal(proposal: ComposerProposal) {
        val api = agent?.editorApi ?: return
        val data = session.value ?: return
        coroutineScope.launch {
            markProposal(proposal, "applying", "")
            try {
                // The recorded version is the buffer as it stood when the
                // proposal was MADE. Since then the only legitimate mover is
                // this tab accepting another proposal on the same file - and
                // rather than counting those accepts and assuming each bumps
                // the document by exactly one, remember what applyEdit
                // actually reported. [lastAppliedVersion] is ignored once it
                // predates the proposal's own snapshot, so a re-run against a
                // hand-edited file is guarded by the fresh recorded value
                // instead of a stale accept. Anything else in the gap is an
                // outside edit: fail, never apply over it.
                val path = proposal.path
                val current =
                    api.readBuffer(path)?.version
                        ?: run {
                            markProposal(proposal, "failed", "no open buffer for this path")
                            return@launch
                        }
                val siblings = data.proposals.filter { it.path == path && it.id != proposal.id }
                val required =
                    lastAppliedVersion[path]?.takeIf { it >= proposal.expectedVersion }
                        ?: proposal.expectedVersion
                if (current != required) {
                    markProposal(
                        proposal, "failed",
                        "buffer moved since this was reviewed - re-run the task",
                    )
                    return@launch
                }
                // An accepted sibling from the SAME snapshot that starts at or
                // above this range shifted (or overlaps) these lines, so the
                // recorded line numbers are stale exactly in that case. Do
                // not guess - re-run.
                val shifter =
                    siblings.firstOrNull {
                        it.status == "accepted" &&
                            it.expectedVersion == proposal.expectedVersion &&
                            (it.startLine < proposal.endLine ||
                                (it.startLine == proposal.endLine && it.startCol <= proposal.endCol))
                    }
                if (shifter != null) {
                    markProposal(
                        proposal, "failed",
                        "these lines moved when ${shifter.id} was accepted - re-run the task",
                    )
                    return@launch
                }
                // Re-read, not recorded: a previously accepted sibling is a
                // legitimate version move the recorded value cannot know.
                val result =
                    api.applyEdit(
                        path = path,
                        startLine = proposal.startLine,
                        startCol = proposal.startCol,
                        endLine = proposal.endLine,
                        endCol = proposal.endCol,
                        newText = proposal.newText,
                        expectedVersion = current,
                    )
                if (result.applied) {
                    // applyEdit's own answer, threaded forward. The api allows
                    // it to decline to report one; forget the threaded value
                    // then rather than guessing, so the next accept on this
                    // file is guarded by the recorded version and fails closed.
                    val produced = result.newVersion
                    if (produced != null) lastAppliedVersion[path] = produced
                    else lastAppliedVersion.remove(path)
                    markProposal(proposal, "accepted", "applied (buffer version ${result.newVersion})")
                } else {
                    markProposal(proposal, "failed", result.reason ?: "not applied")
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // The tab closed (or the scope was cancelled) while the apply
                // was in flight: nothing was marked applied, so the proposal
                // goes back to retryable instead of stranding in "applying".
                markProposal(proposal, "pending", "interrupted - try again")
                throw e
            }
        }
    }

    fun rejectProposal(proposal: ComposerProposal) {
        markProposal(proposal, "rejected", "")
    }

    private fun markProposal(proposal: ComposerProposal, status: String, message: String) {
        sessions?.update(sessionId) { s ->
            s.copy(
                proposals = s.proposals.map { p ->
                    if (p.id == proposal.id) p.copy(status = status, statusMessage = message) else p
                },
            )
        }
    }

    @Composable
    override fun Content() {
        val state by session.collectAsState()
        val activeModel = remember { agent?.gateway()?.activeModel() }
        // The tab is composed only while it is the active one, so entering
        // the composition IS "the user came to the composer" - the moment to
        // read what they left selected in the editor.
        LaunchedEffect(Unit) { refreshSelection() }
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                state == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                agent == null -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("Composer unavailable", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "The editor buffer API is not available on this host version.",
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                else -> {
                    val s = state!!
                    Row(modifier = Modifier.fillMaxSize()) {
                        // Chat pane
                        Column(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(s.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                if (s.status == "running") {
                                    CircularProgressIndicator(modifier = Modifier.height(14.dp).padding(horizontal = 8.dp))
                                    OutlinedButton(onClick = { stopRun() }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                                        Text("Stop")
                                    }
                                }
                            }
                            val statusLine = s.statusMessage
                            if (statusLine.isNotEmpty() && s.status != "idle") {
                                Text(
                                    statusLine,
                                    fontSize = 11.sp,
                                    color =
                                        if (s.status == "error") MaterialTheme.colors.error
                                        else MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                                )
                            }
                            val noGateway = agent.gateway() == null
                            if (noGateway) {
                                Text(
                                    "No AI provider configured. Set one up in the Secret Manager panel (AI section).",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                                )
                            }
                            // Messages
                            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                items(s.messages) { m ->
                                    ComposerMessageRow(m)
                                }
                            }
                            // What the run will be about, when the editor
                            // had a selection: named in the UI because the
                            // agent is told to work those lines.
                            s.selection?.let { sel ->
                                ComposerSelectionChip(sel, onClear = { clearSelection() })
                            }
                            // Input
                            var taskInput by remember { mutableStateOf("") }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(8.dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                TextField(
                                    value = taskInput,
                                    onValueChange = { taskInput = it },
                                    placeholder = { Text("Describe a task...") },
                                    modifier = Modifier.weight(1f),
                                    maxLines = 3,
                                )
                                Button(
                                    onClick = {
                                        val t = taskInput.trim()
                                        if (t.isNotEmpty()) {
                                            runTask(t)
                                            taskInput = ""
                                        }
                                    },
                                    enabled = s.status != "running" && taskInput.isNotBlank() && !noGateway,
                                    modifier = Modifier.padding(start = 8.dp),
                                ) {
                                    Text(if (s.task.isEmpty()) "Run" else "Continue")
                                }
                            }
                        }
                        // Worklist
                        Column(
                            modifier =
                                Modifier
                                    .weight(0.4f)
                                    .fillMaxHeight()
                                    .background(MaterialTheme.colors.surface.copy(alpha = 0.5f)),
                        ) {
                            val pendingCount = s.proposals.count { it.status == "pending" }
                            Text(
                                "Proposed changes ($pendingCount pending)",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(12.dp),
                            )
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(s.proposals) { p ->
                                    ComposerProposalRow(
                                        proposal = p,
                                        onAccept = { acceptProposal(p) },
                                        onReject = { rejectProposal(p) },
                                    )
                                }
                                if (s.proposals.isEmpty()) {
                                    item {
                                        Text(
                                            "Nothing proposed yet. The agent records edits here for your review - it never applies them directly.",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                                            modifier = Modifier.padding(12.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (activeModel != null) {
                        Text(
                            activeModel.modelId,
                            fontSize = 10.sp,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                        )
                    }
                }
            }
        }
    }

/**
 * The editor selection this session is pointed at: file, line range, and a
 * dismiss. Shown above the input because it is part of what pressing Run
 * sends, not a decoration on the transcript.
 */
@Composable
private fun ComposerSelectionChip(
    selection: ComposerSelection,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .background(MaterialTheme.colors.primary.copy(alpha = 0.10f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                selection.label(),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.8f),
            )
            Text(
                "  ${selection.lineCount} line${if (selection.lineCount == 1) "" else "s"} selected",
                fontSize = 11.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
            )
            Text(
                "  ✕",
                fontSize = 11.sp,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.clickable(onClick = onClear),
            )
        }
    }
}

@Composable
private fun ComposerMessageRow(m: ComposerMessage) {
    val label = if (m.role == "user") "You" else if (m.role == "assistant") "Agent" else "tool"
    val color =
        when (m.role) {
            "user" -> MaterialTheme.colors.primary
            "assistant" -> MaterialTheme.colors.onSurface
            else -> MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
        }
    val mono = m.role != "user" && m.role != "assistant"
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color)
        SelectionContainer {
            Text(
                m.text,
                fontSize = if (mono) 11.sp else 12.5.sp,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun ComposerProposalRow(
    proposal: ComposerProposal,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    val statusColor =
        when (proposal.status) {
            "accepted" -> Color(0xFF2E7D32)
            "failed" -> MaterialTheme.colors.error
            "rejected" -> MaterialTheme.colors.onSurface.copy(alpha = 0.4f)
            else -> MaterialTheme.colors.onSurface
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .background(Color(0xFF000000).copy(alpha = 0.04f))
                .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                proposal.path.substringAfterLast('/'),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                proposal.status + if (proposal.statusMessage.isNotEmpty()) " - ${proposal.statusMessage}" else "",
                fontSize = 10.sp,
                color = statusColor,
            )
        }
        Text(
            "lines ${proposal.startLine}:${proposal.startCol} - ${proposal.endLine}:${proposal.endCol}",
            fontSize = 10.sp,
            color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
            modifier = Modifier.padding(top = 2.dp),
        )
        if (proposal.status == "pending" || proposal.status == "failed") {
            SelectionContainer {
                Text(
                    proposal.newText.lineSequence().take(6).joinToString("\n"),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp).background(Color(0xFF000000).copy(alpha = 0.04f)).padding(4.dp),
                )
            }
        }
        if (proposal.status == "pending" || proposal.status == "applying") {
            Row(modifier = Modifier.padding(top = 6.dp)) {
                Button(
                    onClick = onAccept,
                    enabled = proposal.status == "pending",
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                ) {
                    Text("Accept")
                }
                OutlinedButton(
                    onClick = onReject,
                    enabled = proposal.status == "pending",
                    modifier = Modifier.padding(start = 8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                ) {
                    Text("Reject")
                }
            }
        }
    }
}}
