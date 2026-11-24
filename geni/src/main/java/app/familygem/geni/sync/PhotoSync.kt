package app.familygem.geni.sync

import app.familygem.geni.TreeHost
import app.familygem.geni.api.GeniPhoto
import app.familygem.geni.api.GeniProfile
import app.familygem.geni.api.PhotoDownloader
import app.familygem.geni.api.mugshotToPhoto
import app.familygem.geni.map.GeniMapper
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
    private val download: suspend (String, File) -> String = PhotoDownloader()::download
) {

    /**
     * Brings the main photo of [personGedcomId] in line with [profile]'s mugshot. A photo already downloaded
     * and unchanged (same Geni photo ID) is not fetched again; if the download fails nothing changes.
     * A photo that was already downloaded as an album photo becomes the main one without a new download.
     * The previous main photo stays on the device as an album photo.
     * @return true if the tree changed
     */
    suspend fun sync(treeId: Int, profile: GeniProfile, personGedcomId: String): Boolean {
        val photo = mugshotToPhoto(profile)
        val links = store.photoLinks(treeId, profile.id)
        val main = links.firstOrNull { it.isMain }
        if (photo == null) return main?.takeIf { it.state == PhotoState.DOWNLOADED }?.let { remove(treeId, profile, personGedcomId, it) } ?: false
        val dir = host.mediaDir(treeId)
        fun present(link: PhotoLink?) = link?.fileName?.let { File(dir, it).isFile } == true
        if (main?.geniPhotoId == photo.id && present(main)) return false
        val known = links.firstOrNull { it.geniPhotoId == photo.id }
        val fileName = if (present(known)) known!!.fileName!! else fetch(treeId, photo) ?: return false
        return attach(treeId, profile.id, personGedcomId, photo, fileName, main, asMain = true)
    }

    /**
     * Downloads an album photo and attaches it to the person as a plain media (not primary).
     * @return true if it was added; false if it was already there or the person is not in the tree
     */
    suspend fun addAlbumPhoto(treeId: Int, geniProfileId: String, personGedcomId: String, photo: GeniPhoto): Boolean {
        val known = store.photoLinks(treeId, geniProfileId).firstOrNull { it.geniPhotoId == photo.id }
        if (known?.fileName?.let { File(host.mediaDir(treeId), it).isFile } == true) return false
        val fileName = fetch(treeId, photo) ?: return false
        return attach(treeId, geniProfileId, personGedcomId, photo, fileName, previous = null, asMain = false)
    }

    /** Downloads the photo to the tree's media folder; null if it has no usable URL. Throws on a failed download. */
    private suspend fun fetch(treeId: Int, photo: GeniPhoto): String? {
        val url = photo.urlFor(*SIZES) ?: return null
        val dir = host.mediaDir(treeId)
        // File work stays off the main thread; each call has its own staging file so quick double taps do not clash
        return withContext(Dispatchers.IO) {
            val temp = File.createTempFile("geni-${photo.id}-", ".download", dir)
            try {
                val type = download(url, temp)
                if (!looksLikeImage(temp)) throw java.io.IOException("Downloaded file is not an image")
                val fileName = "geni-${photo.id}.${extension(type, url)}"
                val target = File(dir, fileName)
                if (!temp.renameTo(target)) temp.copyTo(target, overwrite = true)
                fileName
            } finally {
                temp.delete()
            }
        }
    }

    private suspend fun attach(
        treeId: Int, profileId: String, personGedcomId: String, photo: GeniPhoto, fileName: String, previous: PhotoLink?, asMain: Boolean
    ): Boolean {
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
            true
        }
        if (!attached) return false
        previous?.takeIf { it.geniPhotoId != photo.id }?.let { store.savePhotoLink(it.copy(isMain = false)) }
        // A repaired album file keeps main status, read now (not before the download) so a main change meanwhile is not undone
        val stillMain = store.photoLinks(treeId, profileId).any { it.geniPhotoId == photo.id && it.isMain }
        store.savePhotoLink(PhotoLink(treeId, profileId, photo.id, null, asMain || stillMain, marker(photo), fileName, PhotoState.DOWNLOADED))
        return true
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
     * Geni confirmed the deletion of [geniPhotoId]: if it is the main photo of the person, removes it here (media, link, file
     * when unused), then looks up the profile with [fetchProfile] and, if Geni now names another main photo, syncs it.
     * A response that still names the deleted photo never brings it back. A failed lookup or download leaves the removal
     * as it is; the next refresh picks the replacement up.
     * @param onChanged called after the removal and again after a replacement arrived
     * @return true if the main photo was removed here
     */
    suspend fun mainDeleted(
        treeId: Int, geniProfileId: String, personGedcomId: String, geniPhotoId: String,
        fetchProfile: suspend () -> GeniProfile?, onChanged: () -> Unit
    ): Boolean {
        val link = store.photoLinks(treeId, geniProfileId).firstOrNull { it.geniPhotoId == geniPhotoId && it.isMain } ?: return false
        if (!removeLink(treeId, geniProfileId, personGedcomId, link)) return false
        onChanged()
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

    /** @param keepIfEdited leave everything as it is when the media carries a title or notes of the user */
    private suspend fun removeLink(treeId: Int, profileId: String, personGedcomId: String, link: PhotoLink, keepIfEdited: Boolean = false): Boolean {
        var kept = false
        var found = false
        val changed = host.edit(treeId) { gedcom ->
            val person = gedcom.people.orEmpty().firstOrNull { it.id == personGedcomId && GeniMapper.getGeniId(it) == profileId }
            val before = person?.media.orEmpty().size
            found = person?.media.orEmpty().any { GeniMapper.getTag(it, PHOTO_TAG) == link.geniPhotoId }
            if (keepIfEdited && person?.media.orEmpty().any { GeniMapper.getTag(it, PHOTO_TAG) == link.geniPhotoId && isEdited(it) }) {
                kept = true
                return@edit false
            }
            person?.let { it.media = it.media.orEmpty().filter { m -> GeniMapper.getTag(m, PHOTO_TAG) != link.geniPhotoId }.toMutableList() }
            person != null && person.media.size != before
        }
        // No inline media with the tag (e.g. made a shared media): the user owns it, so its link and file stay
        if (kept || !found) return false
        store.deletePhotoLink(treeId, profileId, link.geniPhotoId)
        deleteFileIfUnused(treeId, link.fileName)
        return changed
    }

    /** A media the user added a title or a note to (a GEDCOM media holds no sources of its own). */
    private fun isEdited(media: Media) =
        !media.title.isNullOrBlank() || media.notes.orEmpty().isNotEmpty() || media.noteRefs.orEmpty().isNotEmpty()

    /** Two profiles can share one Geni photo and so one file: it goes only when no photo link uses it any more. */
    private suspend fun deleteFileIfUnused(treeId: Int, fileName: String?) {
        if (fileName == null || store.photoLinks(treeId).any { it.fileName == fileName }) return
        withContext(Dispatchers.IO) { File(host.mediaDir(treeId), fileName).delete() }
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
