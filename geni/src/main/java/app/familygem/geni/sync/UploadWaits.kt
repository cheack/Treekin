package app.familygem.geni.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Why the uploads of a whole tree stopped until something happens. */
sealed interface TreeWait {
    /** A temporary problem: the next run starts at [atMillis] or when the network comes back. */
    data class Retry(val atMillis: Long) : TreeWait

    /** The login is missing or expired: the uploads go on after the next login. */
    data object NeedsLogin : TreeWait
}

/** The stops of each tree as the upload runner decided them, in memory: for the sync backlog. */
class UploadWaits {

    private val state = MutableStateFlow<Map<Int, TreeWait>>(emptyMap())

    val waits: StateFlow<Map<Int, TreeWait>> = state.asStateFlow()

    /** Records why [treeId] stopped, or clears it with null when a run starts or the queue is empty. */
    fun set(treeId: Int, wait: TreeWait?) = state.update { if (wait == null) it - treeId else it + (treeId to wait) }
}
