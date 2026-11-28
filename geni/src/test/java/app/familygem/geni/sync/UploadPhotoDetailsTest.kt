package app.familygem.geni.sync

import app.familygem.geni.FakeTreeHost
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.RateLimiter
import app.familygem.geni.auth.AuthResult
import app.familygem.geni.auth.InMemoryTokenStore
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.PhotoDetails
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Media
import org.folg.gedcom.model.Person
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

/** `UPDATE_PHOTO`: the edit of a photo's title, description and date on Geni, and what it leaves in the tree. */
class UploadPhotoDetailsTest {

    private val server = MockWebServer().apply { start() }
    private val tokens = InMemoryTokenStore().apply { kotlinx.coroutines.runBlocking { save(AuthResult.Success("abc", Long.MAX_VALUE)) } }
    private val store = InMemorySyncStore()
    private val host = FakeTreeHost()
    private val downloads = mutableListOf<String>()
    private val photoSync = PhotoSync(store, host) { url, _ -> downloads += url; error("no download expected") }
    private val gedcom = Gedcom()
    private val treeId: Int

    init {
        for ((id, geniId) in listOf("I1" to "profile-1", "I2" to "profile-2")) gedcom.addPerson(Person().apply {
            this.id = id
            GeniMapper.setGeniId(this, geniId)
            addMedia(Media().apply {
                file = "geni-photo-5.jpg"
                setFileTag("FILE")
                GeniMapper.setTag(this, PhotoSync.PHOTO_TAG, "photo-5")
                PhotoDetails.apply(this, PhotoDetails("Old", "Old text", "1950"))
            })
        })
        treeId = kotlinx.coroutines.runBlocking { host.createTree("t", gedcom) }
    }

    @After
    fun tearDown() = server.close()

    private fun TestScope.uploader() = Uploader(
        GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime }), store, host, now = { 7_000L }, photos = photoSync
    )

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()
    private fun photoJson(title: String? = null, description: String? = null, date: String? = null) =
        "{\"id\":\"photo-5\"" + (title?.let { ",\"title\":\"$it\"" } ?: "") + (description?.let { ",\"description\":\"$it\"" } ?: "") +
            (date?.let { ",\"date\":$it" } ?: "") + ",\"sizes\":{\"medium\":\"https://p.example/5/m.jpg\"}}"
    private val oldPhoto get() = photoJson("Old", "Old text", """{"year":1950}""")

    private val base = PhotoDetails("Old", "Old text", "1950")

    private suspend fun stored(want: PhotoDetails, from: PhotoDetails = base): UploadJob {
        val job = UploadJob(treeId = treeId, key = "updphoto:photo-5", kind = JobKind.UPDATE_PHOTO, geniId = "photo-5", personGedcomId = "I1",
            params = from.toParams(UploadJob.BASE_PREFIX) + want.toParams(UploadJob.WANT_PREFIX), label = "Photo details: Me", localHash = 0)
        return job.copy(id = store.saveJob(job))
    }

    private fun form(body: String) = body.split('&').filter { it.isNotEmpty() }.associate {
        URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
    }

    private fun media(personId: String) = gedcom.people.single { it.id == personId }.media.single()

    @Test
    fun sendsOnlyTheChangedFieldAndAppliesTheConfirmedValuesToBothProfiles() = runTest {
        val job = stored(PhotoDetails("New title", "Old text", "1950"))
        server.enqueue(ok(oldPhoto))
        server.enqueue(ok(photoJson("New title", "Old text", """{"year":1950}""")))
        assertEquals(Outcome.Done, uploader().run(job))
        assertEquals("GET", server.takeRequest().method)
        val post = server.takeRequest()
        assertEquals("/api/photo-5/update", post.url.encodedPath)
        assertEquals(mapOf("title" to "New title"), form(post.body!!.utf8()))
        for (id in listOf("I1", "I2")) {
            assertEquals(PhotoDetails("New title", "Old text", "1950"), PhotoDetails.shown(media(id)))
            assertEquals("New title", PhotoDetails.syncedTitle(media(id)))
        }
        assertTrue(downloads.isEmpty())
        assertEquals(1, host.refreshes)
    }

    @Test
    fun clearsFieldsWithEmptyValuesAndSendsTheDateParts() = runTest {
        val job = stored(PhotoDetails(null, null, "ABT 3 MAY 1951"))
        server.enqueue(ok(oldPhoto))
        server.enqueue(ok(photoJson(date = """{"day":3,"month":5,"year":1951,"circa":true}""")))
        assertEquals(Outcome.Done, uploader().run(job))
        server.takeRequest()
        assertEquals(
            mapOf("title" to "", "description" to "", "date[day]" to "3", "date[month]" to "5", "date[year]" to "1951", "date[circa]" to "true"),
            form(server.takeRequest().body!!.utf8())
        )
        assertEquals(PhotoDetails(null, null, "ABT 3 MAY 1951"), PhotoDetails.shown(media("I1")))
        assertEquals(PhotoDetails(null, null, "ABT 3 MAY 1951"), PhotoDetails.shown(media("I2")))
    }

    @Test
    fun clearingTheDateSendsEmptyParts() = runTest {
        val job = stored(PhotoDetails("Old", "Old text", null))
        server.enqueue(ok(oldPhoto))
        server.enqueue(ok(photoJson("Old", "Old text")))
        assertEquals(Outcome.Done, uploader().run(job))
        server.takeRequest()
        assertEquals(mapOf("date[day]" to "", "date[month]" to "", "date[year]" to "", "date[circa]" to "false"), form(server.takeRequest().body!!.utf8()))
        assertEquals(null, PhotoDetails.shown(media("I1")).date)
    }

    @Test
    fun aLostResponseIsNotSentAgain() = runTest {
        // The first attempt reached Geni but its answer was lost: Geni already has the wanted values
        val job = stored(PhotoDetails("New title", "Old text", "1950"))
        server.enqueue(ok(photoJson("New title", "Old text", """{"year":1950}""")))
        assertEquals(Outcome.Done, uploader().run(job))
        assertEquals(1, server.requestCount)
        assertEquals("New title", media("I2").title)
    }

    @Test
    fun aFieldChangedOnGeniMeanwhileFailsKeepingTheDraftAndARetryOverwritesIt() = runTest {
        val job = stored(PhotoDetails("My title", "My text", "1950"))
        server.enqueue(ok(photoJson("Their title", "Old text", """{"year":1950}""")))
        val outcome = uploader().run(job)
        assertTrue(outcome is Outcome.Failed)
        assertTrue((outcome as Outcome.Failed).message.contains("title"))
        assertEquals(1, server.requestCount) // nothing was sent
        val kept = store.jobs(treeId).single()
        assertEquals(PhotoDetails("My title", "My text", "1950"), PhotoDetails.fromParams(kept.params, UploadJob.WANT_PREFIX))
        assertEquals("Their title", PhotoDetails.fromParams(kept.params, UploadJob.BASE_PREFIX).title)
        assertEquals("Old", media("I1").title) // the tree is not changed by a failed job
        // The retry from the backlog: Geni still has their title, now the starting point, so the draft goes over it
        server.enqueue(ok(photoJson("Their title", "Old text", """{"year":1950}""")))
        server.enqueue(ok(photoJson("My title", "My text", """{"year":1950}""")))
        assertEquals(Outcome.Done, uploader().run(kept))
        server.takeRequest(); server.takeRequest()
        assertEquals(mapOf("title" to "My title", "description" to "My text"), form(server.takeRequest().body!!.utf8()).filterKeys { !it.startsWith("date") })
    }

    @Test
    fun geniRejectingTheEditFailsAndKeepsTheJob() = runTest {
        val job = stored(PhotoDetails("New title", "Old text", "1950"))
        server.enqueue(ok(oldPhoto))
        server.enqueue(MockResponse.Builder().code(403).body("""{"error":{"message":"Access denied"}}""").build())
        val outcome = uploader().run(job)
        assertTrue(outcome is Outcome.Failed)
        assertTrue((outcome as Outcome.Failed).message.contains("Access denied"))
        assertEquals(1, store.jobs(treeId).size)
        assertEquals("Old", media("I1").title)
    }

    @Test
    fun aPhotoGoneFromGeniFailsInsteadOfLooping() = runTest {
        val job = stored(PhotoDetails("New title", "Old text", "1950"))
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":{"message":"Not found"}}""").build())
        assertTrue(uploader().run(job) is Outcome.Failed)
    }

    @Test
    fun aServerErrorIsRetriedLater() = runTest {
        val job = stored(PhotoDetails("New title", "Old text", "1950"))
        server.enqueue(MockResponse.Builder().code(500).build())
        assertTrue(uploader().run(job) is Outcome.Retry)
    }

    @Test
    fun aJobReplacedBySavingMeanwhileSendsNothing() = runTest {
        val job = stored(PhotoDetails("New title", "Old text", "1950"))
        store.saveJob(job.copy(version = job.version + 1))
        server.enqueue(ok(oldPhoto))
        assertEquals(Outcome.Superseded, uploader().run(job))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aJobWantingWhatItStartedFromSendsNothing() = runTest {
        assertEquals(Outcome.Done, uploader().run(stored(base)))
        assertEquals(0, server.requestCount)
    }
}
