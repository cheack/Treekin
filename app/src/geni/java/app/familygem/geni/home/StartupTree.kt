package app.familygem.geni.home

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
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

/**
 * The ordinary app launch opens a saved tree on Home. Replaces upstream's "Load last opened tree" switch:
 * after a crash the next launch shows the Trees list once instead, as upstream's crash handler turned that switch off.
 */
object StartupTree {

    private const val PREFS = "treekin_startup"
    private const val SKIP_NEXT = "skipNextLoad"

    /** The startup load in progress (main thread only); it outlives a trees list recreated meanwhile. */
    private var pending: Deferred<Boolean>? = null

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
     * Called by [app.familygem.LauncherActivity] instead of starting the trees list: opens the saved tree straight on Home,
     * so the list never shows at launch (Back from Home closes the app, see [TreeMenu.leaveTree]).
     * Without a tree to open (none saved, skipped after a crash, unreadable) the trees list starts as before.
     * A launcher recreated while the tree is loading waits for the same load.
     */
    fun launch(activity: AppCompatActivity) {
        val load = pending ?: start(activity.applicationContext).also { pending = it }
        activity.lifecycleScope.launch {
            activity.startActivity(if (load.await()) HomeFragment.intent(activity) else Intent(activity, TreesActivity::class.java))
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
