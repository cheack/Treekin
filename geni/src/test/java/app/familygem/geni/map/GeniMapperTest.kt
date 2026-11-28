package app.familygem.geni.map

import app.familygem.geni.Fixtures
import app.familygem.geni.api.GeniDate
import app.familygem.geni.api.GeniLocation
import app.familygem.geni.api.GeniProfile
import app.familygem.geni.api.GeniUnion
import app.familygem.geni.api.parseResults
import org.folg.gedcom.model.EventFact
import org.folg.gedcom.model.Family
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Note
import org.folg.gedcom.model.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class GeniMapperTest {

    private val profiles = parseResults(Fixtures.read("profiles-batch.json"), GeniProfile::class.java).associateBy { it.id }
    private val unions = parseResults(Fixtures.read("unions-batch.json"), GeniUnion::class.java).associateBy { it.id }

    private fun Person.fact(tag: String) = eventsFacts.single { it.tag == tag }
    private fun Person.mapped(id: String) = apply { GeniMapper.applyProfile(Gedcom(), this, profiles.getValue(id)) }

    @Test
    fun fullDate() = assertEquals("12 MAR 1960", GeniMapper.gedcomDate(GeniDate(1960, 3, 12, null)))

    @Test
    fun monthYear() = assertEquals("JUN 2012", GeniMapper.gedcomDate(GeniDate(2012, 6, null, null)))

    @Test
    fun yearOnly() = assertEquals("2014", GeniMapper.gedcomDate(GeniDate(2014, null, null, null)))

    @Test
    fun circa() = assertEquals("ABT 1960", GeniMapper.gedcomDate(GeniDate(1960, null, null, true)))

    @Test
    fun beforeAndAfter() {
        assertEquals("BEF 1900", GeniMapper.gedcomDate(GeniDate(1900, null, null, null, "before")))
        assertEquals("AFT 3 MAY 1900", GeniMapper.gedcomDate(GeniDate(1900, 5, 3, false, "after")))
    }

    @Test
    fun betweenKeepsEachEndsApproximation() {
        assertEquals("BET ABT 1840 AND 1845", GeniMapper.gedcomDate(GeniDate(1840, null, null, true, "between", 1845, null, null, false)))
        assertEquals("BET 1840 AND ABT JUN 1845", GeniMapper.gedcomDate(GeniDate(1840, null, null, false, "between", 1845, 6, null, true)))
    }

    @Test
    fun betweenWithoutEndIsTheStartDate() =
        assertEquals("1840", GeniMapper.gedcomDate(GeniDate(1840, null, null, null, "between")))

    @Test
    fun rangeRoundTripsThroughText() {
        listOf("BEF 1900", "AFT ABT 1900", "BET ABT 1840 AND 1845", "BET 12 MAR 1840 AND ABT JUN 1845").forEach {
            assertEquals(it, GeniMapper.gedcomDate(GeniParams.parseDate(it)))
        }
        assertEquals("BET 1840 AND 1845", GeniMapper.gedcomDate(GeniParams.parseDate("FROM 1840 TO 1845")))
    }

    @Test
    fun noYearIsNull() {
        assertNull(GeniMapper.gedcomDate(GeniDate(null, 5, 3, null)))
        assertNull(GeniMapper.gedcomDate(null))
    }

    @Test
    fun placeJoinsParts() = assertEquals(
        "Vvedenskoye Cemetery, Moscow",
        GeniMapper.place(GeniLocation(placeName = "Vvedenskoye Cemetery", city = "Moscow", county = null, state = " ", country = null))
    )

    @Test
    fun profileMapsNamesSexEvents() {
        val father = Person().mapped("profile-36431")
        assertEquals("Ivan /User/", father.names.single().value)
        assertEquals("M", father.fact("SEX").value)
        assertEquals("12 MAR 1960", father.fact("BIRT").date)
        assertEquals("Moscow, Russia", father.fact("BIRT").place)
        assertEquals("15 JAN 2020", father.fact("DEAT").date)
        assertEquals("Vvedenskoye Cemetery, Moscow", father.fact("BURI").place)
        assertEquals("Teacher", father.fact("OCCU").value)
        assertEquals("Taught physics at school No. 5 for 35 years.", GeniMapper.getTag(father, GeniMapper.ABOUT_TAG))
    }

    @Test
    fun maidenNameAddsBirthName() {
        val mother = Person().mapped("profile-36432")
        assertEquals(listOf("Maria /User/", "Maria /Smirnova/"), mother.names.map { it.value })
        assertEquals("birth", mother.names[1].type)
        assertEquals("Masha", mother.names[0].nickname)
    }

    @Test
    fun deadWithoutDeathEventGetsDeatY() {
        val person = Person()
        GeniMapper.applyProfile(Gedcom(), person, profiles.getValue("profile-36432").copy(isAlive = false))
        assertEquals("Y", person.fact("DEAT").value)
    }

    @Test
    fun applyProfileKeepsOtherData() {
        val person = Person()
        person.addNote(Note().apply { value = "My note" })
        person.addEventFact(EventFact().apply { tag = "RESI"; place = "Kazan" })
        person.mapped("profile-36431")
        assertEquals("My note", person.notes.single().value)
        assertEquals("Kazan", person.fact("RESI").place)
    }

    @Test
    fun applyProfileTwiceDoesNotDuplicateFacts() {
        val father = Person().mapped("profile-36431").mapped("profile-36431")
        assertEquals(1, father.names.size)
        assertEquals(1, father.eventsFacts.count { it.tag == "BIRT" })
        assertEquals("Taught physics at school No. 5 for 35 years.", GeniMapper.getTag(father, GeniMapper.ABOUT_TAG))
    }

    @Test
    fun unionLinksPartnersAndChildren() {
        val gedcom = Gedcom()
        val people = listOf("profile-36430", "profile-36433", "profile-36434", "profile-36436", "profile-36437").mapIndexed { i, id ->
            Person().apply { this.id = "I${i + 1}"; GeniMapper.applyProfile(gedcom, this, profiles.getValue(id)); GeniMapper.setGeniId(this, id); gedcom.addPerson(this) }
        }.associateBy { GeniMapper.getGeniId(it)!! }
        val family = Family().apply { id = "F1"; gedcom.addFamily(this) }
        GeniMapper.applyUnion(gedcom, family, unions.getValue("union-10106")) { people[it] }
        assertEquals("I1", family.husbandRefs.single().ref)
        assertEquals("I2", family.wifeRefs.single().ref)
        assertEquals(listOf("I3", "I4", "I5"), family.childRefs.map { it.ref })
        assertEquals("F1", people.getValue("profile-36434").parentFamilyRefs.single().ref)
        assertEquals("F1", people.getValue("profile-36430").spouseFamilyRefs.single().ref)
        assertEquals("JUN 2012", family.eventsFacts.single { it.tag == "MARR" }.date)
        GeniMapper.applyUnion(gedcom, family, unions.getValue("union-10106")) { people[it] }
        assertEquals(3, family.childRefs.size)
        assertEquals(1, people.getValue("profile-36434").parentFamilyRefs.size)
    }

    @Test
    fun unknownGenderPartnerTakesFreeSlot() {
        val gedcom = Gedcom()
        val a = Person().apply { id = "I1" }.also { gedcom.addPerson(it) }
        val b = Person().apply { id = "I2" }.also { gedcom.addPerson(it) }
        val family = Family().apply { id = "F1" }
        val union = GeniUnion("union-1", "spouse", listOf("profile-a", "profile-b"), null, null, null)
        GeniMapper.applyUnion(gedcom, family, union) { mapOf("profile-a" to a, "profile-b" to b)[it] }
        assertEquals("I1", family.husbandRefs.single().ref)
        assertEquals("I2", family.wifeRefs.single().ref)
    }

    @Test
    fun geniIdTagRoundTrip() {
        val person = Person()
        GeniMapper.setGeniId(person, "profile-1")
        GeniMapper.setGeniId(person, "profile-2")
        assertEquals("profile-2", GeniMapper.getGeniId(person))
    }

    @Test
    fun keepsLocalEditWhenGeniUnchanged() {
        val geni = profiles.getValue("profile-36431")
        val father = Person().mapped("profile-36431")
        father.names[0].value = "Ivan /Petrov/"
        father.fact("BIRT").date = "13 MAR 1960"
        GeniMapper.applyProfile(Gedcom(), father, geni, previous = geni)
        assertEquals("Ivan /Petrov/", father.names.single().value)
        assertEquals("13 MAR 1960", father.fact("BIRT").date)
    }

    @Test
    fun appliesOnlyTheFieldsGeniChanged() {
        val geni = profiles.getValue("profile-36431")
        val father = Person().mapped("profile-36431")
        father.names[0].value = "Ivan /Petrov/"
        GeniMapper.applyProfile(Gedcom(), father, geni.copy(occupation = "Director"), previous = geni)
        assertEquals("Director", father.fact("OCCU").value)
        assertEquals("Ivan /Petrov/", father.names.single().value)
    }

    @Test
    fun keepsSubRecordsOfUpdatedFact() {
        val geni = profiles.getValue("profile-36431")
        val father = Person().mapped("profile-36431")
        val birth = father.fact("BIRT")
        birth.addNote(Note().apply { value = "From the parish book" })
        val changed = geni.copy(birth = geni.birth!!.copy(date = geni.birth!!.date!!.copy(day = 14)))
        GeniMapper.applyProfile(Gedcom(), father, changed, previous = geni)
        assertSame(birth, father.fact("BIRT"))
        assertEquals("14 MAR 1960", birth.date)
        assertEquals("From the parish book", birth.notes.single().value)
    }

    @Test
    fun keepsLocalBirthWhenGeniNeverHadOne() {
        val geni = profiles.getValue("profile-36431").copy(birth = null)
        val person = Person().apply { GeniMapper.applyProfile(Gedcom(), this, geni) }
        person.addEventFact(EventFact().apply { tag = "BIRT"; date = "1960" })
        GeniMapper.applyProfile(Gedcom(), person, geni, previous = geni)
        assertEquals("1960", person.fact("BIRT").date)
    }

    @Test
    fun keepsSexWhenGeniHasNoGender() {
        val person = Person().apply { addEventFact(EventFact().apply { tag = "SEX"; value = "M" }) }
        GeniMapper.applyProfile(Gedcom(), person, profiles.getValue("profile-36431").copy(gender = null))
        assertEquals("M", person.fact("SEX").value)
    }

    @Test
    fun clearedGenderOnGeniClearsTheSex() {
        val person = Person().apply { addEventFact(EventFact().apply { tag = "SEX"; value = "M" }) }
        val male = profiles.getValue("profile-36431")
        GeniMapper.applyProfile(Gedcom(), person, male.copy(gender = null), previous = male)
        assertEquals("U", person.fact("SEX").value)
        GeniMapper.applyProfile(Gedcom(), person, male, previous = male.copy(gender = null))
        assertEquals("M", person.fact("SEX").value)
    }

    @Test
    fun privacyIsAppliedFromGeni() {
        val fixture = parseResults(Fixtures.read("profiles-batch-privacy.json"), GeniProfile::class.java).associateBy { it.id }
        val person = Person().apply { GeniMapper.applyProfile(Gedcom(), this, fixture.getValue("profile-101")) }
        assertEquals(true, GeniMapper.getBoolean(person, GeniMapper.PUBLIC_TAG))
        // An explicit false is kept apart from missing
        val living = Person().apply { GeniMapper.applyProfile(Gedcom(), this, fixture.getValue("profile-100")) }
        assertEquals(false, GeniMapper.getBoolean(living, GeniMapper.PUBLIC_TAG))
        assertNull(GeniMapper.getBoolean(living, GeniMapper.LOCKED_TAG))
        // Geni changes it, a later load follows; a local change in the same group is kept
        val changed = fixture.getValue("profile-101").copy(public = false)
        GeniMapper.applyProfile(Gedcom(), person, changed, previous = fixture.getValue("profile-101"))
        assertEquals(false, GeniMapper.getBoolean(person, GeniMapper.PUBLIC_TAG))
        GeniMapper.applyProfile(Gedcom(), person, fixture.getValue("profile-101"), previous = changed, keep = setOf(ProfileGroup.PUBLIC))
        assertEquals(false, GeniMapper.getBoolean(person, GeniMapper.PUBLIC_TAG))
    }

    @Test
    fun lockedIsReadOnlyAndMissingStaysMissing() {
        val fixture = parseResults(Fixtures.read("profile-merge-note-forms.json"), GeniProfile::class.java).associateBy { it.id }
        val locked = Person().apply { GeniMapper.applyProfile(Gedcom(), this, fixture.getValue("profile-110")) }
        assertEquals(true, GeniMapper.getBoolean(locked, GeniMapper.LOCKED_TAG))
        val plain = Person().apply { GeniMapper.applyProfile(Gedcom(), this, fixture.getValue("profile-112")) }
        assertNull(GeniMapper.getBoolean(plain, GeniMapper.LOCKED_TAG))
        assertNull(GeniMapper.getBoolean(plain, GeniMapper.PUBLIC_TAG))
    }

    @Test
    fun clearedEventKeepsAFactWithLocalData() {
        val person = Person()
        val withBirth = com.google.gson.Gson().fromJson("""{"id":"profile-1","birth":{"date":{"year":1960}}}""", GeniProfile::class.java)
        GeniMapper.applyProfile(Gedcom(), person, withBirth)
        person.fact("BIRT").addNote(org.folg.gedcom.model.Note().apply { value = "Parish book" })
        GeniMapper.applyProfile(Gedcom(), person, withBirth.copy(birth = null), withBirth)
        assertEquals("Parish book", person.fact("BIRT").notes.single().value)
        assertEquals(null, person.fact("BIRT").date)
        assertEquals(null, app.familygem.geni.map.ProfileFields.of(person).birth) // An empty fact counts as no birth
    }

    @Test
    fun clearedOccupationKeepsLocalDateAndPlace() {
        val person = Person()
        val worker = com.google.gson.Gson().fromJson("""{"id":"profile-1","occupation":"Miner"}""", GeniProfile::class.java)
        GeniMapper.applyProfile(Gedcom(), person, worker)
        person.fact("OCCU").date = "1950"
        person.fact("OCCU").place = "Donetsk"
        GeniMapper.applyProfile(Gedcom(), person, worker.copy(occupation = null), worker)
        assertEquals(null, person.fact("OCCU").value)
        assertEquals("1950", person.fact("OCCU").date)
        assertEquals("Donetsk", person.fact("OCCU").place)
    }

    @Test
    fun updatedOccupationKeepsLocalDateAndPlace() {
        val person = Person()
        val worker = com.google.gson.Gson().fromJson("""{"id":"profile-1","occupation":"Miner"}""", GeniProfile::class.java)
        GeniMapper.applyProfile(Gedcom(), person, worker)
        person.fact("OCCU").date = "1950"
        person.fact("OCCU").place = "Donetsk"
        GeniMapper.applyProfile(Gedcom(), person, worker.copy(occupation = "Engineer"), worker)
        assertEquals("Engineer", person.fact("OCCU").value)
        assertEquals("1950", person.fact("OCCU").date)
        assertEquals("Donetsk", person.fact("OCCU").place)
    }

    @Test
    fun importStoresNameParts() {
        val person = Person()
        val mary = com.google.gson.Gson().fromJson("""{"id":"profile-1","first_name":"Mary Ann","middle_name":"Louise","last_name":"Example"}""", GeniProfile::class.java)
        GeniMapper.applyProfile(Gedcom(), person, mary)
        assertEquals("Mary Ann Louise /Example/", person.names.single().value)
        assertEquals(GivenNameParts("Mary Ann", "Louise"), GeniMapper.getGivenNameParts(person))
        assertEquals("", GeniMapper.getTag(person, GeniMapper.DISPLAY_NAME_TAG)) // Explicitly none
        assertEquals(GivenNameParts("Mary Ann", "Louise"), ProfileFields.of(person).givenParts)
        // Edited elsewhere (the name screen): the stale parts are ignored
        person.names.single().value = "Maria /Example/"
        assertNull(ProfileFields.of(person).givenParts)
    }

    @Test
    fun splitChangeOnGeniUpdatesParts() {
        val person = Person()
        val mary = com.google.gson.Gson().fromJson("""{"id":"profile-1","first_name":"Mary Ann","middle_name":"Louise"}""", GeniProfile::class.java)
        GeniMapper.applyProfile(Gedcom(), person, mary)
        GeniMapper.applyProfile(Gedcom(), person, mary.copy(firstName = "Mary", middleName = "Ann Louise"), mary)
        assertEquals(GivenNameParts("Mary", "Ann Louise"), GeniMapper.getGivenNameParts(person))
    }

    @Test
    fun prefillsPartsFromSnapshotWhenGivenMatches() {
        val snapshot = """{"id":"profile-1","first_name":"Mary Ann","middle_name":"Louise","last_name":"Example"}"""
        val legacy = Person().apply { addName(org.folg.gedcom.model.Name().apply { value = "Mary Ann Louise /Example/" }) }
        assertEquals(GivenNameParts("Mary Ann", "Louise"), GeniMapper.givenNamePartsFor(legacy, snapshot))
        legacy.names.single().value = "Maria Louise /Example/"
        assertEquals(GivenNameParts("Maria", "Louise"), GeniMapper.givenNamePartsFor(legacy, snapshot))
        assertEquals(GivenNameParts("Maria", "Louise"), GeniMapper.givenNamePartsFor(legacy, null))
    }

    @Test
    fun clearedEventKeepsAFactWithLocalDetails() {
        val person = Person()
        val baptized = com.google.gson.Gson().fromJson("""{"id":"profile-1","baptism":{"date":{"year":1846}}}""", GeniProfile::class.java)
        GeniMapper.applyProfile(Gedcom(), person, baptized)
        person.fact("BAPM").type = "Catholic"
        GeniMapper.applyProfile(Gedcom(), person, baptized.copy(baptism = null), baptized)
        assertEquals("Catholic", person.fact("BAPM").type)
        assertNull(person.fact("BAPM").date)
        assertNull(ProfileFields.of(person).baptism)
    }

    @Test
    fun clearedOccupationKeepsAFactWithOtherDetails() {
        val worker = com.google.gson.Gson().fromJson("""{"id":"profile-1","occupation":"Miner"}""", GeniProfile::class.java)
        listOf<EventFact.() -> Unit>({ rin = "R1" }, { phone = "123" }, { putExtension("custom", "x") }).forEach { detail ->
            val person = Person()
            GeniMapper.applyProfile(Gedcom(), person, worker)
            person.fact("OCCU").detail()
            GeniMapper.applyProfile(Gedcom(), person, worker.copy(occupation = null), worker)
            assertNull(person.fact("OCCU").value)
        }
    }
}
