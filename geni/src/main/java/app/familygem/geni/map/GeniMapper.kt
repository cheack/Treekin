package app.familygem.geni.map

import app.familygem.geni.api.GeniDate
import app.familygem.geni.api.GeniEvent
import app.familygem.geni.api.GeniLocation
import app.familygem.geni.api.GeniProfile
import app.familygem.geni.api.GeniUnion
import app.familygem.geni.api.geniId
import org.folg.gedcom.model.ChildRef
import org.folg.gedcom.model.EventFact
import org.folg.gedcom.model.ExtensionContainer
import org.folg.gedcom.model.Family
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.GedcomTag
import org.folg.gedcom.model.Name
import org.folg.gedcom.model.ParentFamilyRef
import org.folg.gedcom.model.Person
import org.folg.gedcom.model.SpouseFamilyRef
import org.folg.gedcom.model.SpouseRef
import org.folg.gedcom.parser.ModelParser

/**
 * Maps Geni profiles and unions into the GEDCOM model.
 * Updates existing records in place and leaves alone everything Geni does not have.
 */
object GeniMapper {

    const val GENI_ID_TAG = "_GENI_ID"
    const val ABOUT_TAG = "_GENI_ABOUT"
    const val FIRST_NAME_TAG = "_GENI_FIRST_NAME"
    const val MIDDLE_NAME_TAG = "_GENI_MIDDLE_NAME"

    /** Geni's display name override; an empty value means none (a missing tag: not known, a record from before). */
    const val DISPLAY_NAME_TAG = "_GENI_DISPLAY_NAME"

    /** Tags kept for the sync and edited in the person editor, not as facts. */
    val INTERNAL_TAGS = setOf(FIRST_NAME_TAG, MIDDLE_NAME_TAG, DISPLAY_NAME_TAG)

    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    /** GEDCOM date like "12 MAR 1960", "JUN 2012", "2014" or "ABT 1960"; null without a year. */
    fun gedcomDate(date: GeniDate?): String? {
        val year = date?.year ?: return null
        val month = date.month?.takeIf { it in 1..12 }?.let { MONTHS[it - 1] }
        val day = date.day?.takeIf { month != null && it in 1..31 }
        val text = listOfNotNull(day?.toString(), month, year.toString()).joinToString(" ")
        return if (date.circa == true) "ABT $text" else text
    }

    /** Place text from the non-blank location parts, from the most specific. */
    fun place(location: GeniLocation?): String? {
        location ?: return null
        return listOf(location.placeName, location.city, location.county, location.state, location.country)
            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .joinToString(", ").takeIf { it.isNotEmpty() }
    }

    /**
     * Applies a Geni profile to a person, as a three-way merge: a field changes only if Geni changed it since [previous],
     * the Geni version of the last load (null on the first load: every field is applied).
     * Facts are updated in place, keeping their notes, sources and media. Groups in [keep] are left as they are.
     */
    fun applyProfile(gedcom: Gedcom, person: Person, profile: GeniProfile, previous: GeniProfile? = null, keep: Set<ProfileGroup> = emptySet()) {
        fun changed(group: ProfileGroup, field: (GeniProfile) -> Any?) = group !in keep && (previous == null || field(previous) != field(profile))
        if (changed(ProfileGroup.NAMES) { listOf(clean(it.firstName), clean(it.middleName), it.lastName, it.maidenName, it.suffix, it.nicknames) }) {
            updateNames(person, profile)
            setGivenNameParts(person, GivenNameParts(profile.firstName, profile.middleName))
        }
        if (changed(ProfileGroup.DISPLAY_NAME) { clean(it.displayName) }) setTag(person, DISPLAY_NAME_TAG, clean(profile.displayName) ?: "")
        if (profile.gender != null && changed(ProfileGroup.SEX) { it.gender })
            setFact(person, "SEX", value = if (profile.gender == "male") "M" else if (profile.gender == "female") "F" else "U")
        else if (person.eventsFacts.orEmpty().none { it.tag == "SEX" })
            setFact(person, "SEX", value = "U")
        if (changed(ProfileGroup.BIRTH) { it.birth }) setEvent(person, "BIRT", profile.birth)
        if (changed(ProfileGroup.BAPTISM) { it.baptism }) setEvent(person, "BAPM", profile.baptism)
        if (changed(ProfileGroup.OCCUPATION) { it.occupation }) {
            val occupation = profile.occupation?.takeIf { it.isNotBlank() }
            // Geni has only the text: a local date or place of the occupation stays
            val fact = person.eventsFacts.orEmpty().firstOrNull { it.tag == "OCCU" }
            if (occupation == null) removeFacts(person, "OCCU")
            else if (fact != null) fact.value = occupation
            else setFact(person, "OCCU", value = occupation)
        }
        if (changed(ProfileGroup.DEATH) { listOf(it.death, it.isAlive, it.causeOfDeath) }) {
            val dead = profile.isAlive == false || !profile.causeOfDeath.isNullOrBlank()
            val date = gedcomDate(profile.death?.date)
            val place = place(profile.death?.location)
            if (date == null && place == null && !dead) removeFacts(person, "DEAT")
            else setFact(person, "DEAT", value = if (date == null && place == null) "Y" else null, date = date, place = place,
                cause = profile.causeOfDeath?.takeIf { it.isNotBlank() })
        }
        if (changed(ProfileGroup.BURIAL) { it.burial }) setEvent(person, "BURI", profile.burial)
        if (changed(ProfileGroup.ABOUT) { it.aboutMe }) setTag(person, ABOUT_TAG, profile.aboutMe?.takeIf { it.isNotBlank() })
    }

    /** Updates the main name and the birth (maiden) name in place, keeping their sub-records. */
    private fun updateNames(person: Person, profile: GeniProfile) {
        val wanted = names(profile)
        val current = person.names.orEmpty()
        val main = current.firstOrNull { it.type != "birth" } ?: Name().also { person.addName(it) }
        main.value = wanted[0].value
        main.suffix = wanted[0].suffix
        main.nickname = wanted[0].nickname
        val birthName = current.firstOrNull { it.type == "birth" }
        val wantedBirth = wanted.getOrNull(1)
        when {
            wantedBirth == null -> if (birthName != null) person.names = person.names.filter { it !== birthName }
            birthName != null -> birthName.value = wantedBirth.value
            else -> person.addName(wantedBirth)
        }
    }

    /** Sets the date and place of the first fact with this tag, or removes the facts when Geni has neither. */
    private fun setEvent(container: Any, tag: String, event: GeniEvent?) {
        val date = gedcomDate(event?.date)
        val place = place(event?.location)
        if (date == null && place == null) removeFacts(container, tag)
        else setFact(container, tag, date = date, place = place)
    }

    private fun setFact(container: Any, tag: String, value: String? = null, date: String? = null, place: String? = null, cause: String? = null) {
        val facts = facts(container)
        val fact = facts.firstOrNull { it.tag == tag } ?: EventFact().also {
            it.tag = tag
            setFacts(container, facts + it)
        }
        fact.value = value
        fact.date = date
        fact.place = place
        if (tag == "DEAT") fact.cause = cause
    }

    /**
     * Removes the facts with this tag. A birth, baptism, burial, marriage or occupation fact that holds local data
     * (notes, sources, media) is emptied instead: Geni does not have that data. An empty fact projects as no value.
     * Death and divorce facts are removed anyway: an empty one would still mean "dead" or "divorced".
     */
    private fun removeFacts(container: Any, tag: String) {
        val kept = when (tag) {
            // Geni has only the occupation's text: a local date or place is local data too, and stays
            "OCCU" -> facts(container).filter { it.tag == tag && (hasLocalData(it) || it.date != null || it.place != null) }
                .onEach { it.value = null }
            in EMPTIABLE -> facts(container).filter { it.tag == tag && hasLocalData(it) }.onEach { it.value = null; it.date = null; it.place = null }
            else -> emptyList()
        }
        setFacts(container, facts(container).filter { it.tag != tag || it in kept })
    }

    private val EMPTIABLE = setOf("BIRT", "BAPM", "BURI", "MARR", "OCCU")

    private fun hasLocalData(fact: EventFact) = fact.notes.orEmpty().isNotEmpty() || fact.noteRefs.orEmpty().isNotEmpty() ||
            fact.sourceCitations.orEmpty().isNotEmpty() || fact.media.orEmpty().isNotEmpty() || fact.mediaRefs.orEmpty().isNotEmpty()

    private fun facts(container: Any): List<EventFact> = when (container) {
        is Person -> container.eventsFacts.orEmpty()
        is Family -> container.eventsFacts.orEmpty()
        else -> emptyList()
    }

    private fun setFacts(container: Any, facts: List<EventFact>) = when (container) {
        is Person -> container.eventsFacts = facts.toMutableList()
        is Family -> container.eventsFacts = facts.toMutableList()
        else -> Unit
    }

    private fun names(profile: GeniProfile): List<Name> {
        val given = listOfNotNull(profile.firstName, profile.middleName).filter { it.isNotBlank() }.joinToString(" ")
        val main = Name().apply {
            value = nameValue(given, profile.lastName)
            suffix = profile.suffix?.takeIf { it.isNotBlank() }
            nickname = profile.nicknames?.filter { it.isNotBlank() }?.joinToString(", ")?.takeIf { it.isNotEmpty() }
        }
        val maiden = profile.maidenName?.takeIf { it.isNotBlank() && it != profile.lastName }
            ?.let { Name().apply { value = nameValue(given, it); type = "birth" } }
        return listOfNotNull(main, maiden)
    }

    private fun nameValue(given: String, surname: String?): String =
        if (surname.isNullOrBlank()) given else "$given /$surname/".trim()

    /** Links partners and children (never removes links) and merges marriage and divorce like [applyProfile]. */
    fun applyUnion(
        gedcom: Gedcom, family: Family, union: GeniUnion, previous: GeniUnion? = null,
        keep: Set<UnionGroup> = emptySet(), personFor: (String) -> Person?
    ) {
        union.partners.orEmpty().mapNotNull { personFor(geniId(it)) }.forEach { partner ->
            if (family.husbandRefs.orEmpty().any { it.ref == partner.id } || family.wifeRefs.orEmpty().any { it.ref == partner.id }) return@forEach
            val sex = partner.eventsFacts.orEmpty().firstOrNull { it.tag == "SEX" }?.value
            val asHusband = when (sex) {
                "M" -> true
                "F" -> false
                else -> family.husbandRefs.isNullOrEmpty()
            }
            val spouseRef = SpouseRef().apply { ref = partner.id }
            if (asHusband) family.addHusband(spouseRef) else family.addWife(spouseRef)
            if (partner.spouseFamilyRefs.orEmpty().none { it.ref == family.id })
                partner.addSpouseFamilyRef(SpouseFamilyRef().apply { ref = family.id })
        }
        union.children.orEmpty().mapNotNull { personFor(geniId(it)) }.forEach { child ->
            if (family.childRefs.orEmpty().none { it.ref == child.id }) family.addChild(ChildRef().apply { ref = child.id })
            if (child.parentFamilyRefs.orEmpty().none { it.ref == family.id })
                child.addParentFamilyRef(ParentFamilyRef().apply { ref = family.id })
        }
        if (UnionGroup.MARRIAGE !in keep && (previous == null || previous.marriage != union.marriage)) setEvent(family, "MARR", union.marriage)
        if (UnionGroup.DIVORCE !in keep && (previous == null || previous.divorce != union.divorce || previous.status != union.status)) {
            val date = gedcomDate(union.divorce?.date)
            val place = place(union.divorce?.location)
            if (date != null || place != null) setFact(family, "DIV", date = date, place = place)
            else if (union.status == "ex_spouse") setFact(family, "DIV", value = "Y")
            else removeFacts(family, "DIV")
        }
    }

    /** First and middle names stored apart, or null for a person without them. */
    fun getGivenNameParts(person: Person): GivenNameParts? {
        val first = getTag(person, FIRST_NAME_TAG)
        val middle = getTag(person, MIDDLE_NAME_TAG)
        return if (first == null && middle == null) null else GivenNameParts(first, middle)
    }

    fun setGivenNameParts(person: Person, parts: GivenNameParts) {
        val normalized = parts.normalized()
        setTag(person, FIRST_NAME_TAG, normalized.first)
        setTag(person, MIDDLE_NAME_TAG, normalized.middle)
    }

    /**
     * Name parts to show for a person: the stored ones, or for a record from before they were stored the ones of
     * Geni's last version [snapshot] if the given name is still the same, else the first-space split.
     */
    fun givenNamePartsFor(person: Person, snapshot: String?): GivenNameParts? {
        val local = ProfileFields.of(person)
        return local.resolvedAgainst(snapshot?.let { ProfileFields.ofSnapshot(it) } ?: local).givenParts
    }

    fun getGeniId(container: ExtensionContainer): String? = getTag(container, GENI_ID_TAG)

    fun setGeniId(container: ExtensionContainer, id: String) = setTag(container, GENI_ID_TAG, id)

    /** Value of a custom tag kept in the gedcom5-java "more tags" extension. */
    fun getTag(container: ExtensionContainer, tag: String): String? = moreTags(container).firstOrNull { it.tag == tag }?.value

    /** Sets or removes (with null) a custom tag. */
    fun setTag(container: ExtensionContainer, tag: String, value: String?) {
        val tags = moreTags(container).filter { it.tag != tag }.toMutableList()
        if (value != null) tags += GedcomTag(null, tag, null).apply { this.value = value }
        if (tags.isEmpty()) container.extensions?.remove(ModelParser.MORE_TAGS_EXTENSION_KEY)
        else container.putExtension(ModelParser.MORE_TAGS_EXTENSION_KEY, tags)
    }

    @Suppress("UNCHECKED_CAST")
    private fun moreTags(container: ExtensionContainer): List<GedcomTag> =
        (container.getExtension(ModelParser.MORE_TAGS_EXTENSION_KEY) as? List<*>)?.filterIsInstance<GedcomTag>().orEmpty()
}
