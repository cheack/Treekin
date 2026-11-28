package app.familygem.geni

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isNotEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.familygem.Global
import app.familygem.Memory
import app.familygem.PersonEditorActivity
import app.familygem.R
import app.familygem.constant.Extra
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.GeniParams
import app.familygem.geni.map.LocationParts
import app.familygem.geni.map.ProfileFields
import app.familygem.profile.ProfileActivity
import org.folg.gedcom.model.EventFact
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Name
import org.folg.gedcom.model.Person
import org.folg.gedcom.model.SourceCitation
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The profile editor of a Geni tree offers what Geni's profile sync writes, and leaves local records alone.
 * Works on an in-memory tree in place of the open tree, with auto save off: the tree file on the device is never written.
 * Run without uninstalling the app: `gradle :app:installDebug :app:installDebugAndroidTest`, then
 * `adb shell am instrument -w -e class app.familygem.geni.GeniProfileEditorTest
 *  net.cheack.treekin.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class GeniProfileEditorTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var treeId = 0
    private var wasGeni = false
    private var autoSave = true
    private var expert = false

    @Before
    fun setUp() {
        GeniGlue.install()
        treeId = Global.settings.openTree
        assumeTrue("needs an open tree", Global.settings.currentTree != null)
        wasGeni = GeniTrees.isGeni(treeId)
        autoSave = Global.settings.autoSave
        expert = Global.settings.expert
        Global.settings.autoSave = false
        Global.settings.expert = false
    }

    /** Finishes the activities a test opened from another one (an EventActivity, a PersonEditorActivity). */
    private fun finishActivities() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            androidx.test.runner.lifecycle.Stage.values().forEach { stage -> monitor.getActivitiesInStage(stage).forEach { it.finish() } }
        }
        instrumentation.waitForIdleSync()
    }

    @After
    fun tearDown() {
        finishActivities()
        Global.settings.autoSave = autoSave
        Global.settings.expert = expert
        if (wasGeni) GeniTrees.add(treeId) else GeniTrees.remove(treeId)
        Global.gc = null // The real tree is read again from its file
        Memory.clear()
    }

    private fun fact(tag: String, date: String? = null, place: String? = null, value: String? = null) = EventFact().also {
        it.tag = tag
        it.date = date
        it.place = place
        it.value = value
    }

    /** A person with a legacy christening and residence, alone in a fresh tree that replaces the open one. */
    private fun legacyPerson(): Person {
        val person = Person().apply {
            id = "I1"
            addName(Name().apply { value = "Ivan /Test/" })
            addEventFact(fact("SEX", value = "M"))
            addEventFact(fact("CHR", date = "1903"))
            addEventFact(fact("RESI", place = "Moscow"))
        }
        return useTree(person)
    }

    private fun useTree(person: Person): Person {
        val gedcom = Gedcom()
        gedcom.header = app.familygem.util.TreeUtil.createHeader("test.json")
        gedcom.addPerson(person)
        gedcom.createIndexes()
        Global.gc = gedcom
        Global.shouldSave = false
        Global.edited = false
        return person
    }

    /** A dead person of a Geni tree with every field the Modify form binds; the burial has a source citation. */
    private fun geniPerson(): Person = useTree(Person().apply {
        id = "I1"
        addName(Name().apply { value = "Ivan /Test/"; suffix = "Jr."; nickname = "Vanya" })
        addName(Name().apply { value = "Ivan /Maiden/"; type = "birth" })
        addEventFact(fact("SEX", value = "M"))
        addEventFact(fact("BIRT", date = "BET 1840 AND 1845", place = "Kiev"))
        addEventFact(fact("BAPM", date = "1846", place = "Kiev"))
        addEventFact(fact("DEAT", date = "1900").apply { cause = "Fever" })
        addEventFact(fact("BURI", date = "1901", place = "Kazan").apply { addSourceCitation(SourceCitation().apply { ref = "S1" }) })
        addEventFact(fact("OCCU", value = "Miner"))
        GeniMapper.setTag(this, GeniMapper.ABOUT_TAG, "Line one\nLine two")
    })

    private fun openEditor(person: Person): ActivityScenario<PersonEditorActivity> = ActivityScenario.launch(
        android.content.Intent(context, PersonEditorActivity::class.java).putExtra(Extra.PERSON_ID, person.id)
    )

    private fun field(id: Int) = onView(withId(id))

    /**
     * Taps a button of the form after scrolling to the very end: the scroll view does not clip to its padding,
     * so Espresso's scrollTo() can stop with the buttons still behind the navigation bar.
     */
    private fun tapButton(id: Int) {
        onView(withId(R.id.base_scrollView)).perform(object : androidx.test.espresso.ViewAction {
            override fun getConstraints(): Matcher<View> = androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.ScrollView::class.java)
            override fun getDescription() = "scroll to the end"
            override fun perform(uiController: androidx.test.espresso.UiController, view: View) {
                (view as android.widget.ScrollView).fullScroll(View.FOCUS_DOWN)
                uiController.loopMainThreadUntilIdle()
            }
        })
        field(id).perform(click())
    }

    /** Saves the form and returns the Geni parameters that the change makes. */
    private fun saveAndParams(person: Person, before: ProfileFields): List<Pair<String, String>> {
        tapButton(R.id.editor_save)
        return GeniParams.profile(before, ProfileFields.of(person))
    }

    private fun openProfile(person: Person): ActivityScenario<ProfileActivity> {
        Memory.setLeader(person)
        return ActivityScenario.launch(ProfileActivity::class.java)
    }

    private fun popupItem(text: String) = onView(withText(text)).inRoot(isPlatformPopup())

    private fun openEventMenu() {
        onView(withId(R.id.fab)).perform(click())
        popupItem(context.getString(R.string.event)).perform(click())
    }

    /** A popup item that is there, also while the popup is still animating in (its on-screen rectangle may be empty). */
    private val shown = androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility(androidx.test.espresso.matcher.ViewMatchers.Visibility.VISIBLE)

    private fun displayed(matcher: Matcher<View>) = onView(allOf(matcher, isDisplayed()))

    @Test
    fun factsMenuUsesGeniTagsAndKeepsLegacyRecords() {
        GeniTrees.add(treeId)
        val person = legacyPerson()
        openProfile(person).use {
            openEventMenu()
            listOf(R.string.birth, app.familygem.geni.R.string.geni_baptism, R.string.occupation, R.string.death, R.string.burial)
                .forEach { popupItem(context.getString(it)).check(matches(shown)) }
            listOf(R.string.christening, R.string.residence, R.string.other)
                .forEach { popupItem(context.getString(it)).check(doesNotExist()) }

            popupItem(context.getString(app.familygem.geni.R.string.geni_baptism)).perform(click())
            // A tap during the opening animation of the event screen can be lost: tap again until the date editor shows
            for (attempt in 1..3) {
                displayed(allOf(withId(R.id.event_title), withText(R.string.date))).perform(click())
                if (runCatching { displayed(withId(R.id.event_edit)).check(matches(isDisplayed())) }.isSuccess) break
            }
            displayed(withId(R.id.event_edit)).perform(replaceText("1904"))
            displayed(withId(R.id.event_save)).perform(click())
        }
        val facts = person.eventsFacts
        assertEquals("1904", facts.single { it.tag == "BAPM" }.date)
        assertEquals("1903", facts.single { it.tag == "CHR" }.date)
        assertEquals("Moscow", facts.single { it.tag == "RESI" }.place)
    }

    @Test
    fun ordinaryTreeKeepsUpstreamEventMenu() {
        GeniTrees.remove(treeId)
        openProfile(legacyPerson()).use {
            openEventMenu()
            listOf(R.string.birth, R.string.christening, R.string.residence, R.string.other)
                .forEach { popupItem(context.getString(it)).check(matches(shown)) }
        }
    }

    @Test
    fun modifyOffersMappedFieldsOnlyOnGeniTrees() {
        GeniTrees.add(treeId)
        val person = geniPerson()
        openEditor(person).use {
            mapOf(
                R.id.editor_birthSurname to "Maiden", R.id.editor_suffix to "Jr.", R.id.editor_nicknames to "Vanya",
                R.id.editor_baptismDate to "1846", R.id.editor_baptismPlace to "Kiev", R.id.editor_causeOfDeath to "Fever",
                R.id.editor_burialDate to "1901", R.id.editor_burialPlace to "Kazan", R.id.editor_occupation to "Miner",
                R.id.editor_about to "Line one\nLine two"
            ).forEach { (id, text) -> field(id).check(matches(withText(text))) }
            field(R.id.editor_sex3).check(matches(isNotEnabled()))
        }
        GeniTrees.remove(treeId)
        openEditor(person).use {
            listOf(R.id.editor_geniNames, R.id.editor_geniBaptism, R.id.editor_geniCause, R.id.editor_geni)
                .forEach { field(it).check(matches(androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility(
                    androidx.test.espresso.matcher.ViewMatchers.Visibility.GONE))) }
        }
    }

    @Test
    fun editKeepsBurialCitationAndUnchangedDateRange() {
        GeniTrees.add(treeId)
        val person = geniPerson()
        val burial = person.eventsFacts.single { it.tag == "BURI" }
        val before = ProfileFields.of(person)
        val params = openEditor(person).use {
            field(R.id.editor_burialDate).perform(androidx.test.espresso.action.ViewActions.scrollTo(), replaceText("1902"))
            saveAndParams(person, before)
        }
        assertSame(burial, person.eventsFacts.single { it.tag == "BURI" })
        assertEquals("1902", burial.date)
        assertEquals("S1", burial.sourceCitations.single().ref)
        assertEquals("BET 1840 AND 1845", person.eventsFacts.single { it.tag == "BIRT" }.date)
        assertEquals("1902", params.toMap()["burial[date][year]"])
        assertTrue(params.none { it.first.startsWith("birth") })
    }

    @Test
    fun clearingMappedFieldsQueuesClears() {
        GeniTrees.add(treeId)
        val person = geniPerson()
        val before = ProfileFields.of(person)
        val params = openEditor(person).use {
            listOf(R.id.editor_occupation, R.id.editor_causeOfDeath, R.id.editor_about, R.id.editor_burialDate, R.id.editor_burialPlace)
                .forEach { field(it).perform(androidx.test.espresso.action.ViewActions.scrollTo(), replaceText("")) }
            saveAndParams(person, before)
        }.toMap()
        listOf("occupation", "cause_of_death", "about_me", "burial[date][year]", "burial[location][place_name]")
            .forEach { assertEquals(it, "", params[it]) }
        assertTrue(params.keys.none { it.startsWith("birth") || it.startsWith("baptism") || it.startsWith("death[") || it.endsWith("_name") || it == "gender" || it == "nicknames" || it == "suffix" })
        assertEquals("S1", person.eventsFacts.single { it.tag == "BURI" }.sourceCitations.single().ref) // Emptied, not removed
        assertNull(GeniMapper.getTag(person, GeniMapper.ABOUT_TAG))
    }

    @Test
    fun supportedRangeSavesFromModify() {
        GeniTrees.add(treeId)
        val person = geniPerson()
        val before = ProfileFields.of(person)
        val params = openEditor(person).use {
            field(R.id.editor_baptismDate).perform(androidx.test.espresso.action.ViewActions.scrollTo(), replaceText("BET ABT 1846 AND 1847"))
            field(R.id.editor_burialDate).perform(androidx.test.espresso.action.ViewActions.scrollTo(), replaceText("BEF 1902"))
            saveAndParams(person, before)
        }.toMap()
        assertEquals("BET ABT 1846 AND 1847", person.eventsFacts.single { it.tag == "BAPM" }.date)
        assertEquals("between", params["baptism[date][range]"])
        assertEquals("true", params["baptism[date][circa]"])
        assertEquals("1847", params["baptism[date][end_year]"])
        assertEquals("before", params["burial[date][range]"])
        assertEquals("S1", person.eventsFacts.single { it.tag == "BURI" }.sourceCitations.single().ref)
        // A second save of the same form makes no new write
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.profile(ProfileFields.of(person), ProfileFields.of(person)))
    }

    @Test
    fun eventScreenRefusesOnlyNewDatesGeniCannotStore() {
        GeniTrees.add(treeId)
        val person = geniPerson()
        val birth = person.eventsFacts.single { it.tag == "BIRT" }
        val family = org.folg.gedcom.model.Family().apply { id = "F1" }
        val marriage = fact("MARR", date = "FROM 1870")
        family.addEventFact(marriage)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            Memory.setLeader(person)
            val reject = { f: EventFact, text: String -> GeniFacts.rejectDate(context, f, text) }
            assertFalse(reject(birth, "BET 1850 AND 1855"))
            assertFalse(reject(birth, ""))
            assertFalse(reject(birth, "BET 1840 AND 1845")) // Untouched
            assertTrue(reject(birth, "FROM 1850"))
            Memory.setLeader(family)
            assertFalse(reject(marriage, "FROM 1870")) // Untouched legacy text
            assertFalse(reject(marriage, "BEF 1871"))
            assertTrue(reject(marriage, "spring 1871"))
        }
    }

    @Test
    fun unsupportedNewDateKeepsFormOpen() {
        GeniTrees.add(treeId)
        val person = geniPerson()
        openEditor(person).use { scenario ->
            field(R.id.editor_baptismDate).perform(androidx.test.espresso.action.ViewActions.scrollTo(), replaceText("FROM 1846"))
            tapButton(R.id.editor_save)
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
            scenario.onActivity {
                val label = it.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.editor_baptismDateLabel)
                assertEquals(it.getString(app.familygem.geni.R.string.geni_date_not_supported), label.error)
                assertTrue(it.findViewById<View>(R.id.editor_save).isEnabled)
            }
        }
        assertEquals("1846", person.eventsFacts.single { it.tag == "BAPM" }.date)
        assertFalse(Global.shouldSave)
    }

    @Test
    fun deathFromFactsOnLivingProfileOpensModifyAndCancelWritesNothing() {
        GeniTrees.add(treeId)
        val person = legacyPerson()
        openProfile(person).use {
            openEventMenu()
            popupItem(context.getString(R.string.death)).perform(click())
            field(R.id.editor_dead).check(matches(isChecked()))
            tapButton(R.id.editor_cancel)
        }
        assertTrue(person.eventsFacts.none { it.tag == "DEAT" })
        assertFalse(Global.shouldSave)
        assertFalse(Global.edited)
    }

    private fun namedPerson(value: String, parts: Pair<String, String>? = null, displayName: String? = null) = useTree(Person().apply {
        id = "I1"
        addName(Name().apply { this.value = value })
        addEventFact(fact("SEX", value = "F"))
        parts?.let { GeniMapper.setGivenNameParts(this, app.familygem.geni.map.GivenNameParts(it.first, it.second)) }
        displayName?.let { GeniMapper.setTag(this, GeniMapper.DISPLAY_NAME_TAG, it) }
    })

    @Test
    fun editsMiddleNameAndClearsDisplayName() {
        GeniTrees.add(treeId)
        val person = namedPerson("Mary Ann Louise /Test/", "Mary Ann" to "Louise", "M. A. Example")
        val before = ProfileFields.of(person)
        val params = openEditor(person).use {
            field(R.id.editor_name).check(matches(withText("Mary Ann")))
            field(R.id.editor_middleName).check(matches(withText("Louise")))
            field(R.id.editor_displayName).check(matches(withText("M. A. Example")))
            field(R.id.editor_middleName).perform(replaceText("Rose"))
            field(R.id.editor_displayName).perform(androidx.test.espresso.action.ViewActions.scrollTo(), replaceText(""))
            saveAndParams(person, before)
        }
        assertEquals("Mary Ann Rose /Test/", person.names.single().value)
        assertEquals(app.familygem.geni.map.GivenNameParts("Mary Ann", "Rose"), GeniMapper.getGivenNameParts(person))
        assertEquals("", GeniMapper.getTag(person, GeniMapper.DISPLAY_NAME_TAG))
        assertEquals(listOf("middle_name" to "Rose", "display_name" to ""), params)
    }

    @Test
    fun legacyNameIsSplitForEditingAndSavedUnchanged() {
        GeniTrees.add(treeId)
        val person = namedPerson("Mary Ann Louise /Test/")
        val before = ProfileFields.of(person)
        val params = openEditor(person).use {
            field(R.id.editor_name).check(matches(withText("Mary")))
            field(R.id.editor_middleName).check(matches(withText("Ann Louise")))
            saveAndParams(person, before)
        }
        assertEquals("Mary Ann Louise /Test/", person.names.single().value)
        assertNull(GeniMapper.getGivenNameParts(person)) // Not edited: no parts stored
        assertEquals(emptyList<Pair<String, String>>(), params)
    }

    @Test
    fun primaryNameRowOpensModifyAndInternalTagsAreHidden() {
        GeniTrees.add(treeId)
        val person = namedPerson("Mary Ann Louise /Test/", "Mary Ann" to "Louise", "")
        openProfile(person).use {
            onView(withText(GeniMapper.FIRST_NAME_TAG)).check(doesNotExist())
            onView(withText(GeniMapper.DISPLAY_NAME_TAG)).check(doesNotExist())
            onView(withText("Mary Ann Louise Test")).perform(click())
            field(R.id.editor_middleName).check(matches(withText("Louise")))
        }
    }

    @Test
    fun nameTitleIsEditedApartFromGedcomTitle() {
        GeniTrees.add(treeId)
        val person = namedPerson("Ivan /Test/", "Ivan" to "", "").apply {
            addEventFact(fact("TITL", value = "Professor"))
            GeniMapper.setTag(this, GeniMapper.NAME_TITLE_TAG, "Dr.")
        }
        val before = ProfileFields.of(person)
        val params = openEditor(person).use {
            field(R.id.editor_nameTitle).check(matches(withText("Dr.")))
            field(R.id.editor_nameTitle).perform(androidx.test.espresso.action.ViewActions.scrollTo(), replaceText("Prof."))
            saveAndParams(person, before)
        }
        assertEquals("Prof.", GeniMapper.getTag(person, GeniMapper.NAME_TITLE_TAG))
        assertEquals("Professor", person.eventsFacts.single { it.tag == "TITL" }.value)
        assertEquals(listOf("title" to "Prof."), params)
    }

    @Test
    fun causeOfDeathFollowsUpstreamCleanUp() {
        GeniTrees.add(treeId)
        val person = namedPerson("Ivan /Test/", "Ivan" to "", "")
        openEditor(person).use { scenario ->
            scenario.onActivity { it.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.editor_dead).isChecked = true }
            field(R.id.editor_causeOfDeath).perform(androidx.test.espresso.action.ViewActions.scrollTo(), replaceText("Fever"))
            tapButton(R.id.editor_save)
        }
        val death = person.eventsFacts.single { it.tag == "DEAT" }
        assertEquals("Fever", death.cause)
        assertNull(death.value) // "Y" only marks a death fact with nothing else
    }

    @Test
    fun birthNameListedFirstIsLeftAlone() {
        GeniTrees.add(treeId)
        val person = useTree(Person().apply {
            id = "I1"
            addName(Name().apply { value = "Mary /Orlova/"; type = "birth" })
            addName(Name().apply { value = "Mary Ann /Test/" })
            addEventFact(fact("SEX", value = "F"))
            GeniMapper.setGivenNameParts(this, app.familygem.geni.map.GivenNameParts("Mary", "Ann"))
        })
        openEditor(person).use {
            field(R.id.editor_name).check(matches(withText("Mary")))
            field(R.id.editor_surname).check(matches(withText("Test")))
            field(R.id.editor_middleName).perform(replaceText("Jane"))
            tapButton(R.id.editor_save)
        }
        assertEquals("Mary /Orlova/", person.names[0].value)
        assertEquals("Mary Jane /Test/", person.names[1].value)
    }

    @Test
    fun clearingKeepsFactsAndNamesWithOtherDetails() {
        GeniTrees.add(treeId)
        val person = geniPerson().apply {
            eventsFacts.single { it.tag == "BAPM" }.type = "Catholic"
            names.single { it.type == "birth" }.nickname = "Manya"
        }
        openEditor(person).use {
            listOf(R.id.editor_baptismDate, R.id.editor_baptismPlace, R.id.editor_birthSurname)
                .forEach { field(it).perform(androidx.test.espresso.action.ViewActions.scrollTo(), replaceText("")) }
            tapButton(R.id.editor_save)
        }
        assertEquals("Catholic", person.eventsFacts.single { it.tag == "BAPM" }.type)
        assertEquals("Manya", person.names.single { it.type == "birth" }.nickname)
    }

    // Structured places

    private val kazan = LocationParts.of(mapOf("place_name" to "Hospital", "city" to "Kazan", "country" to "Russia", "street_address1" to "Main 1"))

    /** A person whose birth and marriage-less profile keep a structured location, as the mapper stores a Geni one. */
    private fun placedPerson(): Person = useTree(Person().apply {
        id = "I1"
        addName(Name().apply { value = "Ivan /Test/" })
        addEventFact(fact("SEX", value = "M"))
        addEventFact(fact("BIRT", date = "1960").also { GeniMapper.setLocation(it, kazan) })
        addEventFact(fact("BURI", date = "BET 1999 AND ABT 2001").apply {
            addSourceCitation(SourceCitation().apply { ref = "S1" })
            GeniMapper.setLocation(this, kazan)
        })
    })

    private fun Person.birth() = eventsFacts.single { it.tag == "BIRT" }

    @Test
    fun placeInputShowsAndEditsThePlaceNameOnly() {
        GeniTrees.add(treeId)
        val person = placedPerson()
        val before = ProfileFields.of(person)
        val params = openEditor(person).use {
            field(R.id.editor_birthPlace).check(matches(withText("Hospital")))
            field(R.id.editor_burialPlace).check(matches(withText("Hospital")))
            field(R.id.editor_birthPlace).perform(replaceText("Clinic"))
            saveAndParams(person, before)
        }
        assertEquals(listOf("birth[location][place_name]" to "Clinic"), params)
        assertEquals(kazan.with("place_name", "Clinic"), GeniMapper.getLocation(person.birth()))
        assertEquals("Clinic, Kazan, Russia", person.birth().place)
        // A second unchanged save makes no job
        assertEquals(emptyList<Pair<String, String>>(), GeniParams.profile(ProfileFields.of(person), ProfileFields.of(person)))
    }

    @Test
    fun clearingThePlaceInputKeepsCityAndCountry() {
        GeniTrees.add(treeId)
        val person = placedPerson()
        val before = ProfileFields.of(person)
        val params = openEditor(person).use {
            field(R.id.editor_birthPlace).perform(replaceText(""))
            field(R.id.editor_burialPlace).perform(androidx.test.espresso.action.ViewActions.scrollTo(), replaceText(""))
            saveAndParams(person, before)
        }
        assertEquals(listOf("birth[location][place_name]" to "", "burial[location][place_name]" to ""), params)
        assertEquals(kazan.with("place_name", null), GeniMapper.getLocation(person.birth()))
        assertEquals("Kazan, Russia", person.birth().place)
    }

    @Test
    fun burialPlaceEditKeepsCitationAndApproximateRange() {
        GeniTrees.add(treeId)
        val person = placedPerson()
        val before = ProfileFields.of(person)
        val params = openEditor(person).use {
            field(R.id.editor_burialPlace).perform(androidx.test.espresso.action.ViewActions.scrollTo(), replaceText("Cemetery"))
            saveAndParams(person, before)
        }
        val burial = person.eventsFacts.single { it.tag == "BURI" }
        assertEquals(listOf("burial[location][place_name]" to "Cemetery"), params)
        assertEquals("S1", burial.sourceCitations.single().ref)
        assertEquals("BET 1999 AND ABT 2001", burial.date)
        assertEquals("Kazan", GeniMapper.getLocation(burial)!!["city"])
    }

    @Test
    fun unrelatedSaveOfALegacyTreeSendsNoLocationAndStoresNoParts() {
        GeniTrees.add(treeId)
        val person = useTree(Person().apply {
            id = "I1"
            addName(Name().apply { value = "Ivan /Test/" })
            addEventFact(fact("SEX", value = "M"))
            addEventFact(fact("BIRT", date = "1960", place = "Hospital, Kazan, Russia"))
        })
        val before = ProfileFields.of(person)
        val params = openEditor(person).use {
            field(R.id.editor_birthPlace).check(matches(withText("Hospital, Kazan, Russia"))) // Not linked: the text is all there is
            field(R.id.editor_nicknames).perform(androidx.test.espresso.action.ViewActions.scrollTo(), replaceText("Vanya"))
            saveAndParams(person, before)
        }
        assertEquals(listOf("nicknames" to "Vanya"), params)
        assertEquals("Hospital, Kazan, Russia", person.birth().place)
        assertNull(GeniMapper.getLocation(person.birth()))
    }

    private fun openEvent(leader: Any, fact: EventFact): ActivityScenario<app.familygem.detail.EventActivity> {
        Memory.setLeader(leader)
        Memory.add(fact)
        return ActivityScenario.launch(app.familygem.detail.EventActivity::class.java)
    }

    /** A tap on a row can be lost during the opening animation: tap again until the editor shows. */
    private fun editRow(title: String, text: String) {
        for (attempt in 1..3) {
            displayed(allOf(withId(R.id.event_title), withText(title))).perform(click())
            if (runCatching { displayed(withId(R.id.event_edit)).check(matches(isDisplayed())) }.isSuccess) break
        }
        displayed(withId(R.id.event_edit)).perform(replaceText(text), androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
        displayed(withId(R.id.event_edit)).check(matches(withText(text)))
        displayed(withId(R.id.event_save)).perform(click())
    }

    private fun string(id: Int) = context.getString(id)

    private val locationLabels = listOf(
        app.familygem.geni.R.string.geni_place_name, app.familygem.geni.R.string.geni_city, app.familygem.geni.R.string.geni_county,
        app.familygem.geni.R.string.geni_state, app.familygem.geni.R.string.geni_country, app.familygem.geni.R.string.geni_street_address1,
        app.familygem.geni.R.string.geni_street_address2, app.familygem.geni.R.string.geni_street_address3,
        app.familygem.geni.R.string.geni_latitude, app.familygem.geni.R.string.geni_longitude
    )

    @Test
    fun eventScreenEditsOneLocationPartAndClearsAnother() {
        GeniTrees.add(treeId)
        val person = placedPerson()
        val birth = person.birth()
        openEvent(person, birth).use {
            locationLabels.forEach { label -> displayed(allOf(withId(R.id.event_title), withText(string(label)))) }
            editRow(string(app.familygem.geni.R.string.geni_city), "Omsk")
            editRow(string(app.familygem.geni.R.string.geni_latitude), "0")
        }
        assertEquals(kazan.with("city", "Omsk").with("latitude", "0"), GeniMapper.getLocation(birth))
        assertEquals("Hospital, Omsk, Russia", birth.place)
        assertEquals("1960", birth.date)
        // The raw extension is not listed as a generic row
        val params = GeniParams.profile(ProfileFields.of(person).let { it.copy(birth = it.birth!!.copy(location = kazan, place = kazan.joined)) }, ProfileFields.of(person))
        assertEquals(listOf("birth[location][city]" to "Omsk", "birth[location][latitude]" to "0"), params)
    }

    @Test
    fun eventScreenContextMenuDeleteClearsOnlyThatPart() {
        GeniTrees.add(treeId)
        val person = placedPerson()
        val birth = person.birth()
        openEvent(person, birth).use {
            displayed(allOf(withId(R.id.event_title), withText(string(app.familygem.geni.R.string.geni_street_address1)))).perform(androidx.test.espresso.action.ViewActions.longClick())
            onView(withText(R.string.delete)).perform(click())
            onView(withText(R.string.yes)).perform(click())
        }
        assertEquals(kazan.with("street_address1", null), GeniMapper.getLocation(birth))
        assertEquals("Hospital, Kazan, Russia", birth.place)
    }

    @Test
    fun eventScreenRefusesAnInvalidCoordinate() {
        GeniTrees.add(treeId)
        val person = placedPerson()
        val birth = person.birth()
        openEvent(person, birth).use {
            editRow(string(app.familygem.geni.R.string.geni_latitude), "95")
            displayed(withId(R.id.event_edit)).check(matches(isDisplayed())) // Still in the editor
        }
        assertEquals(kazan, GeniMapper.getLocation(birth))
    }

    @Test
    fun marriageOfAFamilyHasTheLocationRows() {
        GeniTrees.add(treeId)
        val person = placedPerson()
        val family = org.folg.gedcom.model.Family().apply { id = "F1" }
        val marriage = fact("MARR", date = "2000")
        family.addEventFact(marriage)
        openEvent(family, marriage).use {
            editRow(string(app.familygem.geni.R.string.geni_city), "Tula")
            displayed(allOf(withId(R.id.event_title), withText(string(app.familygem.geni.R.string.geni_street_address2))))
        }
        assertEquals(LocationParts.of(mapOf("city" to "Tula")), GeniMapper.getLocation(marriage))
        assertEquals("Tula", marriage.place)
        assertEquals("2000", marriage.date)
        assertSame(person, Global.gc.getPerson("I1"))
    }

    @Test
    fun localTreeAndOccupationKeepTheirEventScreens() {
        val person = placedPerson()
        val birth = person.birth()
        GeniTrees.remove(treeId)
        openEvent(person, birth).use {
            displayed(allOf(withId(R.id.event_title), withText(string(R.string.place))))
            onView(withText(string(app.familygem.geni.R.string.geni_city))).check(doesNotExist())
        }
        finishActivities()
        GeniTrees.add(treeId)
        val occupation = fact("OCCU", value = "Miner", place = "Pit 5")
        person.addEventFact(occupation)
        openEvent(person, occupation).use {
            displayed(allOf(withId(R.id.event_title), withText(string(R.string.place))))
            displayed(allOf(withId(R.id.event_title), withText(string(R.string.value))))
            onView(withText(string(app.familygem.geni.R.string.geni_city))).check(doesNotExist())
        }
        assertEquals("Pit 5", occupation.place)
    }
}
