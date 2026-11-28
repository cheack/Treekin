package app.familygem.geni.backlog

import app.familygem.geni.sync.DownloadKind
import app.familygem.geni.sync.DownloadStage
import app.familygem.geni.sync.DownloadTask
import app.familygem.geni.sync.INTERRUPTED_ADD
import app.familygem.geni.sync.JobKind
import app.familygem.geni.sync.JobState
import app.familygem.geni.sync.JobWait
import app.familygem.geni.sync.QueueStatus
import app.familygem.geni.sync.TreeWait
import app.familygem.geni.sync.UploadJob

enum class BacklogSection { IN_PROGRESS, WAITING, NEEDS_ATTENTION }

/** What a backlog row does, as the user reads it. */
enum class BacklogOperation {
    UPDATE_PROFILE, UPDATE_RELATIONSHIP, ADD_PERSON, DELETE_PERSON, ADD_PHOTO, DELETE_PHOTO, UPDATE_PHOTO, TAG_PHOTO, UNTAG_PHOTO, SET_MAIN_PHOTO,
    PREFETCH, KEEP_OFFLINE, DAILY_REFRESH, PERSON_REFRESH, ROOT_LOAD, ALBUM_LIST, ALBUM_DOWNLOAD, PHOTO_DOWNLOAD;

    companion object {
        fun of(kind: JobKind) = when (kind) {
            JobKind.UPDATE_PROFILE -> UPDATE_PROFILE
            JobKind.UPDATE_UNION -> UPDATE_RELATIONSHIP
            JobKind.ADD_CHILD, JobKind.ADD_PARTNER -> ADD_PERSON
            JobKind.DELETE_PROFILE -> DELETE_PERSON
            JobKind.ADD_PHOTO -> ADD_PHOTO
            JobKind.DELETE_PHOTO -> DELETE_PHOTO
            JobKind.UPDATE_PHOTO -> UPDATE_PHOTO
            JobKind.TAG_PHOTO -> TAG_PHOTO
            JobKind.UNTAG_PHOTO -> UNTAG_PHOTO
            JobKind.SET_MAIN_PHOTO -> SET_MAIN_PHOTO
        }

        fun of(kind: DownloadKind) = when (kind) {
            DownloadKind.PREFETCH -> PREFETCH
            DownloadKind.KEEP_OFFLINE -> KEEP_OFFLINE
            DownloadKind.DAILY_REFRESH -> DAILY_REFRESH
            DownloadKind.PERSON_REFRESH -> PERSON_REFRESH
            DownloadKind.ROOT_LOAD -> ROOT_LOAD
            DownloadKind.ALBUM_LIST -> ALBUM_LIST
            DownloadKind.ALBUM_DOWNLOAD -> ALBUM_DOWNLOAD
            DownloadKind.PHOTO -> PHOTO_DOWNLOAD
        }
    }
}

/** The state of a backlog row; only reasons the user can act on or understand, never raw server text. */
sealed interface BacklogStatus {
    data object Sending : BacklogStatus
    /** @param stage The nested step running now, if any */
    data class Downloading(val stage: DownloadStage?) : BacklogStatus
    /** A download waiting for another download of the same tree. */
    data object QueuedDownload : BacklogStatus
    /** An upload waiting its turn, with no reason of its own. */
    data object Queued : BacklogStatus
    data object PhotosOff : BacklogStatus
    data object CropHold : BacklogStatus
    data object Verifying : BacklogStatus
    data object NeedsLogin : BacklogStatus
    data class RetryAt(val atMillis: Long) : BacklogStatus
    /** Stored as sending with no upload running: the queue's recovery is about to decide. */
    data object Interrupted : BacklogStatus
    data object Failed : BacklogStatus
    /** An add stopped while sending: it may exist on Geni already. */
    data object FailedInterrupted : BacklogStatus
    data object Conflict : BacklogStatus
}

/**
 * One row of the sync backlog.
 * @param target What the job shows to the user (a person's name, a photo), for uploads
 * @param targetGeniId The profile a download is about; the screen looks its name up
 * @param done Finished steps, null when the row counts none
 * @param total Number of steps, null when unknown (indeterminate progress)
 */
data class BacklogItem(
    val key: String,
    val section: BacklogSection,
    val treeId: Int,
    val operation: BacklogOperation,
    val target: String?,
    val targetGeniId: String?,
    val status: BacklogStatus,
    val done: Int? = null,
    val total: Int? = null
)

/** Builds the sync backlog from what is stored and what runs in memory now. */
object SyncBacklog {

    /**
     * @param jobs Every stored upload job, in upload order
     * @param waitReason The queue's own reason for holding a pending job back (see `SyncQueue.waitReason`)
     * @return Rows by section (in progress, waiting, needs attention), each in queue order; dismissed jobs left out
     */
    fun build(
        jobs: List<UploadJob>,
        queue: QueueStatus,
        waits: Map<Int, TreeWait>,
        downloads: List<DownloadTask>,
        waitReason: (UploadJob) -> JobWait?
    ): List<BacklogItem> {
        val items = mutableListOf<BacklogItem>()
        for (task in downloads) {
            val stage = task.stages.lastOrNull()
            items += BacklogItem(
                key = "download:${task.id}",
                section = if (task.active) BacklogSection.IN_PROGRESS else BacklogSection.WAITING,
                treeId = task.treeId,
                operation = BacklogOperation.of(task.kind),
                target = null,
                targetGeniId = task.geniId,
                status = if (task.active) BacklogStatus.Downloading(stage) else BacklogStatus.QueuedDownload,
                done = task.done.takeIf { task.active && (it > 0 || task.total != null) },
                total = task.total
            )
        }
        for (job in jobs) {
            val status = jobStatus(job, queue, waits, waitReason) ?: continue
            val section = when (status) {
                BacklogStatus.Sending -> BacklogSection.IN_PROGRESS
                BacklogStatus.Failed, BacklogStatus.FailedInterrupted, BacklogStatus.Conflict -> BacklogSection.NEEDS_ATTENTION
                else -> BacklogSection.WAITING
            }
            items += BacklogItem("job:${job.id}", section, job.treeId, BacklogOperation.of(job.kind), job.label, null, status)
        }
        // Stable: uploads keep queue order within a section, and downloads come before them
        return items.sortedBy { it.section.ordinal }
    }

    private fun jobStatus(job: UploadJob, queue: QueueStatus, waits: Map<Int, TreeWait>, waitReason: (UploadJob) -> JobWait?): BacklogStatus? {
        val active = queue.active[job.treeId]
        // Only the exact version in flight: a newer version planned during the upload waits for its turn.
        // An add planned again while it is sent keeps its SENDING row with a newer version: still the same attempt
        if (active != null && active.id == job.id && (active.version == job.version || job.state == JobState.SENDING)) return BacklogStatus.Sending
        return when (job.state) {
            JobState.DISMISSED -> null
            JobState.FAILED -> if (job.error == INTERRUPTED_ADD) BacklogStatus.FailedInterrupted else BacklogStatus.Failed
            JobState.CONFLICT -> BacklogStatus.Conflict
            JobState.SENDING -> BacklogStatus.Interrupted
            JobState.PENDING -> when (waitReason(job)) {
                JobWait.PHOTOS_OFF -> BacklogStatus.PhotosOff
                JobWait.CROP_HOLD -> BacklogStatus.CropHold
                JobWait.VERIFYING -> BacklogStatus.Verifying
                null -> when (val wait = waits[job.treeId]) {
                    TreeWait.NeedsLogin -> BacklogStatus.NeedsLogin
                    is TreeWait.Retry -> BacklogStatus.RetryAt(wait.atMillis)
                    null -> BacklogStatus.Queued
                }
            }
        }
    }
}
