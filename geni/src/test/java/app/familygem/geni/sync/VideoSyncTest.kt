package app.familygem.geni.sync

import app.familygem.geni.FakeTreeHost
import app.familygem.geni.Fixtures
import app.familygem.geni.api.VideoTypes
import app.familygem.geni.api.GeniApiException
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.PhotoDownloader
import app.familygem.geni.api.RateLimiter
import app.familygem.geni.api.parseVideo
import app.familygem.geni.api.parseVideos
import app.familygem.geni.auth.AuthResult
import app.familygem.geni.auth.InMemoryTokenStore
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.PhotoDetails
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Media
import org.folg.gedcom.model.Person
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

/** Videos on the device: download on demand, one file for two profiles, listings, removal. */
class VideoSyncTest {

    private val server = MockWebServer().apply { start() }
    private val tokens = InMemoryTokenStore().apply { save(AuthResult.Success("abc", Long.MAX_VALUE)) }
    private val store = InMemorySyncStore()
    private val host = FakeTreeHost()
    private val gedcom = Gedcom()
    private val treeId: Int
    private val mp4 = byteArrayOf(0, 0, 0, 0x1c, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d) + "mp4 body".toByteArray()
    private val downloads = mutableListOf<String>()

    private val videos = VideoSync(store, host, download = { url, target ->
        downloads += url
        target.writeBytes(mp4)
        "application/octet-stream"
    })

    init {
        for ((id, geniId) in listOf("I1" to "profile-1", "I2" to "profile-2")) gedcom.addPerson(Person().apply { this.id = id; GeniMapper.setGeniId(this, geniId) })
        treeId = kotlinx.coroutines.runBlocking { host.createTree("t", gedcom) }
    }

    @After
    fun tearDown() = server.close()

    private fun person(id: String) = gedcom.people.single { it.id == id }
    private val video = parseVideo(Fixtures.read("video-added.json"))!!
    private val mediaDir get() = host.mediaDir(treeId)
    private fun mediaOf(personId: String) = person(personId).media.filter { GeniMapper.getTag(it, VideoSync.VIDEO_TAG) == video.id }

    @Test
    fun attachDownloadsTheOriginalAndAttachesItWithItsDetails() = runTest {
        assertTrue(videos.attach(treeId, "profile-1", "I1", video))
        assertEquals(1, downloads.size)
        assertTrue(downloads.single().contains("0011ead7.mp4"))
        val media = mediaOf("I1").single()
        assertEquals("geni-video-289.mp4", media.file)
        assertEquals("Vid", media.title)
        assertTrue(File(mediaDir, "geni-video-289.mp4").readBytes().contentEquals(mp4))
        val link = store.photoLinks(treeId, "profile-1").single()
        assertEquals("video-289", link.geniPhotoId)
        assertFalse(link.isMain)
        assertEquals(PhotoState.DOWNLOADED, link.state)
    }

    @Test
    fun theSecondProfileReusesTheFileAndHasItsOwnLink() = runTest {
        videos.attach(treeId, "profile-1", "I1", video)
        assertTrue(videos.attach(treeId, "profile-2", "I2", video))
        assertEquals(1, downloads.size)
        assertEquals(1, mediaOf("I2").size)
        assertEquals(setOf("profile-1", "profile-2"), store.photoLinks(treeId).map { it.geniProfileId }.toSet())
        // Attaching again changes nothing
        assertFalse(videos.attach(treeId, "profile-2", "I2", video))
    }

    @Test
    fun aVideoWithoutAPlaybackUrlIsNeverDownloadedOrAttached() = runTest {
        val posterOnly = parseVideos(Fixtures.read("videos-with-poster-only.json")).first()
        assertFalse(videos.attach(treeId, "profile-1", "I1", posterOnly))
        assertTrue(downloads.isEmpty())
        assertTrue(person("I1").media.isEmpty())
    }

    @Test
    fun aDownloadThatIsNotAnMp4ChangesNothingAndLeavesNoFile() = runTest {
        val html = VideoSync(store, host, download = { _, target -> target.writeText("<html>not a video</html>"); "application/octet-stream" })
        try {
            html.attach(treeId, "profile-1", "I1", video)
            org.junit.Assert.fail("a file that is no video must be refused")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("not an MP4"))
        }
        assertTrue(person("I1").media.isEmpty())
        assertTrue(store.photoLinks(treeId).isEmpty())
        assertEquals(emptyList<String>(), mediaDir.list()!!.toList())
    }

    private fun realDownloader(maxBytes: Long) = PhotoDownloader(maxBytes = maxBytes, allowHttp = true, accept = VideoTypes::isFile)

    @Test
    fun anOversizedDownloadIsRefusedWithoutAFile() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/octet-stream").body(Buffer().write(ByteArray(500) { 0x25 })).build())
        val sync = VideoSync(store, host, download = realDownloader(100)::download)
        val withUrl = video.copy(urls = mapOf("video" to server.url("/big.mp4").toString()))
        try {
            sync.attach(treeId, "profile-1", "I1", withUrl)
            org.junit.Assert.fail("too big")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("larger than"))
        }
        assertTrue(person("I1").media.isEmpty())
        assertEquals(emptyList<String>(), mediaDir.list()!!.toList())
    }

    @Test
    fun aHostThatServesAnotherTypeIsRefusedAndNeverSeesTheToken() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html></html>").build())
        val sync = VideoSync(store, host, download = realDownloader(1_000)::download)
        try {
            sync.attach(treeId, "profile-1", "I1", video.copy(urls = mapOf("video" to server.url("/x").toString())))
            org.junit.Assert.fail("html is no video")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("content type"))
        }
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun aGoodDownloadThroughTheRealDownloaderSendsNoAuthorization() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/octet-stream").body(Buffer().write(mp4)).build())
        val sync = VideoSync(store, host, download = realDownloader(1_000)::download)
        assertTrue(sync.attach(treeId, "profile-1", "I1", video.copy(urls = mapOf("video" to server.url("/d.mp4").toString()))))
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun aQueuedUntagOrDeleteBlocksTheDownload() = runTest {
        store.saveJob(UploadJob(treeId = treeId, key = "vidtags:video-289:profile-1", kind = JobKind.UNTAG_VIDEO, geniId = "video-289",
            personGedcomId = "I1", params = listOf("profile" to "profile-1"), label = "x", localHash = 0))
        assertFalse(videos.attach(treeId, "profile-1", "I1", video))
        // Another profile is not affected by it
        assertTrue(videos.attach(treeId, "profile-2", "I2", video))
    }

    @Test
    fun removeLocalKeepsTheFileWhileAnotherProfileHasIt() = runTest {
        videos.attach(treeId, "profile-1", "I1", video)
        videos.attach(treeId, "profile-2", "I2", video)
        assertTrue(videos.removeLocal(treeId, "profile-1", "I1", video.id))
        assertTrue(person("I1").media.isEmpty())
        assertTrue(File(mediaDir, "geni-video-289.mp4").isFile)
        assertTrue(videos.removeLocal(treeId, "profile-2", "I2", video.id))
        assertFalse(File(mediaDir, "geni-video-289.mp4").exists())
        assertTrue(store.photoLinks(treeId).isEmpty())
    }

    @Test
    fun aVideoDeletedOnGeniLeavesEveryPersonButKeepsTheUsersOwnCopy() = runTest {
        store.saveLinks(listOf(GeniLink(treeId, "profile-1", "I1", true, null, null, 0), GeniLink(treeId, "profile-2", "I2", true, null, null, 0)))
        videos.attach(treeId, "profile-1", "I1", video)
        videos.attach(treeId, "profile-2", "I2", video)
        mediaOf("I2").single().title = "My own name for it" // The user's title: this copy stays
        assertEquals(2, videos.deletedOnGeni(treeId, video.id))
        assertTrue(person("I1").media.isEmpty())
        val kept = person("I2").media.single()
        assertNull(GeniMapper.getTag(kept, VideoSync.VIDEO_TAG))
        assertEquals("My own name for it", kept.title)
        assertTrue(File(mediaDir, kept.file).isFile)
    }

    @Test
    fun aDetachedVideoWithANoteIsNeverAPhotoUpload() = runTest {
        store.saveLinks(listOf(GeniLink(treeId, "profile-1", "I1", true, null, null, 0)))
        videos.attach(treeId, "profile-1", "I1", video)
        person("I1").media.single().addNote(org.folg.gedcom.model.Note().apply { value = "my note" })
        videos.deletedOnGeni(treeId, video.id)
        val kept = person("I1").media.single()
        assertNull(GeniMapper.getTag(kept, VideoSync.VIDEO_TAG))
        val plan = UploadPlanner.plan(treeId, LocalTree.capture(gedcom), store.links(treeId), emptyList(), store.photoLinks(treeId), true)
        assertTrue(plan.upserts.none { it.kind == JobKind.ADD_PHOTO })
    }

    @Test
    fun reconcileRemovesOnlyVideosGeniNoLongerListsAndNeverAPhotoOrADocument() = runTest {
        videos.attach(treeId, "profile-1", "I1", video)
        store.savePhotoLink(PhotoLink(treeId, "profile-1", "photo-7", null, false, null, "geni-photo-7.jpg", PhotoState.DOWNLOADED))
        store.savePhotoLink(PhotoLink(treeId, "profile-1", "document-9", null, false, null, "geni-document-9.mp4", PhotoState.DOWNLOADED))
        assertEquals(0, videos.reconcile(treeId, "profile-1", "I1", listOf(video)))
        assertEquals(1, videos.reconcile(treeId, "profile-1", "I1", emptyList()))
        assertTrue(person("I1").media.isEmpty())
        assertEquals(listOf("photo-7", "document-9"), store.photoLinks(treeId, "profile-1").map { it.geniPhotoId })
    }

    @Test
    fun theAlbumAndTheDocumentListingNeverRemoveAVideo() = runTest {
        videos.attach(treeId, "profile-1", "I1", video)
        val photos = PhotoSync(store, host) { _, _ -> "image/jpeg" }
        assertEquals(0, photos.reconcileAlbum(treeId, "profile-1", "I1", emptyList()))
        assertEquals(0, DocumentSync(store, host).reconcile(treeId, "profile-1", "I1", emptyList()))
        assertEquals(1, mediaOf("I1").size)
        assertTrue(File(mediaDir, "geni-video-289.mp4").isFile)
    }

    private fun TestScope.loader(): VideoLoader =
        VideoLoader(GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime }), store, videos, host::mediaDir)

    @Test
    fun aFailedListingKeepsEveryCachedCopy() = runTest {
        videos.attach(treeId, "profile-1", "I1", video)
        server.enqueue(MockResponse.Builder().code(500).body("down").build())
        try {
            loader().list(treeId, "profile-1", "I1")
            org.junit.Assert.fail("the listing failed")
        } catch (e: GeniApiException) {
            assertEquals(500, e.code)
        }
        assertEquals(1, mediaOf("I1").size)
        assertTrue(File(mediaDir, "geni-video-289.mp4").isFile)
    }

    @Test
    fun anIncompleteListingWithAFailedSecondPageKeepsEveryCachedCopy() = runTest {
        videos.attach(treeId, "profile-1", "I1", video)
        server.enqueue(MockResponse.Builder().code(200).body("""{"results":[{"id":"video-1","title":"Other"}]}""").build())
        server.enqueue(MockResponse.Builder().code(502).body("bad gateway").build())
        try {
            loader().list(treeId, "profile-1", "I1")
            org.junit.Assert.fail("the second page failed")
        } catch (e: GeniApiException) {
            assertEquals(502, e.code)
        }
        assertEquals(1, mediaOf("I1").size)
        assertEquals(1, store.photoLinks(treeId, "profile-1").size)
    }

    @Test
    fun aCompleteListingMarksWhatIsOnTheDeviceAndRemovesWhatIsGone() = runTest {
        val listed = parseVideos(Fixtures.read("videos-page1.json"))
        videos.attach(treeId, "profile-1", "I1", listed.last()) // video-291, still listed
        videos.attach(treeId, "profile-1", "I1", video.copy(id = "video-999")) // gone from Geni
        server.enqueue(MockResponse.Builder().code(200).body(Fixtures.read("videos-page1.json")).build())
        server.enqueue(MockResponse.Builder().code(200).body(Fixtures.read("videos-page2-empty.json")).build())
        val listing = loader().list(treeId, "profile-1", "I1")
        assertEquals(1, listing.removed)
        assertEquals(listOf("video-293", "video-292", "video-291"), listing.entries.map { it.video.id })
        assertEquals(listOf(false, false, true), listing.entries.map { it.downloaded })
        assertEquals(listOf(true, true, true), listing.entries.map { it.playable })
    }

    @Test
    fun aListedVideoWithoutAPlaybackUrlStaysInTheListAsUnavailable() = runTest {
        server.enqueue(MockResponse.Builder().code(200).body(Fixtures.read("videos-with-poster-only.json")).build())
        server.enqueue(MockResponse.Builder().code(200).body(Fixtures.read("videos-page2-empty.json")).build())
        val listing = loader().list(treeId, "profile-1", "I1")
        assertEquals(listOf("video-296", "video-295"), listing.entries.map { it.video.id })
        assertEquals(listOf(false, true), listing.entries.map { it.playable })
    }

    @Test
    fun aDeletedVideoIsNeverBroughtBackByAnOldListingOrAnInFlightDownload() = runTest {
        videos.guard.confirm(treeId, video.id)
        assertFalse(videos.attach(treeId, "profile-1", "I1", video))
        assertTrue(person("I1").media.isEmpty())
        assertTrue(downloads.isEmpty())
        assertTrue(store.photoLinks(treeId).isEmpty())
    }
}
