package app.familygem.geni

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import app.familygem.DateEditorLayout
import app.familygem.PersonEditorActivity
import androidx.appcompat.app.AlertDialog
import app.familygem.geni.map.GeniLocales
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.GeniParams
import app.familygem.geni.map.GivenNameParts
import app.familygem.geni.map.LocaleName
import app.familygem.geni.map.LocaleNames
import app.familygem.geni.map.LocationParts
import app.familygem.geni.map.ProfileFields
import app.familygem.util.cleanUpFields
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

        /** Key of the unsaved language rows in the activity's saved state. */
        private const val LOCALES_STATE = "app.familygem.geni.locales"

        /** The row fields by the key of the name field they edit. */
        private val LOCALE_FIELDS = listOf(
            LocaleName.FIRST to AppR.id.editor_locale_first, LocaleName.MIDDLE to AppR.id.editor_locale_middle,
            LocaleName.LAST to AppR.id.editor_locale_last, LocaleName.MAIDEN to AppR.id.editor_locale_maiden,
            LocaleName.SUFFIX to AppR.id.editor_locale_suffix, LocaleName.NICKNAMES to AppR.id.editor_locale_nicknames,
            LocaleName.TITLE to AppR.id.editor_locale_title, LocaleName.DISPLAY to AppR.id.editor_locale_display
        )
    }

    /** A row of the language list: its view and its fields by name field key. */
    private class LocaleRow(val view: View, val fields: Map<String, EditText>) {
        fun value(): LocaleName = LocaleName.of(fields.mapValues { it.value.text.toString() })
    }

    /** The language rows in the order shown, by locale code. */
    private val localeRows = linkedMapOf<String, LocaleRow>()

    /** The person's names in other languages when the form was filled; null while the list is not offered (a new person). */
    private var initialLocales: Map<String, LocaleName>? = null

    /** Keys of the primary name in Geni's last version: not languages, so not offered. */
    private var primaryLocales: Set<String> = emptySet()

    private var enabled = false
    private var bound = false
    private var isNew = false

    /** Text of each field when the form was filled, to tell the fields the user changed. */
    private val initial = mutableMapOf<Int, String>()

    /**
     * Geni's last version of the person. The place inputs show and edit the place name of an event; its other location parts
     * (city, country, street, coordinates) are the event screen's, and stay as they were.
     */
    private var baseline: ProfileFields? = null

    /** The person is linked to Geni but its last version cannot be read. */
    private var unavailable = false

    /** Privacy when the form was filled: the person's stored tag, else Geni's last version; null when neither is known. */
    private var initialPublic: Boolean? = null

    /** The privacy the user wants, kept apart from the switch: a living person shows it off, and Dead back on restores it. */
    private var publicChoice = false

    /** The user touched the Privacy switch: an explicit choice, also when it ends where it started. */
    private var publicTouched = false

    /** The switch can be used: privacy is known, or the person has no Geni version yet (new, or never uploaded). */
    private var privacyUsable = true

    /** Structured location and place text of each event (by tag) when the form was filled. */
    private val originalLocation = mutableMapOf<String, LocationParts>()
    private val originalPlace = mutableMapOf<String, String?>()

    private val placeInputs = mapOf(
        "BIRT" to AppR.id.editor_birthPlace, "BAPM" to AppR.id.editor_baptismPlace,
        "DEAT" to AppR.id.editor_deathPlace, "BURI" to AppR.id.editor_burialPlace
    )

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
            AppR.id.editor_geniMiddle to AppR.layout.geni_editor_middle,
            AppR.id.editor_geniNames to AppR.layout.geni_editor_names,
            AppR.id.editor_geniBaptism to AppR.layout.geni_editor_baptism,
            AppR.id.editor_geniCause to AppR.layout.geni_editor_cause,
            AppR.id.editor_geni to AppR.layout.geni_editor_more
        ).forEach { (containerId, layout) ->
            val container = view<ViewGroup>(containerId)
            inflater.inflate(layout, container, true)
            container.visibility = View.VISIBLE
        }

        view<TextInputLayout>(AppR.id.editor_nameLabel).hint = activity.getString(R.string.geni_first_name)
        view<TextInputLayout>(AppR.id.editor_surnameLabel).hint = activity.getString(R.string.geni_last_name)
        view<EditText>(AppR.id.editor_middleName).filters = view<EditText>(AppR.id.editor_name).filters // No "/" in names

        // First and middle name: the stored parts, or for a record from before them Geni's last version, or the first-space split
        isNew = person.id == null
        val lookup = if (isNew) GeniLocations.Lookup(false, null) else GeniLocations.lookup(activity, person.id)
        val snapshot = lookup.snapshot
        unavailable = lookup.unavailable
        baseline = snapshot?.let { ProfileFields.ofSnapshot(it) }
        val parts = if (person.names.isNullOrEmpty()) null else GeniMapper.givenNamePartsFor(person, snapshot)
        if (parts != null) set(AppR.id.editor_name, parts.first) else initial[AppR.id.editor_name] = text(AppR.id.editor_name)
        set(AppR.id.editor_middleName, parts?.middle)
        set(AppR.id.editor_displayName, GeniMapper.getTag(person, GeniMapper.DISPLAY_NAME_TAG))
        set(AppR.id.editor_nameTitle, GeniMapper.getTag(person, GeniMapper.NAME_TITLE_TAG))

        val main = LocaleNames.main(person)
        set(AppR.id.editor_birthSurname, surname(LocaleNames.birth(person)?.value))
        set(AppR.id.editor_suffix, main?.suffix)
        set(AppR.id.editor_nicknames, main?.nickname)
        val baptism = first(person, "BAPM")
        set(AppR.id.editor_baptismDate, baptism?.date)
        for ((tag, id) in placeInputs) set(id, placeName(person, tag))
        set(AppR.id.editor_causeOfDeath, first(person, "DEAT")?.cause)
        val burial = first(person, "BURI")
        set(AppR.id.editor_burialDate, burial?.date)
        set(AppR.id.editor_occupation, first(person, "OCCU")?.value)
        set(AppR.id.editor_about, GeniMapper.getTag(person, GeniMapper.ABOUT_TAG))
        // The upstream form already holds birth and death
        initial[AppR.id.editor_birthDate] = text(AppR.id.editor_birthDate)
        initial[AppR.id.editor_deathDate] = text(AppR.id.editor_deathDate)
        view<DateEditorLayout>(AppR.id.editor_baptismDateEditor).initialize(view(AppR.id.editor_baptismDate), view<ImageView>(AppR.id.editor_baptismAlert))
        view<DateEditorLayout>(AppR.id.editor_burialDateEditor).initialize(view(AppR.id.editor_burialDate), view<ImageView>(AppR.id.editor_burialAlert))

        // Focus goes through the new name fields
        view<EditText>(AppR.id.editor_name).nextFocusForwardId = AppR.id.editor_middleName
        view<EditText>(AppR.id.editor_middleName).nextFocusForwardId = AppR.id.editor_surname
        view<EditText>(AppR.id.editor_surname).nextFocusForwardId = AppR.id.editor_birthSurname
        view<EditText>(AppR.id.editor_birthSurname).nextFocusForwardId = AppR.id.editor_suffix
        view<EditText>(AppR.id.editor_suffix).nextFocusForwardId = AppR.id.editor_nicknames
        view<EditText>(AppR.id.editor_nicknames).nextFocusForwardId = AppR.id.editor_birthDate

        // Male, female and unknown (verified on the sandbox: unknown clears Geni's sex), but no tap-to-clear:
        // "no sex" is not a state Geni has, and leaving it untouched changes nothing
        view<RadioButton>(AppR.id.editor_sex1).setOnClickListener(null)
        view<RadioButton>(AppR.id.editor_sex2).setOnClickListener(null)
        view<RadioButton>(AppR.id.editor_sex3).setOnClickListener(null)

        if (activity.intent.getBooleanExtra(EXTRA_DEAD, false)) view<SwitchCompat>(AppR.id.editor_dead).isChecked = true
        bindPrivacy(person, snapshot) // After the Dead switch has its final state
        bindLocales(person, snapshot)
    }

    /**
     * The list of names in other languages: a row per language of the person, or the unsaved rows of the form before a rotation.
     * Not offered for a new person (a profile is created without them). Nothing is stored before Done.
     */
    private fun bindLocales(person: Person, snapshot: String?) {
        if (isNew) return
        primaryLocales = snapshot?.let { json -> runCatching { LocaleNames.primaryKeys(com.google.gson.Gson().fromJson(json, app.familygem.geni.api.GeniProfile::class.java)) }.getOrNull() }.orEmpty()
        view<View>(AppR.id.editor_geniLocalesSection).visibility = View.VISIBLE
        val state = activity.savedStateRegistry.consumeRestoredStateForKey(LOCALES_STATE)
        val codes = state?.getStringArrayList("codes")
        // After a rotation the baseline is the one the draft started from, not the person now: a language that Geni's sync added
        // meanwhile is not in the draft, and must not be taken for one the user removed
        val stored = if (state != null) state.getStringArrayList("baseCodes").orEmpty().associateWith { code -> LocaleName.of(readFields(state.getStringArray("base:$code")).values) }
        else LocaleNames.read(person)
        initialLocales = stored
        if (codes != null) {
            for (code in codes) addLocaleRow(code, readFields(state.getStringArray("fields:$code")))
        } else stored.forEach { (code, parts) -> addLocaleRow(code, parts) }
        activity.savedStateRegistry.registerSavedStateProvider(LOCALES_STATE) {
            Bundle().apply {
                putStringArrayList("codes", ArrayList(localeRows.keys))
                localeRows.forEach { (code, row) -> putStringArray("fields:$code", LOCALE_FIELDS.map { row.fields.getValue(it.first).text.toString() }.toTypedArray()) }
                putStringArrayList("baseCodes", ArrayList(stored.keys))
                stored.forEach { (code, parts) -> putStringArray("base:$code", LOCALE_FIELDS.map { parts[it.first].orEmpty() }.toTypedArray()) }
            }
        }
        view<Button>(AppR.id.editor_geniAddLocale).setOnClickListener { chooseLocale() }
    }

    private fun readFields(texts: Array<String>?): LocaleName =
        if (texts == null) LocaleName.EMPTY else LocaleName(LOCALE_FIELDS.mapIndexedNotNull { i, (key, _) -> texts.getOrNull(i)?.let { key to it } }.toMap())

    /** Asks which language to add: the catalog's, less the ones the list has. */
    private fun chooseLocale() {
        val codes = GeniLocales.CHOICES.filter { it !in localeRows && it !in primaryLocales }
        AlertDialog.Builder(activity).setTitle(R.string.geni_locales_add)
            .setItems(codes.map { GeniLocales.label(it) }.toTypedArray()) { _, which ->
                addLocaleRow(codes[which], LocaleName.EMPTY).fields.getValue(LocaleName.FIRST).requestFocus()
            }.show()
    }

    /** Adds the row of [code] to the list, its fields filled from [parts] as they are (a rotation brings the form's own texts back). */
    private fun addLocaleRow(code: String, parts: LocaleName): LocaleRow {
        val container = view<ViewGroup>(AppR.id.editor_geniLocales)
        val row = activity.layoutInflater.inflate(AppR.layout.geni_editor_locale, container, false)
        row.findViewById<TextView>(AppR.id.editor_locale_header).text = GeniLocales.label(code)
        val noSlash = view<EditText>(AppR.id.editor_name).filters // No "/" in names
        val fields = LOCALE_FIELDS.associate { (key, id) ->
            key to row.findViewById<EditText>(id).apply {
                filters = noSlash
                isSaveEnabled = false // The rows share their IDs: the form keeps them itself, see LOCALES_STATE
                setText(parts[key].orEmpty())
            }
        }
        container.addView(row)
        val entry = LocaleRow(row, fields)
        localeRows[code] = entry
        row.findViewById<Button>(AppR.id.editor_locale_remove).setOnClickListener {
            container.removeView(row)
            localeRows.remove(code)
        }
        return entry
    }

    /**
     * Writes the language list: a language the user changed is set, one that is gone or has no field left is removed
     * (Geni drops a language when all its fields are emptied), and an untouched one is not written, so saving again changes nothing.
     */
    private fun saveLocales(person: Person) {
        val initial = initialLocales ?: return
        val current = localeRows.mapValues { it.value.value() }.filterValues { !it.isEmpty }
        for ((code, parts) in current) if (parts != initial[code]) LocaleNames.set(person, code, parts)
        for (code in initial.keys - current.keys) LocaleNames.remove(person, code)
    }

    /** Shows the privacy of the person; a living person is always private on Geni, so the switch only works with Dead on. */
    private fun bindPrivacy(person: Person, snapshot: String?) {
        initialPublic = GeniMapper.getBoolean(person, GeniMapper.PUBLIC_TAG) ?: baseline?.isPublic
        // A linked person whose version has no privacy yet (stored before it was read) waits for the next load
        privacyUsable = initialPublic != null || (!unavailable && snapshot == null)
        val switch = view<SwitchCompat>(AppR.id.editor_geniPublic)
        publicChoice = initialPublic == true
        switch.setOnClickListener { publicTouched = true }
        // Android does not restore this switch: after a recreation the form is rebuilt from the person, so a display forced off
        // by a living person can never be taken for a choice. An unsaved Privacy change does not survive a rotation.
        // The user's changes to the switch are the choice; the changes made by updatePrivacy are not
        switch.isSaveEnabled = false
        switch.setOnCheckedChangeListener { _, checked ->
            if (!updatingPrivacy) {
                publicChoice = checked
                updatePrivacy()
            }
        }
        // The upstream form owns the checked listener of the Dead switch: a tap is listened to here as well
        view<SwitchCompat>(AppR.id.editor_dead).setOnClickListener { updatePrivacy() }
        updatePrivacy()
        // Android restores the Dead switch after this runs: the display follows its final state
        switch.post { updatePrivacy() }
    }

    private var updatingPrivacy = false

    private fun updatePrivacy() {
        val switch = view<SwitchCompat>(AppR.id.editor_geniPublic)
        val dead = view<SwitchCompat>(AppR.id.editor_dead).isChecked
        updatingPrivacy = true
        switch.isChecked = publicChoice && dead
        updatingPrivacy = false
        switch.isEnabled = privacyUsable && dead
        view<TextView>(AppR.id.editor_geniPublicNote).setText(
            when {
                !privacyUsable -> R.string.geni_privacy_unavailable
                !dead -> R.string.geni_privacy_living
                switch.isChecked -> R.string.geni_privacy_public
                else -> R.string.geni_privacy_private
            }
        )
    }

    /** Stores the privacy the user chose: explicit when touched, else only a change from what the person had. */
    private fun savePrivacy(person: Person) {
        if (!privacyUsable) return
        val dead = view<SwitchCompat>(AppR.id.editor_dead).isChecked
        // With Dead on the switch shows the choice, also one restored by Android after the form was recreated
        val value = dead && view<SwitchCompat>(AppR.id.editor_geniPublic).isChecked
        if (publicTouched || value != (initialPublic ?: false)) GeniMapper.setBoolean(person, GeniMapper.PUBLIC_TAG, value)
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
        // The upstream form wrote the first name alone as the given name: the middle name joins it
        val first = text(AppR.id.editor_name).replace("/", "")
        val middle = text(AppR.id.editor_middleName).replace("/", "")
        if (middle.isNotEmpty()) joinGiven(person, first, "$first $middle")
        // Parts are stored for a new person and when edited: an untouched record from before keeps inheriting Geni's
        if (isNew || changed(AppR.id.editor_name) || changed(AppR.id.editor_middleName))
            GeniMapper.setGivenNameParts(person, GivenNameParts(first, middle))
        // An empty override is kept as an empty tag: it tells Geni to remove the display name
        if (changed(AppR.id.editor_displayName)) GeniMapper.setTag(person, GeniMapper.DISPLAY_NAME_TAG, text(AppR.id.editor_displayName))
        if (changed(AppR.id.editor_nameTitle)) GeniMapper.setTag(person, GeniMapper.NAME_TITLE_TAG, text(AppR.id.editor_nameTitle))
        saveLocales(person)
        if (changed(AppR.id.editor_birthSurname)) setBirthSurname(person, text(AppR.id.editor_birthSurname))
        if (changed(AppR.id.editor_suffix) || changed(AppR.id.editor_nicknames)) {
            val main = LocaleNames.main(person) ?: Name().also { person.addName(it) }
            if (changed(AppR.id.editor_suffix)) main.suffix = text(AppR.id.editor_suffix).ifEmpty { null }
            if (changed(AppR.id.editor_nicknames)) main.nickname = text(AppR.id.editor_nicknames).ifEmpty { null }
        }
        setEvent(person, "BAPM", AppR.id.editor_baptismDate, AppR.id.editor_baptismPlace)
        fixUpstreamPlace(person, "BIRT")
        fixUpstreamPlace(person, "DEAT")
        // The upstream form already created or removed the death fact following the switch
        if (changed(AppR.id.editor_causeOfDeath)) first(person, "DEAT")?.let {
            it.cause = text(AppR.id.editor_causeOfDeath).ifEmpty { null }
            it.cleanUpFields() // As upstream: "Y" only on a death fact with nothing else
        }
        setEvent(person, "BURI", AppR.id.editor_burialDate, AppR.id.editor_burialPlace)
        savePrivacy(person)
        if (changed(AppR.id.editor_occupation)) setOccupation(person, text(AppR.id.editor_occupation))
        if (changed(AppR.id.editor_about))
            GeniMapper.setTag(person, GeniMapper.ABOUT_TAG, view<EditText>(AppR.id.editor_about).text.toString().trim().ifEmpty { null })
    }

    /** Puts the whole given name [given] where the upstream form put the first name [first], in the name it edited. */
    private fun joinGiven(person: Person, first: String, given: String) {
        if (person.names.isNullOrEmpty()) return
        val name = GeniFacts.editedName(person)
        fun join(a: String, b: String) = listOf(a.trim(), b.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        val value = name.value
        if (value != null) name.value = when {
            value.startsWith(first) -> join(given, value.substring(first.length))
            value.endsWith(first) -> join(value.dropLast(first.length), given)
            else -> value
        } else if (name.given == first) name.given = given.trim()
    }

    private fun setBirthSurname(person: Person, surname: String) {
        val names = person.names.orEmpty()
        val birth = LocaleNames.birth(person)
        val given = given(LocaleNames.main(person)?.value)
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

    /** The place name the input shows: the one of the event's structured location (see [baseline]). Remembers what the form started from. */
    private fun placeName(person: Person, tag: String): String? {
        val fact = first(person, tag)
        val location = GeniLocations.effective(fact, baseline?.event(tag))
        originalPlace[tag] = fact?.place
        originalLocation[tag] = location
        return location[LocationParts.PLACE_NAME]
    }

    /** Sets the place name of the event's structured location to the text of its input, the other parts as they were. */
    private fun setPlaceName(fact: EventFact, tag: String, placeId: Int) {
        val name = text(placeId).ifEmpty { null }
        // Geni's parts are not known yet: only the place text changes, a legacy edit that the next load reads as the new place name
        if (unavailable && GeniMapper.getLocation(fact) == null) fact.place = name
        else GeniMapper.setLocation(fact, (originalLocation[tag] ?: LocationParts.EMPTY).with(LocationParts.PLACE_NAME, name).storable)
    }

    /**
     * Birth and death, written by the upstream form with the place input as the place text: the place text goes back to
     * what it was (the input shows the place name only), and a changed input becomes the place name of the structured location.
     */
    private fun fixUpstreamPlace(person: Person, tag: String) {
        val fact = first(person, tag) ?: return
        val placeId = placeInputs.getValue(tag)
        if (changed(placeId)) setPlaceName(fact, tag, placeId) else if (originalPlace.containsKey(tag)) fact.place = originalPlace[tag]
    }

    /** Updates the changed date and place name of the first fact with [tag] in place; never leaves an empty fact without local data. */
    private fun setEvent(person: Person, tag: String, dateId: Int, placeId: Int) {
        val dateChanged = changed(dateId)
        val placeChanged = changed(placeId)
        if (!dateChanged && !placeChanged) return
        val date = text(dateId).ifEmpty { null }
        val place = text(placeId).ifEmpty { null }
        val fact = first(person, tag)
        if (fact == null) {
            if (date != null || place != null) person.addEventFact(EventFact().also {
                it.tag = tag
                it.date = date
                if (place != null) GeniMapper.setLocation(it, LocationParts.ofPlace(place))
            })
            return
        }
        if (dateChanged) fact.date = date
        if (placeChanged) setPlaceName(fact, tag, placeId)
        if (fact.date == null && fact.place == null && fact.value.isNullOrBlank() && !hasLocalData(fact) && GeniMapper.getLocation(fact)?.isEmpty != false)
            person.eventsFacts.remove(fact)
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

    /** Same rule as a clear from Geni. */
    private fun hasLocalData(fact: EventFact) = GeniMapper.hasLocalData(fact)

    /** Data of a name besides its value: attachments and name pieces. */
    private fun hasLocalData(name: Name) = name.notes.orEmpty().isNotEmpty() || name.noteRefs.orEmpty().isNotEmpty() ||
            name.sourceCitations.orEmpty().isNotEmpty() || name.media.orEmpty().isNotEmpty() || name.mediaRefs.orEmpty().isNotEmpty() ||
            listOf(name.prefix, name.given, name.surnamePrefix, name.surname, name.suffix, name.nickname).any { !it.isNullOrBlank() }

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
