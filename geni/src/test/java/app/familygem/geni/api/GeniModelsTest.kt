package app.familygem.geni.api

import app.familygem.geni.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class GeniModelsTest {

    @Test
    fun parsesImmediateFamilyNodes() {
        val family = ImmediateFamily.parse(Fixtures.read("immediate-family-root.json"))
        assertEquals("profile-36430", family.focusId)
        assertEquals(8, family.profileIds.size)
        assertEquals(setOf("union-10105", "union-10106"), family.unionIds.toSet())
    }

    @Test
    fun parsesProfilesBatch() {
        val profiles = parseResults(Fixtures.read("profiles-batch.json"), GeniProfile::class.java).associateBy { it.id }
        assertEquals(8, profiles.size)
        val father = profiles.getValue("profile-36431")
        assertEquals(15, father.death?.date?.day)
        assertEquals("Vvedenskoye Cemetery", father.burial?.location?.placeName)
        assertEquals(listOf("Masha"), profiles.getValue("profile-36432").nicknames)
    }

    @Test
    fun parsesPrivacyAndClearedSex() {
        val profiles = parseResults(Fixtures.read("profiles-batch-privacy.json"), GeniProfile::class.java).associateBy { it.id }
        assertEquals(false, profiles.getValue("profile-100").public) // An explicit false is returned
        assertEquals(true, profiles.getValue("profile-101").public)
        assertEquals(null, profiles.getValue("profile-102").gender) // A cleared sex is omitted
        assertEquals(null, profiles.getValue("profile-102").locked)
    }

    @Test
    fun mergeNoteIsAStringOrAnArray() {
        val profiles = parseResults(Fixtures.read("profile-merge-note-forms.json"), GeniProfile::class.java).associateBy { it.id }
        assertEquals("same person as profile-1", profiles.getValue("profile-110").mergeNoteText)
        assertEquals(true, profiles.getValue("profile-110").locked)
        assertEquals("first note\nsecond note", profiles.getValue("profile-111").mergeNoteText)
        assertEquals(false, profiles.getValue("profile-111").locked)
        assertEquals(null, profiles.getValue("profile-112").mergeNoteText)
    }

    @Test
    fun parsesUnionsBatch() {
        val union = parseResults(Fixtures.read("unions-batch.json"), GeniUnion::class.java).single { it.id == "union-10106" }
        assertEquals(2, union.partners?.size)
        assertEquals(3, union.children?.size)
        assertEquals(6, union.marriage?.date?.month)
    }

    @Test
    fun geniIdFromUrl() = assertEquals("profile-36431", geniId("https://api.sandbox.geni.com/profile-36431"))

    @Test
    fun singleIdBatchIsBareObject() =
        assertEquals(listOf("profile-36431"), parseResults("""{"id":"profile-36431","first_name":"Ivan"}""", GeniProfile::class.java).map { it.id })
}
