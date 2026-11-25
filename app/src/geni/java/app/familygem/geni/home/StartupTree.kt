package app.familygem.geni.home

import android.content.Context

/**
 * The ordinary app launch opens a saved tree on Home. Replaces upstream's "Load last opened tree" switch:
 * after a crash the next launch shows the Trees list once instead, as upstream's crash handler turned that switch off.
 */
object StartupTree {

    private const val PREFS = "treekin_startup"
    private const val SKIP_NEXT = "skipNextLoad"

    /** The tree to open: the last opened one if it is still saved, else the sole saved tree; 0 for none. */
    fun pick(lastOpened: Int, savedIds: List<Int>): Int = when {
        lastOpened > 0 && lastOpened in savedIds -> lastOpened
        savedIds.size == 1 -> savedIds[0]
        else -> 0
    }

    /** Called by the crash handler: the process dies right after, so the flag is written synchronously. */
    @JvmStatic
    fun skipNextLoad(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(SKIP_NEXT, true).commit()
    }

    /** True once after a crash: this launch stays on the Trees list. */
    fun consumeSkip(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(SKIP_NEXT, false)) return false
        prefs.edit().remove(SKIP_NEXT).apply()
        return true
    }
}
