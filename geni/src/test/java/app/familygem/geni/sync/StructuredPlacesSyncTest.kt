package app.familygem.geni.sync

import app.familygem.geni.FakeTreeHost
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.GeniProfile
import app.familygem.geni.api.RateLimiter
import app.familygem.geni.auth.AuthResult
import app.familygem.geni.auth.InMemoryTokenStore
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.GeniParams
import app.familygem.geni.map.LocationParts
import app.familygem.geni.map.ProfileFields
import com.google.gson.Gson
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.folg.gedcom.model.EventFact
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Person
import org.folg.gedcom.model.SourceCitation
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Structured places through the sync queue: planning, persisted jobs, sending, fallback snapshots and conflicts. */
class StructuredPlacesSyncTest {

    private val server = MockWebServer()
    private val tokens = InMemoryTokenStore().apply { save(AuthResult.Success("abc", Long.MAX_VALUE)) }
    private val store = InMemorySyncStore()
    private val host = FakeTreeHost()

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private val snapshot = """{"id":"profile-1","first_name":"Ivan","is_alive":true,"birth":{"date":{"year":1960},
        "location":{"place_name":"Hospital","city":"Kazan","country":"Russia","street_address1":"Main 1","country_code":"RU"}}}"""

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    private fun person(block: Person.() -> Unit = {}) = Person().apply {
        id = "I1"
        GeniMapper.applyProfile(Gedcom(), this, Gson().fromJson(snapshot, GeniProfile::class.java))
        GeniMapper.setGeniId(this, "profile-1")
        block()
    }

    private fun Person.birth() = eventsFacts.single { it.tag == "BIRT" }

    /** A person as a tree saved by the app before places were structured: the joined text only. */
    private fun legacy() = person {
        birth().apply { place = "Hospital, Kazan, Russia"; setLocationTag(null) }
    }

    private fun EventFact.setLocationTag(value: String?) = GeniMapper.setTag(this, GeniMapper.LOCATION_TAG, value)

    private fun plan(person: Person, jobs: List<UploadJob> = emptyList()): Plan {
        val gedcom = Gedcom().apply { addPerson(person) }
        return UploadPlanner.plan(1, LocalTree.capture(gedcom), listOf(GeniLink(1, "profile-1", "I1", true, null, snapshot, 0)), jobs)
    }

    // Planning

    @Test
    fun unrelatedEditOnALegacyTreeSendsNoLocationKey() {
        val person = legacy().apply { GeniMapper.setTag(this, GeniMapper.ABOUT_TAG, "About me") }
        val params = plan(person).upserts.single().params
        assertEquals(listOf("about_me" to "About me"), params)
    }

    @Test
    fun legacyTreeWithoutChangesPlansNothing() {
        assertEquals(emptyList<UploadJob>(), plan(legacy()).upserts)
    }

    @Test
    fun placeNameEditSendsOnlyThePlaceName() {
        val person = person {
            val fact = birth()
            GeniMapper.setLocation(fact, GeniMapper.getLocation(fact)!!.with("place_name", "Clinic"))
        }
        assertEquals(listOf("birth[location][place_name]" to "Clinic"), plan(person).upserts.single().params)
    }

    @Test
    fun clearedPlaceNameKeepsCityAndCountry() {
        val person = person {
            val fact = birth()
            GeniMapper.setLocation(fact, GeniMapper.getLocation(fact)!!.with("place_name", null))
        }
        assertEquals(listOf("birth[location][place_name]" to ""), plan(person).upserts.single().params)
    }

    @Test
    fun legacyTextEditSendsOnlyThePlaceName() {
        val person = legacy().apply { birth().place = "Clinic" }
        assertEquals(listOf("birth[location][place_name]" to "Clinic"), plan(person).upserts.single().params)
    }

    @Test
    fun aSecondUnchangedSaveMakesNoJob() {
        val person = person {
            val fact = birth()
            GeniMapper.setLocation(fact, GeniMapper.getLocation(fact)!!.with("city", "Omsk"))
        }
        val job = plan(person).upserts.single()
        assertEquals(listOf("birth[location][city]" to "Omsk"), job.params)
        // Geni stored it: the snapshot has the new city, and the same tree plans nothing
        val stored = SnapshotOverlayHelper.apply(snapshot, job.params)
        val gedcom = Gedcom().apply { addPerson(person) }
        val again = UploadPlanner.plan(1, LocalTree.capture(gedcom), listOf(GeniLink(1, "profile-1", "I1", true, null, stored, 0)), listOf(job))
        assertEquals(emptyList<UploadJob>(), again.upserts)
        assertTrue("profile:profile-1" in again.deleteKeys)
    }

    @Test
    fun coordinateZeroAndItsClearArePlanned() {
        val withLatitude = snapshot.replace("\"country_code\":\"RU\"", "\"latitude\":12.5")
        fun planned(change: (LocationParts) -> LocationParts): List<Pair<String, String>> {
            val person = Person().apply {
                id = "I1"
                GeniMapper.applyProfile(Gedcom(), this, Gson().fromJson(withLatitude, GeniProfile::class.java))
                GeniMapper.setGeniId(this, "profile-1")
                GeniMapper.setLocation(birth(), change(GeniMapper.getLocation(birth())!!))
            }
            val gedcom = Gedcom().apply { addPerson(person) }
            return UploadPlanner.plan(1, LocalTree.capture(gedcom), listOf(GeniLink(1, "profile-1", "I1", true, null, withLatitude, 0)), emptyList())
                .upserts.single().params
        }
        assertEquals(listOf("birth[location][latitude]" to "0"), planned { it.with("latitude", "0") })
        assertEquals(listOf("birth[location][latitude]" to ""), planned { it.with("latitude", null) })
    }

    // A job queued before structured places

    private fun legacyJob(format: Int) = UploadJob(
        id = 1, treeId = 1, key = "profile:profile-1", kind = JobKind.UPDATE_PROFILE, geniId = "profile-1", label = "Ivan", localHash = 1,
        params = listOf("birth[location][place_name]" to "Clinic", "birth[location][city]" to "", "birth[location][county]" to "",
            "birth[location][state]" to "", "birth[location][country]" to ""), paramsFormat = format
    )

    @Test
    fun oldJobDoesNotReplayTheImplicitClears() {
        assertEquals(listOf("birth[location][place_name]" to "Clinic"), legacyJob(0).paramsToSend())
    }

    @Test
    fun currentJobSendsExactlyItsParams() {
        val job = legacyJob(UploadJob.CURRENT_PARAMS_FORMAT)
        assertEquals(job.params, job.paramsToSend())
    }

    @Test
    fun oldJobKeepsClearsOfAPlaceThatWasNotRenamed() {
        val job = legacyJob(0).copy(params = listOf("marriage[location][city]" to "", "birth[location][place_name]" to "Clinic"))
        assertEquals(job.params, job.paramsToSend())
    }

    private fun TestScope.uploader() = Uploader(GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime }), store, host)

    private suspend fun storedLegacy(format: Int): UploadJob {
        host.trees[1] = Gedcom().apply { addPerson(person()) }
        store.saveLinks(listOf(GeniLink(1, "profile-1", "I1", true, null, snapshot, 0)))
        return legacyJob(format).let { it.copy(id = store.saveJob(it.copy(id = 0))) }
    }

    @Test
    fun queuedPlaceEditSendsSafelyAfterRestart() = runTest {
        val job = storedLegacy(0)
        server.enqueue(ok(snapshot)) // Check: Geni unchanged
        server.enqueue(ok("""{"id":"profile-1"}"""))
        server.enqueue(ok(snapshot.replace("Hospital", "Clinic")))
        assertEquals(Outcome.Done, uploader().run(job))
        server.takeRequest()
        assertEquals("birth%5Blocation%5D%5Bplace_name%5D=Clinic", server.takeRequest().body!!.utf8())
    }

    @Test
    fun failedRefreshAfterPostKeepsSiblingsAndCausesNoRepeatUpload() = runTest {
        host.trees[1] = Gedcom().apply { addPerson(person { GeniMapper.setLocation(birth(), GeniMapper.getLocation(birth())!!.with("street_address1", null)) }) }
        store.saveLinks(listOf(GeniLink(1, "profile-1", "I1", true, null, snapshot, 0)))
        val queue = SyncQueue(store, uploader())
        queue.onSaved(1, LocalTree.capture(host.trees.getValue(1)))
        val job = store.jobs(1).single()
        assertEquals(listOf("birth[location][street_address1]" to ""), job.params)
        server.enqueue(ok(snapshot)) // Check
        server.enqueue(ok("""{"id":"profile-1"}""")) // The POST
        server.enqueue(MockResponse.Builder().code(503).build()) // The refresh fails: the overlay stands in for Geni's version
        assertEquals(Outcome.Done, uploader().run(job))
        val saved = store.link(1, "profile-1")!!.snapshot!!
        val fields = ProfileFields.ofSnapshot(saved)!!.birth!!.location!!
        assertEquals("Kazan", fields["city"])
        assertEquals("Hospital", fields["place_name"])
        assertEquals(null, fields["street_address1"])
        assertEquals(emptyList<Pair<String, String>>(),
            GeniParams.profile(ProfileFields.ofSnapshot(saved)!!, ProfileFields.of(host.trees.getValue(1).people.single()).copy(givenParts = ProfileFields.ofSnapshot(saved)!!.givenParts)))
    }

    @Test
    fun oldJobIsReplacedByTheCurrentFormatWhenPlannedAgain() = runTest {
        host.trees[1] = Gedcom().apply { addPerson(person { GeniMapper.setLocation(birth(), GeniMapper.getLocation(birth())!!.with("place_name", "Clinic")) }) }
        store.saveLinks(listOf(GeniLink(1, "profile-1", "I1", true, null, snapshot, 0)))
        val queue = SyncQueue(store, uploader())
        // The same params as the new plan wants, but queued by the old version
        val old = UploadJob(treeId = 1, key = "profile:profile-1", kind = JobKind.UPDATE_PROFILE, geniId = "profile-1",
            params = listOf("birth[location][place_name]" to "Clinic"), label = "Ivan", localHash = 1, paramsFormat = 0)
        store.saveJob(old)
        queue.onSaved(1, LocalTree.capture(host.trees.getValue(1)))
        assertEquals(UploadJob.CURRENT_PARAMS_FORMAT, store.jobs(1).single().paramsFormat)
    }

    // Conflicts

    @Test
    fun locationChangeOnBothSidesIsAnEventConflictWithTheDifferingValues() = runTest {
        val local = person {
            birth().addSourceCitation(SourceCitation().apply { value = "Birth record" })
            birth().date = "BET 1959 AND ABT 1961"
            GeniMapper.setLocation(birth(), GeniMapper.getLocation(birth())!!.with("street_address1", "Local 5"))
        }
        val geniJson = snapshot.replace("Main 1", "Geni 9")
        host.trees[1] = Gedcom().apply { addPerson(local) }
        store.saveLinks(listOf(GeniLink(1, "profile-1", "I1", true, null, snapshot, 0)))
        val client = GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime })
        val uploader = Uploader(client, store, host)
        val queue = SyncQueue(store, uploader)
        uploader.onTreeEdited = queue::setLocal
        queue.onSaved(1, LocalTree.capture(host.trees.getValue(1)))
        val job = store.jobs(1).single()
        server.enqueue(ok(geniJson))
        val details = ConflictResolver(client, store, host, queue).details(job)
        assertEquals(listOf("BIRTH"), details.map { it.group })
        assertTrue(details.single().mine, details.single().mine.contains("Local 5"))
        assertTrue(details.single().geni, details.single().geni.contains("Geni 9"))
        // The citation and the approximate range of the local fact are untouched by the location edit
        assertEquals("Birth record", local.birth().sourceCitations.single().value)
        assertEquals("BET 1959 AND ABT 1961", local.birth().date)
    }
}

/** The snapshot as the uploader would store it without a second fetch. */
private object SnapshotOverlayHelper {
    fun apply(base: String, params: List<Pair<String, String>>) = app.familygem.geni.map.SnapshotOverlay.apply(base, params, "profile-1")
}
