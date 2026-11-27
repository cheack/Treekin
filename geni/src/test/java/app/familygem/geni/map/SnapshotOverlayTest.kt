package app.familygem.geni.map

import org.junit.Assert.assertEquals
import org.junit.Test

class SnapshotOverlayTest {

    private val olga = ProfileFields.EMPTY.copy(
        given = "Olga Ivanovna", surname = "Petrova", maiden = "Sidorova", nicknames = "Olya, Lyolya", sex = "F",
        birth = EventFields("12 MAR 1960", "Kyiv"), death = EventFields("ABT 2020", null), dead = true, causeOfDeath = "Illness",
        occupation = "Engineer", about = "Line one\nLine two"
    )

    @Test
    fun paramsOfANewProfileGiveItsFields() {
        val json = SnapshotOverlay.apply(null, GeniParams.profile(ProfileFields.EMPTY, olga, isNew = true), "profile-5")
        assertEquals(olga.asGeni(), ProfileFields.ofSnapshot(json))
        assertEquals("profile-5", com.google.gson.JsonParser.parseString(json).asJsonObject.get("id").asString)
    }

    @Test
    fun paramsOfAnUpdateGiveTheNewFields() {
        val before = SnapshotOverlay.apply(null, GeniParams.profile(ProfileFields.EMPTY, olga, isNew = true), "profile-5")
        val after = olga.copy(given = "Olga", birth = EventFields("1960", null), death = null, dead = false, causeOfDeath = null, occupation = null)
        assertEquals(after.asGeni(), ProfileFields.ofSnapshot(SnapshotOverlay.apply(before, GeniParams.profile(olga, after), "profile-5")))
    }

    /** As Geni's version projects: name parts split at the first space, no display name, no title. */
    private fun ProfileFields.asGeni() = copy(givenParts = GivenNameParts.split(given), displayName = "", title = "")

    @Test
    fun unionParams() {
        val married = UnionFields(EventFields("2000", "Kazan"), EventFields("2010", null), divorced = true)
        val json = SnapshotOverlay.apply("""{"id":"union-1","status":"spouse"}""", GeniParams.union(UnionFields.EMPTY, married), "union-1")
        assertEquals(married, UnionFields.ofSnapshot(json))
    }

    private fun overlay(base: ProfileFields, after: ProfileFields): ProfileFields? {
        val before = SnapshotOverlay.apply(null, GeniParams.profile(ProfileFields.EMPTY, base, isNew = true), "profile-5")
        return ProfileFields.ofSnapshot(SnapshotOverlay.apply(before, GeniParams.profile(base, after), "profile-5"))
    }

    @Test
    fun rangeWithApproximateEndsRoundTrips() {
        val ranged = olga.copy(birth = EventFields("BET ABT 1840 AND 1845", "Kyiv"), death = EventFields("BEF 3 MAY 1900", null))
        assertEquals(ranged.asGeni(), ProfileFields.ofSnapshot(SnapshotOverlay.apply(null, GeniParams.profile(ProfileFields.EMPTY, ranged, isNew = true), "profile-5")))
    }

    @Test
    fun singleDateReplacingARangeLeavesNoStaleParts() {
        val ranged = olga.copy(birth = EventFields("BET ABT 1840 AND ABT 1845", null))
        val json = SnapshotOverlay.apply(null, GeniParams.profile(ProfileFields.EMPTY, ranged, isNew = true), "profile-5")
        val replaced = SnapshotOverlay.apply(json, GeniParams.profile(ranged, ranged.copy(birth = EventFields("1841", null))), "profile-5")
        assertEquals(EventFields("1841", null), ProfileFields.ofSnapshot(replaced)!!.birth)
        val date = com.google.gson.JsonParser.parseString(replaced).asJsonObject.getAsJsonObject("birth").getAsJsonObject("date")
        assertEquals(setOf("year", "circa"), date.keySet().toSet().minus(setOf("end_circa")))
        assertEquals(false, date.has("range"))
        assertEquals(false, date.has("end_year"))
    }

    @Test
    fun clearingARangeLeavesNothing() {
        val ranged = olga.copy(birth = EventFields("AFT 1900", null))
        assertEquals(null, overlay(ranged, ranged.copy(birth = null))!!.birth)
    }

    @Test
    fun unionRange() {
        val married = UnionFields(EventFields("BET 1900 AND ABT 1902", "Kazan"), null)
        val json = SnapshotOverlay.apply("""{"id":"union-1","status":"spouse"}""", GeniParams.union(UnionFields.EMPTY, married), "union-1")
        assertEquals(married, UnionFields.ofSnapshot(json))
    }
}
