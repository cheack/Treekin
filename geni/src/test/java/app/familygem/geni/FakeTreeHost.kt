package app.familygem.geni

import app.familygem.geni.sync.LocalPerson
import app.familygem.geni.sync.LocalTree
import org.folg.gedcom.model.Gedcom
import java.io.File

/** Tree host for tests: trees in memory. */
open class FakeTreeHost : TreeHost {
    val trees = mutableMapOf<Int, Gedcom>()
    val titles = mutableMapOf<Int, String>()
    val roots = mutableMapOf<Int, String>()
    var refreshes = 0
    val opened = mutableListOf<Int>()
    val prefetches = mutableListOf<Triple<Int, String, Int>>()

    /** Like the app: one tree at most. Off by default, because most tests build several trees by hand. */
    var oneTree = false

    override suspend fun createTree(title: String, gedcom: Gedcom, onReserved: suspend (Int) -> Unit): Int {
        if (oneTree && trees.isNotEmpty()) throw TreeLimitException()
        val id = (trees.keys.maxOrNull() ?: 0) + 1
        trees[id] = gedcom
        titles[id] = title
        try {
            onReserved(id)
        } catch (e: Throwable) {
            trees.remove(id) // Released, as the app does
            titles.remove(id)
            throw e
        }
        return id
    }

    override suspend fun <T> edit(treeId: Int, block: (Gedcom) -> T): T = block(trees.getValue(treeId))

    override suspend fun openTree(activity: android.app.Activity, treeId: Int): Boolean {
        opened += treeId
        return true
    }

    /** Set to make [savedPerson] fail, like a read during a write. */
    var savedReadFails = false
    /** Persons the saved tree has, when it differs from [trees] (the unsaved edits). Null: the person of [trees]. */
    val saved = mutableMapOf<Pair<Int, String>, LocalPerson?>()
    /** Called at every [savedPerson], to change the tree meanwhile. */
    var onSavedRead: () -> Unit = {}

    override suspend fun savedPerson(treeId: Int, gedcomId: String): LocalPerson? {
        onSavedRead()
        if (savedReadFails) throw java.io.IOException("tree is being written")
        if ((treeId to gedcomId) in saved) return saved[treeId to gedcomId]
        return trees[treeId]?.let { LocalTree.capture(it) }?.people?.firstOrNull { it.gedcomId == gedcomId }
    }

    override fun treeExists(treeId: Int) = treeId in trees

    override suspend fun setRoot(treeId: Int, personId: String) {
        roots[treeId] = personId
    }

    override fun schedulePrefetch(treeId: Int, startGeniId: String, depth: Int) {
        prefetches += Triple(treeId, startGeniId, depth)
    }

    val mediaRoot: File by lazy { kotlin.io.path.createTempDirectory("geni-media").toFile() }

    override fun mediaDir(treeId: Int) = File(mediaRoot, treeId.toString()).apply { mkdirs() }

    override fun refreshScreens() {
        refreshes++
    }
}
