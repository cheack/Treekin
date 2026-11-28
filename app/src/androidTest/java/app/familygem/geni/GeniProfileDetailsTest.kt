package app.familygem.geni

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.familygem.Global
import app.familygem.Memory
import app.familygem.PersonEditorActivity
import app.familygem.R
import app.familygem.constant.Extra
import app.familygem.constant.Relation
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.sync.GeniLink
import app.familygem.profile.ProfileActivity
import kotlinx.coroutines.runBlocking
import org.folg.gedcom.model.EventFact
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Name
import org.folg.gedcom.model.Person
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The read-only "On Geni" section of Facts, and the optional email of a new relative. Works on an in-memory tree in place of the open
 * one, with auto save off; the link it stores for the Geni profile is removed again.
 */
@RunWith(AndroidJUnit4::class)
class GeniProfileDetailsTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var treeId = 0
    private var wasGeni = false
    private var autoSave = true

    @Before
    fun setUp() {
        GeniGlue.install()
        treeId = Global.settings.openTree
        assumeTrue("needs an open tree", Global.settings.currentTree != null)
        wasGeni = GeniTrees.isGeni(treeId)
        autoSave = Global.settings.autoSave
        Global.settings.autoSave = false
    }

    @After
    fun tearDown() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            androidx.test.runner.lifecycle.Stage.values().forEach { stage -> monitor.getActivitiesInStage(stage).forEach { it.finish() } }
        }
        instrumentation.waitForIdleSync()
        runBlocking {
            val store = Geni.store(context)
            store.deleteLink(treeId, PROFILE)
            store.newEmails(treeId).keys.forEach { store.saveNewEmail(treeId, it, null) }
        }
        Global.settings.autoSave = autoSave
        if (wasGeni) GeniTrees.add(treeId) else GeniTrees.remove(treeId)
        Global.gc = null
        Memory.clear()
    }

    private val details = """{"id":"$PROFILE","profile_url":"https://${Geni.config.host}/people/Ivan-Test/1","claimed":false,"relationship":"aunt","current_residence":{"formatted_location":"Kazan"},"managers":["a","b"],"events":[{"name":"Birth of Ivan Test","date":{"formatted_date":"1900"}},{"name":"Graduation of Ivan Test","date":{"formatted_date":"1920"},"location":{"formatted_location":"Kazan"}},{"name":"Graduation of Ivan Test","date":{"formatted_date":"1920"},"location":{"formatted_location":"Kazan"}}]}"""

    /** Ivan, linked to [PROFILE] with the given snapshot, in a fresh tree that replaces the open one. */
    private fun linkedPerson(snapshot: String?): Person {
        val person = Person().apply {
            id = "I1"
            addName(Name().apply { value = "Ivan /Test/" })
            addEventFact(EventFact().apply { tag = "BIRT"; date = "1900" })
            addEventFact(EventFact().apply { tag = "RESI"; place = "Moscow" })
            GeniMapper.setGeniId(this, PROFILE)
        }
        val gedcom = Gedcom()
        gedcom.header = app.familygem.util.TreeUtil.createHeader("test.json")
        gedcom.addPerson(person)
        gedcom.createIndexes()
        Global.gc = gedcom
        Global.shouldSave = false
        Global.edited = false
        // Loaded just now: opening the card does not ask Geni again
        if (snapshot != null) runBlocking {
            Geni.store(context).saveLinks(listOf(GeniLink(treeId, PROFILE, "I1", true, null, snapshot, System.currentTimeMillis())))
        }
        return person
    }

    private fun openFacts(person: Person): ActivityScenario<ProfileActivity> {
        Memory.setLeader(person)
        return ActivityScenario.launch(ProfileActivity::class.java)
    }

    private val gone = withEffectiveVisibility(Visibility.GONE)

    /** In the list, also below the screen: a row beyond the first screen is not "displayed" for Espresso. */
    private val visible = withEffectiveVisibility(Visibility.VISIBLE)

    @Test
    fun factsShowTheSnapshotDetailsOnceAndKeepTheLocalFacts() {
        GeniTrees.add(treeId)
        val person = linkedPerson(details)
        openFacts(person).use {
            onView(withText(app.familygem.geni.R.string.geni_on_geni)).check(matches(visible))
            onView(withText("Graduation of Ivan Test")).check(matches(visible))
            onView(withText("1920 · Kazan")).check(matches(visible)) // Once: a second row would make the match ambiguous
            onView(withText(app.familygem.geni.R.string.geni_detail_claimed)).check(matches(visible))
            onView(withText(R.string.no)).check(matches(visible)) // An explicit false is shown
            onView(withText("aunt")).check(matches(visible))
            onView(withText(app.familygem.geni.R.string.geni_open_on_geni)).check(matches(visible))
            onView(withText("Birth of Ivan Test")).check(doesNotExist()) // The vital fact is the editable one above
            onView(withText("Moscow")).check(matches(visible)) // The local residence stays
        }
        assertEquals(1, person.eventsFacts.count { it.tag == "RESI" })
    }

    @Test
    fun anOrdinaryTreeAndAnOldSnapshotHaveNoSection() {
        GeniTrees.remove(treeId)
        openFacts(linkedPerson(details)).use {
            onView(withText(app.familygem.geni.R.string.geni_on_geni)).check(doesNotExist())
        }
        GeniTrees.add(treeId)
        openFacts(linkedPerson("""{"id":"$PROFILE","first_name":"Ivan"}""")).use {
            onView(withText(app.familygem.geni.R.string.geni_on_geni)).check(doesNotExist())
            onView(withText("Moscow")).check(matches(isDisplayed()))
        }
    }

    private fun newRelativeEditor(): ActivityScenario<PersonEditorActivity> = ActivityScenario.launch(
        android.content.Intent(context, PersonEditorActivity::class.java).putExtra(Extra.PERSON_ID, "I1").putExtra(Extra.RELATION, Relation.CHILD)
    )

    @Test
    fun emailFieldIsOnlyForANewRelative() {
        GeniTrees.add(treeId)
        linkedPerson(details)
        newRelativeEditor().use { onView(withId(R.id.editor_emailLabel)).check(matches(withEffectiveVisibility(Visibility.VISIBLE))) }
        ActivityScenario.launch<PersonEditorActivity>(
            android.content.Intent(context, PersonEditorActivity::class.java).putExtra(Extra.PERSON_ID, "I1")
        ).use { onView(withId(R.id.editor_emailLabel)).check(matches(gone)) }
        GeniTrees.remove(treeId)
        linkedPerson(null)
        newRelativeEditor().use { onView(withId(R.id.editor_emailLabel)).check(doesNotExist()) } // The Geni fields are not even added
    }

    private fun scrollToEnd() {
        onView(withId(R.id.base_scrollView)).perform(object : androidx.test.espresso.ViewAction {
            override fun getConstraints() = androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.ScrollView::class.java)
            override fun getDescription() = "scroll to the end"
            override fun perform(uiController: androidx.test.espresso.UiController, view: View) {
                (view as android.widget.ScrollView).fullScroll(View.FOCUS_DOWN)
                uiController.loopMainThreadUntilIdle()
            }
        })
    }

    @Test
    fun savedEmailWaitsForTheNewPersonAndABlankOneStoresNothing() {
        GeniTrees.add(treeId)
        linkedPerson(details)
        newRelativeEditor().use {
            onView(withId(R.id.editor_name)).perform(replaceText("Kid"))
            scrollToEnd()
            onView(withId(R.id.editor_email)).perform(replaceText("kid@example.com"))
            scrollToEnd()
            onView(withId(R.id.editor_save)).perform(click())
        }
        val kid = Global.gc.people.single { it.id != "I1" }
        assertEquals(mapOf(kid.id to "kid@example.com"), runBlocking { Geni.store(context).newEmails(treeId) })

        runBlocking { Geni.store(context).saveNewEmail(treeId, kid.id, null) }
        newRelativeEditor().use {
            onView(withId(R.id.editor_name)).perform(replaceText("Other"))
            scrollToEnd()
            onView(withId(R.id.editor_save)).perform(click())
        }
        assertTrue(runBlocking { Geni.store(context).newEmails(treeId) }.isEmpty())
    }

    private companion object {
        const val PROFILE = "profile-900001"
    }
}
