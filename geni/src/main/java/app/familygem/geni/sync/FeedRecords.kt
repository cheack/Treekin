package app.familygem.geni.sync

import android.util.Log
import app.familygem.geni.api.GeniProfile
import kotlinx.coroutines.CancellationException
import org.folg.gedcom.model.Person
import java.util.concurrent.TimeUnit

/** Writes the [FeedRecord]s of the Home feed. Never fails the sync step that calls it. */
object FeedRecords {

    /** Records older than this are dropped: the feed shows the last 90 days. */
    val KEEP_MILLIS: Long = TimeUnit.DAYS.toMillis(100)

    /** Records "profile added" for the loaded [profiles] created on Geni recently; [gedcomIds] maps Geni ID to person. */
    suspend fun added(store: SyncStore, treeId: Int, profiles: List<GeniProfile>, gedcomIds: Map<String, String>, nowMillis: Long) {
        val since = nowMillis - KEEP_MILLIS
        val records = profiles.mapNotNull { profile ->
            val created = profile.createdAt?.toLongOrNull()?.let { TimeUnit.SECONDS.toMillis(it) } ?: return@mapNotNull null
            val gedcomId = gedcomIds[profile.id] ?: return@mapNotNull null
            if (created < since || profile.deleted == true) null
            else FeedRecord(treeId, FeedRecordKind.ADDED, profile.id, gedcomId, null, created)
        }
        save(store, records, nowMillis)
    }

    /** Records that this app just created the profile on Geni. */
    suspend fun addedHere(store: SyncStore, treeId: Int, geniId: String, gedcomId: String, nowMillis: Long) =
        save(store, listOf(FeedRecord(treeId, FeedRecordKind.ADDED, geniId, gedcomId, null, nowMillis)), nowMillis)

    /** Records that the person of [geniId], named [name], left the tree. */
    suspend fun removed(store: SyncStore, treeId: Int, kind: FeedRecordKind, geniId: String, name: String?, nowMillis: Long) =
        save(store, listOf(FeedRecord(treeId, kind, geniId, null, name, nowMillis)), nowMillis)

    /** Also with no records: the store still drops the expired ones. */
    private suspend fun save(store: SyncStore, records: List<FeedRecord>, nowMillis: Long) {
        try {
            store.saveFeedRecords(records, nowMillis - KEEP_MILLIS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            try {
                Log.w("GeniSync", "Feed records not saved", e)
            } catch (ignored: RuntimeException) { // android.util.Log in plain unit tests
            }
        }
    }

    /** The person's main name as shown, without the GEDCOM surname slashes; null if the person has none. */
    fun displayName(person: Person): String? =
        person.names.orEmpty().firstOrNull()?.value?.replace("/", " ")?.replace(Regex("\\s+"), " ")?.trim()?.ifEmpty { null }
}
