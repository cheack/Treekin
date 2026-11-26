package app.familygem.geni.map

import app.familygem.geni.Fixtures
import app.familygem.geni.api.GeniProfile
import app.familygem.geni.api.GeniUnion
import app.familygem.geni.api.rawResults
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.folg.gedcom.model.EventFact
import org.folg.gedcom.model.Family
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Name
import org.folg.gedcom.model.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldsTest {

    private fun byId(file: String) = rawResults(Fixtures.read(file)).associateBy { JsonParser.parseString(it).asJsonObject["id"].asString }
    private val profiles = byId("profiles-batch.json")
    private val unions = byId("unions-batch.json")

    private fun fact(tag: String, date: String? = null, place: String? = null, value: String? = null) =
        EventFact().also { it.tag = tag; it.date = date; it.place = place; it.value = value }

    @Test
    fun personFieldsMatchMapper() {
        for ((id, json) in profiles) {
            val person = Person()
            GeniMapper.applyProfile(Gedcom(), person, Gson().fromJson(json, GeniProfile::class.java))
            assertEquals(id, ProfileFields.ofSnapshot(json), ProfileFields.of(person))
        }
    }

    @Test
    fun snapshotFields() {
        val maria = ProfileFields.ofSnapshot(profiles.getValue("profile-36432"))!!
        assertEquals("Maria", maria.given)
        assertEquals("User", maria.surname)
        assertEquals("Smirnova", maria.maiden)
        assertEquals("Masha", maria.nicknames)
        assertEquals("F", maria.sex)
        assertEquals(EventFields("30 AUG 1962", "Kazan, Russia"), maria.birth)
        assertFalse(maria.dead)
    }

    @Test
    fun normalizedValuesCompareEqual() {
        val person = Person().apply {
            addName(Name().apply { value = "Ivan  /P/ "; nickname = "A,B" })
            addEventFact(fact("SEX", value = "M"))
            addEventFact(fact("BIRT", date = "5 jul 1991"))
        }
        val snapshot = """{"id":"profile-1","first_name":"Ivan","last_name":"P","gender":"male","nicknames":["A","B"],
            "birth":{"date":{"day":5,"month":7,"year":1991}}}"""
        val geni = ProfileFields.ofSnapshot(snapshot)!!
        assertEquals(geni, ProfileFields.of(person).resolvedAgainst(geni)) // The record stores no name parts nor display name
    }

    @Test
    fun deathWithoutDateIsDead() {
        val dead = ProfileFields.ofSnapshot("""{"id":"profile-1","is_alive":false}""")!!
        assertTrue(dead.dead)
        assertNull(dead.death)
    }

    @Test
    fun unknownSexIsNull() {
        assertNull(ProfileFields.of(Person().apply { addEventFact(fact("SEX", value = "U")) }).sex)
        assertNull(ProfileFields.of(Person()).sex)
    }

    @Test
    fun deathCauseOccupationAndAbout() {
        val person = Person().apply {
            addEventFact(fact("OCCU", value = " Engineer "))
            addEventFact(fact("DEAT", date = "2001", place = "Omsk").also { it.cause = "Flu" })
            GeniMapper.setTag(this, GeniMapper.ABOUT_TAG, "Hello")
        }
        val fields = ProfileFields.of(person)
        assertEquals("Engineer", fields.occupation)
        assertEquals(EventFields("2001", "Omsk"), fields.death)
        assertTrue(fields.dead)
        assertEquals("Flu", fields.causeOfDeath)
        assertEquals("Hello", fields.about)
    }

    @Test
    fun unparseableDateIsKeptAsText() {
        val person = Person().apply { addEventFact(fact("BIRT", date = " BEF  1900 ")) }
        assertEquals(EventFields("BEF 1900", null), ProfileFields.of(person).birth)
    }

    @Test
    fun familyFields() {
        val family = Family().apply {
            addEventFact(fact("MARR", date = "1 may 2016", place = "Kazan"))
            addEventFact(fact("DIV", date = "2020"))
        }
        assertEquals(UnionFields(EventFields("1 MAY 2016", "Kazan"), EventFields("2020", null), divorced = true), UnionFields.of(family))
    }

    @Test
    fun unionFieldsMatchMapper() {
        for ((id, json) in unions) {
            val family = Family()
            GeniMapper.applyUnion(Gedcom(), family, Gson().fromJson(json, GeniUnion::class.java)) { null }
            assertEquals(id, UnionFields.ofSnapshot(json), UnionFields.of(family))
        }
    }

    @Test
    fun badSnapshotIsNull() {
        assertNull(ProfileFields.ofSnapshot("not json"))
        assertNull(UnionFields.ofSnapshot("[]"))
    }

    @Test
    fun maidenEqualToSurnameIsIgnored() {
        val person = Person().apply {
            addName(Name().apply { value = "Ivan /Petrov/" })
            addName(Name().apply { value = "Ivan /Petrov/"; type = "birth" })
        }
        assertNull(ProfileFields.of(person).maiden)
    }

    @Test
    fun aboutKeepsLineBreaks() {
        val person = Person().apply { GeniMapper.setTag(this, GeniMapper.ABOUT_TAG, " Line one\r\n\r\nLine  two ") }
        assertEquals("Line one\n\nLine  two", ProfileFields.of(person).about)
    }
}
