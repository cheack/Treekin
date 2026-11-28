package app.familygem.geni.sync

import app.familygem.geni.api.GeniAccountMismatchException
import app.familygem.geni.api.GeniApiException
import app.familygem.geni.api.GeniAuthRequiredException
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.GeniNotConfirmedException
import app.familygem.geni.api.GeniSessionChangedException
import app.familygem.geni.api.geniId
import kotlinx.coroutines.CancellationException

/** What a merge of two Geni profiles came to. */
sealed interface MergeResult {
    /** Merged on Geni, and the tree now shows the survivor. */
    data object Merged : MergeResult

    /** Merged on Geni, but the tree could not be refreshed yet: [ProfileMerger.reconcile] retries it with reads only. */
    data object RefreshNeeded : MergeResult

    /** Geni filed a merge request for this pair (the survivor lists the absorbed profile in `requested_merges`). Both records stay. */
    data object Requested : MergeResult

    /** Geni answered, yet the absorbed profile is still there and no request for the pair shows: both records stay. */
    data object NotCompleted : MergeResult

    /** No answer, and the profiles could not be read to tell: nothing is known, so nothing changed here. */
    data object Unknown : MergeResult

    /** Geni refused the merge (for example no right to edit); nothing happened. */
    data class Rejected(val message: String) : MergeResult
    data object NeedsLogin : MergeResult

    /** The login is another account than the one that confirmed: nothing was sent. */
    data object AccountChanged : MergeResult

    /** One of the profiles is not in the tree (any more), or they are the same: nothing was sent. */
    data object NotLinked : MergeResult
}

/**
 * Merges a duplicate profile of the tree into another one on Geni, then brings the tree in line.
 * The tree changes only after Geni confirms the merge by the read-back of the absorbed profile.
 */
class ProfileMerger(private val client: GeniClient, private val store: SyncStore, private val loader: GeniLoader) {

    /**
     * Sends one merge of [absorbedId] into [survivorId], pinned to the account [confirmedBy].
     * When the answer is lost or Geni fails after the request, the profiles are read back before anything is reported: no second POST.
     */
    suspend fun merge(treeId: Int, survivorId: String, absorbedId: String, confirmedBy: String?): MergeResult {
        if (survivorId == absorbedId || store.link(treeId, survivorId) == null || store.link(treeId, absorbedId) == null) return MergeResult.NotLinked
        try {
            client.mergeProfiles(survivorId, absorbedId, confirmedBy)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeniAuthRequiredException) {
            return MergeResult.NeedsLogin
        } catch (e: GeniAccountMismatchException) {
            return MergeResult.AccountChanged
        } catch (e: GeniSessionChangedException) {
            return MergeResult.AccountChanged
        } catch (e: GeniNotConfirmedException) {
            return MergeResult.AccountChanged
        } catch (e: GeniApiException) {
            // A client error means Geni did not take the request; a server error may have done it
            if (e.code in 400..499) return MergeResult.Rejected(e.message.orEmpty())
        } catch (e: Exception) {
            // The request may have reached Geni: read back
        }
        return reconcile(treeId, survivorId, absorbedId)
    }

    /** Reads both profiles; if the absorbed one is merged away, updates the tree. Reads only, so it is safe to repeat. */
    suspend fun reconcile(treeId: Int, survivorId: String, absorbedId: String): MergeResult {
        val read = try {
            client.profiles(listOf(absorbedId, survivorId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeniAuthRequiredException) {
            return MergeResult.NeedsLogin
        } catch (e: Exception) {
            return MergeResult.Unknown
        }
        val absorbed = read[absorbedId]?.first ?: return MergeResult.Unknown
        // Still alive: a request for this very pair, or nothing known
        if (absorbed.deleted != true) {
            val requested = read[survivorId]?.first?.requestedMerges.orEmpty().any { geniId(it) == absorbedId }
            return if (requested) MergeResult.Requested else MergeResult.NotCompleted
        }
        // Deleted for another reason, or merged into another profile, is not this merge
        if (absorbed.mergedInto?.let { geniId(it) } != survivorId) return MergeResult.NotCompleted
        // The absorbed person leaves the tree (kept when it holds local work), the survivor is refreshed
        val gone = loader.load(treeId, absorbedId)
        if (gone !is LoadResult.Gone) return if (gone == LoadResult.NeedsLogin) MergeResult.NeedsLogin else MergeResult.RefreshNeeded
        return when (loader.load(treeId, survivorId)) {
            is LoadResult.Loaded -> MergeResult.Merged
            LoadResult.NeedsLogin -> MergeResult.NeedsLogin
            else -> MergeResult.RefreshNeeded
        }
    }
}
