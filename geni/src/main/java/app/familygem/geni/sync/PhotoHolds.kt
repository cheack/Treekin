package app.familygem.geni.sync

/** Result of [PhotoHolds.commit]. */
data class CommitResult(
    /** False if the hold was already released: the block did not run. */
    val active: Boolean,
    /** What the block returned; the hold is released only when true. */
    val ok: Boolean,
    val treeId: Int?
)

/**
 * Photos that wait for the user's decision about cropping, by tree and local file name.
 * A held new-photo job stays pending and is not sent until the hold is released. Process-local on purpose:
 * after the process dies the persisted job sends the last fully written file.
 */
class PhotoHolds {

    private val held = mutableMapOf<Long, Pair<Int, String>>()
    private var last = 0L

    /** Called after every hold or release, with the lock held: it must be quick and must not call back here. */
    @Volatile
    var onChange: () -> Unit = {}

    /** Holds the photo [file] of a tree; returns the session that releases it (never 0). */
    @Synchronized
    fun acquire(treeId: Int, file: String): Long = (++last).also {
        held[it] = treeId to file
        onChange()
    }

    @Synchronized
    fun isHeld(treeId: Int, file: String): Boolean = held.containsValue(treeId to file)

    @Synchronized
    fun isActive(session: Long): Boolean = session in held

    /** Releases a session; returns its tree ID, or null if it was already released (idempotent). */
    @Synchronized
    fun release(session: Long): Int? = held.remove(session)?.first?.also { onChange() }

    /**
     * Runs [block] (it replaces the file with the finished crop) only while the session is held, and releases
     * the session if it returns true. Under the same lock as [release], so a released photo is never rewritten.
     */
    @Synchronized
    fun commit(session: Long, block: () -> Boolean): CommitResult {
        val key = held[session] ?: return CommitResult(false, false, null)
        val ok = block()
        if (ok) {
            held.remove(session)
            onChange()
        }
        return CommitResult(true, ok, key.first)
    }
}
