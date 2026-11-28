package app.familygem.geni.sync

import app.familygem.geni.TreeHost
import app.familygem.geni.api.DocumentTypes
import app.familygem.geni.api.GeniDocument
import app.familygem.geni.api.PhotoDownloader
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.PhotoDetails
import app.familygem.geni.map.PhotoField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.folg.gedcom.model.Media
import java.io.File

/**
 * Keeps the Geni documents of a person in the tree: each one downloaded on demand to the tree's media folder and attached to the
 * person as a media tagged with its Geni document ID ([DOCUMENT_TAG]). A document is tracked per profile, in the photo link
 * table (document IDs never clash with photo IDs); two profiles tagged in one document share the file.
 * A text document has no file to download, so it is never attached.
 * Title, description and date use the same media tags as photos ([PhotoDetails]).
 * @param download saves the file at a URL to a file and returns its content type
 */
class DocumentSync(
    private val store: SyncStore,
    private val host: TreeHost,
    /** Share one guard with the photos of the process (see [PhotoDeletionGuard]). */
    val guard: PhotoDeletionGuard = PhotoDeletionGuard(),
    private val feed: DownloadFeed = DownloadFeed(),
    private val download: suspend (String, File) -> String = PhotoDownloader(maxBytes = DocumentTypes.MAX_DOWNLOAD_BYTES, accept = DocumentTypes::isFile)::download
) {

    /** A downloaded file waiting in the media folder under a staging name, and the name it gets when published. */
    private class Staged(val file: File, val fileName: String)

    /**
     * Downloads the document unless a copy is already on the device (the same file serves every profile tagged in it) and
     * attaches it to the person. Nothing changes when the download fails. The staging file never outlives the call.
     * @return true if the tree changed; false if it was already there, has no file, was deleted or untagged meanwhile, or the person is not in the tree
     */
    suspend fun attach(treeId: Int, profileId: String, personGedcomId: String, doc: GeniDocument): Boolean {
        if (guard.isDeleted(treeId, doc.id)) return false
        val url = doc.fileUrl ?: return false
        val dir = host.mediaDir(treeId)
        val links = store.photoLinks(treeId).filter { it.geniPhotoId == doc.id }
        // A copy already on the device is not fetched again; the media is still attached if the person lost it (an unsaved tree)
        val knownFile = links.firstNotNullOfOrNull { link -> link.fileName?.takeIf { File(dir, it).isFile } }
        val temps = mutableListOf<File>()
        try {
            val staged = if (knownFile == null) stage(treeId, profileId, doc, url, temps) else null
            return attachStaged(treeId, profileId, personGedcomId, doc, staged, knownFile ?: staged!!.fileName)
        } finally {
            temps.forEach { it.delete() }
        }
    }

    private suspend fun stage(treeId: Int, profileId: String, doc: GeniDocument, url: String, temps: MutableList<File>): Staged {
        val dir = host.mediaDir(treeId)
        return withContext(Dispatchers.IO) {
            val temp = File.createTempFile("geni-${doc.id}-", ".download", dir)
            temps += temp
            val type = feed.track(treeId, DownloadKind.DOCUMENT, profileId) { download(url, temp) }
            if (!DocumentTypes.looksLikeDocument(temp)) throw java.io.IOException("Downloaded file is not a PDF or an image")
            Staged(temp, "geni-${doc.id}.${DocumentTypes.extension(type, url)}")
        }
    }

    /** Publishes the file, edits the tree and writes the link as one step under the tree's lock, after a last check of what Geni or the user did meanwhile. */
    private suspend fun attachStaged(treeId: Int, profileId: String, personGedcomId: String, doc: GeniDocument, staged: Staged?, fileName: String): Boolean =
        guard.withTree(treeId) {
            if (guard.isDeleted(treeId, doc.id)) return@withTree false
            // Checked again under the lock: a download that started before the untag or the delete must not bring the document back
            if (store.jobs(treeId).any { it.isActive() && it.geniId == doc.id &&
                    (it.kind == JobKind.DELETE_DOCUMENT || (it.kind == JobKind.UNTAG_DOCUMENT && it.params.any { p -> p.first == "profile" && p.second == profileId })) }) return@withTree false
            if (staged != null) withContext(Dispatchers.IO) {
                val target = File(host.mediaDir(treeId), fileName)
                if (!staged.file.renameTo(target)) staged.file.copyTo(target, overwrite = true)
            } else if (!File(host.mediaDir(treeId), fileName).isFile) return@withTree false // The reused copy vanished meanwhile
            val details = if (editQueued(treeId, doc.id)) null else details(doc)
            var personFound = false
            val attached = host.edit(treeId) { gedcom ->
                val person = gedcom.people.orEmpty().firstOrNull { it.id == personGedcomId && GeniMapper.getGeniId(it) == profileId }
                    ?: return@edit false
                personFound = true
                val existing = person.media.orEmpty().firstOrNull { GeniMapper.getTag(it, DOCUMENT_TAG) == doc.id }
                val changed = existing == null || existing.file != fileName
                existing?.file = fileName
                val media = existing ?: Media().apply {
                    file = fileName
                    setFileTag("FILE")
                    GeniMapper.setTag(this, DOCUMENT_TAG, doc.id)
                    person.addMedia(this)
                }
                (details != null && PhotoDetails.apply(media, details)) || changed
            }
            if (!personFound) return@withTree false
            // The link is saved whenever the person has the media, also when nothing in the tree changed
            store.savePhotoLink(PhotoLink(treeId, profileId, doc.id, null, false, marker(doc), fileName, PhotoState.DOWNLOADED))
            attached
        }

    /**
     * Brings the title, description and date of the attached documents in line with the listed [documents], in every person
     * of the tree that has them. No download. A document whose own edit is still waiting for Geni and a deleted one are left alone.
     * @return how many documents changed in the tree
     */
    suspend fun refreshDetails(treeId: Int, documents: List<GeniDocument>): Int {
        var changed = 0
        for (doc in documents) {
            val details = details(doc)
            if (applyToMedia(treeId, doc.id, skipIfEditQueued = true) { PhotoDetails.apply(it, details) }) changed++
        }
        return changed
    }

    /** Geni confirmed [doc] after an edit of [sent] fields from here: its details replace the local ones. */
    suspend fun applyConfirmed(treeId: Int, doc: GeniDocument, sent: Collection<PhotoField>): Boolean {
        val details = details(doc)
        return applyToMedia(treeId, doc.id) { PhotoDetails.apply(it, details, force = PhotoField.TITLE in sent) }
    }

    /** The user's edit of [fields], shown at once on every media of the document in the tree (the job to Geni is queued apart). */
    suspend fun applyOwnEdit(treeId: Int, documentId: String, details: PhotoDetails, fields: Collection<PhotoField>): Boolean =
        applyToMedia(treeId, documentId) { PhotoDetails.applyOwn(it, details, fields) }

    private suspend fun applyToMedia(treeId: Int, documentId: String, skipIfEditQueued: Boolean = false, change: (Media) -> Boolean): Boolean =
        guard.withTree(treeId) {
            if (guard.isDeleted(treeId, documentId)) return@withTree false
            if (skipIfEditQueued && editQueued(treeId, documentId)) return@withTree false
            host.edit(treeId) { gedcom ->
                var any = false
                for (person in gedcom.people.orEmpty()) for (media in person.media.orEmpty()) {
                    if (GeniMapper.getTag(media, DOCUMENT_TAG) == documentId && change(media)) any = true
                }
                any
            }
        }

    private suspend fun editQueued(treeId: Int, documentId: String) = store.jobs(treeId).any {
        it.kind == JobKind.UPDATE_DOCUMENT && it.geniId == documentId && it.state != JobState.DISMISSED
    }

    /**
     * Removes a document from the person: its media, the link and the file when nothing else uses it. A media the user gave
     * a title of their own or a note stays as their plain local copy ([keepIfEdited] leaves everything as it is instead).
     * @return true if the tree changed
     */
    suspend fun removeLocal(treeId: Int, profileId: String, personGedcomId: String, documentId: String, keepIfEdited: Boolean = false, detachIfEdited: Boolean = false): Boolean =
        guard.withTree(treeId) {
            val link = store.photoLinks(treeId, profileId).firstOrNull { it.geniPhotoId == documentId }
            var kept = false
            var found = false
            var detached = false
            val changed = host.edit(treeId) { gedcom ->
                val person = gedcom.people.orEmpty().firstOrNull { it.id == personGedcomId && GeniMapper.getGeniId(it) == profileId }
                val before = person?.media.orEmpty().size
                found = person?.media.orEmpty().any { GeniMapper.getTag(it, DOCUMENT_TAG) == documentId }
                if (keepIfEdited && person?.media.orEmpty().any { GeniMapper.getTag(it, DOCUMENT_TAG) == documentId && isEdited(it) }) {
                    kept = true
                    return@edit false
                }
                if (detachIfEdited) person?.media.orEmpty().filter { GeniMapper.getTag(it, DOCUMENT_TAG) == documentId && isEdited(it) }.forEach {
                    GeniMapper.setTag(it, DOCUMENT_TAG, null)
                    GeniMapper.setTag(it, DETACHED_TAG, "Y") // Still no photo candidate, though it has no link any more
                    detached = true
                }
                person?.let { it.media = it.media.orEmpty().filter { m -> GeniMapper.getTag(m, DOCUMENT_TAG) != documentId }.toMutableList() }
                person != null && (person.media.size != before || detached)
            }
            // No inline media with the tag (made a shared media): the user owns it, so its link and file stay
            if (kept || !found) return@withTree false
            store.deletePhotoLink(treeId, profileId, documentId)
            if (!detached) MediaFiles.deleteIfUnused(store, host, treeId, link?.fileName)
            changed
        }

    /**
     * Geni confirmed the deletion of [documentId] (already recorded in [guard]): removes it from every person of the tree
     * that has it, keeping what the user made their own.
     */
    suspend fun deletedOnGeni(treeId: Int, documentId: String): Int {
        var removed = 0
        for (link in store.photoLinks(treeId).filter { it.geniPhotoId == documentId }) {
            val person = hostPersonOf(treeId, link.geniProfileId) ?: continue
            if (removeLocal(treeId, link.geniProfileId, person, documentId, detachIfEdited = true)) removed++
        }
        return removed
    }

    private suspend fun hostPersonOf(treeId: Int, profileId: String): String? = store.link(treeId, profileId)?.gedcomId

    /**
     * Removes the documents of a profile that Geni no longer lists. Call it only with a listing that was read completely: a failed
     * or partial one must leave every cached copy alone. Not touched: documents waiting for upload or deletion and media the user made their own.
     * @return how many documents were removed
     */
    suspend fun reconcile(treeId: Int, profileId: String, personGedcomId: String, listed: List<GeniDocument>): Int {
        val ids = listed.map { it.id }.toSet()
        val gone = store.photoLinks(treeId, profileId).filter { isDocumentId(it.geniPhotoId) && it.state == PhotoState.DOWNLOADED && it.geniPhotoId !in ids }
        var removed = 0
        for (link in gone) if (removeLocal(treeId, profileId, personGedcomId, link.geniPhotoId, keepIfEdited = true)) removed++
        return removed
    }

    /** A media the user gave a title of their own (not the one synced from Geni) or a note. */
    private fun isEdited(media: Media) =
        PhotoDetails.hasOwnTitle(media) || media.notes.orEmpty().isNotEmpty() || media.noteRefs.orEmpty().isNotEmpty()

    private fun UploadJob.isActive() = state == JobState.PENDING || state == JobState.SENDING

    companion object {
        /** Custom tag of a media that is a Geni document. */
        const val DOCUMENT_TAG = "_GENI_DOCUMENT_ID"

        /** Set on a media whose upload to Geni is queued: it is no photo candidate, and has no document ID yet. */
        const val QUEUED_TAG = "_GENI_DOCUMENT_QUEUED"

        /** The title, description and date Geni lists for [doc]. */
        fun details(doc: GeniDocument) = PhotoDetails(PhotoDetails.clean(doc.title), PhotoDetails.clean(doc.description), GeniMapper.gedcomDate(doc.date))

        /** Set on a media that was a Geni document and is now the user's own: it never becomes a photo upload. */
        const val DETACHED_TAG = "_GENI_DOCUMENT_DETACHED"

        fun isDocumentId(id: String) = id.startsWith("document-")

        /** URL path of the original without its signed query: it changes only when the file does. */
        fun marker(doc: GeniDocument): String? = doc.fileUrl?.substringBefore('?')
    }
}
