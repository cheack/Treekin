package app.familygem.geni.sync

import app.familygem.geni.TreeHost
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.GeniProfile
import app.familygem.geni.api.GeniUnion
import app.familygem.geni.map.EventFields
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.ProfileFields
import app.familygem.geni.map.ProfileGroup
import app.familygem.geni.map.UnionFields
import app.familygem.geni.map.UnionGroup
import app.familygem.geni.map.threeWay
import app.familygem.geni.map.value
import com.google.gson.Gson
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.folg.gedcom.model.Gedcom

/** A field group changed both in the app and on Geni: the two values as the user reads them. */
data class FieldConflict(val group: String, val mine: String, val geni: String)

/**
 * Shows and resolves the conflicts of a job stopped as [JobState.CONFLICT]: Geni's current version is applied to the record
 * except the groups the user keeps, and becomes the new snapshot; the next plan then sends only what the user kept.
 */
class ConflictResolver(
    private val client: GeniClient,
    private val store: SyncStore,
    private val host: TreeHost,
    private val queue: SyncQueue
) {

    private val gson = Gson()

    private companion object {
        /** One resolution at a time: each checks and moves the snapshot its tree change is based on. */
        val mutex = Mutex()
        const val CHANGED = "Geni changed again meanwhile; open the conflict again"
    }

    /** The groups in conflict, with the app's and Geni's values. Needs the network. */
    suspend fun details(job: UploadJob): List<FieldConflict> {
        val (link, raw) = fetch(job)
        if (isGone(link, raw)) return listOf(FieldConflict(GONE, "", ""))
        val snapshot = link.snapshot ?: return emptyList()
        return host.edit(job.treeId) { gedcom ->
            if (link.geniId.startsWith("profile-")) {
                val person = gedcom.people.orEmpty().firstOrNull { it.id == link.gedcomId } ?: return@edit emptyList()
                val local = ProfileFields.of(person)
                val geni = ProfileFields.ofSnapshot(raw) ?: return@edit emptyList()
                threeWay(ProfileFields.ofSnapshot(snapshot) ?: return@edit emptyList(), local, geni).conflicts
                    .sortedBy { it.ordinal }.map { FieldConflict(it.name, show(local.value(it), it == ProfileGroup.NAMES), show(geni.value(it), it == ProfileGroup.NAMES)) }
            } else {
                val family = gedcom.families.orEmpty().firstOrNull { it.id == link.gedcomId } ?: return@edit emptyList()
                val local = UnionFields.of(family)
                val geni = UnionFields.ofSnapshot(raw) ?: return@edit emptyList()
                threeWay(UnionFields.ofSnapshot(snapshot) ?: return@edit emptyList(), local, geni).conflicts
                    .sortedBy { it.ordinal }.map { FieldConflict(it.name, show(local.value(it)), show(geni.value(it))) }
            }
        }
    }

    /**
     * Applies Geni's version to the record except the groups in [keepMine], stores it as the snapshot
     * and plans the job again. Throws if Geni or the snapshot changed meanwhile (the user tries again).
     */
    suspend fun resolve(job: UploadJob, keepMine: Set<String>, shown: Set<String>? = null): Unit = mutex.withLock {
        val (link, raw) = fetch(job)
        if (isGone(link, raw)) {
            check(shown == setOf(GONE)) { CHANGED } // Deleted while another dialog was open: never removed by its Apply
            return@withLock resolveGone(job, link, keep = GONE in keepMine)
        }
        val snapshot = link.snapshot ?: error("no snapshot")
        // The snapshot may have moved during the fetch: never apply Geni's version over a newer resolution
        check(store.link(job.treeId, link.geniId)?.snapshot == snapshot) { CHANGED }
        host.edit(job.treeId) { gedcom -> apply(gedcom, link, snapshot, raw, keepMine, shown) }
        val updatedAt = if (link.geniId.startsWith("profile-")) gson.fromJson(raw, GeniProfile::class.java).updatedAt else link.geniUpdatedAt
        check(store.updateSnapshot(job.treeId, link.geniId, snapshot, raw, updatedAt)) { CHANGED }
        // The tree and the snapshot changed: the job must leave the conflict state even if the screen is closed now
        withContext(NonCancellable) {
            host.refreshScreens()
            queue.afterResolve(job.treeId, job.key)
        }
    }

    private fun isGone(link: GeniLink, raw: String) =
        link.geniId.startsWith("profile-") && gson.fromJson(raw, GeniProfile::class.java).deleted == true

    /**
     * A profile deleted or merged on Geni: the person stays as a local person (the tag stays, the link goes: never
     * uploaded again, as a new person neither), or leaves the tree. The job is then planned away.
     */
    private suspend fun resolveGone(job: UploadJob, link: GeniLink, keep: Boolean) {
        var emptied = emptyList<String>()
        var name: String? = null
        var removed = false
        host.edit(job.treeId) { gedcom ->
            val same = gedcom.people.orEmpty().filter { it.id == link.gedcomId }
            val person = same.firstOrNull()
            // The GEDCOM ID may have been given to another person meanwhile
            check(same.size <= 1 && (person == null || GeniMapper.getGeniId(person) == link.geniId)) { CHANGED }
            if (!keep && person != null) {
                name = FeedRecords.displayName(person)
                emptied = GoneProfiles.remove(gedcom, link.gedcomId)
                removed = true
            }
            queue.setLocal(job.treeId, LocalTree.capture(gedcom))
        }
        store.deleteLink(job.treeId, link.geniId)
        emptied.forEach { family -> store.linkByGedcomId(job.treeId, family)?.let { store.deleteLink(job.treeId, it.geniId) } }
        if (removed) FeedRecords.removed(store, job.treeId, FeedRecordKind.GONE_ON_GENI, link.geniId, name, System.currentTimeMillis())
        withContext(NonCancellable) {
            host.refreshScreens()
            queue.afterResolve(job.treeId, job.key)
        }
    }

    /** Throws before changing anything if the conflicts are not the [shown] ones (Geni changed again since the dialog). */
    private fun apply(gedcom: Gedcom, link: GeniLink, snapshot: String, raw: String, keepMine: Set<String>, shown: Set<String>?) {
        if (link.geniId.startsWith("profile-")) {
            val person = gedcom.people.orEmpty().firstOrNull { it.id == link.gedcomId } ?: error("the person is no longer in the tree")
            // Local edits outside the conflict stay too, also when Geni only reformatted the same field
            val result = threeWay(ProfileFields.ofSnapshot(snapshot)!!, ProfileFields.of(person), ProfileFields.ofSnapshot(raw)!!)
            check(shown == null || result.conflicts.map { it.name }.toSet() == shown) { CHANGED }
            val keep = (result.local - result.conflicts) + ProfileGroup.values().filter { it.name in keepMine }
            GeniMapper.applyProfile(gedcom, person, gson.fromJson(raw, GeniProfile::class.java), gson.fromJson(snapshot, GeniProfile::class.java), keep)
        } else {
            val family = gedcom.families.orEmpty().firstOrNull { it.id == link.gedcomId } ?: error("the family is no longer in the tree")
            val result = threeWay(UnionFields.ofSnapshot(snapshot)!!, UnionFields.of(family), UnionFields.ofSnapshot(raw)!!)
            check(shown == null || result.conflicts.map { it.name }.toSet() == shown) { CHANGED }
            val keep = (result.local - result.conflicts) + UnionGroup.values().filter { it.name in keepMine }
            GeniMapper.applyUnion(gedcom, family, gson.fromJson(raw, GeniUnion::class.java), gson.fromJson(snapshot, GeniUnion::class.java), keep) { null }
        }
        queue.setLocal(link.treeId, LocalTree.capture(gedcom))
    }

    private suspend fun fetch(job: UploadJob): Pair<GeniLink, String> {
        val geniId = job.geniId ?: error("no Geni ID")
        val link = store.link(job.treeId, geniId) ?: error("no link")
        val raw = if (geniId.startsWith("profile-")) client.profiles(listOf(geniId))[geniId]?.second
        else client.unions(listOf(geniId))[geniId]?.second
        return link to (raw ?: error("Geni did not return $geniId"))
    }

    /** A group value as text: parts joined (name parts by spaces), an empty value as a dash. */
    private fun show(value: Any?, name: Boolean = false): String = when (value) {
        null -> "—"
        is EventFields -> listOfNotNull(value.date, value.place).joinToString(", ").ifEmpty { "—" }
        is Boolean -> if (value) "deceased" else ""
        is List<*> -> value.map { show(it) }.filter { it.isNotEmpty() && it != "—" }.joinToString(if (name) " " else "; ").ifEmpty { "—" }
        else -> value.toString()
    }
}
