package app.familygem.geni.map

/*
 * Field groups: the parts of a record that GeniMapper applies as a whole and that conflict as a whole.
 * Comparing the local record and Geni's version group by group against the last snapshot tells
 * who changed what, and where both changed the same thing differently.
 */

enum class ProfileGroup { NAMES, DISPLAY_NAME, TITLE, SEX, BIRTH, BAPTISM, DEATH, BURIAL, OCCUPATION, ABOUT }

enum class UnionGroup { MARRIAGE, DIVORCE }

fun ProfileFields.value(group: ProfileGroup): Any? = when (group) {
    ProfileGroup.NAMES -> listOf(given, surname, maiden, suffix, nicknames, givenParts)
    ProfileGroup.DISPLAY_NAME -> displayName
    ProfileGroup.TITLE -> title
    ProfileGroup.SEX -> sex
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
fun ProfileFields.filled(): Set<ProfileGroup> = ProfileGroup.values().filter { filled(value(it)) }.toSet()

fun UnionFields.filled(): Set<UnionGroup> = UnionGroup.values().filter { filled(value(it)) }.toSet()

/** Groups changed locally and on Geni since the snapshot, and the conflicts: changed on both sides to different values. */
data class ThreeWay<G>(val local: Set<G>, val geni: Set<G>, val conflicts: Set<G>)

private fun <G, F> threeWay(groups: Array<G>, snapshot: F, local: F, geni: F, value: F.(G) -> Any?): ThreeWay<G> {
    val localChanged = groups.filter { local.value(it) != snapshot.value(it) }.toSet()
    val geniChanged = groups.filter { geni.value(it) != snapshot.value(it) }.toSet()
    val conflicts = localChanged.filter { it in geniChanged && local.value(it) != geni.value(it) }.toSet()
    return ThreeWay(localChanged, geniChanged, conflicts)
}

/** A legacy local record takes the name parts, display name and title it lacks from the snapshot (see [ProfileFields.resolvedAgainst]). */
fun threeWay(snapshot: ProfileFields, local: ProfileFields, geni: ProfileFields): ThreeWay<ProfileGroup> =
    threeWay(ProfileGroup.values(), snapshot.resolvedAgainst(snapshot), local.resolvedAgainst(snapshot), geni.resolvedAgainst(geni)) { value(it) }

fun threeWay(snapshot: UnionFields, local: UnionFields, geni: UnionFields): ThreeWay<UnionGroup> =
    threeWay(UnionGroup.values(), snapshot, local, geni) { value(it) }
