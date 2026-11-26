package app.familygem.geni.map

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldGroupsTest {

    private val base = ProfileFields.EMPTY.copy(given = "Ivan", surname = "Petrov", occupation = "Engineer", birth = EventFields("1960", "Kyiv"))

    @Test
    fun localOnlyChangeIsNoConflict() {
        val result = threeWay(base, base.copy(occupation = "Driver"), base)
        assertEquals(setOf(ProfileGroup.OCCUPATION), result.local)
        assertEquals(emptySet<ProfileGroup>(), result.geni)
        assertEquals(emptySet<ProfileGroup>(), result.conflicts)
    }

    @Test
    fun geniOnlyChangeIsNoConflict() {
        val result = threeWay(base, base, base.copy(birth = EventFields("1961", "Kyiv")))
        assertEquals(emptySet<ProfileGroup>(), result.local)
        assertEquals(setOf(ProfileGroup.BIRTH), result.geni)
        assertEquals(emptySet<ProfileGroup>(), result.conflicts)
    }

    @Test
    fun sameValueOnBothSidesIsNoConflict() {
        val both = base.copy(occupation = "Driver")
        assertEquals(emptySet<ProfileGroup>(), threeWay(base, both, both).conflicts)
    }

    @Test
    fun differentValuesOnBothSidesConflict() {
        val result = threeWay(base, base.copy(occupation = "Driver", surname = "Ivanov"), base.copy(occupation = "Pilot", birth = null))
        assertEquals(setOf(ProfileGroup.OCCUPATION), result.conflicts)
        assertEquals(setOf(ProfileGroup.OCCUPATION, ProfileGroup.NAMES), result.local)
        assertEquals(setOf(ProfileGroup.OCCUPATION, ProfileGroup.BIRTH), result.geni)
    }

    @Test
    fun deathGroupIncludesCauseAndFlag() {
        val dead = base.copy(dead = true)
        assertEquals(setOf(ProfileGroup.DEATH), threeWay(base, dead, base.copy(causeOfDeath = "Illness")).conflicts)
    }

    @Test
    fun unionDivorceConflict() {
        val married = UnionFields(EventFields("2000", null), null)
        val result = threeWay(married, married.copy(divorce = EventFields("2010", null)), married.copy(divorce = EventFields("2011", null)))
        assertEquals(setOf(UnionGroup.DIVORCE), result.conflicts)
    }

    @Test
    fun undatedDivorceOnGeniConflictsWithLocalDivorceDate() {
        val married = UnionFields(EventFields("2000", null), null)
        val result = threeWay(married, married.copy(divorce = EventFields("2010", null), divorced = true), married.copy(divorced = true))
        assertEquals(setOf(UnionGroup.DIVORCE), result.conflicts)
    }

    @Test
    fun filledGroupsSkipEmptyValues() {
        assertEquals(setOf(ProfileGroup.NAMES, ProfileGroup.BIRTH, ProfileGroup.OCCUPATION), base.filled())
    }

    private val mary = base.copy(given = "Mary Ann Louise", givenParts = GivenNameParts("Mary Ann", "Louise"))

    @Test
    fun concurrentNameEditsConflict() {
        val result = threeWay(mary, mary.copy(given = "Mary Ann Rose", givenParts = GivenNameParts("Mary Ann", "Rose")),
            mary.copy(given = "Mary Ann Grace", givenParts = GivenNameParts("Mary Ann", "Grace")))
        assertEquals(setOf(ProfileGroup.NAMES), result.conflicts)
    }

    @Test
    fun changedSplitIsANameChange() {
        val result = threeWay(mary, mary.copy(givenParts = GivenNameParts("Mary", "Ann Louise")), mary)
        assertEquals(setOf(ProfileGroup.NAMES), result.local)
    }

    @Test
    fun legacyLocalNameInheritsTheSnapshot() {
        val result = threeWay(mary.copy(displayName = "M. A."), mary.copy(givenParts = null, displayName = null),
            mary.copy(given = "Mary Ann Grace", givenParts = GivenNameParts("Mary Ann", "Grace"), displayName = ""))
        assertEquals(emptySet<ProfileGroup>(), result.local)
        assertEquals(setOf(ProfileGroup.NAMES, ProfileGroup.DISPLAY_NAME), result.geni)
        assertEquals(emptySet<ProfileGroup>(), result.conflicts)
    }
}
