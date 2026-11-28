package app.familygem.geni.sync

import app.familygem.geni.FakeTreeHost
import app.familygem.geni.Fixtures
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.RateLimiter
import app.familygem.geni.api.parseVideo
import app.familygem.geni.auth.AuthResult
import app.familygem.geni.auth.InMemoryTokenStore
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.PhotoDetails
import app.familygem.geni.map.PhotoField
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
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
import java.io.RandomAccessFile

/** Video jobs: what Geni gets, what the tree and the device keep, and that nothing is ever created twice. */
class UploadVideoTest {

    private val server = MockWebServer().apply { start() }
    private val tokens = InMemoryTokenStore().apply { save(AuthResult.Success("abc", Long.MAX_VALUE)) }
    private val store = InMemorySyncStore()
    private val host = FakeTreeHost()
    private val gedcom = Gedcom()
    private val treeId: Int
    private val mp4 = byteArrayOf(0, 0, 0, 0x1c, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d) + "mp4 body".toByteArray()
    private val downloads = mutableListOf<String>()
    private val videos = VideoSync(store, host, download = { url, target -> downloads += url; target.writeBytes(mp4); "application/octet-stream" })

    init {
        for ((id, geniId) in listOf("I1" to "profile-1", "I2" to "profile-2")) gedcom.addPerson(Person().apply { this.id = id; GeniMapper.setGeniId(this, geniId) })
        treeId = kotlinx.coroutines.runBlocking { host.createTree("t", gedcom) }
        kotlinx.coroutines.runBlocking {
            store.saveLinks(listOf(GeniLink(treeId, "profile-1", "I1", true, "1", """{"id":"profile-1"}""", 5, 99), GeniLink(treeId, "profile-2", "I2", true, "1", """{"id":"profile-2"}""", 5, 99)))
        }
    }

    @After
    fun tearDown() = server.close()

    private fun TestScope.uploader() = Uploader(
        GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime }), store, host, now = { 7_000L },
        photos = PhotoSync(store, host) { _, _ -> "image/jpeg" }, videos = videos
    )

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()
    private fun person(id: String) = gedcom.people.single { it.id == id }
    private val mediaDir get() = host.mediaDir(treeId)

    /** What the user's side does before queueing: the file in the media folder and its media, marked as waiting for Geni. */
    private fun stage(fileName: String = "clip.mp4", bytes: ByteArray = mp4, title: String? = "My clip") {
        File(mediaDir, fileName).writeBytes(bytes)
        person("I1").addMedia(Media().apply {
            file = fileName; setFileTag("FILE"); this.title = title; GeniMapper.setTag(this, VideoSync.QUEUED_TAG, "Y")
        })
    }

    private suspend fun queueUpload(fileName: String = "clip.mp4") =
        SyncQueue(store, uploader0).enqueueAddVideo(treeId, "profile-1", "I1", fileName, "My clip", "a holiday", "Video: $fileName")

    private lateinit var uploader0: Uploader

    private fun addJob(fileName: String = "clip.mp4") = UploadJob(id = 1, treeId = treeId, key = "vid:I1:$fileName", kind = JobKind.ADD_VIDEO, geniId = "profile-1",
        personGedcomId = "I1", params = listOf("file" to fileName, "content_type" to "video/mp4", "title" to "My clip", "description" to "a holiday"), label = "x", localHash = 0)

    private fun form(body: String) = body.split("&").associate {
        val (k, v) = it.split("=", limit = 2)
        java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
    }

    @Test
    fun addVideoSendsTheFileOnceThenLinksAndTagsTheMedia() = runTest {
        stage()
        server.enqueue(ok(Fixtures.read("video-added.json")))
        assertEquals(Outcome.Done, uploader().run(addJob()))
        val request = server.takeRequest()
        assertEquals("/api/profile-1/add-video", request.url.encodedPath)
        val form = form(request.body!!.utf8())
        assertEquals("clip.mp4", form["file_name"])
        assertEquals("video/mp4", form["content_type"])
        assertEquals("My clip", form["title"])
        assertEquals("a holiday", form["description"])
        assertTrue(mp4.contentEquals(java.util.Base64.getDecoder().decode(form["file"])))
        val link = store.photoLinks(treeId, "profile-1").single()
        assertEquals("video-289", link.geniPhotoId)
        assertEquals("clip.mp4", link.fileName)
        val media = person("I1").media.single()
        assertEquals("video-289", GeniMapper.getTag(media, VideoSync.VIDEO_TAG))
        assertNull(GeniMapper.getTag(media, VideoSync.QUEUED_TAG))
        // A run of the same job again (a finished job left behind) sends nothing
        assertEquals(Outcome.Done, uploader().run(addJob()))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aQueuedOrUploadedVideoIsNeverAPhotoCandidate() {
        stage()
        fun photoJobs() = UploadPlanner.plan(treeId, LocalTree.capture(gedcom), kotlinx.coroutines.runBlocking { store.links(treeId) }, emptyList(), emptyList(), true)
            .upserts.filter { it.kind == JobKind.ADD_PHOTO }
        assertTrue("queued for upload", photoJobs().isEmpty())
        val media = person("I1").media.single()
        GeniMapper.setTag(media, VideoSync.QUEUED_TAG, null)
        GeniMapper.setTag(media, VideoSync.VIDEO_TAG, "video-5")
        assertTrue("uploaded", photoJobs().isEmpty())
    }

    @Test
    fun anUploadAnswerWithoutAPlaybackUrlStillLinksTheVideo() = runTest {
        stage()
        server.enqueue(ok(Fixtures.read("video-updated-no-sizes.json")))
        assertEquals(Outcome.Done, uploader().run(addJob()))
        assertEquals("video-291", store.photoLinks(treeId, "profile-1").single().geniPhotoId)
        assertEquals("video-291", GeniMapper.getTag(person("I1").media.single(), VideoSync.VIDEO_TAG))
    }

    @Test
    fun aLostCreateResponseKeepsTheJobVisibleAndNeverSendsAnotherCreate() = runTest {
        stage()
        server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build())
        uploader0 = uploader()
        val queue = SyncQueue(store, uploader0)
        assertTrue(queueUpload())
        assertEquals(RunResult.EMPTY, queue.runPending(treeId))
        val failed = store.jobs(treeId).single()
        assertEquals(JobState.FAILED, failed.state)
        assertTrue(failed.error!!.contains("check on Geni"))
        assertTrue(store.photoLinks(treeId).isEmpty())
        // Running the queue again (a later sync, a restart) must not create another video
        assertEquals(RunResult.EMPTY, queue.runPending(treeId))
        assertEquals(1, server.requestCount)
        assertEquals(JobState.FAILED, store.jobs(treeId).single().state)
    }

    @Test
    fun anUploadInterruptedByARestartIsFailedNotResent() = runTest {
        stage()
        uploader0 = uploader()
        val queue = SyncQueue(store, uploader0)
        queueUpload()
        store.saveJob(store.jobs(treeId).single().copy(state = JobState.SENDING)) // The app stopped while sending
        queue.runPending(treeId)
        val job = store.jobs(treeId).single()
        assertEquals(JobState.FAILED, job.state)
        assertEquals(INTERRUPTED_ADD, job.error)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun queuingTheSameFileTwiceKeepsOneJob() = runTest {
        stage()
        uploader0 = uploader()
        assertTrue(queueUpload())
        assertFalse(queueUpload())
        assertEquals(1, store.jobs(treeId).size)
    }

    @Test
    fun theUploadInputSurvivesARestart() = runTest {
        stage()
        uploader0 = uploader()
        queueUpload()
        // A new queue and uploader over the same store and media folder, as after a restart
        server.enqueue(ok(Fixtures.read("video-added.json")))
        val queue = SyncQueue(store, uploader())
        assertEquals(RunResult.EMPTY, queue.runPending(treeId))
        assertEquals(1, server.requestCount)
        assertTrue(store.jobs(treeId).isEmpty())
        assertEquals("video-289", store.photoLinks(treeId).single().geniPhotoId)
    }

    @Test
    fun aFileOverTheVerifiedSizeOrNotAnMp4FailsWithoutSending() = runTest {
        stage()
        RandomAccessFile(File(mediaDir, "clip.mp4"), "rw").use { it.setLength(VideoMaxBytes + 1) }
        val tooBig = uploader().run(addJob())
        assertTrue(tooBig is Outcome.Failed && tooBig.message.contains("larger than"))
        val mov = uploader().run(addJob("clip.mov").copy(params = listOf("file" to "clip.mov")))
        assertTrue(mov is Outcome.Failed && mov.message.contains("MP4"))
        assertEquals(0, server.requestCount)
    }

    private val VideoMaxBytes = app.familygem.geni.api.VideoTypes.MAX_UPLOAD_BYTES

    @Test
    fun aVideoTheUserRemovedBeforeItWasSentIsNotSent() = runTest {
        stage()
        person("I1").media.clear()
        val outcome = uploader().run(addJob())
        assertTrue(outcome is Outcome.Failed && outcome.message.contains("removed from this device"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aPersonWhoCannotBeVerifiedIsDeferredNotSent() = runTest {
        stage()
        host.savedReadFails = true
        assertEquals(Outcome.Deferred, uploader().run(addJob()))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aFailedTagRetriesAgainstTheSameVideoAndNeverCreates() = runTest {
        val job = UploadJob(id = 1, treeId = treeId, key = "vidtags:video-289:profile-2", kind = JobKind.TAG_VIDEO, geniId = "video-289",
            personGedcomId = "I2", params = listOf("profile" to "profile-2"), label = "x", localHash = 0)
        server.enqueue(MockResponse.Builder().code(503).body("busy").build())
        assertTrue(uploader().run(job) is Outcome.Retry)
        server.enqueue(ok("""{"results":[]}"""))
        server.enqueue(ok(Fixtures.read("video-added.json")))
        assertEquals(Outcome.Done, uploader().run(job))
        val paths = List(server.requestCount) { server.takeRequest().url.encodedPath }
        assertEquals(listOf("/api/video-289/tag/profile-2", "/api/video-289/tag/profile-2", "/api/video-289"), paths)
        assertTrue(paths.none { it.endsWith("add-video") || it == "/api/video/add" })
        assertEquals(1, person("I2").media.size)
        assertEquals(1, downloads.size)
    }

    @Test
    fun taggingASecondProfileLetsBothOpenTheSameFileThenUntagKeepsTheOther() = runTest {
        val doc = parseVideo(Fixtures.read("video-added.json"))!!
        videos.attach(treeId, "profile-1", "I1", doc)
        server.enqueue(ok("""{"results":[]}"""))
        server.enqueue(ok(Fixtures.read("video-added.json")))
        uploader().run(UploadJob(id = 1, treeId = treeId, key = "k", kind = JobKind.TAG_VIDEO, geniId = doc.id, personGedcomId = "I2",
            params = listOf("profile" to "profile-2"), label = "x", localHash = 0))
        assertEquals(1, downloads.size) // the file was already there
        assertTrue(File(mediaDir, "geni-video-289.mp4").isFile)
        assertEquals(1, person("I1").media.size)
        assertEquals(1, person("I2").media.size)
        server.enqueue(ok("""{"results":[]}"""))
        uploader().run(UploadJob(id = 2, treeId = treeId, key = "u", kind = JobKind.UNTAG_VIDEO, geniId = doc.id, personGedcomId = "I1",
            params = listOf("profile" to "profile-1"), label = "x", localHash = 0))
        assertTrue(person("I1").media.isEmpty())
        assertEquals(1, person("I2").media.size)
        assertTrue(File(mediaDir, "geni-video-289.mp4").isFile)
        assertEquals(listOf("profile-2"), store.photoLinks(treeId).map { it.geniProfileId })
    }

    @Test
    fun untagOfATagAlreadyGoneIsDone() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("""{"error":{"message":"Not found"}}""").build())
        assertEquals(Outcome.Done, uploader().run(UploadJob(id = 2, treeId = treeId, key = "u", kind = JobKind.UNTAG_VIDEO, geniId = "video-9",
            personGedcomId = "I1", params = listOf("profile" to "profile-1"), label = "x", localHash = 0)))
    }

    @Test
    fun aConfirmedDeleteRemovesTheSyncedAttachmentsAndKeepsAnEditedCopy() = runTest {
        val doc = parseVideo(Fixtures.read("video-added.json"))!!
        videos.attach(treeId, "profile-1", "I1", doc)
        videos.attach(treeId, "profile-2", "I2", doc)
        person("I2").media.single().title = "Renamed by me"
        server.enqueue(ok("""{"result":"Deleted"}"""))
        assertEquals(Outcome.Done, uploader().run(UploadJob(id = 3, treeId = treeId, key = "delvid:${doc.id}", kind = JobKind.DELETE_VIDEO, geniId = doc.id,
            personGedcomId = "I1", params = emptyList(), label = "x", localHash = 0)))
        assertEquals("/api/video-289/delete", server.takeRequest().url.encodedPath)
        assertTrue(person("I1").media.isEmpty())
        val kept = person("I2").media.single()
        assertNull(GeniMapper.getTag(kept, VideoSync.VIDEO_TAG))
        assertTrue(File(mediaDir, kept.file).isFile)
        assertTrue(store.photoLinks(treeId, "profile-1").isEmpty())
        // Geni deleted it: a stale listing or a download in flight cannot bring it back
        assertFalse(videos.attach(treeId, "profile-1", "I1", doc))
    }

    @Test
    fun aDeleteOfAVideoAlreadyGoneIsDone() = runTest {
        server.enqueue(MockResponse.Builder().code(404).body("{}").build())
        assertEquals(Outcome.Done, uploader().run(UploadJob(id = 3, treeId = treeId, key = "delvid:video-9", kind = JobKind.DELETE_VIDEO, geniId = "video-9",
            personGedcomId = "I1", params = emptyList(), label = "x", localHash = 0)))
    }

    private val base = PhotoDetails("Vid", null, null)

    private suspend fun queueEdit(wanted: PhotoDetails, fields: List<PhotoField>) = SyncQueue(store, uploader0)
        .enqueueUpdatePhoto(treeId, "video-289", "I1", "Video details", base, wanted, fields, JobKind.UPDATE_VIDEO)

    @Test
    fun anEditSendsOnlyTheChangedFieldAndAppliesGenisAnswer() = runTest {
        val doc = parseVideo(Fixtures.read("video-added.json"))!!
        videos.attach(treeId, "profile-1", "I1", doc)
        uploader0 = uploader()
        assertTrue(queueEdit(base.copy(description = "scanned twice"), listOf(PhotoField.DESCRIPTION)))
        assertEquals("updvid:video-289", store.jobs(treeId).single().key)
        server.enqueue(ok(Fixtures.read("video-added.json"))) // the current video on Geni
        server.enqueue(ok(Fixtures.read("video-added.json").replace("\"title\":\"Vid\"", "\"title\":\"Vid\",\"description\":\"scanned twice\""))) // the update's answer
        assertEquals(RunResult.EMPTY, SyncQueue(store, uploader0).runPending(treeId))
        assertEquals("/api/video-289", server.takeRequest().url.encodedPath)
        val post = server.takeRequest()
        assertEquals("/api/video-289/update", post.url.encodedPath)
        assertEquals("description=scanned+twice", post.body!!.utf8())
        assertTrue(store.jobs(treeId).isEmpty())
        assertEquals("scanned twice", PhotoDetails.shown(person("I1").media.single()).description)
    }

    @Test
    fun aRejectedEditKeepsTheDraftForARetry() = runTest {
        val doc = parseVideo(Fixtures.read("video-added.json"))!!
        videos.attach(treeId, "profile-1", "I1", doc)
        uploader0 = uploader()
        queueEdit(base.copy(title = "A better title"), listOf(PhotoField.TITLE))
        val draft = store.jobs(treeId).single().params
        server.enqueue(ok(Fixtures.read("video-added.json")))
        server.enqueue(MockResponse.Builder().code(403).body("""{"error":{"message":"Access Denied"}}""").build())
        SyncQueue(store, uploader0).runPending(treeId)
        val job = store.jobs(treeId).single()
        assertEquals(JobState.FAILED, job.state)
        assertTrue(job.error!!.contains("Access Denied"))
        assertEquals(draft, job.params)
        // The user's own title is still on screen
        applyOwn()
    }

    private suspend fun applyOwn() {
        videos.applyOwnEdit(treeId, "video-289", PhotoDetails("A better title", null, null), listOf(PhotoField.TITLE))
        assertEquals("A better title", person("I1").media.single().title)
    }

    @Test
    fun aVideoGoneFromGeniFailsTheEditWithTheDraftKept() = runTest {
        uploader0 = uploader()
        queueEdit(base.copy(title = "x"), listOf(PhotoField.TITLE))
        server.enqueue(ok(Fixtures.read("video-deleted.json")))
        SyncQueue(store, uploader0).runPending(treeId)
        val job = store.jobs(treeId).single()
        assertEquals(JobState.FAILED, job.state)
        assertTrue(job.error!!.contains("gone from Geni"))
    }

    @Test
    fun theOtherPhotoJobsAreUntouchedByVideoJobsInThePlanner() = runTest {
        stage()
        uploader0 = uploader()
        queueUpload()
        val queue = SyncQueue(store, uploader0)
        queue.onSaved(treeId, LocalTree.capture(gedcom))
        assertEquals(listOf(JobKind.ADD_VIDEO), store.jobs(treeId).map { it.kind }) // a save does not plan it away or add a photo job
    }
}
