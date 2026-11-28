package app.familygem.geni.sync

import app.familygem.geni.FakeTreeHost
import app.familygem.geni.Fixtures
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.RateLimiter
import app.familygem.geni.auth.AuthResult
import app.familygem.geni.auth.InMemoryTokenStore
import app.familygem.geni.map.GeniMapper
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Name
import org.folg.gedcom.model.Note
import org.folg.gedcom.model.Person
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Survivor profile-7 (person I1), absorbed profile-9 (person I2), both cached; Geni answers are set per test. */
class ProfileMergerTest {

    private val server = MockWebServer()
    private val tokens = InMemoryTokenStore().apply { save(AuthResult.Success("abc", Long.MAX_VALUE)) }
    private val store = InMemorySyncStore()
    private val host = FakeTreeHost()

    /** What the batch read answers for profile-9: merged into profile-7 by default. */
    private var absorbedAnswer = """{"id":"profile-9","first_name":"Dup","deleted":true,"merged_into":"https://api.sandbox.geni.com/profile-7"}"""
    private var survivorAnswer = """{"id":"profile-7","first_name":"Kept","is_alive":true}"""
    private var mergePostCode = 200
    private var familyCode = 200
    private val posts = mutableListOf<String>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val ids = request.url.queryParameter("ids").orEmpty()
                if (request.method == "POST") posts += path
                return when {
                    path == "/api/profile" && ids.isEmpty() -> json("""{"id":"profile-1","name":"Me"}""")
                    path == "/api/profile-7/merge/profile-9" -> json(Fixtures.read("profile-merge-response.json"), mergePostCode)
                    path == "/api/profile-9/immediate-family" -> json("""{"focus":{"id":"profile-9"},"nodes":{"profile-9":{"id":"profile-9"}}}""", familyCode)
                    path == "/api/profile-7/immediate-family" -> json("""{"focus":{"id":"profile-7"},"nodes":{"profile-7":{"id":"profile-7"}}}""", familyCode)
                    path == "/api/profile" && ids == "profile-9" -> json("""{"results":[$absorbedAnswer]}""".let { if (absorbedAnswer.isEmpty()) """{"results":[]}""" else it })
                    path == "/api/profile" && ids == "profile-7" -> json(survivorAnswer)
                    path == "/api/profile" && ids == "profile-9,profile-7" -> json("""{"results":[${listOf(absorbedAnswer, survivorAnswer).filter { it.isNotEmpty() }.joinToString(",")}]}""")
                    else -> json("""{"results":[]}""")
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private fun json(body: String, code: Int = 200) = MockResponse.Builder().code(code).body(body).build()

    private fun TestScope.merger(): ProfileMerger {
        val client = GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime })
        return ProfileMerger(client, store, GeniLoader(client, store, host, now = { 5_000L }))
    }

    private suspend fun tree(root: String = "profile-7"): Int {
        val id = host.createTree("Geni", Gedcom())
        store.saveTree(GeniTreeInfo(id, root, ownerGeniId = "profile-1"))
        host.roots[id] = if (root == "profile-7") "I1" else "I2"
        val gedcom = host.trees.getValue(id)
        listOf("I1" to "profile-7", "I2" to "profile-9").forEach { (gedcomId, geniId) ->
            gedcom.addPerson(Person().apply {
                this.id = gedcomId
                addName(Name().apply { value = if (geniId == "profile-7") "Kept" else "Dup" })
                GeniMapper.setGeniId(this, geniId)
            })
            store.saveLinks(listOf(GeniLink(id, geniId, gedcomId, true, null, null, 0)))
        }
        gedcom.createIndexes()
        return id
    }

    @Test
    fun completedMergeSendsOnePostAndRemovesTheAbsorbedCopy() = runTest {
        val treeId = tree()
        assertEquals(MergeResult.Merged, merger().merge(treeId, "profile-7", "profile-9", "profile-1"))
        assertEquals(listOf("/api/profile-7/merge/profile-9"), posts)
        assertEquals(listOf("I1"), host.trees.getValue(treeId).people.map { it.id })
        assertNull(store.link(treeId, "profile-9"))
        assertNotNull(store.link(treeId, "profile-7"))
    }

    @Test
    fun absorbedRootMovesToTheSurvivorAndTheOwnerStays() = runTest {
        val treeId = tree(root = "profile-9")
        assertEquals(MergeResult.Merged, merger().merge(treeId, "profile-7", "profile-9", "profile-1"))
        assertEquals(GeniTreeInfo(treeId, "profile-7", ownerGeniId = "profile-1"), store.tree(treeId))
        assertEquals("I1", host.roots[treeId])
    }

    @Test
    fun localNoteOnTheAbsorbedPersonKeepsItAndItsLink() = runTest {
        val treeId = tree()
        host.trees.getValue(treeId).people.first { it.id == "I2" }.addNote(Note().apply { value = "Mine" })
        assertEquals(MergeResult.Merged, merger().merge(treeId, "profile-7", "profile-9", "profile-1"))
        assertEquals(listOf("I1", "I2"), host.trees.getValue(treeId).people.map { it.id })
        assertNotNull(store.link(treeId, "profile-9"))
    }

    @Test
    fun stillAliveAbsorbedProfileIsNotCompletedAndNothingChanges() = runTest {
        absorbedAnswer = """{"id":"profile-9","first_name":"Dup","deleted":false,"merge_pending":true}"""
        val treeId = tree()
        assertEquals(MergeResult.NotCompleted, merger().merge(treeId, "profile-7", "profile-9", "profile-1"))
        assertEquals(2, host.trees.getValue(treeId).people.size)
        assertNotNull(store.link(treeId, "profile-9"))
    }

    @Test
    fun requestListedBySurvivorIsRequestedAndNothingChanges() = runTest {
        absorbedAnswer = """{"id":"profile-9","first_name":"Dup","deleted":false,"merge_pending":true}"""
        survivorAnswer = """{"id":"profile-7","first_name":"Kept","requested_merges":["https://api.sandbox.geni.com/profile-9"],"merge_pending":true}"""
        val treeId = tree()
        assertEquals(MergeResult.Requested, merger().merge(treeId, "profile-7", "profile-9", "profile-1"))
        assertEquals(1, posts.size)
        assertEquals(2, host.trees.getValue(treeId).people.size)
    }

    @Test
    fun requestForAnotherProfileIsNotThisPair() = runTest {
        absorbedAnswer = """{"id":"profile-9","first_name":"Dup","deleted":false}"""
        survivorAnswer = """{"id":"profile-7","first_name":"Kept","requested_merges":["https://api.sandbox.geni.com/profile-8"]}"""
        val treeId = tree()
        assertEquals(MergeResult.NotCompleted, merger().merge(treeId, "profile-7", "profile-9", "profile-1"))
    }

    @Test
    fun lostAnswerIsReadBackWithoutASecondPost() = runTest {
        mergePostCode = 502
        val treeId = tree()
        assertEquals(MergeResult.Merged, merger().merge(treeId, "profile-7", "profile-9", "profile-1"))
        assertEquals(1, posts.size)
    }

    @Test
    fun lostAnswerWithAnUnreadableAbsorbedProfileIsUnknown() = runTest {
        mergePostCode = 502
        absorbedAnswer = ""
        val treeId = tree()
        assertEquals(MergeResult.Unknown, merger().merge(treeId, "profile-7", "profile-9", "profile-1"))
        assertEquals(1, posts.size)
        assertEquals(2, host.trees.getValue(treeId).people.size)
    }

    @Test
    fun refusedMergeIsRejectedAndNeverReadBack() = runTest {
        mergePostCode = 403
        val treeId = tree()
        assertTrue(merger().merge(treeId, "profile-7", "profile-9", "profile-1") is MergeResult.Rejected)
        assertEquals(2, host.trees.getValue(treeId).people.size)
    }

    @Test
    fun failedRefreshAfterAConfirmedMergeIsRetriedWithReadsOnly() = runTest {
        val treeId = tree()
        familyCode = 500
        assertEquals(MergeResult.RefreshNeeded, merger().merge(treeId, "profile-7", "profile-9", "profile-1"))
        assertEquals(2, host.trees.getValue(treeId).people.size)
        familyCode = 200
        assertEquals(MergeResult.Merged, merger().reconcile(treeId, "profile-7", "profile-9"))
        assertEquals(1, posts.size)
        assertEquals(listOf("I1"), host.trees.getValue(treeId).people.map { it.id })
    }

    @Test
    fun anotherAccountOrAnUnlinkedProfileSendsNoPost() = runTest {
        val treeId = tree()
        assertEquals(MergeResult.AccountChanged, merger().merge(treeId, "profile-7", "profile-9", "profile-2"))
        assertEquals(MergeResult.NotLinked, merger().merge(treeId, "profile-7", "profile-5", "profile-1"))
        assertEquals(MergeResult.NotLinked, merger().merge(treeId, "profile-7", "profile-7", "profile-1"))
        assertEquals(emptyList<String>(), posts)
    }
}
