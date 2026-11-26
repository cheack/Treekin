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
        assertNull(GeniParams.parseDate("BEF 1900"))
        assertNull(GeniParams.parseDate("BET 1900 AND 1910"))
        assertNull(GeniParams.parseDate("5 1990"))
        assertNull(GeniParams.parseDate("32 JAN 1990"))
        assertNull(GeniParams.parseDate("spring 1990"))
        assertNull(GeniParams.parseDate(""))
        assertNull(GeniParams.parseDate(null))
    }

    private val ivan = ProfileFields.EMPTY.copy(given = "Ivan", surname = "User", sex = "M", birth = EventFields("12 MAR 1960", "Moscow, Russia"))
    private val location = listOf("city", "county", "state", "country").map { "birth[location][$it]" to "" }

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
    fun placeClearsStructuredParts() = assertEquals(
        listOf("birth[location][place_name]" to "Kazan") + location,
        GeniParams.profile(ivan, ivan.copy(birth = EventFields("12 MAR 1960", "Kazan")))
    )

    @Test
    fun removedEventClearsDateAndPlace() = assertEquals(
        listOf("birth[date][day]" to "", "birth[date][month]" to "", "birth[date][year]" to "", "birth[date][circa]" to "false",
            "birth[location][place_name]" to "") + location,
        GeniParams.profile(ivan, ivan.copy(birth = null))
    )

    @Test
    fun unparseableDateKeepsGeniDate() {
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.profile(ivan, ivan.copy(birth = EventFields("BEF 1900", "Moscow, Russia"))))
        assertEquals(listOf("birth[location][place_name]" to "Kazan") + location,
            GeniParams.profile(ivan, ivan.copy(birth = EventFields("BEF 1900", "Kazan"))))
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
            "burial[location][place_name]" to "") + listOf("city", "county", "state", "country").map { "burial[location][$it]" to "" } +
            listOf("occupation" to "", "about_me" to ""), params.filter { it.first != "is_alive" })
        assertTrue(params.none { it.first.startsWith("birth") || it.first.startsWith("death") || it.first.endsWith("_name") || it.first == "gender" })
    }
}
