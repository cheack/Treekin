package app.familygem.geni.map

import app.familygem.geni.api.GeniEvent
import app.familygem.geni.api.GeniLocation
import app.familygem.geni.api.GeniProfile
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.folg.gedcom.model.EventFact
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.GedcomTag
import org.folg.gedcom.model.Note
import org.folg.gedcom.model.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structured Geni locations: stored on the fact, projected, diffed per key and written over a snapshot. */
class StructuredPlacesTest {

    private fun parts(vararg pairs: Pair<String, String>) = LocationParts.of(mapOf(*pairs))
    private val kazan = parts("city" to "Kazan", "country" to "Russia")

    private fun profile(json: String) = Gson().fromJson(json, GeniProfile::class.java)

    private fun person(json: String) = Person().also { GeniMapper.applyProfile(Gedcom(), it, profile(json)) }

    private fun Person.birth() = eventsFacts.single { it.tag == "BIRT" }

    private val withCity = """{"id":"profile-1","first_name":"Ivan","birth":{"date":{"year":1960},
        "location":{"place_name":"Hospital","city":"Kazan","country":"Russia","street_address1":"Main 1","latitude":55.79,"longitude":0,
        "country_code":"RU","formatted_location":"Hospital, Kazan, Russia"}}}"""

    // Parts and text

    @Test
    fun partsAreNormalized() {
        val p = parts("city" to "  Kazan  ", "county" to " ", "latitude" to "55.7900", "longitude" to "0.0", "bogus" to "x")
        assertEquals(mapOf("city" to "Kazan", "latitude" to "55.79", "longitude" to "0"), p.values)
        assertEquals("Kazan", p.joined)
    }

    @Test
    fun streetAndCoordinatesAreNotInTheJoinedText() {
        assertNull(parts("street_address1" to "Main 1", "latitude" to "1").joined)
        assertEquals("Hospital, Kazan, Russia", parts("country" to "Russia", "city" to "Kazan", "place_name" to "Hospital").joined)
    }

    @Test
    fun locationOfGeniKeepsEveryPartAndTheCoordinateZero() {
        val location = profile(withCity).birth!!.location
        val p = LocationParts.of(location)
        assertEquals("Main 1", p["street_address1"])
        assertEquals("55.79", p["latitude"])
        assertEquals("0", p["longitude"])
        assertEquals(setOf("place_name", "city", "country", "street_address1", "latitude", "longitude"), p.values.keys)
    }

    // The mapper

    @Test
    fun mapperStoresPartsOnTheFactAndJoinsPlac() {
        val fact = person(withCity).birth()
        assertEquals("Hospital, Kazan, Russia", fact.place)
        assertEquals(LocationParts.of(profile(withCity).birth!!.location), GeniMapper.getLocation(fact))
    }

    @Test
    fun eventWithOnlyAStreetLineIsKept() {
        val fact = person("""{"id":"profile-1","birth":{"location":{"street_address1":"Main 1"}}}""").birth()
        assertNull(fact.place)
        assertEquals(parts("street_address1" to "Main 1"), GeniMapper.getLocation(fact))
        assertEquals(parts("street_address1" to "Main 1"), ProfileFields.of(Person().apply { addEventFact(fact) }).birth?.location)
    }

    @Test
    fun eventWithOnlyCoordinatesIsKept() {
        val fact = person("""{"id":"profile-1","birth":{"location":{"latitude":0,"longitude":0}}}""").birth()
        assertEquals(parts("latitude" to "0", "longitude" to "0"), GeniMapper.getLocation(fact))
        assertEquals(parts("latitude" to "0", "longitude" to "0"), EventFields.of(fact)?.location)
    }

    @Test
    fun remoteLocationChangeKeepsAttachmentsAndOtherExtensions() {
        val person = person(withCity)
        val fact = person.birth()
        fact.addNote(Note().apply { value = "Local note" })
        fact.putExtension("other", "kept")
        GeniMapper.setTag(fact, "_CUSTOM", "yes")
        val changed = profile(withCity.replace("\"Kazan\"", "\"Omsk\""))
        GeniMapper.applyProfile(Gedcom(), person, changed, profile(withCity))
        assertEquals("Omsk", GeniMapper.getLocation(fact)!!["city"])
        assertEquals("Hospital, Omsk, Russia", fact.place)
        assertEquals("Local note", fact.notes.single().value)
        assertEquals("kept", fact.getExtension("other"))
        assertEquals("yes", GeniMapper.getTag(fact, "_CUSTOM"))
    }

    @Test
    fun remoteClearKeepsTheFactWithItsAttachments() {
        val person = person(withCity)
        val fact = person.birth()
        fact.addNote(Note().apply { value = "Local note" })
        GeniMapper.applyProfile(Gedcom(), person, profile("""{"id":"profile-1","first_name":"Ivan"}"""), profile(withCity))
        val kept = person.birth()
        assertNull(kept.date)
        assertNull(kept.place)
        assertNull(GeniMapper.getLocation(kept))
        assertEquals("Local note", kept.notes.single().value)
    }

    @Test
    fun theLocationExtensionAloneIsNotLocalData() {
        val fact = person(withCity).birth()
        assertFalse(GeniMapper.hasLocalData(fact))
        fact.putExtension("other", "x")
        assertTrue(GeniMapper.hasLocalData(fact))
    }

    @Test
    fun remoteClearRemovesAFactThatHoldsNothingElse() {
        val person = person(withCity)
        GeniMapper.applyProfile(Gedcom(), person, profile("""{"id":"profile-1","first_name":"Ivan"}"""), profile(withCity))
        assertTrue(person.eventsFacts.none { it.tag == "BIRT" })
    }

    @Test
    fun locationInPlaceOfDeathAndDivorceIsStoredToo() {
        val person = person("""{"id":"profile-1","is_alive":false,"death":{"location":{"city":"Omsk","street_address1":"Main 1"}}}""")
        assertEquals(parts("city" to "Omsk", "street_address1" to "Main 1"), GeniMapper.getLocation(person.eventsFacts.single { it.tag == "DEAT" }))
    }

    // Projection

    @Test
    fun explicitExtensionIsTheLocation() {
        val fact = EventFact().apply { tag = "BIRT"; date = "1960" }
        GeniMapper.setLocation(fact, kazan)
        val fields = EventFields.of(fact)!!
        assertEquals(kazan, fields.location)
        assertEquals("Kazan, Russia", fields.place)
    }

    @Test
    fun legacyFactHasNoLocationUntilResolved() {
        val fact = EventFact().apply { tag = "BIRT"; place = "Kazan, Russia" }
        val fields = EventFields.of(fact)!!
        assertNull(fields.location)
        assertEquals("Kazan, Russia", fields.place)
    }

    @Test
    fun legacyTextMatchingTheSnapshotInheritsItsParts() {
        val snapshot = EventFields("1960", "Hospital, Kazan, Russia", parts("place_name" to "Hospital", "city" to "Kazan", "country" to "Russia", "street_address1" to "Main 1"))
        val legacy = EventFields("1960", "Hospital, Kazan, Russia")
        assertEquals(snapshot, legacy.resolvedAgainst(snapshot).copy(place = snapshot.place))
        assertEquals(snapshot.location, legacy.resolvedAgainst(snapshot).location)
    }

    @Test
    fun legacyTextEditedBecomesThePlaceNameOverTheSnapshotParts() {
        val snapshot = EventFields("1960", "Kazan, Russia", kazan)
        val resolved = EventFields("1960", "Rome").resolvedAgainst(snapshot)
        assertEquals(kazan.with("place_name", "Rome"), resolved.location)
    }

    @Test
    fun legacyTextClearedClearsOnlyThePlaceName() {
        val snapshot = EventFields("1960", "Home, Kazan", parts("place_name" to "Home", "city" to "Kazan"))
        assertEquals(parts("city" to "Kazan"), EventFields("1960", null).resolvedAgainst(snapshot).location)
    }

    @Test
    fun explicitClearIsNotInheritedFromTheSnapshot() {
        val snapshot = EventFields("1960", "Kazan, Russia", kazan)
        val cleared = EventFields("1960", null, LocationParts.EMPTY).resolvedAgainst(snapshot)
        assertTrue(cleared.location!!.isEmpty)
    }

    @Test
    fun placeTextChangedElsewhereIsAnEditOfThePlaceName() {
        val fact = EventFact().apply { tag = "BIRT"; date = "1960" }
        GeniMapper.setLocation(fact, kazan)
        fact.place = "Rome" // An upstream form wrote the place text
        assertEquals(kazan.with("place_name", "Rome"), EventFields.of(fact)!!.location)
    }

    @Test
    fun factWithoutDateAndPlaceButWithStreetIsAnEvent() {
        val fact = EventFact().apply { tag = "BIRT" }
        GeniMapper.setLocation(fact, parts("street_address1" to "Main 1"))
        assertEquals(parts("street_address1" to "Main 1"), EventFields.of(fact)!!.location)
        GeniMapper.setLocation(fact, LocationParts.EMPTY)
        assertNull(EventFields.of(fact))
    }

    @Test
    fun legacyFactResolvesToTheSnapshotWhenSavedUnrelatedly() {
        val snapshotJson = withCity
        val legacy = Person().apply {
            addEventFact(EventFact().apply { tag = "BIRT"; date = "1960"; place = "Hospital, Kazan, Russia" })
            addName(org.folg.gedcom.model.Name().apply { value = "Ivan" })
        }
        val snapshot = ProfileFields.ofSnapshot(snapshotJson)!!
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.profile(snapshot, ProfileFields.of(legacy).copy(givenParts = snapshot.givenParts, displayName = null)).filter { it.first.startsWith("birth") })
    }

    // Params

    private fun birth(location: LocationParts?, place: String? = location?.joined) = ProfileFields.EMPTY.copy(birth = EventFields("1960", place, location))
    private fun diff(old: LocationParts, new: LocationParts) = GeniParams.profile(birth(old), birth(new))

    @Test
    fun onlyTheChangedKeyIsSent() {
        assertEquals(listOf("birth[location][city]" to "Omsk"), diff(kazan, kazan.with("city", "Omsk")))
    }

    @Test
    fun clearingOnePartSendsAnEmptyValueForIt() {
        val old = kazan.with("street_address1", "Main 1").with("street_address2", "Flat 2")
        assertEquals(listOf("birth[location][street_address1]" to ""), diff(old, old.with("street_address1", null)))
    }

    @Test
    fun placeNameEditLeavesTheRest() {
        val old = kazan.with("place_name", "Hospital")
        assertEquals(listOf("birth[location][place_name]" to "Clinic"), diff(old, old.with("place_name", "Clinic")))
        assertEquals(listOf("birth[location][place_name]" to ""), diff(old, old.with("place_name", null)))
    }

    @Test
    fun coordinateZeroIsAValueAndClearIsEmpty() {
        val old = kazan.with("latitude", "10")
        assertEquals(listOf("birth[location][latitude]" to "0"), diff(old, old.with("latitude", "0")))
        assertEquals(listOf("birth[location][latitude]" to ""), diff(kazan.with("latitude", "0"), kazan))
    }

    @Test
    fun coordinatesAloneAreNotSentAndGoWithTheLastTextPart() {
        // Geni drops a location that is left with only coordinates (sandbox, 2026-11-28)
        assertEquals(emptyList<Pair<String, String>>(), diff(LocationParts.EMPTY, parts("latitude" to "1", "longitude" to "0")))
        val old = parts("street_address1" to "Main 1", "latitude" to "1")
        assertEquals(listOf("birth[location][street_address1]" to "", "birth[location][latitude]" to ""), diff(old, parts("latitude" to "1")))
        assertTrue(parts("latitude" to "1").storable.isEmpty)
        assertEquals(old, old.storable)
    }

    @Test
    fun displayFieldsAreNeverSent() {
        val params = GeniParams.profile(ProfileFields.EMPTY, birth(kazan), isNew = true).map { it.first }
        assertTrue(params.none { it.contains("country_code") || it.contains("formatted_location") })
    }

    @Test
    fun unchangedTextAndPartsMakeNoParams() = assertEquals(emptyList<Pair<String, String>>(), diff(kazan, kazan))

    @Test
    fun newProfileSendsEveryNonEmptyPart() {
        val params = GeniParams.profile(ProfileFields.EMPTY, birth(kazan.with("street_address1", "Main 1")), isNew = true)
            .filter { it.first.contains("[location]") }
        assertEquals(listOf("birth[location][city]" to "Kazan", "birth[location][country]" to "Russia", "birth[location][street_address1]" to "Main 1"), params)
    }

    @Test
    fun legacyPlaceEditSendsOnlyThePlaceName() {
        val snapshot = ProfileFields.EMPTY.copy(birth = EventFields("1960", "Kazan, Russia", kazan))
        val edited = ProfileFields.EMPTY.copy(birth = EventFields("1960", "Rome"))
        assertEquals(listOf("birth[location][place_name]" to "Rome"), GeniParams.profile(snapshot, edited))
    }

    @Test
    fun legacyUnchangedPlaceSendsNothing() {
        val snapshot = ProfileFields.EMPTY.copy(birth = EventFields("1960", "Kazan, Russia", kazan))
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.profile(snapshot, ProfileFields.EMPTY.copy(birth = EventFields("1960", "Kazan, Russia"))))
    }

    @Test
    fun unionLocationDiffsPerKey() {
        val old = UnionFields(EventFields("2000", "Kazan", parts("city" to "Kazan")), null)
        val new = UnionFields(EventFields("2000", "Omsk", parts("city" to "Omsk", "street_address1" to "Main 1")), null)
        assertEquals(listOf("marriage[location][city]" to "Omsk", "marriage[location][street_address1]" to "Main 1"), GeniParams.union(old, new))
    }

    // Overlay

    private fun overlay(base: String, params: List<Pair<String, String>>) = JsonParser.parseString(SnapshotOverlay.apply(base, params, "profile-1")).asJsonObject

    @Test
    fun overlayKeepsSiblingPartsAndWritesCoordinatesAsNumbers() {
        val json = overlay(withCity, listOf("birth[location][city]" to "Omsk", "birth[location][latitude]" to "0", "birth[location][longitude]" to "-12.5"))
        val location = json.getAsJsonObject("birth").getAsJsonObject("location")
        assertEquals("Omsk", location["city"].asString)
        assertEquals("Russia", location["country"].asString)
        assertEquals("Main 1", location["street_address1"].asString)
        assertTrue(location["latitude"].asJsonPrimitive.isNumber)
        assertEquals(0, location["latitude"].asInt)
        assertEquals(-12.5, location["longitude"].asDouble, 0.0)
        assertFalse(location.has("formatted_location"))
        assertFalse(location.has("country_code"))
    }

    @Test
    fun overlayClearRemovesOnlyTheSelectedKey() {
        val location = overlay(withCity, listOf("birth[location][street_address1]" to "")).getAsJsonObject("birth").getAsJsonObject("location")
        assertFalse(location.has("street_address1"))
        assertEquals("Kazan", location["city"].asString)
    }

    @Test
    fun overlayWithEveryPartClearedDropsTheLocation() {
        val keys = listOf("place_name", "city", "country", "street_address1", "latitude", "longitude")
        val birth = overlay(withCity, keys.map { "birth[location][$it]" to "" }).getAsJsonObject("birth")
        assertFalse(birth.has("location"))
        assertTrue(birth.has("date"))
    }

    @Test
    fun overlayRoundTripKeepsTheStructuredFields() {
        val fields = ProfileFields.ofSnapshot(withCity)!!
        val edited = fields.copy(birth = fields.birth!!.copy(location = fields.birth!!.location!!.with("city", "Omsk").with("longitude", null)))
        val json = SnapshotOverlay.apply(withCity, GeniParams.profile(fields.withPlacesResolved(), edited), "profile-1")
        val result = ProfileFields.ofSnapshot(json)!!.birth!!.location!!
        assertEquals("Omsk", result["city"])
        assertEquals("Main 1", result["street_address1"])
        assertNull(result["longitude"])
        assertEquals("55.79", result["latitude"])
    }

    @Test
    fun placeFromGeniEventStaysTheJoinedText() {
        val event = GeniEvent(null, GeniLocation("Hospital", "Kazan", null, null, "Russia", streetAddress1 = "Main 1"))
        assertEquals("Hospital, Kazan, Russia", GeniMapper.place(event.location))
    }

    @Test
    fun rawLocationTagIsReadable() {
        val fact = person(withCity).birth()
        val tag: GedcomTag = (fact.getExtension(org.folg.gedcom.parser.ModelParser.MORE_TAGS_EXTENSION_KEY) as List<*>).filterIsInstance<GedcomTag>()
            .single { it.tag == GeniMapper.LOCATION_TAG }
        assertTrue(tag.value.startsWith("{"))
        assertTrue(tag.value.contains("\"street_address1\":\"Main 1\""))
    }
}
