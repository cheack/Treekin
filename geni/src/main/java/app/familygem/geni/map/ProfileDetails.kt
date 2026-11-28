package app.familygem.geni.map

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** An event of the profile's Geni timeline, as Geni names and dates it. */
data class DetailEvent(val name: String, val date: String?, val place: String?)

/**
 * What Geni knows of a profile beyond the mapped fields, read from the saved snapshot. Read-only: it never reaches GEDCOM or an upload.
 * Every part tolerates a missing key or an unfamiliar shape by being left out. A flag is null when Geni omitted it, and false only when
 * Geni said so (checked on the sandbox, 2026-11-28: `claimed` comes with an explicit false, `big_tree` and `merge_pending` are omitted).
 * `email` and `phone_numbers` were never returned there, not even for profiles created with an email, so they are read only if present.
 */
data class ProfileDetails(
    val email: String? = null,
    val phones: List<String> = emptyList(),
    val residence: String? = null,
    val events: List<DetailEvent> = emptyList(),
    val managers: Int? = null,
    val claimed: Boolean? = null,
    val curator: Boolean? = null,
    val bigTree: Boolean? = null,
    val mergePending: Boolean? = null,
    val relationship: String? = null,
    val profileUrl: String? = null
) {
    val isEmpty: Boolean get() = this == ProfileDetails()

    /** The profile page for a browser: Geni's `profile_url` only if it is https on the configured [host], else nothing. */
    fun browserUrl(host: String): String? = profileUrl?.takeIf {
        runCatching { java.net.URI(it) }.getOrNull()?.let { uri -> uri.scheme == "https" && uri.host.equals(host, ignoreCase = true) } == true
    }

    companion object {
        /**
         * Events Geni names after the profile's own vital facts and unions ("Birth of …"): the card already shows those, so only other
         * events are listed. No custom event could be created on the sandbox: their shape is assumed to be the same.
         */
        private val VITAL = Regex("^(Birth|Death|Burial|Baptism|Marriage|Divorce|Engagement|Separation) of ", RegexOption.IGNORE_CASE)

        /** The details of a raw profile; null if the JSON is not an object. */
        fun ofSnapshot(json: String?): ProfileDetails? {
            val root = runCatching { JsonParser.parseString(json ?: return null) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            return ProfileDetails(
                email = root.text("email"),
                phones = root.safe { phones(it.get("phone_numbers")) } ?: emptyList(),
                residence = root.safe { location(it.get("current_residence")) },
                events = root.safe { events(it.get("events")) } ?: emptyList(),
                managers = root.safe { it.get("managers")?.takeIf(JsonElement::isJsonArray)?.asJsonArray?.size() },
                claimed = root.flag("claimed"),
                // Geni documents `curator`; the sandbox only returns `is_curator`. Both are profile data, not the account's role
                curator = root.flag("curator") ?: root.flag("is_curator"),
                bigTree = root.flag("big_tree"),
                mergePending = root.flag("merge_pending"),
                relationship = root.text("relationship"),
                profileUrl = root.text("profile_url")
            )
        }

        private fun <T> JsonObject.safe(read: (JsonObject) -> T?): T? = runCatching { read(this) }.getOrNull()

        private fun JsonObject.text(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString?.trim()?.takeIf { it.isNotEmpty() }

        private fun JsonObject.flag(key: String): Boolean? =
            get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

        private fun phones(element: JsonElement?): List<String> {
            val list = element?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return emptyList()
            return list.mapNotNull { item ->
                when {
                    item.isJsonPrimitive && item.asJsonPrimitive.isString -> item.asString.trim()
                    item.isJsonObject -> item.asJsonObject.run { text("number") ?: text("phone_number") ?: text("value") }
                    else -> null
                }?.takeIf { it.isNotEmpty() }
            }.distinct()
        }

        /** The formatted text of a Geni location; the parts joined when there is none. */
        private fun location(element: JsonElement?): String? {
            val place = element?.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return null
            place.text("formatted_location")?.let { return it }
            return listOf("place_name", "street_address1", "city", "county", "state", "country").mapNotNull { place.text(it) }
                .joinToString(", ").takeIf { it.isNotEmpty() }
        }

        private fun events(element: JsonElement?): List<DetailEvent> {
            val list = element?.takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return emptyList()
            val result = linkedSetOf<DetailEvent>()
            for (item in list) {
                // An unfamiliar value is skipped, the other events stay
                val event = runCatching {
                    val obj = item.takeIf(JsonElement::isJsonObject)?.asJsonObject ?: return@runCatching null
                    val name = obj.text("name") ?: return@runCatching null
                    if (VITAL.containsMatchIn(name)) return@runCatching null
                    val date = obj.get("date")?.takeIf(JsonElement::isJsonObject)?.asJsonObject?.text("formatted_date")
                    DetailEvent(name, date, location(obj.get("location")))
                }.getOrNull()
                if (event != null) result.add(event)
            }
            return result.toList()
        }
    }
}
