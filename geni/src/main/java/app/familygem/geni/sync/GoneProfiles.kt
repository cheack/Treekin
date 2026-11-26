package app.familygem.geni.sync

import app.familygem.geni.map.ProfileFields
import org.folg.gedcom.model.EventFact
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Person

/** People whose Geni profile was deleted or merged into another one. */
object GoneProfiles {

    /** Facts that map to Geni fields, each at most once. */
    private val MAPPED_FACTS = setOf("SEX", "BIRT", "BAPM", "DEAT", "BURI", "OCCU")

    /**
     * True if the person holds something the user may want to keep: edits of Geni fields not uploaded yet,
     * or data Geni does not have (notes, sources, media, on the person or on its facts; facts Geni does not map).
     */
    fun hasLocalWork(person: Person, link: GeniLink): Boolean {
        val facts = person.eventsFacts.orEmpty()
        val localData = person.notes.orEmpty().isNotEmpty() || person.noteRefs.orEmpty().isNotEmpty() ||
                person.sourceCitations.orEmpty().isNotEmpty() || person.media.orEmpty().isNotEmpty() || person.mediaRefs.orEmpty().isNotEmpty() ||
                facts.any(::hasAttachments) ||
                facts.any { it.tag !in MAPPED_FACTS } || facts.groupBy { it.tag }.any { it.value.size > 1 } ||
                // Geni's occupation has no date or place
                facts.any { it.tag == "OCCU" && (!it.date.isNullOrBlank() || !it.place.isNullOrBlank()) } ||
                // Geni has one main name and one birth name
                person.names.orEmpty().partition { it.type == "birth" }.let { (birth, other) -> birth.size > 1 || other.size > 1 }
        if (localData) return true
        val local = ProfileFields.of(person)
        val snapshot = link.snapshot?.let { ProfileFields.ofSnapshot(it) }
        return if (snapshot != null) snapshot.resolvedAgainst(snapshot) != local.resolvedAgainst(snapshot) else link.verifiedHash != null && link.verifiedHash != local.hashCode()
    }

    /**
     * Removes a person from the tree and from its families, and the families this left without anyone, unless they
     * hold data of their own (notes, sources, media, events Geni does not have); returns the IDs of the removed families.
     */
    fun remove(gedcom: Gedcom, personId: String): List<String> {
        gedcom.people = gedcom.people.orEmpty().filter { it.id != personId }.toMutableList()
        val emptied = mutableListOf<String>()
        for (family in gedcom.families.orEmpty()) {
            val members = family.husbandRefs.orEmpty() + family.wifeRefs.orEmpty() + family.childRefs.orEmpty()
            if (members.none { it.ref == personId }) continue
            family.husbandRefs = family.husbandRefs.orEmpty().filter { it.ref != personId }.toMutableList()
            family.wifeRefs = family.wifeRefs.orEmpty().filter { it.ref != personId }.toMutableList()
            family.childRefs = family.childRefs.orEmpty().filter { it.ref != personId }.toMutableList()
            val facts = family.eventsFacts.orEmpty()
            val ownData = family.notes.orEmpty().isNotEmpty() || family.noteRefs.orEmpty().isNotEmpty() ||
                    family.sourceCitations.orEmpty().isNotEmpty() || family.media.orEmpty().isNotEmpty() || family.mediaRefs.orEmpty().isNotEmpty() ||
                    // Events Geni does not have (it has one marriage and one divorce), or with notes or sources
                    facts.any { it.tag != "MARR" && it.tag != "DIV" } || facts.groupBy { it.tag }.any { it.value.size > 1 } ||
                    facts.any(::hasAttachments)
            if (members.all { it.ref == personId } && !ownData) emptied += family.id
        }
        gedcom.families = gedcom.families.orEmpty().filter { it.id !in emptied }.toMutableList()
        gedcom.createIndexes()
        return emptied
    }

    private fun hasAttachments(fact: EventFact) = fact.notes.orEmpty().isNotEmpty() || fact.noteRefs.orEmpty().isNotEmpty() ||
            fact.sourceCitations.orEmpty().isNotEmpty() || fact.media.orEmpty().isNotEmpty() || fact.mediaRefs.orEmpty().isNotEmpty()
}
