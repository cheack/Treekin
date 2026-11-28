package app.familygem.geni.map

import app.familygem.geni.Fixtures
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read-only profile details from the saved snapshot (fixtures profile-details-*.json). */
class ProfileDetailsTest {

    private fun results() = JsonParser.parseString(Fixtures.read("profile-details-batch.json")).asJsonObject.getAsJsonArray("results")

    @Test
    fun ownProfileKeepsAnExplicitFalseAndNoVitalEvents() {
        val details = ProfileDetails.ofSnapshot(results()[0].toString())!!
        assertEquals(true, details.claimed)
        assertEquals(false, details.curator)
        assertNull(details.bigTree)
        assertNull(details.mergePending)
        assertEquals("yourself", details.relationship)
        assertEquals("https://sandbox.geni.com/people/V-P/1000001", details.profileUrl)
        // Birth, marriage and the child's birth are the card's own facts and relatives
        assertTrue(details.events.isEmpty())
        assertNull(details.residence)
        assertNull(details.managers)
    }

    @Test
    fun otherProfileHasResidenceManagersAndRelationship() {
        val details = ProfileDetails.ofSnapshot(results()[1].toString())!!
        assertEquals("Kazan, Russia (Russian Federation)", details.residence)
        assertEquals(1, details.managers)
        assertEquals(false, details.claimed)
        assertEquals("aunt", details.relationship)
    }

    @Test
    fun customEventIsListedOnceAndUnfamiliarValuesAreSkipped() {
        val details = ProfileDetails.ofSnapshot(Fixtures.read("profile-details-custom.json"))!!
        assertEquals(listOf(DetailEvent("Graduation of V P", "2005", "Kazan"), DetailEvent("Moved", null, null)), details.events)
        assertEquals(true, details.bigTree)
        assertEquals(false, details.mergePending)
        assertEquals(true, details.curator)
        assertEquals("person@example.com", details.email)
        assertEquals(listOf("+100000", "ignored", "+200000"), details.phones)
        assertEquals(2, details.managers)
    }

    @Test
    fun brokenOrOldSnapshotsGiveNothingUsable() {
        assertNull(ProfileDetails.ofSnapshot(null))
        assertNull(ProfileDetails.ofSnapshot("not json"))
        assertNull(ProfileDetails.ofSnapshot("[1]"))
        assertTrue(ProfileDetails.ofSnapshot("""{"id":"profile-1","first_name":"A"}""")!!.isEmpty)
        // Wrong types leave the rest of the section alone
        val odd = ProfileDetails.ofSnapshot("""{"claimed":"yes","events":"x","managers":3,"phone_numbers":{"a":1},"relationship":"son","current_residence":[1]}""")!!
        assertNull(odd.claimed)
        assertNull(odd.managers)
        assertEquals("son", odd.relationship)
        assertFalse(odd.isEmpty)
    }

    @Test
    fun browserUrlNeedsHttpsOnTheConfiguredHost() {
        val details = ProfileDetails.ofSnapshot(results()[0].toString())!!
        assertEquals("https://sandbox.geni.com/people/V-P/1000001", details.browserUrl("sandbox.geni.com"))
        assertNull(details.browserUrl("www.geni.com"))
        assertNull(details.copy(profileUrl = "http://sandbox.geni.com/x").browserUrl("sandbox.geni.com"))
        assertNull(details.copy(profileUrl = "https://evil.example/x?sandbox.geni.com").browserUrl("sandbox.geni.com"))
        assertNull(details.copy(profileUrl = null).browserUrl("sandbox.geni.com"))
    }
}
