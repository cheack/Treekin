package app.familygem.geni.map

import app.familygem.geni.api.GeniProfile
import app.familygem.geni.api.GeniUnion
import com.google.gson.Gson
import org.folg.gedcom.model.EventFact
import org.folg.gedcom.model.Family
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Person

/*
 * Projections of GEDCOM records to the fields Geni has, in normalized GEDCOM form.
 * The local record and its Geni snapshot (mapped through GeniMapper) are projected the same way,
 * so a value that round-trips through Geni compares equal and makes no upload.
 */

/**
 * Date and place of an event; the date is canonical when Geni can store it, else the trimmed text.
 * @param place The joined place text (GEDCOM `PLAC`).
 * @param location The structured parts. Null for a legacy record that does not store them: [resolvedAgainst] fills them in.
 *  An empty location is an explicit clear of every part.
 */
data class EventFields(val date: String?, val place: String?, val location: LocationParts? = null) {
    /**
     * This event with what a legacy record lacks taken from [baseline] (the last Geni version):
     * - A date that is only the start of the baseline's range ("1840" for "BET 1840 AND 1845", as the app stored ranges
     *   before it synced them) is the baseline's date, not a change.
     * - A legacy place text equal to the baseline's joined text is the baseline's structured location, not a change.
     *   A text that was edited is the new `place_name` over the baseline's other parts.
     * The place text of the result is the joined text of the location.
     */
    fun resolvedAgainst(baseline: EventFields?): EventFields {
        val baseDate = baseline?.date
        val resolvedDate = if (date != null && baseDate != null && date != baseDate && date == GeniMapper.startOfRange(baseDate)) baseDate else date
        val resolvedLocation = location ?: run {
            val base = baseline?.location ?: LocationParts.ofPlace(baseline?.place)
            if (place == baseline?.place) base else base.with(LocationParts.PLACE_NAME, place)
        }
        return EventFields(resolvedDate, resolvedLocation.joined, resolvedLocation)
    }

    /** The structured location, taken from the place text for a record that has none. */
    val locationParts: LocationParts get() = location ?: LocationParts.ofPlace(place)

    companion object {
        /** Projection of a fact; null if it has neither date nor place nor any structured part. */
        fun of(fact: EventFact?): EventFields? = event(fact)
    }
}

internal fun EventFields?.resolvedAgainst(baseline: EventFields?): EventFields? = this?.resolvedAgainst(baseline)

/** First and middle names as Geni keeps them apart; joined with a space they make the given name of the GEDCOM name. */
data class GivenNameParts(val first: String?, val middle: String?) {
    val joined: String? get() = clean(listOfNotNull(first, middle).joinToString(" "))

    fun normalized() = GivenNameParts(clean(first), clean(middle))

    companion object {
        /** Split at the first space: how a given name typed as one text is sent to Geni. */
        fun split(given: String?): GivenNameParts? =
            clean(given)?.let { GivenNameParts(it.substringBefore(' '), it.substringAfter(' ', "").ifEmpty { null }) }
    }
}

data class ProfileFields(
    val given: String?,
    val surname: String?,
    val maiden: String?,
    val suffix: String?,
    val nicknames: String?,
    /** "M", "F", "U" for an unknown sex written as such, null without a SEX fact. */
    val sex: String?,
    val birth: EventFields?,
    val baptism: EventFields?,
    val death: EventFields?,
    val burial: EventFields?,
    val dead: Boolean,
    val causeOfDeath: String?,
    val occupation: String?,
    val about: String?,
    /** First and middle names stored apart; null for a record without them (or whose given name changed elsewhere since). */
    val givenParts: GivenNameParts? = null,
    /** Geni's display name override: null when the record does not know it, empty for none. */
    val displayName: String? = null,
    /** Geni's name title (not the GEDCOM title fact): null when the record does not know it, empty for none. */
    val title: String? = null,
    /** Geni's privacy: null when the record does not know it (no `_GENI_PUBLIC` tag), false for an explicit private. */
    val isPublic: Boolean? = null,
    /** Curator lock as Geni reported it (read-only, never written): null when unknown. */
    val locked: Boolean? = null
) {
    /**
     * The fields with what a legacy record does not store taken from [baseline] (the last Geni version):
     * the name parts when the given name is still the same (else the first-space split), and the display name.
     */
    fun resolvedAgainst(baseline: ProfileFields): ProfileFields = copy(
        givenParts = givenParts ?: baseline.givenParts?.takeIf { given == baseline.given } ?: GivenNameParts.split(given),
        displayName = displayName ?: baseline.displayName,
        title = title ?: baseline.title,
        isPublic = isPublic ?: baseline.isPublic,
        locked = locked ?: baseline.locked,
        birth = birth.resolvedAgainst(baseline.birth),
        baptism = baptism.resolvedAgainst(baseline.baptism),
        death = death.resolvedAgainst(baseline.death),
        burial = burial.resolvedAgainst(baseline.burial)
    )

    /** Every event with its structured location (empty if Geni has none): the form snapshots and records are compared in. */
    fun withPlacesResolved() = copy(
        birth = birth.resolvedAgainst(birth), baptism = baptism.resolvedAgainst(baptism),
        death = death.resolvedAgainst(death), burial = burial.resolvedAgainst(burial)
    )

    /** The event with this GEDCOM tag (BIRT, BAPM, DEAT or BURI). */
    fun event(tag: String): EventFields? = when (tag) {
        "BIRT" -> birth
        "BAPM" -> baptism
        "DEAT" -> death
        "BURI" -> burial
        else -> null
    }

    companion object {
        val EMPTY = ProfileFields(null, null, null, null, null, null, null, null, null, null, false, null, null, null)

        /** Reads what [GeniMapper.applyProfile] writes: first main name, birth name, first fact of each tag. */
        fun of(person: Person): ProfileFields {
            val names = person.names.orEmpty()
            val main = names.firstOrNull { it.type != "birth" }
            val (given, surname) = splitName(main?.value)
            val facts = person.eventsFacts.orEmpty()
            fun first(tag: String) = facts.firstOrNull { it.tag == tag }
            val death = first("DEAT")
            return ProfileFields(
                given = given,
                surname = surname,
                // GeniMapper writes no birth name when the maiden name equals the surname
                maiden = splitName(names.firstOrNull { it.type == "birth" }?.value).second?.takeIf { it != surname },
                suffix = clean(main?.suffix),
                nicknames = main?.nickname?.split(',')?.mapNotNull { clean(it) }?.joinToString(", ")?.takeIf { it.isNotEmpty() },
                // "U" is Geni's cleared sex (or a record that never had one); a missing SEX fact is null
                sex = clean(first("SEX")?.value)?.uppercase()?.takeIf { it == "M" || it == "F" || it == "U" },
                birth = event(first("BIRT")),
                baptism = event(first("BAPM")),
                death = event(death),
                burial = event(first("BURI")),
                dead = death != null,
                causeOfDeath = clean(death?.cause),
                occupation = clean(first("OCCU")?.value),
                about = GeniMapper.getTag(person, GeniMapper.ABOUT_TAG)?.replace("\r\n", "\n")?.trim()?.takeIf { it.isNotEmpty() }, // Keeps line breaks
                // Parts that no longer make the given name were left by an edit elsewhere: ignored
                givenParts = GeniMapper.getGivenNameParts(person)?.normalized()?.takeIf { it.joined == given },
                displayName = GeniMapper.getTag(person, GeniMapper.DISPLAY_NAME_TAG)?.let { clean(it) ?: "" },
                title = GeniMapper.getTag(person, GeniMapper.NAME_TITLE_TAG)?.let { clean(it) ?: "" },
                isPublic = GeniMapper.getBoolean(person, GeniMapper.PUBLIC_TAG),
                locked = GeniMapper.getBoolean(person, GeniMapper.LOCKED_TAG)
            )
        }

        /** Projection of a raw Geni profile, as the loader would map it; null if the JSON is not a profile. */
        fun ofSnapshot(json: String): ProfileFields? {
            val profile = parse(json, GeniProfile::class.java) ?: return null
            val person = Person()
            GeniMapper.applyProfile(Gedcom(), person, profile)
            return of(person)
        }
    }
}

/** @param divorced Any divorce fact, also one without date and place ("DIV Y", Geni's ex-spouse status) */
data class UnionFields(val marriage: EventFields?, val divorce: EventFields?, val divorced: Boolean = false) {
    /** The fields with legacy dates resolved against [baseline] (see [EventFields.resolvedAgainst]). */
    fun resolvedAgainst(baseline: UnionFields) = copy(marriage = marriage.resolvedAgainst(baseline.marriage), divorce = divorce.resolvedAgainst(baseline.divorce))

    fun withPlacesResolved() = resolvedAgainst(this)

    /** The event with this GEDCOM tag (MARR or DIV). */
    fun event(tag: String): EventFields? = when (tag) {
        "MARR" -> marriage
        "DIV" -> divorce
        else -> null
    }

    companion object {
        val EMPTY = UnionFields(null, null)

        /** A divorce without date and place ("DIV Y") counts as no divorce event: Geni would need `status` for it. */
        fun of(family: Family): UnionFields {
            val facts = family.eventsFacts.orEmpty()
            return UnionFields(event(facts.firstOrNull { it.tag == "MARR" }), event(facts.firstOrNull { it.tag == "DIV" }), facts.any { it.tag == "DIV" })
        }

        fun ofSnapshot(json: String): UnionFields? {
            val union = parse(json, GeniUnion::class.java) ?: return null
            val family = Family()
            GeniMapper.applyUnion(Gedcom(), family, union) { null }
            return of(family)
        }
    }
}

private val gson = Gson()

private fun <T : Any> parse(json: String, type: Class<T>): T? =
    runCatching { gson.fromJson(json, type) }.getOrNull()

/** Trimmed text with single spaces, null if blank. */
internal fun clean(text: String?): String? = text?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() }

/** Given name and surname of a GEDCOM name value like "Ivan /Petrov/". */
private fun splitName(value: String?): Pair<String?, String?> {
    value ?: return null to null
    val start = value.indexOf('/')
    if (start < 0) return clean(value) to null
    val end = value.indexOf('/', start + 1).let { if (it < 0) value.length else it }
    return clean(value.substring(0, start)) to clean(value.substring(start + 1, end))
}

private fun event(fact: EventFact?): EventFields? {
    fact ?: return null
    val text = clean(fact.place)
    val date = clean(fact.date)?.let { raw -> GeniParams.parseDate(raw)?.let { GeniMapper.gedcomDate(it) } ?: raw }
    // Parts stored apart; a place text changed elsewhere since (a form of the upstream app) is the new place name
    val stored = GeniMapper.getLocation(fact)
    val location = stored?.let { if (it.joined == text) it else it.with(LocationParts.PLACE_NAME, text) }
    if (date == null && text == null && location?.isEmpty != false) return null
    return EventFields(date, if (location != null) location.joined else text, location)
}
