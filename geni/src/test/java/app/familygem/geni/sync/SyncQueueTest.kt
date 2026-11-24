package app.familygem.geni.sync

import app.familygem.geni.FakeTreeHost
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.RateLimiter
import app.familygem.geni.auth.InMemoryTokenStore
import app.familygem.geni.map.ProfileFields
import com.google.gson.JsonParser
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import app.familygem.geni.map.UnionFields
import org.junit.Test

class SyncQueueTest {

    private val ivanJson = """{"id":"profile-1","first_name":"Ivan","last_name":"User","occupation":"Engineer","is_alive":true}"""
    private val annaJson = """{"id":"profile-2","first_name":"Anna","last_name":"User","is_alive":true}"""
    private val ivan = ProfileFields.ofSnapshot(ivanJson)!!
    private val anna = ProfileFields.ofSnapshot(annaJson)!!
    private var onDeleteJob: suspend (Long) -> Unit = {}
    private val store = object : InMemorySyncStore() {
        override suspend fun deleteJob(id: Long) {
            onDeleteJob(id)
            super.deleteJob(id)
        }
    }

    /** Uploader that answers from a script instead of Geni. */
    private inner class ScriptedUploader : Uploader(
        GeniClient("http://localhost/api/".toHttpUrl(), InMemoryTokenStore(), RateLimiter { 0L }), store, FakeTreeHost()
    ) {
        val ran = mutableListOf<UploadJob>()
        /** Like Geni: an update changes the profile, and the uploader stores the new version as the snapshot. */
        val geniApplies: suspend (UploadJob) -> Outcome = { job ->
            if (job.kind == JobKind.UPDATE_PROFILE) {
                val link = store.link(1, job.geniId!!)!!
                val json = JsonParser.parseString(link.snapshot).asJsonObject
                job.params.forEach { (key, value) -> json.addProperty(key, value) }
                store.saveLinks(listOf(link.copy(snapshot = json.toString(), verifiedHash = null)))
            }
            Outcome.Done
        }
        var answer: suspend (UploadJob) -> Outcome = geniApplies
        override suspend fun run(job: UploadJob): Outcome {
            ran += job
            return answer(job)
        }
    }

    private val uploader = ScriptedUploader()
    private var photosOn = true
    private val queue = SyncQueue(store, uploader, { photosOn })

    private suspend fun linkBoth() = store.saveLinks(listOf(
        GeniLink(1, "profile-1", "I1", true, null, ivanJson, 0),
        GeniLink(1, "profile-2", "I2", true, null, annaJson, 0)
    ))

    private fun tree(ivanFields: ProfileFields = ivan, annaFields: ProfileFields = anna) = LocalTree(
        listOf(LocalPerson("I1", "profile-1", "Ivan User", ivanFields), LocalPerson("I2", "profile-2", "Anna User", annaFields)),
        emptyList()
    )

    private val driver = ivan.copy(occupation = "Driver")
    private val pilot = ivan.copy(occupation = "Pilot")

    private fun treeWithPhoto() = tree().let { it.copy(people = listOf(it.people[0].copy(media = listOf(LocalMedia("me.jpg", "Me", null))), it.people[1])) }

    private val me = LocalMedia("me.jpg", "Me", null)
    private fun photoJob(person: String = "I1", geniId: String = "profile-1", state: JobState = JobState.PENDING) = UploadJob(treeId = 1,
        key = "photo:$person:me.jpg", kind = JobKind.ADD_PHOTO, geniId = geniId, personGedcomId = person, params = listOf("file" to "me.jpg"),
        label = "Photo", localHash = 0, state = state)
    private val updateJob = UploadJob(treeId = 1, key = "profile:profile-2", kind = JobKind.UPDATE_PROFILE, geniId = "profile-2",
        params = listOf("occupation" to "Driver"), label = "x", localHash = 1)

    @Test
    fun ambiguousSaveDropsPendingPhotoAndResolvedSaveSendsItToTheResolvedProfile() = runTest {
        linkBoth()
        val both = treeWithPhoto().let { it.copy(people = listOf(it.people[0], it.people[1].copy(media = listOf(me)))) }
        queue.onSaved(1, both)
        assertEquals(setOf("photo:I1:me.jpg", "photo:I2:me.jpg"), store.jobs(1).map { it.key }.toSet())
        // I1 lost its tag and has another name
        val lost = LocalPerson("I1", null, "New User", ProfileFields.EMPTY.copy(given = "New", surname = "User"), listOf(me))
        queue.onSaved(1, both.copy(people = listOf(lost, both.people[1]), stamp = both.stamp + 1))
        assertEquals(listOf("photo:I2:me.jpg"), store.jobs(1).map { it.key }) // The same file name of another person stays
        queue.onSaved(1, both.copy(stamp = both.stamp + 2))
        val photo = store.jobs(1).single { it.key == "photo:I1:me.jpg" }
        assertEquals("profile-1", photo.geniId)
        queue.runPending(1)
        assertEquals(1, uploader.ran.count { it.key == "photo:I1:me.jpg" && it.geniId == "profile-1" })
    }

    @Test
    fun deferredPhotoStaysPendingAndOthersRun() = runTest {
        linkBoth()
        store.saveJob(photoJob()) // Persisted by an older build: no save planned it
        store.saveJob(updateJob)
        uploader.answer = { if (it.kind == JobKind.ADD_PHOTO) Outcome.Deferred else uploader.geniApplies(it) }
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(1, uploader.ran.count { it.kind == JobKind.ADD_PHOTO })
        assertEquals(1, uploader.ran.count { it.kind == JobKind.UPDATE_PROFILE })
        assertEquals(listOf("photo:I1:me.jpg" to JobState.PENDING), store.jobs(1).map { it.key to it.state })
    }

    @Test
    fun retriedFailedPhotoThatCannotBeVerifiedGoesBackToPending() = runTest {
        linkBoth()
        val id = store.saveJob(photoJob(state = JobState.FAILED))
        uploader.answer = { Outcome.Deferred }
        queue.retryFailed(setOf(id))
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(1, uploader.ran.size)
        assertEquals(JobState.PENDING, store.jobs(1).single().state)
    }

    @Test
    fun photoJobIsPlannedOnlyWhileTheSettingIsOn() = runTest {
        linkBoth()
        photosOn = false
        queue.onSaved(1, treeWithPhoto())
        assertEquals(emptyList<UploadJob>(), store.jobs(1))
        photosOn = true
        queue.replan(1)
        assertEquals(listOf("photo:I1:me.jpg"), store.jobs(1).map { it.key })
    }

    @Test
    fun heldPhotoIsNotSentAndDoesNotBlockOtherJobs() = runTest {
        linkBoth()
        val session = queue.holds.acquire(1, "me.jpg")
        queue.onSaved(1, treeWithPhoto().let { it.copy(people = listOf(it.people[0].copy(fields = driver), it.people[1])) })
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(listOf(JobKind.UPDATE_PROFILE), uploader.ran.map { it.kind })
        assertEquals(JobState.PENDING, store.jobs(1).single { it.kind == JobKind.ADD_PHOTO }.state)
        assertEquals(1, queue.holds.release(session))
        queue.runPending(1)
        queue.runPending(1)
        assertEquals(1, uploader.ran.count { it.kind == JobKind.ADD_PHOTO })
        assertNull(queue.holds.release(session)) // Idempotent
    }

    @Test
    fun holdOfOneTreeDoesNotHoldTheSameFileNameOfAnother() = runTest {
        queue.holds.acquire(1, "me.jpg")
        store.saveJob(photoJob())
        store.saveJob(photoJob().copy(treeId = 2))
        queue.runPending(2)
        assertEquals(listOf(2), uploader.ran.map { it.treeId })
        queue.runPending(1)
        assertEquals(listOf(2), uploader.ran.map { it.treeId })
    }

    @Test
    fun releasedPhotoStillWaitsWhileTheSettingIsOff() = runTest {
        linkBoth()
        photosOn = false
        val session = queue.holds.acquire(1, "me.jpg")
        store.saveJob(photoJob())
        queue.holds.release(session)
        queue.runPending(1)
        assertEquals(emptyList<UploadJob>(), uploader.ran)
        assertEquals(JobState.PENDING, store.jobs(1).single().state)
    }

    @Test
    fun commitRunsOnlyWhileHeld() {
        val holds = PhotoHolds()
        val session = holds.acquire(1, "a.jpg")
        assertEquals(CommitResult(true, false, 1), holds.commit(session) { false })
        assertTrue(holds.isHeld(1, "a.jpg"))
        assertEquals(CommitResult(true, true, 1), holds.commit(session) { true })
        assertTrue(!holds.isHeld(1, "a.jpg"))
        var ran = false
        assertEquals(CommitResult(false, false, null), holds.commit(session) { ran = true; true })
        assertTrue(!ran)
    }

    @Test
    fun failedPhotoJobDoesNotStopLaterJobs() = runTest {
        linkBoth()
        queue.onSaved(1, treeWithPhoto().let { it.copy(people = listOf(it.people[0].copy(fields = driver), it.people[1])) })
        assertEquals(2, store.jobs(1).size)
        uploader.answer = { job -> if (job.kind == JobKind.ADD_PHOTO) Outcome.Failed("cannot read the photo: x") else uploader.geniApplies(job) }
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(listOf(JobKind.ADD_PHOTO, JobKind.UPDATE_PROFILE), uploader.ran.map { it.kind })
        assertEquals(JobState.FAILED, store.jobs(1).single { it.kind == JobKind.ADD_PHOTO }.state)
    }

    @Test
    fun photoJobIsNotRunWhileTheSettingIsOff() = runTest {
        linkBoth()
        queue.onSaved(1, treeWithPhoto())
        photosOn = false
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(emptyList<UploadJob>(), uploader.ran)
        assertEquals(JobState.PENDING, store.jobs(1).single().state)
        photosOn = true
        queue.runPending(1)
        assertEquals(listOf(JobKind.ADD_PHOTO), uploader.ran.map { it.kind })
    }

    @Test
    fun replanWithoutSavedTreeDoesNothing() = runTest {
        queue.replan(1)
        assertEquals(emptyList<UploadJob>(), store.jobs(1))
    }

    @Test
    fun samePlanTwiceKeepsOneJob() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver))
        queue.onSaved(1, tree(driver))
        val job = store.jobs(1).single()
        assertEquals(listOf("occupation" to "Driver"), job.params)
        assertEquals(0, job.version)
    }

    @Test
    fun unchangedTreeVerifiesLinks() = runTest {
        linkBoth()
        queue.onSaved(1, tree())
        assertEquals(ivan.hashCode(), store.link(1, "profile-1")!!.verifiedHash)
        assertEquals(emptyList<UploadJob>(), store.jobs(1))
    }

    @Test
    fun verifiedHashIsNotWrittenOverANewerSnapshot() = runTest {
        linkBoth()
        val plan = UploadPlanner.plan(1, tree(), store.links(1), emptyList())
        // A load replaces the snapshot after planning read the links
        store.saveLinks(listOf(store.link(1, "profile-1")!!.copy(snapshot = """{"id":"profile-1","first_name":"Ivan2"}""")))
        queue.saveVerified(plan.verified)
        assertNull(store.link(1, "profile-1")!!.verifiedHash)
        assertEquals(anna.hashCode(), store.link(1, "profile-2")!!.verifiedHash)
    }

    @Test
    fun changedParamsResetFailedJob() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver))
        val failed = store.jobs(1).single().copy(state = JobState.FAILED, error = "Access Denied")
        store.saveJob(failed)
        queue.onSaved(1, tree(pilot))
        val job = store.jobs(1).single()
        assertEquals(failed.id, job.id)
        assertEquals(JobState.PENDING, job.state)
        assertNull(job.error)
        assertEquals(1, job.version)
        assertEquals(listOf("occupation" to "Pilot"), job.params)
    }

    @Test
    fun dismissedJobStaysDismissedForSameParams() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver))
        val failed = store.jobs(1).single().copy(state = JobState.FAILED, error = "x").also { store.saveJob(it) }
        queue.dismissFailed(setOf(failed.id))
        queue.onSaved(1, tree(driver))
        assertEquals(JobState.DISMISSED, store.jobs(1).single().state)
        queue.onSaved(1, tree(pilot))
        assertEquals(JobState.PENDING, store.jobs(1).single().state)
    }

    @Test
    fun retryFailedMakesJobsPending() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver))
        val failed = store.jobs(1).single().copy(state = JobState.FAILED, error = "x").also { store.saveJob(it) }
        queue.retryFailed(setOf(failed.id))
        assertEquals(JobState.PENDING, store.jobs(1).single().state)
        assertNull(store.jobs(1).single().error)
    }

    @Test
    fun retryAndDismissTouchOnlyTheShownJobs() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver, anna.copy(occupation = "Nurse")))
        val (shown, later) = store.jobs(1).map { it.copy(state = JobState.FAILED, error = "x").also { job -> store.saveJob(job) } }
        // The user saw only the first failure; the second failed while the dialog was open
        queue.retryFailed(setOf(shown.id))
        assertEquals(JobState.PENDING, store.jobs(1).single { it.id == shown.id }.state)
        assertEquals(JobState.FAILED, store.jobs(1).single { it.id == later.id }.state)
        queue.dismissFailed(setOf(shown.id))
        assertEquals(JobState.FAILED, store.jobs(1).single { it.id == later.id }.state)
    }

    @Test
    fun runsJobsInOrderAndDeletesDone() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver, anna.copy(occupation = "Doctor")))
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(listOf("profile:profile-1", "profile:profile-2"), uploader.ran.map { it.key })
        assertEquals(emptyList<UploadJob>(), store.jobs(1))
    }

    @Test
    fun editDuringUploadKeepsNewerVersion() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver))
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        uploader.answer = { job ->
            if (uploader.ran.size == 1) {
                started.complete(Unit)
                release.await()
            }
            uploader.geniApplies(job)
        }
        val run = async { queue.runPending(1) }
        started.await()
        queue.onSaved(1, tree(pilot)) // The user edits again while the first version uploads
        release.complete(Unit)
        assertEquals(RunResult.EMPTY, run.await())
        assertEquals(listOf(listOf("occupation" to "Driver"), listOf("occupation" to "Pilot")), uploader.ran.map { it.params })
        assertEquals(emptyList<UploadJob>(), store.jobs(1))
    }

    @Test
    fun transientErrorKeepsJobPending() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver))
        uploader.answer = { Outcome.Retry("HTTP 503") }
        assertEquals(RunResult.RETRY_LATER, queue.runPending(1))
        assertEquals(JobState.PENDING, store.jobs(1).single().state)
    }

    @Test
    fun authErrorStopsTheQueue() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver, anna.copy(occupation = "Doctor")))
        uploader.answer = { Outcome.NeedsLogin }
        assertEquals(RunResult.NEEDS_LOGIN, queue.runPending(1))
        assertEquals(1, uploader.ran.size)
        assertEquals(2, store.jobs(1).count { it.state == JobState.PENDING })
    }

    @Test
    fun failedJobIsKeptAndSkipped() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver, anna.copy(occupation = "Doctor")))
        uploader.answer = { job -> if (job.key == "profile:profile-1") Outcome.Failed("Access Denied") else uploader.geniApplies(job) }
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        val job = store.jobs(1).single()
        assertEquals("profile:profile-1", job.key)
        assertEquals(JobState.FAILED, job.state)
        assertEquals("Access Denied", job.error)
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(2, uploader.ran.size)
    }

    private fun addJob(key: String, person: String, kind: JobKind, anchors: List<String>, state: JobState = JobState.PENDING) =
        UploadJob(treeId = 1, key = key, kind = kind, personGedcomId = person, familyGedcomId = "F3", anchors = anchors,
            params = listOf("first_name" to person), label = person, localHash = 0, state = state)

    @Test
    fun interruptedAddBecomesFailed() = runTest {
        store.saveJob(addJob("add:I6", "I6", JobKind.ADD_PARTNER, listOf("partner:I1"), JobState.SENDING))
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        val job = store.jobs(1).single()
        assertEquals(JobState.FAILED, job.state)
        assertTrue(job.error!!.contains("check on Geni"))
        assertEquals(emptyList<UploadJob>(), uploader.ran)
    }

    @Test
    fun addIsSendingWhileRunning() = runTest {
        store.saveJob(addJob("add:I6", "I6", JobKind.ADD_PARTNER, listOf("partner:I1")))
        var stateDuringRun: JobState? = null
        uploader.answer = { stateDuringRun = store.jobs(1).single().state; Outcome.Done }
        queue.runPending(1)
        assertEquals(JobState.SENDING, stateDuringRun)
    }

    @Test
    fun needsLoginPutsAddBackToPending() = runTest {
        store.saveJob(addJob("add:I6", "I6", JobKind.ADD_PARTNER, listOf("partner:I1")))
        uploader.answer = { Outcome.NeedsLogin }
        assertEquals(RunResult.NEEDS_LOGIN, queue.runPending(1))
        assertEquals(JobState.PENDING, store.jobs(1).single().state)
    }

    @Test
    fun laterSpouseRunsBeforeEarlierChild() = runTest {
        store.saveJob(addJob("add:I7", "I7", JobKind.ADD_CHILD, listOf("partner:I1", "partner:I6")))
        store.saveJob(addJob("add:I6", "I6", JobKind.ADD_PARTNER, listOf("child:I7", "partner:I1")))
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(listOf("add:I6", "add:I7"), uploader.ran.map { it.key })
    }

    @Test
    fun changedTargetUpdatesJob() = runTest {
        linkBoth()
        store.saveLinks(listOf(GeniLink(1, "union-1", "F1", true, null, "{\"id\":\"union-1\"}", 0)))
        val kid = LocalPerson("I3", null, "Kid", ProfileFields.EMPTY.copy(given = "Kid"))
        fun withKid(families: List<LocalFamily>) = tree().let { it.copy(people = it.people + kid, families = families) }
        queue.onSaved(1, withKid(listOf(LocalFamily("F1", "union-1", listOf("I1", "I2"), listOf("I3"), UnionFields.EMPTY))))
        queue.onSaved(1, withKid(listOf(LocalFamily("F1", "union-1", listOf("I1", "I2"), emptyList(), UnionFields.EMPTY),
            LocalFamily("F2", null, listOf("I3"), listOf("I1"), UnionFields.EMPTY))))
        val job = store.jobs(1).single()
        assertEquals(JobKind.ADD_PARTNER, job.kind)
        assertEquals("F2", job.familyGedcomId)
        assertEquals(1, job.version)
    }

    @Test
    fun revertDuringUploadIsPlannedAgain() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver))
        uploader.answer = { job ->
            if (uploader.ran.size == 1) queue.onSaved(1, tree(ivan)) // The user reverts to Engineer while Driver uploads
            uploader.geniApplies(job)
        }
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(listOf(listOf("occupation" to "Driver"), listOf("occupation" to "Engineer")), uploader.ran.map { it.params })
    }

    @Test
    fun valueGeniStoresDifferentlyFailsInsteadOfLooping() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver))
        uploader.answer = { Outcome.Done } // Geni keeps Engineer: the snapshot never matches
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(1, uploader.ran.size)
        val job = store.jobs(1).single()
        assertEquals(JobState.FAILED, job.state)
        assertTrue(job.error!!.contains("different value"))
    }

    /** Ivan on Geni, and a new spouse I6 in family F3. */
    private fun withSpouse(name: String) = tree().let {
        it.copy(people = it.people + LocalPerson("I6", null, name, ProfileFields.EMPTY.copy(given = name)),
            families = listOf(LocalFamily("F3", null, listOf("I1", "I6"), emptyList(), UnionFields.EMPTY)))
    }

    @Test
    fun editedSendingAddThatFailsIsNotRunAgain() = runTest {
        linkBoth()
        queue.onSaved(1, withSpouse("Olga"))
        uploader.answer = {
            queue.onSaved(1, withSpouse("Olya")) // The user renames the new spouse while the add is sent
            Outcome.Failed("connection lost while adding; check on Geni")
        }
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(1, uploader.ran.size)
        val job = store.jobs(1).single()
        assertEquals(JobState.FAILED, job.state)
        assertEquals("Olya", job.params.toMap()["first_name"])
    }

    @Test
    fun failedAddKeepsStateWhenEdited() = runTest {
        linkBoth()
        queue.onSaved(1, withSpouse("Olga"))
        store.saveJob(store.jobs(1).single().copy(state = JobState.FAILED, error = "check on Geni"))
        queue.onSaved(1, withSpouse("Olya"))
        val job = store.jobs(1).single()
        assertEquals(JobState.FAILED, job.state)
        assertEquals("Olya", job.params.toMap()["first_name"])
        assertEquals(1, job.version)
    }

    @Test
    fun doneAddWithNewerVersionIsRemoved() = runTest {
        linkBoth()
        queue.onSaved(1, withSpouse("Olga"))
        uploader.answer = {
            queue.onSaved(1, withSpouse("Olya"))
            store.saveLinks(listOf(GeniLink(1, "profile-60", "I6", false, null, null, 0)))
            Outcome.Done
        }
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(1, uploader.ran.size)
        assertEquals(emptyList<UploadJob>(), store.jobs(1))
    }

    @Test
    fun absentRecordOrphansItsLink() = runTest {
        linkBoth()
        queue.onSaved(1, tree().let { it.copy(people = it.people.take(1)) })
        assertTrue(store.link(1, "profile-2")!!.orphaned)
        queue.onSaved(1, tree())
        assertFalse(store.link(1, "profile-2")!!.orphaned)
    }

    @Test
    fun editDuringAddIsSentAsUpdate() = runTest {
        linkBoth()
        queue.onSaved(1, withSpouse("Olga"))
        uploader.answer = {
            queue.onSaved(1, withSpouse("Olya")) // Renamed while the add is sent; the saved tree has no tag yet
            store.saveLinks(listOf(GeniLink(1, "profile-60", "I6", false, null, """{"id":"profile-60","first_name":"Olga","is_alive":true}""", 0)))
            uploader.answer = uploader.geniApplies
            Outcome.Done
        }
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(listOf(JobKind.ADD_PARTNER, JobKind.UPDATE_PROFILE), uploader.ran.map { it.kind })
        assertEquals("Olya", uploader.ran[1].params.toMap()["first_name"])
        assertEquals("profile-60", uploader.ran[1].geniId)
    }

    @Test
    fun editDuringAddWithoutSnapshotWaitsForSnapshot() = runTest {
        linkBoth()
        queue.onSaved(1, withSpouse("Olga"))
        uploader.answer = { add ->
            queue.onSaved(1, withSpouse("Olya"))
            store.saveLinks(listOf(GeniLink(1, "profile-60", "I6", false, null, null, 0, verifiedHash = add.localHash)))
            uploader.answer = { Outcome.Retry("offline") }
            Outcome.Done
        }
        assertEquals(RunResult.RETRY_LATER, queue.runPending(1))
        // Saved again: the add tagged the live tree
        queue.onSaved(1, withSpouse("Olya").let { t -> t.copy(people = t.people.map { if (it.gedcomId == "I6") it.copy(geniId = "profile-60") else it }) })
        val waiting = store.jobs(1).single()
        assertEquals("profile:profile-60", waiting.key)
        assertEquals(emptyList<Pair<String, String>>(), waiting.params)
        // Back online: the snapshot comes first, then only the changed name is sent
        uploader.answer = { fetch ->
            store.updateSnapshot(1, fetch.geniId!!, null, """{"id":"profile-60","first_name":"Olga","is_alive":true}""", null)
            uploader.answer = uploader.geniApplies
            Outcome.Done
        }
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        val update = uploader.ran.last()
        assertEquals("Olya", update.params.toMap()["first_name"])
        assertFalse("is_alive" in update.params.toMap())
    }

    @Test
    fun updateIsQueuedBeforeTheAddIsDeleted() = runTest {
        linkBoth()
        queue.onSaved(1, withSpouse("Olga"))
        val addId = store.jobs(1).single().id
        var jobsAtDelete: List<String>? = null
        onDeleteJob = { if (it == addId && jobsAtDelete == null) jobsAtDelete = store.jobs(1).map { job -> job.key } }
        uploader.answer = {
            queue.onSaved(1, withSpouse("Olya"))
            store.saveLinks(listOf(GeniLink(1, "profile-60", "I6", false, null, """{"id":"profile-60","first_name":"Olga","is_alive":true}""", 0)))
            uploader.answer = { Outcome.Retry("offline") }
            Outcome.Done
        }
        queue.runPending(1)
        // Stopped right after this delete, the app still has the update; before it, the add is found linked and not sent again
        assertTrue("profile:profile-60" in jobsAtDelete!!)
    }

    @Test
    fun editOfDismissedAddKeepsItHidden() = runTest {
        linkBoth()
        queue.onSaved(1, withSpouse("Olga"))
        store.saveJob(store.jobs(1).single().copy(state = JobState.DISMISSED))
        queue.onSaved(1, withSpouse("Olya"))
        assertEquals(JobState.DISMISSED, store.jobs(1).single().state)
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(emptyList<UploadJob>(), uploader.ran)
    }

    @Test
    fun conflictJobIsKeptUntilChangedAgain() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver))
        uploader.answer = { Outcome.Conflict(listOf("OCCUPATION")) }
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        val job = store.jobs(1).single()
        assertEquals(JobState.CONFLICT, job.state)
        assertTrue(job.error!!.contains("occupation"))
        queue.onSaved(1, tree(driver))
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(1, uploader.ran.size) // A conflict never runs by itself
        queue.onSaved(1, tree(pilot))
        assertEquals(JobState.PENDING, store.jobs(1).single().state)
    }

    @Test
    fun queuePlansWithTreeReportedByUploader() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver))
        uploader.answer = { job ->
            uploader.geniApplies(job)
            // The upload merged a change into the tree: the next plan must see it, not the tree saved before
            queue.setLocal(1, tree(driver, anna.copy(occupation = "Nurse")))
            uploader.answer = { Outcome.Retry("offline") }
            Outcome.Done
        }
        queue.runPending(1)
        assertEquals("profile:profile-2", uploader.ran.last().key)
    }

    @Test
    fun olderCaptureNeverReplacesNewerOne() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver).copy(stamp = 5))
        // Saved earlier but planned later (captures are planned in background), and an older tree from the uploader
        queue.onSaved(1, tree(pilot).copy(stamp = 3))
        queue.setLocal(1, tree(pilot).copy(stamp = 4))
        assertEquals(listOf("occupation" to "Driver"), store.jobs(1).single().params)
        uploader.answer = { Outcome.Retry("offline") }
        queue.runPending(1)
        assertEquals(listOf("occupation" to "Driver"), uploader.ran.single().params)
    }

    @Test
    fun supersededJobRunsAgainWithNewParams() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver))
        uploader.answer = {
            queue.onSaved(1, tree(pilot)) // Saved during the check: the uploader posts nothing
            uploader.answer = uploader.geniApplies
            Outcome.Superseded
        }
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(listOf("occupation" to "Pilot"), uploader.ran.last().params)
    }

    @Test
    fun treeReportedByAFailedUploadIsPlanned() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver).copy(stamp = 1))
        uploader.answer = {
            // The uploader's tree already holds Anna's edit, saved but not planned yet
            queue.setLocal(1, tree(driver, anna.copy(occupation = "Nurse")).copy(stamp = 5))
            uploader.answer = { Outcome.Retry("offline") }
            Outcome.Failed("Access Denied")
        }
        queue.runPending(1)
        assertTrue(store.jobs(1).any { it.key == "profile:profile-2" })
    }

    @Test
    fun supersededJobIsPlannedAgainstTheNewSnapshot() = runTest {
        linkBoth()
        queue.onSaved(1, tree(driver))
        val withBirth = driver.copy(birth = app.familygem.geni.map.EventFields("1960", null))
        uploader.answer = {
            // The check merged Geni's birth into the tree; a save planned it against the old snapshot, then the snapshot moved
            queue.onSaved(1, tree(withBirth))
            store.saveLinks(listOf(store.link(1, "profile-1")!!.copy(snapshot = ivanJson.replace("}", ""","birth":{"date":{"year":1960}}}"""))))
            uploader.answer = uploader.geniApplies
            Outcome.Superseded
        }
        queue.runPending(1)
        assertEquals(listOf("occupation" to "Driver"), uploader.ran.last().params)
    }

    @Test
    fun enqueueDeleteStoresOneJobPerProfileAndRunsIt() = runTest {
        linkBoth()
        queue.enqueueDelete(1, "profile-2", "Anna User", "profile-1")
        queue.enqueueDelete(1, "profile-2", "Anna User", "profile-1")
        val job = store.jobs(1).single()
        assertEquals("delete:profile-2", job.key)
        assertEquals(JobKind.DELETE_PROFILE, job.kind)
        assertEquals("Anna User", job.label)
        queue.runPending(1)
        assertEquals(listOf("delete:profile-2"), uploader.ran.map { it.key })
        assertTrue(store.jobs(1).isEmpty())
    }

    @Test
    fun enqueueDeleteStoresTheConfirmingAccountAndKeepsItOnRetry() = runTest {
        linkBoth()
        assertEquals(DeleteQueued.QUEUED, queue.enqueueDelete(1, "profile-2", "Anna User", "profile-1"))
        assertEquals("profile-1", store.jobs(1).single().confirmedByProfileId)
        store.saveJob(store.jobs(1).single().copy(state = JobState.FAILED, error = "wrong account"))
        queue.retryFailed(store.jobs(1).map { it.id }.toSet())
        val retried = store.jobs(1).single()
        assertEquals(JobState.PENDING, retried.state)
        assertEquals("profile-1", retried.confirmedByProfileId)
    }

    @Test
    fun anotherAccountDoesNotReplaceAnOutstandingConfirmation() = runTest {
        linkBoth()
        queue.enqueueDelete(1, "profile-2", "Anna User", "profile-1")
        val failed = store.jobs(1).single().copy(state = JobState.FAILED, error = "wrong account")
        store.saveJob(failed)
        assertEquals(DeleteQueued.OTHER_ACCOUNT, queue.enqueueDelete(1, "profile-2", "Anna User", "profile-9"))
        assertEquals(failed, store.jobs(1).single())
        // Dismissing it is the user's choice to drop the old confirmation
        store.saveJob(failed.copy(state = JobState.DISMISSED))
        assertEquals(DeleteQueued.QUEUED, queue.enqueueDelete(1, "profile-2", "Anna User", "profile-9"))
        assertEquals("profile-9", store.jobs(1).single().confirmedByProfileId)
        assertEquals(JobState.PENDING, store.jobs(1).single().state)
    }

    @Test
    fun legacyDeleteGetsAnAccountOnlyFromAFreshConfirmation() = runTest {
        linkBoth()
        store.saveJob(UploadJob(treeId = 1, key = "delete:profile-2", kind = JobKind.DELETE_PROFILE, geniId = "profile-2",
            params = emptyList(), label = "Anna User", localHash = 0, state = JobState.FAILED, error = "not confirmed"))
        queue.retryFailed(store.jobs(1).map { it.id }.toSet())
        assertNull(store.jobs(1).single().confirmedByProfileId) // A plain retry confirms nothing
        assertEquals(DeleteQueued.QUEUED, queue.enqueueDelete(1, "profile-2", "Anna User", "profile-1"))
        val job = store.jobs(1).single()
        assertEquals("profile-1", job.confirmedByProfileId)
        assertEquals(JobState.PENDING, job.state)
    }

    @Test
    fun accountMismatchFailsTheDeleteAndLaterJobsProceed() = runTest {
        linkBoth()
        queue.enqueueDelete(1, "profile-2", "Anna User", "profile-1")
        store.saveJob(UploadJob(treeId = 1, key = "profile:profile-1", kind = JobKind.UPDATE_PROFILE, geniId = "profile-1",
            params = listOf("occupation" to "Pilot"), label = "Ivan User", localHash = 1))
        uploader.answer = { job -> if (job.kind == JobKind.DELETE_PROFILE) Outcome.Failed("not deleted: another Geni account") else Outcome.Done }
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        val failed = store.jobs(1).single()
        assertEquals("delete:profile-2", failed.key)
        assertEquals(JobState.FAILED, failed.state)
        assertEquals("profile-1", failed.confirmedByProfileId)
        assertEquals(listOf("delete:profile-2", "profile:profile-1"), uploader.ran.map { it.key })
    }

    @Test
    fun enqueueDeleteDropsAWaitingUpdateOfTheSameProfile() = runTest {
        linkBoth()
        store.saveJob(UploadJob(treeId = 1, key = "profile:profile-2", kind = JobKind.UPDATE_PROFILE, geniId = "profile-2",
            params = listOf("occupation" to "Pilot"), label = "Anna User", localHash = 1))
        queue.enqueueDelete(1, "profile-2", "Anna User", "profile-1")
        assertEquals(listOf("delete:profile-2"), store.jobs(1).map { it.key })
    }

    @Test
    fun enqueueDeleteAgainPutsAFailedDeleteBackInTheQueue() = runTest {
        linkBoth()
        queue.enqueueDelete(1, "profile-2", "Anna User", "profile-1")
        store.saveJob(store.jobs(1).single().copy(state = JobState.FAILED, error = "Access denied"))
        queue.enqueueDelete(1, "profile-2", "Anna User", "profile-1")
        val job = store.jobs(1).single()
        assertEquals(JobState.PENDING, job.state)
        assertNull(job.error)
    }

    @Test
    fun enqueueDeletePhotoQueuesOnceAndRerequestFailed() = runTest {
        linkBoth()
        queue.enqueueDeletePhoto(1, "photo-5", "I1", "Me")
        queue.enqueueDeletePhoto(1, "photo-5", "I1", "Me")
        val job = store.jobs(1).single()
        assertEquals("delphoto:photo-5", job.key)
        assertEquals(JobKind.DELETE_PHOTO, job.kind)
        assertEquals("photo-5", job.geniId)
        store.saveJob(job.copy(state = JobState.FAILED, error = "Access denied"))
        queue.enqueueDeletePhoto(1, "photo-5", "I1", "Me")
        val again = store.jobs(1).single()
        assertEquals(JobState.PENDING, again.state)
        assertNull(again.error)
        queue.runPending(1)
        assertEquals(listOf("delphoto:photo-5"), uploader.ran.map { it.key })
        assertTrue(store.jobs(1).isEmpty())
    }

    private fun photoJob(state: JobState = JobState.PENDING) = UploadJob(treeId = 1, key = "photo:I1:me.jpg", kind = JobKind.ADD_PHOTO, geniId = "profile-1",
        personGedcomId = "I1", params = listOf("file" to "me.jpg"), label = "Ivan User", localHash = 0, state = state)

    @Test
    fun interruptedPhotoUploadBecomesFailed() = runTest {
        store.saveJob(photoJob(JobState.SENDING))
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        val job = store.jobs(1).single()
        assertEquals(JobState.FAILED, job.state)
        assertTrue(job.error!!.contains("check on Geni"))
        assertEquals(emptyList<UploadJob>(), uploader.ran)
    }

    @Test
    fun donePhotoJobIsDeleted() = runTest {
        linkBoth()
        queue.onSaved(1, tree().let { t -> t.copy(people = t.people.map { if (it.gedcomId == "I1") it.copy(media = listOf(LocalMedia("me.jpg", null, null))) else it }) })
        assertEquals("photo:I1:me.jpg", store.jobs(1).single().key)
        var stateDuringRun: JobState? = null
        uploader.answer = { stateDuringRun = store.jobs(1).single().state; Outcome.Done }
        assertEquals(RunResult.EMPTY, queue.runPending(1))
        assertEquals(JobState.SENDING, stateDuringRun)
        assertEquals(emptyList<UploadJob>(), store.jobs(1))
    }

    @Test
    fun failedPhotoJobIsRecordedWhateverItsVersion() = runTest {
        store.saveJob(photoJob())
        uploader.answer = {
            store.saveJob(store.jobs(1).single().copy(version = 5)) // Planned again while it was sent
            Outcome.Failed("lost")
        }
        queue.runPending(1)
        val job = store.jobs(1).single()
        assertEquals(JobState.FAILED, job.state)
        assertEquals("lost", job.error)
    }

    @Test
    fun pendingPhotoJobDoesNotMakeAChildWait() = runTest {
        store.saveJob(addJob("add:I6", "I6", JobKind.ADD_CHILD, listOf("partner:I1")))
        store.saveJob(photoJob())
        uploader.answer = { Outcome.Done }
        queue.runPending(1)
        // The child is not held back by the photo of its parent
        assertEquals(listOf("add:I6", "photo:I1:me.jpg"), uploader.ran.map { it.key })
    }
}
