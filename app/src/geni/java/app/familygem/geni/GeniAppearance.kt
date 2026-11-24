package app.familygem.geni

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.widget.TextView
import androidx.annotation.AttrRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import java.util.WeakHashMap

/**
 * The appearance of the app (Original, Geni Light, Geni Dark): night mode, palette overlay and live switching.
 * Original is the upstream AppTheme untouched, the Geni themes add a palette overlay on top of it.
 */
object GeniAppearance {

    /** The appearance each live activity was created with: tells which ones are stale after a change. */
    private val applied = WeakHashMap<Activity, AppearanceMode>()

    /** Applies the stored night mode and the palette to every activity before it inflates its views. */
    fun install(app: Application) {
        AppCompatDelegate.setDefaultNightMode(GeniSettings.appearance(app).nightMode)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) = apply(activity)

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) apply(activity) // No "pre created" callback before Android 10
            }

            override fun onActivityResumed(activity: Activity) = recreateIfStale(activity)
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                applied.remove(activity)
            }
        })
    }

    private fun apply(activity: Activity) {
        val mode = GeniSettings.appearance(activity)
        applied[activity] = mode
        when (mode) {
            AppearanceMode.GENI_LIGHT -> activity.theme.applyStyle(R.style.Geni_Palette_Light, true)
            AppearanceMode.GENI_DARK -> activity.theme.applyStyle(R.style.Geni_Palette_Dark, true)
            AppearanceMode.ORIGINAL -> {}
        }
    }

    /** An activity left behind in the back stack while the user changed the appearance is rebuilt when it comes back. */
    private fun recreateIfStale(activity: Activity) {
        val created = applied[activity] ?: return
        if (created != GeniSettings.appearance(activity) && !activity.isFinishing && !activity.isDestroyed) activity.recreate()
    }

    /**
     * Saves [mode] and applies it at once. A night mode change makes AppCompat rebuild the started activities;
     * the palette alone does not, so the current one is rebuilt here, and the others when they resume.
     */
    fun choose(activity: Activity, mode: AppearanceMode) {
        if (mode == GeniSettings.appearance(activity)) return
        GeniSettings.setAppearance(activity, mode)
        AppCompatDelegate.setDefaultNightMode(mode.nightMode)
        activity.window.decorView.post { recreateIfStale(activity) }
    }

    private fun label(context: Context, mode: AppearanceMode): String = context.getString(
        when (mode) {
            AppearanceMode.ORIGINAL -> R.string.geni_appearance_original
            AppearanceMode.GENI_LIGHT -> R.string.geni_appearance_light
            AppearanceMode.GENI_DARK -> R.string.geni_appearance_dark
        }
    )

    /** Makes [view] the Appearance row of the settings: shows the current choice and opens the picker. */
    fun bindPicker(activity: Activity, view: TextView) {
        val modes = AppearanceMode.entries
        val actual = GeniSettings.appearance(activity)
        view.text = activity.getString(R.string.geni_appearance) + ": " + label(activity, actual)
        view.isFocusable = true // reachable with a keyboard or a switch
        view.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle(R.string.geni_appearance)
                .setSingleChoiceItems(modes.map { label(activity, it) }.toTypedArray(), modes.indexOf(actual)) { dialog, item ->
                    dialog.dismiss()
                    choose(activity, modes[item])
                }.show()
        }
    }

    /** The color of a palette attribute in the theme of [context]. */
    fun color(context: Context, @AttrRes attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data
    }

    val text get() = R.attr.geni_text
    val textGray get() = R.attr.geni_textGray
    val accent get() = R.attr.geni_accent
    val surface get() = R.attr.geni_surface
    val primary get() = R.attr.geni_primary
    val primaryLight get() = R.attr.geni_primaryLight
    val accentLight get() = R.attr.geni_accentLight
}
