package app.familygem.geni.sync

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A kind of download from Geni, as the sync backlog names it. */
enum class DownloadKind {
    /** Relatives loaded around the root after the tree was made. */
    PREFETCH,
    KEEP_OFFLINE,
    DAILY_REFRESH,
    /** The family of one profile, loaded again when its card opens. */
    PERSON_REFRESH,
    /** The root's family, loaded when the tree is made. */
    ROOT_LOAD,
    ALBUM_LIST,
    ALBUM_DOWNLOAD,
    /** One photo file. */
    PHOTO,
    /** One document file. */
    DOCUMENT,
    /** The documents of a profile, listed from Geni. */
    DOCUMENT_LIST,
    /** One video file. */
    VIDEO,
    /** The videos of a profile, listed from Geni. */
    VIDEO_LIST
}

/** Work done inside a running download, shown with it instead of as a task of its own. */
data class DownloadStage(val id: Long, val kind: DownloadKind, val geniId: String?, val done: Int = 0, val total: Int? = null)

/**
 * A download running or waiting for its turn.
 * @param geniId The profile it is about, if any
 * @param active False while it waits for another download of the same tree
 * @param total How many steps it has, if known; [done] counts the finished ones
 * @param stages Nested work running now, outermost first
 */
data class DownloadTask(
    val id: Long,
    val treeId: Int,
    val kind: DownloadKind,
    val geniId: String?,
    val active: Boolean,
    val done: Int = 0,
    val total: Int? = null,
    val stages: List<DownloadStage> = emptyList()
)

/** Lets the code of a download report its steps. */
interface DownloadProgress {
    /** Sets the number of steps, null if unknown. */
    fun total(count: Int?)
    /** One more step finished. */
    fun step()
}

/**
 * Downloads from Geni as they happen, in memory and for this process only: for the sync backlog.
 * A download started inside another one (a person loaded by a branch pass, photos of an album batch) is a stage of the
 * outer one, so each operation is listed once. A task leaves the list when its coroutine ends, however it ends.
 */
class DownloadFeed {

    private val state = MutableStateFlow<List<DownloadTask>>(emptyList())
    private val lastId = AtomicLong()

    val tasks: StateFlow<List<DownloadTask>> = state.asStateFlow()

    /** Marks the coroutine of a running task, so nested downloads find it. */
    private class Current(val feed: DownloadFeed, val taskId: Long) : AbstractCoroutineContextElement(Current) {
        companion object Key : CoroutineContext.Key<Current>
    }

    /** A download waiting for its turn; [run] it once its turn comes, and [remove] it in any case when done. */
    inner class Queued internal constructor(private val id: Long) {
        suspend fun <T> run(block: suspend (DownloadProgress) -> T): T {
            change(id) { it.copy(active = true) }
            return runTask(id, block)
        }

        /** Takes the task off the list; safe to call again. */
        fun remove() = drop(id)
    }

    /** Lists a download that waits for another one: shown as queued until [Queued.run]. */
    fun queue(treeId: Int, kind: DownloadKind, geniId: String?): Queued {
        val id = lastId.incrementAndGet()
        state.update { it + DownloadTask(id, treeId, kind, geniId, active = false) }
        return Queued(id)
    }

    /** Runs [block] as a download: a task of its own, or a stage of the download it runs in. */
    suspend fun <T> track(treeId: Int, kind: DownloadKind, geniId: String?, block: suspend (DownloadProgress) -> T): T {
        val parent = currentCoroutineContext()[Current]?.takeIf { it.feed === this && state.value.any { task -> task.id == it.taskId } }
        if (parent != null) return stage(parent.taskId, kind, geniId, block)
        val id = lastId.incrementAndGet()
        state.update { it + DownloadTask(id, treeId, kind, geniId, active = true) }
        return runTask(id, block)
    }

    private suspend fun <T> runTask(id: Long, block: suspend (DownloadProgress) -> T): T = try {
        withContext(Current(this, id)) {
            block(object : DownloadProgress {
                override fun total(count: Int?) = change(id) { it.copy(total = count) }
                override fun step() = change(id) { it.copy(done = it.done + 1) }
            })
        }
    } finally {
        drop(id)
    }

    private suspend fun <T> stage(taskId: Long, kind: DownloadKind, geniId: String?, block: suspend (DownloadProgress) -> T): T {
        val stageId = lastId.incrementAndGet()
        fun changeStage(update: (DownloadStage) -> DownloadStage) = change(taskId) { task ->
            task.copy(stages = task.stages.map { if (it.id == stageId) update(it) else it })
        }
        change(taskId) { it.copy(stages = it.stages + DownloadStage(stageId, kind, geniId)) }
        try {
            return block(object : DownloadProgress {
                override fun total(count: Int?) = changeStage { it.copy(total = count) }
                override fun step() = changeStage { it.copy(done = it.done + 1) }
            })
        } finally {
            change(taskId) { task -> task.copy(stages = task.stages.filter { it.id != stageId }) }
        }
    }

    private fun change(id: Long, update: (DownloadTask) -> DownloadTask) =
        state.update { list -> list.map { if (it.id == id) update(it) else it } }

    private fun drop(id: Long) = state.update { list -> list.filter { it.id != id } }
}
