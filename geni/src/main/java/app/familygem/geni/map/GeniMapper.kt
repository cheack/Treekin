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

    /** Geni's name title, like the display name; the GEDCOM title fact (TITL) stays local. */
    const val NAME_TITLE_TAG = "_GENI_NAME_TITLE"

    /**
     * The structured location of an event fact, as a JSON object of the Geni location keys ("city", "country", ...).
     * Missing: a legacy fact, whose place text is all there is. An empty object ("{}"): every part cleared on purpose.
     * `PLAC` keeps the joined text for cards and GEDCOM consumers.
     */
    const val LOCATION_TAG = "_GENI_LOCATION"

    private val locationGson = com.google.gson.GsonBuilder().disableHtmlEscaping().create()
    private val locationType = object : com.google.gson.reflect.TypeToken<Map<String, String?>>() {}.type

    /** Tags kept for the sync and edited in the person editor, not as facts. */
    val INTERNAL_TAGS = setOf(FIRST_NAME_TAG, MIDDLE_NAME_TAG, DISPLAY_NAME_TAG, NAME_TITLE_TAG)

    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")

    /**
     * GEDCOM date like "12 MAR 1960", "JUN 2012", "2014" or "ABT 1960"; null without a year.
     * A `before` / `after` range gives "BEF 1900" / "AFT 1900", `between` gives "BET 1840 AND 1845" (each end with its own ABT).
     * A `between` without a usable end is the start date alone.
     */
    fun gedcomDate(date: GeniDate?): String? {
        val start = point(date?.year, date?.month, date?.day, date?.circa) ?: return null
        return when (date?.range) {
            "before" -> "BEF $start"
            "after" -> "AFT $start"
            "between" -> point(date.endYear, date.endMonth, date.endDay, date.endCirca)?.let { "BET $start AND $it" } ?: start
            else -> start
        }
    }

    /** The start date alone of a GEDCOM range, as the app stored such a date before it synced ranges; null for a date without range. */
    fun startOfRange(date: String): String? {
        val parsed = GeniParams.parseDate(date)?.takeIf { it.range != null } ?: return null
        return gedcomDate(parsed.copy(range = null, endYear = null, endMonth = null, endDay = null, endCirca = null))
    }

    private fun point(year: Int?, month: Int?, day: Int?, circa: Boolean?): String? {
        year ?: return null
        val monthName = month?.takeIf { it in 1..12 }?.let { MONTHS[it - 1] }
        val dayNumber = day?.takeIf { monthName != null && it in 1..31 }
        val text = listOfNotNull(dayNumber?.toString(), monthName, year.toString()).joinToString(" ")
        return if (circa == true) "ABT $text" else text
    }

    /** Place text from the non-blank location parts, from the most specific. */
    fun place(location: GeniLocation?): String? = LocationParts.of(location).joined

    /** The structured location stored on a fact; null for a fact without it (or with an unreadable one). */
    fun getLocation(fact: EventFact): LocationParts? {
        val json = getTag(fact, LOCATION_TAG) ?: return null
        return try {
            locationGson.fromJson<Map<String, String?>>(json, locationType)?.let { LocationParts.of(it) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Stores [location] on the fact: the parts in the extension, their joined text as `PLAC`. Only this extension is touched:
     * notes, sources, media and other extensions stay. Pass null to drop the extension (the fact is then a legacy one).
     */
    fun setLocation(fact: EventFact, location: LocationParts?) {
        setTag(fact, LOCATION_TAG, location?.let { locationGson.toJson(it.values) })
        if (location != null) fact.place = location.joined
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
        if (changed(ProfileGroup.TITLE) { clean(it.title) }) setTag(person, NAME_TITLE_TAG, clean(profile.title) ?: "")
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
            val location = LocationParts.of(profile.death?.location)
            if (date == null && location.isEmpty && !dead) removeFacts(person, "DEAT")
            else setFact(person, "DEAT", value = if (date == null && location.isEmpty) "Y" else null, date = date, location = location,
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
        val location = LocationParts.of(event?.location)
        if (date == null && location.isEmpty) removeFacts(container, tag)
        else setFact(container, tag, date = date, location = location)
    }

    /** [location], if given, replaces the structured location of the fact and gives its place text; else [place] is the place text. */
    private fun setFact(container: Any, tag: String, value: String? = null, date: String? = null, place: String? = null, cause: String? = null,
        location: LocationParts? = null) {
        val facts = facts(container)
        val fact = facts.firstOrNull { it.tag == tag } ?: EventFact().also {
            it.tag = tag
            setFacts(container, facts + it)
        }
        fact.value = value
        fact.date = date
        fact.place = location?.joined ?: place
        // A location Geni does not have is a fact without the extension (what a legacy record looks like); other extensions stay
        if (location != null) setLocation(fact, location.takeIf { !it.isEmpty })
        if (tag == "DEAT") fact.cause = cause
    }

    /**
     * Removes the facts with this tag. A birth, baptism, burial, marriage or occupation fact that holds local data
     * (notes, sources, media, type, cause, age, address) is emptied instead: Geni does not have that data. An empty fact projects as no value.
     * Death and divorce facts are removed anyway: an empty one would still mean "dead" or "divorced".
     */
    private fun removeFacts(container: Any, tag: String) {
        val kept = when (tag) {
            // Geni has only the occupation's text: a local date or place is local data too, and stays
            "OCCU" -> facts(container).filter { it.tag == tag && (hasLocalData(it) || it.date != null || it.place != null) }
                .onEach { it.value = null }
            in EMPTIABLE -> facts(container).filter { it.tag == tag && hasLocalData(it) }
                .onEach { it.value = null; it.date = null; it.place = null; setTag(it, LOCATION_TAG, null) }
            else -> emptyList()
        }
        setFacts(container, facts(container).filter { it.tag != tag || it in kept })
    }

    private val EMPTIABLE = setOf("BIRT", "BAPM", "BURI", "MARR", "OCCU")

    /**
     * True for a fact with data Geni does not have, besides date, place and value: attachments (notes, sources, media),
     * details (type, cause, age, address, contacts, RIN, UID) and extensions. Such a fact is emptied, never removed,
     * by a clear from Geni or from the person editor.
     */
    fun hasLocalData(fact: EventFact) = fact.notes.orEmpty().isNotEmpty() || fact.noteRefs.orEmpty().isNotEmpty() ||
            fact.sourceCitations.orEmpty().isNotEmpty() || fact.media.orEmpty().isNotEmpty() || fact.mediaRefs.orEmpty().isNotEmpty() ||
            listOf(fact.type, fact.cause, fact.age, fact.phone, fact.fax, fact.email, fact.www, fact.rin, fact.uid).any { !it.isNullOrBlank() } ||
            fact.address != null || hasOtherExtensions(fact)

    /** True for an extension other than the structured location, which is Geni data, not local data. */
    private fun hasOtherExtensions(fact: EventFact): Boolean = fact.extensions.orEmpty().any { (key, value) ->
        key != ModelParser.MORE_TAGS_EXTENSION_KEY || (value as? List<*>)?.any { it !is GedcomTag || it.tag != LOCATION_TAG } != false
    }

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
            val location = LocationParts.of(union.divorce?.location)
            if (date != null || !location.isEmpty) setFact(family, "DIV", date = date, location = location)
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
