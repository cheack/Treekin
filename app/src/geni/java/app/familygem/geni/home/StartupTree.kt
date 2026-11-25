package app.familygem.geni.home

import android.content.Context
import android.view.View
import androidx.lifecycle.lifecycleScope
import app.familygem.Global
import app.familygem.TreesActivity
import app.familygem.util.TreeUtil
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * The ordinary app launch opens a saved tree on Home. Replaces upstream's "Load last opened tree" switch:
 * after a crash the next launch shows the Trees list once instead, as upstream's crash handler turned that switch off.
 */
object StartupTree {

    private const val PREFS = "treekin_startup"
    private const val SKIP_NEXT = "skipNextLoad"

    /** The startup load in progress (main thread only); it outlives a trees list recreated meanwhile. */
    private var pending: Deferred<Boolean>? = null

    /** The trees list waiting for [pending]: a second launch intent to the same list does not wait twice. */
    private var waiter = WeakReference<TreesActivity>(null)

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

    /** True once after a crash: this launch stays on the Trees list. Cleared synchronously, so a quick second crash cannot keep it. */
    private fun consumeSkip(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(SKIP_NEXT, false)) return false
        prefs.edit().remove(SKIP_NEXT).commit()
        return true
    }

    /**
     * Opens the startup tree on Home from the trees list; the tree already in memory is reused, so unsaved edits survive.
     * [handled] runs once this launch is dealt with (opened, skipped after a crash, nothing to open or failed), so a recreated
     * list does not repeat it; a list recreated while the tree is still loading waits for the same load.
     */
    fun autoLoad(activity: TreesActivity, handled: () -> Unit) {
        if (pending != null && waiter.get() === activity) return
        waiter = WeakReference(activity)
        val load = pending ?: start(activity.applicationContext).also { pending = it }
        activity.lifecycleScope.launch {
            activity.progress.visibility = View.VISIBLE
            val opened = load.await()
            if (waiter.get() === activity) waiter.clear()
            handled()
            if (opened) activity.startActivity(HomeFragment.intent(activity))
            else activity.progress.visibility = View.GONE
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun start(context: Context): Deferred<Boolean> = GlobalScope.async(Dispatchers.Main) {
        try {
            if (withContext(Dispatchers.IO) { consumeSkip(context) }) return@async false // The previous run crashed
            val treeId = pick(Global.settings.openTree, Global.settings.trees.map { it.id })
            when {
                treeId == 0 -> false
                Global.gc != null && treeId == Global.settings.openTree -> true
                else -> withContext(Dispatchers.IO) { TreeUtil.openGedcom(treeId, true) }
            }
        } finally {
            pending = null
        }
    }
}
