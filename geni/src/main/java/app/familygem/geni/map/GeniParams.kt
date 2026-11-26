package app.familygem.geni.map

import app.familygem.geni.api.GeniDate

/** Turns local GEDCOM values into Geni API parameters. */
object GeniParams {

    private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    private val DATE = Regex("""^(?:(ABT|EST|CAL)\s+)?(?:(\d{1,2})\s+)?(?:([A-Z]{3})\s+)?(\d{1,4})$""")

    /**
     * Geni date of a GEDCOM date like "12 MAR 1960", "JUN 2012", "1960" or "ABT 1960" (also EST and CAL), in any case.
     * Null for anything Geni cannot store: ranges, BEF/AFT, free text, a day without a month.
     */
    fun parseDate(text: String?): GeniDate? {
        val match = DATE.matchEntire(text?.trim()?.uppercase()?.replace(Regex("\\s+"), " ") ?: return null) ?: return null
        val (prefix, day, monthName, year) = match.destructured
        val month = if (monthName.isEmpty()) null else MONTHS.indexOf(monthName).takeIf { it >= 0 }?.plus(1) ?: return null
        val dayNumber = if (day.isEmpty()) null else day.toInt().takeIf { it in 1..31 && month != null } ?: return null
        val yearNumber = year.toInt().takeIf { it > 0 } ?: return null
        return GeniDate(yearNumber, month, dayNumber, prefix.isNotEmpty())
    }

    private val LOCATION_PARTS = listOf("city", "county", "state", "country")

    /**
     * Form parameters that bring a Geni profile from [old] to [new], only for the field groups that differ.
     * With [isNew] the profile is being created: every non-empty group is sent, and `is_alive` always
     * (Geni makes profiles created through the API deceased otherwise).
     * Values Geni cannot store (a date like "BEF 1900") count as unchanged.
     */
    fun profile(oldFields: ProfileFields, newFields: ProfileFields, isNew: Boolean = false): List<Pair<String, String>> {
        val old = oldFields.resolvedAgainst(oldFields)
        val new = newFields.resolvedAgainst(oldFields)
        val params = mutableListOf<Pair<String, String>>()
        // Each name part only if it changed: re-sending an unchanged part could re-split Geni's first and middle names.
        // A given name changed without stored parts (an edit outside the Geni form) goes split at the first space, whole.
        val wholeGiven = newFields.givenParts == null && new.given != old.given
        if (wholeGiven || new.givenParts?.first != old.givenParts?.first) params += "first_name" to (new.givenParts?.first ?: "")
        if (wholeGiven || new.givenParts?.middle != old.givenParts?.middle) params += "middle_name" to (new.givenParts?.middle ?: "")
        if (new.surname != old.surname) params += "last_name" to (new.surname ?: "")
        if (new.maiden != old.maiden) params += "maiden_name" to (new.maiden ?: "")
        if (new.suffix != old.suffix) params += "suffix" to (new.suffix ?: "")
        if (new.nicknames != old.nicknames) params += "nicknames" to (new.nicknames ?: "")
        if (new.displayName.orEmpty() != old.displayName.orEmpty()) params += "display_name" to new.displayName.orEmpty()
        if (new.sex != null && new.sex != old.sex) params += "gender" to if (new.sex == "M") "male" else "female"
        event(params, "birth", old.birth, new.birth)
        event(params, "baptism", old.baptism, new.baptism)
        // Geni ignores death while the profile is alive, so is_alive goes first and with every death change
        val death = mutableListOf<Pair<String, String>>()
        if (new.dead) {
            event(death, "death", old.death.takeIf { old.dead }, new.death)
            if (new.causeOfDeath != old.causeOfDeath) death += "cause_of_death" to (new.causeOfDeath ?: "")
        }
        if (isNew || old.dead != new.dead || death.isNotEmpty()) {
            params += "is_alive" to (!new.dead).toString()
            params += death
        }
        event(params, "burial", old.burial, new.burial)
        if (new.occupation != old.occupation) params += "occupation" to (new.occupation ?: "")
        if (new.about != old.about) params += "about_me" to (new.about ?: "")
        return params
    }

    /** Form parameters for marriage and divorce changes. Never `status`: changing it wipes both events on Geni. */
    fun union(old: UnionFields, new: UnionFields): List<Pair<String, String>> {
        val params = mutableListOf<Pair<String, String>>()
        event(params, "marriage", old.marriage, new.marriage)
        event(params, "divorce", old.divorce, new.divorce)
        return params
    }

    /**
     * Date parameters if the date changed (all four parts: Geni replaces the date as a whole),
     * location parameters if the place changed (the structured parts are cleared, the place goes to place_name).
     */
    private fun event(params: MutableList<Pair<String, String>>, name: String, old: EventFields?, new: EventFields?) {
        val newDate = new?.date
        if (newDate != old?.date && (newDate == null || parseDate(newDate) != null)) {
            val date = parseDate(newDate)
            params += "$name[date][day]" to (date?.day?.toString() ?: "")
            params += "$name[date][month]" to (date?.month?.toString() ?: "")
            params += "$name[date][year]" to (date?.year?.toString() ?: "")
            params += "$name[date][circa]" to (date?.circa == true).toString()
        }
        if (new?.place != old?.place) {
            params += "$name[location][place_name]" to (new?.place ?: "")
            LOCATION_PARTS.forEach { params += "$name[location][$it]" to "" }
        }
    }
}
