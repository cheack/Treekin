package app.familygem.geni.sync

import app.familygem.geni.TreeHost
import app.familygem.geni.api.VideoTypes
import app.familygem.geni.api.GeniVideo
import app.familygem.geni.api.PhotoDownloader
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.PhotoDetails
import app.familygem.geni.map.PhotoField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.folg.gedcom.model.Media
import java.io.File

/**
 * Keeps the Geni videos of a person in the tree: each one downloaded on demand to the tree's media folder and attached to the
 * person as a media tagged with its Geni video ID ([VIDEO_TAG]). A video is tracked per profile, in the photo link
 * table (video IDs never clash with photo IDs); two profiles tagged in one video share the file.
 * Title, description and date use the same media tags as photos ([PhotoDetails]).
 * @param download saves the file at a URL to a file and returns its content type
 */
class VideoSync(
    private val store: SyncStore,
    private val host: TreeHost,
    /** Share one guard with the photos of the process (see [PhotoDeletionGuard]). */
    val guard: PhotoDeletionGuard = PhotoDeletionGuard(),
    private val feed: DownloadFeed = DownloadFeed(),
    private val download: suspend (String, File) -> String = PhotoDownloader(maxBytes = VideoTypes.MAX_DOWNLOAD_BYTES, accept = VideoTypes::isFile)::download
) {

    /** A downloaded file waiting in the media folder under a staging name, and the name it gets when published. */
    private class Staged(val file: File, val fileName: String)

    /**
     * Downloads the video unless a copy is already on the device (the same file serves every profile tagged in it) and
     * attaches it to the person. Nothing changes when the download fails. The staging file never outlives the call.
     * @return true if the tree changed; false if it was already there, has no file, was deleted or untagged meanwhile, or the person is not in the tree
     */
    suspend fun attach(treeId: Int, profileId: String, personGedcomId: String, video: GeniVideo): Boolean {
        if (guard.isDeleted(treeId, video.id)) return false
        val url = video.videoUrl ?: return false
        val dir = host.mediaDir(treeId)
        val links = store.photoLinks(treeId).filter { it.geniPhotoId == video.id }
        // A copy already on the device is not fetched again; the media is still attached if the person lost it (an unsaved tree)
        val knownFile = links.firstNotNullOfOrNull { link -> link.fileName?.takeIf { File(dir, it).isFile } }
        val temps = mutableListOf<File>()
        try {
            val staged = if (knownFile == null) stage(treeId, profileId, video, url, temps) else null
            return attachStaged(treeId, profileId, personGedcomId, video, staged, knownFile ?: staged!!.fileName)
        } finally {
            temps.forEach { it.delete() }
        }
    }

    private suspend fun stage(treeId: Int, profileId: String, video: GeniVideo, url: String, temps: MutableList<File>): Staged {
        val dir = host.mediaDir(treeId)
        return withContext(Dispatchers.IO) {
            val temp = File.createTempFile("geni-${video.id}-", ".download", dir)
            temps += temp
            feed.track(treeId, DownloadKind.VIDEO, profileId) { download(url, temp) }
            if (!VideoTypes.looksLikeMp4(temp)) throw java.io.IOException("Downloaded file is not an MP4 video")
            Staged(temp, "geni-${video.id}.mp4")
        }
    }

    /** Publishes the file, edits the tree and writes the link as one step under the tree's lock, after a last check of what Geni or the user did meanwhile. */
    private suspend fun attachStaged(treeId: Int, profileId: String, personGedcomId: String, video: GeniVideo, staged: Staged?, fileName: String): Boolean =
        guard.withTree(treeId) {
            if (guard.isDeleted(treeId, video.id)) return@withTree false
            // Checked again under the lock: a download that started before the untag or the delete must not bring the video back
            if (store.jobs(treeId).any { it.isActive() && it.geniId == video.id &&
                    (it.kind == JobKind.DELETE_VIDEO || (it.kind == JobKind.UNTAG_VIDEO && it.params.any { p -> p.first == "profile" && p.second == profileId })) }) return@withTree false
            if (staged != null) withContext(Dispatchers.IO) {
                val target = File(host.mediaDir(treeId), fileName)
                if (!staged.file.renameTo(target)) staged.file.copyTo(target, overwrite = true)
            } else if (!File(host.mediaDir(treeId), fileName).isFile) return@withTree false // The reused copy vanished meanwhile
            val details = if (editQueued(treeId, video.id)) null else details(video)
            var personFound = false
            val attached = host.edit(treeId) { gedcom ->
                val person = gedcom.people.orEmpty().firstOrNull { it.id == personGedcomId && GeniMapper.getGeniId(it) == profileId }
                    ?: return@edit false
                personFound = true
                val existing = person.media.orEmpty().firstOrNull { GeniMapper.getTag(it, VIDEO_TAG) == video.id }
                val changed = existing == null || existing.file != fileName
                existing?.file = fileName
                val media = existing ?: Media().apply {
                    file = fileName
                    setFileTag("FILE")
                    GeniMapper.setTag(this, VIDEO_TAG, video.id)
                    person.addMedia(this)
                }
                (details != null && PhotoDetails.apply(media, details)) || changed
            }
            if (!personFound) return@withTree false
            // The link is saved whenever the person has the media, also when nothing in the tree changed
            store.savePhotoLink(PhotoLink(treeId, profileId, video.id, null, false, marker(video), fileName, PhotoState.DOWNLOADED))
            attached
        }

    /**
     * Brings the title, description and date of the attached videos in line with the listed [videos], in every person
     * of the tree that has them. No download. A video whose own edit is still waiting for Geni and a deleted one are left alone.
     * @return how many videos changed in the tree
     */
    suspend fun refreshDetails(treeId: Int, videos: List<GeniVideo>): Int {
        var changed = 0
        for (video in videos) {
            val details = details(video)
            if (applyToMedia(treeId, video.id, skipIfEditQueued = true) { PhotoDetails.apply(it, details) }) changed++
        }
        return changed
    }

    /** Geni confirmed [video] after an edit of [sent] fields from here: its details replace the local ones. */
    suspend fun applyConfirmed(treeId: Int, video: GeniVideo, sent: Collection<PhotoField>): Boolean {
        val details = details(video)
        return applyToMedia(treeId, video.id) { PhotoDetails.apply(it, details, force = PhotoField.TITLE in sent) }
    }

    /** The user's edit of [fields], shown at once on every media of the video in the tree (the job to Geni is queued apart). */
    suspend fun applyOwnEdit(treeId: Int, videoId: String, details: PhotoDetails, fields: Collection<PhotoField>): Boolean =
        applyToMedia(treeId, videoId) { PhotoDetails.applyOwn(it, details, fields) }

    private suspend fun applyToMedia(treeId: Int, videoId: String, skipIfEditQueued: Boolean = false, change: (Media) -> Boolean): Boolean =
        guard.withTree(treeId) {
            if (guard.isDeleted(treeId, videoId)) return@withTree false
            if (skipIfEditQueued && editQueued(treeId, videoId)) return@withTree false
            host.edit(treeId) { gedcom ->
                var any = false
                for (person in gedcom.people.orEmpty()) for (media in person.media.orEmpty()) {
                    if (GeniMapper.getTag(media, VIDEO_TAG) == videoId && change(media)) any = true
                }
                any
            }
        }

    private suspend fun editQueued(treeId: Int, videoId: String) = store.jobs(treeId).any {
        it.kind == JobKind.UPDATE_VIDEO && it.geniId == videoId && it.state != JobState.DISMISSED
    }

    /**
     * Removes a video from the person: its media, the link and the file when nothing else uses it. A media the user gave
     * a title of their own or a note stays as their plain local copy ([keepIfEdited] leaves everything as it is instead).
     * @return true if the tree changed
     */
    suspend fun removeLocal(treeId: Int, profileId: String, personGedcomId: String, videoId: String, keepIfEdited: Boolean = false, detachIfEdited: Boolean = false): Boolean =
        guard.withTree(treeId) {
            val link = store.photoLinks(treeId, profileId).firstOrNull { it.geniPhotoId == videoId }
            var kept = false
            var found = false
            var detached = false
            val changed = host.edit(treeId) { gedcom ->
                val person = gedcom.people.orEmpty().firstOrNull { it.id == personGedcomId && GeniMapper.getGeniId(it) == profileId }
                val before = person?.media.orEmpty().size
                found = person?.media.orEmpty().any { GeniMapper.getTag(it, VIDEO_TAG) == videoId }
                if (keepIfEdited && person?.media.orEmpty().any { GeniMapper.getTag(it, VIDEO_TAG) == videoId && isEdited(it) }) {
                    kept = true
                    return@edit false
                }
                if (detachIfEdited) person?.media.orEmpty().filter { GeniMapper.getTag(it, VIDEO_TAG) == videoId && isEdited(it) }.forEach {
                    GeniMapper.setTag(it, VIDEO_TAG, null)
                    GeniMapper.setTag(it, DETACHED_TAG, "Y") // Still no photo candidate, though it has no link any more
                    detached = true
                }
                person?.let { it.media = it.media.orEmpty().filter { m -> GeniMapper.getTag(m, VIDEO_TAG) != videoId }.toMutableList() }
                person != null && (person.media.size != before || detached)
            }
            // No inline media with the tag (made a shared media): the user owns it, so its link and file stay
            if (kept || !found) return@withTree false
            store.deletePhotoLink(treeId, profileId, videoId)
            if (!detached) MediaFiles.deleteIfUnused(store, host, treeId, link?.fileName)
            changed
        }

    /**
     * Geni confirmed the deletion of [videoId] (already recorded in [guard]): removes it from every person of the tree
     * that has it, keeping what the user made their own.
     */
    suspend fun deletedOnGeni(treeId: Int, videoId: String): Int {
        var removed = 0
        for (link in store.photoLinks(treeId).filter { it.geniPhotoId == videoId }) {
            val person = hostPersonOf(treeId, link.geniProfileId) ?: continue
            if (removeLocal(treeId, link.geniProfileId, person, videoId, detachIfEdited = true)) removed++
        }
        return removed
    }

    private suspend fun hostPersonOf(treeId: Int, profileId: String): String? = store.link(treeId, profileId)?.gedcomId

    /**
     * Removes the videos of a profile that Geni no longer lists. Call it only with a listing that was read completely: a failed
     * or partial one must leave every cached copy alone. Not touched: videos waiting for upload or deletion and media the user made their own.
     * @return how many videos were removed
     */
    suspend fun reconcile(treeId: Int, profileId: String, personGedcomId: String, listed: List<GeniVideo>): Int {
        val ids = listed.map { it.id }.toSet()
        val gone = store.photoLinks(treeId, profileId).filter { isVideoId(it.geniPhotoId) && it.state == PhotoState.DOWNLOADED && it.geniPhotoId !in ids }
        var removed = 0
        for (link in gone) if (removeLocal(treeId, profileId, personGedcomId, link.geniPhotoId, keepIfEdited = true)) removed++
        return removed
    }

    /** A media the user gave a title of their own (not the one synced from Geni) or a note. */
    private fun isEdited(media: Media) =
        PhotoDetails.hasOwnTitle(media) || media.notes.orEmpty().isNotEmpty() || media.noteRefs.orEmpty().isNotEmpty()

    private fun UploadJob.isActive() = state == JobState.PENDING || state == JobState.SENDING

    companion object {
        /** Custom tag of a media that is a Geni video. */
        const val VIDEO_TAG = "_GENI_VIDEO_ID"

        /** Set on a media whose upload to Geni is queued: it is no photo candidate, and has no video ID yet. */
        const val QUEUED_TAG = "_GENI_VIDEO_QUEUED"

        /** The title, description and date Geni lists for [video]. */
        fun details(video: GeniVideo) = PhotoDetails(PhotoDetails.clean(video.title), PhotoDetails.clean(video.description), GeniMapper.gedcomDate(video.date))

        /** Set on a media that was a Geni video and is now the user's own: it never becomes a photo upload. */
        const val DETACHED_TAG = "_GENI_VIDEO_DETACHED"

        fun isVideoId(id: String) = id.startsWith("video-")

        /** URL path of the original without its signed query: it changes only when the file does. */
        fun marker(video: GeniVideo): String? = video.videoUrl?.substringBefore('?')
    }
}
