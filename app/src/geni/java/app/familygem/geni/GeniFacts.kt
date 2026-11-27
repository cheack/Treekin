package app.familygem.geni

import android.app.Activity
import android.content.Intent
import android.view.Menu
import android.text.InputType
import android.widget.LinearLayout
import android.widget.Toast
import app.familygem.geni.map.GeniParams
import org.folg.gedcom.model.Family
import app.familygem.DetailActivity
import app.familygem.Global
import app.familygem.util.MediaUtil
import app.familygem.util.NoteUtil
import app.familygem.util.SourceCitationUtil
import org.folg.gedcom.model.Name
import app.familygem.R as AppR
import app.familygem.Memory
import app.familygem.PersonEditorActivity
import app.familygem.constant.Extra
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
    private const val DEATH = "DEAT"

    fun isGeniTree(): Boolean = Global.settings != null && GeniGlue.isGeniTree(Global.settings.openTree)

    /** True for a fact whose date, place (or value of an occupation, cause of death) Geni stores. */
    fun isSynced(fact: EventFact): Boolean = isGeniTree() && EVENTS.any { it.first == fact.tag }

    /** True for a fact whose date Geni stores: a synced person event (not the occupation) or a marriage or divorce of a family. */
    private fun hasSyncedDate(fact: EventFact): Boolean =
        if (Memory.getLeaderObject() is Family) fact.tag == "MARR" || fact.tag == "DIV"
        else isSynced(fact) && fact.tag != OCCUPATION

    /**
     * Refuses a date typed in an event screen that Geni cannot store, with the same error as the person editor.
     * Blanks and the date the fact already has (an untouched legacy text) are accepted. True if refused.
     */
    fun rejectDate(context: android.content.Context, fact: EventFact, text: String): Boolean {
        if (!isGeniTree() || !hasSyncedDate(fact) || text.isEmpty() || text == fact.date?.trim()) return false
        if (GeniParams.parseDate(text) != null) return false
        Toast.makeText(context, R.string.geni_date_not_supported, Toast.LENGTH_LONG).show()
        return true
    }

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
        if (existing == null && tag == DEATH) {
            // A living person: "Dead" is switched on in the Modify form, and nothing changes until it is saved
            activity.startActivity(Intent(activity, PersonEditorActivity::class.java)
                .putExtra(Extra.PERSON_ID, person.id).putExtra(GeniProfileEditor.EXTRA_DEAD, true))
            return true
        }
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

    /** In a Geni tree the main name is edited in the Modify form, with first, middle and display names. True if opened. */
    fun editName(activity: Activity, person: Person?, name: Any?): Boolean {
        if (person == null || !isGeniTree() || name !== person.names.orEmpty().firstOrNull { it.type != "birth" }) return false
        activity.startActivity(Intent(activity, PersonEditorActivity::class.java).putExtra(Extra.PERSON_ID, person.id))
        return true
    }

    /** In a Geni tree removes the unknown sex from the choices (Geni writes male and female). True if trimmed. */
    fun trimSexes(sexes: MutableMap<String, String>): Boolean {
        if (!isGeniTree()) return false
        sexes.remove("U")
        return true
    }

    /** The name the person editor edits: in a Geni tree the main name (the mapper's), else upstream's first name. */
    fun editedName(person: Person): Name {
        val names = person.names
        return if (isGeniTree()) names.firstOrNull { it.type != "birth" } ?: names[0] else names[0]
    }

    /**
     * Places the pieces of an event screen for a fact synced with Geni: only the fields Geni stores are offered,
     * other fields show when they hold data, and media, notes and sources stay. False for any other fact.
     */
    fun placeFields(activity: DetailActivity, event: EventFact): Boolean {
        if (!isSynced(event)) return false
        val occupation = event.tag == OCCUPATION
        fun s(id: Int) = activity.getString(id)
        activity.place(s(AppR.string.value), "Value", occupation, 0)
        activity.place(s(AppR.string.type), "Type", false, 0)
        activity.place(s(AppR.string.date), "Date", !occupation, 0)
        activity.place(s(AppR.string.place), "Place", !occupation, 0)
        if (event.address != null) activity.place(s(AppR.string.address), event.address)
        activity.place(s(AppR.string.cause), "Cause", event.tag == DEATH, 0)
        activity.place(s(AppR.string.age), "Age", false, 0)
        activity.place(s(AppR.string.www), "Www", false, InputType.TYPE_CLASS_TEXT)
        activity.place(s(AppR.string.email), "Email", false, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        activity.place(s(AppR.string.telephone), "Phone", false, InputType.TYPE_CLASS_PHONE)
        activity.place(s(AppR.string.fax), "Fax", false, InputType.TYPE_CLASS_PHONE)
        activity.place(s(AppR.string.rin), "Rin", false, 0)
        activity.place(s(AppR.string.user_id), "Uid", false, 0)
        activity.placeExtensions(event)
        val box = activity.findViewById<LinearLayout>(AppR.id.detail_box)
        MediaUtil.placeMedia(box, event)
        NoteUtil.placeNotes(box, event)
        SourceCitationUtil.placeSourceCitations(box, event)
        return true
    }
}
