package app.familygem.geni

import app.familygem.Global
import app.familygem.Settings
import app.familygem.util.TreeUtil
import app.familygem.geni.sync.LocalPerson
import app.familygem.geni.sync.LocalTree
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.io.FileUtils
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.parser.JsonParser
import java.io.File

/** Tree host on top of FamilyGem's settings and JSON tree files. */
object AppTreeHost : TreeHost {

    private val mutex = Mutex()

    override suspend fun createTree(title: String, gedcom: Gedcom): Int = mutex.withLock {
        val treeId = Global.settings.max() + 1
        gedcom.header = TreeUtil.createHeader("$treeId.json")
        gedcom.createIndexes()
        // The tree must be in the settings before saveJson(), which updates its birthday notifications
        withContext(Dispatchers.Main) {
            Global.settings.addTree(Settings.Tree(treeId, title, 0, 0, null, null, null, 0))
            Global.settings.save()
        }
        TreeUtil.saveJson(gedcom, treeId, false)
        treeId
    }

    override suspend fun <T> edit(treeId: Int, block: (Gedcom) -> T): T = mutex.withLock {
        if (!treeExists(treeId)) error("Tree $treeId does not exist")
        // Screens replace Global.gc on the main thread, so the open tree is picked there
        val (gedcom, result) = editOpened(treeId, block) ?: run {
            val copy = TreeUtil.openGedcomTemporarily(treeId, false) ?: error("Tree $treeId not readable")
            val copyResult = withContext(Dispatchers.Main) { block(copy) }
            // If the tree was opened meanwhile, the live copy gets the change instead (merges are repeatable)
            editOpened(treeId, block) ?: (copy to copyResult)
        }
        val isOpen = withContext(Dispatchers.Main) { gedcom === Global.gc }
        if (isOpen && !Global.settings.autoSave) {
            Global.shouldSave = true // Like upstream: the user's unsaved edits are not written without the Save button
        } else {
            withContext(Dispatchers.IO) { TreeUtil.saveJson(gedcom, treeId, false) }
        }
        withContext(Dispatchers.Main) {
            Global.settings.getTree(treeId)?.let { TreeUtil.refreshData(gedcom, it) }
        }
        result
    }

    /** Runs the block on the open tree if it is [treeId], on the main thread. */
    private suspend fun <T> editOpened(treeId: Int, block: (Gedcom) -> T): Pair<Gedcom, T>? = withContext(Dispatchers.Main) {
        Global.gc?.takeIf { Global.settings.openTree == treeId }?.let { it to block(it) }
    }

    /** Reads the JSON file directly: TreeUtil.readJson toasts errors and may create a notifier, and nothing here may touch Global.gc. */
    override suspend fun savedPerson(treeId: Int, gedcomId: String): LocalPerson? = withContext(Dispatchers.IO) {
        val text = FileUtils.readFileToString(File(Global.context.filesDir, "$treeId.json"), "UTF-8")
        val gedcom = JsonParser().fromJson(text) ?: error("Tree $treeId not readable")
        gedcom.people.orEmpty().firstOrNull { it.id == gedcomId }?.let { LocalTree.capturePerson(it) }
    }

    override fun treeExists(treeId: Int) = Global.settings.getTree(treeId) != null

    override suspend fun setRoot(treeId: Int, personId: String) = withContext(Dispatchers.Main) {
        Global.settings.getTree(treeId)?.let {
            it.root = personId
            Global.settings.save()
        }
        Unit
    }

    override fun schedulePrefetch(treeId: Int, startGeniId: String, depth: Int) =
        GeniPrefetch.enqueue(Global.context, treeId, startGeniId, depth)

    override fun mediaDir(treeId: Int): File {
        val dir = Global.context.getExternalFilesDir(treeId.toString()) ?: error("No external storage")
        dir.mkdirs()
        return dir
    }

    /** Holds the edit lock, so no edit of the tree being replaced lands in the new one; the tree already open is kept as it is. */
    override suspend fun openTree(activity: android.app.Activity, treeId: Int): Boolean = mutex.withLock {
        val alreadyOpen = withContext(Dispatchers.Main) { Global.gc != null && Global.settings.openTree == treeId }
        // As the trees list does: files are read off the main thread
        val opened = alreadyOpen || withContext(Dispatchers.IO) { TreeUtil.openGedcom(treeId, true) }
        if (opened) withContext(Dispatchers.Main) {
            activity.startActivity(android.content.Intent(activity, app.familygem.main.MainActivity::class.java))
        }
        opened
    }

    override fun refreshScreens() {
        Global.edited = true
    }
}
