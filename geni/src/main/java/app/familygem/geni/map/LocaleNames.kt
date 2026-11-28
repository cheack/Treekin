package app.familygem.geni.map

import app.familygem.geni.api.GeniProfile
import com.google.gson.JsonElement
import org.folg.gedcom.model.GedcomTag
import org.folg.gedcom.model.Name
import org.folg.gedcom.model.Person
import org.folg.gedcom.parser.ModelParser

/**
 * The name fields Geni keeps per locale, each under its API key, in normalized form: only non-blank values, text with single spaces,
 * nicknames as "a, b". A field missing from [values] is empty.
 * Checked on the sandbox (2026-11-28): every one of these round-trips through `names[<locale>][<key>]`, and none leaks into another locale.
 */
data class LocaleName(val values: Map<String, String> = emptyMap()) {

    operator fun get(key: String): String? = values[key]

    val isEmpty: Boolean get() = values.isEmpty()

    /** These fields with [key] set to [value] (removed when blank). */
    fun with(key: String, value: String?): LocaleName = of(values + (key to (value ?: "")))

    /** The given name: first and middle joined with a space. */
    val given: String? get() = clean(listOfNotNull(values[FIRST], values[MIDDLE]).joinToString(" "))

    companion object {
        const val FIRST = "first_name"
        const val MIDDLE = "middle_name"
        const val LAST = "last_name"
        const val MAIDEN = "maiden_name"
        const val SUFFIX = "suffix"
        const val DISPLAY = "display_name"
        const val NICKNAMES = "nicknames"
        const val TITLE = "title"

        /** The keys of the fields a locale holds, in the order the app shows them. */
        val KEYS = listOf(FIRST, MIDDLE, LAST, MAIDEN, SUFFIX, DISPLAY, NICKNAMES, TITLE)

        val EMPTY = LocaleName()

        /** Normalizes [raw]: unknown keys and blank values go. */
        fun of(raw: Map<String, String?>): LocaleName {
            val values = linkedMapOf<String, String>()
            for (key in KEYS) {
                val value = raw[key]?.let { if (key == NICKNAMES) nicknames(it) else clean(it) } ?: continue
                values[key] = value
            }
            return LocaleName(values)
        }

        /** Nicknames separated by commas, each trimmed, joined with ", "; null for none. */
        fun nicknames(text: String): String? = text.split(',').mapNotNull { clean(it) }.joinToString(", ").ifEmpty { null }
    }
}

/**
 * The names of a profile in other languages, and how they sit in GEDCOM: one `NAME` per locale, after the main and birth names,
 * marked with `_GENI_LOCALE` (GEDCOM 5.5.1 has no language for a `NAME`). Its parts stay apart in tags, the same way the main name's do.
 * Such a name is never the main or the birth name: use [main] and [birth] to pick those.
 */
object LocaleNames {

    const val LOCALE_TAG = "_GENI_LOCALE"
    private const val FIRST_TAG = GeniMapper.FIRST_NAME_TAG
    private const val MIDDLE_TAG = GeniMapper.MIDDLE_NAME_TAG
    private const val MAIDEN_TAG = "_GENI_MAIDEN_NAME"
    private const val DISPLAY_TAG = GeniMapper.DISPLAY_NAME_TAG
    private const val TITLE_TAG = GeniMapper.NAME_TITLE_TAG

    /** The tags a localized name carries besides [LOCALE_TAG]. */
    private val PART_TAGS = listOf(FIRST_TAG, MIDDLE_TAG, MAIDEN_TAG, DISPLAY_TAG, TITLE_TAG)

    /** The locale of a name, or null for a main, birth or any other name that is not a locale of Geni. */
    fun localeOf(name: Name): String? = GeniMapper.getTag(name, LOCALE_TAG)?.trim()?.takeIf { it.isNotEmpty() }

    fun isLocalized(name: Name): Boolean = localeOf(name) != null

    /** The main name: the first name that is not a birth name or a locale. */
    fun main(person: Person): Name? = person.names.orEmpty().firstOrNull { it.type != "birth" && !isLocalized(it) }

    /** The birth (maiden) name. */
    fun birth(person: Person): Name? = person.names.orEmpty().firstOrNull { it.type == "birth" && !isLocalized(it) }

    /** The locales of a person: the first name of each, in the order of the record. */
    fun read(person: Person): Map<String, LocaleName> {
        val result = linkedMapOf<String, LocaleName>()
        for (name in person.names.orEmpty()) {
            val code = localeOf(name) ?: continue
            if (code !in result) result[code] = parts(name)
        }
        return result
    }

    /** The name of this locale, or null. */
    fun find(person: Person, code: String): Name? = person.names.orEmpty().firstOrNull { localeOf(it) == code }

    /** The fields stored on a localized name. */
    fun parts(name: Name): LocaleName {
        val value = name.value.orEmpty()
        val start = value.indexOf('/')
        val given = (if (start < 0) value else value.substring(0, start)).let { clean(it) }
        val last = if (start < 0) null else value.substring(start + 1).substringBefore('/')
        val split = GivenNameParts.split(given)
        val hasParts = GeniMapper.getTag(name, FIRST_TAG) != null || GeniMapper.getTag(name, MIDDLE_TAG) != null
        return LocaleName.of(
            mapOf(
                LocaleName.FIRST to if (hasParts) GeniMapper.getTag(name, FIRST_TAG) else split?.first,
                LocaleName.MIDDLE to if (hasParts) GeniMapper.getTag(name, MIDDLE_TAG) else split?.middle,
                LocaleName.LAST to last,
                LocaleName.MAIDEN to GeniMapper.getTag(name, MAIDEN_TAG),
                LocaleName.SUFFIX to name.suffix,
                LocaleName.DISPLAY to GeniMapper.getTag(name, DISPLAY_TAG),
                LocaleName.NICKNAMES to name.nickname,
                LocaleName.TITLE to GeniMapper.getTag(name, TITLE_TAG)
            )
        )
    }

    /**
     * Stores [parts] on [name] for [code], in place: notes, sources and other extensions of the name stay.
     * The value is the given name and the last name as GEDCOM writes them.
     */
    fun write(name: Name, code: String, parts: LocaleName) {
        name.type = null
        GeniMapper.setTag(name, LOCALE_TAG, code)
        val given = parts.given
        val last = parts[LocaleName.LAST]
        name.value = if (last == null) given.orEmpty() else "${given.orEmpty()} /$last/".trim()
        name.suffix = parts[LocaleName.SUFFIX]
        name.nickname = parts[LocaleName.NICKNAMES]
        val hasGivenParts = parts[LocaleName.FIRST] != null || parts[LocaleName.MIDDLE] != null
        GeniMapper.setTag(name, FIRST_TAG, parts[LocaleName.FIRST]?.takeIf { hasGivenParts })
        GeniMapper.setTag(name, MIDDLE_TAG, parts[LocaleName.MIDDLE]?.takeIf { hasGivenParts })
        GeniMapper.setTag(name, MAIDEN_TAG, parts[LocaleName.MAIDEN])
        GeniMapper.setTag(name, DISPLAY_TAG, parts[LocaleName.DISPLAY])
        GeniMapper.setTag(name, TITLE_TAG, parts[LocaleName.TITLE])
    }

    /** Sets the name of [code]: the existing one is updated in place, a new one goes to the end of the list. */
    fun set(person: Person, code: String, parts: LocaleName) {
        val name = find(person, code) ?: Name().also { person.addName(it) }
        write(name, code, parts)
    }

    /**
     * Removes the name of [code]. A name with notes, sources or media of its own stays as an ordinary name, only its locale
     * and parts go (Geni does not have those local records).
     */
    fun remove(person: Person, code: String) {
        val names = person.names.orEmpty()
        for (name in names.filter { localeOf(it) == code }) {
            if (hasLocalData(name)) {
                GeniMapper.setTag(name, LOCALE_TAG, null)
                PART_TAGS.forEach { GeniMapper.setTag(name, it, null) }
            } else {
                person.names = person.names.filter { it !== name }.toMutableList()
            }
        }
    }

    /** True for a name with notes, sources or media of its own: records Geni does not have. */
    fun hasLocalData(name: Name) = name.notes.orEmpty().isNotEmpty() || name.noteRefs.orEmpty().isNotEmpty() ||
            name.sourceCitations.orEmpty().isNotEmpty() || name.media.orEmpty().isNotEmpty() || name.mediaRefs.orEmpty().isNotEmpty() ||
            hasOtherExtensions(name) ||
            // Name pieces this class does not write (romanization, phonetic form, other GEDCOM name parts)
            listOf(name.romn, name.fone, name.aka, name.marriedName, name.prefix, name.given, name.surname, name.surnamePrefix).any { !it.isNullOrBlank() }

    /** True for an extension of the name that is not one of the tags this class writes. */
    private fun hasOtherExtensions(name: Name): Boolean = name.extensions.orEmpty().any { (key, value) ->
        key != ModelParser.MORE_TAGS_EXTENSION_KEY || (value as? List<*>)?.any { it !is GedcomTag || (it.tag != LOCALE_TAG && it.tag !in PART_TAGS) } != false
    }

    /**
     * The names of a profile in other languages: Geni's `names` without the primary name's own entry. Empty when there are none.
     * That entry is keyed `en-US` (sandbox, 2026-11-28; it is only returned while another locale exists); when it is missing the entry whose
     * first and last name are the profile's own is the primary one (an account in another language).
     */
    fun of(profile: GeniProfile): Map<String, LocaleName> {
        val raw = rawNames(profile) ?: return emptyMap()
        val own = primaryKeys(profile)
        val result = linkedMapOf<String, LocaleName>()
        for ((code, fields) in raw) {
            if (code in own) continue
            val parts = LocaleName.of(fields)
            if (!parts.isEmpty) result[code] = parts
        }
        return result
    }

    /** The keys of the primary name in [GeniProfile.names]; none without one. */
    fun primaryKeys(profile: GeniProfile): Set<String> = primaryKeys(rawNames(profile), profile.firstName, profile.middleName, profile.lastName)

    /**
     * `en-US` is the primary name's key. Without it the entries with the profile's own first, middle and last name are the primary one:
     * the order of the response says nothing, so when two entries match, both stay out of the languages (never written, never imported).
     */
    fun primaryKeys(names: Map<String, Map<String, String?>>?, first: String?, middle: String?, last: String?): Set<String> {
        names ?: return emptySet()
        if (GeniLocales.DEFAULT in names) return setOf(GeniLocales.DEFAULT)
        return names.filter { (_, fields) ->
            clean(fields[LocaleName.FIRST]) == clean(first) && clean(fields[LocaleName.LAST]) == clean(last) &&
                    clean(fields[LocaleName.MIDDLE]) == clean(middle)
        }.keys
    }

    /**
     * Geni's `names` as locale to field to text, read leniently (a field that is not text is joined or skipped, as one odd value
     * must not make the whole profile unreadable); null when the profile has no `names` object.
     */
    fun rawNames(profile: GeniProfile): Map<String, Map<String, String?>>? = rawNames(profile.names)

    fun rawNames(json: JsonElement?): Map<String, Map<String, String?>>? {
        val root = json?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return root.entrySet().filter { it.value.isJsonObject }.associate { (code, fields) ->
            code to fields.asJsonObject.entrySet().associate { (key, value) ->
                key to when {
                    value.isJsonPrimitive -> value.asString
                    value.isJsonArray -> value.asJsonArray.filter { it.isJsonPrimitive }.joinToString(",") { it.asString }
                    else -> null
                }
            }
        }
    }
}
