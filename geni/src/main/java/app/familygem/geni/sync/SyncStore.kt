package app.familygem.geni.sync

import kotlinx.coroutines.flow.Flow

/**
 * A FamilyGem tree that mirrors part of Geni.
 * @param rootGeniId The profile the tree starts from; it moves when Geni merges that profile into another
 * @param ownerGeniId The profile of the Geni account that made the tree; never moves, and only this account may sign in while the tree exists
 */
data class GeniTreeInfo(val treeId: Int, val rootGeniId: String, val prefetchDepth: Int = 3, val ownerGeniId: String = rootGeniId)

/**
 * Link between a Geni profile or union and a GEDCOM record of a tree.
 * @param fullyLoaded The immediate family of this profile has been loaded
 * @param snapshot Raw Geni JSON of the object as last loaded
 * @param loadedAtMillis When the immediate family was last loaded, 0 if never
 * @param verifiedHash Hash of the local projection last found equal to [snapshot]; the upload planner skips the record while it matches
 * @param orphaned The record was missing from a saved tree (deleted locally): its GEDCOM ID may now belong to another record
 */
data class GeniLink(
    val treeId: Int,
    val geniId: String,
    val gedcomId: String,
    val fullyLoaded: Boolean,
    val geniUpdatedAt: String?,
    val snapshot: String?,
    val loadedAtMillis: Long,
    val verifiedHash: Int? = null,
    val orphaned: Boolean = false
)

/** State of a photo's local copy. */
enum class PhotoState { DOWNLOADED, PENDING_DOWNLOAD, PENDING_UPLOAD, PENDING_DELETE }

/**
 * Link between a Geni photo of a profile and a GEDCOM media record (`OBJE`) of a tree.
 * @param gedcomMediaId ID of the `OBJE`, null while the photo is not in the tree yet
 * @param isMain The photo is the profile's main photo on Geni
 * @param marker Change marker of the Geni photo (the URL path without its query), never the signed URL itself
 * @param fileName Name of the local file in the tree's media folder, null while not downloaded
 */
data class PhotoLink(
    val treeId: Int,
    val geniProfileId: String,
    val geniPhotoId: String,
    val gedcomMediaId: String?,
    val isMain: Boolean,
    val marker: String?,
    val fileName: String?,
    val state: PhotoState
)

/** What a [FeedRecord] tells. */
enum class FeedRecordKind {
    /** The profile was created on Geni (its `created_at`, or the moment this app added it). */
    ADDED,
    /** The refresh found the profile deleted or merged on Geni, and the person left the tree. */
    GONE_ON_GENI,
    /** The user deleted the profile on Geni from this app. */
    DELETED_ON_GENI,
    /** The user removed the person from this device only. */
    REMOVED_FROM_DEVICE
}

/**
 * An event of the Home feed that the tree itself cannot tell: a profile created on Geni, or a person who left the tree.
 * @param gedcomId The person in the tree (added profiles); after a removal it may belong to someone else
 * @param name The person's name when it left the tree (removals), since the person is gone
 * @param atMillis When it happened
 */
data class FeedRecord(val treeId: Int, val kind: FeedRecordKind, val geniId: String, val gedcomId: String?, val name: String?, val atMillis: Long)

/** Geni metadata of the trees, kept apart from upstream settings. */
interface SyncStore {
    suspend fun tree(treeId: Int): GeniTreeInfo?
    suspend fun saveTree(info: GeniTreeInfo)
    /** Moves the root of an existing tree row only if it is still [oldRoot]; false if nothing changed. */
    suspend fun updateRoot(treeId: Int, oldRoot: String, newRoot: String): Boolean
    suspend fun trees(): List<GeniTreeInfo>
    suspend fun link(treeId: Int, geniId: String): GeniLink?
    suspend fun links(treeId: Int): List<GeniLink>
    suspend fun unionLinks(treeId: Int): List<GeniLink>
    suspend fun saveLinks(links: List<GeniLink>)
    suspend fun linkByGedcomId(treeId: Int, gedcomId: String): GeniLink?
    suspend fun deleteLink(treeId: Int, geniId: String)
    /** Sets the verified hash only if the link still has this GEDCOM ID and snapshot; true if it did. */
    suspend fun setVerifiedHash(treeId: Int, geniId: String, gedcomId: String, snapshot: String?, hash: Int): Boolean
    /**
     * Replaces only the snapshot and its update time, and clears the verified hash,
     * but only if the snapshot is still [expected]; true if it was.
     */
    suspend fun updateSnapshot(treeId: Int, geniId: String, expected: String?, snapshot: String, geniUpdatedAt: String?): Boolean
    suspend fun setOrphaned(treeId: Int, geniId: String, orphaned: Boolean)
    /** Photo links of a tree. */
    suspend fun photoLinks(treeId: Int): List<PhotoLink>
    /** Photo links of one profile. */
    suspend fun photoLinks(treeId: Int, geniProfileId: String): List<PhotoLink>
    suspend fun savePhotoLink(link: PhotoLink)
    suspend fun deletePhotoLink(treeId: Int, geniProfileId: String, geniPhotoId: String)
    /** Keeps feed records (one per tree, kind and profile, the last wins) and drops those older than [keepSinceMillis]. */
    suspend fun saveFeedRecords(records: List<FeedRecord>, keepSinceMillis: Long)
    /** Feed records of a tree at or after [sinceMillis]. */
    suspend fun feedRecords(treeId: Int, sinceMillis: Long): List<FeedRecord>
    /** Deletes the tree with its links, photo links, jobs and feed records. */
    suspend fun deleteTree(treeId: Int)

    /** Upload jobs of a tree in upload order. */
    suspend fun jobs(treeId: Int): List<UploadJob>
    suspend fun allJobs(): List<UploadJob>
    /** Every job of every tree in upload order, emitted again after each insert, change or delete. */
    fun observeJobs(): Flow<List<UploadJob>>
    /** Inserts a job with ID 0, else replaces the job with that ID; returns the ID. */
    suspend fun saveJob(job: UploadJob): Long
    suspend fun deleteJob(id: Long)
    /** Deletes the job only if it still has this version (no newer change was planned meanwhile); true if deleted. */
    suspend fun deleteJobIfVersion(id: Long, version: Int): Boolean
}
