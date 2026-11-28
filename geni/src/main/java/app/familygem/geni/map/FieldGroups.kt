package app.familygem.geni.map

/*
 * Field groups: the parts of a record that GeniMapper applies as a whole and that conflict as a whole.
 * Comparing the local record and Geni's version group by group against the last snapshot tells
 * who changed what, and where both changed the same thing differently.
 */

/** A group of fields that changes and conflicts as a whole; [name] is how the conflict screen and its choices refer to it. */
interface FieldGroup {
    val name: String
}

enum class ProfileGroup : FieldGroup { NAMES, DISPLAY_NAME, TITLE, SEX, PUBLIC, BIRTH, BAPTISM, DEATH, BURIAL, OCCUPATION, ABOUT }

/** The name of a person in one other language: each locale changes and conflicts on its own. */
data class LocaleGroup(val locale: String) : FieldGroup {
    override val name: String get() = PREFIX + locale

    companion object {
        const val PREFIX = "LOCALE:"

        /** The locale of a group [name] made by [LocaleGroup.name], or null for another group. */
        fun localeOf(name: String): String? = name.removePrefix(PREFIX).takeIf { name.startsWith(PREFIX) }
    }
}

enum class UnionGroup { MARRIAGE, DIVORCE }

fun ProfileFields.value(group: FieldGroup): Any? = if (group is LocaleGroup) locales[group.locale] else value(group as ProfileGroup)

fun ProfileFields.value(group: ProfileGroup): Any? = when (group) {
    ProfileGroup.NAMES -> listOf(given, surname, maiden, suffix, nicknames, givenParts)
    ProfileGroup.DISPLAY_NAME -> displayName
    ProfileGroup.TITLE -> title
    ProfileGroup.SEX -> sex?.takeIf { it != "U" } // An unknown sex equals a missing one
    ProfileGroup.PUBLIC -> isPublic?.let { if (it) "public" else "private" }
    ProfileGroup.BIRTH -> birth
    ProfileGroup.BAPTISM -> baptism
    ProfileGroup.DEATH -> listOf(death, dead, causeOfDeath)
    ProfileGroup.BURIAL -> burial
    ProfileGroup.OCCUPATION -> occupation
    ProfileGroup.ABOUT -> about
}

fun UnionFields.value(group: UnionGroup): Any? = when (group) {
    UnionGroup.MARRIAGE -> marriage
    UnionGroup.DIVORCE -> listOf(divorce, divorced)
}

private fun filled(value: Any?): Boolean = value != null && (value !is List<*> || value.any { it != null && it != false })

/** Groups with a value. */
fun ProfileFields.filled(): Set<FieldGroup> = (ProfileGroup.values().filter { filled(value(it)) } + locales.keys.map { LocaleGroup(it) }).toSet()

fun UnionFields.filled(): Set<UnionGroup> = UnionGroup.values().filter { filled(value(it)) }.toSet()

/** Groups changed locally and on Geni since the snapshot, and the conflicts: changed on both sides to different values. */
data class ThreeWay<G>(val local: Set<G>, val geni: Set<G>, val conflicts: Set<G>)

private fun <G, F> threeWay(groups: Collection<G>, snapshot: F, local: F, geni: F, value: F.(G) -> Any?): ThreeWay<G> {
    val localChanged = groups.filter { local.value(it) != snapshot.value(it) }.toSet()
    val geniChanged = groups.filter { geni.value(it) != snapshot.value(it) }.toSet()
    val conflicts = localChanged.filter { it in geniChanged && local.value(it) != geni.value(it) }.toSet()
    return ThreeWay(localChanged, geniChanged, conflicts)
}

/**
 * A legacy local record takes the name parts, display name, title and structured places it lacks from the snapshot (see [ProfileFields.resolvedAgainst]).
 * The groups are the fixed ones and one for each language any of the three versions has.
 */
fun threeWay(snapshot: ProfileFields, local: ProfileFields, geni: ProfileFields): ThreeWay<FieldGroup> {
    // A version from before the languages were read has none: an unknown list counts as an empty one, so it never removes anything
    val locales = (snapshot.locales.keys + local.locales.keys + geni.locales.keys).sorted().map { LocaleGroup(it) }
    return threeWay(ProfileGroup.values().toList() + locales, snapshot.resolvedAgainst(snapshot), local.resolvedAgainst(snapshot), geni.resolvedAgainst(geni)) { value(it) }
}

fun threeWay(snapshot: UnionFields, local: UnionFields, geni: UnionFields): ThreeWay<UnionGroup> =
    threeWay(UnionGroup.values().toList(), snapshot.withPlacesResolved(), local.resolvedAgainst(snapshot), geni.withPlacesResolved()) { value(it) }
