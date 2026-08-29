package ai.rever.boss.plugin.dynamic.editortab

/**
 * What one stat of the open file saw. Deliberately just the three cheap facts a poll can
 * gather without reading the file: whether it is there, when it was last written, how big
 * it is. Content only gets read once these say something changed.
 *
 * Known limit: an edit that changes no bytes of length, on a filesystem with second-grained
 * mtime (HFS+), landing inside the same second we last looked, is invisible here until the
 * next write moves one of the three. APFS and ext4 both timestamp far finer than the poll
 * interval, so this is a corner rather than a case.
 */
internal data class DiskSnapshot(
    val exists: Boolean,
    val lastModified: Long,
    val size: Long,
) {
    companion object {
        /** A file that is not on disk. All missing snapshots compare equal. */
        val MISSING = DiskSnapshot(exists = false, lastModified = 0L, size = 0L)
    }
}

/** What [ExternalChangeDetector] makes of the latest snapshot. */
internal enum class DiskState {
    /** Disk still matches what we last loaded or wrote. */
    UNCHANGED,

    /** Something is moving. Look again shortly, do not read the file yet. */
    SETTLING,

    /** Disk has moved off our baseline and has stopped moving. Safe to read. */
    CHANGED,

    /** The file has been gone for long enough that it is not a rename in flight. */
    DELETED,
}

/**
 * Decides when a file changed underneath an open editor tab, from snapshots alone.
 *
 * Two things it deliberately refuses to do:
 *
 * - **Act on the first differing snapshot.** A writer that is still writing produces a
 *   sequence of different snapshots; reading at that moment yields a torn file. A change is
 *   only reported once two consecutive snapshots agree with each other and disagree with the
 *   baseline. (This settles a write in flight. It cannot settle a *process* that writes in
 *   several complete steps - a shell loop appending every 400ms is indistinguishable from a
 *   file that was appended to and then left alone, and both are legitimately worth showing.)
 *
 * - **Call a missing file deleted straight away.** An atomic save is a write to a temp file
 *   plus a rename, and a poll landing in that gap sees nothing there. [MISSING_TICKS_BEFORE_DELETED]
 *   consecutive misses are required before it counts.
 *
 * No file I/O and no Compose here on purpose, so the rules can be unit tested directly -
 * same reason `scrollMetrics` and `gutterIconOffsetPx` live outside the component.
 */
internal class ExternalChangeDetector(baseline: DiskSnapshot) {

    /** What we believe is ours: the state at the last load, save or accepted reload. */
    private var baseline: DiskSnapshot = baseline

    /** The previous tick, to spot a snapshot that is still moving. */
    private var previous: DiskSnapshot = baseline

    private var missingTicks: Int = 0

    fun observe(snapshot: DiskSnapshot): DiskState {
        val prior = previous
        previous = snapshot

        if (!snapshot.exists) {
            missingTicks++
            return if (missingTicks >= MISSING_TICKS_BEFORE_DELETED) DiskState.DELETED else DiskState.SETTLING
        }
        missingTicks = 0

        return when {
            snapshot == baseline -> DiskState.UNCHANGED
            // Still moving: this tick disagrees with the last one.
            snapshot != prior -> DiskState.SETTLING
            else -> DiskState.CHANGED
        }
    }

    /**
     * Adopts [snapshot] as the new baseline: called after we load, after we save, after a
     * reload, and when the user chooses to keep their version - anything that means "this
     * disk state is no longer news".
     */
    fun accept(snapshot: DiskSnapshot) {
        baseline = snapshot
        previous = snapshot
        missingTicks = 0
    }

    private companion object {
        const val MISSING_TICKS_BEFORE_DELETED = 3
    }
}

/** What to do once a settled external change has actually been read off disk. */
internal enum class ReloadAction {
    /** Disk and buffer say the same thing. Re-baseline and stay quiet. */
    NONE,

    /** No local edits to lose. Swap the content in. */
    RELOAD,

    /** Local edits would be lost. Ask first. */
    PROMPT,
}

/**
 * The content-level half of the decision, kept separate from the snapshot-level half because
 * a changed mtime very often means nothing changed at all: `touch`, a formatter that had
 * nothing to reformat, a `git checkout` of the same revision, or our own save observed a
 * moment late. Prompting in those cases would be pure noise.
 */
internal fun reloadAction(
    diskContent: String,
    bufferContent: String,
    bufferModified: Boolean,
): ReloadAction = when {
    diskContent == bufferContent -> ReloadAction.NONE
    bufferModified -> ReloadAction.PROMPT
    else -> ReloadAction.RELOAD
}

/** How long between stats of an open file that looks quiet, while the window has focus. */
internal const val DISK_POLL_INTERVAL_MILLIS = 1_000L

/**
 * The same, while BOSS is in the background. Slower rather than stopped: an edit made behind
 * BOSS's back is caught by the check that runs the moment focus returns, so this only exists
 * so the feature degrades to "a bit late" rather than to nothing if a host ever reports focus
 * in a way this tab does not expect.
 */
internal const val DISK_BACKGROUND_POLL_INTERVAL_MILLIS = 5_000L

/**
 * How long between stats once something has moved. Short, so a settled change is picked up
 * about as fast as it is noticed rather than a whole poll interval later.
 */
internal const val DISK_SETTLE_INTERVAL_MILLIS = 200L
