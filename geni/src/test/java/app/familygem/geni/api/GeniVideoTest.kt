package app.familygem.geni.api

import app.familygem.geni.Fixtures
import app.familygem.geni.auth.AuthResult
import app.familygem.geni.auth.InMemoryTokenStore
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** The Geni video responses saved from the sandbox, and the calls the client makes for them. */
class GeniVideoTest {

    private val server = MockWebServer()
    private val tokens = InMemoryTokenStore().apply { save(AuthResult.Success("abc", Long.MAX_VALUE)) }

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun TestScope.client() = GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime })

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    private fun form(body: String) = body.split("&").associate {
        val (k, v) = it.split("=", limit = 2)
        java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
    }

    @Test
    fun theUploadAnswerHasThePlayableMp4AndAPosterOriginal() {
        val video = parseVideo(Fixtures.read("video-added.json"))!!
        assertEquals("video-289", video.id)
        assertEquals("Vid", video.title)
        assertEquals(listOf("profile-36581"), video.tags)
        assertTrue(video.videoUrl!!.contains("0011ead7.mp4"))
        assertTrue(video.posterUrl!!.contains("_original.jpg")) // the poster, never the video
        assertFalse(video.urls.containsKey("url"))
    }

    @Test
    fun aVideoWithOnlyPostersIsKeptWithNoPlaybackUrl() {
        val videos = parseVideos(Fixtures.read("videos-with-poster-only.json"))
        assertEquals(listOf("video-296", "video-295"), videos.map { it.id })
        assertNull(videos[0].videoUrl)
        assertTrue(videos[0].posterUrl != null)
        assertTrue(videos[1].videoUrl != null)
    }

    @Test
    fun anUpdateAnswerWithoutSizesIsStillAVideo() {
        val video = parseVideo(Fixtures.read("video-updated-no-sizes.json"))!!
        assertEquals("T2", video.title)
        assertNull(video.videoUrl)
        assertNull(video.posterUrl)
    }

    @Test
    fun aDeletedVideoAnswersAnEmptyListNotAVideo() {
        assertNull(parseVideo(Fixtures.read("video-deleted.json")))
    }

    @Test
    fun parsesAListAndAOneItemAnswerWithoutResults() {
        assertEquals(listOf("video-293", "video-292", "video-291"), parseVideos(Fixtures.read("videos-page1.json")).map { it.id })
        assertEquals(listOf("video-289"), parseVideos(Fixtures.read("videos-single-bare.json")).map { it.id })
        assertTrue(parseVideos(Fixtures.read("videos-page2-empty.json")).isEmpty())
    }

    @Test
    fun videosReadsEveryPageUntilAnEmptyOne() = runTest {
        server.enqueue(ok(Fixtures.read("videos-page1.json")))
        server.enqueue(ok(Fixtures.read("videos-page2-empty.json")))
        val videos = client().videos("profile-36580")
        assertEquals(3, videos.size)
        assertEquals("/api/profile-36580/videos", server.takeRequest().url.encodedPath)
        assertEquals("2", server.takeRequest().url.queryParameter("page"))
    }

    @Test
    fun videosThrowsWhenAPageFailsInsteadOfReturningAPartialList() = runTest {
        server.enqueue(ok(Fixtures.read("videos-page1.json")))
        server.enqueue(MockResponse.Builder().code(500).body("down").build())
        try {
            client().videos("profile-36580")
            org.junit.Assert.fail("a failed page must not give a partial list")
        } catch (e: GeniApiException) {
            assertEquals(500, e.code)
        }
    }

    @Test
    fun addVideoSendsBase64FileNameAndMp4ContentTypeWithTheDetails() = runTest {
        server.enqueue(ok(Fixtures.read("video-added.json")))
        val bytes = ByteArray(300) { it.toByte() }
        val video = client().addVideo("profile-9", "clip.mp4", "Title", "Two lines\nhere", bytes)
        assertEquals("video-289", video.id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/profile-9/add-video", request.url.encodedPath)
        val form = form(request.body!!.utf8())
        assertEquals(setOf("file", "file_name", "content_type", "title", "description"), form.keys)
        assertEquals("clip.mp4", form["file_name"])
        assertEquals("video/mp4", form["content_type"])
        assertEquals("Title", form["title"])
        assertEquals("Two lines\nhere", form["description"])
        assertTrue(bytes.contentEquals(java.util.Base64.getDecoder().decode(form["file"])))
    }

    @Test
    fun addVideoOmitsAMissingTitleAndDescription() = runTest {
        server.enqueue(ok(Fixtures.read("video-added.json")))
        client().addVideo("profile-9", "a.mp4", null, null, byteArrayOf(1, 2))
        assertEquals(setOf("file", "file_name", "content_type"), form(server.takeRequest().body!!.utf8()).keys)
    }

    @Test
    fun updateTagUntagAndDeletePostToTheVideoRoutes() = runTest {
        server.enqueue(ok(Fixtures.read("video-updated-no-sizes.json")))
        server.enqueue(ok("""{"results":[]}"""))
        server.enqueue(ok("""{"results":[]}"""))
        server.enqueue(ok("""{"result":"Deleted"}"""))
        val client = client()
        client.updateVideo("video-5", listOf("title" to "New"))
        client.tagVideo("video-5", "profile-2")
        client.untagVideo("video-5", "profile-2")
        client.deleteVideo("video-5")
        val requests = List(4) { server.takeRequest() }
        assertEquals(listOf("/api/video-5/update", "/api/video-5/tag/profile-2", "/api/video-5/untag/profile-2", "/api/video-5/delete"),
            requests.map { it.url.encodedPath })
        assertTrue(requests.all { it.method == "POST" })
        assertEquals("title=New", requests[0].body!!.utf8())
    }

    @Test
    fun videoOfADeletedOneIsNull() = runTest {
        server.enqueue(ok(Fixtures.read("video-deleted.json")))
        assertNull(client().video("video-291"))
    }

    @Test
    fun onlyMp4FilesAreUploadedAndTheCapIsBelowTheSizeGeniCutOff() {
        assertEquals("video/mp4", VideoTypes.contentTypeFor("Holiday.MP4"))
        assertNull(VideoTypes.contentTypeFor("movie.mov"))
        assertNull(VideoTypes.contentTypeFor("mp4"))
        assertTrue(VideoTypes.MAX_UPLOAD_BYTES < 4_000_000) // a 4 MB upload timed out in the sandbox
    }

    @Test
    fun theMp4CheckReadsTheFtypBoxAndTheCdnTypeIsAccepted() {
        val dir = java.nio.file.Files.createTempDirectory("vt").toFile()
        try {
            val mp4 = File(dir, "a.mp4").apply { writeBytes(byteArrayOf(0, 0, 0, 0x1c, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte())) }
            val jpeg = File(dir, "b.mp4").apply { writeBytes(ByteArray(40) { 0xFF.toByte() }) }
            val short = File(dir, "c.mp4").apply { writeBytes(byteArrayOf(1, 2)) }
            assertTrue(VideoTypes.looksLikeMp4(mp4))
            assertFalse(VideoTypes.looksLikeMp4(jpeg))
            assertFalse(VideoTypes.looksLikeMp4(short))
        } finally {
            dir.deleteRecursively()
        }
        assertTrue(VideoTypes.isFile("application/octet-stream")) // how the CDN serves the transcoded MP4
        assertTrue(VideoTypes.isFile("video/mp4; charset=x"))
        assertFalse(VideoTypes.isFile("text/html"))
        assertFalse(VideoTypes.isFile("image/jpeg"))
    }
}
