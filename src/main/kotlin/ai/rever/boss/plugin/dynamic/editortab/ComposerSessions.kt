package ai.rever.boss.plugin.dynamic.editortab

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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
    // How many composer tabs currently hold this session (per tab instance;
    // open() is called once per tab in its init).
    private val openTabs = HashMap<String, Int>()

    @Synchronized
    private fun flowFor(sessionId: String): MutableStateFlow<ComposerSessionData?> =
        flows.getOrPut(sessionId) { MutableStateFlow(null) }

    /**
     * The session's flow WITHOUT creating it. Read-only callers must use
     * this: a getOrPut here would resurrect a just-closed session as a
     * permanent null flow (maybeCloseIfIdle did exactly that on its own
     * `?: return` path), undoing what [close] is for.
     */
    @Synchronized
    private fun peekFlow(sessionId: String): MutableStateFlow<ComposerSessionData?>? = flows[sessionId]

    @Synchronized
    private fun claimLoad(sessionId: String): Boolean = loadStarted.add(sessionId)

    /**
     * The live state for [sessionId]. Null until the persisted copy has been
     * read (the view renders a spinner); the read happens once per session
     * per app run, and never overwrites a session that is already live.
     */
    fun open(sessionId: String): StateFlow<ComposerSessionData?> {
        val flow = flowFor(sessionId)
        synchronized(this) { openTabs[sessionId] = (openTabs[sessionId] ?: 0) + 1 }
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

    /** A composer tab went away (its onDestroy). Pairs with [open]. */
    fun detach(sessionId: String) {
        synchronized(this) {
            val n = (openTabs[sessionId] ?: 0) - 1
            if (n <= 0) openTabs.remove(sessionId) else openTabs[sessionId] = n
        }
        maybeCloseIfIdle(sessionId)
    }

    /**
     * Forget a session that nothing can still need.
     *
     * Safe only when NO tab shows it, it is not running, and no proposal is
     * still pending or mid-apply - then even a restored tab would have
     * nothing left to reload. Runs deliberately outlive their tabs (the
     * design point of this class), so a running session is never closed
     * here, whatever the tab count; its finish calls back in. Without this,
     * every session ever run stays in plugin storage - and in [flows] - for
     * the process lifetime.
     */
    fun maybeCloseIfIdle(sessionId: String) {
        if (synchronized(this) { (openTabs[sessionId] ?: 0) > 0 }) return
        val data = snapshot(sessionId) ?: return
        if (data.status == "running") return
        if (data.proposals.any { it.status == "pending" || it.status == "applying" }) return
        close(sessionId)
    }

    /** Forget a session completely: live flow, pending persist, stored copy. */
    fun close(sessionId: String) {
        synchronized(this) {
            persistJobs.remove(sessionId)?.cancel()
            flows.remove(sessionId)
            loadStarted.remove(sessionId)
            openTabs.remove(sessionId)
        }
        // No-op if the scope is already gone (plugin dispose runs close-adjacent
        // work on the way out); the storage entry then dies with the app anyway.
        scope.launch { withContext(Dispatchers.IO) { store?.delete(sessionId) } }
    }

    /** The live value, or null when this session has never been opened here. */
    fun snapshot(sessionId: String): ComposerSessionData? = peekFlow(sessionId)?.value

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
     * Mutate a live session atomically. A no-op (returning null) for a
     * session that is not loaded - callers start runs only on sessions they
     * opened - and the no-op leaves no trace: the lookup must not CREATE the
     * flow, or an update racing a close would re-insert the session that
     * close just removed.
     */
    fun update(
        sessionId: String,
        transform: (ComposerSessionData) -> ComposerSessionData,
    ): ComposerSessionData? {
        val flow = peekFlow(sessionId) ?: return null
        var latest: ComposerSessionData? = null
        flow.update { current ->
            if (current == null) null else transform(current).also { latest = it }
        }
        latest?.let(::schedulePersist)
        return latest
    }

    /**
     * Write every live session out now. Blocking on purpose: this runs from
     * plugin dispose, immediately before the scope that would have carried
     * the writes is cancelled. Bounded, because a hung filesystem must not
     * freeze the app's UI thread during unload - losing the last writes is
     * the lesser evil, and nothing is corrupted: the sessions stay live in
     * memory until the process ends.
     */
    fun flushAll() {
        val live = synchronized(this) { flows.values.mapNotNull { it.value } }
        synchronized(this) { persistJobs.values.forEach { it.cancel() }; persistJobs.clear() }
        if (live.isEmpty() || store == null) return
        try {
            runBlocking(Dispatchers.IO) {
                withTimeout(FLUSH_TIMEOUT_MS) { live.forEach { store.save(it) } }
            }
        } catch (e: TimeoutCancellationException) {
            // See above: a hung store loses the tail of the flush, not the app.
        }
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
        const val FLUSH_TIMEOUT_MS = 5_000L
    }
}
