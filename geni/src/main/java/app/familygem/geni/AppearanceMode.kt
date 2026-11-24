package app.familygem.geni

import androidx.appcompat.app.AppCompatDelegate

/**
 * The look of the whole app: the palette and the family tree diagram.
 * [stored] is written to the preferences and must never change; [nightMode] is the AppCompat night mode to apply.
 */
enum class AppearanceMode(val stored: String, val nightMode: Int) {
    /** Native Family Gem colors and diagram; follows the device's light or dark setting. */
    ORIGINAL("original", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    GENI_LIGHT("geni_light", AppCompatDelegate.MODE_NIGHT_NO),
    GENI_DARK("geni_dark", AppCompatDelegate.MODE_NIGHT_YES);

    /** True for the two themes with Treekin's own palette and horizontal cards. */
    val isGeni: Boolean get() = this != ORIGINAL

    companion object {
        /** The mode saved as [stored]; a missing or unknown value is [ORIGINAL]. */
        fun fromStored(value: String?): AppearanceMode = entries.firstOrNull { it.stored == value } ?: ORIGINAL
    }
}
