package app.familygem.geni

import android.app.Activity
import android.content.Intent
import android.view.Menu
import app.familygem.geni.ui.GeniLoginActivity
import app.familygem.geni.ui.GeniSyncBacklogActivity

/** Functions called by one-line GENI-HOOK calls in upstream code. */
object GeniHooks {

    /** Adds the Geni account and sync backlog items to the menu of the trees list. */
    fun addTreesMenu(activity: Activity, menu: Menu) {
        menu.add(R.string.geni_menu_account).setOnMenuItemClickListener {
            activity.startActivity(Intent(activity, GeniLoginActivity::class.java))
            true
        }
        menu.add(R.string.geni_menu_sync_backlog).setOnMenuItemClickListener {
            activity.startActivity(Intent(activity, GeniSyncBacklogActivity::class.java))
            true
        }
    }

    private var onboardingShown = false

    /** The empty trees list gives way to the Geni login once per process; without Geni credentials the upstream prompt stays. */
    internal fun shouldOnboard(configured: Boolean, alreadyShown: Boolean) = configured && !alreadyShown

    /** Opens the Geni login instead of the "add a tree" prompt; true if it did. Back returns to the list with the prompt. */
    fun startOnboarding(activity: Activity): Boolean {
        if (!shouldOnboard(Geni.isConfigured, onboardingShown)) return false
        onboardingShown = true
        activity.startActivity(Intent(activity, GeniLoginActivity::class.java))
        return true
    }

    /**
     * Removes the items that would send the tree to a third party, merge it or make another tree from a shared one: share (5), merge (6), compare (7).
     * For every tree, Geni or not: Treekin has no other way to make a tree than the Geni login.
     */
    fun trimTreeMenu(treeId: Int, menu: Menu) {
        for (id in intArrayOf(5, 6, 7)) menu.removeItem(id)
    }
}
