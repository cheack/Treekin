package app.familygem.geni.map

import app.familygem.geni.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Geni sandbox responses (2026-11-28, disposable profile-36543 and union-10107) read through the app's projections:
 * a location of city and country only, one with every part, a street-only event and parts cleared one by one.
 * The cleared fixtures are the GET after `place_name`, then `street_address2`/`street_address3`/coordinates were cleared
 * with empty values: each clear removed only its own key, a location left with coordinates alone was dropped whole,
 * and `place_name` / `street_address1` edits kept the sibling parts.
 */
class LocationFixturesTest {

    private fun profile(name: String) = ProfileFields.ofSnapshot(Fixtures.read(name))!!.withPlacesResolved()
    private fun union(name: String) = UnionFields.ofSnapshot(Fixtures.read(name))!!.withPlacesResolved()

    @Test
    fun cityAndCountryOnly() {
        val birth = profile("profile-location-city-country.json").birth!!
        assertEquals(mapOf("city" to "Kazan", "country" to "Russia"), birth.location!!.values)
        assertEquals("Kazan, Russia", birth.place)
        assertEquals("1960", birth.date)
    }

    @Test
    fun everyPartIncludingTheCoordinateZero() {
        val fields = profile("profile-location-all-parts.json")
        assertEquals(
            mapOf("place_name" to "Hospital", "city" to "Kazan", "country" to "Russia", "street_address1" to "Main1",
                "street_address2" to "Flat2", "street_address3" to "Block3", "latitude" to "55.79", "longitude" to "0"),
            fields.birth!!.location!!.values
        )
        assertEquals("Hospital, Kazan, Russia", fields.birth!!.place) // Street lines and coordinates are not in the text
    }

    @Test
    fun streetOnlyEventIsAnEventWithoutPlaceText() {
        val baptism = profile("profile-location-all-parts.json").baptism!!
        assertNull(baptism.date)
        assertNull(baptism.place)
        assertEquals(mapOf("street_address1" to "Church1"), baptism.location!!.values)
    }

    @Test
    fun clearingPartsOneByOneChangesOnlyThoseKeys() {
        val before = profile("profile-location-all-parts.json")
        val after = profile("profile-location-cleared-parts.json")
        val params = GeniParams.profile(before, after.copy(baptism = before.baptism)).toMap()
        assertEquals(
            mapOf("birth[location][place_name]" to "", "birth[location][street_address2]" to "", "birth[location][street_address3]" to "",
                "birth[location][latitude]" to "", "birth[location][longitude]" to ""), params
        )
    }

    @Test
    fun unionMarriageHasTheSameParts() {
        val marriage = union("union-location-all-parts.json").marriage!!
        assertEquals("2000", marriage.date)
        assertEquals("Hall", marriage.location!!["place_name"])
        assertEquals("Church2", marriage.location!!["street_address1"])
        assertEquals("54.2", marriage.location!!["latitude"])
        assertEquals("0", marriage.location!!["longitude"])
        val cleared = union("union-location-cleared-parts.json").marriage!!
        assertEquals(mapOf("city" to "Tula", "country" to "Russia"), cleared.location!!.values)
        assertEquals(
            mapOf("marriage[location][place_name]" to "", "marriage[location][street_address1]" to "",
                "marriage[location][latitude]" to "", "marriage[location][longitude]" to ""),
            GeniParams.union(union("union-location-all-parts.json"), union("union-location-cleared-parts.json")).toMap()
        )
    }
}
