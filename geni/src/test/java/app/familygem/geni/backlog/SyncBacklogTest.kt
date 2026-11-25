package app.familygem.geni.backlog

import app.familygem.geni.sync.ActiveJob
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
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncBacklogTest {

    private fun job(id: Long, kind: JobKind = JobKind.UPDATE_PROFILE, state: JobState = JobState.PENDING, version: Int = 0, error: String? = null, tree: Int = 1) =
        UploadJob(id = id, treeId = tree, key = "k$id", kind = kind, params = listOf("secret" to "payload"), label = "Person $id",
            localHash = 0, state = state, version = version, error = error)

    private fun build(
        jobs: List<UploadJob>, status: QueueStatus = QueueStatus(), waits: Map<Int, TreeWait> = emptyMap(),
        downloads: List<DownloadTask> = emptyList(), reason: (UploadJob) -> JobWait? = { null }
    ) = SyncBacklog.build(jobs, status, waits, downloads, reason)

    @Test
    fun jobsGoToTheirSectionsInQueueOrder() {
        val rows = build(listOf(
            job(1, state = JobState.FAILED), job(2), job(3, JobKind.ADD_PHOTO), job(4, state = JobState.CONFLICT),
            job(5, state = JobState.DISMISSED), job(6, JobKind.DELETE_PHOTO)
        ), QueueStatus(active = mapOf(1 to ActiveJob(3, 0))))
        assertEquals(listOf("job:3", "job:2", "job:6", "job:1", "job:4"), rows.map { it.key })
        assertEquals(listOf(BacklogSection.IN_PROGRESS, BacklogSection.WAITING, BacklogSection.WAITING,
            BacklogSection.NEEDS_ATTENTION, BacklogSection.NEEDS_ATTENTION), rows.map { it.section })
        assertEquals(BacklogOperation.ADD_PHOTO, rows[0].operation)
        assertEquals("Person 3", rows[0].target)
        assertEquals(listOf(BacklogStatus.Failed, BacklogStatus.Conflict), rows.drop(3).map { it.status })
    }

    @Test
    fun onlyTheVersionInFlightIsActive() {
        val rows = build(listOf(job(1, version = 2)), QueueStatus(active = mapOf(1 to ActiveJob(1, 1))))
        assertEquals(BacklogStatus.Queued, rows.single().status)
        assertEquals(BacklogStatus.Sending, build(listOf(job(1, version = 1)), QueueStatus(active = mapOf(1 to ActiveJob(1, 1)))).single().status)
        // Another tree's active job does not count
        assertEquals(BacklogStatus.Queued, build(listOf(job(1, tree = 2)), QueueStatus(active = mapOf(1 to ActiveJob(1, 0)))).single().status)
    }

    @Test
    fun sendingAddPlannedAgainIsStillTheAttemptInFlight() {
        val rows = build(listOf(job(1, JobKind.ADD_CHILD, JobState.SENDING, version = 3)), QueueStatus(active = mapOf(1 to ActiveJob(1, 2))))
        assertEquals(BacklogStatus.Sending, rows.single().status)
    }

    @Test
    fun sendingRowWithoutALiveAttemptShowsAsInterrupted() {
        val row = build(listOf(job(1, JobKind.ADD_PHOTO, JobState.SENDING))).single()
        assertEquals(BacklogSection.WAITING, row.section)
        assertEquals(BacklogStatus.Interrupted, row.status)
        val failed = build(listOf(job(1, JobKind.ADD_PHOTO, JobState.FAILED, error = INTERRUPTED_ADD))).single()
        assertEquals(BacklogStatus.FailedInterrupted, failed.status)
        assertEquals(BacklogSection.NEEDS_ATTENTION, failed.section)
    }

    @Test
    fun waitingReasonsComeFromTheQueueThenTheRunner() {
        val jobs = listOf(job(1, JobKind.ADD_PHOTO), job(2, JobKind.ADD_PHOTO), job(3, JobKind.ADD_PHOTO), job(4), job(5, tree = 2))
        val reasons = mapOf(1L to JobWait.CROP_HOLD, 2L to JobWait.PHOTOS_OFF, 3L to JobWait.VERIFYING)
        val rows = build(jobs, waits = mapOf(1 to TreeWait.NeedsLogin, 2 to TreeWait.Retry(60_000)), reason = { reasons[it.id] })
        assertEquals(listOf(BacklogStatus.CropHold, BacklogStatus.PhotosOff, BacklogStatus.Verifying, BacklogStatus.NeedsLogin,
            BacklogStatus.RetryAt(60_000)), rows.map { it.status })
    }

    @Test
    fun downloadsShowQueuedOrActiveWithTheirInnermostStage() {
        val stage = DownloadStage(9, DownloadKind.PHOTO, "profile-2")
        val rows = build(emptyList(), downloads = listOf(
            DownloadTask(1, 1, DownloadKind.KEEP_OFFLINE, "profile-1", active = true, done = 4,
                stages = listOf(DownloadStage(8, DownloadKind.ALBUM_DOWNLOAD, "profile-2", 1, 3), stage)),
            DownloadTask(2, 1, DownloadKind.KEEP_OFFLINE, "profile-3", active = false),
            DownloadTask(3, 1, DownloadKind.ALBUM_LIST, "profile-4", active = true)
        ))
        assertEquals(listOf("download:1", "download:3", "download:2"), rows.map { it.key })
        assertEquals(BacklogStatus.Downloading(stage), rows[0].status)
        assertEquals(4 to null, rows[0].done to rows[0].total)
        assertEquals(null to null, rows[1].done to rows[1].total) // Indeterminate
        assertEquals(BacklogStatus.QueuedDownload, rows[2].status)
        assertEquals(BacklogSection.WAITING, rows[2].section)
        assertEquals("profile-3", rows[2].targetGeniId)
    }

    @Test
    fun nothingToDoGivesNoRows() {
        assertEquals(emptyList<BacklogItem>(), build(listOf(job(1, state = JobState.DISMISSED))))
    }
}
