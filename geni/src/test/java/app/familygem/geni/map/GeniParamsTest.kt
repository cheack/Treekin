package app.familygem.geni.map

import app.familygem.geni.api.GeniDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class GeniParamsTest {

    @Test
    fun parsesDates() {
        assertEquals(GeniDate(1960, 3, 12, false), GeniParams.parseDate("12 MAR 1960"))
        assertEquals(GeniDate(2012, 6, null, false), GeniParams.parseDate("jun 2012"))
        assertEquals(GeniDate(1960, null, null, true), GeniParams.parseDate("ABT 1960"))
        assertEquals(GeniDate(1890, 1, 2, true), GeniParams.parseDate(" est 2 Jan 1890 "))
        assertEquals(GeniDate(1700, null, null, true), GeniParams.parseDate("CAL 1700"))
        assertNull(GeniParams.parseDate("FROM 1900"))
        assertNull(GeniParams.parseDate("TO 1900"))
        assertNull(GeniParams.parseDate("BET 1900 AND"))
        assertNull(GeniParams.parseDate("BET 1900"))
        assertNull(GeniParams.parseDate("INT 1900 (about)"))
        assertNull(GeniParams.parseDate("5 1990"))
        assertNull(GeniParams.parseDate("32 JAN 1990"))
        assertNull(GeniParams.parseDate("spring 1990"))
        assertNull(GeniParams.parseDate(""))
        assertNull(GeniParams.parseDate(null))
    }

    @Test
    fun parsesRanges() {
        assertEquals(GeniDate(1900, null, null, false, range = "before"), GeniParams.parseDate("BEF 1900"))
        assertEquals(GeniDate(1900, 5, 2, false, range = "after"), GeniParams.parseDate("aft 2 may 1900"))
        assertEquals(GeniDate(1840, null, null, false, "between", 1845, null, null, false), GeniParams.parseDate("BET 1840 AND 1845"))
        assertEquals(GeniDate(1840, 3, null, true, "between", 1845, 7, 4, true), GeniParams.parseDate("BET ABT MAR 1840 AND est 4 JUL 1845"))
        assertEquals(GeniDate(1840, null, null, false, "between", 1845, null, null, true), GeniParams.parseDate("FROM 1840 TO ABT 1845"))
    }

    private val ivan = ProfileFields.EMPTY.copy(given = "Ivan", surname = "User", sex = "M", birth = EventFields("12 MAR 1960", "Moscow, Russia"))
    @Test
    fun sendsOnlyChangedGroups() =
        assertEquals(listOf("occupation" to "Driver"), GeniParams.profile(ivan, ivan.copy(occupation = "Driver")))

    @Test
    fun unchangedMakesNoParams() = assertEquals(emptyList<Pair<String, String>>(), GeniParams.profile(ivan, ivan.copy()))

    @Test
    fun dateIsSentWhole() = assertEquals(
        listOf("birth[date][day]" to "", "birth[date][month]" to "", "birth[date][year]" to "1961", "birth[date][circa]" to "false"),
        GeniParams.profile(ivan, ivan.copy(birth = EventFields("1961", "Moscow, Russia")))
    )

    @Test
    fun circaDate() = assertEquals(
        listOf("birth[date][day]" to "2", "birth[date][month]" to "1", "birth[date][year]" to "1960", "birth[date][circa]" to "true"),
        GeniParams.profile(ivan, ivan.copy(birth = EventFields("ABT 2 JAN 1960", "Moscow, Russia")))
    )

    @Test
    fun placeEditSendsOnlyThePlaceName() = assertEquals(
        listOf("birth[location][place_name]" to "Kazan"),
        GeniParams.profile(ivan, ivan.copy(birth = EventFields("12 MAR 1960", "Kazan")))
    )

    @Test
    fun removedEventClearsDateAndPlace() = assertEquals(
        listOf("birth[date][day]" to "", "birth[date][month]" to "", "birth[date][year]" to "", "birth[date][circa]" to "false",
            "birth[location][place_name]" to ""),
        GeniParams.profile(ivan, ivan.copy(birth = null))
    )

    @Test
    fun unparseableDateKeepsGeniDate() {
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.profile(ivan, ivan.copy(birth = EventFields("FROM 1900", "Moscow, Russia"))))
        assertEquals(listOf("birth[location][place_name]" to "Kazan"),
            GeniParams.profile(ivan, ivan.copy(birth = EventFields("FROM 1900", "Kazan"))))
    }

    private val rangeParts = listOf("range", "end_day", "end_month", "end_year", "end_circa")

    @Test
    fun rangeSendsAllRangeParts() {
        val params = GeniParams.profile(ivan, ivan.copy(birth = EventFields("BET ABT 1840 AND 1845", "Moscow, Russia"))).toMap()
        assertEquals("between", params["birth[date][range]"])
        assertEquals("1840", params["birth[date][year]"])
        assertEquals("true", params["birth[date][circa]"])
        assertEquals("1845", params["birth[date][end_year]"])
        assertEquals("false", params["birth[date][end_circa]"])
        assertEquals("", params["birth[date][end_month]"])
    }

    @Test
    fun beforeSendsRangeAndNoEnd() {
        val params = GeniParams.profile(ivan, ivan.copy(birth = EventFields("BEF 1900", "Moscow, Russia"))).toMap()
        assertEquals("before", params["birth[date][range]"])
        assertEquals("1900", params["birth[date][year]"])
        assertEquals("", params["birth[date][end_year]"])
    }

    @Test
    fun singleDateReplacingRangeClearsTheRangeParts() {
        val ranged = ivan.copy(birth = EventFields("BET 1840 AND 1845", null))
        val params = GeniParams.profile(ranged, ranged.copy(birth = EventFields("1841", null))).toMap()
        assertEquals("1841", params["birth[date][year]"])
        rangeParts.filter { it != "end_circa" }.forEach { assertEquals(it, "", params["birth[date][$it]"]) }
        assertEquals("false", params["birth[date][end_circa]"])
    }

    @Test
    fun clearingARangeClearsTheRangeParts() {
        val ranged = ivan.copy(birth = EventFields("BEF 1900", null))
        val params = GeniParams.profile(ranged, ranged.copy(birth = null)).toMap()
        assertEquals("", params["birth[date][year]"])
        assertEquals("", params["birth[date][range]"])
    }

    @Test
    fun plainDateChangeSendsNoRangeParts() {
        val params = GeniParams.profile(ivan, ivan.copy(birth = EventFields("1961", "Moscow, Russia"))).toMap()
        rangeParts.forEach { assertFalse(it, "birth[date][$it]" in params) }
    }

    @Test
    fun legacyStartOfRangeIsNoChange() {
        val geni = ivan.copy(birth = EventFields("BET 1840 AND 1845", "Moscow, Russia"))
        val legacy = geni.copy(birth = EventFields("1840", "Moscow, Russia"))
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.profile(geni, legacy))
        // A deliberate change of the date still goes
        assertEquals("1841", GeniParams.profile(geni, legacy.copy(birth = EventFields("1841", "Moscow, Russia"))).toMap()["birth[date][year]"])
    }

    @Test
    fun unionRangeUsesTheSameParts() {
        val params = GeniParams.union(UnionFields.EMPTY, UnionFields(EventFields("BET 1900 AND 1901", null), null)).toMap()
        assertEquals("between", params["marriage[date][range]"])
        assertEquals("1901", params["marriage[date][end_year]"])
    }

    @Test
    fun deathSendsIsAliveFalse() {
        val params = GeniParams.profile(ivan, ivan.copy(dead = true, death = EventFields("2001", "Omsk"), causeOfDeath = "Flu"))
        assertEquals("false", params.toMap()["is_alive"])
        assertEquals(0, params.indexOfFirst { it.first == "is_alive" }) // Before death, which Geni ignores while alive
        assertEquals("2001", params.toMap()["death[date][year]"])
        assertEquals("Omsk", params.toMap()["death[location][place_name]"])
        assertEquals("Flu", params.toMap()["cause_of_death"])
    }

    @Test
    fun deadWithoutDateSendsOnlyIsAlive() =
        assertEquals(listOf("is_alive" to "false"), GeniParams.profile(ivan, ivan.copy(dead = true)))

    @Test
    fun aliveAgainSendsOnlyIsAlive() {
        val dead = ivan.copy(dead = true, death = EventFields("2001", "Omsk"), causeOfDeath = "Flu")
        assertEquals(listOf("is_alive" to "true"), GeniParams.profile(dead, ivan))
    }

    @Test
    fun deathDateChangeOfDeadPersonSendsIsAliveToo() {
        val dead = ivan.copy(dead = true, death = EventFields("2001", null))
        val params = GeniParams.profile(dead, dead.copy(death = EventFields("2002", null)))
        assertEquals(listOf("is_alive" to "false", "death[date][day]" to "", "death[date][month]" to "", "death[date][year]" to "2002",
            "death[date][circa]" to "false"), params)
    }

    @Test
    fun namesSplitGivenIntoFirstAndMiddle() = assertEquals(
        listOf("first_name" to "Ivan", "middle_name" to "Petrovich Jr", "nicknames" to "Vanya, Vano"),
        GeniParams.profile(ivan, ivan.copy(given = "Ivan Petrovich Jr", nicknames = "Vanya, Vano"))
    )

    @Test
    fun unchangedGivenIsNotResent() {
        // Geni may hold "Mary Ann" as first_name; re-sending it would split it into first and middle
        val mary = ivan.copy(given = "Mary Ann")
        assertEquals(listOf("nicknames" to "Molly"), GeniParams.profile(mary, mary.copy(nicknames = "Molly")))
        assertEquals(listOf("last_name" to "", "maiden_name" to "User"), GeniParams.profile(mary, mary.copy(surname = null, maiden = "User")))
    }

    @Test
    fun genderIsSentOnlyWhenKnown() {
        assertEquals(listOf("gender" to "female"), GeniParams.profile(ivan, ivan.copy(sex = "F")))
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.profile(ivan, ivan.copy(sex = null)))
    }

    @Test
    fun unknownSexClearsAKnownOne() {
        assertEquals(listOf("gender" to "unknown"), GeniParams.profile(ivan, ivan.copy(sex = "U")))
        // Back to a sex: a normal write
        assertEquals(listOf("gender" to "male"), GeniParams.profile(ivan.copy(sex = "U"), ivan))
    }

    @Test
    fun unknownSexAgainstUnknownMakesNoWrite() {
        // A legacy "SEX U" placeholder, a snapshot without gender, a missing SEX fact: none writes
        val unknown = ivan.copy(sex = "U")
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.profile(unknown, unknown.copy(occupation = null)))
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.profile(unknown, unknown.copy(sex = null)))
        assertTrue(GeniParams.profile(ProfileFields.EMPTY, ProfileFields.EMPTY.copy(given = "A", sex = "U"), isNew = true).none { it.first == "gender" })
    }

    @Test
    fun privacyIsSentOnlyWhenSetAndChanged() {
        val dead = ivan.copy(dead = true, isPublic = false)
        assertEquals(listOf("public" to "true"), GeniParams.profile(dead, dead.copy(isPublic = true)))
        assertEquals(listOf("public" to "false"), GeniParams.profile(dead.copy(isPublic = true), dead))
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.profile(dead, dead.copy()))
        // An untouched control (no tag) inherits Geni's value: no write
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.profile(dead, dead.copy(isPublic = null)))
    }

    @Test
    fun livingProfileIsNeverSentPublic() {
        val living = ivan.copy(isPublic = false)
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.profile(living, living.copy(isPublic = true)))
        assertEquals(listOf("public" to "false"), GeniParams.profile(living.copy(isPublic = true), living))
    }

    @Test
    fun publicFollowsIsAliveWhenMadeDeceasedInOneSave() {
        val living = ivan.copy(isPublic = false)
        val keys = GeniParams.profile(living, living.copy(isPublic = true, dead = true)).map { it.first }
        assertTrue(keys.indexOf("is_alive") < keys.indexOf("public"))
    }

    @Test
    fun newProfilePrivacy() {
        val base = ProfileFields.EMPTY.copy(given = "A", dead = true)
        assertTrue(GeniParams.profile(ProfileFields.EMPTY, base, isNew = true).none { it.first == "public" })
        assertEquals("false", GeniParams.profile(ProfileFields.EMPTY, base.copy(isPublic = false), isNew = true).toMap()["public"])
        assertEquals("true", GeniParams.profile(ProfileFields.EMPTY, base.copy(isPublic = true), isNew = true).toMap()["public"])
    }

    @Test
    fun newProfileSendsIsAlive() {
        val params = GeniParams.profile(ProfileFields.EMPTY, ProfileFields.EMPTY.copy(given = "Petr", sex = "M"), isNew = true).toMap()
        assertEquals("true", params["is_alive"])
        assertEquals("Petr", params["first_name"])
        assertEquals("male", params["gender"])
        assertFalse(params.keys.any { it.startsWith("birth") || it.startsWith("death") || it == "occupation" })
    }

    @Test
    fun newDeadProfileSendsDeath() {
        val params = GeniParams.profile(ProfileFields.EMPTY, ProfileFields.EMPTY.copy(given = "Old", dead = true,
            death = EventFields("1900", null)), isNew = true).toMap()
        assertEquals("false", params["is_alive"])
        assertEquals("1900", params["death[date][year]"])
    }

    @Test
    fun unionNeverSendsStatus() {
        val old = UnionFields(EventFields("2012", "Kazan"), null)
        val params = GeniParams.union(old, UnionFields(EventFields("1 JUN 2012", "Kazan"), EventFields("2020", null)))
        assertEquals(listOf("marriage[date][day]" to "1", "marriage[date][month]" to "6", "marriage[date][year]" to "2012",
            "marriage[date][circa]" to "false", "divorce[date][day]" to "", "divorce[date][month]" to "", "divorce[date][year]" to "2020",
            "divorce[date][circa]" to "false"), params)
        assertTrue(params.none { it.first == "status" })
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.union(old, old.copy()))
    }

    @Test
    fun clearingFieldsSendsEmptyValuesOnly() {
        val old = ivan.copy(occupation = "Miner", dead = true, death = EventFields("1950", null), causeOfDeath = "Fever",
            about = "Line one\nLine two", burial = EventFields("1950", "Kazan"))
        val params = GeniParams.profile(old, old.copy(occupation = null, causeOfDeath = null, about = null, burial = null))
        assertEquals(listOf("cause_of_death" to "",
            "burial[date][day]" to "", "burial[date][month]" to "", "burial[date][year]" to "", "burial[date][circa]" to "false",
            "burial[location][place_name]" to "", "occupation" to "", "about_me" to ""), params.filter { it.first != "is_alive" })
        assertTrue(params.none { it.first.startsWith("birth") || it.first.startsWith("death") || it.first.endsWith("_name") || it.first == "gender" })
    }

    private val mary = ivan.copy(given = "Mary Ann Louise", givenParts = GivenNameParts("Mary Ann", "Louise"))

    @Test
    fun keepsMultiwordFirstNameWhenMiddleNameChanges() = assertEquals(
        listOf("middle_name" to "Rose"),
        GeniParams.profile(mary, mary.copy(given = "Mary Ann Rose", givenParts = GivenNameParts("Mary Ann", "Rose")))
    )

    @Test
    fun changedSplitSendsBothParts() = assertEquals(
        listOf("first_name" to "Mary", "middle_name" to "Ann Louise"),
        GeniParams.profile(mary, mary.copy(givenParts = GivenNameParts("Mary", "Ann Louise")))
    )

    @Test
    fun changedLegacyGivenFallsBackToFirstSpace() = assertEquals(
        listOf("first_name" to "Maria", "middle_name" to "Louise"),
        GeniParams.profile(mary, mary.copy(given = "Maria Louise", givenParts = null))
    )

    /** Shape read back from the sandbox on 2026-11-26: `display_name` is present only while an override is set. */
    private val maryJson = """{"id":"profile-1","first_name":"Mary Ann","middle_name":"Louise","last_name":"Example","display_name":"M. A. Example","name":"M. A. Example","is_alive":true}"""

    @Test
    fun displayNameSetAndClearRoundTrip() {
        val person = org.folg.gedcom.model.Person()
        GeniMapper.applyProfile(org.folg.gedcom.model.Gedcom(), person, com.google.gson.Gson().fromJson(maryJson, app.familygem.geni.api.GeniProfile::class.java))
        assertEquals("M. A. Example", GeniMapper.getTag(person, GeniMapper.DISPLAY_NAME_TAG))
        val snapshot = ProfileFields.ofSnapshot(maryJson)!!
        assertEquals("M. A. Example", snapshot.displayName)
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.profile(snapshot, ProfileFields.of(person)))
        // Cleared in the editor: an empty tag is an explicit "no display name"
        GeniMapper.setTag(person, GeniMapper.DISPLAY_NAME_TAG, "")
        val params = GeniParams.profile(snapshot, ProfileFields.of(person))
        assertEquals(listOf("display_name" to ""), params)
        assertEquals("", ProfileFields.ofSnapshot(SnapshotOverlay.apply(maryJson, params, "profile-1"))!!.displayName)
        // Set again
        GeniMapper.setTag(person, GeniMapper.DISPLAY_NAME_TAG, "Mary Example")
        assertEquals(listOf("display_name" to "Mary Example"), GeniParams.profile(snapshot, ProfileFields.of(person)))
    }

    @Test
    fun legacyRecordWithoutDisplayNameSendsNoClear() {
        // Saved before display names were stored: no extension, the combined given name, no name parts
        val legacy = org.folg.gedcom.model.Person().apply {
            addName(org.folg.gedcom.model.Name().apply { value = "Mary Ann Louise /Example/" })
            addEventFact(org.folg.gedcom.model.EventFact().apply { tag = "OCCU"; value = "Driver" })
        }
        val fields = ProfileFields.of(legacy)
        assertNull(fields.displayName)
        assertNull(fields.givenParts)
        assertEquals(listOf("occupation" to "Driver"), GeniParams.profile(ProfileFields.ofSnapshot(maryJson)!!, fields))
    }

    /** Shape read back from the sandbox on 2026-11-26: a top-level `title`, present only while set. */
    private val doctorJson = """{"id":"profile-1","first_name":"Ivan","last_name":"User","title":"Dr.","name":"Ivan User"}"""

    @Test
    fun nameTitleRoundTripsIndependentlyOfGedcomTitle() {
        val person = org.folg.gedcom.model.Person()
        GeniMapper.applyProfile(org.folg.gedcom.model.Gedcom(), person, com.google.gson.Gson().fromJson(doctorJson, app.familygem.geni.api.GeniProfile::class.java))
        assertEquals("Dr.", GeniMapper.getTag(person, GeniMapper.NAME_TITLE_TAG))
        val snapshot = ProfileFields.ofSnapshot(doctorJson)!!
        assertEquals("Dr.", snapshot.title)
        // The GEDCOM title fact is local: it makes no write
        person.addEventFact(org.folg.gedcom.model.EventFact().apply { tag = "TITL"; value = "Professor" })
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.profile(snapshot, ProfileFields.of(person)))
        GeniMapper.setTag(person, GeniMapper.NAME_TITLE_TAG, "Prof.")
        assertEquals(listOf("title" to "Prof."), GeniParams.profile(snapshot, ProfileFields.of(person)))
        GeniMapper.setTag(person, GeniMapper.NAME_TITLE_TAG, "")
        val params = GeniParams.profile(snapshot, ProfileFields.of(person))
        assertEquals(listOf("title" to ""), params)
        assertEquals("", ProfileFields.ofSnapshot(SnapshotOverlay.apply(doctorJson, params, "profile-1"))!!.title)
        // A record from before the title was stored inherits it
        GeniMapper.setTag(person, GeniMapper.NAME_TITLE_TAG, null)
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.profile(snapshot, ProfileFields.of(person)))
    }

    @Test
    fun photoParamsHoldOnlyTheChangedFieldsWithEmptyValuesForClears() {
        val old = PhotoDetails("Old", "Old text", "ABT 3 MAY 1950")
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.photo(old, old))
        assertEquals(listOf("title" to "New"), GeniParams.photo(old, old.copy(title = "New")))
        assertEquals(listOf("title" to "", "description" to ""), GeniParams.photo(old, old.copy(title = null, description = null)))
        assertEquals(listOf("description" to "Two\nlines"), GeniParams.photo(old, old.copy(description = "Two\nlines")))
    }

    @Test
    fun photoDateParamsAreSentWholeAndClearedWithEmptyParts() {
        val old = PhotoDetails(null, null, "1950")
        assertEquals(
            listOf("date[day]" to "3", "date[month]" to "5", "date[year]" to "1951", "date[circa]" to "true"),
            GeniParams.photo(old, old.copy(date = "ABT 3 MAY 1951"))
        )
        assertEquals(
            listOf("date[day]" to "", "date[month]" to "7", "date[year]" to "1948", "date[circa]" to "false"),
            GeniParams.photo(old, old.copy(date = "JUL 1948"))
        )
        assertEquals(
            listOf("date[day]" to "", "date[month]" to "", "date[year]" to "", "date[circa]" to "false"),
            GeniParams.photo(old, old.copy(date = null))
        )
    }

    @Test
    fun aPhotoDateWithARangeOrNoSenseIsNotSent() {
        val old = PhotoDetails("T", null, "1950")
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.photo(old, old.copy(date = "BEF 1900")))
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.photo(old, old.copy(date = "BET 1940 AND 1945")))
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.photo(old, old.copy(date = "spring")))
        assertTrue(GeniParams.isPhotoDate("12 mar 1960"))
        assertTrue(GeniParams.isPhotoDate("ABT 1950"))
        assertTrue(!GeniParams.isPhotoDate("BEF 1900"))
        assertTrue(!GeniParams.isPhotoDate("12 1960"))
        // Events still take ranges
        assertTrue(GeniParams.isStorable("BEF 1900", ranges = true))
    }
}
