package app.familygem.geni.sync

import app.familygem.geni.FakeTreeHost
import app.familygem.geni.map.GeniMapper
import kotlinx.coroutines.test.runTest
import org.folg.gedcom.model.ChildRef
import org.folg.gedcom.model.Family
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Person
import org.folg.gedcom.model.SpouseRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeleteRulesTest {

    @Test
    fun treeThatIsNotGeniDeletesAsUpstream() =
        assertEquals(DeleteDecision.Upstream, DeleteRules.decide(false, "profile-2", "profile-1"))

    @Test
    fun personWithoutGeniIdDeletesAsUpstream() =
        assertEquals(DeleteDecision.Upstream, DeleteRules.decide(true, null, "profile-1"))

    @Test
    fun rootPersonIsBlocked() =
        assertEquals(DeleteDecision.BlockedRoot, DeleteRules.decide(true, "profile-1", "profile-1"))

    @Test
    fun otherGeniPersonGetsTheDialog() =
        assertEquals(DeleteDecision.Dialog, DeleteRules.decide(true, "profile-2", "profile-1"))

    @Test
    fun managedIsDecidedByGuid() {
        assertTrue(DeleteRules.isManaged("""{"id":"profile-2","guid":"1165127"}""", setOf("1165127", "9")))
        assertFalse(DeleteRules.isManaged("""{"id":"profile-2","guid":"5"}""", setOf("1165127")))
        assertFalse(DeleteRules.isManaged("""{"id":"profile-2"}""", setOf("1165127")))
        assertFalse(DeleteRules.isManaged(null, setOf("1165127")))
    }

    private fun person(id: String, geniId: String) = Person().also { it.id = id; GeniMapper.setGeniId(it, geniId) }

    @Test
    fun removeFromTreeDropsPersonFamiliesAndLinksWithoutUpload() = runTest {
        val host = FakeTreeHost()
        val gedcom = Gedcom().apply {
            addPerson(person("I1", "profile-1")); addPerson(person("I2", "profile-2"))
            addFamily(Family().apply { id = "F1"; addHusband(SpouseRef().apply { ref = "I2" }) })
            addFamily(Family().apply { id = "F2"; addHusband(SpouseRef().apply { ref = "I1" }); addChild(ChildRef().apply { ref = "I2" }) })
        }
        host.trees[1] = gedcom
        val store = InMemorySyncStore()
        store.saveLinks(listOf(
            GeniLink(1, "profile-1", "I1", true, null, "{}", 0), GeniLink(1, "profile-2", "I2", true, null, "{}", 0),
            GeniLink(1, "union-1", "F1", true, null, "{}", 0), GeniLink(1, "union-2", "F2", true, null, "{}", 0)
        ))
        assertTrue(PersonRemoval(store, host).removeFromTree(1, "profile-2"))
        assertEquals(listOf("I1"), gedcom.people.map { it.id })
        assertEquals(listOf("F2"), gedcom.families.map { it.id })
        assertNull(store.link(1, "profile-2"))
        assertNull(store.link(1, "union-1"))
        assertEquals("union-2", store.link(1, "union-2")?.geniId)
        assertTrue(store.jobs(1).isEmpty())
        assertEquals(listOf(FeedRecordKind.REMOVED_FROM_DEVICE to "profile-2"), store.feedMap.values.map { it.kind to it.geniId })
    }

    @Test
    fun removeFromTreeOfAnUnknownPersonChangesNothing() = runTest {
        val host = FakeTreeHost().apply { trees[1] = Gedcom().apply { addPerson(person("I1", "profile-1")) } }
        assertFalse(PersonRemoval(InMemorySyncStore(), host).removeFromTree(1, "profile-9"))
        assertEquals(1, host.trees.getValue(1).people.size)
    }

    @Test
    fun removeFromTreeDropsTheWaitingEditOfThePerson() = runTest {
        val host = FakeTreeHost().apply { trees[1] = Gedcom().apply { addPerson(person("I2", "profile-2")) } }
        val store = InMemorySyncStore()
        store.saveLinks(listOf(GeniLink(1, "profile-2", "I2", true, null, "{}", 0)))
        store.saveJob(UploadJob(treeId = 1, key = "profile:profile-2", kind = JobKind.UPDATE_PROFILE, geniId = "profile-2",
            params = listOf("occupation" to "Pilot"), label = "x", localHash = 1))
        PersonRemoval(store, host).removeFromTree(1, "profile-2")
        assertTrue(store.jobs(1).isEmpty())
    }

    @Test
    fun removeFromTreeFindsThePersonByGeniTagAfterAGedcomIdEdit() = runTest {
        val host = FakeTreeHost().apply { trees[1] = Gedcom().apply { addPerson(person("I9", "profile-2")); addPerson(person("I1", "profile-1")) } }
        val store = InMemorySyncStore()
        store.saveLinks(listOf(GeniLink(1, "profile-2", "I2", true, null, "{}", 0))) // The link still has the old ID
        assertTrue(PersonRemoval(store, host).removeFromTree(1, "profile-2"))
        assertEquals(listOf("I1"), host.trees.getValue(1).people.map { it.id })
        assertNull(store.link(1, "profile-2"))
    }

    @Test
    fun removeFromTreeNeverRemovesTheRoot() = runTest {
        val host = FakeTreeHost().apply { trees[1] = Gedcom().apply { addPerson(person("I1", "profile-1")) } }
        val store = InMemorySyncStore()
        store.saveTree(GeniTreeInfo(1, "profile-1"))
        store.saveLinks(listOf(GeniLink(1, "profile-1", "I1", true, null, "{}", 0)))
        assertFalse(PersonRemoval(store, host).removeFromTree(1, "profile-1"))
        assertEquals(1, host.trees.getValue(1).people.size)
    }

    @Test
    fun removeFromTreeCancelsAQueuedDeleteOnGeni() = runTest {
        val host = FakeTreeHost().apply { trees[1] = Gedcom().apply { addPerson(person("I2", "profile-2")) } }
        val store = InMemorySyncStore()
        store.saveLinks(listOf(GeniLink(1, "profile-2", "I2", true, null, "{}", 0)))
        store.saveJob(UploadJob(treeId = 1, key = "delete:profile-2", kind = JobKind.DELETE_PROFILE, geniId = "profile-2",
            params = emptyList(), label = "x", localHash = 0))
        PersonRemoval(store, host).removeFromTree(1, "profile-2")
        assertTrue(store.jobs(1).isEmpty())
    }
}
