package app.familygem.geni.sync

import app.familygem.geni.FakeTreeHost
import app.familygem.geni.Fixtures
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.RateLimiter
import app.familygem.geni.api.rawResults
import app.familygem.geni.auth.AuthResult
import app.familygem.geni.auth.InMemoryTokenStore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.folg.gedcom.model.Gedcom
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import app.familygem.geni.TreeLimitException

class GeniLoaderTest {

    private val server = MockWebServer()
    private val tokens = InMemoryTokenStore().apply { save(AuthResult.Success("abc", Long.MAX_VALUE)) }
    private val store = InMemorySyncStore()
    private val host = FakeTreeHost()
    private var batchCode = 200
    private var familyCode = 200

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    path == "/api/profile-36430/immediate-family" -> json(Fixtures.read("immediate-family-root.json"), familyCode)
                    path == "/api/profile" && request.url.queryParameter("ids") != null -> json(Fixtures.read("profiles-batch.json"), batchCode)
                    path == "/api/profile" -> json(rawResults(Fixtures.read("profiles-batch.json")).first(), 200)
                    path == "/api/union" -> json(Fixtures.read("unions-batch.json"), batchCode)
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() = server.close()

    private suspend inline fun <reified T : Throwable> failsWith(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return
            throw e
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    private fun json(body: String, code: Int) = MockResponse.Builder().code(code).body(body).build()

    private fun TestScope.loader() = GeniLoader(
        GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime }),
        store, host, now = { 5_000L }
    )

    private suspend fun geniTree(): Int {
        val id = host.createTree("Geni", Gedcom())
        store.saveTree(GeniTreeInfo(id, "profile-36430"))
        return id
    }

    @Test
    fun loadFetchesThreeRequestsAndMerges() = runTest {
        val treeId = geniTree()
        assertEquals(LoadResult.Loaded(newPeople = 8, profileId = "profile-36430"), loader().load(treeId, "profile-36430"))
        assertEquals(3, server.requestCount)
        assertEquals(8, host.trees.getValue(treeId).people.size)
        assertEquals(10, store.links(treeId).size)
        assertEquals(1, host.refreshes)
        val ids = server.takeRequest().url.encodedPath to server.takeRequest().url.queryParameter("ids")
        assertEquals("/api/profile-36430/immediate-family", ids.first)
        assertTrue(ids.second!!.split(',').containsAll(listOf("profile-36430", "profile-36431")))
    }

    private fun TestScope.photoLoader(errors: MutableList<String>, download: suspend (String, java.io.File) -> String) = GeniLoader(
        GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime }),
        store, host, now = { 5_000L }, photos = PhotoSync(store, host, download = download), onPhotoError = { id, _ -> errors += id }
    )

    @Test
    fun loadDownloadsMainPhotoOfProfilesThatHaveOne() = runTest {
        val treeId = geniTree()
        val urls = mutableListOf<String>()
        val result = photoLoader(mutableListOf()) { url, target ->
            urls += url
            target.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
            "image/jpeg"
        }.load(treeId, "profile-36430")
        assertTrue(result is LoadResult.Loaded)
        assertEquals(1, urls.size)
        assertEquals(2, host.refreshes) // Once before the photos, once after
        val link = store.photoLinks(treeId).single()
        assertEquals("profile-36430", link.geniProfileId)
        assertEquals(1, host.trees.getValue(treeId).people.count { it.media.orEmpty().isNotEmpty() })
    }

    @Test
    fun photoFailureDoesNotFailTheLoad() = runTest {
        val treeId = geniTree()
        val errors = mutableListOf<String>()
        val result = photoLoader(errors) { _, _ -> throw java.io.IOException("cdn down") }.load(treeId, "profile-36430")
        assertTrue(result is LoadResult.Loaded)
        assertEquals(listOf("profile-36430"), errors)
        assertEquals(8, host.trees.getValue(treeId).people.size)
        assertTrue(store.photoLinks(treeId).isEmpty())
    }

    @Test
    fun networkErrorLeavesTreeUnchanged() = runTest {
        val treeId = geniTree()
        batchCode = 500
        val result = loader().load(treeId, "profile-36430")
        assertTrue(result is LoadResult.Failed)
        assertEquals(0, host.trees.getValue(treeId).people.orEmpty().size)
        assertEquals(0, store.links(treeId).size)
        assertEquals(0, host.refreshes)
    }

    @Test
    fun authErrorReturnsNeedsLogin() = runTest {
        val treeId = geniTree()
        familyCode = 401
        assertEquals(LoadResult.NeedsLogin, loader().load(treeId, "profile-36430"))
    }

    @Test
    fun notAGeniTree() = runTest {
        val treeId = host.createTree("Local", Gedcom())
        assertEquals(LoadResult.NotAGeniTree, loader().load(treeId, "profile-36430"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun createTreeStoresInfo() = runTest {
        val treeId = loader().createTree()
        assertEquals("profile-36430", store.tree(treeId)?.rootGeniId)
        assertEquals("Test User", host.titles[treeId])
        assertEquals(8, host.trees.getValue(treeId).people.size)
        assertEquals(store.link(treeId, "profile-36430")?.gedcomId, host.roots[treeId])
        assertEquals(listOf(Triple(treeId, "profile-36430", 3)), host.prefetches)
    }

    @Test
    fun createTreeFromGivenRootIsStillOwnedByTheAccount() = runTest {
        val treeId = loader().createTree(rootGeniId = "profile-36430")
        assertEquals("profile-36430", store.tree(treeId)?.rootGeniId)
        assertEquals("Test User", host.titles[treeId])
        val requests = generateSequence { server.takeRequest(0, java.util.concurrent.TimeUnit.MILLISECONDS) }.map { it.url }.toList()
        assertEquals("profile-36430", store.tree(treeId)?.ownerGeniId)
        // "profile" without ids is the logged-in user's profile: asked once, for the owner
        assertEquals(1, requests.count { it.encodedPath == "/api/profile" && it.queryParameter("ids") == null })
    }

    @Test
    fun ownershipOfTheAccountIsFound() = runTest {
        assertEquals(TreeOwnership.None, loader().ownership("profile-36430"))
        val treeId = loader().createTree()
        assertEquals(TreeOwnership.Own(treeId, complete = true), loader().ownership("profile-36430"))
        assertEquals(TreeOwnership.Other, loader().ownership("profile-other"))
        host.trees.remove(treeId) // Deleted in FamilyGem
        assertEquals(TreeOwnership.None, loader().ownership("profile-36430"))
        assertEquals(TreeOwnership.None, loader().ownership("profile-other")) // A deleted tree blocks nobody
    }

    @Test
    fun firstLoginCreatesOneTreeOwnedByTheAccount() = runTest {
        host.oneTree = true
        val treeId = loader().ensureTree()
        assertEquals("profile-36430", store.tree(treeId)?.ownerGeniId)
        assertEquals(1, host.trees.size)
        assertEquals(8, host.trees.getValue(treeId).people.size)
        assertEquals(store.link(treeId, "profile-36430")?.gedcomId, host.roots[treeId])
    }

    @Test
    fun sameAccountReopensTheTreeWithoutAnotherOne() = runTest {
        host.oneTree = true
        val first = loader().ensureTree()
        val prefetches = host.prefetches.size
        assertEquals(first, loader().ensureTree())
        assertEquals(1, host.trees.size)
        assertEquals(prefetches, host.prefetches.size) // A complete tree is not set up again
    }

    @Test
    fun otherAccountCannotTakeTheTree() = runTest {
        host.oneTree = true
        val treeId = host.createTree("Theirs", Gedcom())
        store.saveTree(GeniTreeInfo(treeId, "profile-1", ownerGeniId = "profile-1"))
        failsWith<AccountMismatchException> { loader().ensureTree() } // The signed-in profile is profile-36430
        assertEquals(setOf(treeId), host.trees.keys)
        assertEquals(1, store.trees.size)
    }

    @Test
    fun hostRefusesASecondTree() = runTest {
        host.oneTree = true
        loader().createTree()
        failsWith<TreeLimitException> { loader().createTree() }
        assertEquals(1, host.trees.size)
        assertEquals(1, store.trees.size)
    }

    @Test
    fun ownerSurvivesARootRemap() = runTest {
        val treeId = loader().createTree()
        // Geni merged the root profile into another one: the root moves, the owner does not
        store.saveLinks(listOf(GeniLink(treeId, "profile-99", "I99", true, null, null, 1L)))
        assertTrue(store.updateRoot(treeId, "profile-36430", "profile-99"))
        assertEquals("profile-36430", store.tree(treeId)?.ownerGeniId)
        assertEquals(TreeOwnership.Own(treeId, complete = true), loader().ownership("profile-36430"))
        assertEquals(TreeOwnership.Other, loader().ownership("profile-99"))
    }

    @Test
    fun retryFinishesASetupThatFailedLoading() = runTest {
        host.oneTree = true
        batchCode = 500 // Geni fails while the root's family is loaded
        failsWith<Exception> { loader().ensureTree() }
        val treeId = host.trees.keys.single() // The tree stays, owned, but without its root person
        assertEquals(TreeOwnership.Own(treeId, complete = false), loader().ownership("profile-36430"))
        batchCode = 200
        assertEquals(treeId, loader().ensureTree()) // Finished, not duplicated
        assertEquals(1, host.trees.size)
        assertEquals(8, host.trees.getValue(treeId).people.size)
        assertEquals(store.link(treeId, "profile-36430")?.gedcomId, host.roots[treeId])
        assertEquals(1, host.prefetches.size)
    }

    @Test
    fun retryFinishesASetupInterruptedAfterTheRootLinkWasSaved() = runTest {
        host.oneTree = true
        val treeId = loader().ensureTree()
        host.roots.remove(treeId) // The process ended after the link was saved, before the root was set
        assertEquals(TreeOwnership.Own(treeId, complete = false), loader().ownership("profile-36430"))
        assertEquals(treeId, loader().ensureTree())
        assertEquals(store.link(treeId, "profile-36430")?.gedcomId, host.roots[treeId])
        assertEquals(2, host.prefetches.size) // The prefetch is planned again, it does not survive the process
        assertEquals(TreeOwnership.Own(treeId, complete = true), loader().ownership("profile-36430"))
    }

    @Test
    fun failureWhileRegisteringReleasesTheReservation() = runTest {
        host.oneTree = true
        var failures = 1
        val flaky = object : InMemorySyncStore() {
            override suspend fun saveTree(info: GeniTreeInfo) {
                if (failures-- > 0) throw java.io.IOException("disk full")
                super.saveTree(info)
            }
        }
        val loader = GeniLoader(GeniClient(server.url("/api/"), tokens, RateLimiter { 0L }), flaky, host, now = { 5_000L })
        failsWith<java.io.IOException> { loader.ensureTree() }
        assertTrue(host.trees.isEmpty()) // Nothing left that would block the retry
        val treeId = loader.ensureTree()
        assertEquals(setOf(treeId), host.trees.keys)
    }

    @Test
    fun createTreeDropsRowsOfDeletedTreeWithSameId() = runTest {
        store.saveTree(GeniTreeInfo(1, "profile-old"))
        store.saveLinks(listOf(GeniLink(1, "profile-99999", "I99", true, null, null, 4_000L)))
        val treeId = loader().createTree() // The host gives ID 1 again: tree 1 was deleted
        assertEquals(1, treeId)
        assertEquals("profile-36430", store.tree(1)?.rootGeniId)
        assertTrue(store.links(1).none { it.gedcomId == "I99" })
    }

    @Test
    fun deletedTreeIsNotLoaded() = runTest {
        val treeId = geniTree()
        host.trees.remove(treeId)
        assertEquals(LoadResult.NotAGeniTree, loader().load(treeId, "profile-36430"))
        assertEquals(0, server.requestCount)
    }

    // Profiles deleted or merged on Geni

    private val ninth = """{"id":"profile-9","first_name":"Gone","is_alive":true}"""

    /** A Geni tree with one cached person, profile-9, and Geni answering for it with [answer]. */
    private suspend fun goneTree(answer: String, survivor: String? = null): Int {
        val treeId = geniTree()
        val person = org.folg.gedcom.model.Person().apply {
            id = "I1"
            app.familygem.geni.map.GeniMapper.applyProfile(Gedcom(), this, com.google.gson.Gson().fromJson(ninth, app.familygem.geni.api.GeniProfile::class.java))
            app.familygem.geni.map.GeniMapper.setGeniId(this, "profile-9")
        }
        host.trees.getValue(treeId).addPerson(person)
        store.saveLinks(listOf(GeniLink(treeId, "profile-9", "I1", true, null, ninth, 0)))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val ids = request.url.queryParameter("ids").orEmpty()
                return when {
                    path == "/api/profile-9/immediate-family" -> json("""{"focus":{"id":"profile-9"},"nodes":{"profile-9":{"id":"profile-9"}}}""", 200)
                    path == "/api/profile" && ids == "profile-9" -> json(answer, 200)
                    survivor != null && path == "/api/$survivor/immediate-family" ->
                        json("""{"focus":{"id":"$survivor"},"nodes":{"$survivor":{"id":"$survivor"}}}""", 200)
                    survivor != null && path == "/api/profile" && ids == survivor ->
                        json("""{"id":"$survivor","first_name":"Kept","is_alive":true}""", 200)
                    else -> json("""{"results":[]}""", 200)
                }
            }
        }
        return treeId
    }

    @Test
    fun deletedUnchangedPersonIsRemoved() = runTest {
        val treeId = goneTree(ninth.replace("}", ""","deleted":true}"""))
        assertEquals(LoadResult.Gone(removed = true), loader().load(treeId, "profile-9"))
        assertEquals(0, host.trees.getValue(treeId).people.orEmpty().size)
        assertEquals(null, store.link(treeId, "profile-9"))
    }

    @Test
    fun deletedEditedPersonStays() = runTest {
        val treeId = goneTree(ninth.replace("}", ""","deleted":true}"""))
        host.trees.getValue(treeId).people.single().addNote(org.folg.gedcom.model.Note().apply { value = "Mine" })
        assertEquals(LoadResult.Gone(removed = false), loader().load(treeId, "profile-9"))
        assertEquals(1, host.trees.getValue(treeId).people.size)
    }

    @Test
    fun mergedPersonMovesToTheSurvivor() = runTest {
        val treeId = goneTree(ninth.replace("}", ""","deleted":true,"merged_into":"https://api.sandbox.geni.com/profile-7"}"""), "profile-7")
        assertTrue(loader().load(treeId, "profile-9") is LoadResult.Loaded)
        val person = host.trees.getValue(treeId).people.single()
        assertEquals("profile-7", app.familygem.geni.map.GeniMapper.getGeniId(person))
        assertEquals("Kept", person.names.single().value)
        assertEquals(null, store.link(treeId, "profile-9"))
        assertEquals("I1", store.link(treeId, "profile-7")!!.gedcomId)
    }

    @Test
    fun mergedRootMovesTheTreeRoot() = runTest {
        val treeId = goneTree(ninth.replace("}", ""","deleted":true,"merged_into":"https://api.sandbox.geni.com/profile-7"}"""), "profile-7")
        store.saveTree(GeniTreeInfo(treeId, "profile-9", prefetchDepth = 2))
        assertEquals(LoadResult.Loaded(newPeople = 0, profileId = "profile-7"), loader().load(treeId, "profile-9"))
        assertEquals(GeniTreeInfo(treeId, "profile-7", prefetchDepth = 2, ownerGeniId = "profile-9"), store.tree(treeId)) // The owner stays
    }

    @Test
    fun mergedRootDoesNotRecreateADeletedTreeRow() = runTest {
        val treeId = goneTree(ninth.replace("}", ""","deleted":true,"merged_into":"https://api.sandbox.geni.com/profile-7"}"""), "profile-7")
        store.trees.remove(treeId)
        assertTrue(store.updateRoot(treeId, "profile-9", "profile-7").not())
        assertEquals(null, store.tree(treeId))
    }
}
