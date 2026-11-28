package app.familygem.geni.map

import app.familygem.geni.Fixtures
import app.familygem.geni.api.GeniProfile
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Header
import org.folg.gedcom.model.Name
import org.folg.gedcom.model.Note
import org.folg.gedcom.model.Person
import org.folg.gedcom.parser.ModelParser
import org.folg.gedcom.visitors.GedcomWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Names in other languages, as the sandbox returned and accepted them (fixtures profile-names-*.json). */
class LocaleNamesTest {

    private fun profile(json: String) = Gson().fromJson(json, GeniProfile::class.java)

    private fun applied(json: String, previous: String? = null, keep: Set<FieldGroup> = emptySet(), into: Person = Person().apply { id = "I1" }): Person {
        GeniMapper.applyProfile(Gedcom(), into, profile(json), previous?.let { profile(it) }, keep)
        return into
    }

    private val locales = Fixtures.read("profile-names-locales.json")
    private val withoutRussian = locales.replace(Regex(""","ru":\{[^}]*\}"""), "")

    @Test
    fun everyLanguageButThePrimaryIsRead() {
        val fields = ProfileFields.of(applied(locales))
        assertEquals(setOf("de", "ru", "zh-CN"), fields.locales.keys)
        assertEquals(
            mapOf("first_name" to "Marie", "last_name" to "Schmidt", "maiden_name" to "Meier", "display_name" to "Frau Marie", "nicknames" to "Mimi, Mia", "title" to "Dr."),
            fields.locales.getValue("de").values
        )
        assertEquals("Мария", fields.locales.getValue("ru")[LocaleName.FIRST])
        assertEquals("玛丽", fields.locales.getValue("zh-CN")[LocaleName.FIRST])
    }

    @Test
    fun theMainAndBirthNamesIgnoreTheLanguages() {
        val person = applied(locales)
        val fields = ProfileFields.of(person)
        assertEquals("Mary Ann Offline", fields.given)
        assertEquals("P", fields.surname)
        assertEquals("III", fields.suffix)
        assertNull(fields.maiden)
        assertEquals(1, person.names.count { !LocaleNames.isLocalized(it) })
        assertEquals("Mary Ann Offline /P/", LocaleNames.main(person)!!.value)
        assertNull(LocaleNames.birth(person))
        // The primary name is first: it is the one every list shows
        assertEquals(person.names[0], LocaleNames.main(person))
    }

    @Test
    fun aNameOfALanguageKeepsItsPartsApart() {
        val name = LocaleNames.find(applied(locales), "zh-CN")!!
        assertEquals("zh-CN", GeniMapper.getTag(name, LocaleNames.LOCALE_TAG))
        assertEquals("玛丽", name.value)
        val de = LocaleNames.find(applied(locales), "de")!!
        assertEquals("Marie /Schmidt/", de.value)
        assertEquals("Mimi, Mia", de.nickname)
    }

    @Test
    fun theLanguageOfAnotherAccountLocaleIsNotThePrimaryName() {
        // Without an en-US entry the entry that has the profile's own first and last name is the primary one
        val ru = """{"id":"profile-1","first_name":"Иван","last_name":"Петров","names":{"ru":{"first_name":"Иван","last_name":"Петров"},"de":{"first_name":"Iwan"}}}"""
        assertEquals(setOf("de"), LocaleNames.of(profile(ru)).keys)
    }

    @Test
    fun topLevelFieldsOfAnotherLanguageDoNotMakeAnEntry() {
        val fields = ProfileFields.of(applied(Fixtures.read("profile-names-fallback.json")))
        assertEquals(setOf("de"), fields.locales.keys)
        assertEquals("Meier", fields.locales.getValue("de")[LocaleName.MAIDEN])
    }

    @Test
    fun aProfileWithoutOtherLanguagesHasNone() {
        assertTrue(ProfileFields.of(applied(Fixtures.read("profile-names-none.json"))).locales.isEmpty())
    }

    @Test
    fun oddValuesDoNotBreakTheProfile() {
        val json = """{"id":"profile-1","first_name":"A","names":{"de":{"first_name":"Ä","nicknames":["x","y"],"title":{"a":1}},"fr":"text"}}"""
        assertEquals(mapOf("first_name" to "Ä", "nicknames" to "x, y"), LocaleNames.of(profile(json)).getValue("de").values)
        assertEquals(setOf("de"), LocaleNames.of(profile(json)).keys)
    }

    @Test
    fun aLanguageRemovedOnGeniIsRemoved() {
        val person = applied(locales)
        applied(withoutRussian, previous = locales, into = person)
        assertEquals(setOf("de", "zh-CN"), ProfileFields.of(person).locales.keys)
    }

    @Test
    fun aLanguageGeniDidNotChangeKeepsTheLocalEdit() {
        val person = applied(locales)
        LocaleNames.set(person, "de", LocaleName.of(mapOf("first_name" to "Marie", "last_name" to "Local")))
        applied(locales.replace("Мария", "Маша"), previous = locales, into = person)
        assertEquals("Local", ProfileFields.of(person).locales.getValue("de")[LocaleName.LAST])
        assertEquals("Маша", ProfileFields.of(person).locales.getValue("ru")[LocaleName.FIRST])
    }

    @Test
    fun aKeptLanguageIsNotTouched() {
        val person = applied(locales)
        LocaleNames.set(person, "ru", LocaleName.of(mapOf("first_name" to "Local")))
        applied(withoutRussian, previous = locales, keep = setOf(LocaleGroup("ru")), into = person)
        assertEquals("Local", ProfileFields.of(person).locales.getValue("ru")[LocaleName.FIRST])
    }

    @Test
    fun aVersionFromBeforeTheLanguagesNeverRemovesAnything() {
        val person = applied(locales)
        LocaleNames.set(person, "fr", LocaleName.of(mapOf("first_name" to "Marie")))
        // The stored version has no `names` key (it was read before they were): Geni's languages are added, none is removed
        applied(withoutRussian, previous = Fixtures.read("profile-names-none.json").replace("\"names\":{},", ""), into = person)
        assertEquals(setOf("de", "ru", "zh-CN", "fr"), ProfileFields.of(person).locales.keys)
    }

    @Test
    fun aProfileFetchedWithoutNamesSaysNothingAboutTheLanguages() {
        val person = applied(locales)
        applied("""{"id":"profile-103","first_name":"Mary Ann"}""", previous = locales, into = person)
        assertEquals(setOf("de", "ru", "zh-CN"), ProfileFields.of(person).locales.keys)
    }

    @Test
    fun aRemovedLanguageWithNotesStaysAsAnOrdinaryName() {
        val person = applied(locales)
        LocaleNames.find(person, "ru")!!.addNote(Note().apply { value = "Spelling from the register" })
        applied(withoutRussian, previous = locales, into = person)
        assertFalse("ru" in ProfileFields.of(person).locales)
        val kept = person.names.single { it.notes.isNotEmpty() }
        assertFalse(LocaleNames.isLocalized(kept))
        assertEquals("Мария /Пэ/", kept.value)
        assertEquals(1, person.names.count { !LocaleNames.isLocalized(it) && it.type != "birth" && it !== LocaleNames.main(person) })
    }

    @Test
    fun aChangedLanguageIsUpdatedInPlaceWithItsNotes() {
        val person = applied(locales)
        val de = LocaleNames.find(person, "de")!!
        de.addNote(Note().apply { value = "n" })
        applied(locales.replace("\"last_name\":\"Schmidt\"", "\"last_name\":\"Schmid\""), previous = locales, into = person)
        assertTrue(LocaleNames.find(person, "de") === de)
        assertEquals(1, de.notes.size)
        assertEquals("Marie /Schmid/", de.value)
    }

    @Test
    fun theLanguagesSurviveAGedcomExportAndReimport() {
        val gedcom = Gedcom().apply {
            header = Header().apply { characterSet = org.folg.gedcom.model.CharacterSet().apply { value = "UTF-8" } } // As the app's export writes it
            addPerson(applied(locales))
        }
        val file = File.createTempFile("locales", ".ged")
        try {
            GedcomWriter().write(gedcom, file)
            assertTrue(file.readText().contains("_GENI_LOCALE zh-CN"))
            val back = ModelParser().parseGedcom(file)
            val fields = ProfileFields.of(back.people.single())
            assertEquals(ProfileFields.of(gedcom.people.single()).locales, fields.locales)
            assertEquals("玛丽", fields.locales.getValue("zh-CN")[LocaleName.FIRST])
            assertEquals("Mary Ann Offline", fields.given)
        } finally {
            file.delete()
        }
    }

    @Test
    fun theCatalogSpellsCodesAsGeniWantsThem() {
        assertTrue(GeniLocales.isSupported("zh-CN"))
        assertTrue(GeniLocales.isSupported("pt-BZ"))
        assertFalse(GeniLocales.isSupported("pt-BR"))
        assertFalse(GeniLocales.isSupported("DE"))
        assertFalse(GeniLocales.isSupported("de-AT"))
        assertEquals("zh-CN", GeniLocales.canonical("ZH-cn"))
        assertNull(GeniLocales.canonical("xx"))
        assertFalse(GeniLocales.DEFAULT in GeniLocales.CHOICES)
        assertEquals("German - Deutsch (de)", GeniLocales.label("de"))
        assertEquals("xx", GeniLocales.label("xx"))
    }

    // ----- Parameters -----

    private val base = ProfileFields.EMPTY.copy(given = "Mary", surname = "P")
    private val de = LocaleName.of(mapOf("first_name" to "Marie", "last_name" to "Schmidt", "maiden_name" to "Meier"))

    @Test
    fun onlyTheChangedFieldOfTheChangedLanguageIsSent() {
        val old = base.copy(locales = mapOf("de" to de, "ru" to LocaleName.of(mapOf("first_name" to "Мария"))))
        val new = old.copy(locales = old.locales + ("de" to de.with("last_name", "Schmid")))
        assertEquals(listOf("names[de][last_name]" to "Schmid"), GeniParams.profile(old, new))
    }

    @Test
    fun aNewLanguageSendsItsFilledFields() {
        val new = base.copy(locales = mapOf("de" to de))
        assertEquals(
            listOf("names[de][first_name]" to "Marie", "names[de][last_name]" to "Schmidt", "names[de][maiden_name]" to "Meier"),
            GeniParams.profile(base, new)
        )
    }

    @Test
    fun aRemovedLanguageClearsEveryFieldItHad() {
        val old = base.copy(locales = mapOf("de" to de))
        assertEquals(
            listOf("names[de][first_name]" to "", "names[de][last_name]" to "", "names[de][maiden_name]" to ""),
            GeniParams.profile(old, base)
        )
    }

    @Test
    fun aClearedFieldIsSentEmpty() {
        val old = base.copy(locales = mapOf("de" to de))
        assertEquals(listOf("names[de][maiden_name]" to ""), GeniParams.profile(old, old.copy(locales = mapOf("de" to de.with("maiden_name", null)))))
    }

    @Test
    fun anUntouchedProfileSendsNoLanguage() {
        val same = base.copy(locales = mapOf("de" to de))
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.profile(same, same.copy()))
        // A version from before the languages has none: the local ones count as new, but a record without any sends nothing
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.profile(base, base.copy(occupation = null)))
        assertTrue(GeniParams.profile(base, base.copy(occupation = "Pilot")).none { it.first.startsWith("names[") })
    }

    @Test
    fun aCodeGeniDoesNotListIsNeverSent() {
        val new = base.copy(locales = mapOf("pt-BR" to de, "en-US" to de, "DE" to de, "de" to de))
        assertEquals(setOf("de"), GeniParams.profile(base, new).map { it.first.substringAfter('[').substringBefore(']') }.toSet())
    }

    @Test
    fun aNewProfileIsCreatedWithoutLanguages() {
        assertTrue(GeniParams.profile(ProfileFields.EMPTY, base.copy(locales = mapOf("de" to de)), isNew = true).none { it.first.startsWith("names[") })
    }

    // ----- Snapshot after a write -----

    private fun overlay(base: String?, vararg params: Pair<String, String>) = SnapshotOverlay.apply(base, params.toList(), "profile-103")

    @Test
    fun theOverlayWritesALanguageField() {
        val json = overlay(locales, "names[de][last_name]" to "Schmid", "names[fr][first_name]" to "Marie")
        val fields = ProfileFields.ofSnapshot(json)!!
        assertEquals("Schmid", fields.locales.getValue("de")[LocaleName.LAST])
        assertEquals("Meier", fields.locales.getValue("de")[LocaleName.MAIDEN])
        assertEquals("Marie", fields.locales.getValue("fr")[LocaleName.FIRST])
        assertEquals(setOf("de", "ru", "zh-CN", "fr"), fields.locales.keys)
    }

    @Test
    fun theOverlayRemovesALanguageWhoseFieldsAreAllEmpty() {
        val json = overlay(locales, *LocaleName.KEYS.map { "names[ru][$it]" to "" }.toTypedArray())
        assertEquals(setOf("de", "zh-CN"), ProfileFields.ofSnapshot(json)!!.locales.keys)
    }

    @Test
    fun theOverlayKeepsAnEmptyNamesObject() {
        val none = Fixtures.read("profile-names-none.json").replace("}", ",\"names\":{}}")
        val json = overlay(none, "names[de][first_name]" to "Marie")
        val removed = overlay(json, "names[de][first_name]" to "")
        assertTrue(JsonParser.parseString(removed).asJsonObject.get("names").asJsonObject.size() == 0)
    }

    @Test
    fun theOverlayNeverInventsNamesForAVersionFromBefore() {
        val none = Fixtures.read("profile-names-none.json")
        assertFalse(JsonParser.parseString(overlay(none, "occupation" to "Pilot")).asJsonObject.has("names"))
    }

    @Test
    fun theOverlayKeepsThePrimaryEntryInStepWithItsFields() {
        // The primary name is also an entry of `names`: a changed last name must not leave the old one looking like a language
        val json = overlay(locales, "last_name" to "Q", "first_name" to "Mary")
        assertEquals(setOf("de", "ru", "zh-CN"), ProfileFields.ofSnapshot(json)!!.locales.keys)
        assertEquals("Q", JsonParser.parseString(json).asJsonObject.getAsJsonObject("names").getAsJsonObject("en-US").get("last_name").asString)
    }

    @Test
    fun theOverlayOfANicknamesWriteIsAText() {
        val json = overlay(locales, "names[de][nicknames]" to "Mimi, Mia, Mausi")
        assertEquals("Mimi, Mia, Mausi", ProfileFields.ofSnapshot(json)!!.locales.getValue("de")[LocaleName.NICKNAMES])
        assertTrue(JsonParser.parseString(json).asJsonObject.getAsJsonObject("names").getAsJsonObject("de").get("nicknames").isJsonPrimitive)
    }

    // ----- Three-way -----

    @Test
    fun differentLanguagesMergeAndTheSameOneConflicts() {
        val snapshot = base.copy(locales = mapOf("de" to de, "ru" to LocaleName.of(mapOf("first_name" to "Мария"))))
        val local = snapshot.copy(locales = snapshot.locales + ("de" to de.with("last_name", "Local")))
        val geni = snapshot.copy(locales = snapshot.locales + ("ru" to LocaleName.of(mapOf("first_name" to "Маша"))))
        val merged = threeWay(snapshot, local, geni)
        assertEquals(setOf<FieldGroup>(LocaleGroup("de")), merged.local)
        assertEquals(setOf<FieldGroup>(LocaleGroup("ru")), merged.geni)
        assertTrue(merged.conflicts.isEmpty())
        val clash = threeWay(snapshot, local, snapshot.copy(locales = snapshot.locales + ("de" to de.with("last_name", "Geni"))))
        assertEquals(setOf<FieldGroup>(LocaleGroup("de")), clash.conflicts)
        assertEquals("LOCALE:de", clash.conflicts.single().name)
        assertEquals("de", LocaleGroup.localeOf("LOCALE:de"))
        assertNull(LocaleGroup.localeOf("NAMES"))
    }

    @Test
    fun aLanguageAddedOnBothSidesTheSameIsNoConflict() {
        val snapshot = base
        val added = snapshot.copy(locales = mapOf("de" to de))
        assertTrue(threeWay(snapshot, added, added.copy()).conflicts.isEmpty())
    }

    @Test
    fun parsedNamesAreFilledGroups() {
        assertEquals(setOf<FieldGroup>(LocaleGroup("de")), base.copy(locales = mapOf("de" to de)).filled().filterIsInstance<LocaleGroup>().toSet())
    }

    @Test
    fun aGedcomNameWithoutPartsIsSplitAtTheFirstSpace() {
        val name = Name().apply { value = "Marie Anne /Schmidt/"; GeniMapper.setTag(this, LocaleNames.LOCALE_TAG, "de") }
        assertEquals(LocaleName.of(mapOf("first_name" to "Marie", "middle_name" to "Anne", "last_name" to "Schmidt")), LocaleNames.parts(name))
    }

    @Test
    fun twoEntriesWithTheProfilesOwnNameStayOutOfTheLanguages() {
        // No en-US: the response order says nothing about which one is the primary name
        val json = """{"id":"profile-1","first_name":"Иван","last_name":"Петров","names":{"de":{"first_name":"Иван","last_name":"Петров"},"ru":{"first_name":"Иван","last_name":"Петров"},"fr":{"first_name":"Jean"}}}"""
        assertEquals(setOf("de", "ru"), LocaleNames.primaryKeys(profile(json)))
        assertEquals(setOf("fr"), LocaleNames.of(profile(json)).keys)
        // The middle name tells them apart
        val middle = """{"id":"profile-1","first_name":"Иван","middle_name":"П","last_name":"Петров","names":{"de":{"first_name":"Иван","last_name":"Петров"},"ru":{"first_name":"Иван","middle_name":"П","last_name":"Петров"}}}"""
        assertEquals(setOf("ru"), LocaleNames.primaryKeys(profile(middle)))
        assertEquals(setOf("de"), LocaleNames.of(profile(middle)).keys)
    }

    @Test
    fun aRemovedLanguageWithAnExtensionStaysAsAnOrdinaryName() {
        val person = applied(locales)
        GeniMapper.setTag(LocaleNames.find(person, "ru")!!, "_CUSTOM", "keep me")
        applied(withoutRussian, previous = locales, into = person)
        assertFalse("ru" in ProfileFields.of(person).locales)
        assertEquals("keep me", GeniMapper.getTag(person.names.single { it.value == "Мария /Пэ/" }, "_CUSTOM"))
    }
}
