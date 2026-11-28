package app.familygem.geni.sync

import app.familygem.geni.map.PhotoDetails
import app.familygem.geni.map.PhotoField
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RunResult {
    /** No pending job left. */
    EMPTY,
    /** A temporary problem stopped the queue: run again later. */
    RETRY_LATER,
    /** The token is missing or expired: run again after login. */
    NEEDS_LOGIN
}

/** Result of asking to delete a profile on Geni. */
enum class DeleteQueued {
    QUEUED,
    /** A delete of this profile confirmed by another Geni account is still queued or failed: it is not replaced. */
    OTHER_ACCOUNT
}

/** The job an upload run is sending now, by ID and the version it read. */
data class ActiveJob(val id: Long, val version: Int)

/**
 * What the queue knows only in memory, for the sync backlog.
 * @param active The job each tree is sending now; a stored row with another version waits for its turn
 * @param deferred Keys of the photo jobs each tree skips for the rest of its run, because their person could not be verified
 * @param holdsChanged Increases whenever a crop hold starts or ends
 */
data class QueueStatus(
    val active: Map<Int, ActiveJob> = emptyMap(),
    val deferred: Map<Int, Set<String>> = emptyMap(),
    val holdsChanged: Long = 0
)

/** Why a pending job is not sent now, as the queue decides it. */
enum class JobWait {
    /** A new photo while "Send new photos to Geni" is off. */
    PHOTOS_OFF,
    /** A new photo the user is still cropping. */
    CROP_HOLD,
    /** A new photo whose person could not be checked in this run; the next run tries again. */
    VERIFYING
}

/** Message of an add that was being sent when the app stopped: Geni may or may not have created the profile (or photo). */
internal const val INTERRUPTED_ADD = "the app stopped while adding; check on Geni whether it was created, then retry or dismiss"

private const val DIFFERENT_VALUE = "sent, but Geni stored a different value; check the profile on Geni"

/**
 * Upload queue of the Geni trees: turns saved trees into jobs and runs them in order.
 * One lock per tree guards the jobs; it is never held during network calls.
 * [runPending] must not run twice at the same time for one tree (the app runner guarantees it).
 */
class SyncQueue(
    private val store: SyncStore,
    private val uploader: Uploader,
    /** Read at every plan: the "send new photos" setting. */
    private val uploadPhotos: () -> Boolean = { true },
    /** Told about every finished upload attempt (for the log); never given params or tokens. */
    private val onResult: (UploadJob, Outcome) -> Unit = { _, _ -> }
) {

    /** Photos waiting for the user to crop them; a held photo job is not selected. After a release, kick the runner. */
    val holds = PhotoHolds().also { holds -> holds.onChange = { statusFlow.update { it.copy(holdsChanged = it.holdsChanged + 1) } } }

    private val statusFlow = MutableStateFlow(QueueStatus())

    /** Live, in-memory state of the runs, for the sync backlog; never read by the queue itself. */
    val status: StateFlow<QueueStatus> = statusFlow.asStateFlow()

    /** Why [job] waits, if the queue holds it back for a reason of its own; null when it simply waits its turn. */
    fun waitReason(job: UploadJob): JobWait? = when {
        job.state != JobState.PENDING -> null
        job.kind == JobKind.ADD_PHOTO && !uploadPhotos() -> JobWait.PHOTOS_OFF
        job.kind == JobKind.ADD_PHOTO && isHeld(job) -> JobWait.CROP_HOLD
        job.key in status.value.deferred[job.treeId].orEmpty() -> JobWait.VERIFYING
        else -> null
    }

    private fun setActive(treeId: Int, job: UploadJob?) = statusFlow.update {
        it.copy(active = if (job == null) it.active - treeId else it.active + (treeId to ActiveJob(job.id, job.version)))
    }

    private fun setDeferred(treeId: Int, keys: Set<String>) = statusFlow.update {
        it.copy(deferred = if (keys.isEmpty()) it.deferred - treeId else it.deferred + (treeId to keys.toSet()))
    }

    private val locks = mutableMapOf<Int, Mutex>()
    /** Last saved state of each tree, to plan again after an upload changed links or snapshots. */
    private val lastLocal = mutableMapOf<Int, LocalTree>()
    /** Key of the job being uploaded in each tree. */
    private val running = mutableMapOf<Int, String>()
    /** Stamp of the last tree each plan started from: a tree accepted later must still be planned. */
    private val planned = mutableMapOf<Int, Long>()

    private fun lock(treeId: Int) = synchronized(locks) { locks.getOrPut(treeId) { Mutex() } }

    /** Plans the jobs for a saved tree and merges them into the queue by key. */
    suspend fun onSaved(treeId: Int, local: LocalTree) = lock(treeId).withLock {
        if (newer(treeId, local)) plan(treeId, local)
    }

    /** Stores [local] as the last saved tree unless a later capture is already stored; true if stored. */
    private fun newer(treeId: Int, local: LocalTree): Boolean = synchronized(lastLocal) {
        if (local.stamp < (lastLocal[treeId]?.stamp ?: Long.MIN_VALUE)) return false
        lastLocal[treeId] = local
        true
    }

    /** Takes a tree the uploader just edited as the last saved one, so the next plan starts from it. */
    fun setLocal(treeId: Int, local: LocalTree) {
        newer(treeId, local)
    }

    /**
     * After a conflict was resolved (the record and its snapshot changed): plans the tree again and puts the job back
     * in the queue if the user kept some of their values; with nothing left to send the plan has deleted it.
     */
    suspend fun afterResolve(treeId: Int, key: String) = lock(treeId).withLock {
        synchronized(lastLocal) { lastLocal[treeId] }?.let { plan(treeId, it) }
        store.jobs(treeId).firstOrNull { it.key == key && it.state == JobState.CONFLICT }
            ?.let { store.saveJob(it.copy(state = JobState.PENDING, error = null)) }
    }

    /** Plans the last saved tree again, e.g. after the photos setting changed; nothing if no tree was saved yet. */
    suspend fun replan(treeId: Int) = lock(treeId).withLock {
        synchronized(lastLocal) { lastLocal[treeId] }?.let { plan(treeId, it) }
    }

    /**
     * Queues the deletion of a profile on Geni (once per profile), bound to [confirmedBy], the Geni account (profile ID)
     * that confirmed it; [label] is what the user sees if it fails. The same account asking again is idempotent and puts a
     * failed or dismissed delete back. A job without an account (older) takes this confirmation. A job confirmed by
     * another account is kept as it is, unless the user dismissed it: [DeleteQueued.OTHER_ACCOUNT] then.
     */
    suspend fun enqueueDelete(treeId: Int, geniId: String, label: String, confirmedBy: String): DeleteQueued = lock(treeId).withLock {
        val key = "delete:$geniId"
        val existing = store.jobs(treeId).firstOrNull { it.key == key }
        if (existing != null) {
            val bound = existing.confirmedByProfileId
            if (bound != null && bound != confirmedBy && existing.state != JobState.DISMISSED) return@withLock DeleteQueued.OTHER_ACCOUNT
            val rebind = bound != confirmedBy
            // A failed or dismissed delete is asked for again: the user chose it once more
            if (rebind || (existing.state != JobState.PENDING && existing.state != JobState.SENDING))
                store.saveJob(existing.copy(state = JobState.PENDING, error = null, version = existing.version + 1, confirmedByProfileId = confirmedBy))
        } else store.saveJob(UploadJob(treeId = treeId, key = key, kind = JobKind.DELETE_PROFILE,
            geniId = geniId, params = emptyList(), label = label, localHash = 0, confirmedByProfileId = confirmedBy))
        // Stored first, so a failure leaves the edit queued. An edit still waiting would fail once Geni has deleted the profile
        // The delete is stored: a failure here must not report the request as failed
        try {
            store.jobs(treeId).firstOrNull { it.key == "profile:$geniId" }?.let { store.deleteJob(it.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The update would fail on Geni after the delete and show in the failed list
        }
        DeleteQueued.QUEUED
    }

    /** Queues the deletion of a photo on Geni (once per photo); a failed or dismissed one is asked for again. */
    suspend fun enqueueDeletePhoto(treeId: Int, photoId: String, personGedcomId: String, label: String) = lock(treeId).withLock {
        val key = "delphoto:$photoId"
        val existing = store.jobs(treeId).firstOrNull { it.key == key }
        if (existing != null) {
            if (existing.state != JobState.PENDING && existing.state != JobState.SENDING)
                store.saveJob(existing.copy(state = JobState.PENDING, error = null, version = existing.version + 1))
        } else store.saveJob(UploadJob(treeId = treeId, key = key, kind = JobKind.DELETE_PHOTO, geniId = photoId,
            personGedcomId = personGedcomId, params = emptyList(), label = label, localHash = 0))
    }

    /**
     * Queues the edit of a Geni photo's [fields] (the details the user changed to [wanted]), one job per photo.
     * [base] is what Geni had when the media was last synced; a job already queued keeps the base it started from and
     * its other wanted fields, so two edits of one photo send as one. A job that ends up wanting what it started from
     * is dropped; a failed or dismissed one is asked for again.
     * @return false if nothing is queued for the photo (no changed field and no job)
     */
    suspend fun enqueueUpdatePhoto(
        treeId: Int, photoId: String, personGedcomId: String, label: String, base: PhotoDetails, wanted: PhotoDetails, fields: Collection<PhotoField>
    ): Boolean = lock(treeId).withLock {
        val key = "updphoto:$photoId"
        // A dismissed job was given up: this edit starts from the current values. A sending one may still reach Geni, so what it
        // sends is the starting point: reverting it then sends the revert instead of dropping the job
        val existing = store.jobs(treeId).firstOrNull { it.key == key }?.takeIf { it.state != JobState.DISMISSED }
        val drafted = existing?.let { PhotoDetails.fromParams(it.params, UploadJob.WANT_PREFIX) }
        val start = when {
            existing == null -> base
            existing.state == JobState.SENDING -> drafted!!
            else -> PhotoDetails.fromParams(existing.params, UploadJob.BASE_PREFIX)
        }
        var want = drafted ?: base
        for (field in fields) want = want.with(field, wanted)
        if (start == want) {
            existing?.let { store.deleteJob(it.id) }
            return@withLock existing != null
        }
        val params = start.toParams(UploadJob.BASE_PREFIX) + want.toParams(UploadJob.WANT_PREFIX)
        val job = UploadJob(treeId = treeId, key = key, kind = JobKind.UPDATE_PHOTO, geniId = photoId, personGedcomId = personGedcomId,
            params = params, label = label, localHash = 0)
        store.saveJob(if (existing == null) store.jobs(treeId).firstOrNull { it.key == key }?.let { job.copy(id = it.id, version = it.version + 1) } ?: job else job.copy(id = existing.id, version = existing.version + 1))
        true
    }

    /** Queues tagging ([tag] true) or untagging a linked profile in a photo; the latest wish for the pair replaces an earlier one. */
    suspend fun enqueuePhotoTag(treeId: Int, photoId: String, profileId: String, personGedcomId: String, tag: Boolean, label: String) = lock(treeId).withLock {
        val key = "tags:$photoId:$profileId"
        val existing = store.jobs(treeId).firstOrNull { it.key == key }
        val job = UploadJob(treeId = treeId, key = key, kind = if (tag) JobKind.TAG_PHOTO else JobKind.UNTAG_PHOTO, geniId = photoId,
            personGedcomId = personGedcomId, params = listOf("profile" to profileId), label = label, localHash = 0)
        store.saveJob(if (existing == null) job else job.copy(id = existing.id, version = existing.version + 1))
    }

    /** Queues making [photoId] the main photo of [profileId] (one job per profile; the latest choice wins). */
    suspend fun enqueueSetMainPhoto(treeId: Int, profileId: String, photoId: String, personGedcomId: String, label: String) = lock(treeId).withLock {
        val key = "mainphoto:$profileId"
        val existing = store.jobs(treeId).firstOrNull { it.key == key }
        val job = UploadJob(treeId = treeId, key = key, kind = JobKind.SET_MAIN_PHOTO, geniId = profileId, personGedcomId = personGedcomId,
            params = listOf("photo_id" to photoId), label = label, localHash = 0)
        store.saveJob(if (existing == null) job else job.copy(id = existing.id, version = existing.version + 1))
    }

    /** Called with the tree lock held. */
    private suspend fun plan(treeId: Int, local: LocalTree) {
        synchronized(planned) { planned[treeId] = local.stamp }
        val existing = store.jobs(treeId).associateBy { it.key }
        val plan = UploadPlanner.plan(treeId, local, store.links(treeId), existing.values.toList(),
            store.photoLinks(treeId), uploadPhotos())
        for (wanted in plan.upserts) {
            val job = existing[wanted.key]
            when {
                job == null -> store.saveJob(wanted)
                job.sameChange(wanted) -> Unit // Keep it as it is, also when failed, dismissed or being sent
                // An add that was sent, failed or dismissed may exist on Geni: new details, but only the user sends it again
                job.isAdd() && job.state != JobState.PENDING ->
                    store.saveJob(wanted.copy(id = job.id, state = job.state, error = job.error, version = job.version + 1))
                else -> store.saveJob(wanted.copy(id = job.id, state = JobState.PENDING, error = null, version = job.version + 1))
            }
        }
        // The job being uploaded is neither deleted nor verified: its upload may change Geni after this plan
        val runningKey = synchronized(running) { running[treeId] }
        plan.deleteKeys.filter { it != runningKey }.mapNotNull { existing[it] }.forEach { store.deleteJob(it.id) }
        // An email typed for a person who is no longer in the tree must not go to a later person with the same GEDCOM ID
        val present = local.people.map { it.gedcomId }.toSet()
        store.newEmails(treeId).keys.filter { it !in present }.forEach { store.saveNewEmail(treeId, it, null) }
        plan.staleLinks.forEach { store.deleteLink(treeId, it) }
        plan.orphaned.forEach { store.setOrphaned(treeId, it, true) }
        plan.unorphaned.forEach { store.setOrphaned(treeId, it, false) }
        saveVerified(plan.verified.filter { runningKey != "profile:${it.geniId}" && runningKey != "union:${it.geniId}" })
    }

    /** Compared by what would be sent: a job queued before structured places that sends the same params stays as it is (a dismissed one too). */
    private fun UploadJob.sameChange(other: UploadJob) = paramsToSend() == other.params && kind == other.kind && geniId == other.geniId &&
            personGedcomId == other.personGedcomId && familyGedcomId == other.familyGedcomId && anchors == other.anchors

    /** Stores verified hashes, but only on links whose snapshot is still the one the hash was checked against. */
    internal suspend fun saveVerified(verified: List<GeniLink>) = verified.forEach {
        store.setVerifiedHash(it.treeId, it.geniId, it.gedcomId, it.snapshot, it.verifiedHash ?: return@forEach)
    }

    /** Runs the pending jobs of a tree in order until none is left or one has to wait. */
    suspend fun runPending(treeId: Int): RunResult {
        lock(treeId).withLock {
            // An add left "sending" by a stopped app may have reached Geni: the user decides, never an automatic retry
            store.jobs(treeId).filter { it.state == JobState.SENDING }
                .forEach { store.saveJob(it.copy(state = JobState.FAILED, error = INTERRUPTED_ADD)) }
        }
        val sent = mutableMapOf<String, List<Pair<String, String>>>() // Changes uploaded in this run, by key
        val deferred = mutableSetOf<String>() // Keys of photos whose target could not be verified: skipped for the rest of this run
        try {
            return runJobs(treeId, sent, deferred)
        } finally {
            setDeferred(treeId, emptySet())
        }
    }

    private suspend fun runJobs(treeId: Int, sent: MutableMap<String, List<Pair<String, String>>>, deferred: MutableSet<String>): RunResult {
        while (true) {
            val job = lock(treeId).withLock {
                var next = nextJob(store.jobs(treeId), deferred) ?: return@withLock null
                // The same change planned again right after it was sent: Geni stored another value. Stop instead of looping
                while (sent[next.key] == next.signature()) {
                    store.saveJob(next.copy(state = JobState.FAILED, error = DIFFERENT_VALUE))
                    next = nextJob(store.jobs(treeId), deferred) ?: return@withLock null
                }
                synchronized(running) { running[treeId] = next.key }
                // Shown as sending before the row turns SENDING, so the backlog never sees a sending add as interrupted
                setActive(treeId, next)
                try {
                    if (next.isAdd()) next.copy(state = JobState.SENDING).also { store.saveJob(it) } else next
                } catch (e: Throwable) {
                    synchronized(running) { running.remove(treeId) }
                    setActive(treeId, null)
                    throw e
                }
            } ?: return RunResult.EMPTY
            // Shown as sending until its outcome is stored: an add stays SENDING in the store until then
            val outcome = try {
                record(treeId, job, sent, deferred)
            } finally {
                setActive(treeId, null)
            }
            when (outcome) {
                is Outcome.Retry -> return RunResult.RETRY_LATER
                Outcome.NeedsLogin -> return RunResult.NEEDS_LOGIN
                else -> Unit
            }
        }
    }

    /** Uploads [job] and stores its outcome. */
    private suspend fun record(treeId: Int, job: UploadJob, sent: MutableMap<String, List<Pair<String, String>>>, deferred: MutableSet<String>): Outcome {
        val outcome = try {
            uploader.run(job)
        } finally {
            synchronized(running) { running.remove(treeId) }
        }
        onResult(job, outcome)
        lock(treeId).withLock {
            val current = store.jobs(treeId).firstOrNull { it.id == job.id }
            val unchanged = current?.version == job.version
            when (outcome) {
                Outcome.Done -> {
                    sent[job.key] = job.signature()
                    // An added person is linked now: newer details of it go as an update. A newer version
                    // of an update planned during the upload stays pending and runs next
                    if (!job.isAdd()) store.deleteJobIfVersion(job.id, job.version)
                    // Links and snapshots changed. The saved tree was captured before the add tagged the person
                    synchronized(lastLocal) { lastLocal[treeId] }?.let { local ->
                        val current = if (job.isAdd()) withAddedTags(treeId, local, job).also { tagged ->
                            synchronized(lastLocal) { lastLocal[treeId] = tagged }
                        } else local
                        plan(treeId, current)
                    }
                    // Only now, with an update of newer details stored: an add left by a stopped app finds its link and is not sent
                    if (job.isAdd()) store.deleteJob(job.id)
                }
                // A failed add may exist on Geni: recorded whatever its version, so it is never sent again by itself
                is Outcome.Failed -> if (current != null && (unchanged || job.isAdd()))
                    store.saveJob(current.copy(state = JobState.FAILED, error = outcome.message))
                // Waits for the user; a new change of the record plans it again
                is Outcome.Conflict -> if (current != null && unchanged)
                    store.saveJob(current.copy(state = JobState.CONFLICT, error = "changed on Geni meanwhile: " +
                            outcome.groups.joinToString(", ") { it.lowercase() }))
                // The check may have moved the snapshot after the new version was planned: plan it again against it
                Outcome.Superseded -> synchronized(lastLocal) { lastLocal[treeId] }?.let { plan(treeId, it) }
                // Nothing was sent: back to waiting, and not selected again in this run
                Outcome.Deferred -> {
                    deferred += job.key
                    setDeferred(treeId, deferred)
                    if (current?.state == JobState.SENDING) store.saveJob(current.copy(state = JobState.PENDING))
                }
                is Outcome.Retry, Outcome.NeedsLogin ->
                    if (current?.state == JobState.SENDING) store.saveJob(current.copy(state = JobState.PENDING)) // Not sent
            }
            // A tree the uploader reported, newer than any plan (its save may not be planned yet), whatever the outcome
            synchronized(lastLocal) { lastLocal[treeId] }
                ?.takeIf { it.stamp > synchronized(planned) { planned[treeId] ?: Long.MIN_VALUE } }
                ?.let { plan(treeId, it) }
        }
        return outcome
    }

    /** The saved tree with the tags an add gave to its person and family, as the live tree has them now. */
    private suspend fun withAddedTags(treeId: Int, local: LocalTree, job: UploadJob): LocalTree {
        val person = job.personGedcomId?.let { store.linkByGedcomId(treeId, it) }?.geniId?.takeIf { it.startsWith("profile-") }
        val family = job.familyGedcomId?.let { store.linkByGedcomId(treeId, it) }?.geniId?.takeIf { it.startsWith("union-") }
        return local.copy(
            people = local.people.map { if (it.gedcomId == job.personGedcomId && it.geniId == null && person != null) it.copy(geniId = person) else it },
            families = local.families.map { if (it.gedcomId == job.familyGedcomId && it.geniId == null && family != null) it.copy(geniId = family) else it }
        )
    }

    /** What a finished job sent: its params and kind, so an untag followed by a tag with the same params is not taken for a repeat. */
    private fun UploadJob.signature() = params + ("kind" to kind.name)

    /** A photo upload is like an add: after a lost response the photo may exist on Geni, so it is never sent again by itself. */
    private fun UploadJob.isAdd() = kind == JobKind.ADD_CHILD || kind == JobKind.ADD_PARTNER || kind == JobKind.ADD_PHOTO

    private fun isHeld(job: UploadJob): Boolean {
        val file = job.params.firstOrNull { it.first == "file" }?.second ?: return false
        return holds.isHeld(job.treeId, file)
    }

    /**
     * First pending job, in order, that does not wait for another add, whatever order they were planned in:
     * a child waits for every parent still to be added (it must go under the couple's union);
     * a partner waits only while none of its relatives is on Geni or added yet.
     */
    private fun nextJob(jobs: List<UploadJob>, skip: Set<String> = emptySet()): UploadJob? {
        val photos = uploadPhotos()
        val pending = jobs.filter {
            it.state == JobState.PENDING && it.key !in skip && (it.kind != JobKind.ADD_PHOTO || (photos && !isHeld(it)))
        }
        val adding = jobs.filter { it.isAdd() && it.kind != JobKind.ADD_PHOTO && (it.state == JobState.PENDING || it.state == JobState.SENDING) }
            .mapNotNull { it.personGedcomId }.toSet()
        fun waits(job: UploadJob): Boolean {
            val waiting = job.anchors.filter { it.substringAfter(':') in adding }
            return when (job.kind) {
                JobKind.ADD_CHILD -> waiting.any { it.startsWith("partner:") }
                JobKind.ADD_PARTNER -> job.anchors.isNotEmpty() && waiting.size == job.anchors.size
                else -> false
            }
        }
        return pending.firstOrNull { !waits(it) } ?: pending.firstOrNull()
    }

    /**
     * Puts failed jobs back in the queue: only those with these IDs, the ones the user saw
     * (a job that failed meanwhile, maybe an add, is never retried unseen).
     */
    suspend fun retryFailed(ids: Set<Long>) = changeFailed(ids) { it.copy(state = JobState.PENDING, error = null) }

    /** Hides the failed jobs with these IDs; they stay stored so the same change is not planned again. */
    suspend fun dismissFailed(ids: Set<Long>) = changeFailed(ids) { it.copy(state = JobState.DISMISSED) }

    private suspend fun changeFailed(ids: Set<Long>, change: (UploadJob) -> UploadJob) {
        val failed = { job: UploadJob -> job.state == JobState.FAILED && job.id in ids }
        for (treeId in store.allJobs().filter(failed).map { it.treeId }.distinct()) {
            lock(treeId).withLock {
                store.jobs(treeId).filter(failed).forEach { store.saveJob(change(it)) }
            }
        }
    }
}
