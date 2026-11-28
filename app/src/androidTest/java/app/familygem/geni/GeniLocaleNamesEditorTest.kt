package app.familygem.geni

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.familygem.Global
import app.familygem.Memory
import app.familygem.PersonEditorActivity
import app.familygem.R
import app.familygem.constant.Extra
import app.familygem.geni.map.GeniLocales
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.GeniParams
import app.familygem.geni.map.LocaleName
import app.familygem.geni.map.LocaleNames
import app.familygem.geni.map.ProfileFields
import app.familygem.profile.ProfileActivity
import org.folg.gedcom.model.EventFact
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Name
import org.folg.gedcom.model.Person
import org.hamcrest.Matchers.hasToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The language list of the Modify form of a Geni tree: names in other languages are listed, edited, added and removed,
 * and only a changed one is written. Works on an in-memory tree with auto save off, like [GeniProfileEditorTest]:
 * `gradle :app:installDebug :app:installDebugAndroidTest`, then
 * `adb shell am instrument -w -e class app.familygem.geni.GeniLocaleNamesEditorTest
 *  net.cheack.treekin.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class GeniLocaleNamesEditorTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
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
        GeniTrees.add(treeId)
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            androidx.test.runner.lifecycle.Stage.values().forEach { stage -> monitor.getActivitiesInStage(stage).forEach { it.finish() } }
        }
        instrumentation.waitForIdleSync()
        Global.settings.autoSave = autoSave
        if (wasGeni) GeniTrees.add(treeId) else GeniTrees.remove(treeId)
        Global.gc = null
        Memory.clear()
    }

    private val de = LocaleName.of(mapOf("first_name" to "Marie", "last_name" to "Schmidt", "maiden_name" to "Meier"))
    private val ru = LocaleName.of(mapOf("first_name" to "Мария", "last_name" to "Пэ"))

    /** A person of a Geni tree with a German and a Russian name, alone in a fresh tree that replaces the open one. */
    private fun person(): Person {
        val person = Person().apply {
            id = "I1"
            addName(Name().apply { value = "Mary /Test/" })
            addEventFact(EventFact().also { it.tag = "SEX"; it.value = "F" })
        }
        LocaleNames.set(person, "de", de)
        LocaleNames.set(person, "ru", ru)
        val gedcom = Gedcom()
        gedcom.header = app.familygem.util.TreeUtil.createHeader("test.json")
        gedcom.addPerson(person)
        gedcom.createIndexes()
        Global.gc = gedcom
        Global.shouldSave = false
        Global.edited = false
        return person
    }

    private fun openEditor(person: Person?): ActivityScenario<PersonEditorActivity> = ActivityScenario.launch(
        android.content.Intent(context, PersonEditorActivity::class.java).also { if (person != null) it.putExtra(Extra.PERSON_ID, person.id) }
    )

    private fun ActivityScenario<PersonEditorActivity>.rows(): List<View> {
        var rows = emptyList<View>()
        onActivity { activity ->
            val container = activity.findViewById<ViewGroup>(R.id.editor_geniLocales)
            rows = (0 until container.childCount).map { container.getChildAt(it) }
        }
        return rows
    }

    private fun View.header() = findViewById<TextView>(R.id.editor_locale_header).text.toString()
    private fun View.text(id: Int) = findViewById<EditText>(id).text.toString()
    private fun View.type(id: Int, text: String) = instrumentation.runOnMainSync { findViewById<EditText>(id).setText(text) }

    private fun ActivityScenario<PersonEditorActivity>.save() {
        onView(withId(R.id.base_scrollView)).perform(object : androidx.test.espresso.ViewAction {
            override fun getConstraints() = androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.ScrollView::class.java)
            override fun getDescription() = "scroll to the end"
            override fun perform(uiController: androidx.test.espresso.UiController, view: View) {
                (view as android.widget.ScrollView).fullScroll(View.FOCUS_DOWN)
                uiController.loopMainThreadUntilIdle()
            }
        })
        onView(withId(R.id.editor_save)).perform(click())
    }

    /** Taps "Add a language": the form is long, so it is scrolled to the button first. */
    private fun addLocale() {
        scrollToTop(R.id.editor_geniAddLocale)
        onView(withId(R.id.editor_geniAddLocale)).perform(click())
    }

    /**
     * Scrolls the form until the view is near its top: Espresso's scrollTo() stops with the view at the bottom edge, under the
     * buttons of the form (the scroll view does not clip to its padding), and a tap there would hit them.
     */
    private fun scrollToTop(id: Int) {
        onView(withId(R.id.base_scrollView)).perform(object : androidx.test.espresso.ViewAction {
            override fun getConstraints() = androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(android.widget.ScrollView::class.java)
            override fun getDescription() = "scroll the view near the top"
            override fun perform(uiController: androidx.test.espresso.UiController, view: View) {
                val scroll = view as android.widget.ScrollView
                var target = scroll.findViewById<View>(id)
                var y = 0
                while (target !== scroll) { y += target.top; target = target.parent as View }
                scroll.scrollTo(0, y - scroll.height / 4)
                uiController.loopMainThreadUntilIdle()
            }
        })
    }

    private fun ProfileFields.paramsTo(person: Person) = GeniParams.profile(this, ProfileFields.of(person))

    @Test
    fun theLanguagesOfThePersonAreListed() {
        val person = person()
        openEditor(person).use { scenario ->
            val rows = scenario.rows()
            assertEquals(listOf("German - Deutsch (de)", "Russian - Русский (ru)"), rows.map { it.header() })
            assertEquals("Marie", rows[0].text(R.id.editor_locale_first))
            assertEquals("Schmidt", rows[0].text(R.id.editor_locale_last))
            assertEquals("Meier", rows[0].text(R.id.editor_locale_maiden))
            assertEquals("Мария", rows[1].text(R.id.editor_locale_first))
            scrollToTop(R.id.editor_geniAddLocale)
            onView(withId(R.id.editor_geniAddLocale)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun editingOneLanguageWritesItAndASecondSaveQueuesNothing() {
        val person = person()
        val before = ProfileFields.of(person)
        openEditor(person).use { scenario ->
            scenario.rows()[1].type(R.id.editor_locale_last, "Пэ-Эс")
            scenario.save()
        }
        assertEquals(listOf("names[ru][last_name]" to "Пэ-Эс"), before.paramsTo(person))
        assertEquals(de, ProfileFields.of(person).locales.getValue("de"))
        assertEquals("Mary /Test/", LocaleNames.main(person)!!.value)
        val saved = ProfileFields.of(person)
        openEditor(person).use { it.save() }
        assertEquals(emptyList<Pair<String, String>>(), saved.paramsTo(person))
        assertEquals(saved, ProfileFields.of(person))
    }

    @Test
    fun addingALanguageWritesItsFields() {
        val person = person()
        val before = ProfileFields.of(person)
        openEditor(person).use { scenario ->
            addLocale()
            onData(hasToString(GeniLocales.label("fr"))).inRoot(isDialog()).perform(click())
            val rows = scenario.rows()
            assertEquals(3, rows.size)
            assertEquals(GeniLocales.label("fr"), rows[2].header())
            rows[2].type(R.id.editor_locale_first, "Marie")
            rows[2].type(R.id.editor_locale_last, "Schmitt")
            scenario.save()
        }
        assertEquals(listOf("names[fr][first_name]" to "Marie", "names[fr][last_name]" to "Schmitt"), before.paramsTo(person))
        assertEquals("Marie /Schmitt/", LocaleNames.find(person, "fr")!!.value)
        // The new language is last: the main name stays the first
        assertEquals(LocaleNames.main(person), person.names[0])
    }

    @Test
    fun theLanguageAlreadyListedIsNotOffered() {
        val person = person()
        openEditor(person).use {
            addLocale()
            onData(hasToString(GeniLocales.label("fr"))).inRoot(isDialog()).check(matches(isDisplayed()))
            assertFalse(GeniLocales.label("de") in dialogItems())
            assertFalse(GeniLocales.DEFAULT in dialogItems().map { it.substringAfterLast('(').removeSuffix(")") })
        }
    }

    private fun dialogItems(): List<String> {
        var items = emptyList<String>()
        instrumentation.runOnMainSync {
            val monitor = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            val activity = monitor.getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).firstOrNull() ?: return@runOnMainSync
            val list = findListView(activity.window.decorView.rootView)
            items = list?.let { l -> (0 until l.adapter.count).map { l.adapter.getItem(it).toString() } }.orEmpty()
        }
        return items
    }

    private fun findListView(view: View): android.widget.ListView? {
        if (view is android.widget.ListView && view.adapter?.count ?: 0 > 20) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findListView(view.getChildAt(i))?.let { return it }
        return null
    }

    @Test
    fun removingALanguageClearsItsFields() {
        val person = person()
        val before = ProfileFields.of(person)
        openEditor(person).use { scenario ->
            instrumentation.runOnMainSync { scenario.rows()[0].findViewById<View>(R.id.editor_locale_remove).performClick() }
            assertEquals(listOf("Russian - Русский (ru)"), scenario.rows().map { it.header() })
            scenario.save()
        }
        assertEquals(
            listOf("names[de][first_name]" to "", "names[de][last_name]" to "", "names[de][maiden_name]" to ""),
            before.paramsTo(person)
        )
        assertEquals(setOf("ru"), ProfileFields.of(person).locales.keys)
        assertEquals(2, person.names.size)
    }

    @Test
    fun aLanguageEmptiedFieldByFieldIsRemoved() {
        val person = person()
        openEditor(person).use { scenario ->
            val row = scenario.rows()[1]
            listOf(R.id.editor_locale_first, R.id.editor_locale_last).forEach { row.type(it, "") }
            scenario.save()
        }
        assertEquals(setOf("de"), ProfileFields.of(person).locales.keys)
    }

    @Test
    fun anUnsavedDraftSurvivesARotationAndCancelStoresNothing() {
        val person = person()
        val stored = ProfileFields.of(person)
        openEditor(person).use { scenario ->
            scenario.rows()[0].type(R.id.editor_locale_last, "Draft")
            addLocale()
            onData(hasToString(GeniLocales.label("fr"))).inRoot(isDialog()).perform(click())
            scenario.rows()[2].type(R.id.editor_locale_first, "Brouillon")
            instrumentation.runOnMainSync { scenario.rows()[1].findViewById<View>(R.id.editor_locale_remove).performClick() }
            scenario.recreate()
            val rows = scenario.rows()
            assertEquals(listOf(GeniLocales.label("de"), GeniLocales.label("fr")), rows.map { it.header() })
            assertEquals("Draft", rows[0].text(R.id.editor_locale_last))
            assertEquals("Meier", rows[0].text(R.id.editor_locale_maiden))
            assertEquals("Brouillon", rows[1].text(R.id.editor_locale_first))
        } // Closed without Done: nothing was stored
        assertEquals(stored, ProfileFields.of(person))
        openEditor(person).use { scenario ->
            assertEquals("Schmidt", scenario.rows()[0].text(R.id.editor_locale_last))
            assertEquals(2, scenario.rows().size)
        }
    }

    @Test
    fun editingTheMainNameLeavesTheLanguagesAlone() {
        val person = person()
        val before = ProfileFields.of(person)
        openEditor(person).use { scenario ->
            instrumentation.runOnMainSync {
                scenario.onActivity { it.findViewById<EditText>(R.id.editor_surname).setText("Changed") }
            }
            scenario.save()
        }
        assertEquals(listOf("last_name" to "Changed"), before.paramsTo(person))
        assertEquals(before.locales, ProfileFields.of(person).locales)
        assertEquals("Mary /Changed/", LocaleNames.main(person)!!.value)
    }

    @Test
    fun anOrdinaryTreeHasNoLanguageControls() {
        val person = person()
        GeniTrees.remove(treeId)
        openEditor(person).use { scenario ->
            scenario.onActivity {
                assertEquals(View.GONE, it.findViewById<View>(R.id.editor_geniNames).visibility)
                assertEquals(0, it.findViewById<ViewGroup>(R.id.editor_geniLocales)?.childCount ?: 0)
            }
            assertFalse(GeniFacts.isLocaleName(person.names[1]))
        }
    }

    @Test
    fun aNewPersonHasNoLanguageList() {
        person()
        openEditor(null).use { scenario ->
            scenario.onActivity { assertEquals(View.GONE, it.findViewById<View>(R.id.editor_geniLocalesSection).visibility) }
        }
    }

    @Test
    fun aNameInAnotherLanguageOpensModifyAndNeverMovesOrDeletes() {
        val person = person()
        val de = person.names[1]
        assertTrue(GeniFacts.isLocaleName(de))
        assertFalse(GeniFacts.isLocaleName(person.names[0]))
        // Nothing moves past a language: the main name stays the first
        assertFalse(GeniFacts.canMoveName(person, person.names[0], 1))
        assertFalse(GeniFacts.canMoveName(person, de, -1))
        assertFalse(GeniFacts.canMoveName(person, de, 1))
        assertFalse(GeniFacts.canMoveName(person, person.names[2], -1))
        assertEquals(context.getString(app.familygem.geni.R.string.geni_locale_name_title, GeniLocales.label("de")), GeniFacts.nameTitle(context, de))
        assertEquals(null, GeniFacts.nameTitle(context, person.names[0]))
        // Ordinary names move among themselves as upstream lets them, but not past a language
        val ordinary = Name().apply { value = "Other /Name/" }
        val another = Name().apply { value = "Another /Name/" }
        person.addName(ordinary)
        person.addName(another)
        assertFalse(GeniFacts.canMoveName(person, ordinary, -1))
        assertTrue(GeniFacts.canMoveName(person, another, -1))
        Memory.setLeader(person)
        ActivityScenario.launch(ProfileActivity::class.java).use {
            onView(androidx.test.espresso.matcher.ViewMatchers.withText(context.getString(app.familygem.geni.R.string.geni_locale_name_title, GeniLocales.label("de"))))
                .perform(click())
            scrollToTop(R.id.editor_geniAddLocale)
            onView(withId(R.id.editor_geniAddLocale)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun theLanguagesStayWhenTheFirstNameChanges() {
        val person = person()
        GeniMapper.setGivenNameParts(person, app.familygem.geni.map.GivenNameParts("Mary", null))
        val before = ProfileFields.of(person)
        openEditor(person).use { it.save() }
        assertEquals(before.locales, ProfileFields.of(person).locales)
    }
}
