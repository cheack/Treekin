package app.familygem.geni.sync

import app.familygem.geni.TreeHost
import app.familygem.geni.api.GeniAccountMismatchException
import app.familygem.geni.api.GeniApiException
import app.familygem.geni.api.GeniNotConfirmedException
import app.familygem.geni.api.GeniSendRejectedException
import app.familygem.geni.api.GeniSessionChangedException
import app.familygem.geni.api.GeniAuthRequiredException
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.GeniPhoto
import app.familygem.geni.api.geniId
import app.familygem.geni.api.GeniProfile
import app.familygem.geni.api.GeniUnion
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.ProfileFields
import app.familygem.geni.map.SnapshotOverlay
import app.familygem.geni.map.UnionFields
import app.familygem.geni.map.threeWay
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException

/** How a job ended. */
sealed interface Outcome {
    data object Done : Outcome
    /** Temporary problem (network, Geni server error): try again later. */
    data class Retry(val reason: String) : Outcome
    /** Geni refused the change, or it cannot be sent: the user decides. */
    data class Failed(val message: String) : Outcome
    data object NeedsLogin : Outcome
    /** Geni changed these field groups since the snapshot, and the user changed them too: nothing was sent. */
    data class Conflict(val groups: List<String>) : Outcome
    /** The job changed while it was checked: nothing was sent, the new version runs next. */
    data object Superseded : Outcome
    /** The target of the job could not be verified against the saved tree: nothing was sent, the job stays waiting. */
    data object Deferred : Outcome
}

private val gson = Gson()

/** Conflict group of a profile deleted or merged on Geni. */
const val GONE = "GONE"

private class SnapshotConflict : Exception("the snapshot kept changing during the refresh")

private const val WRONG_ACCOUNT = "not deleted: it was confirmed under another Geni account; sign in to that account, then retry"

private const val NOT_CONFIRMED = "not deleted: it was queued before the account was recorded; choose Delete on Geni for this person again"

private const val CHECK_PHOTO = "check on Geni whether the photo was added, then retry or dismiss"

private const val CHECK_GENI = "check on Geni whether the person was created, then retry or dismiss"

/** Runs one upload job against Geni and records the result in the sync store and the tree. */
open class Uploader(
    private val client: GeniClient,
    private val store: SyncStore,
    private val host: TreeHost,
    private val now: () -> Long = System::currentTimeMillis,
    /** The bytes to send for a photo file (a place to shrink big photos); the file as it is by default. */
    private val prepare: suspend (File) -> ByteArray = { it.readBytes() },
    private val photos: PhotoSync = PhotoSync(store, host)
) {

    /** Told about the tree after every edit the uploader makes, so plans never use a tree saved before it. */
    var onTreeEdited: (Int, LocalTree) -> Unit = { _, _ -> }

    open suspend fun run(job: UploadJob): Outcome = try {
        when (job.kind) {
            JobKind.UPDATE_PROFILE, JobKind.UPDATE_UNION -> update(job)
            JobKind.ADD_CHILD, JobKind.ADD_PARTNER -> add(job)
            JobKind.DELETE_PROFILE -> delete(job)
            JobKind.ADD_PHOTO -> addPhoto(job)
            JobKind.DELETE_PHOTO -> deletePhoto(job)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: GeniAuthRequiredException) {
        Outcome.NeedsLogin
    } catch (e: GeniApiException) {
        if (e.code in 400..499 && e.code != 408 && e.code != 429) Outcome.Failed(geniMessage(e.message))
        else Outcome.Retry("HTTP ${e.code}")
    } catch (e: IOException) {
        Outcome.Retry(e.message ?: e.javaClass.simpleName)
    } catch (e: Exception) {
        // Never throw out of the queue; adds handle their own errors after the POST, so this is an update or a lookup
        Outcome.Retry(e.message ?: e.javaClass.simpleName)
    }

    /**
     * Sends the changed fields (if any), then stores Geni's new version as the snapshot.
     * First Geni's current version is compared with the snapshot: changes made on Geni meanwhile are merged into the tree
     * (the job's fields are the user's, in other groups), or the job stops as a conflict when they touch the same groups.
     */
    private suspend fun update(job: UploadJob): Outcome {
        val geniId = job.geniId ?: return Outcome.Failed("no Geni ID")
        val params = job.paramsToSend() // A job queued before structured places must not clear parts it never changed
        if (params.isEmpty()) return refreshOnly(job.treeId, geniId)
        mergeGeniChanges(job.treeId, geniId)?.let { return it }
        // A save during the check replaced the params: never post the old ones
        val current = store.jobs(job.treeId).firstOrNull { it.id == job.id }
        if (current == null || current.version != job.version) return Outcome.Superseded
        val before = store.link(job.treeId, geniId)?.snapshot
        client.post("$geniId/update", params)
        // Geni took the change. Without its new version, the old snapshot would make this change look like someone else's:
        // the snapshot becomes the old one with the sent fields written over it
        return try {
            refreshSnapshot(job.treeId, geniId)
            Outcome.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: SnapshotConflict) {
            Outcome.Retry(e.message!!) // Loads keep storing other versions: send again later, that is safe
        } catch (e: Exception) {
            val link = store.link(job.treeId, geniId)
            if (before != null) store.updateSnapshot(job.treeId, geniId, before, SnapshotOverlay.apply(before, params, geniId), link?.geniUpdatedAt)
            Outcome.Done
        }
    }

    /** Deletes the profile on Geni; only after Geni answered does the person leave the tree. */
    private suspend fun delete(job: UploadJob): Outcome {
        val geniId = job.geniId ?: return Outcome.Failed("no Geni ID")
        // Only under the account that confirmed it; a mismatch or an unconfirmed job leaves the person and the link alone
        try {
            client.deleteProfile(geniId, job.confirmedByProfileId)
        } catch (e: GeniAccountMismatchException) {
            return Outcome.Failed(WRONG_ACCOUNT)
        } catch (e: GeniNotConfirmedException) {
            return Outcome.Failed(NOT_CONFIRMED)
        } catch (e: GeniSessionChangedException) {
            return Outcome.Retry("the Geni login changed; the account is checked again")
        }
        // Geni has deleted it: a failure here must not make the job post the delete again
        bestEffort { PersonRemoval(store, host, now).removeFromTree(job.treeId, geniId, FeedRecordKind.DELETED_ON_GENI) }
        bestEffort { store.deleteLink(job.treeId, geniId) }
        return Outcome.Done
    }

    /** Deletes the photo on Geni; already gone there counts as done. A repeat is safe. */
    private suspend fun deletePhoto(job: UploadJob): Outcome {
        val photoId = job.geniId ?: return Outcome.Failed("no Geni photo ID")
        try {
            client.deletePhoto(photoId)
        } catch (e: GeniApiException) {
            if (e.code != 404) throw e
        }
        // Geni has deleted it: from now no profile response or download may attach it again
        photos.guard.confirm(job.treeId, photoId)
        // The local follow-up must never make the job post the delete again
        bestEffort { afterPhotoDeleted(job, photoId) }
        return Outcome.Done
    }

    /** Takes a deleted main photo off the person and fetches the one Geni chose instead, then tells the open profile. */
    private suspend fun afterPhotoDeleted(job: UploadJob, photoId: String) {
        val treeId = job.treeId
        val personId = job.personGedcomId ?: return
        val profileId = store.linkByGedcomId(treeId, personId)?.geniId?.takeIf { it.startsWith("profile-") } ?: return
        val changed = { host.refreshScreens(); PersonPhotoChanges.notify(treeId, personId) }
        photos.mainDeleted(treeId, profileId, personId, photoId, { client.profiles(listOf(profileId))[profileId]?.first }, changed)
    }

    /** An update without parameters only fetches the snapshot (after an add whose snapshot could not be fetched). */
    private suspend fun refreshOnly(treeId: Int, geniId: String): Outcome {
        return try {
            refreshSnapshot(treeId, geniId)
            Outcome.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Outcome.Retry("snapshot after update: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Creates the profile on Geni under the family's union or an anchor relative, then links the person (and the family). */
    private suspend fun add(job: UploadJob): Outcome {
        val treeId = job.treeId
        val personId = job.personGedcomId ?: return Outcome.Failed("no person")
        // Already created (a finished job run again): never create a second profile
        if (store.linkByGedcomId(treeId, personId)?.geniId?.startsWith("profile-") == true) {
            bestEffort { store.saveNewEmail(treeId, personId, null) }
            return Outcome.Done
        }
        val familyLink = job.familyGedcomId?.let { store.linkByGedcomId(treeId, it) }?.takeIf { it.geniId.startsWith("union-") }
        suspend fun anchor(role: String): GeniLink? = job.anchors.filter { it.startsWith("$role:") }.firstNotNullOfOrNull {
            store.linkByGedcomId(treeId, it.substringAfter(':'))?.takeIf { link -> link.geniId.startsWith("profile-") }
        }
        val path = when (job.kind) {
            JobKind.ADD_CHILD -> familyLink?.let { "${it.geniId}/add-child" } ?: singleParent(job)?.let { "${it.geniId}/add-child" }
            else -> familyLink?.let { "${it.geniId}/add-partner" } ?: anchor("child")?.let { "${it.geniId}/add-parent" }
                ?: anchor("partner")?.let { "${it.geniId}/add-partner" }
        } ?: return Outcome.Failed("depends on a change that was not uploaded")

        // The email typed for the new relative goes with this one request only: not into the job, the snapshot or any later update
        val emailParam = try {
            store.newEmails(treeId)[personId]?.takeIf { it.isNotBlank() }?.let { listOf("email" to it) }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Retry("email not read: ${e.message ?: e.javaClass.simpleName}") // Nothing was sent yet
        }

        // From here Geni may have created the profile even when the request fails: never retry automatically
        val response = try {
            client.post(path, job.paramsToSend() + emailParam)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeniAuthRequiredException) {
            return Outcome.NeedsLogin // Refused before anything was created
        } catch (e: GeniApiException) {
            if (e.code == 429) return Outcome.Retry("HTTP 429") // Not processed
            return Outcome.Failed("${geniMessage(e.message)}; $CHECK_GENI")
        } catch (e: UnknownHostException) {
            return Outcome.Retry("offline: ${e.message}") // No connection was made: nothing reached Geni
        } catch (e: ConnectException) {
            return Outcome.Retry("offline: ${e.message}")
        } catch (e: NoRouteToHostException) {
            return Outcome.Retry("offline: ${e.message}")
        } catch (e: Exception) {
            return Outcome.Failed("connection lost while adding; $CHECK_GENI")
        }
        val json = runCatching { JsonParser.parseString(response).asJsonObject }.getOrNull()
        val profileId = runCatching { json?.get("id")?.asString }.getOrNull()?.takeIf { it.startsWith("profile-") }
            ?: return Outcome.Failed("unexpected answer from Geni; $CHECK_GENI")
        val unionId = runCatching { json?.getAsJsonArray("unions")?.firstOrNull()?.asString?.let { geniId(it) } }.getOrNull()
        val familyId = job.familyGedcomId
        val linkFamily = familyLink == null && unionId != null && familyId != null

        // Links first: once stored, this person can never be added again; the tags in the tree follow
        // What was sent stands for Geni's version until the full one is fetched: an edit made meanwhile goes as an update
        val links = mutableListOf(GeniLink(treeId, profileId, personId, false, null, SnapshotOverlay.apply(null, job.profileParams(), profileId), 0))
        if (linkFamily) links += GeniLink(treeId, unionId!!, familyId!!, true, null, null, now())
        try {
            store.saveLinks(links)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Geni created the profile but this device could not record it: never send the add again by itself
            return Outcome.Failed("Geni created $profileId but the app could not save the link (${e.message}); $CHECK_GENI")
        }
        bestEffort {
            host.edit(treeId) { gedcom ->
                gedcom.people.orEmpty().firstOrNull { it.id == personId }?.let { GeniMapper.setGeniId(it, profileId) }
                if (linkFamily) gedcom.families.orEmpty().firstOrNull { it.id == familyId }?.let { GeniMapper.setGeniId(it, unionId!!) }
                onTreeEdited(treeId, LocalTree.capture(gedcom))
            }
            host.refreshScreens()
        }
        bestEffort { store.saveNewEmail(treeId, personId, null) } // Used: later changes of the email are made on Geni
        FeedRecords.addedHere(store, treeId, profileId, personId, now())
        bestEffort {
            refreshSnapshot(treeId, profileId)
            (unionId ?: familyLink?.geniId)?.let { refreshSnapshot(treeId, it) }
        }
        return Outcome.Done
    }

    /** Sends the photo file of a person to its Geni profile, then links it and tags the media in the tree. */
    private suspend fun addPhoto(job: UploadJob): Outcome {
        val treeId = job.treeId
        val profileId = job.geniId ?: return Outcome.Failed("no Geni ID")
        val personId = job.personGedcomId ?: return Outcome.Failed("no person")
        val fileName = job.params.firstOrNull { it.first == "file" }?.second
        if (fileName.isNullOrEmpty() || fileName == "." || fileName == ".." || '/' in fileName || '\\' in fileName)
            return Outcome.Failed("the photo file name is not a plain file name")
        // Already uploaded (a finished job run again): never send a second copy
        if (store.photoLinks(treeId, profileId).any { it.fileName == fileName }) return Outcome.Done
        if (!ownerStillMatches(treeId, profileId, personId, fileName)) return Outcome.Deferred
        val file = File(host.mediaDir(treeId), fileName)
        if (!file.isFile) return Outcome.Failed("the photo file is missing: $fileName")
        val title = job.params.firstOrNull { it.first == "title" }?.second
        val bytes = try {
            prepare(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Nothing was sent, so a failure is safe to report; fatal errors other than memory are not ours to catch
            if (e is Error && e !is OutOfMemoryError) throw e
            return Outcome.Failed("cannot read the photo: ${e.message ?: e.javaClass.simpleName}")
        }

        // From here Geni may have stored the photo even when the request fails: never retry automatically
        val photo: GeniPhoto = try {
            client.addPhoto(profileId, fileName, title, bytes) { ownerStillMatches(treeId, profileId, personId, fileName) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeniSendRejectedException) {
            return Outcome.Deferred // Refused before the request went out
        } catch (e: GeniAuthRequiredException) {
            return Outcome.NeedsLogin // Refused before anything was stored
        } catch (e: GeniApiException) {
            if (e.code == 429) return Outcome.Retry("HTTP 429") // Not processed
            return Outcome.Failed("${geniMessage(e.message)}; $CHECK_PHOTO")
        } catch (e: UnknownHostException) {
            return Outcome.Retry("offline: ${e.message}") // No connection was made: nothing reached Geni
        } catch (e: ConnectException) {
            return Outcome.Retry("offline: ${e.message}")
        } catch (e: NoRouteToHostException) {
            return Outcome.Retry("offline: ${e.message}")
        } catch (e: Exception) {
            return Outcome.Failed("connection lost while sending the photo; $CHECK_PHOTO")
        }

        // The link first: once stored, this file is never sent again; the tag in the tree follows
        try {
            store.savePhotoLink(PhotoLink(treeId, profileId, photo.id, null, false, PhotoSync.marker(photo), fileName, PhotoState.DOWNLOADED))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Failed("Geni stored ${photo.id} but the app could not save the link (${e.message}); $CHECK_PHOTO")
        }
        bestEffort {
            host.edit(treeId) { gedcom ->
                val media = gedcom.people.orEmpty().firstOrNull { it.id == personId }?.media.orEmpty()
                    .firstOrNull { it.file?.substringAfterLast('/') == fileName && GeniMapper.getTag(it, PhotoSync.PHOTO_TAG) == null }
                media?.let { GeniMapper.setTag(it, PhotoSync.PHOTO_TAG, photo.id) }
                onTreeEdited(treeId, LocalTree.capture(gedcom))
            }
            host.refreshScreens()
        }
        return Outcome.Done
    }

    /**
     * True if the saved tree still gives the photo to the person, and the link rules still resolve that person to [profileId].
     * Anything that cannot be verified (unreadable tree, missing person, ambiguous or other link) counts as no.
     */
    private suspend fun ownerStillMatches(treeId: Int, profileId: String, personId: String, fileName: String): Boolean {
        val person = try {
            host.savedPerson(treeId, personId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return false
        if (person.media.none { it.file == fileName && it.geniPhotoId == null }) return false
        val (tagged, untagged) = try {
            person.geniId?.let { store.link(treeId, it) } to (if (person.geniId == null) store.linkByGedcomId(treeId, personId) else null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false // A database error is not a verified owner
        }
        val match = matchLink(person.gedcomId, person.geniId, "profile-", { tagged }, { untagged }) { sameProfile(it, person) }
        return match is LinkMatch.Linked && match.link.geniId == profileId
    }

    /** Merges what Geni changed since the snapshot into the tree; a conflict, a retry or null to go on. */
    private suspend fun mergeGeniChanges(treeId: Int, geniId: String): Outcome? {
        val link = store.link(treeId, geniId) ?: return null
        val snapshot = link.snapshot ?: return null
        val isProfile = geniId.startsWith("profile-")
        val (raw, updatedAt) = if (isProfile) {
            val (profile, raw) = client.profiles(listOf(geniId))[geniId] ?: return null
            raw to profile.updatedAt
        } else {
            val (_, raw) = client.unions(listOf(geniId))[geniId] ?: return null
            raw to link.geniUpdatedAt
        }
        // Deleted or merged on Geni: only the user can say what happens to the edits
        if (isProfile && gson.fromJson(raw, GeniProfile::class.java).deleted == true) return Outcome.Conflict(listOf(GONE))
        val changed = if (isProfile) ProfileFields.ofSnapshot(snapshot) != ProfileFields.ofSnapshot(raw)
        else UnionFields.ofSnapshot(snapshot) != UnionFields.ofSnapshot(raw)
        if (!changed) return null
        val conflicts = host.edit(treeId) { gedcom ->
            val conflicts = if (isProfile) {
                val person = gedcom.people.orEmpty().firstOrNull { it.id == link.gedcomId } ?: return@edit null
                val geni = gson.fromJson(raw, GeniProfile::class.java)
                val result = threeWay(ProfileFields.ofSnapshot(snapshot)!!, ProfileFields.of(person), ProfileFields.ofSnapshot(raw)!!)
                result.conflicts.map { it.name }.ifEmpty {
                        GeniMapper.applyProfile(gedcom, person, geni, gson.fromJson(snapshot, GeniProfile::class.java), result.local)
                        emptyList()
                    }
            } else {
                val family = gedcom.families.orEmpty().firstOrNull { it.id == link.gedcomId } ?: return@edit null
                val geni = gson.fromJson(raw, GeniUnion::class.java)
                val result = threeWay(UnionFields.ofSnapshot(snapshot)!!, UnionFields.of(family), UnionFields.ofSnapshot(raw)!!)
                result.conflicts.map { it.name }.ifEmpty {
                        GeniMapper.applyUnion(gedcom, family, geni, gson.fromJson(snapshot, GeniUnion::class.java), result.local, personFor = GeniMapper.peopleByGeniId(gedcom))
                        emptyList()
                    }
            }
            if (conflicts.isEmpty()) onTreeEdited(treeId, LocalTree.capture(gedcom))
            conflicts
        } ?: return Outcome.Failed("the record is no longer in the tree")
        if (conflicts.isNotEmpty()) {
            // A load may have stored a newer base during the check: compare again against it
            if (store.link(treeId, geniId)?.snapshot != snapshot) return Outcome.Retry("the snapshot changed during the check")
            return Outcome.Conflict(conflicts)
        }
        host.refreshScreens()
        // Geni's version is now merged: it is the new base. A load that stored another one meanwhile makes this run again
        if (!store.updateSnapshot(treeId, geniId, snapshot, raw, updatedAt)) return Outcome.Retry("the snapshot changed during the check")
        return null
    }

    /**
     * The only parent of a family that has no union on Geni yet: profile-X/add-child creates the union.
     * With two parents the child must wait for (or fail with) the couple's union, never go under one parent alone.
     */
    private suspend fun singleParent(job: UploadJob): GeniLink? {
        val partners = job.anchors.filter { it.startsWith("partner:") }
        if (partners.size != 1) return null
        return store.linkByGedcomId(job.treeId, partners[0].substringAfter(':'))?.takeIf { it.geniId.startsWith("profile-") }
    }

    /**
     * Fetches the full Geni version of a linked record (write responses lack fields) and stores it as the snapshot.
     * A load may store a newer snapshot during the fetch: the write happens only over the snapshot read before it,
     * else the record is fetched again; after a few conflicts it throws, so an update is retried later.
     */
    private suspend fun refreshSnapshot(treeId: Int, geniId: String) {
        repeat(3) {
            val before = store.link(treeId, geniId) ?: return
            val written = if (geniId.startsWith("profile-")) {
                val (profile, raw) = client.profiles(listOf(geniId))[geniId] ?: return
                store.updateSnapshot(treeId, geniId, before.snapshot, raw, profile.updatedAt)
            } else {
                val (_, raw) = client.unions(listOf(geniId))[geniId] ?: return
                store.updateSnapshot(treeId, geniId, before.snapshot, raw, before.geniUpdatedAt)
            }
            if (written) return
        }
        throw SnapshotConflict()
    }

    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The change itself is on Geni; the snapshot comes with the next load
        }
    }

    /** The message of a Geni error body like {"error":{"message":"Access Denied"}}, else the body itself (shortened). */
    private fun geniMessage(body: String?): String {
        val message = runCatching {
            JsonParser.parseString(body).asJsonObject.getAsJsonObject("error").get("message").asString
        }.getOrNull()
        return message ?: body?.take(200)?.ifBlank { null } ?: "Geni refused the change"
    }
}
