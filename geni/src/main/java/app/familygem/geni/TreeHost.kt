package app.familygem.geni

import app.familygem.geni.sync.LocalPerson
import org.folg.gedcom.model.Gedcom
import java.io.File

/** What :geni needs from the app to read and write FamilyGem trees (implemented in the app module). */
interface TreeHost {

    /**
     * Creates a FamilyGem tree with the given content (the host adds the header) and returns its ID.
     * The host allows one tree: with a tree already there it throws [TreeLimitException] before it allocates an ID.
     * [onReserved] runs with the new ID once the tree is registered and before its content is written;
     * if it or the write fails, the host removes the tree again, so no half-made tree stays behind.
     */
    suspend fun createTree(title: String, gedcom: Gedcom, onReserved: suspend (treeId: Int) -> Unit = {}): Int

    /**
     * Runs [block] on a tree and saves it once.
     * The opened tree is edited in memory on the main thread; any other tree is read and written back.
     * Calls are serialized.
     */
    suspend fun <T> edit(treeId: Int, block: (Gedcom) -> T): T

    /**
     * A person as the tree is saved on disk (not the unsaved edits of an open tree), or null if the tree has no such person.
     * Read-only, with no screen or tree side effects. Throws if the saved tree cannot be read (e.g. during a write).
     */
    suspend fun savedPerson(treeId: Int, gedcomId: String): LocalPerson?

    /** True if the FamilyGem tree still exists (the user may have deleted it). */
    fun treeExists(treeId: Int): Boolean

    /** The root person of the tree, null until [setRoot]. It is set last in the tree's setup, so it also tells a finished setup from an interrupted one. */
    fun rootOf(treeId: Int): String?

    /** Sets the root person of the tree, the one the diagram starts from. */
    suspend fun setRoot(treeId: Int, personId: String)

    /** Starts loading the relatives of a profile in background, up to [depth] relationship steps. */
    fun schedulePrefetch(treeId: Int, startGeniId: String, depth: Int)

    /** Media folder of the tree (created if missing): files named in `OBJE.FILE` live here. */
    fun mediaDir(treeId: Int): File

    /**
     * Opens the tree and shows its diagram, as when the user taps it in the trees list (an already open tree keeps its unsaved edits).
     * @return false if the tree could not be read
     */
    suspend fun openTree(activity: android.app.Activity, treeId: Int): Boolean

    /** Tells the open screens that the tree changed. */
    fun refreshScreens()
}

/** The app keeps one tree and it already has it. */
class TreeLimitException : IllegalStateException("Treekin keeps one tree: delete it before making another")
