package app.familygem.geni.sync

import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.GeniPhoto
import kotlinx.coroutines.CancellationException
import java.io.File

/** A photo of a profile's Geni album and whether a copy is on the device. */
data class AlbumEntry(val photo: GeniPhoto, val downloaded: Boolean, val isMain: Boolean)

/** The album as listed now, and how many photos deleted on Geni were removed from the device meanwhile. */
data class Album(val entries: List<AlbumEntry>, val removed: Int)

/** Lists a profile's Geni photos and downloads chosen ones into the tree. */
class AlbumLoader(
    private val client: GeniClient,
    private val store: SyncStore,
    private val photos: PhotoSync,
    private val mediaDir: (Int) -> File
) {

    /**
     * The album as Geni lists it now, each photo marked with its local state; needs the network.
     * Photos deleted on Geni are removed from the person first (see [PhotoSync.reconcileAlbum]).
     */
    suspend fun list(treeId: Int, geniProfileId: String, personGedcomId: String): Album {
        val photos = client.photos(geniProfileId)
        val removed = this.photos.reconcileAlbum(treeId, geniProfileId, personGedcomId, photos)
        val links = store.photoLinks(treeId, geniProfileId).associateBy { it.geniPhotoId }
        val dir = mediaDir(treeId)
        return Album(photos.map { photo ->
            val link = links[photo.id]
            AlbumEntry(photo, link?.fileName?.let { File(dir, it).isFile } == true, link?.isMain == true)
        }, removed)
    }

    /**
     * Downloads one photo into the person's media.
     * A photo whose deletion on Geni is still queued is skipped, as Geni may keep listing it until the delete runs.
     * @return true if it was added
     */
    suspend fun download(treeId: Int, geniProfileId: String, personGedcomId: String, photo: GeniPhoto): Boolean {
        val deleting = store.jobs(treeId).any {
            it.kind == JobKind.DELETE_PHOTO && it.geniId == photo.id && (it.state == JobState.PENDING || it.state == JobState.SENDING)
        }
        return !deleting && photos.addAlbumPhoto(treeId, geniProfileId, personGedcomId, photo)
    }

    /**
     * Downloads every photo of the album that is not on the device; a failed photo is reported and skipped.
     * Photos deleted on Geni are removed from the device first.
     * @return how many photos were added
     */
    suspend fun downloadAll(treeId: Int, geniProfileId: String, personGedcomId: String, onError: (GeniPhoto, Exception) -> Unit = { _, _ -> }): Int {
        var added = 0
        for (entry in list(treeId, geniProfileId, personGedcomId).entries) {
            if (entry.downloaded) continue
            try {
                if (download(treeId, geniProfileId, personGedcomId, entry.photo)) added++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(entry.photo, e)
            }
        }
        return added
    }
}
