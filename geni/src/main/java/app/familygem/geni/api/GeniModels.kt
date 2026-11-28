package app.familygem.geni.api

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

/**
 * Date of a Geni event; every part is optional.
 * A [range] of `before` or `after` has one date; `between` also has the end date in the `end*` parts.
 */
data class GeniDate(
    val year: Int?,
    val month: Int?,
    val day: Int?,
    val circa: Boolean?,
    val range: String? = null,
    @SerializedName("end_year") val endYear: Int? = null,
    @SerializedName("end_month") val endMonth: Int? = null,
    @SerializedName("end_day") val endDay: Int? = null,
    @SerializedName("end_circa") val endCirca: Boolean? = null
)

data class GeniLocation(
    @SerializedName("place_name") val placeName: String?,
    val city: String?,
    val county: String?,
    val state: String?,
    val country: String?,
    @SerializedName("street_address1") val streetAddress1: String? = null,
    @SerializedName("street_address2") val streetAddress2: String? = null,
    @SerializedName("street_address3") val streetAddress3: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

data class GeniEvent(val date: GeniDate?, val location: GeniLocation?)

/** Geni profile with the fields the app maps. */
data class GeniProfile(
    val id: String,
    val guid: String? = null,
    @SerializedName("first_name") val firstName: String? = null,
    @SerializedName("middle_name") val middleName: String? = null,
    @SerializedName("last_name") val lastName: String? = null,
    @SerializedName("maiden_name") val maidenName: String? = null,
    val suffix: String? = null,
    val name: String? = null,
    /** Set only while the profile has a display name override (checked on the sandbox, 2026-11-26). */
    @SerializedName("display_name") val displayName: String? = null,
    /** Name title like "Dr."; top-level and set only while there is one (checked on the sandbox, 2026-11-26). */
    val title: String? = null,
    /** "male" or "female"; omitted while the sex is cleared (checked on the sandbox, 2026-11-28: `gender=unknown` and an empty value both clear it). */
    val gender: String? = null,
    /** Always returned on the sandbox, an explicit false included; a living profile is never public. */
    val public: Boolean? = null,
    /** Curator lock; never returned for a basic account (sandbox, 2026-11-28), so it is read-only and may stay null. */
    val locked: Boolean? = null,
    /** Curator note: documented as a string or an array of strings; never returned for a basic account. */
    @SerializedName("merge_note") val mergeNote: JsonElement? = null,
    @SerializedName("is_alive") val isAlive: Boolean? = null,
    val occupation: String? = null,
    @SerializedName("about_me") val aboutMe: String? = null,
    @SerializedName("cause_of_death") val causeOfDeath: String? = null,
    val nicknames: List<String>? = null,
    /**
     * Names by locale (`{"en-US": {...}, "de": {...}}`), each with the fields first_name, middle_name, last_name, maiden_name, suffix,
     * display_name, nicknames (a comma separated text) and title. Omitted by Geni while the profile has no other language
     * (sandbox, 2026-11-28); the client then writes `{}` into the stored version, so a missing key means a version from before.
     * Read it through `LocaleNames`.
     */
    val names: JsonElement? = null,
    val birth: GeniEvent? = null,
    val baptism: GeniEvent? = null,
    val death: GeniEvent? = null,
    val burial: GeniEvent? = null,
    @SerializedName("updated_at") val updatedAt: String? = null,
    /** When the profile was created on Geni, in Unix seconds. */
    @SerializedName("created_at") val createdAt: String? = null,
    val deleted: Boolean? = null,
    @SerializedName("merged_into") val mergedInto: String? = null,
    @SerializedName("mugshot_urls") val mugshotUrls: Map<String, String>? = null
)

/** The merge note as text, the items of an array joined by line breaks; null when there is none. */
val GeniProfile.mergeNoteText: String?
    get() = mergeNote?.let { note ->
        when {
            note.isJsonPrimitive -> note.asString
            note.isJsonArray -> note.asJsonArray.filter { it.isJsonPrimitive }.joinToString("\n") { it.asString }
            else -> null
        }
    }?.trim()?.takeIf { it.isNotEmpty() }

/** Geni union: a couple and their children. */
data class GeniUnion(
    val id: String,
    val status: String?,
    val partners: List<String>?,
    val children: List<String>?,
    val marriage: GeniEvent?,
    val divorce: GeniEvent?
)

/** IDs of the profiles and unions returned by profile-X/immediate-family. */
data class ImmediateFamily(val focusId: String, val profileIds: List<String>, val unionIds: List<String>) {
    companion object {
        fun parse(json: String): ImmediateFamily {
            val root = JsonParser.parseString(json).asJsonObject
            val ids = root.getAsJsonObject("nodes").keySet()
            return ImmediateFamily(
                focusId = root.getAsJsonObject("focus").get("id").asString,
                profileIds = ids.filter { it.startsWith("profile-") },
                unionIds = ids.filter { it.startsWith("union-") }
            )
        }
    }
}

/** Geni ID ("profile-1") from an API URL or an ID. */
fun geniId(urlOrId: String): String = urlOrId.substringAfterLast('/')

/** Items of a batch response (see [rawResults]). */
fun <T> parseResults(json: String, type: Class<T>): List<T> = rawResults(json).map { Gson().fromJson(it, type) }

/** Raw JSON of each item of a batch response: {"results": [...]}, or the bare object when only one ID was asked. */
fun rawResults(json: String): List<String> {
    val root = JsonParser.parseString(json).asJsonObject
    val results = root.getAsJsonArray("results")
    return when {
        results != null -> results.map { it.toString() }
        root.has("id") -> listOf(root.toString())
        else -> emptyList()
    }
}

