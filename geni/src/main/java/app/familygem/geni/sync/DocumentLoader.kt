package app.familygem.geni.sync

import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.GeniDocument
import java.io.File

/** A document of a profile on Geni and whether a copy is on the device; [hasFile] is false for a text document, which cannot be downloaded. */
data class DocumentEntry(val document: GeniDocument, val downloaded: Boolean, val hasFile: Boolean)

/** The documents as listed now, and how many documents gone from Geni were removed from the device meanwhile. */
data class DocumentListing(val entries: List<DocumentEntry>, val removed: Int)

/** Lists a profile's Geni documents and downloads chosen ones into the tree, on demand. */
class DocumentLoader(
    private val client: GeniClient,
    private val store: SyncStore,
    private val documents: DocumentSync,
    private val mediaDir: (Int) -> File,
    private val feed: DownloadFeed = DownloadFeed()
) {

    /**
     * The documents as Geni lists them now, each marked with its local state; needs the network. A listing that fails (offline,
     * a page missing) throws and changes nothing, so every cached copy stays. Only after a complete listing are the documents gone from Geni removed
     * from the person (see [DocumentSync.reconcile]) and the details of the others refreshed, with no download.
     */
    suspend fun list(treeId: Int, geniProfileId: String, personGedcomId: String): DocumentListing =
        feed.track(treeId, DownloadKind.DOCUMENT_LIST, geniProfileId) {
            val listed = client.documents(geniProfileId)
            val removed = documents.reconcile(treeId, geniProfileId, personGedcomId, listed)
            documents.refreshDetails(treeId, listed)
            val links = store.photoLinks(treeId, geniProfileId).associateBy { it.geniPhotoId }
            val dir = mediaDir(treeId)
            DocumentListing(listed.map { doc ->
                DocumentEntry(doc, links[doc.id]?.fileName?.let { File(dir, it).isFile } == true, doc.fileUrl != null)
            }, removed)
        }

    /** Downloads one document into the person's media; true if it was added (see [DocumentSync.attach]). */
    suspend fun download(treeId: Int, geniProfileId: String, personGedcomId: String, document: GeniDocument): Boolean =
        documents.attach(treeId, geniProfileId, personGedcomId, document)
}
