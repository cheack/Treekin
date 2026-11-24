package app.familygem.geni.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers, for the life of the process, which Geni photos Geni confirmed deleted, so a profile response fetched before
 * the deletion or a download already in flight cannot bring the photo back. One instance is shared by everything that
 * attaches or removes photos (uploader, profile loader, album loader).
 *
 * Confirmation and the final attachment of a photo (file, media, links) run under one lock per tree: whichever comes
 * first wins, and the other one cleans up or is skipped. Network work stays outside the lock.
 * The record is not persisted: it covers readers that outlive the queue job in this process only.
 */
class PhotoDeletionGuard {

    private val deleted = ConcurrentHashMap.newKeySet<Pair<Int, String>>()
    private val locks = ConcurrentHashMap<Int, Mutex>()

    fun isDeleted(treeId: Int, geniPhotoId: String) = (treeId to geniPhotoId) in deleted

    /** Records the confirmed deletion; waits for an attachment of the same tree that is running now. */
    suspend fun confirm(treeId: Int, geniPhotoId: String) = withTree(treeId) { deleted.add(treeId to geniPhotoId) }

    /** Runs [block] holding the tree's lock. Not reentrant: [block] must not call this again for the same tree. */
    suspend fun <T> withTree(treeId: Int, block: suspend () -> T): T = locks.getOrPut(treeId) { Mutex() }.withLock { block() }
}
