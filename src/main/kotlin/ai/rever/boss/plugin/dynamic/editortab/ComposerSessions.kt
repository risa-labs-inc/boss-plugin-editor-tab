package ai.rever.boss.plugin.dynamic.editortab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * The live composer sessions, owned by the PLUGIN rather than by a tab.
 *
 * Why this exists: a composer run outlives the view. The agent loop runs in
 * the plugin scope, but its progress used to be written into the tab
 * component that started it - so switching tabs (or closing and reopening
 * one) left the run writing into a view nobody was looking at, and coming
 * back re-read the debounced copy from storage, which is at best a few
 * hundred milliseconds behind and at worst the pre-run state. The output
 * appeared to vanish.
 *
 * State lives here instead: one [MutableStateFlow] per session id, mutated
 * atomically with [update], persisted on this scope. A tab is a view over the
 * flow and owns nothing; a session with no tab open keeps running and keeps
 * its transcript.
 *
 * [update] applies its transform to the CURRENT value rather than to a
 * snapshot the caller captured earlier - the previous shape (agent captures
 * `session`, then posts `session.copy(...)` for each step) meant every tool
 * log rewound the transcript to the state at the start of the run, dropping
 * the proposals recorded in between.
 */
class ComposerSessions(
    private val store: ComposerSessionStore?,
    private val scope: CoroutineScope,
) {
    private val flows = HashMap<String, MutableStateFlow<ComposerSessionData?>>()
    private val loadStarted = HashSet<String>()
    private val persistJobs = HashMap<String, Job>()

    @Synchronized
    private fun flowFor(sessionId: String): MutableStateFlow<ComposerSessionData?> =
        flows.getOrPut(sessionId) { MutableStateFlow(null) }

    @Synchronized
    private fun claimLoad(sessionId: String): Boolean = loadStarted.add(sessionId)

    /**
     * The live state for [sessionId]. Null until the persisted copy has been
     * read (the view renders a spinner); the read happens once per session
     * per app run, and never overwrites a session that is already live.
     */
    fun open(sessionId: String): StateFlow<ComposerSessionData?> {
        val flow = flowFor(sessionId)
        if (claimLoad(sessionId)) {
            scope.launch {
                val restored = withContext(Dispatchers.IO) { store?.load(sessionId) }
                flow.compareAndSet(
                    expect = null,
                    update = restored?.let(::afterRestart)
                        ?: ComposerSessionData(sessionId, task = "", title = "Composer"),
                )
            }
        }
        return flow.asStateFlow()
    }

    /** The live value, or null when this session has never been opened here. */
    fun snapshot(sessionId: String): ComposerSessionData? = flowFor(sessionId).value

    /** The live value, falling back to storage for a session with no tab open. */
    suspend fun read(sessionId: String): ComposerSessionData? =
        snapshot(sessionId) ?: withContext(Dispatchers.IO) { store?.load(sessionId) }

    /** Seed a session (the ai_compose MCP tool) and persist it at once. */
    fun put(session: ComposerSessionData) {
        claimLoad(session.sessionId)
        flowFor(session.sessionId).value = session
        persistNow(session)
    }

    /**
     * Mutate a live session atomically. A no-op (returning null) for a session
     * that is not loaded - callers start runs only on sessions they opened.
     */
    fun update(
        sessionId: String,
        transform: (ComposerSessionData) -> ComposerSessionData,
    ): ComposerSessionData? {
        var latest: ComposerSessionData? = null
        flowFor(sessionId).update { current ->
            if (current == null) null else transform(current).also { latest = it }
        }
        latest?.let(::schedulePersist)
        return latest
    }

    /**
     * Write every live session out now. Blocking on purpose: this runs from
     * plugin dispose, immediately before the scope that would have carried
     * the writes is cancelled.
     */
    fun flushAll() {
        val live = synchronized(this) { flows.values.mapNotNull { it.value } }
        synchronized(this) { persistJobs.values.forEach { it.cancel() }; persistJobs.clear() }
        if (live.isEmpty() || store == null) return
        runBlocking(Dispatchers.IO) { live.forEach { store.save(it) } }
    }

    private fun schedulePersist(session: ComposerSessionData) {
        val job =
            scope.launch {
                delay(PERSIST_DEBOUNCE_MS)
                withContext(Dispatchers.IO) { store?.save(session) }
            }
        val previous = synchronized(this) { persistJobs.put(session.sessionId, job) }
        previous?.cancel()
    }

    private fun persistNow(session: ComposerSessionData) {
        synchronized(this) { persistJobs.remove(session.sessionId) }?.cancel()
        scope.launch { withContext(Dispatchers.IO) { store?.save(session) } }
    }

    /**
     * A session persisted as "running" was cut off by the app closing - the
     * run did not survive it, so say so rather than showing a spinner that
     * will never resolve.
     */
    private fun afterRestart(session: ComposerSessionData): ComposerSessionData =
        if (session.status == "running") {
            session.copy(status = "idle", statusMessage = "The app restarted mid-run; start again.")
        } else {
            session
        }

    private companion object {
        const val PERSIST_DEBOUNCE_MS = 400L
    }
}
