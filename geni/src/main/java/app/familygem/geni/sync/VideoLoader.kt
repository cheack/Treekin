package app.familygem.geni.sync

import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.GeniVideo
import java.io.File

/** A video of a profile on Geni and whether a copy is on the device; [playable] is false while Geni gave no playback URL (listed as unavailable). */
data class VideoEntry(val video: GeniVideo, val downloaded: Boolean, val playable: Boolean)

/** The videos as listed now, and how many videos gone from Geni were removed from the device meanwhile. */
data class VideoListing(val entries: List<VideoEntry>, val removed: Int)

/** Lists a profile's Geni videos and downloads chosen ones into the tree, on demand. */
class VideoLoader(
    private val client: GeniClient,
    private val store: SyncStore,
    private val videos: VideoSync,
    private val mediaDir: (Int) -> File,
    private val feed: DownloadFeed = DownloadFeed()
) {

    /**
     * The videos as Geni lists them now, each marked with its local state; needs the network. A listing that fails (offline,
     * a page missing) throws and changes nothing, so every cached copy stays. Only after a complete listing are the videos gone from Geni removed
     * from the person (see [VideoSync.reconcile]) and the details of the others refreshed, with no download.
     */
    suspend fun list(treeId: Int, geniProfileId: String, personGedcomId: String): VideoListing =
        feed.track(treeId, DownloadKind.VIDEO_LIST, geniProfileId) {
            val listed = client.videos(geniProfileId)
            val removed = videos.reconcile(treeId, geniProfileId, personGedcomId, listed)
            videos.refreshDetails(treeId, listed)
            val links = store.photoLinks(treeId, geniProfileId).associateBy { it.geniPhotoId }
            val dir = mediaDir(treeId)
            VideoListing(listed.map { video ->
                VideoEntry(video, links[video.id]?.fileName?.let { File(dir, it).isFile } == true, video.videoUrl != null)
            }, removed)
        }

    /** Downloads one video into the person's media; true if it was added (see [VideoSync.attach]). */
    suspend fun download(treeId: Int, geniProfileId: String, personGedcomId: String, video: GeniVideo): Boolean =
        videos.attach(treeId, geniProfileId, personGedcomId, video)
}
