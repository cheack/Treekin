package app.familygem.geni.map

import app.familygem.geni.api.GeniLocation
import java.math.BigDecimal

/**
 * The parts of a Geni location, each under its API key, in normalized form: only non-blank values, text with single spaces,
 * coordinates as plain numbers ("0", "40.5"). A part missing from [values] is empty.
 * The joined display text (the GEDCOM `PLAC`) is made of [joined]; street lines and coordinates are not part of it.
 */
data class LocationParts(val values: Map<String, String> = emptyMap()) {

    operator fun get(key: String): String? = values[key]

    val isEmpty: Boolean get() = values.isEmpty()

    /** Place text from the non-blank parts, from the most specific; null if there are none. */
    val joined: String? get() = TEXT_KEYS.mapNotNull { values[it] }.joinToString(", ").takeIf { it.isNotEmpty() }

    /**
     * What Geni keeps of these parts: coordinates only together with a text part (checked on the sandbox, 2026-11-28: a location
     * left with nothing but coordinates is dropped whole). Coordinates alone are not storable and count as none.
     */
    val storable: LocationParts
        get() = if (values.keys.any { it in TEXT_PARTS } || values.keys.none { it == LATITUDE || it == LONGITUDE }) this else EMPTY

    /** These parts with [key] set to [value] (removed when blank). */
    fun with(key: String, value: String?): LocationParts = of(values + (key to (value ?: "")))

    companion object {
        const val PLACE_NAME = "place_name"
        const val LATITUDE = "latitude"
        const val LONGITUDE = "longitude"

        /** The keys of the Geni Location structure that the API writes, in the order the app shows them. */
        val KEYS = listOf(
            PLACE_NAME, "city", "county", "state", "country", "street_address1", "street_address2", "street_address3", LATITUDE, LONGITUDE
        )

        /** The keys of every part that is text (all but the coordinates). */
        private val TEXT_PARTS = KEYS - setOf(LATITUDE, LONGITUDE)

        /** The keys that make the joined place text. */
        private val TEXT_KEYS = listOf(PLACE_NAME, "city", "county", "state", "country")

        val EMPTY = LocationParts()

        /** Normalizes [raw]: unknown keys and blank values go. */
        fun of(raw: Map<String, String?>): LocationParts {
            val values = linkedMapOf<String, String>()
            for (key in KEYS) {
                val value = raw[key]?.let { if (key == LATITUDE || key == LONGITUDE) number(it) else clean(it) } ?: continue
                values[key] = value
            }
            return LocationParts(values)
        }

        /** Parts that hold only a place text, the form a record from before structured places has. */
        fun ofPlace(text: String?): LocationParts = EMPTY.with(PLACE_NAME, text)

        /** The parts of a Geni location; the display fields `country_code` and `formatted_location` are not parts. */
        fun of(location: GeniLocation?): LocationParts = if (location == null) EMPTY else of(
            mapOf(
                PLACE_NAME to location.placeName, "city" to location.city, "county" to location.county, "state" to location.state,
                "country" to location.country, "street_address1" to location.streetAddress1, "street_address2" to location.streetAddress2,
                "street_address3" to location.streetAddress3, LATITUDE to location.latitude?.let { plain(BigDecimal(it.toString())) },
                LONGITUDE to location.longitude?.let { plain(BigDecimal(it.toString())) }
            )
        )

        /** A coordinate as a plain number text, or null for blank; text that is not a number stays as it is (Geni refuses it). */
        private fun number(text: String): String? {
            val trimmed = text.trim().takeIf { it.isNotEmpty() } ?: return null
            return trimmed.toBigDecimalOrNull()?.let { plain(it) } ?: trimmed
        }

        private fun plain(number: BigDecimal): String = (if (number.signum() == 0) BigDecimal.ZERO else number.stripTrailingZeros()).toPlainString()

        /** True if [text] is a coordinate Geni can store: a number within [-limit, limit]. */
        fun isCoordinate(text: String, limit: Int): Boolean = text.trim().toBigDecimalOrNull()?.let { it.abs() <= BigDecimal(limit) } == true
    }
}
