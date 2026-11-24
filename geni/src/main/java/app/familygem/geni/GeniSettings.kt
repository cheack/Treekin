package app.familygem.geni

import android.content.Context

/** Local settings of the Geni integration (plain preferences: nothing secret). */
object GeniSettings {

    private const val FILE = "geni_settings"
    private const val UPLOAD_PHOTOS = "upload_photos"
    private const val APPEARANCE = "appearance"

    /** The appearance read once per process: the diagram asks for it while drawing every line. */
    @Volatile
    private var appearanceCache: AppearanceMode? = null

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** True (the default) to send new photos of Geni people to Geni. */
    fun uploadPhotos(context: Context): Boolean = prefs(context).getBoolean(UPLOAD_PHOTOS, true)

    fun setUploadPhotos(context: Context, value: Boolean) = prefs(context).edit().putBoolean(UPLOAD_PHOTOS, value).apply()

    /** The chosen appearance; [AppearanceMode.DEFAULT] (Geni Light) until the user picks another one. */
    fun appearance(context: Context): AppearanceMode =
        appearanceCache ?: AppearanceMode.fromStored(prefs(context).getString(APPEARANCE, null)).also { appearanceCache = it }

    fun setAppearance(context: Context, mode: AppearanceMode) {
        appearanceCache = mode
        prefs(context).edit().putString(APPEARANCE, mode.stored).apply()
    }
}
