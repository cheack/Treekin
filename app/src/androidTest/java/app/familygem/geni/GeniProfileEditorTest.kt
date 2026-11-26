package app.familygem.geni

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.familygem.Global
import app.familygem.Memory
import app.familygem.R
import app.familygem.profile.ProfileActivity
import org.folg.gedcom.model.EventFact
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Name
import org.folg.gedcom.model.Person
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
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

    @After
    fun tearDown() {
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
        val gedcom = Gedcom()
        gedcom.header = app.familygem.util.TreeUtil.createHeader("test.json")
        val person = Person().apply {
            id = "I1"
            addName(Name().apply { value = "Ivan /Test/" })
            addEventFact(fact("SEX", value = "M"))
            addEventFact(fact("CHR", date = "1903"))
            addEventFact(fact("RESI", place = "Moscow"))
        }
        gedcom.addPerson(person)
        gedcom.createIndexes()
        Global.gc = gedcom
        return person
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
}
