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
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Media
import org.folg.gedcom.model.Person
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** TAG_PHOTO, UNTAG_PHOTO and SET_MAIN_PHOTO: the calls Geni got and what stays in the tree and on the device. */
class UploadPhotoTagsTest {

    private val server = MockWebServer().apply { start() }
    private val tokens = InMemoryTokenStore().apply { kotlinx.coroutines.runBlocking { save(AuthResult.Success("abc", Long.MAX_VALUE)) } }
    private val store = InMemorySyncStore()
    private val host = FakeTreeHost()
    private val downloads = mutableListOf<String>()
    private val photoSync = PhotoSync(store, host) { url, target ->
        downloads += url
        target.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1))
        "image/jpeg"
    }
    private val gedcom = Gedcom()
    private val treeId: Int
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1)

    init {
        for ((id, geniId) in listOf("I1" to "profile-1", "I2" to "profile-2")) gedcom.addPerson(Person().apply { this.id = id; GeniMapper.setGeniId(this, geniId) })
        treeId = kotlinx.coroutines.runBlocking { host.createTree("t", gedcom) }
    }

    @After
    fun tearDown() = server.close()

    private fun TestScope.uploader() = Uploader(
        GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime }), store, host, now = { 7_000L }, photos = photoSync
    )

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()
    private fun person(id: String) = gedcom.people.single { it.id == id }
    private fun photoJson(id: String) = """{"id":"$id","sizes":{"medium":"https://p.example/$id/m.jpg"}}"""
    private fun mugshotProfile(photo: String) = """{"results":[{"id":"profile-1","mugshot_urls":{"url":"https://api.example/$photo","medium":"https://p.example/$photo/m.jpg"}}]}"""

    private suspend fun give(personId: String, geniId: String, photoId: String, main: Boolean) {
        person(personId).addMedia(Media().apply {
            file = "geni-$photoId.jpg"; setFileTag("FILE"); GeniMapper.setTag(this, PhotoSync.PHOTO_TAG, photoId); if (main) primary = "Y"
        })
        File(host.mediaDir(treeId), "geni-$photoId.jpg").writeBytes(jpeg)
        store.savePhotoLink(PhotoLink(treeId, geniId, photoId, null, main, "https://p.example/$photoId/m.jpg", "geni-$photoId.jpg", PhotoState.DOWNLOADED))
    }

    private fun job(kind: JobKind, geniId: String, key: String, vararg params: Pair<String, String>, person: String = "I1") =
        UploadJob(id = 1, treeId = treeId, key = key, kind = kind, geniId = geniId, personGedcomId = person, params = params.toList(), label = "x", localHash = 0)

    @Test
    fun tagPostsAndDownloadsThePhotoForTheTaggedPerson() = runTest {
        server.enqueue(ok(Fixtures.read("photo-tag-response.json")))
        server.enqueue(ok(photoJson("photo-5")))
        assertEquals(Outcome.Done, uploader().run(job(JobKind.TAG_PHOTO, "photo-5", "tags:photo-5:profile-2", "profile" to "profile-2", person = "I2")))
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/api/photo-5/tag/profile-2", post.url.encodedPath)
        assertEquals(listOf("photo-5"), person("I2").media.map { GeniMapper.getTag(it, PhotoSync.PHOTO_TAG) })
        assertTrue(person("I1").media.isEmpty())
    }

    @Test
    fun untagPostsAndLeavesTheOtherProfilesAndTheFileAlone() = runTest {
        give("I1", "profile-1", "photo-5", false)
        give("I2", "profile-2", "photo-5", false)
        // The user's side already took the photo off I1 (see removeLocal); the file stays for I2
        photoSync.removeLocal(treeId, "profile-1", "I1", "photo-5")
        server.enqueue(ok(Fixtures.read("photo-untag-response.json")))
        assertEquals(Outcome.Done, uploader().run(job(JobKind.UNTAG_PHOTO, "photo-5", "tags:photo-5:profile-1", "profile" to "profile-1")))
        assertEquals("/api/photo-5/untag/profile-1", server.takeRequest().url.encodedPath)
        assertEquals(1, server.requestCount) // not the main photo: nothing to look up
        assertTrue(person("I1").media.isEmpty())
        assertEquals(1, person("I2").media.size)
        assertTrue(File(host.mediaDir(treeId), "geni-photo-5.jpg").isFile)
        assertEquals(listOf("profile-2"), store.photoLinks(treeId).map { it.geniProfileId })
    }

    @Test
    fun untaggingTheMainPhotoTakesGenisReplacementIn() = runTest {
        give("I1", "profile-1", "photo-5", true)
        give("I1", "profile-1", "photo-6", false)
        server.enqueue(ok(Fixtures.read("photo-untag-last-response.json")))
        server.enqueue(ok(mugshotProfile("photo-6")))
        assertEquals(Outcome.Done, uploader().run(job(JobKind.UNTAG_PHOTO, "photo-5", "tags:photo-5:profile-1", "profile" to "profile-1")))
        server.takeRequest()
        assertEquals("profile-1", server.takeRequest().url.queryParameter("ids"))
        assertEquals(listOf("photo-6"), person("I1").media.map { GeniMapper.getTag(it, PhotoSync.PHOTO_TAG) })
        assertEquals("Y", person("I1").media.single().primary)
        assertTrue(downloads.isEmpty()) // photo-6 was on the device
    }

    @Test
    fun untagOfAnAlreadyGoneTagIsDone() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":{"message":"Not found"}}""").build())
        assertEquals(Outcome.Done, uploader().run(job(JobKind.UNTAG_PHOTO, "photo-5", "tags:photo-5:profile-1", "profile" to "profile-1")))
    }

    @Test
    fun setMainPostsPhotoIdAndSwitchesTheMainPhotoWithoutDownloading() = runTest {
        give("I1", "profile-1", "photo-5", true)
        give("I1", "profile-1", "photo-6", false)
        server.enqueue(ok(Fixtures.read("photo-add-mugshot-response.json")))
        server.enqueue(ok(mugshotProfile("photo-6")))
        assertEquals(Outcome.Done, uploader().run(job(JobKind.SET_MAIN_PHOTO, "profile-1", "mainphoto:profile-1", "photo_id" to "photo-6")))
        val post = server.takeRequest()
        assertEquals("/api/profile-1/add-mugshot", post.url.encodedPath)
        assertEquals("photo_id=photo-6", post.body!!.utf8())
        val media = person("I1").media.associateBy { GeniMapper.getTag(it, PhotoSync.PHOTO_TAG) }
        assertEquals("Y", media.getValue("photo-6").primary)
        assertEquals(null, media.getValue("photo-5").primary) // the former main stays as a plain photo
        assertTrue(downloads.isEmpty())
        assertEquals(mapOf("photo-5" to false, "photo-6" to true), store.photoLinks(treeId).associate { it.geniPhotoId to it.isMain })
    }

    @Test
    fun setMainOfThePhotoAlreadyMainAnswersNullAndChangesNothing() = runTest {
        give("I1", "profile-1", "photo-5", true)
        server.enqueue(ok(Fixtures.read("photo-add-mugshot-already-main.json")))
        server.enqueue(ok(mugshotProfile("photo-5")))
        assertEquals(Outcome.Done, uploader().run(job(JobKind.SET_MAIN_PHOTO, "profile-1", "mainphoto:profile-1", "photo_id" to "photo-5")))
        assertEquals("Y", person("I1").media.single().primary)
    }

    @Test
    fun aRejectedSetMainFailsAndKeepsTheMainPhoto() = runTest {
        give("I1", "profile-1", "photo-5", true)
        give("I1", "profile-1", "photo-6", false)
        server.enqueue(MockResponse.Builder().code(403).body("""{"error":{"message":"Access denied"}}""").build())
        assertTrue(uploader().run(job(JobKind.SET_MAIN_PHOTO, "profile-1", "mainphoto:profile-1", "photo_id" to "photo-6")) is Outcome.Failed)
        assertEquals("Y", person("I1").media.single { GeniMapper.getTag(it, PhotoSync.PHOTO_TAG) == "photo-5" }.primary)
    }
}
