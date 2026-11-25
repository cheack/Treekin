package app.familygem.geni.api

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

/** Date of a Geni event; every part is optional. */
data class GeniDate(val year: Int?, val month: Int?, val day: Int?, val circa: Boolean?)

data class GeniLocation(
    @SerializedName("place_name") val placeName: String?,
    val city: String?,
    val county: String?,
    val state: String?,
    val country: String?
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
    val gender: String? = null,
    @SerializedName("is_alive") val isAlive: Boolean? = null,
    val occupation: String? = null,
    @SerializedName("about_me") val aboutMe: String? = null,
    @SerializedName("cause_of_death") val causeOfDeath: String? = null,
    val nicknames: List<String>? = null,
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

