package app.familygem.geni

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.familygem.Global
import app.familygem.geni.sync.GeniTreeInfo
import app.familygem.util.TreeUtil
import kotlinx.coroutines.runBlocking
import org.folg.gedcom.model.Gedcom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One tree on the real tree host and the real sync database: the limit, and a delete followed at once by another
 * account's tree. It REPLACES the trees of the installed app, so it runs only with `-e singleTreeTest 1`:
 * `adb shell am instrument -w -e class app.familygem.geni.SingleTreeTest -e singleTreeTest 1
 *  net.cheack.treekin.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class SingleTreeTest {

    private suspend fun make(owner: String): Int = AppTreeHost.createTree(owner, Gedcom()) { id ->
        Geni.store(Global.context).saveTree(GeniTreeInfo(id, owner, ownerGeniId = owner))
        GeniTrees.add(id)
    }

    @Test
    fun deleteThenOtherAccountAtOnce() = runBlocking {
        assumeTrue("destructive: needs -e singleTreeTest 1", InstrumentationRegistry.getArguments().getString("singleTreeTest") == "1")
        GeniGlue.install()
        val store = Geni.store(Global.context)
        Global.settings.trees.forEach { TreeUtil.deleteTree(it.id) }
        while (store.trees().isNotEmpty() || Geni.isCleaning(1)) Thread.sleep(50) // Earlier cleanups

        val first = make("profile-A")
        try {
            make("profile-B")
            throw AssertionError("a second tree was made")
        } catch (e: TreeLimitException) {
            // Refused before an ID was taken or a row written
        }
        assertEquals(1, Global.settings.trees.size)
        assertEquals(listOf(first), store.trees().map { it.treeId })

        // The user confirms "Delete tree", and the other account signs in at once, before the cleanup of tree A ran
        TreeUtil.deleteTree(first)
        assertEquals(0, Global.settings.trees.size)
        val second = make("profile-B")
        assertTrue(Geni.isCleaning(first).not() || second != first) // Never the ID whose rows are being deleted
        while (Geni.isCleaning(first)) Thread.sleep(50)
        Thread.sleep(300) // The late cleanup of tree A had its chance to wipe tree B
        assertEquals(listOf(second), store.trees().map { it.treeId })
        assertEquals("profile-B", store.tree(second)?.ownerGeniId)
        assertTrue(GeniTrees.isGeni(second))
        assertEquals(second, Global.settings.trees.single().id)
        TreeUtil.deleteTree(second)
    }
}
