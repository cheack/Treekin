package app.familygem.geni

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.Visibility
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.familygem.Global
import app.familygem.Memory
import app.familygem.PersonEditorActivity
import app.familygem.R
import app.familygem.constant.Extra
import app.familygem.constant.Relation
import app.familygem.geni.map.GeniMapper
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Name
import org.folg.gedcom.model.Person
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Lineage choice of a new child or sibling in a Geni tree: Birth, Adopted or Foster on the child's own link to its family.
 * Works on an in-memory tree in place of the open one, with auto save off. Run like [GeniProfileEditorTest].
 */
@RunWith(AndroidJUnit4::class)
class GeniLineageEditorTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var treeId = 0
    private var wasGeni = false
    private var autoSave = true
    private var ready = false

    @Before
    fun setUp() {
        GeniGlue.install()
        treeId = Global.settings.openTree
        assumeTrue("needs an open tree", Global.settings.currentTree != null)
        wasGeni = GeniTrees.isGeni(treeId)
        autoSave = Global.settings.autoSave
        ready = true
        Global.settings.autoSave = false
        GeniTrees.add(treeId)
        val gedcom = Gedcom()
        gedcom.header = app.familygem.util.TreeUtil.createHeader("test.json")
        gedcom.addPerson(Person().apply { id = "I1"; addName(Name().apply { value = "Ivan /Test/" }) })
        gedcom.createIndexes()
        Global.gc = gedcom
        Global.shouldSave = false
        Global.edited = false
    }

    @After
    fun tearDown() {
        if (!ready) return // Setup was skipped: nothing of the settings was changed
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            androidx.test.runner.lifecycle.Stage.values().forEach { stage -> monitor.getActivitiesInStage(stage).forEach { it.finish() } }
        }
        instrumentation.waitForIdleSync()
        Global.settings.autoSave = autoSave
        Global.settings.save() // The editor's save wrote the test's setting to the file
        if (wasGeni) GeniTrees.add(treeId) else GeniTrees.remove(treeId)
        Global.gc = null
        Memory.clear()
    }

    private fun editor(relation: Relation?, person: String? = "I1"): ActivityScenario<PersonEditorActivity> = ActivityScenario.launch(
        android.content.Intent(context, PersonEditorActivity::class.java).putExtra(Extra.PERSON_ID, person).apply {
            if (relation != null) putExtra(Extra.RELATION, relation)
        }
    )

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

    private fun choose(id: Int) {
        scrollToEnd()
        onView(withId(id)).perform(click())
    }

    private fun name(text: String) = onView(withId(R.id.editor_name)).perform(replaceText(text))

    private fun save() {
        scrollToEnd()
        onView(withId(R.id.editor_save)).perform(click())
    }

    private val kid get() = Global.gc.people.single { it.id != "I1" }

    @Test
    fun adoptedChildIsMarkedOnItsOwnLink() {
        editor(Relation.CHILD).use {
            name("Kid")
            choose(R.id.editor_lineageAdopted)
            save()
        }
        val ref = kid.parentFamilyRefs.single()
        assertEquals("adopted", ref.relationshipType)
        assertEquals(1, Global.gc.families.size)
        assertEquals(ref.ref, Global.gc.families.single().id)
        assertTrue(Global.gc.getPerson("I1").parentFamilyRefs.isEmpty())
    }

    @Test
    fun birthIsTheDefaultAndLeavesTheLinkUnmarked() {
        editor(Relation.CHILD).use {
            name("Kid")
            onView(withId(R.id.editor_lineageBirth)).check(matches(isChecked()))
            save()
        }
        assertNull(kid.parentFamilyRefs.single().relationshipType)
    }

    @Test
    fun fosterSiblingGetsALinkOfItsOwn() {
        editor(Relation.SIBLING).use {
            name("Sib")
            choose(R.id.editor_lineageFoster)
            save()
        }
        val pivotRef = Global.gc.getPerson("I1").parentFamilyRefs.single()
        val ref = kid.parentFamilyRefs.single()
        assertNotSame(pivotRef, ref) // Shared in upstream: a mark on the new child must not land on the pivot
        assertEquals(pivotRef.ref, ref.ref)
        assertEquals("foster", ref.relationshipType)
        assertNull(pivotRef.relationshipType)
    }

    @Test
    fun choiceSurvivesRotationAndCancelSavesNothing() {
        editor(Relation.CHILD).use { scenario ->
            choose(R.id.editor_lineageFoster)
            scenario.recreate()
            onView(withId(R.id.editor_lineageFoster)).check(matches(isChecked()))
            scrollToEnd()
            onView(withId(R.id.editor_cancel)).perform(click())
        }
        assertEquals(1, Global.gc.people.size)
        assertTrue(Global.gc.families.isEmpty())
    }

    @Test
    fun choiceIsOnlyForANewChildOrSibling() {
        editor(Relation.PARTNER).use { onView(withId(R.id.editor_lineageGroup)).check(matches(withEffectiveVisibility(Visibility.GONE))) }
        editor(null).use { onView(withId(R.id.editor_lineageGroup)).check(matches(withEffectiveVisibility(Visibility.GONE))) }
        GeniTrees.remove(treeId)
        editor(Relation.CHILD).use { onView(withId(R.id.editor_lineageGroup)).check(doesNotExist()) }
    }

    @Test
    fun lineageOfAPersonOnGeniIsReadOnlyAndAnOrdinaryTreeStaysEditable() {
        val person = Global.gc.getPerson("I1")
        editor(null).use { scenario ->
            scenario.onActivity { assertFalse(GeniGlue.blockLineage(it, person)) } // Not on Geni yet
            GeniMapper.setGeniId(person, "profile-900002")
            scenario.onActivity { assertTrue(GeniGlue.blockLineage(it, person)) }
            GeniTrees.remove(treeId)
            scenario.onActivity { assertFalse(GeniGlue.blockLineage(it, person)) }
        }
    }
}
