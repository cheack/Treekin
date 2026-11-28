package app.familygem.geni.sync

import app.familygem.geni.TreeHost
import app.familygem.geni.api.GeniPhoto
import app.familygem.geni.api.GeniProfile
import app.familygem.geni.api.PhotoDownloader
import app.familygem.geni.api.mugshotToPhoto
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.PhotoDetails
import app.familygem.geni.map.PhotoField
import org.folg.gedcom.model.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Keeps the main Geni photo of a person in the FamilyGem tree: downloaded to the tree's media folder and
 * attached to the person as a simple media marked primary and tagged with its Geni photo ID.
 * Upstream's card and diagram then show it like any other media.
 * @param download saves the photo at a URL to a file and returns its content type
 */
class PhotoSync(
    private val store: SyncStore,
    private val host: TreeHost,
    /** Share one guard between every [PhotoSync] of the process (see [PhotoDeletionGuard]). */
    val guard: PhotoDeletionGuard = PhotoDeletionGuard(),
    /** Where file transfers show in the sync backlog; a photo already on the device shows nothing. */
    private val feed: DownloadFeed = DownloadFeed(),
    private val download: suspend (String, File) -> String = PhotoDownloader()::download
) {

    /**
     * Brings the main photo of [personGedcomId] in line with [profile]'s mugshot. A photo already downloaded
     * and unchanged (same Geni photo ID) is not fetched again; if the download fails nothing changes.
     * A photo that was already downloaded as an album photo becomes the main one without a new download.
     * The previous main photo stays on the device as an album photo.
     * A photo whose deletion Geni confirmed is never attached, even if [profile] was fetched before the deletion.
     * @return true if the tree changed
     */
    suspend fun sync(treeId: Int, profile: GeniProfile, personGedcomId: String): Boolean {
        val photo = mugshotToPhoto(profile)
        val links = store.photoLinks(treeId, profile.id)
        val main = links.firstOrNull { it.isMain }
        if (photo == null) return main?.takeIf { it.state == PhotoState.DOWNLOADED }?.let { remove(treeId, profile, personGedcomId, it) } ?: false
        if (guard.isDeleted(treeId, photo.id)) return false
        val dir = host.mediaDir(treeId)
        fun present(link: PhotoLink?) = link?.fileName?.let { File(dir, it).isFile } == true
        if (main?.geniPhotoId == photo.id && present(main)) return false
        val known = links.firstOrNull { it.geniPhotoId == photo.id }
        return attachFetched(treeId, profile.id, personGedcomId, photo, known?.fileName?.takeIf { present(known) }, asMain = true)
    }

    /**
     * Downloads an album photo and attaches it to the person as a plain media (not primary).
     * @return true if it was added; false if it was already there, was deleted on Geni or the person is not in the tree
     */
    suspend fun addAlbumPhoto(treeId: Int, geniProfileId: String, personGedcomId: String, photo: GeniPhoto): Boolean {
        if (guard.isDeleted(treeId, photo.id)) return false
        val known = store.photoLinks(treeId, geniProfileId).firstOrNull { it.geniPhotoId == photo.id }
        if (known?.fileName?.let { File(host.mediaDir(treeId), it).isFile } == true) return false
        return attachFetched(treeId, geniProfileId, personGedcomId, photo, null, asMain = false)
    }

    /** A downloaded file waiting in the media folder under a staging name, and the name it gets when published. */
    private class Staged(val file: File, val fileName: String)

    /**
     * Downloads the photo unless [knownFile] already holds it, then attaches it under the tree's lock.
     * The staging file never outlives the call, whatever the outcome.
     */
    private suspend fun attachFetched(
        treeId: Int, profileId: String, personGedcomId: String, photo: GeniPhoto, knownFile: String?, asMain: Boolean
    ): Boolean {
        // Tracked here, outside the IO block, so a cancellation that discards the stage() result still finds the file
        val temps = mutableListOf<File>()
        try {
            val staged = if (knownFile == null) stage(treeId, profileId, photo, temps) ?: return false else null
            return attach(treeId, profileId, personGedcomId, photo, staged, knownFile ?: staged!!.fileName, asMain)
        } finally {
            temps.forEach { it.delete() }
        }
    }

    /** Downloads the photo to a staging file in the tree's media folder, listed in [temps] for the caller to delete; null if it has no usable URL. Throws on a failed download. */
    private suspend fun stage(treeId: Int, profileId: String, photo: GeniPhoto, temps: MutableList<File>): Staged? {
        val url = photo.urlFor(*SIZES) ?: return null
        val dir = host.mediaDir(treeId)
        // File work stays off the main thread; each call has its own staging file so quick double taps do not clash
        return withContext(Dispatchers.IO) {
            val temp = File.createTempFile("geni-${photo.id}-", ".download", dir)
            temps += temp
            val type = feed.track(treeId, DownloadKind.PHOTO, profileId) { download(url, temp) }
            if (!looksLikeImage(temp)) throw java.io.IOException("Downloaded file is not an image")
            Staged(temp, "geni-${photo.id}.${extension(type, url)}")
        }
    }

    /**
     * Publishes the file, edits the tree and writes the links as one step under the tree's lock, after a last check
     * that Geni did not delete the photo meanwhile.
     */
    private suspend fun attach(
        treeId: Int, profileId: String, personGedcomId: String, photo: GeniPhoto, staged: Staged?, fileName: String, asMain: Boolean
    ): Boolean = guard.withTree(treeId) {
        if (guard.isDeleted(treeId, photo.id)) return@withTree false
        if (staged != null) withContext(Dispatchers.IO) {
            val target = File(host.mediaDir(treeId), fileName)
            if (!staged.file.renameTo(target)) staged.file.copyTo(target, overwrite = true)
        }
        val details = detailsToApply(treeId, photo)
        // Read under the lock, so a main photo changed since the download is demoted, not a stale one
        val previous = if (asMain) store.photoLinks(treeId, profileId).firstOrNull { it.isMain } else null
        val attached = host.edit(treeId) { gedcom ->
            val person = gedcom.people.orEmpty().firstOrNull { it.id == personGedcomId && GeniMapper.getGeniId(it) == profileId }
                ?: return@edit false
            if (asMain) {
                // The Geni main photo becomes the primary one: any other primary is demoted, the old Geni one stays as plain media
                person.media.orEmpty().forEach { it.primary = null }
            }
            val existing = person.media.orEmpty().firstOrNull { GeniMapper.getTag(it, PHOTO_TAG) == photo.id }
            existing?.file = fileName
            val media = existing ?: Media().apply {
                file = fileName
                setFileTag("FILE")
                GeniMapper.setTag(this, PHOTO_TAG, photo.id)
                person.addMedia(this)
            }
            if (asMain) media.primary = "Y"
            if (details != null) PhotoDetails.apply(media, details)
            true
        }
        if (!attached) return@withTree false
        previous?.takeIf { it.geniPhotoId != photo.id }?.let { store.savePhotoLink(it.copy(isMain = false)) }
        // A repaired album file keeps main status
        val stillMain = store.photoLinks(treeId, profileId).any { it.geniPhotoId == photo.id && it.isMain }
        store.savePhotoLink(PhotoLink(treeId, profileId, photo.id, null, asMain || stillMain, marker(photo), fileName, PhotoState.DOWNLOADED))
        true
    }

    /**
     * Brings the title, description and date of the media of the listed [photos] in line with Geni, in every person of the tree
     * that has the photo, so both profiles of a shared photo show the same. No download. A photo whose own edit is
     * still waiting for Geni, a deleted one and one listed without details are left alone.
     * @return how many photos changed in the tree
     */
    suspend fun refreshDetails(treeId: Int, photos: List<GeniPhoto>): Int {
        var changed = 0
        for (photo in photos) {
            if (!photo.detailsKnown) continue
            val details = PhotoDetails.of(photo)
            // The queued-edit check is made under the tree's lock, with the write
            if (applyToMedia(treeId, photo.id, skipIfEditQueued = true) { PhotoDetails.apply(it, details) }) changed++
        }
        return changed
    }

    /**
     * Geni confirmed [photo] after an edit of [sent] fields from here: its details replace the local ones. Geni's title
     * replaces the media's own only when the title was among the [sent] fields; otherwise a local title stays.
     */
    suspend fun applyConfirmed(treeId: Int, photo: GeniPhoto, sent: Collection<PhotoField>): Boolean {
        if (!photo.detailsKnown) return false
        val details = PhotoDetails.of(photo)
        return applyToMedia(treeId, photo.id) { PhotoDetails.apply(it, details, force = PhotoField.TITLE in sent) }
    }

    /** The user's edit of [fields], shown at once on every media of the photo in the tree (the job to Geni is queued apart). */
    suspend fun applyOwnEdit(treeId: Int, geniPhotoId: String, details: PhotoDetails, fields: Collection<PhotoField>): Boolean =
        applyToMedia(treeId, geniPhotoId) { PhotoDetails.applyOwn(it, details, fields) }

    /** Runs [change] on every inline media of a person that carries [geniPhotoId], under the tree's lock; true if one changed. */
    private suspend fun applyToMedia(
        treeId: Int, geniPhotoId: String, skipIfEditQueued: Boolean = false, change: (Media) -> Boolean
    ): Boolean = guard.withTree(treeId) {
        if (guard.isDeleted(treeId, geniPhotoId)) return@withTree false
        if (skipIfEditQueued && editQueued(treeId, geniPhotoId)) return@withTree false
        host.edit(treeId) { gedcom ->
            var any = false
            for (person in gedcom.people.orEmpty()) for (media in person.media.orEmpty()) {
                if (GeniMapper.getTag(media, PHOTO_TAG) == geniPhotoId && change(media)) any = true
            }
            any
        }
    }

    /** The details to store for [photo], or null: it has none, Geni deleted it, or the user's edit of it is still queued. */
    private suspend fun detailsToApply(treeId: Int, photo: GeniPhoto): PhotoDetails? =
        if (!photo.detailsKnown || guard.isDeleted(treeId, photo.id) || editQueued(treeId, photo.id)) null else PhotoDetails.of(photo)

    /** True while a queued edit of the photo's details has not been given up (a dismissed one has). */
    private suspend fun editQueued(treeId: Int, photoId: String) = store.jobs(treeId).any {
        it.kind == JobKind.UPDATE_PHOTO && it.geniId == photoId && it.state != JobState.DISMISSED
    }

    private suspend fun remove(treeId: Int, profile: GeniProfile, personGedcomId: String, link: PhotoLink): Boolean {
        removeLink(treeId, profile.id, personGedcomId, link)
        return true
    }

    /**
     * Removes a Geni photo from the person: its media, the photo link and the file when no other link uses it.
     * @return true if the tree changed
     */
    suspend fun removeLocal(treeId: Int, geniProfileId: String, personGedcomId: String, geniPhotoId: String): Boolean {
        val link = store.photoLinks(treeId, geniProfileId).firstOrNull { it.geniPhotoId == geniPhotoId }
        return removeLink(treeId, geniProfileId, personGedcomId, link ?: PhotoLink(treeId, geniProfileId, geniPhotoId, null, false, null, null, PhotoState.DOWNLOADED))
    }

    /**
     * Geni confirmed the deletion of [geniPhotoId] (already recorded in [guard]): removes it here (media, link, file when unused), then, if it was the
     * main photo, looks up the profile with [fetchProfile] and, if Geni now names another main photo, syncs it.
     * A response that still names the deleted photo never brings it back. A failed lookup or download leaves the removal
     * as it is; the next refresh picks the replacement up. Media the user gave a title or a note stays as a plain local
     * photo of the person (no Geni tag, not primary, file kept); only its Geni link goes.
     * @param onChanged called after the removal and again after a replacement arrived
     * @return true if the photo was taken off the person here
     */
    suspend fun mainDeleted(
        treeId: Int, geniProfileId: String, personGedcomId: String, geniPhotoId: String,
        fetchProfile: suspend () -> GeniProfile?, onChanged: () -> Unit
    ): Boolean {
        // By photo ID, not main status: a load meanwhile may have demoted the photo already
        val link = store.photoLinks(treeId, geniProfileId).firstOrNull { it.geniPhotoId == geniPhotoId } ?: return false
        // Under the tree's lock: an attachment running now finishes first, so the removal sees it; the lock is free before any fetch
        val removed = guard.withTree(treeId) { removeLink(treeId, geniProfileId, personGedcomId, link, detachIfEdited = true) }
        if (!removed) return false
        onChanged()
        if (!link.isMain) return true // Another photo is already main: nothing to look up
        try {
            val profile = fetchProfile() ?: return true
            val next = mugshotToPhoto(profile) ?: return true
            if (next.id != geniPhotoId && sync(treeId, profile, personGedcomId)) onChanged()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // The deletion stands; a later refresh fetches the replacement
        }
        return true
    }

    /**
     * Removes the album photos of a profile that Geni no longer lists: media, link and file, as [removeLocal] does.
     * Not touched: the main photo, photos not downloaded yet or waiting for upload or deletion, and photos
     * the user made their own with a title or a note (the media stays and so does its link).
     * @param album the photos Geni lists now
     * @return how many photos were removed
     */
    suspend fun reconcileAlbum(treeId: Int, geniProfileId: String, personGedcomId: String, album: List<GeniPhoto>): Int {
        val listed = album.map { it.id }.toSet()
        val gone = store.photoLinks(treeId, geniProfileId).filter { !it.isMain && it.state == PhotoState.DOWNLOADED && it.geniPhotoId !in listed }
        var removed = 0
        for (link in gone) {
            if (removeLink(treeId, geniProfileId, personGedcomId, link, keepIfEdited = true)) removed++
        }
        return removed
    }

    /**
     * @param keepIfEdited leave everything as it is when the media carries a title or notes of the user
     * @param detachIfEdited such media stays as the user's own: it loses its Geni tag and primary mark, and keeps its file
     */
    private suspend fun removeLink(
        treeId: Int, profileId: String, personGedcomId: String, link: PhotoLink,
        keepIfEdited: Boolean = false, detachIfEdited: Boolean = false
    ): Boolean {
        var kept = false
        var found = false
        var detached = false
        val changed = host.edit(treeId) { gedcom ->
            val person = gedcom.people.orEmpty().firstOrNull { it.id == personGedcomId && GeniMapper.getGeniId(it) == profileId }
            val before = person?.media.orEmpty().size
            found = person?.media.orEmpty().any { GeniMapper.getTag(it, PHOTO_TAG) == link.geniPhotoId }
            if (keepIfEdited && person?.media.orEmpty().any { GeniMapper.getTag(it, PHOTO_TAG) == link.geniPhotoId && isEdited(it) }) {
                kept = true
                return@edit false
            }
            if (detachIfEdited) person?.media.orEmpty().filter { GeniMapper.getTag(it, PHOTO_TAG) == link.geniPhotoId && isEdited(it) }.forEach {
                GeniMapper.setTag(it, PHOTO_TAG, null)
                it.primary = null
                detached = true
            }
            person?.let { it.media = it.media.orEmpty().filter { m -> GeniMapper.getTag(m, PHOTO_TAG) != link.geniPhotoId }.toMutableList() }
            person != null && (person.media.size != before || detached)
        }
        // No inline media with the tag (e.g. made a shared media): the user owns it, so its link and file stay
        if (kept || !found) return false
        store.deletePhotoLink(treeId, profileId, link.geniPhotoId)
        if (!detached) deleteFileIfUnused(treeId, link.fileName)
        return changed
    }

    /** A media the user gave a title of their own (not the one synced from Geni) or a note (a GEDCOM media holds no sources of its own). */
    private fun isEdited(media: Media) =
        PhotoDetails.hasOwnTitle(media) || media.notes.orEmpty().isNotEmpty() || media.noteRefs.orEmpty().isNotEmpty()

    /**
     * Two profiles can share one Geni photo and so one file: it goes only when no photo link uses it any more
     * and no media in the tree still names it (a photo kept as the user's own has no link).
     */
    private suspend fun deleteFileIfUnused(treeId: Int, fileName: String?) {
        if (fileName == null || store.photoLinks(treeId).any { it.fileName == fileName }) return
        if (host.edit(treeId) { gedcom -> mediaFiles(gedcom).any { it == fileName } }) return
        withContext(Dispatchers.IO) { File(host.mediaDir(treeId), fileName).delete() }
    }

    /** File names (without folders) of every media of people, families, their events and the shared media. */
    private fun mediaFiles(gedcom: org.folg.gedcom.model.Gedcom): Sequence<String> {
        val holders = gedcom.people.orEmpty().asSequence().flatMap { sequenceOf(it.media.orEmpty()) + it.eventsFacts.orEmpty().map { e -> e.media.orEmpty() } } +
                gedcom.families.orEmpty().asSequence().flatMap { sequenceOf(it.media.orEmpty()) + it.eventsFacts.orEmpty().map { e -> e.media.orEmpty() } } +
                sequenceOf(gedcom.media.orEmpty())
        return holders.flatMap { it.asSequence() }.mapNotNull { it.file?.substringAfterLast('/')?.substringAfterLast('\\') }
    }

    companion object {
        /** Custom tag of a media that came from Geni. */
        const val PHOTO_TAG = "_GENI_PHOTO_ID"

        /** Size order: medium is enough for the card and the diagram and keeps downloads small. */
        private val SIZES = arrayOf("medium", "large", "original", "small")

        /** URL path without its signed query: it changes only when the photo does. */
        fun marker(photo: GeniPhoto): String? = photo.urlFor(*SIZES)?.substringBefore('?')

        fun extension(contentType: String, url: String): String = when (contentType.lowercase().substringBefore(';').trim()) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/jpeg", "image/jpg" -> "jpg"
            else -> url.substringBefore('?').substringAfterLast('.', "jpg").takeIf { it.length in 2..4 } ?: "jpg"
        }

        /** True if the file starts like a JPEG, PNG, GIF or WebP image. */
        fun looksLikeImage(file: File): Boolean {
            val head = ByteArray(12)
            val n = file.inputStream().use { it.read(head) }
            if (n < 4) return false
            fun starts(vararg bytes: Int) = bytes.indices.all { head[it] == bytes[it].toByte() }
            return starts(0xFF, 0xD8, 0xFF) || starts(0x89, 0x50, 0x4E, 0x47) || starts(0x47, 0x49, 0x46, 0x38) ||
                    (n >= 12 && starts(0x52, 0x49, 0x46, 0x46) && head[8] == 'W'.code.toByte() && head[9] == 'E'.code.toByte())
        }
    }
}
