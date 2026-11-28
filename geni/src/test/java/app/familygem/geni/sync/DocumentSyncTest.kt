package app.familygem.geni.sync

import app.familygem.geni.FakeTreeHost
import app.familygem.geni.Fixtures
import app.familygem.geni.api.DocumentTypes
import app.familygem.geni.api.GeniApiException
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.PhotoDownloader
import app.familygem.geni.api.RateLimiter
import app.familygem.geni.api.parseDocument
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

/** Documents on the device: download on demand, one file for two profiles, listings, removal. */
class DocumentSyncTest {

    private val server = MockWebServer().apply { start() }
    private val tokens = InMemoryTokenStore().apply { save(AuthResult.Success("abc", Long.MAX_VALUE)) }
    private val store = InMemorySyncStore()
    private val host = FakeTreeHost()
    private val gedcom = Gedcom()
    private val treeId: Int
    private val pdf = "%PDF-1.4 three pages".toByteArray()
    private val downloads = mutableListOf<String>()

    private val documents = DocumentSync(store, host, download = { url, target ->
        downloads += url
        target.writeBytes(pdf)
        "application/pdf"
    })

    init {
        for ((id, geniId) in listOf("I1" to "profile-1", "I2" to "profile-2")) gedcom.addPerson(Person().apply { this.id = id; GeniMapper.setGeniId(this, geniId) })
        treeId = kotlinx.coroutines.runBlocking { host.createTree("t", gedcom) }
    }

    @After
    fun tearDown() = server.close()

    private fun person(id: String) = gedcom.people.single { it.id == id }
    private val doc = parseDocument(Fixtures.read("document-pdf.json"))!!
    private val mediaDir get() = host.mediaDir(treeId)
    private fun mediaOf(personId: String) = person(personId).media.filter { GeniMapper.getTag(it, DocumentSync.DOCUMENT_TAG) == doc.id }

    @Test
    fun attachDownloadsTheOriginalAndAttachesItWithItsDetails() = runTest {
        assertTrue(documents.attach(treeId, "profile-1", "I1", doc))
        assertEquals(1, downloads.size)
        assertTrue(downloads.single().contains("probe_original.pdf"))
        val media = mediaOf("I1").single()
        assertEquals("geni-document-11416.pdf", media.file)
        assertEquals("Probe PDF", media.title)
        assertEquals("three pages", PhotoDetails.shown(media).description)
        assertTrue(File(mediaDir, "geni-document-11416.pdf").readBytes().contentEquals(pdf))
        val link = store.photoLinks(treeId, "profile-1").single()
        assertEquals("document-11416", link.geniPhotoId)
        assertFalse(link.isMain)
        assertEquals(PhotoState.DOWNLOADED, link.state)
    }

    @Test
    fun theSecondProfileReusesTheFileAndHasItsOwnLink() = runTest {
        documents.attach(treeId, "profile-1", "I1", doc)
        assertTrue(documents.attach(treeId, "profile-2", "I2", doc))
        assertEquals(1, downloads.size)
        assertEquals(1, mediaOf("I2").size)
        assertEquals(setOf("profile-1", "profile-2"), store.photoLinks(treeId).map { it.geniProfileId }.toSet())
        // Attaching again changes nothing
        assertFalse(documents.attach(treeId, "profile-2", "I2", doc))
    }

    @Test
    fun aTextDocumentIsNeverDownloaded() = runTest {
        val text = parseDocument(Fixtures.read("document-text.json"))!!
        assertFalse(documents.attach(treeId, "profile-1", "I1", text))
        assertTrue(downloads.isEmpty())
        assertTrue(person("I1").media.isEmpty())
    }

    @Test
    fun aDownloadThatIsNotAPdfOrAnImageChangesNothingAndLeavesNoFile() = runTest {
        val html = DocumentSync(store, host, download = { _, target -> target.writeText("<html>not a document</html>"); "application/pdf" })
        try {
            html.attach(treeId, "profile-1", "I1", doc)
            org.junit.Assert.fail("a file that is no document must be refused")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("not a PDF"))
        }
        assertTrue(person("I1").media.isEmpty())
        assertTrue(store.photoLinks(treeId).isEmpty())
        assertEquals(emptyList<String>(), mediaDir.list()!!.toList())
    }

    private fun realDownloader(maxBytes: Long) = PhotoDownloader(maxBytes = maxBytes, allowHttp = true, accept = DocumentTypes::isFile)

    @Test
    fun anOversizedDownloadIsRefusedWithoutAFile() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/pdf").body(Buffer().write(ByteArray(500) { 0x25 })).build())
        val sync = DocumentSync(store, host, download = realDownloader(100)::download)
        val withUrl = doc.copy(urls = mapOf("original" to server.url("/big.pdf").toString()))
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
        val sync = DocumentSync(store, host, download = realDownloader(1_000)::download)
        try {
            sync.attach(treeId, "profile-1", "I1", doc.copy(urls = mapOf("original" to server.url("/x").toString())))
            org.junit.Assert.fail("html is no document")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("content type"))
        }
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun aGoodDownloadThroughTheRealDownloaderSendsNoAuthorization() = runTest {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "application/pdf").body(Buffer().write(pdf)).build())
        val sync = DocumentSync(store, host, download = realDownloader(1_000)::download)
        assertTrue(sync.attach(treeId, "profile-1", "I1", doc.copy(urls = mapOf("original" to server.url("/d.pdf").toString()))))
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun aQueuedUntagOrDeleteBlocksTheDownload() = runTest {
        store.saveJob(UploadJob(treeId = treeId, key = "doctags:document-11416:profile-1", kind = JobKind.UNTAG_DOCUMENT, geniId = "document-11416",
            personGedcomId = "I1", params = listOf("profile" to "profile-1"), label = "x", localHash = 0))
        assertFalse(documents.attach(treeId, "profile-1", "I1", doc))
        // Another profile is not affected by it
        assertTrue(documents.attach(treeId, "profile-2", "I2", doc))
    }

    @Test
    fun removeLocalKeepsTheFileWhileAnotherProfileHasIt() = runTest {
        documents.attach(treeId, "profile-1", "I1", doc)
        documents.attach(treeId, "profile-2", "I2", doc)
        assertTrue(documents.removeLocal(treeId, "profile-1", "I1", doc.id))
        assertTrue(person("I1").media.isEmpty())
        assertTrue(File(mediaDir, "geni-document-11416.pdf").isFile)
        assertTrue(documents.removeLocal(treeId, "profile-2", "I2", doc.id))
        assertFalse(File(mediaDir, "geni-document-11416.pdf").exists())
        assertTrue(store.photoLinks(treeId).isEmpty())
    }

    @Test
    fun aDocumentDeletedOnGeniLeavesEveryPersonButKeepsTheUsersOwnCopy() = runTest {
        store.saveLinks(listOf(GeniLink(treeId, "profile-1", "I1", true, null, null, 0), GeniLink(treeId, "profile-2", "I2", true, null, null, 0)))
        documents.attach(treeId, "profile-1", "I1", doc)
        documents.attach(treeId, "profile-2", "I2", doc)
        mediaOf("I2").single().title = "My own name for it" // The user's title: this copy stays
        assertEquals(2, documents.deletedOnGeni(treeId, doc.id))
        assertTrue(person("I1").media.isEmpty())
        val kept = person("I2").media.single()
        assertNull(GeniMapper.getTag(kept, DocumentSync.DOCUMENT_TAG))
        assertEquals("My own name for it", kept.title)
        assertTrue(File(mediaDir, kept.file).isFile)
    }

    @Test
    fun aDetachedJpegWithANoteIsNeverAPhotoUpload() = runTest {
        store.saveLinks(listOf(GeniLink(treeId, "profile-1", "I1", true, null, null, 0)))
        val jpegDoc = parseDocument(Fixtures.read("document-png.json"))!!.copy(urls = mapOf("original" to "https://p.example/a.jpg"), contentType = "image/jpeg")
        val sync = DocumentSync(store, host, download = { _, target -> target.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1)); "image/jpeg" })
        sync.attach(treeId, "profile-1", "I1", jpegDoc)
        person("I1").media.single().addNote(org.folg.gedcom.model.Note().apply { value = "my note" })
        sync.deletedOnGeni(treeId, jpegDoc.id)
        val kept = person("I1").media.single()
        assertNull(GeniMapper.getTag(kept, DocumentSync.DOCUMENT_TAG))
        val plan = UploadPlanner.plan(treeId, LocalTree.capture(gedcom), store.links(treeId), emptyList(), store.photoLinks(treeId), true)
        assertTrue(plan.upserts.none { it.kind == JobKind.ADD_PHOTO })
    }

    @Test
    fun reconcileRemovesOnlyDocumentsGeniNoLongerListsAndNeverAPhoto() = runTest {
        documents.attach(treeId, "profile-1", "I1", doc)
        store.savePhotoLink(PhotoLink(treeId, "profile-1", "photo-7", null, false, null, "geni-photo-7.jpg", PhotoState.DOWNLOADED))
        assertEquals(0, documents.reconcile(treeId, "profile-1", "I1", listOf(doc)))
        assertEquals(1, documents.reconcile(treeId, "profile-1", "I1", emptyList()))
        assertTrue(person("I1").media.isEmpty())
        assertEquals(listOf("photo-7"), store.photoLinks(treeId, "profile-1").map { it.geniPhotoId })
    }

    @Test
    fun theAlbumNeverRemovesADocument() = runTest {
        documents.attach(treeId, "profile-1", "I1", doc)
        val photos = PhotoSync(store, host) { _, _ -> "image/jpeg" }
        assertEquals(0, photos.reconcileAlbum(treeId, "profile-1", "I1", emptyList()))
        assertEquals(1, mediaOf("I1").size)
    }

    private fun TestScope.loader(): DocumentLoader =
        DocumentLoader(GeniClient(server.url("/api/"), tokens, RateLimiter { testScheduler.currentTime }), store, documents, host::mediaDir)

    @Test
    fun aFailedListingKeepsEveryCachedCopy() = runTest {
        documents.attach(treeId, "profile-1", "I1", doc)
        server.enqueue(MockResponse.Builder().code(500).body("down").build())
        try {
            loader().list(treeId, "profile-1", "I1")
            org.junit.Assert.fail("the listing failed")
        } catch (e: GeniApiException) {
            assertEquals(500, e.code)
        }
        assertEquals(1, mediaOf("I1").size)
        assertTrue(File(mediaDir, "geni-document-11416.pdf").isFile)
    }

    @Test
    fun anIncompleteListingWithAFailedSecondPageKeepsEveryCachedCopy() = runTest {
        documents.attach(treeId, "profile-1", "I1", doc)
        server.enqueue(MockResponse.Builder().code(200).body("""{"results":[{"id":"document-1","title":"Other"}]}""").build())
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
        documents.attach(treeId, "profile-1", "I1", doc)
        val gone = parseDocument(Fixtures.read("document-png.json"))!!
        documents.attach(treeId, "profile-1", "I1", gone.copy(urls = mapOf("original" to "https://p.example/gone.png")))
        server.enqueue(MockResponse.Builder().code(200).body(Fixtures.read("documents-page1.json")).build())
        server.enqueue(MockResponse.Builder().code(200).body(Fixtures.read("documents-page2-empty.json")).build())
        val listing = loader().list(treeId, "profile-1", "I1")
        // page1 lists document-11421 (text), 11420 (the png) and 11416 (the pdf): nothing is gone
        assertEquals(0, listing.removed)
        assertEquals(listOf(false, true, true), listing.entries.map { it.downloaded })
        assertEquals(listOf(false, true, true), listing.entries.map { it.hasFile })
    }
}
