package app.familygem.geni

import android.app.Activity
import android.content.Intent
import android.view.Menu
import app.familygem.Global
import app.familygem.Memory
import app.familygem.detail.EventActivity
import app.familygem.util.TreeUtil
import org.folg.gedcom.model.EventFact
import org.folg.gedcom.model.Person

/**
 * Facts of a person in a Geni tree: only the events that Geni's profile sync writes can be created,
 * so a new fact never stays local without notice. Existing local facts keep their usual screens.
 */
object GeniFacts {

    /** Tags of the events synced with Geni, in menu order, with their labels. The index gives the menu ID. */
    private val EVENTS = listOf(
        "BIRT" to app.familygem.R.string.birth,
        "BAPM" to R.string.geni_baptism,
        "OCCU" to app.familygem.R.string.occupation,
        "DEAT" to app.familygem.R.string.death,
        "BURI" to app.familygem.R.string.burial
    )

    /** Menu IDs of the Geni events: far from upstream's IDs (40+ main events, 50+ other events). */
    private const val FIRST_ID = 900

    /** Synced tags whose Geni field is a value, not a date and place. */
    private const val OCCUPATION = "OCCU"

    fun isGeniTree(): Boolean = Global.settings != null && GeniGlue.isGeniTree(Global.settings.openTree)

    /** True for a fact whose date, place (or value of an occupation, cause of death) Geni stores. */
    fun isSynced(fact: EventFact): Boolean = isGeniTree() && EVENTS.any { it.first == fact.tag }

    /** In a Geni tree replaces the items of the event menu with the synced events. */
    fun replaceEventMenu(activity: Activity, menu: Menu) {
        if (!isGeniTree()) return
        menu.clear()
        EVENTS.forEachIndexed { i, (tag, label) ->
            val text = activity.getString(label)
            menu.add(0, FIRST_ID + i, 0, if (Global.settings.expert) "$text — $tag" else text)
        }
    }

    /**
     * Opens the chosen synced event: the first fact with its tag if there is one (a second fact would not reach Geni),
     * else a new empty fact. Returns false for any other menu item.
     */
    fun onEventChosen(activity: Activity, person: Person?, itemId: Int): Boolean {
        val tag = EVENTS.getOrNull(itemId - FIRST_ID)?.first ?: return false
        person ?: return true
        val existing = person.eventsFacts.orEmpty().firstOrNull { it.tag == tag }
        val fact = existing ?: EventFact().apply {
            this.tag = tag
            if (tag == OCCUPATION) value = "" else {
                date = ""
                place = ""
            }
        }
        if (existing == null) person.addEventFact(fact)
        Memory.add(fact)
        activity.startActivity(Intent(activity, EventActivity::class.java))
        if (existing == null) TreeUtil.save(true, person)
        return true
    }

    /** Sexes offered in a Geni tree: Geni writes only male and female. Any other tree keeps all choices. */
    fun sexChoices(sexes: Map<String, String>): Map<String, String> =
        if (isGeniTree()) sexes.filterKeys { it == "M" || it == "F" } else sexes
}
