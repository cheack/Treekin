package app.familygem.geni.sync

import app.familygem.geni.api.GeniProfile
import app.familygem.geni.api.GeniUnion
import app.familygem.geni.api.ImmediateFamily
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.ProfileFields
import app.familygem.geni.map.UnionFields
import app.familygem.geni.map.UnionGroup
import app.familygem.geni.map.FieldGroup
import app.familygem.geni.map.ThreeWay
import app.familygem.geni.map.filled
import app.familygem.geni.map.threeWay
import com.google.gson.Gson
import org.folg.gedcom.model.Family
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Person

/** Merges a loaded immediate family into a GEDCOM tree, without duplicating people or families. */
object FamilyMerger {

    /**
     * @param existing Links already stored for this tree, by Geni ID
     * @return Links of all merged profiles and unions, to be saved
     */
    fun merge(
        gedcom: Gedcom,
        treeId: Int,
        family: ImmediateFamily,
        profiles: List<GeniProfile>,
        unions: List<GeniUnion>,
        rawProfiles: Map<String, String>,
        rawUnions: Map<String, String>,
        existing: Map<String, GeniLink>,
        nowMillis: Long
    ): List<GeniLink> {
        gedcom.createIndexes()
        // Built once per merge; a link is trusted only if its record still carries the same Geni ID
        val taggedPeople = gedcom.people.orEmpty().mapNotNull { p -> GeniMapper.getGeniId(p)?.let { it to p } }.toMap()
        val taggedFamilies = gedcom.families.orEmpty().mapNotNull { f -> GeniMapper.getGeniId(f)?.let { it to f } }.toMap()
        var lastPerson = maxNumber(gedcom.people.orEmpty().map { it.id }, "I")
        var lastFamily = maxNumber(gedcom.families.orEmpty().map { it.id }, "F")
        val people = mutableMapOf<String, Person>()
        val links = mutableListOf<GeniLink>()
        for (profile in profiles) {
            val link = existing[profile.id]
            val person = link?.let { gedcom.getPerson(it.gedcomId) }?.takeIf { GeniMapper.getGeniId(it) == profile.id }
                ?: taggedPeople[profile.id]
                ?: Person().apply {
                    id = "I${++lastPerson}"
                    gedcom.addPerson(this)
                }
            // The previous Geni version counts only if the record is the linked one
            val linked = link != null && person.id == link.gedcomId
            val previous = if (linked) link!!.snapshot?.let { parse(it, GeniProfile::class.java) } else null
            val decision = if (linked) decide(link!!, ProfileFields.of(person), rawProfiles[profile.id], ProfileFields::ofSnapshot,
                { a, b, c -> threeWay(a, b, c) }, { it.filled() }) else Decision.NONE
            val keep = decision.keep
            if (keep != Keep.CONFLICT)
                GeniMapper.applyProfile(gedcom, person, profile, previous, decision.groups.filterIsInstance<FieldGroup>().toSet())
            GeniMapper.setGeniId(person, profile.id)
            people[profile.id] = person
            val isFocus = profile.id == family.focusId
            val snapshot = if (keep == Keep.CONFLICT) link!!.snapshot else rawProfiles[profile.id]
            links += GeniLink(
                treeId = treeId,
                geniId = profile.id,
                gedcomId = person.id,
                fullyLoaded = isFocus || link?.fullyLoaded == true,
                geniUpdatedAt = if (keep == Keep.CONFLICT) link!!.geniUpdatedAt else profile.updatedAt,
                snapshot = snapshot,
                loadedAtMillis = if (isFocus) nowMillis else link?.loadedAtMillis ?: 0,
                verifiedHash = keptHash(link, snapshot)
            )
        }
        val personFor = { geniId: String -> people[geniId] ?: taggedPeople[geniId] }
        for (union in unions) {
            val link = existing[union.id]
            val fam = link?.let { gedcom.getFamily(it.gedcomId) }?.takeIf { GeniMapper.getGeniId(it) == union.id }
                ?: taggedFamilies[union.id]
                ?: Family().apply {
                    id = "F${++lastFamily}"
                    gedcom.addFamily(this)
                }
            val linked = link != null && fam.id == link.gedcomId
            val previous = if (linked) link!!.snapshot?.let { parse(it, GeniUnion::class.java) } else null
            val decision = if (linked) decide(link!!, UnionFields.of(fam), rawUnions[union.id], UnionFields::ofSnapshot,
                { a, b, c -> threeWay(a, b, c) }, { it.filled() }) else Decision.NONE
            val keep = decision.keep
            // Partners and children are always linked; in a conflict marriage and divorce stay as they are
            val kept = if (keep == Keep.CONFLICT) UnionGroup.values().toSet() else decision.groups.filterIsInstance<UnionGroup>().toSet()
            GeniMapper.applyUnion(gedcom, fam, union, previous, keep = kept, personFor = personFor)
            GeniMapper.setGeniId(fam, union.id)
            val snapshot = if (keep == Keep.CONFLICT) link!!.snapshot else rawUnions[union.id]
            links += GeniLink(treeId, union.id, fam.id, true, null, snapshot, nowMillis, keptHash(link, snapshot))
        }
        return links
    }

    /** How a load treats a linked record: apply Geni's changes, or keep the local fields because they hold unsent edits. */
    private enum class Keep {
        NONE,
        /** Geni changed a field the user also changed: nothing applied, the old snapshot stays, the upload stops as a conflict. */
        CONFLICT,
        /** Added by the app without a snapshot and edited since: Geni fills only empty fields, its version becomes the snapshot. */
        LOCAL
    }

    /** [keep] and the field groups the mapper must leave as they are. */
    private class Decision(val keep: Keep, val groups: Set<Any> = emptySet()) {
        companion object {
            val NONE = Decision(Keep.NONE)
        }
    }

    /**
     * Three-way check of a linked record against its snapshot and Geni's version. Without a conflict Geni's changes are
     * applied except to the groups changed locally (Geni changed none of them, or to the same value; raw text Geni only
     * reformatted must not replace a local edit). Without a snapshot the hash of what the app sent tells whether the
     * record was edited since; then nobody knows which local fields are edits, and Geni only fills the empty ones.
     */
    private fun <F> decide(
        link: GeniLink, local: F, raw: String?, project: (String) -> F?,
        threeWay: (F, F, F) -> ThreeWay<*>, filled: (F) -> Set<*>
    ): Decision {
        raw ?: return Decision.NONE
        val geni = project(raw) ?: return Decision.NONE
        val snapshot = link.snapshot?.let(project)
        if (snapshot == null) {
            val edited = link.snapshot == null && link.verifiedHash != null && local.hashCode() != link.verifiedHash
            return if (edited) Decision(Keep.LOCAL, filled(local).filterNotNull().toSet()) else Decision.NONE
        }
        val result = threeWay(snapshot, local, geni)
        return if (result.conflicts.isEmpty()) Decision(Keep.NONE, result.local.filterNotNull().toSet()) else Decision(Keep.CONFLICT)
    }

    /** The verified hash holds only for the snapshot it was checked against: a new snapshot needs a new check. */
    private fun keptHash(link: GeniLink?, snapshot: String?): Int? = link?.verifiedHash?.takeIf { link.snapshot == snapshot }

    private val gson = Gson()

    private fun <T> parse(json: String, type: Class<T>): T? = runCatching { gson.fromJson(json, type) }.getOrNull()

    /** Highest number used in IDs like "I12" with this prefix, 0 if none. */
    private fun maxNumber(ids: List<String>, prefix: String): Int =
        ids.mapNotNull { it.removePrefix(prefix).toIntOrNull() }.maxOrNull() ?: 0
}
