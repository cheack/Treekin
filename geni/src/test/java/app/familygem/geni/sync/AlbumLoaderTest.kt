package app.familygem.geni.sync

import app.familygem.geni.FakeTreeHost
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
import org.folg.gedcom.model.Person
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class AlbumLoaderTest {

    private val server = MockWebServer().apply { start() }
    private val tokens = InMemoryTokenStore().apply { kotlinx.coroutines.runBlocking { save(AuthResult.Success("abc", 10_000L)) } }
    private val host = FakeTreeHost()
    private val store = InMemorySyncStore()
    private val gedcom = Gedcom().apply { addPerson(Person().apply { id = "I1"; GeniMapper.setGeniId(this, "profile-1") }) }
    private val treeId = kotlinx.coroutines.runBlocking { host.createTree("t", gedcom) }
    private val failing = mutableSetOf<String>()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1)

    private val sync = PhotoSync(store, host) { url, target ->
        if (failing.any { url.contains(it) }) throw IOException("cdn")
        target.writeBytes(jpeg)
        "image/jpeg"
    }

    @After
    fun tearDown() = server.close()

    private fun TestScope.loader() = AlbumLoader(
        GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime }, now = { 1_000L }),
        store, sync, host::mediaDir
    )

    private fun photoJson(n: Int) = """{"id":"photo-$n","title":"T$n","sizes":{"medium":"https://p.example/$n/m.jpg","thumb":"https://p.example/$n/t.jpg"}}"""

    private fun enqueueAlbum(vararg ids: Int) {
        server.enqueue(MockResponse.Builder().code(200).body("""{"results":[${ids.joinToString(",") { photoJson(it) }}]}""").build())
        server.enqueue(MockResponse.Builder().code(200).body("""{"results":[]}""").build())
    }

    @Test
    fun listMarksDownloadedAndMainPhotos() = runTest {
        sync.addAlbumPhoto(treeId, "profile-1", "I1", loaderPhoto(2))
        store.savePhotoLink(PhotoLink(treeId, "profile-1", "photo-1", null, true, null, "geni-photo-1.jpg", PhotoState.DOWNLOADED))
        java.io.File(host.mediaDir(treeId), "geni-photo-1.jpg").writeBytes(jpeg)
        enqueueAlbum(1, 2, 3)
        val entries = loader().list(treeId, "profile-1", "I1").entries
        assertEquals(listOf(true, true, false), entries.map { it.downloaded })
        assertEquals(listOf(true, false, false), entries.map { it.isMain })
    }

    @Test
    fun listReportsRemovedPhotos() = runTest {
        sync.addAlbumPhoto(treeId, "profile-1", "I1", loaderPhoto(1))
        sync.addAlbumPhoto(treeId, "profile-1", "I1", loaderPhoto(2))
        enqueueAlbum(1)
        val album = loader().list(treeId, "profile-1", "I1")
        assertEquals(1, album.removed)
        assertEquals(listOf("photo-1"), album.entries.map { it.photo.id })
        assertEquals(listOf("geni-photo-1.jpg"), gedcom.people.single().media.map { it.file })
    }

    private fun loaderPhoto(n: Int) = app.familygem.geni.api.parsePhoto(photoJson(n))

    @Test
    fun downloadAllAddsMissingPhotosAndSkipsFailures() = runTest {
        failing += "/2/"
        enqueueAlbum(1, 2, 3)
        val errors = mutableListOf<String>()
        val added = loader().downloadAll(treeId, "profile-1", "I1") { photo, _ -> errors += photo.id }
        assertEquals(2, added)
        assertEquals(listOf("photo-2"), errors)
        assertEquals(listOf("geni-photo-1.jpg", "geni-photo-3.jpg"), gedcom.people.single().media.map { it.file })
        assertEquals(listOf("photo-1", "photo-3"), store.photoLinks(treeId).map { it.geniPhotoId })
    }

    @Test
    fun downloadAllTwiceDownloadsNothingNew() = runTest {
        enqueueAlbum(1, 2)
        assertEquals(2, loader().downloadAll(treeId, "profile-1", "I1"))
        enqueueAlbum(1, 2)
        assertEquals(0, loader().downloadAll(treeId, "profile-1", "I1"))
        assertEquals(2, gedcom.people.single().media.size)
        assertTrue(store.photoLinks(treeId).none { it.isMain })
        assertFalse(gedcom.people.single().media.any { it.primary == "Y" })
    }

    private fun deleteJob(photoId: String, state: JobState = JobState.PENDING, tree: Int = treeId) =
        UploadJob(treeId = tree, key = "delphoto:$photoId", kind = JobKind.DELETE_PHOTO, geniId = photoId, personGedcomId = "I1",
            params = emptyList(), label = "x", localHash = 0, state = state)

    @Test
    fun downloadSkipsAPhotoWhoseDeleteIsPendingOrSending() = runTest {
        store.saveJob(deleteJob("photo-1", JobState.PENDING))
        store.saveJob(deleteJob("photo-2", JobState.SENDING))
        var calls = 0
        val guarded = AlbumLoader(
            GeniClient(server.url("/api/"), tokens, RateLimiter { 0L }, now = { 1_000L }),
            store, PhotoSync(store, host) { _, _ -> calls++; "image/jpeg" }, host::mediaDir
        )
        assertFalse(guarded.download(treeId, "profile-1", "I1", loaderPhoto(1)))
        assertFalse(guarded.download(treeId, "profile-1", "I1", loaderPhoto(2)))
        assertEquals(0, calls)
        assertTrue(gedcom.people.single().media.isEmpty())
        assertTrue(store.photoLinks(treeId).isEmpty())
    }

    @Test
    fun downloadSkipsAPhotoFromAnOlderListingOnceItsDeleteIsConfirmed() = runTest {
        // The delete job is gone from the queue; only the shared record of the confirmation remains
        sync.guard.confirm(treeId, "photo-1")
        assertFalse(loader().download(treeId, "profile-1", "I1", loaderPhoto(1)))
        assertTrue(gedcom.people.single().media.isEmpty())
        assertTrue(store.photoLinks(treeId).isEmpty())
        assertTrue(loader().download(treeId, "profile-1", "I1", loaderPhoto(2)))
    }

    @Test
    fun downloadStillWorksWhenTheDeleteFailedWasDismissedOrIsInAnotherTree() = runTest {
        store.saveJob(deleteJob("photo-1", JobState.FAILED))
        store.saveJob(deleteJob("photo-2", JobState.DISMISSED))
        store.saveJob(deleteJob("photo-3", tree = treeId + 1))
        val l = loader()
        assertTrue(l.download(treeId, "profile-1", "I1", loaderPhoto(1)))
        assertTrue(l.download(treeId, "profile-1", "I1", loaderPhoto(2)))
        assertTrue(l.download(treeId, "profile-1", "I1", loaderPhoto(3)))
    }

    @Test
    fun downloadAllSkipsOnlyThePhotoQueuedForDeletion() = runTest {
        store.saveJob(deleteJob("photo-2"))
        enqueueAlbum(1, 2, 3)
        assertEquals(2, loader().downloadAll(treeId, "profile-1", "I1"))
        assertEquals(listOf("geni-photo-1.jpg", "geni-photo-3.jpg"), gedcom.people.single().media.map { it.file })
    }

    @Test
    fun listKeepsReconcilingAgainstTheFullAlbumDespiteAQueuedDelete() = runTest {
        sync.addAlbumPhoto(treeId, "profile-1", "I1", loaderPhoto(1))
        sync.addAlbumPhoto(treeId, "profile-1", "I1", loaderPhoto(2))
        store.saveJob(deleteJob("photo-2"))
        enqueueAlbum(1, 2)
        val album = loader().list(treeId, "profile-1", "I1")
        assertEquals(0, album.removed)
        assertEquals(listOf("photo-1", "photo-2"), album.entries.map { it.photo.id })
    }
}
