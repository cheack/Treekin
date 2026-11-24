package app.familygem.geni.sync

import app.familygem.geni.FakeTreeHost
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.RateLimiter
import app.familygem.geni.auth.AuthResult
import app.familygem.geni.auth.InMemoryTokenStore
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.ProfileFields
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.folg.gedcom.model.ChildRef
import org.folg.gedcom.model.Family
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Media
import org.folg.gedcom.model.Person
import org.folg.gedcom.model.SpouseRef
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UploaderTest {

    private val server = MockWebServer()
    private val tokens = InMemoryTokenStore().apply { save(AuthResult.Success("abc", Long.MAX_VALUE)) }
    private var failLinkSave = false
    private var beforeSnapshotWrite: suspend () -> Unit = {}
    private val store = object : InMemorySyncStore() {
        override suspend fun updateSnapshot(treeId: Int, geniId: String, expected: String?, snapshot: String, geniUpdatedAt: String?): Boolean {
            beforeSnapshotWrite()
            return super.updateSnapshot(treeId, geniId, expected, snapshot, geniUpdatedAt)
        }
        override suspend fun saveLinks(links: List<GeniLink>) {
            if (failLinkSave) error("disk full")
            super.saveLinks(links)
        }
    }
    private var editHook: () -> Unit = {}
    private val host = object : FakeTreeHost() {
        override suspend fun <T> edit(treeId: Int, block: (Gedcom) -> T): T {
            editHook()
            return super.edit(treeId, block)
        }
    }

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private val downloads = mutableListOf<String>()
    private var failDownload = false

    private val photoSync = PhotoSync(store, host) { url, target ->
        downloads += url
        if (failDownload) throw java.io.IOException("network")
        target.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3))
        "image/jpeg"
    }

    private fun TestScope.uploader() = Uploader(
        GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime }),
        store, host, now = { 7_000L }, photos = photoSync
    )

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()
    private fun error(code: Int, body: String = "") = MockResponse.Builder().code(code).body(body).build()
    private fun link(geniId: String, gedcomId: String, snapshot: String? = "{}") = GeniLink(1, geniId, gedcomId, true, "1", snapshot, 5, 99)

    /** I1 and I2 (Geni couple, family F1 = union-10106), I3 new child of F1; I5 new parent of I1 in F2; I8 new child of F2. */
    private fun tree() = Gedcom().apply {
        fun person(id: String, geniId: String?) = Person().also { it.id = id; if (geniId != null) GeniMapper.setGeniId(it, geniId); addPerson(it) }
        person("I1", "profile-36430"); person("I2", "profile-36433"); person("I3", null); person("I5", null); person("I8", null)
        addFamily(Family().apply {
            id = "F1"; GeniMapper.setGeniId(this, "union-10106")
            addHusband(SpouseRef().apply { ref = "I1" }); addWife(SpouseRef().apply { ref = "I2" }); addChild(ChildRef().apply { ref = "I3" })
        })
        addFamily(Family().apply {
            id = "F2"
            addHusband(SpouseRef().apply { ref = "I5" }); addChild(ChildRef().apply { ref = "I1" }); addChild(ChildRef().apply { ref = "I8" })
        })
    }.also { host.trees[1] = it }

    private fun job(kind: JobKind, key: String, geniId: String? = null, person: String? = null, family: String? = null, anchors: List<String> = emptyList()) =
        UploadJob(id = 3, treeId = 1, key = key, kind = kind, geniId = geniId, personGedcomId = person, familyGedcomId = family, anchors = anchors,
            params = listOf("first_name" to "New", "is_alive" to "true"), label = "New", localHash = 1)

    /** The job as the queue keeps it, so the uploader finds it unchanged before posting. */
    private suspend fun stored(job: UploadJob) = job.copy(id = store.saveJob(job.copy(id = 0)))

    private val updateProfile = UploadJob(id = 1, treeId = 1, key = "profile:profile-36430", kind = JobKind.UPDATE_PROFILE,
        geniId = "profile-36430", params = listOf("occupation" to "Driver"), label = "Test User", localHash = 1)

    @Test
    fun updateProfilePostsParamsAndSavesSnapshot() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-36430", "I1")))
        server.enqueue(ok("""{"id":"profile-36430"}""")) // Check: Geni unchanged since the snapshot
        server.enqueue(ok("""{"id":"profile-36430","occupation":"Driver"}"""))
        val full = """{"id":"profile-36430","occupation":"Driver","about_me":"Hi","updated_at":"1795000000"}"""
        server.enqueue(ok(full))
        assertEquals(Outcome.Done, uploader().run(stored(updateProfile)))
        assertEquals("GET", server.takeRequest().method)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/api/profile-36430/update", post.url.encodedPath)
        assertEquals("occupation=Driver", post.body!!.utf8())
        assertEquals("profile-36430", server.takeRequest().url.queryParameter("ids"))
        val saved = store.link(1, "profile-36430")!!
        assertEquals(full, saved.snapshot)
        assertEquals("1795000000", saved.geniUpdatedAt)
        assertNull(saved.verifiedHash)
        assertEquals("I1", saved.gedcomId)
        assertTrue(saved.fullyLoaded)
    }

    @Test
    fun updateUnionPostsToUnion() = runTest {
        tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        server.enqueue(ok("""{"id":"union-10106"}""")) // Check
        server.enqueue(ok("""{"id":"union-10106"}"""))
        server.enqueue(ok("""{"id":"union-10106","marriage":{"date":{"year":2012}}}"""))
        val job = updateProfile.copy(key = "union:union-10106", kind = JobKind.UPDATE_UNION, geniId = "union-10106", params = listOf("marriage[date][year]" to "2012"))
        assertEquals(Outcome.Done, uploader().run(stored(job)))
        server.takeRequest()
        assertEquals("/api/union-10106/update", server.takeRequest().url.encodedPath)
        assertTrue(store.link(1, "union-10106")!!.snapshot!!.contains("2012"))
    }

    @Test
    fun addChildToLinkedUnion() = runTest {
        val gedcom = tree()
        store.saveLinks(listOf(link("profile-36430", "I1"), link("union-10106", "F1")))
        server.enqueue(ok("""{"id":"profile-50","unions":["https://api.sandbox.geni.com/union-10106"]}"""))
        server.enqueue(ok("""{"id":"profile-50","first_name":"New","updated_at":"1795000001"}"""))
        server.enqueue(ok("""{"id":"union-10106","children":["profile-50"]}"""))
        val outcome = uploader().run(job(JobKind.ADD_CHILD, "add:I3", person = "I3", family = "F1", anchors = listOf("partner:I1", "partner:I2")))
        assertEquals(Outcome.Done, outcome)
        val post = server.takeRequest()
        assertEquals("/api/union-10106/add-child", post.url.encodedPath)
        assertEquals("first_name=New&is_alive=true", post.body!!.utf8())
        assertEquals("profile-50", GeniMapper.getGeniId(gedcom.people.single { it.id == "I3" }))
        val child = store.link(1, "profile-50")!!
        assertEquals("I3", child.gedcomId)
        assertFalse(child.fullyLoaded)
        assertEquals(0L, child.loadedAtMillis)
        assertTrue(child.snapshot!!.contains("1795000001"))
        assertTrue(store.link(1, "union-10106")!!.snapshot!!.contains("profile-50"))
    }

    @Test
    fun addParentCreatesUnionLink() = runTest {
        val gedcom = tree()
        store.saveLinks(listOf(link("profile-36430", "I1")))
        server.enqueue(ok("""{"id":"profile-51","unions":["https://api.sandbox.geni.com/union-77"]}"""))
        server.enqueue(ok("""{"id":"profile-51"}"""))
        server.enqueue(ok("""{"id":"union-77","partners":["profile-51"],"children":["profile-36430"]}"""))
        assertEquals(Outcome.Done, uploader().run(job(JobKind.ADD_PARTNER, "add:I5", person = "I5", family = "F2", anchors = listOf("child:I1", "child:I8"))))
        assertEquals("/api/profile-36430/add-parent", server.takeRequest().url.encodedPath)
        assertEquals("union-77", GeniMapper.getGeniId(gedcom.families.single { it.id == "F2" }))
        assertEquals("profile-51", GeniMapper.getGeniId(gedcom.people.single { it.id == "I5" }))
        assertEquals("F2", store.link(1, "union-77")!!.gedcomId)
        assertTrue(store.link(1, "union-77")!!.snapshot!!.contains("profile-51"))
    }

    @Test
    fun addPartnerToLinkedPartner() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-36430", "I1")))
        server.enqueue(ok("""{"id":"profile-52","unions":["https://api.sandbox.geni.com/union-78"]}"""))
        server.enqueue(ok("""{"id":"profile-52"}"""))
        server.enqueue(ok("""{"id":"union-78"}"""))
        assertEquals(Outcome.Done, uploader().run(job(JobKind.ADD_PARTNER, "add:I5", person = "I5", family = "F2", anchors = listOf("partner:I1"))))
        assertEquals("/api/profile-36430/add-partner", server.takeRequest().url.encodedPath)
    }

    @Test
    fun childOfNewPersonUsesTheNewUnion() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-36430", "I1"), link("profile-51", "I5"), link("union-77", "F2")))
        server.enqueue(ok("""{"id":"profile-53","unions":["https://api.sandbox.geni.com/union-77"]}"""))
        server.enqueue(ok("""{"id":"profile-53"}"""))
        server.enqueue(ok("""{"id":"union-77"}"""))
        assertEquals(Outcome.Done, uploader().run(job(JobKind.ADD_CHILD, "add:I8", person = "I8", family = "F2", anchors = listOf("partner:I5"))))
        assertEquals("/api/union-77/add-child", server.takeRequest().url.encodedPath)
    }

    @Test
    fun alreadyLinkedPersonIsNotAddedTwice() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-50", "I3"), link("union-10106", "F1")))
        assertEquals(Outcome.Done, uploader().run(job(JobKind.ADD_CHILD, "add:I3", person = "I3", family = "F1", anchors = listOf("partner:I1"))))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun missingAnchorFails() = runTest {
        tree()
        val outcome = uploader().run(job(JobKind.ADD_CHILD, "add:I8", person = "I8", family = "F2", anchors = listOf("partner:I5")))
        assertTrue(outcome is Outcome.Failed)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun http403Fails() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-36430", "I1")))
        server.enqueue(error(403, """{"error":{"type":"ApiException","message":"Access Denied"}}"""))
        assertEquals(Outcome.Failed("Access Denied"), uploader().run(stored(updateProfile)))
        assertEquals("{}", store.link(1, "profile-36430")!!.snapshot)
    }

    @Test
    fun http503Retries() = runTest {
        tree()
        server.enqueue(error(503))
        assertTrue(uploader().run(stored(updateProfile)) is Outcome.Retry)
    }

    @Test
    fun http401NeedsLogin() = runTest {
        tree()
        server.enqueue(error(401))
        assertEquals(Outcome.NeedsLogin, uploader().run(stored(updateProfile)))
    }

    @Test
    fun connectionLostOnUpdateRetries() = runTest {
        tree()
        server.close()
        assertTrue(uploader().run(stored(updateProfile)) is Outcome.Retry)
    }

    @Test
    fun connectionLostOnAddFails() = runTest {
        tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        // Geni got the request; the answer never came
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        val outcome = uploader().run(job(JobKind.ADD_CHILD, "add:I3", person = "I3", family = "F1", anchors = listOf("partner:I1")))
        assertTrue(outcome is Outcome.Failed)
        assertNull(store.linkByGedcomId(1, "I3"))
    }

    @Test
    fun addWhileOfflineRetries() = runTest {
        tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        server.close() // Connection refused: nothing reached Geni
        val outcome = uploader().run(job(JobKind.ADD_CHILD, "add:I3", person = "I3", family = "F1", anchors = listOf("partner:I1")))
        assertTrue(outcome is Outcome.Retry)
        assertNull(store.linkByGedcomId(1, "I3"))
    }

    @Test
    fun addWithUnknownHostRetries() = runTest {
        tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        val offline = Uploader(GeniClient("http://geni.invalid/api/".toHttpUrl(), tokens, RateLimiter { testScheduler.currentTime }), store, host)
        assertTrue(offline.run(job(JobKind.ADD_CHILD, "add:I3", person = "I3", family = "F1", anchors = listOf("partner:I1"))) is Outcome.Retry)
    }

    @Test
    fun snapshotFetchFailureAfterAddStillLinks() = runTest {
        val gedcom = tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        server.enqueue(ok("""{"id":"profile-50","unions":["https://api.sandbox.geni.com/union-10106"]}"""))
        server.enqueue(error(503))
        assertEquals(Outcome.Done, uploader().run(job(JobKind.ADD_CHILD, "add:I3", person = "I3", family = "F1", anchors = listOf("partner:I1"))))
        assertEquals("profile-50", GeniMapper.getGeniId(gedcom.people.single { it.id == "I3" }))
        assertEquals("I3", store.link(1, "profile-50")!!.gedcomId)
    }

    private val addI3 get() = job(JobKind.ADD_CHILD, "add:I3", person = "I3", family = "F1", anchors = listOf("partner:I1", "partner:I2"))

    @Test
    fun add503FailsWithoutRetry() = runTest {
        tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        server.enqueue(error(503))
        val outcome = uploader().run(addI3)
        assertTrue(outcome is Outcome.Failed)
        assertTrue((outcome as Outcome.Failed).message.contains("check on Geni"))
    }

    @Test
    fun addSavesLinkBeforeTag() = runTest {
        tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        var linkedAtEdit: GeniLink? = null
        editHook = { linkedAtEdit = runBlockingLink() }
        server.enqueue(ok("""{"id":"profile-50","unions":["union-10106"]}"""))
        server.enqueue(ok("""{"id":"profile-50"}"""))
        server.enqueue(ok("""{"id":"union-10106"}"""))
        assertEquals(Outcome.Done, uploader().run(addI3))
        assertEquals("profile-50", linkedAtEdit?.geniId)
    }

    private fun runBlockingLink() = kotlinx.coroutines.runBlocking { store.linkByGedcomId(1, "I3") }

    @Test
    fun treeEditFailureAfterAddIsStillDone() = runTest {
        tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        editHook = { error("Tree 1 not readable") }
        server.enqueue(ok("""{"id":"profile-50","unions":["union-10106"]}"""))
        server.enqueue(ok("""{"id":"profile-50"}"""))
        server.enqueue(ok("""{"id":"union-10106"}"""))
        assertEquals(Outcome.Done, uploader().run(addI3))
        assertEquals("I3", store.link(1, "profile-50")!!.gedcomId)
    }

    @Test
    fun nonJsonAddResponseFails() = runTest {
        tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        server.enqueue(ok("<html>oops</html>"))
        val outcome = uploader().run(addI3)
        assertTrue((outcome as Outcome.Failed).message.contains("check on Geni"))
    }

    @Test
    fun updateSnapshotFetchFailureKeepsSentFields() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-36430", "I1")))
        server.enqueue(ok("""{"id":"profile-36430"}""")) // Check
        server.enqueue(ok("""{"id":"profile-36430"}"""))
        server.enqueue(error(503))
        // Geni took the change: done. The old snapshot would make it look like someone else's change later
        assertEquals(Outcome.Done, uploader().run(stored(updateProfile)))
        val link = store.link(1, "profile-36430")!!
        assertEquals("Driver", app.familygem.geni.map.ProfileFields.ofSnapshot(link.snapshot!!)!!.occupation)
    }

    @Test
    fun updateIsNotSentWhenJobChangedDuringCheck() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-36430", "I1")))
        server.enqueue(ok("""{"id":"profile-36430"}"""))
        val job = stored(updateProfile)
        store.saveJob(job.copy(params = listOf("occupation" to "Pilot"), version = 1)) // Saved during the check
        assertEquals(Outcome.Superseded, uploader().run(job))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun childOfTwoParentsWithoutUnionFails() = runTest {
        tree()
        // F2 gets a second partner I2: both parents on Geni, but no union to add the child to
        host.trees.getValue(1).families.single { it.id == "F2" }.addWife(SpouseRef().apply { ref = "I2" })
        store.saveLinks(listOf(link("profile-51", "I5"), link("profile-36433", "I2")))
        val outcome = uploader().run(job(JobKind.ADD_CHILD, "add:I8", person = "I8", family = "F2", anchors = listOf("partner:I5", "partner:I2")))
        assertTrue(outcome is Outcome.Failed)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun linkSaveFailureAfterAddFails() = runTest {
        tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        failLinkSave = true
        server.enqueue(ok("""{"id":"profile-50","unions":["union-10106"]}"""))
        val outcome = uploader().run(addI3)
        assertTrue((outcome as Outcome.Failed).message.contains("profile-50"))
    }

    @Test
    fun snapshotRefreshKeepsOtherLinkFields() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-36430", "I1")))
        server.enqueue(ok("""{"id":"profile-36430"}""")) // Check
        server.enqueue(ok("""{"id":"profile-36430"}"""))
        server.enqueue(ok("""{"id":"profile-36430","occupation":"Driver","updated_at":"9"}"""))
        assertEquals(Outcome.Done, uploader().run(stored(updateProfile)))
        val saved = store.link(1, "profile-36430")!!
        assertEquals(5L, saved.loadedAtMillis)
        assertEquals("9", saved.geniUpdatedAt)
    }

    @Test
    fun addStoresSentFieldsAsSnapshot() = runTest {
        tree()
        store.saveLinks(listOf(link("union-10106", "F1")))
        server.enqueue(ok("""{"id":"profile-50","unions":["union-10106"]}"""))
        server.enqueue(error(503))
        assertEquals(Outcome.Done, uploader().run(job(JobKind.ADD_CHILD, "add:I3", person = "I3", family = "F1", anchors = listOf("partner:I1"))))
        val saved = store.link(1, "profile-50")!!
        // Geni's full version could not be fetched: what was sent is Geni's version for later comparisons
        assertEquals(app.familygem.geni.map.ProfileFields.EMPTY.copy(given = "New"), app.familygem.geni.map.ProfileFields.ofSnapshot(saved.snapshot!!))
        assertNull(saved.verifiedHash)
    }

    @Test
    fun snapshotRefreshNeverOverwritesANewerSnapshot() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-36430", "I1")))
        server.enqueue(ok("""{"id":"profile-36430"}""")) // Check
        server.enqueue(ok("""{"id":"profile-36430"}"""))
        server.enqueue(ok("""{"id":"profile-36430","occupation":"Old"}"""))
        server.enqueue(ok("""{"id":"profile-36430","occupation":"Fresh"}"""))
        var loads = 0
        beforeSnapshotWrite = {
            // A load stores a newer snapshot after the uploader fetched its own
            if (loads++ == 0) store.saveLinks(listOf(store.link(1, "profile-36430")!!.copy(snapshot = """{"id":"profile-36430","occupation":"Loaded"}""")))
        }
        assertEquals(Outcome.Done, uploader().run(stored(updateProfile)))
        assertEquals("""{"id":"profile-36430","occupation":"Fresh"}""", store.link(1, "profile-36430")!!.snapshot)
    }

    @Test
    fun snapshotConflictEveryTimeRetries() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-36430", "I1")))
        server.enqueue(ok("""{"id":"profile-36430"}""")) // Check
        server.enqueue(ok("""{"id":"profile-36430"}"""))
        repeat(3) { server.enqueue(ok("""{"id":"profile-36430","occupation":"Driver"}""")) }
        var loads = 0
        beforeSnapshotWrite = { store.saveLinks(listOf(store.link(1, "profile-36430")!!.copy(snapshot = """{"loaded":${loads++}}"""))) }
        assertTrue(uploader().run(stored(updateProfile)) is Outcome.Retry)
    }

    @Test
    fun updateWithoutParamsOnlyFetchesSnapshot() = runTest {
        tree()
        store.saveLinks(listOf(link("profile-36430", "I1", snapshot = null)))
        server.enqueue(ok("""{"id":"profile-36430","first_name":"Olga"}"""))
        assertEquals(Outcome.Done, uploader().run(stored(updateProfile.copy(params = emptyList()))))
        assertEquals("GET", server.takeRequest().method)
        assertEquals(1, server.requestCount)
        assertEquals("""{"id":"profile-36430","first_name":"Olga"}""", store.link(1, "profile-36430")!!.snapshot)
    }

    // Check of Geni's version before an update

    private fun treeWithOccupation(occupation: String) = tree().also { gedcom ->
        gedcom.people.single { it.id == "I1" }.addEventFact(org.folg.gedcom.model.EventFact().apply { tag = "OCCU"; value = occupation })
    }
    private val engineer = """{"id":"profile-36430","occupation":"Engineer"}"""
    private val driverJob = updateProfile.copy(params = listOf("occupation" to "Driver"))

    @Test
    fun updateSendsWhenGeniUnchanged() = runTest {
        treeWithOccupation("Driver")
        store.saveLinks(listOf(link("profile-36430", "I1", snapshot = engineer)))
        server.enqueue(ok(engineer))
        server.enqueue(ok("""{"id":"profile-36430"}"""))
        server.enqueue(ok("""{"id":"profile-36430","occupation":"Driver"}"""))
        assertEquals(Outcome.Done, uploader().run(stored(driverJob)))
        assertEquals("GET", server.takeRequest().method)
        assertEquals("occupation=Driver", server.takeRequest().body!!.utf8())
    }

    @Test
    fun updateStopsOnConflict() = runTest {
        val gedcom = treeWithOccupation("Driver")
        store.saveLinks(listOf(link("profile-36430", "I1", snapshot = engineer)))
        server.enqueue(ok(engineer.replace("Engineer", "Pilot")))
        assertEquals(Outcome.Conflict(listOf("OCCUPATION")), uploader().run(stored(driverJob)))
        assertEquals(1, server.requestCount) // Nothing sent
        assertEquals(engineer, store.link(1, "profile-36430")!!.snapshot)
        assertEquals("Driver", gedcom.people.single { it.id == "I1" }.eventsFacts.single { it.tag == "OCCU" }.value)
    }

    @Test
    fun updateMergesOtherGeniChangeFirst() = runTest {
        val gedcom = treeWithOccupation("Driver")
        store.saveLinks(listOf(link("profile-36430", "I1", snapshot = engineer)))
        val withBirth = """{"id":"profile-36430","occupation":"Engineer","birth":{"date":{"year":1960}}}"""
        server.enqueue(ok(withBirth))
        server.enqueue(ok("""{"id":"profile-36430"}"""))
        server.enqueue(ok(withBirth.replace("Engineer", "Driver")))
        val uploader = uploader()
        var reported: LocalTree? = null
        uploader.onTreeEdited = { _, local -> reported = local }
        assertEquals(Outcome.Done, uploader.run(stored(driverJob)))
        server.takeRequest()
        assertEquals("occupation=Driver", server.takeRequest().body!!.utf8()) // Only the user's change
        val person = gedcom.people.single { it.id == "I1" }
        assertEquals("1960", person.eventsFacts.single { it.tag == "BIRT" }.date)
        assertEquals("Driver", person.eventsFacts.single { it.tag == "OCCU" }.value)
        assertEquals("1960", reported!!.people.single { it.gedcomId == "I1" }.fields.birth?.date)
    }

    @Test
    fun conflictAgainstAnOutdatedBaseRetries() = runTest {
        val gedcom = treeWithOccupation("Engineer")
        gedcom.people.single { it.id == "I1" }.addEventFact(org.folg.gedcom.model.EventFact().apply { tag = "BIRT"; date = "1961" })
        store.saveLinks(listOf(link("profile-36430", "I1", snapshot = engineer)))
        val geni = """{"id":"profile-36430","occupation":"Engineer","birth":{"date":{"year":1960}}}"""
        server.enqueue(ok(geni))
        // A load stores Geni's version while the uploader compares against the snapshot it read before
        editHook = { kotlinx.coroutines.runBlocking { store.saveLinks(listOf(store.link(1, "profile-36430")!!.copy(snapshot = geni))) } }
        assertTrue(uploader().run(stored(driverJob)) is Outcome.Retry)
    }

    @Test
    fun updateOfDeletedProfileIsConflict() = runTest {
        treeWithOccupation("Driver")
        store.saveLinks(listOf(link("profile-36430", "I1", snapshot = engineer)))
        server.enqueue(ok(engineer.replace("}", ""","deleted":true}""")))
        assertEquals(Outcome.Conflict(listOf("GONE")), uploader().run(stored(driverJob)))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun deleteJobPostsDeleteAndDropsTheLink() = runTest {
        store.saveLinks(listOf(link("profile-7", "I7")))
        host.trees[1] = Gedcom().apply {
            addPerson(Person().also { it.id = "I7"; GeniMapper.setGeniId(it, "profile-7") })
            addPerson(Person().also { it.id = "I8"; GeniMapper.setGeniId(it, "profile-8") })
        }
        server.enqueue(ok("""{"id":"profile-1"}"""))
        server.enqueue(ok("""{"result":"Deleted"}"""))
        assertEquals(Outcome.Done, uploader().run(deleteJob()))
        val identity = server.takeRequest()
        val request = server.takeRequest()
        assertEquals("/api/profile", identity.url.encodedPath)
        assertEquals("POST", request.method)
        assertEquals("/api/profile-7/delete", request.url.encodedPath)
        // The account was read and the delete sent with the same bearer
        assertEquals("Bearer abc", identity.headers["Authorization"])
        assertEquals("Bearer abc", request.headers["Authorization"])
        assertNull(store.link(1, "profile-7"))
        assertEquals(listOf("I8"), host.trees.getValue(1).people.map { it.id }) // The person leaves the tree only now
    }

    @Test
    fun deleteJobRefusedByGeniFailsAndKeepsTheLink() = runTest {
        store.saveLinks(listOf(link("profile-7", "I7")))
        host.trees[1] = Gedcom().apply { addPerson(Person().also { it.id = "I7"; GeniMapper.setGeniId(it, "profile-7") }) }
        server.enqueue(ok("""{"id":"profile-1"}"""))
        server.enqueue(error(403, """{"error":{"message":"Access denied"}}"""))
        assertTrue(uploader().run(deleteJob()) is Outcome.Failed)
        assertTrue(store.link(1, "profile-7") != null)
        assertEquals(1, host.trees.getValue(1).people.size)
    }

    @Test
    fun deleteJobIsRetriedWhenOffline() = runTest {
        server.close()
        assertTrue(uploader().run(deleteJob()) is Outcome.Retry)
    }

    private fun deleteJob(confirmedBy: String? = "profile-1") = UploadJob(treeId = 1, key = "delete:profile-7", kind = JobKind.DELETE_PROFILE,
        geniId = "profile-7", params = emptyList(), label = "Ivan", localHash = 0, confirmedByProfileId = confirmedBy)

    private suspend fun linkedPerson() {
        store.saveLinks(listOf(link("profile-7", "I7")))
        host.trees[1] = Gedcom().apply { addPerson(Person().also { it.id = "I7"; GeniMapper.setGeniId(it, "profile-7") }) }
    }

    private fun assertUntouched() {
        assertEquals(1, host.trees.getValue(1).people.size)
        assertTrue(runBlockingLink("profile-7") != null)
    }

    private fun runBlockingLink(geniId: String) = kotlinx.coroutines.runBlocking { store.link(1, geniId) }

    private fun deleteRequests() = generateSequence { server.takeRequest(0, java.util.concurrent.TimeUnit.MILLISECONDS) }
        .count { it.url.encodedPath == "/api/profile-7/delete" }

    @Test
    fun deleteUnderAnotherAccountFailsWithoutAPost() = runTest {
        linkedPerson()
        server.enqueue(ok("""{"id":"profile-9"}"""))
        val outcome = uploader().run(deleteJob())
        assertTrue(outcome is Outcome.Failed)
        assertTrue((outcome as Outcome.Failed).message.contains("sign in"))
        assertEquals(1, server.requestCount) // Only the identity lookup
        assertEquals(0, deleteRequests())
        assertUntouched()
    }

    @Test
    fun deleteWithoutAConfirmingAccountSendsNothing() = runTest {
        linkedPerson()
        val outcome = uploader().run(deleteJob(confirmedBy = null))
        assertTrue(outcome is Outcome.Failed)
        assertEquals(0, server.requestCount)
        assertUntouched()
    }

    @Test
    fun loginSwitchBeforeTheDeletePostSendsNothing() = runTest {
        linkedPerson()
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                // Account A answers the identity request, then the user signs in as B before the POST is dispatched
                if (request.url.encodedPath == "/api/profile") tokens.save(AuthResult.Success("other", Long.MAX_VALUE))
                return ok("""{"id":"profile-1"}""")
            }
        }
        assertTrue(uploader().run(deleteJob()) is Outcome.Retry)
        assertEquals(1, server.requestCount)
        assertEquals("other", tokens.get()?.token)
        assertUntouched()
    }

    @Test
    fun renewedTokenOfTheSameAccountPassesTheCheck() = runTest {
        linkedPerson()
        tokens.save(AuthResult.Success("renewed", Long.MAX_VALUE))
        server.enqueue(ok("""{"id":"profile-1"}"""))
        server.enqueue(ok("""{"result":"Deleted"}"""))
        assertEquals(Outcome.Done, uploader().run(deleteJob()))
        server.takeRequest()
        assertEquals("Bearer renewed", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun lateUnauthorizedOfAnOldLoginDoesNotClearANewerLogin() = runTest {
        linkedPerson()
        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                tokens.save(AuthResult.Success("newer", Long.MAX_VALUE)) // A new login lands while this request is in flight
                return error(401)
            }
        }
        assertEquals(Outcome.NeedsLogin, uploader().run(deleteJob()))
        assertEquals("newer", tokens.get()?.token)
        assertUntouched()
    }

    @Test
    fun deleteWithoutLoginWaitsForLogin() = runTest {
        linkedPerson()
        tokens.clear()
        assertEquals(Outcome.NeedsLogin, uploader().run(deleteJob()))
        assertEquals(0, server.requestCount)
        assertUntouched()
    }

    private fun deletePhotoJob() = UploadJob(treeId = 1, key = "delphoto:photo-5", kind = JobKind.DELETE_PHOTO, geniId = "photo-5",
        personGedcomId = "I1", params = emptyList(), label = "Me", localHash = 0)

    @Test
    fun deletePhotoPostsDeleteAndIsDone() = runTest {
        server.enqueue(ok("""{"result":"Deleted"}"""))
        assertEquals(Outcome.Done, uploader().run(deletePhotoJob()))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/photo-5/delete", request.url.encodedPath)
    }

    @Test
    fun deletePhotoNotFoundIsDone() = runTest {
        server.enqueue(error(404, """{"error":{"message":"Not found"}}"""))
        assertEquals(Outcome.Done, uploader().run(deletePhotoJob()))
    }

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    private fun mediaOf(photoId: String, main: Boolean) = Media().apply {
        file = "geni-$photoId.jpg"
        setFileTag("FILE")
        GeniMapper.setTag(this, PhotoSync.PHOTO_TAG, photoId)
        if (main) primary = "Y"
    }

    /** I1 (profile-36430) with the main photo photo-5, plus the listed album photos, all downloaded. */
    private suspend fun personWithMainPhoto(vararg album: String): Gedcom {
        store.saveLinks(listOf(link("profile-36430", "I1")))
        val gedcom = tree()
        val person = gedcom.people.single { it.id == "I1" }
        for ((id, main) in listOf("photo-5" to true) + album.map { it to false }) {
            person.addMedia(mediaOf(id, main))
            File(host.mediaDir(1), "geni-$id.jpg").writeBytes(jpeg)
            store.savePhotoLink(PhotoLink(1, "profile-36430", id, null, main, "https://x.test/$id/medium.jpg", "geni-$id.jpg", PhotoState.DOWNLOADED))
        }
        return gedcom
    }

    private fun profileWithPhoto(photoId: String?) = if (photoId == null) """{"id":"profile-36430"}"""
    else """{"id":"profile-36430","mugshot_urls":{"url":"https://api.test/$photoId","medium":"https://x.test/$photoId/medium.jpg"}}"""

    private fun Gedcom.files() = people.single { it.id == "I1" }.media.map { it.file }
    private fun Gedcom.primaryFile() = people.single { it.id == "I1" }.media.singleOrNull { it.primary == "Y" }?.file
    private suspend fun assertMainIntact(gedcom: Gedcom) {
        assertEquals(listOf("geni-photo-5.jpg"), gedcom.files())
        assertEquals("geni-photo-5.jpg", gedcom.primaryFile())
        assertTrue(File(host.mediaDir(1), "geni-photo-5.jpg").isFile)
        assertEquals(listOf("photo-5"), store.photoLinks(1, "profile-36430").map { it.geniPhotoId })
    }

    @Test
    fun deletePhotoRemovesTheMainPhotoFromThePerson() = runTest {
        val gedcom = personWithMainPhoto()
        server.enqueue(ok("""{"result":"Deleted"}"""))
        server.enqueue(ok(profileWithPhoto(null)))
        assertEquals(Outcome.Done, uploader().run(deletePhotoJob()))
        assertEquals("/api/photo-5/delete", server.takeRequest().url.encodedPath)
        assertEquals("profile-36430", server.takeRequest().url.queryParameter("ids"))
        assertTrue(gedcom.files().isEmpty())
        assertTrue(store.photoLinks(1, "profile-36430").isEmpty())
        assertFalse(File(host.mediaDir(1), "geni-photo-5.jpg").exists())
        assertEquals(1, host.refreshes)
    }

    @Test
    fun deletePhotoNotFoundAlsoRemovesTheMainPhoto() = runTest {
        val gedcom = personWithMainPhoto()
        server.enqueue(error(404, """{"error":{"message":"Not found"}}"""))
        server.enqueue(ok(profileWithPhoto(null)))
        assertEquals(Outcome.Done, uploader().run(deletePhotoJob()))
        assertTrue(gedcom.files().isEmpty())
        assertTrue(store.photoLinks(1, "profile-36430").isEmpty())
    }

    @Test
    fun deletePhotoDownloadsTheReplacementMainPhotoAndTellsTheOpenProfile() = runTest {
        val gedcom = personWithMainPhoto()
        server.enqueue(ok("""{"result":"Deleted"}"""))
        server.enqueue(ok(profileWithPhoto("photo-6")))
        val seen = mutableListOf<Unit>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { PersonPhotoChanges.of(1, "I1").collect { seen += it } }
        assertEquals(Outcome.Done, uploader().run(deletePhotoJob()))
        collector.cancel()
        assertEquals(listOf("geni-photo-6.jpg"), gedcom.files())
        assertEquals("geni-photo-6.jpg", gedcom.primaryFile())
        assertEquals(listOf("https://x.test/photo-6/medium.jpg"), downloads)
        assertEquals(listOf("photo-6" to true), store.photoLinks(1, "profile-36430").map { it.geniPhotoId to it.isMain })
        assertEquals(2, host.refreshes)
        assertEquals(2, seen.size)
    }

    @Test
    fun deletePhotoPromotesAReplacementAlreadyInTheAlbum() = runTest {
        val gedcom = personWithMainPhoto("photo-6")
        server.enqueue(ok("""{"result":"Deleted"}"""))
        server.enqueue(ok(profileWithPhoto("photo-6")))
        assertEquals(Outcome.Done, uploader().run(deletePhotoJob()))
        assertEquals(listOf("geni-photo-6.jpg"), gedcom.files())
        assertEquals("geni-photo-6.jpg", gedcom.primaryFile())
        assertTrue(downloads.isEmpty())
        assertEquals(listOf("photo-6" to true), store.photoLinks(1, "profile-36430").map { it.geniPhotoId to it.isMain })
    }

    @Test
    fun deletePhotoNeverReattachesAPhotoGeniStillNames() = runTest {
        val gedcom = personWithMainPhoto()
        server.enqueue(ok("""{"result":"Deleted"}"""))
        server.enqueue(ok(profileWithPhoto("photo-5")))
        assertEquals(Outcome.Done, uploader().run(deletePhotoJob()))
        assertTrue(gedcom.files().isEmpty())
        assertTrue(store.photoLinks(1, "profile-36430").isEmpty())
        assertTrue(downloads.isEmpty())
        assertEquals(1, host.refreshes)
    }

    @Test
    fun deletePhotoStaysDoneWhenTheProfileLookupFails() = runTest {
        val gedcom = personWithMainPhoto()
        server.enqueue(ok("""{"result":"Deleted"}"""))
        server.enqueue(error(500, "{}"))
        assertEquals(Outcome.Done, uploader().run(deletePhotoJob()))
        assertTrue(gedcom.files().isEmpty())
        assertTrue(store.photoLinks(1, "profile-36430").isEmpty())
    }

    @Test
    fun deletePhotoStaysDoneWhenTheReplacementDownloadFails() = runTest {
        val gedcom = personWithMainPhoto()
        failDownload = true
        server.enqueue(ok("""{"result":"Deleted"}"""))
        server.enqueue(ok(profileWithPhoto("photo-6")))
        assertEquals(Outcome.Done, uploader().run(deletePhotoJob()))
        assertTrue(gedcom.files().isEmpty())
        assertTrue(store.photoLinks(1, "profile-36430").isEmpty())
        assertEquals(1, host.refreshes)
    }

    @Test
    fun deletePhotoRemovesAPhotoThatWasDemotedMeanwhileWithoutALookup() = runTest {
        val gedcom = personWithMainPhoto("photo-6")
        // A load made photo-6 the main one while the delete was pending
        val person = gedcom.people.single { it.id == "I1" }
        person.media.forEach { it.primary = if (GeniMapper.getTag(it, PhotoSync.PHOTO_TAG) == "photo-6") "Y" else null }
        store.savePhotoLink(store.photoLinks(1, "profile-36430").single { it.geniPhotoId == "photo-5" }.copy(isMain = false))
        store.savePhotoLink(store.photoLinks(1, "profile-36430").single { it.geniPhotoId == "photo-6" }.copy(isMain = true))
        server.enqueue(ok("""{"result":"Deleted"}"""))
        assertEquals(Outcome.Done, uploader().run(deletePhotoJob()))
        assertEquals(1, server.requestCount)
        assertEquals(listOf("geni-photo-6.jpg"), gedcom.files())
        assertEquals("geni-photo-6.jpg", gedcom.primaryFile())
        assertEquals(listOf("photo-6"), store.photoLinks(1, "profile-36430").map { it.geniPhotoId })
    }

    @Test
    fun deletePhotoKeepsTheUsersNoteAsAPlainLocalPhoto() = runTest {
        val gedcom = personWithMainPhoto()
        gedcom.people.single { it.id == "I1" }.media.single().title = "Grandpa at the dacha"
        server.enqueue(ok("""{"result":"Deleted"}"""))
        server.enqueue(ok(profileWithPhoto(null)))
        assertEquals(Outcome.Done, uploader().run(deletePhotoJob()))
        val media = gedcom.people.single { it.id == "I1" }.media.single()
        assertEquals("Grandpa at the dacha", media.title)
        assertNull(GeniMapper.getTag(media, PhotoSync.PHOTO_TAG))
        assertNull(media.primary)
        assertTrue(File(host.mediaDir(1), "geni-photo-5.jpg").isFile)
        assertTrue(store.photoLinks(1, "profile-36430").isEmpty())
    }

    @Test
    fun fileOfADetachedPhotoSurvivesTheCleanupOfAnotherProfile() = runTest {
        val gedcom = personWithMainPhoto()
        gedcom.people.single { it.id == "I1" }.media.single().title = "Mine"
        server.enqueue(ok("""{"result":"Deleted"}"""))
        server.enqueue(ok(profileWithPhoto(null)))
        assertEquals(Outcome.Done, uploader().run(deletePhotoJob()))
        // Another profile shared the file; its copy goes later through the album cleanup
        store.savePhotoLink(PhotoLink(1, "profile-36433", "photo-5", null, false, null, "geni-photo-5.jpg", PhotoState.DOWNLOADED))
        gedcom.people.single { it.id == "I2" }.addMedia(mediaOf("photo-5", false))
        GeniMapper.setGeniId(gedcom.people.single { it.id == "I2" }, "profile-36433")
        photoSync.reconcileAlbum(1, "profile-36433", "I2", emptyList())
        assertTrue(File(host.mediaDir(1), "geni-photo-5.jpg").isFile)
    }

    @Test
    fun deletePhotoOfAPersonWhoseProfileChangedLeavesThePhoto() = runTest {
        val gedcom = personWithMainPhoto()
        store.saveLinks(listOf(link("profile-99999", "I1"))) // I1 now resolves to another profile
        gedcom.people.single { it.id == "I1" }.let { GeniMapper.setGeniId(it, "profile-99999") }
        server.enqueue(ok("""{"result":"Deleted"}"""))
        assertEquals(Outcome.Done, uploader().run(deletePhotoJob()))
        assertEquals(1, server.requestCount)
        assertEquals(listOf("geni-photo-5.jpg"), gedcom.files())
    }

    @Test
    fun deletePhotoServerErrorRetriesAndKeepsThePhoto() = runTest {
        val gedcom = personWithMainPhoto()
        server.enqueue(error(500, "{}"))
        assertTrue(uploader().run(deletePhotoJob()) is Outcome.Retry)
        assertEquals(1, server.requestCount)
        assertMainIntact(gedcom)
    }

    @Test
    fun deletePhotoClientErrorFailsAndKeepsThePhoto() = runTest {
        val gedcom = personWithMainPhoto()
        server.enqueue(error(403, """{"error":{"message":"Access denied"}}"""))
        assertTrue(uploader().run(deletePhotoJob()) is Outcome.Failed)
        assertEquals(1, server.requestCount)
        assertMainIntact(gedcom)
        assertEquals(0, host.refreshes)
    }

    private val photoJson = """{"id":"photo-5","title":"Me","content_type":"image/jpeg","album_id":"album-1","sizes":{"medium":"https://x.test/p/me.jpg?sig=1"}}"""

    private fun photoJob(file: String = "me.jpg") = UploadJob(id = 3, treeId = 1, key = "photo:I1:$file", kind = JobKind.ADD_PHOTO, geniId = "profile-36430",
        personGedcomId = "I1", params = listOfNotNull("file" to file, "title" to "Me"), label = "Test User", localHash = 1)

    private suspend fun treeWithMedia(file: String = "me.jpg", bytes: ByteArray? = byteArrayOf(1, 2, 3)): Gedcom {
        store.saveLinks(listOf(link("profile-36430", "I1"))) // The owner is linked to the job's profile
        val gedcom = tree()
        gedcom.people.first { it.id == "I1" }.addMedia(Media().apply { this.file = file; setFileTag("FILE") })
        if (bytes != null) java.io.File(host.mediaDir(1), file).writeBytes(bytes)
        return gedcom
    }

    @Test
    fun photoUploadStoresLinkAndTagsMedia() = runTest {
        val gedcom = treeWithMedia()
        server.enqueue(ok(photoJson))
        assertEquals(Outcome.Done, uploader().run(photoJob()))
        val post = server.takeRequest()
        assertEquals("/api/profile-36430/add-photo", post.url.encodedPath)
        val link = store.photoLinks(1, "profile-36430").single()
        assertEquals("photo-5", link.geniPhotoId)
        assertEquals(PhotoState.DOWNLOADED, link.state)
        assertEquals("me.jpg", link.fileName)
        assertFalse(link.isMain)
        assertNull(link.gedcomMediaId)
        assertEquals("https://x.test/p/me.jpg", link.marker)
        assertEquals("photo-5", GeniMapper.getTag(gedcom.people.first { it.id == "I1" }.media.single(), PhotoSync.PHOTO_TAG))
    }

    @Test
    fun photoAlreadyLinkedIsNotUploadedAgain() = runTest {
        treeWithMedia()
        store.savePhotoLink(PhotoLink(1, "profile-36430", "photo-9", null, false, null, "me.jpg", PhotoState.DOWNLOADED))
        assertEquals(Outcome.Done, uploader().run(photoJob()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun missingFileFails() = runTest {
        treeWithMedia(bytes = null)
        assertTrue(uploader().run(photoJob()) is Outcome.Failed)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun fileNameWithPathFails() = runTest {
        treeWithMedia()
        assertTrue(uploader().run(photoJob("../me.jpg")) is Outcome.Failed)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun lostPhotoResponseFailsAndNeverRetriesItself() = runTest {
        treeWithMedia()
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        val outcome = uploader().run(photoJob())
        assertTrue(outcome is Outcome.Failed && outcome.message.contains("check on Geni"))
        assertTrue(store.photoLinks(1).isEmpty())
    }

    @Test
    fun photoNetworkDownBeforeSendRetries() = runTest {
        treeWithMedia()
        val offline = Uploader(GeniClient("http://geni.invalid/api/".toHttpUrl(), tokens, RateLimiter { testScheduler.currentTime }), store, host)
        assertTrue(offline.run(photoJob()) is Outcome.Retry)
    }

    @Test
    fun photo429RetriesAndAuthNeedsLogin() = runTest {
        treeWithMedia()
        server.enqueue(error(429)) // The client asks once more after the rate window
        server.enqueue(error(429))
        assertTrue(uploader().run(photoJob()) is Outcome.Retry)
        server.enqueue(error(401))
        assertEquals(Outcome.NeedsLogin, uploader().run(photoJob()))
    }

    @Test
    fun unreadablePhotoFailsWithoutSending() = runTest {
        treeWithMedia()
        val client = GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime })
        for (error in listOf<Throwable>(java.io.IOException("bad file"), IllegalStateException("boom"), OutOfMemoryError("big"))) {
            val outcome = Uploader(client, store, host, now = { 7_000L }, prepare = { throw error }).run(photoJob())
            assertTrue(outcome is Outcome.Failed)
            assertTrue((outcome as Outcome.Failed).message.startsWith("cannot read the photo: "))
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun prepareIsUsedForBytes() = runTest {
        treeWithMedia()
        server.enqueue(ok(photoJson))
        val client = GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime })
        val prepared = Uploader(client, store, host, now = { 7_000L }, prepare = { byteArrayOf(9) })
        assertEquals(Outcome.Done, prepared.run(photoJob()))
        // base64 of one byte 9 is "CQ=="
        assertTrue(server.takeRequest().body!!.utf8().contains("file=CQ%3D%3D"))
    }

    @Test
    fun photoLinkSaveFailureFails() = runTest {
        treeWithMedia()
        server.enqueue(ok(photoJson))
        val failing = object : InMemorySyncStore() {
            override suspend fun savePhotoLink(link: PhotoLink) = error("disk full")
        }
        failing.saveLinks(listOf(link("profile-36430", "I1")))
        val client = GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime })
        val outcome = Uploader(client, failing, host).run(photoJob())
        assertTrue(outcome is Outcome.Failed && outcome.message.contains("check on Geni"))
    }

    private val ivanSnapshot = """{"id":"profile-36430","first_name":"Ivan","last_name":"User"}"""
    private val photoOf = LocalMedia("me.jpg", "Me", null)

    /** The saved I1 as a lost tag with another name leaves it: the link rules cannot tell if it is the profile. */
    private fun ambiguousOwner() = LocalPerson("I1", null, "New User", ProfileFields.EMPTY.copy(given = "New", surname = "User"), listOf(photoOf))

    private suspend fun linkWithName() = store.saveLinks(listOf(GeniLink(1, "profile-36430", "I1", true, "1", ivanSnapshot, 5, 99)))

    @Test
    fun photoOfAmbiguousOwnerIsDeferredWithoutRequest() = runTest {
        treeWithMedia()
        linkWithName()
        host.saved[1 to "I1"] = ambiguousOwner()
        assertEquals(Outcome.Deferred, uploader().run(photoJob()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun photoWhoseAttachmentWasRemovedIsDeferred() = runTest {
        treeWithMedia()
        host.saved[1 to "I1"] = null
        assertEquals(Outcome.Deferred, uploader().run(photoJob()))
        host.saved[1 to "I1"] = ambiguousOwner().copy(geniId = "profile-36430", media = emptyList())
        assertEquals(Outcome.Deferred, uploader().run(photoJob()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun photoWhoseOwnerNowResolvesToAnotherProfileIsDeferred() = runTest {
        treeWithMedia()
        store.saveLinks(listOf(link("profile-99", "I1")))
        host.saved[1 to "I1"] = ambiguousOwner().copy(geniId = "profile-99")
        assertEquals(Outcome.Deferred, uploader().run(photoJob()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun photoIsDeferredWhenTheSavedTreeCannotBeRead() = runTest {
        treeWithMedia()
        host.savedReadFails = true
        assertEquals(Outcome.Deferred, uploader().run(photoJob()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun savedTreeChangingWhilePreparedStopsTheRequest() = runTest {
        treeWithMedia()
        linkWithName()
        val client = GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime })
        val changing = Uploader(client, store, host, now = { 7_000L }, prepare = {
            host.saved[1 to "I1"] = ambiguousOwner()
            byteArrayOf(1)
        })
        assertEquals(Outcome.Deferred, changing.run(photoJob()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun photoIsDeferredWhenTheLinkLookupFails() = runTest {
        treeWithMedia()
        val broken = object : InMemorySyncStore() {
            override suspend fun link(treeId: Int, geniId: String): GeniLink? = error("database closed")
        }
        val client = GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime })
        assertEquals(Outcome.Deferred, Uploader(client, broken, host).run(photoJob()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun matchingOwnerSendsExactlyOnePhotoPost() = runTest {
        treeWithMedia()
        server.enqueue(ok(photoJson))
        assertEquals(Outcome.Done, uploader().run(photoJob()))
        assertEquals(1, server.requestCount)
        assertEquals("/api/profile-36430/add-photo", server.takeRequest().url.encodedPath)
    }
}
