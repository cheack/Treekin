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
 * Run: `adb shell am instrument -w -e class app.familygem.geni.GeniProfileEditorTest
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

    private fun displayed(matcher: Matcher<View>) = onView(allOf(matcher, isDisplayed()))

    @Test
    fun factsMenuUsesGeniTagsAndKeepsLegacyRecords() {
        GeniTrees.add(treeId)
        val person = legacyPerson()
        openProfile(person).use {
            openEventMenu()
            listOf(R.string.birth, app.familygem.geni.R.string.geni_baptism, R.string.occupation, R.string.death, R.string.burial)
                .forEach { popupItem(context.getString(it)).check(matches(isDisplayed())) }
            listOf(R.string.christening, R.string.residence, R.string.other)
                .forEach { popupItem(context.getString(it)).check(doesNotExist()) }

            popupItem(context.getString(app.familygem.geni.R.string.geni_baptism)).perform(click())
            displayed(allOf(withId(R.id.event_title), withText(R.string.date))).perform(click())
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
                .forEach { popupItem(context.getString(it)).check(matches(isDisplayed())) }
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
    fun unsupportedNewDateKeepsFormOpen() {
        GeniTrees.add(treeId)
        val person = geniPerson()
        openEditor(person).use { scenario ->
            field(R.id.editor_baptismDate).perform(androidx.test.espresso.action.ViewActions.scrollTo(), replaceText("BEF 1846"))
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
}
