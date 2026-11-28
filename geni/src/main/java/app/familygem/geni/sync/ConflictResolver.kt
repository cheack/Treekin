package app.familygem.geni.sync

import app.familygem.geni.TreeHost
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.GeniProfile
import app.familygem.geni.api.GeniUnion
import app.familygem.geni.map.EventFields
import app.familygem.geni.map.FieldGroup
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.LocaleGroup
import app.familygem.geni.map.LocaleName
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
                val base = ProfileFields.ofSnapshot(snapshot) ?: return@edit emptyList()
                val local = ProfileFields.of(person).resolvedAgainst(base)
                val geni = ProfileFields.ofSnapshot(raw)?.let { it.resolvedAgainst(it) } ?: return@edit emptyList()
                // The same given name split differently: the first and middle names tell the two versions apart
                val split = local.given == geni.given && local.givenParts != geni.givenParts
                threeWay(base, local, geni).conflicts
                    .sortedWith(compareBy<FieldGroup>({ (it as? ProfileGroup)?.ordinal ?: Int.MAX_VALUE }, { it.name })).map {
                        val name = it == ProfileGroup.NAMES
                        val splitHere = if (it is LocaleGroup) localeSplit(local.locales[it.locale], geni.locales[it.locale]) else split
                        FieldConflict(it.name, show(local.value(it), name, splitHere), show(geni.value(it), name, splitHere))
                    }
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
        withContext(NonCancellable) {
            host.refreshScreens()
            queue.afterResolve(job.treeId, job.key)
        }
        // Last: a cancellation here may skip only this optional history record, never the resolution above
        if (removed) FeedRecords.removed(store, job.treeId, FeedRecordKind.GONE_ON_GENI, link.geniId, name, System.currentTimeMillis())
    }

    /** Throws before changing anything if the conflicts are not the [shown] ones (Geni changed again since the dialog). */
    private fun apply(gedcom: Gedcom, link: GeniLink, snapshot: String, raw: String, keepMine: Set<String>, shown: Set<String>?) {
        if (link.geniId.startsWith("profile-")) {
            val person = gedcom.people.orEmpty().firstOrNull { it.id == link.gedcomId } ?: error("the person is no longer in the tree")
            // Local edits outside the conflict stay too, also when Geni only reformatted the same field
            val result = threeWay(ProfileFields.ofSnapshot(snapshot)!!, ProfileFields.of(person), ProfileFields.ofSnapshot(raw)!!)
            check(shown == null || result.conflicts.map { it.name }.toSet() == shown) { CHANGED }
            val keep = (result.local - result.conflicts) + result.conflicts.filter { it.name in keepMine }
            GeniMapper.applyProfile(gedcom, person, gson.fromJson(raw, GeniProfile::class.java), gson.fromJson(snapshot, GeniProfile::class.java), keep)
        } else {
            val family = gedcom.families.orEmpty().firstOrNull { it.id == link.gedcomId } ?: error("the family is no longer in the tree")
            val result = threeWay(UnionFields.ofSnapshot(snapshot)!!, UnionFields.of(family), UnionFields.ofSnapshot(raw)!!)
            check(shown == null || result.conflicts.map { it.name }.toSet() == shown) { CHANGED }
            val keep = (result.local - result.conflicts) + UnionGroup.values().filter { it.name in keepMine }
            GeniMapper.applyUnion(gedcom, family, gson.fromJson(raw, GeniUnion::class.java), gson.fromJson(snapshot, GeniUnion::class.java), keep, GeniMapper.peopleByGeniId(gedcom))
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

    /** Date, place text and the structured parts the place text does not hold (street lines, coordinates). */
    private fun showEvent(event: EventFields): String {
        val location = event.locationParts
        val street = listOfNotNull(location["street_address1"], location["street_address2"], location["street_address3"]).joinToString(", ")
        val coordinates = listOfNotNull(location["latitude"]?.let { "lat $it" }, location["longitude"]?.let { "lon $it" }).joinToString(", ")
        return listOfNotNull(event.date, event.place, street.takeIf { it.isNotEmpty() }, coordinates.takeIf { it.isNotEmpty() }?.let { "($it)" })
            .joinToString(", ").ifEmpty { "—" }
    }

    /** True when the two versions of a language have the same given name split into first and middle names differently. */
    private fun localeSplit(a: LocaleName?, b: LocaleName?) =
        a?.given == b?.given && (a?.get(LocaleName.FIRST) != b?.get(LocaleName.FIRST) || a?.get(LocaleName.MIDDLE) != b?.get(LocaleName.MIDDLE))

    /** A name in another language: title, given name, last name and suffix, then the other fields with their meaning. */
    private fun showLocale(name: LocaleName, split: Boolean): String {
        // The same given name split differently: the first and middle names tell the two versions apart
        val given = if (split) "${name.given} (${name[LocaleName.FIRST] ?: "—"} / ${name[LocaleName.MIDDLE] ?: "—"})" else name.given
        val full = listOfNotNull(name[LocaleName.TITLE], given, name[LocaleName.LAST], name[LocaleName.SUFFIX]).joinToString(" ")
        val more = listOfNotNull(
            name[LocaleName.MAIDEN]?.let { "born $it" }, name[LocaleName.DISPLAY]?.let { "display name $it" },
            name[LocaleName.NICKNAMES]?.let { "nicknames $it" }
        )
        return (listOf(full).filter { it.isNotEmpty() } + more).joinToString(", ").ifEmpty { "—" }
    }

    /** A group value as text: parts joined (name parts by spaces), an empty value as a dash. */
    private fun show(value: Any?, name: Boolean = false, split: Boolean = false): String = when (value) {
        null -> "—"
        is EventFields -> showEvent(value)
        is LocaleName -> showLocale(value, split)
        is Boolean -> if (value) "deceased" else ""
        is app.familygem.geni.map.GivenNameParts -> if (split) "(${value.first ?: "—"} / ${value.middle ?: "—"})" else "" // Else the given name shows them
        "" -> "—"
        is List<*> -> value.map { show(it, split = split) }.filter { it.isNotEmpty() && it != "—" }.joinToString(if (name) " " else "; ").ifEmpty { "—" }
        else -> value.toString()
    }
}
