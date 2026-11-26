package app.familygem.geni

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import androidx.appcompat.widget.SwitchCompat
import app.familygem.DateEditorLayout
import app.familygem.PersonEditorActivity
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.GeniParams
import com.google.android.material.textfield.TextInputLayout
import org.folg.gedcom.model.EventFact
import org.folg.gedcom.model.Name
import org.folg.gedcom.model.Person
import app.familygem.R as AppR

/**
 * The extra fields of the person editor ("Modify") in a Geni tree: the profile fields that Geni's sync maps
 * but the upstream form lacks. They are bound to the GEDCOM records the mapper reads (first non-birth name,
 * birth name, first fact of each tag, the about-me tag), and only fields the user changed are written back,
 * so an untouched legacy value (a date range, a local occupation date) stays as it is.
 * In any other tree the editor does nothing.
 */
class GeniProfileEditor(private val activity: PersonEditorActivity) {

    companion object {
        /** Opens the form with "Dead" switched on, in the form only: nothing is saved until Done. */
        const val EXTRA_DEAD = "app.familygem.geni.dead"
    }

    private var enabled = false
    private var bound = false

    /** Text of each field when the form was filled, to tell the fields the user changed. */
    private val initial = mutableMapOf<Int, String>()

    private fun <T : View> view(id: Int): T = activity.findViewById(id)
    private fun text(id: Int): String = view<EditText>(id).text.toString().trim()

    /** Field IDs synced with Geni, from the upstream form and from the Geni layouts. */
    private val dateFields = listOf(
        AppR.id.editor_birthDate to AppR.id.editor_birthDateLabel,
        AppR.id.editor_deathDate to AppR.id.editor_deathDateLabel,
        AppR.id.editor_baptismDate to AppR.id.editor_baptismDateLabel,
        AppR.id.editor_burialDate to AppR.id.editor_burialDateLabel
    )
    private val textFields = listOf(
        AppR.id.editor_birthSurname, AppR.id.editor_suffix, AppR.id.editor_nicknames, AppR.id.editor_baptismPlace,
        AppR.id.editor_causeOfDeath, AppR.id.editor_burialPlace, AppR.id.editor_occupation, AppR.id.editor_about
    )

    /** Shows and fills the Geni fields; call once the GEDCOM and the person (also a new one) are ready. */
    fun bind(person: Person) {
        enabled = GeniFacts.isGeniTree()
        if (!enabled || bound) return
        bound = true
        val inflater = activity.layoutInflater
        mapOf(
            AppR.id.editor_geniNames to AppR.layout.geni_editor_names,
            AppR.id.editor_geniBaptism to AppR.layout.geni_editor_baptism,
            AppR.id.editor_geniCause to AppR.layout.geni_editor_cause,
            AppR.id.editor_geni to AppR.layout.geni_editor_more
        ).forEach { (containerId, layout) ->
            val container = view<ViewGroup>(containerId)
            inflater.inflate(layout, container, true)
            container.visibility = View.VISIBLE
        }

        val names = person.names.orEmpty()
        val main = names.firstOrNull { it.type != "birth" }
        set(AppR.id.editor_birthSurname, surname(names.firstOrNull { it.type == "birth" }?.value))
        set(AppR.id.editor_suffix, main?.suffix)
        set(AppR.id.editor_nicknames, main?.nickname)
        val baptism = first(person, "BAPM")
        set(AppR.id.editor_baptismDate, baptism?.date)
        set(AppR.id.editor_baptismPlace, baptism?.place)
        set(AppR.id.editor_causeOfDeath, first(person, "DEAT")?.cause)
        val burial = first(person, "BURI")
        set(AppR.id.editor_burialDate, burial?.date)
        set(AppR.id.editor_burialPlace, burial?.place)
        set(AppR.id.editor_occupation, first(person, "OCCU")?.value)
        set(AppR.id.editor_about, GeniMapper.getTag(person, GeniMapper.ABOUT_TAG))
        // The upstream form already holds birth and death
        initial[AppR.id.editor_birthDate] = text(AppR.id.editor_birthDate)
        initial[AppR.id.editor_deathDate] = text(AppR.id.editor_deathDate)
        view<DateEditorLayout>(AppR.id.editor_baptismDateEditor).initialize(view(AppR.id.editor_baptismDate), view<ImageView>(AppR.id.editor_baptismAlert))
        view<DateEditorLayout>(AppR.id.editor_burialDateEditor).initialize(view(AppR.id.editor_burialDate), view<ImageView>(AppR.id.editor_burialAlert))

        // Focus goes through the new name fields
        view<EditText>(AppR.id.editor_surname).nextFocusForwardId = AppR.id.editor_birthSurname
        view<EditText>(AppR.id.editor_birthSurname).nextFocusForwardId = AppR.id.editor_suffix
        view<EditText>(AppR.id.editor_suffix).nextFocusForwardId = AppR.id.editor_nicknames
        view<EditText>(AppR.id.editor_nicknames).nextFocusForwardId = AppR.id.editor_birthDate

        // Geni writes male and female: no tap-to-clear, and an unknown sex is only shown
        view<RadioButton>(AppR.id.editor_sex1).setOnClickListener(null)
        view<RadioButton>(AppR.id.editor_sex2).setOnClickListener(null)
        view<RadioButton>(AppR.id.editor_sex3).apply {
            setOnClickListener(null)
            isEnabled = false
        }

        if (activity.intent.getBooleanExtra(EXTRA_DEAD, false)) view<SwitchCompat>(AppR.id.editor_dead).isChecked = true
    }

    private fun set(id: Int, value: String?) {
        val text = value?.trim().orEmpty()
        view<EditText>(id).setText(text)
        initial[id] = text
    }

    private fun changed(id: Int) = text(id) != initial[id]

    /**
     * Completes the date pickers and checks the dates: a date the user changed must be one Geni can store.
     * Blanks and untouched legacy dates are accepted. On an error the form stays open and the person is not touched.
     */
    fun finishEditingAndValidate(): Boolean {
        if (!enabled) return true
        listOf(AppR.id.editor_birthDateEditor, AppR.id.editor_deathDateEditor, AppR.id.editor_baptismDateEditor, AppR.id.editor_burialDateEditor)
            .forEach { view<DateEditorLayout>(it).finishEditing() }
        val dead = view<SwitchCompat>(AppR.id.editor_dead).isChecked
        var firstError: View? = null
        for ((field, label) in dateFields) {
            val date = text(field)
            val wrong = changed(field) && date.isNotEmpty() && GeniParams.parseDate(date) == null &&
                    (field != AppR.id.editor_deathDate || dead)
            view<TextInputLayout>(label).error = if (wrong) activity.getString(R.string.geni_date_not_supported) else null
            if (wrong && firstError == null) firstError = view(field)
        }
        if (firstError == null) return true
        firstError.requestFocus()
        view<View>(AppR.id.editor_save).isEnabled = true // Disabled by the click
        return false
    }

    /** Writes the changed Geni fields to the person, after the upstream form wrote names, sex, birth and death. */
    fun saveTo(person: Person) {
        if (!enabled) return
        val names = person.names.orEmpty()
        if (changed(AppR.id.editor_birthSurname)) setBirthSurname(person, text(AppR.id.editor_birthSurname))
        if (changed(AppR.id.editor_suffix) || changed(AppR.id.editor_nicknames)) {
            val main = names.firstOrNull { it.type != "birth" } ?: Name().also { person.addName(it) }
            if (changed(AppR.id.editor_suffix)) main.suffix = text(AppR.id.editor_suffix).ifEmpty { null }
            if (changed(AppR.id.editor_nicknames)) main.nickname = text(AppR.id.editor_nicknames).ifEmpty { null }
        }
        setEvent(person, "BAPM", AppR.id.editor_baptismDate, AppR.id.editor_baptismPlace)
        // The upstream form already created or removed the death fact following the switch
        if (changed(AppR.id.editor_causeOfDeath)) first(person, "DEAT")?.cause = text(AppR.id.editor_causeOfDeath).ifEmpty { null }
        setEvent(person, "BURI", AppR.id.editor_burialDate, AppR.id.editor_burialPlace)
        if (changed(AppR.id.editor_occupation)) setOccupation(person, text(AppR.id.editor_occupation))
        if (changed(AppR.id.editor_about))
            GeniMapper.setTag(person, GeniMapper.ABOUT_TAG, view<EditText>(AppR.id.editor_about).text.toString().trim().ifEmpty { null })
    }

    private fun setBirthSurname(person: Person, surname: String) {
        val names = person.names.orEmpty()
        val birth = names.firstOrNull { it.type == "birth" }
        val given = given(names.firstOrNull { it.type != "birth" }?.value)
        when {
            surname.isNotEmpty() -> {
                val value = "$given /$surname/".trim()
                if (birth != null) birth.value = value else person.addName(Name().apply { this.value = value; type = "birth" })
            }
            birth == null -> Unit
            hasLocalData(birth) -> birth.value = given // Kept for its notes, sources or media, without a surname
            else -> person.names = names.filter { it !== birth }.toMutableList()
        }
    }

    /** Updates the changed date and place of the first fact with [tag] in place; never leaves an empty fact without local data. */
    private fun setEvent(person: Person, tag: String, dateId: Int, placeId: Int) {
        val dateChanged = changed(dateId)
        val placeChanged = changed(placeId)
        if (!dateChanged && !placeChanged) return
        val date = text(dateId).ifEmpty { null }
        val place = text(placeId).ifEmpty { null }
        val fact = first(person, tag)
        if (fact == null) {
            if (date != null || place != null) person.addEventFact(EventFact().also { it.tag = tag; it.date = date; it.place = place })
            return
        }
        if (dateChanged) fact.date = date
        if (placeChanged) fact.place = place
        if (fact.date == null && fact.place == null && fact.value.isNullOrBlank() && !hasLocalData(fact)) person.eventsFacts.remove(fact)
    }

    private fun setOccupation(person: Person, text: String) {
        val fact = first(person, "OCCU")
        when {
            text.isNotEmpty() -> if (fact != null) fact.value = text else person.addEventFact(EventFact().also { it.tag = "OCCU"; it.value = text })
            fact == null -> Unit
            // A local date or place of the occupation stays
            fact.date != null || fact.place != null || hasLocalData(fact) -> fact.value = null
            else -> person.eventsFacts.remove(fact)
        }
    }

    private fun first(person: Person, tag: String): EventFact? = person.eventsFacts.orEmpty().firstOrNull { it.tag == tag }

    private fun hasLocalData(fact: EventFact) = fact.notes.orEmpty().isNotEmpty() || fact.noteRefs.orEmpty().isNotEmpty() ||
            fact.sourceCitations.orEmpty().isNotEmpty() || fact.media.orEmpty().isNotEmpty() || fact.mediaRefs.orEmpty().isNotEmpty()

    private fun hasLocalData(name: Name) = name.notes.orEmpty().isNotEmpty() || name.noteRefs.orEmpty().isNotEmpty() ||
            name.sourceCitations.orEmpty().isNotEmpty() || name.media.orEmpty().isNotEmpty() || name.mediaRefs.orEmpty().isNotEmpty()

    /** Surname of a GEDCOM name value like "Ivan /Petrov/". */
    private fun surname(value: String?): String? {
        value ?: return null
        val start = value.indexOf('/')
        if (start < 0) return null
        val end = value.indexOf('/', start + 1).let { if (it < 0) value.length else it }
        return value.substring(start + 1, end).trim()
    }

    /** Given part of a GEDCOM name value: the text outside the surname slashes. */
    private fun given(value: String?): String {
        value ?: return ""
        val start = value.indexOf('/')
        if (start < 0) return value.trim()
        val end = value.indexOf('/', start + 1)
        val after = if (end < 0) "" else value.substring(end + 1)
        return (value.substring(0, start) + " " + after).trim().replace(Regex("\\s+"), " ")
    }
}
