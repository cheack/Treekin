package app.familygem.geni.home

import android.content.Intent
import android.net.Uri
import android.view.Menu
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import app.familygem.Exporter
import app.familygem.Global
import app.familygem.InfoActivity
import app.familygem.R
import app.familygem.SettingsActivity
import app.familygem.TreesActivity
import app.familygem.constant.Choice
import app.familygem.constant.Extra
import app.familygem.constant.FileType
import app.familygem.main.BaseFragment
import app.familygem.main.MainActivity
import app.familygem.main.TreeSettingsFragment
import app.familygem.util.FileUtil
import app.familygem.util.TreeUtil
import app.familygem.util.Util
import app.familygem.geni.ui.GeniLoginActivity
import app.familygem.geni.ui.GeniSyncBacklogActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.familygem.geni.R as GR

/**
 * Treekin hides the trees list: Back from a tree closes the app, and what the list offered lives in the tree itself.
 * The drawer gets the Geni account, the sync backlog and the app settings; the Tree settings toolbar gets the tree's own
 * actions (info, export and backup in expert mode, delete). The list still opens when there is no tree to show.
 */
object TreeMenu {

    private const val DRAWER_GROUP = 0x7e1

    /** Adds the Geni account, sync backlog and app settings below the drawer's tree items. */
    fun addDrawerItems(activity: MainActivity, drawer: DrawerLayout, menu: Menu) {
        fun add(order: Int, title: Int, icon: Int, open: () -> Unit) =
            menu.add(DRAWER_GROUP, Menu.NONE, 1000 + order, title).setIcon(icon).setOnMenuItemClickListener {
                drawer.closeDrawer(GravityCompat.START)
                open()
                true
            }
        add(0, GR.string.geni_menu_account, GR.drawable.geni_menu_account) {
            activity.startActivity(Intent(activity, GeniLoginActivity::class.java))
        }
        add(1, GR.string.geni_menu_sync_backlog, GR.drawable.geni_menu_sync) {
            activity.startActivity(Intent(activity, GeniSyncBacklogActivity::class.java))
        }
        add(2, R.string.settings, R.drawable.gear) { activity.startActivity(Intent(activity, SettingsActivity::class.java)) }
    }

    /** Back from the last screen of a tree closes the app, trees list included; a picker returns to its caller. True if handled. */
    fun leaveTree(activity: MainActivity): Boolean {
        val intent = activity.intent
        if (listOf(Choice.PERSON, Choice.MEDIA, Choice.NOTE, Choice.SOURCE, Choice.REPOSITORY).any { intent.getBooleanExtra(it, false) })
            return false
        activity.finishAffinity()
        return true
    }

    /** Adds the tree's own actions to the Tree settings toolbar menu. */
    fun addToolbarItems(activity: MainActivity, fragment: BaseFragment, menu: Menu) {
        if (fragment !is TreeSettingsFragment) return
        val treeId = Global.settings.openTree
        menu.add(R.string.info).setOnMenuItemClickListener {
            activity.startActivity(Intent(activity, InfoActivity::class.java).putExtra(Extra.TREE_ID, treeId))
            true
        }
        if (Global.settings.expert) {
            menu.add(R.string.export_gedcom).setOnMenuItemClickListener { exportGedcom(activity, treeId); true }
            menu.add(R.string.make_backup).setOnMenuItemClickListener { export(activity, treeId, FileType.ZIP_BACKUP); true }
        }
        menu.add(GR.string.geni_delete_tree).setOnMenuItemClickListener {
            Util.confirmDelete(activity) {
                TreeUtil.deleteTree(treeId)
                // The trees list is empty again: it shows the Geni login, as on a fresh install
                activity.startActivity(Intent(activity, TreesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
            true
        }
    }

    /** As the trees list does: GEDCOM only, or zipped with its media when there are media files to attach. */
    private fun exportGedcom(activity: MainActivity, treeId: Int) {
        val exporter = Exporter(activity)
        activity.lifecycleScope.launch {
            // The export works on the open tree and reloads it from disk afterwards: unsaved edits are saved first
            if (Global.shouldSave) {
                withContext(Dispatchers.IO) { TreeUtil.saveJson(Global.gc, treeId) }
                Global.shouldSave = false
            }
            if (!withContext(Dispatchers.IO) { exporter.openTree(treeId) }) {
                Toast.makeText(activity, exporter.errorMessage, Toast.LENGTH_LONG).show()
                return@launch
            }
            val media = exporter.countMediaFilesToAttach()
            if (media == 0) export(activity, treeId, FileType.GEDCOM, exporter)
            else {
                val choices = arrayOf(activity.getString(R.string.gedcom_media_zip, media), activity.getString(R.string.gedcom_only))
                AlertDialog.Builder(activity).setTitle(R.string.export_gedcom).setSingleChoiceItems(choices, -1) { dialog, selected ->
                    export(activity, treeId, if (selected == 0) FileType.ZIPPED_GEDCOM else FileType.GEDCOM, exporter)
                    dialog.dismiss()
                }.show()
            }
        }
    }

    /**
     * Asks where to save the file, then writes it. The result launcher is registered on demand and dropped after one use,
     * since it outlives no screen: a result arriving after MainActivity was recreated is lost, as the user can export again.
     */
    private fun export(activity: MainActivity, treeId: Int, type: FileType, opened: Exporter? = null) {
        val exporter = opened ?: Exporter(activity)
        lateinit var launcher: ActivityResultLauncher<Intent>
        launcher = activity.activityResultRegistry.register("treekin.export.${type.name}", ActivityResultContracts.StartActivityForResult()) { result ->
            launcher.unregister()
            val uri: Uri = result.data?.data ?: return@register
            if (result.resultCode != android.app.Activity.RESULT_OK) return@register
            activity.lifecycleScope.launch(Dispatchers.IO) {
                val done = when (type) {
                    FileType.GEDCOM -> exporter.exportGedcom(uri)
                    FileType.ZIPPED_GEDCOM -> exporter.exportGedcomToZip(uri)
                    FileType.ZIP_BACKUP -> exporter.openTree(treeId) && exporter.exportZipBackup(null, -1, uri)
                    else -> false
                }
                // The GEDCOM export rewrites the header and trims the open tree; a failed one would leave it so
                if (type != FileType.ZIP_BACKUP && Global.settings.openTree == treeId) TreeUtil.readJson(treeId)?.let { Global.gc = it }
                Util.toast(if (done) exporter.successMessage else exporter.errorMessage)
            }
        }
        FileUtil.openSaf(treeId, type, launcher)
    }
}
