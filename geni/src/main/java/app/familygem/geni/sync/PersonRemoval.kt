package app.familygem.geni.sync

import app.familygem.geni.TreeHost
import app.familygem.geni.map.GeniMapper
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Person

/** Takes a person out of a Geni tree on this device only; nothing is sent to Geni. */
class PersonRemoval(private val store: SyncStore, private val host: TreeHost, private val now: () -> Long = System::currentTimeMillis) {

    /**
     * Removes the person of [geniId], and the families this leaves empty, from the tree and drops their links
     * and waiting jobs (an edit, or a queued delete on Geni: the user chose this device only). The root is never removed.
     * @param why What the Home feed tells: removed from this device, or deleted on Geni by the user
     * @return false if the tree has no such person, or it is the root
     */
    suspend fun removeFromTree(treeId: Int, geniId: String, why: FeedRecordKind = FeedRecordKind.REMOVED_FROM_DEVICE): Boolean {
        if (store.tree(treeId)?.rootGeniId == geniId) return false
        val link = store.link(treeId, geniId) ?: return false
        var emptied: List<String>? = null
        var name: String? = null
        host.edit(treeId) { gedcom ->
            val person = find(gedcom, link.gedcomId, geniId)
            if (person != null) {
                name = FeedRecords.displayName(person)
                emptied = GoneProfiles.remove(gedcom, person.id)
            }
        }
        val families = emptied ?: return false
        store.deleteLink(treeId, geniId)
        families.forEach { family -> store.linkByGedcomId(treeId, family)?.let { store.deleteLink(treeId, it.geniId) } }
        store.jobs(treeId).filter { it.key == "profile:$geniId" || it.key == "delete:$geniId" }.forEach { store.deleteJob(it.id) }
        FeedRecords.removed(store, treeId, why, geniId, name, now())
        host.refreshScreens()
        return true
    }

    /** The person under the linked GEDCOM ID, or, if the ID was edited since, the only person with this Geni tag. */
    private fun find(gedcom: Gedcom, gedcomId: String, geniId: String): Person? {
        val people = gedcom.people.orEmpty()
        people.firstOrNull { it.id == gedcomId && GeniMapper.getGeniId(it) == geniId }?.let { return it }
        return people.filter { GeniMapper.getGeniId(it) == geniId }.singleOrNull()
    }
}
