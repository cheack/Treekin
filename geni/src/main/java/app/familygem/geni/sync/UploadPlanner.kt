package app.familygem.geni.sync

import app.familygem.geni.api.geniId
import app.familygem.geni.map.GeniParams
import app.familygem.geni.map.ProfileFields
import app.familygem.geni.map.UnionFields
import com.google.gson.JsonParser

/**
 * Result of planning: jobs to insert or update by key (in upload order, with ID 0),
 * keys of jobs that are no longer needed, links found equal to their snapshot (with their new verified hash),
 * Geni IDs of links whose GEDCOM ID now belongs to another record (to delete), and orphan marks to set or clear.
 */
data class Plan(
    val upserts: List<UploadJob>,
    val deleteKeys: Set<String>,
    val verified: List<GeniLink>,
    val staleLinks: List<String> = emptyList(),
    /** Links whose record is missing from the tree (deleted locally). */
    val orphaned: List<String> = emptyList(),
    /** Orphaned links whose record is back. */
    val unorphaned: List<String> = emptyList()
)

/** What the link rules say about one record. */
internal sealed interface LinkMatch {
    /** The record is linked to [link]. */
    data class Linked(val link: GeniLink) : LinkMatch
    /** The record has no usable link (no link, or its tag does not match one). */
    data object None : LinkMatch
    /** An untagged record whose ID belonged to a deleted record: the link is [link], to remove. */
    data class Stale(val link: GeniLink) : LinkMatch
    /** An untagged record that may or may not be the linked one: nobody can tell. */
    data class Ambiguous(val link: GeniLink) : LinkMatch
}

/**
 * Link of a record: by its tag, or by its GEDCOM ID when the tag was lost (an unsaved edit after an add).
 * A GEDCOM ID is reused by the next new record after a delete. A link whose record was seen missing (orphaned)
 * belongs to the deleted record: an untagged record with its ID is new, whatever its name (a deleted record
 * cannot come back without its tag). A link never orphaned counts unless [sameRecord] finds the snapshot differs.
 * Shared by the planner and the check right before a photo is sent.
 */
internal fun matchLink(gedcomId: String, geniId: String?, prefix: String, byGeniId: (String) -> GeniLink?,
    byGedcomId: (String) -> GeniLink?, sameRecord: (String) -> Boolean): LinkMatch {
    if (geniId != null) return byGeniId(geniId)?.takeIf { it.gedcomId == gedcomId }?.let { LinkMatch.Linked(it) } ?: LinkMatch.None
    val link = byGedcomId(gedcomId)?.takeIf { it.geniId.startsWith(prefix) } ?: return LinkMatch.None
    return when {
        link.orphaned -> LinkMatch.Stale(link)
        link.snapshot == null || sameRecord(link.snapshot) -> LinkMatch.Linked(link)
        else -> LinkMatch.Ambiguous(link)
    }
}

/** True if the snapshot of a profile has the name of [person]. */
internal fun sameProfile(snapshot: String, person: LocalPerson) =
    ProfileFields.ofSnapshot(snapshot)?.let { it.given == person.fields.given && it.surname == person.fields.surname } == true

/** Compares a saved tree with the Geni snapshots of its records and works out the upload jobs. Pure: no I/O. */
object UploadPlanner {

    fun plan(treeId: Int, local: LocalTree, links: List<GeniLink>, jobs: List<UploadJob>,
        photoLinks: List<PhotoLink> = emptyList(), uploadPhotos: Boolean = true): Plan {
        val byGeniId = links.associateBy { it.geniId }
        val byGedcomId = links.associateBy { it.gedcomId }
        val labels = local.people.associate { it.gedcomId to it.label }
        val upserts = mutableListOf<UploadJob>()
        val deleteKeys = mutableSetOf<String>()
        val verified = mutableListOf<GeniLink>()
        val linkedKeys = mutableSetOf<String>() // Keys of the linked records still in the tree

        val staleLinks = mutableListOf<String>()

        val ambiguous = mutableSetOf<String>()

        /**
         * Link of a record by [matchLink]. An ambiguous record gets no new job and keeps its jobs (no update that could
         * overwrite another profile, no add that could duplicate this one); only its waiting photo jobs go (see below).
         */
        fun linkOf(gedcomId: String, geniId: String?, prefix: String, sameRecord: (String) -> Boolean): GeniLink? =
            when (val match = matchLink(gedcomId, geniId, prefix, byGeniId::get, byGedcomId::get, sameRecord)) {
                is LinkMatch.Linked -> match.link
                is LinkMatch.Stale -> { staleLinks += match.link.geniId; null }
                is LinkMatch.Ambiguous -> {
                    ambiguous += gedcomId
                    linkedKeys += (if (prefix == "profile-") "profile:" else "union:") + match.link.geniId
                    null
                }
                LinkMatch.None -> null
            }

        /** Compares one linked record with its snapshot. */
        fun check(link: GeniLink, key: String, hash: Int, params: () -> List<Pair<String, String>>?, job: (List<Pair<String, String>>) -> UploadJob) {
            linkedKeys += key
            if (link.verifiedHash == hash) return // Unchanged since it was found equal: keep its jobs as they are
            val wanted = params() ?: return // No usable snapshot: the next load brings one
            if (wanted.isEmpty()) {
                deleteKeys += key
                verified += link.copy(verifiedHash = hash)
            } else upserts += job(wanted)
        }

        val linkedPeople = mutableSetOf<String>()
        val peopleGeniIds = mutableMapOf<String, String>()
        val newPeople = mutableMapOf<String, LocalPerson>()
        val photoKeys = mutableSetOf<String>() // Keys of the photo jobs still wanted
        val mediaKeys = mutableSetOf<String>() // Keys of every photo that could be sent, whatever the setting
        for (person in local.people) {
            val link = linkOf(person.gedcomId, person.geniId, "profile-") { sameProfile(it, person) }
            when {
                link != null -> {
                    linkedPeople += person.gedcomId
                    peopleGeniIds[person.gedcomId] = link.geniId
                    val key = "profile:${link.geniId}"
                    val hash = person.fields.hashCode()
                    run {
                        val known = photoLinks.filter { it.geniProfileId == link.geniId }.mapNotNull { it.fileName }.toSet()
                        for (media in person.media.distinctBy { it.file }) {
                            if (media.geniPhotoId != null || media.file in known) continue
                            val photoKey = "photo:${person.gedcomId}:${media.file}"
                            mediaKeys += photoKey
                            if (!uploadPhotos) continue
                            photoKeys += photoKey
                            upserts += UploadJob(treeId = treeId, key = photoKey, kind = JobKind.ADD_PHOTO, geniId = link.geniId, personGedcomId = person.gedcomId,
                                params = listOfNotNull("file" to media.file, media.title?.let { "title" to it }), label = "Photo: ${media.file} (${person.label})", localHash = media.hashCode())
                        }
                    }
                    fun update(params: List<Pair<String, String>>) = UploadJob(treeId = treeId, key = key, kind = JobKind.UPDATE_PROFILE,
                        geniId = link.geniId, params = params, label = person.label, localHash = hash)
                    if (link.snapshot == null && link.verifiedHash != null && link.verifiedHash != hash) {
                        // Changed after an add whose snapshot could not be fetched (the link holds the hash of what was sent):
                        // an update without parameters only fetches it; the changed fields follow with the next plan
                        linkedKeys += key
                        upserts += update(emptyList())
                    } else check(link, key, hash, { link.snapshot?.let { ProfileFields.ofSnapshot(it) }?.let { GeniParams.profile(it, person.fields) } }, ::update)
                }
                person.geniId == null && person.gedcomId !in ambiguous -> newPeople[person.gedcomId] = person
                // A tag without a link: skipped until a load links it again
            }
        }

        for (family in local.families) {
            val link = linkOf(family.gedcomId, family.geniId, "union-") { snapshot ->
                // Same union if every linked partner is one of its partners on Geni
                val geniPartners = runCatching { JsonParser.parseString(snapshot).asJsonObject.getAsJsonArray("partners")?.map { geniId(it.asString) } }
                    .getOrNull().orEmpty()
                val localPartners = family.partners.mapNotNull { peopleGeniIds[it] }
                localPartners.isNotEmpty() && geniPartners.containsAll(localPartners)
            } ?: continue
            val key = "union:${link.geniId}"
            val hash = family.fields.hashCode()
            val label = family.partners.map { labels[it] ?: it }.joinToString(" & ").ifEmpty { family.gedcomId }
            check(link, key, hash, { link.snapshot?.let { UnionFields.ofSnapshot(it) }?.let { GeniParams.union(it, family.fields) } }) {
                UploadJob(treeId = treeId, key = key, kind = JobKind.UPDATE_UNION, geniId = link.geniId, params = it, label = label, localHash = hash)
            }
        }

        // New people connected to Geni through a family, directly or through other new people: parents of a chain first
        val planned = linkedMapOf<String, UploadJob>()
        do {
            var changed = false
            for (family in local.families) {
                val members = family.partners + family.children
                if (members.none { it in linkedPeople || it in planned }) continue
                fun add(gedcomId: String, kind: JobKind, anchors: List<String>, modifier: String? = null) {
                    val person = newPeople[gedcomId] ?: return
                    if (gedcomId in planned) return
                    planned[gedcomId] = UploadJob(
                        treeId = treeId, key = "add:$gedcomId", kind = kind, personGedcomId = gedcomId, familyGedcomId = family.gedcomId,
                        anchors = anchors, params = GeniParams.profile(ProfileFields.EMPTY, person.fields, isNew = true) +
                            listOfNotNull(modifier?.let { UploadJob.MODIFIER_PARAM to it }),
                        label = person.label, localHash = person.fields.hashCode()
                    )
                    changed = true
                }
                for (partner in family.partners) add(partner, JobKind.ADD_PARTNER,
                    family.children.map { "child:$it" } + family.partners.filter { it != partner }.map { "partner:$it" })
                for (child in family.children) add(child, JobKind.ADD_CHILD, family.partners.map { "partner:$it" }, family.lineages[child])
            }
        } while (changed)
        upserts += planned.values

        // Jobs whose record is gone (deleted locally: not uploaded), linked meanwhile or no longer connected
        for (job in jobs) {
            if (job.kind == JobKind.DELETE_PROFILE || job.kind == JobKind.DELETE_PHOTO || job.kind == JobKind.UPDATE_PHOTO ||
                job.kind == JobKind.TAG_PHOTO || job.kind == JobKind.UNTAG_PHOTO || job.kind == JobKind.SET_MAIN_PHOTO) continue // The record is gone on purpose, or the job was queued by the user
            val gone = when {
                job.key.startsWith("add:") -> job.key.removePrefix("add:") !in planned
                // With the setting off only the waiting ones go; a failed or dismissed one stays, so it is not planned and sent again later
                // An ambiguous person's waiting photo jobs go: the destination is not certain, and a later save plans them again
                // once the match is resolved. A failed, dismissed or sending one stays: it may already be on Geni
                job.key.startsWith("photo:") && (job.personGedcomId ?: job.key.split(':').getOrNull(1)) in ambiguous -> job.state == JobState.PENDING
                job.key.startsWith("photo:") -> if (uploadPhotos) job.key !in mediaKeys else job.key !in mediaKeys || job.state == JobState.PENDING
                else -> job.key !in linkedKeys
            }
            if (gone) deleteKeys += job.key
        }
        // Orphan marks: a link whose record is missing now, or back again
        val present = local.people.map { it.gedcomId }.toSet() + local.families.map { it.gedcomId }
        val orphaned = links.filter { !it.orphaned && it.gedcomId !in present }.map { it.geniId }
        val unorphaned = links.filter { it.orphaned && it.geniId !in staleLinks && it.gedcomId in present && it.gedcomId !in ambiguous }.map { it.geniId }
        return Plan(upserts, deleteKeys, verified, staleLinks, orphaned, unorphaned)
    }
}
