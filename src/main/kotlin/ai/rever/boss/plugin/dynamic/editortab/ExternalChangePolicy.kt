package ai.rever.boss.plugin.dynamic.editortab

/**
 * What to do when the file behind an open buffer changes underneath it.
 *
 * Pure, and the one hard rule lives here: an unsaved edit is never discarded.
 * Showing stale content is recoverable - the user reloads. Clobbering work the
 * user has typed but not saved is not recoverable by anything, so the only
 * verdict this may reach for a dirty buffer is [Verdict.CONFLICT], which asks.
 *
 * The other trap is our OWN writes. Rather than time-boxing them (a debounce
 * that is always either too short or too long), the policy compares CONTENT:
 * after we save, the disk and the buffer agree, so there is nothing to report.
 * That also silences a `touch`, and a third party writing a byte-identical
 * file, for the same reason and with no timing to get wrong.
 */
internal object ExternalChangePolicy {

    /**
     * What a file looked like last time we stat'd it. mtime and size together
     * are the cheap screen; content settles anything they flag.
     */
    data class FileSignature(
        val exists: Boolean,
        val modifiedAt: Long,
        val size: Long,
    ) {
        companion object {
            val MISSING = FileSignature(exists = false, modifiedAt = 0L, size = 0L)
        }
    }

    enum class Verdict {
        /** Nothing to do; the buffer already agrees with the disk. */
        NONE,

        /** Disk moved on and the buffer is clean: take the new content. */
        RELOAD,

        /** Disk moved on and the buffer is dirty: ASK, never overwrite. */
        CONFLICT,

        /** The file is gone. Mark the tab; do not blank the buffer. */
        DELETED,
    }

    fun decide(
        known: FileSignature,
        current: FileSignature,
        /** The file's current text, or null when it could not be read this time. */
        diskText: String?,
        bufferText: String,
        hasUnsavedChanges: Boolean,
    ): Verdict = when {
        // Gone. Reported once: a second poll on a still-missing file has
        // nothing new to say.
        !current.exists -> if (known.exists) Verdict.DELETED else Verdict.NONE

        // The cheap screen says nothing happened.
        current == known -> Verdict.NONE

        // Stat changed but the read failed - a half-written file, a lock, a
        // permission blip. Say nothing and look again next time rather than
        // acting on content we do not have.
        diskText == null -> Verdict.NONE

        // The signature moved but the text did not: our own save, a touch, or
        // someone writing identical bytes. This is the self-write guard.
        diskText == bufferText -> Verdict.NONE

        // Real divergence. Unsaved work is never thrown away without asking.
        hasUnsavedChanges -> Verdict.CONFLICT

        else -> Verdict.RELOAD
    }

    /**
     * Where the caret should land after a reload.
     *
     * A reload can shorten the file under the caret, so both coordinates are
     * clamped rather than trusted - restoring a caret past the end throws or
     * silently snaps somewhere surprising.
     */
    fun clampCaret(
        line: Int,
        column: Int,
        newLineCount: Int,
        lengthOfLine: (Int) -> Int,
    ): Pair<Int, Int> {
        if (newLineCount <= 0) return 0 to 0
        val clampedLine = line.coerceIn(0, newLineCount - 1)
        val clampedColumn = column.coerceIn(0, lengthOfLine(clampedLine).coerceAtLeast(0))
        return clampedLine to clampedColumn
    }
}
