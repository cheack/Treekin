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

/** The Geni document responses saved from the sandbox, and the calls the client makes for them. */
class GeniDocumentTest {

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
    fun parsesAPdfWithItsPagesAndOriginal() {
        val doc = parseDocument(Fixtures.read("document-pdf.json"))!!
        assertEquals("document-11416", doc.id)
        assertEquals("Probe PDF", doc.title)
        assertEquals("three pages", doc.description)
        assertEquals(3, doc.pageCount)
        assertEquals(listOf("profile-36580"), doc.tags)
        assertFalse(doc.isText)
        assertTrue(doc.fileUrl!!.contains("probe_original.pdf"))
        assertFalse(doc.urls.containsKey("url"))
    }

    @Test
    fun aTextDocumentHasNoFileToDownload() {
        val doc = parseDocument(Fixtures.read("document-text.json"))!!
        assertTrue(doc.isText)
        assertNull(doc.fileUrl) // its "original" is a placeholder icon: the text is not readable through the API
        assertEquals(1950, doc.date!!.year)
    }

    @Test
    fun anImageScanKeepsItsSizes() {
        val doc = parseDocument(Fixtures.read("document-png.json"))!!
        assertEquals("image/png", doc.contentType)
        assertTrue(doc.fileUrl!!.contains("probe-scan_original.png"))
        assertTrue(doc.urlFor("thumb") != null)
    }

    @Test
    fun aDeletedDocumentAnswersAnEmptyListNotADocument() {
        assertNull(parseDocument(Fixtures.read("document-deleted.json")))
    }

    @Test
    fun parsesAListAndAOneItemAnswerWithoutResults() {
        assertEquals(listOf("document-11421", "document-11420", "document-11416"), parseDocuments(Fixtures.read("documents-page1.json")).map { it.id })
        assertEquals(listOf("document-11424"), parseDocuments(Fixtures.read("documents-single-bare.json")).map { it.id })
        assertTrue(parseDocuments(Fixtures.read("documents-page2-empty.json")).isEmpty())
    }

    @Test
    fun documentsReadsEveryPageUntilAnEmptyOne() = runTest {
        server.enqueue(ok(Fixtures.read("documents-page1.json")))
        server.enqueue(ok(Fixtures.read("documents-page2-empty.json")))
        val documents = client().documents("profile-36580")
        assertEquals(3, documents.size)
        assertEquals("/api/profile-36580/documents", server.takeRequest().url.encodedPath)
        assertEquals("2", server.takeRequest().url.queryParameter("page"))
    }

    @Test
    fun documentsThrowsWhenAPageFailsInsteadOfReturningAPartialList() = runTest {
        server.enqueue(ok(Fixtures.read("documents-page1.json")))
        server.enqueue(MockResponse.Builder().code(500).body("down").build())
        try {
            client().documents("profile-36580")
            org.junit.Assert.fail("a failed page must not give a partial list")
        } catch (e: GeniApiException) {
            assertEquals(500, e.code)
        }
    }

    @Test
    fun addDocumentSendsBase64FileNameAndContentTypeWithTheDetails() = runTest {
        server.enqueue(ok(Fixtures.read("document-pdf.json")))
        val bytes = ByteArray(300) { it.toByte() }
        val doc = client().addDocument("profile-9", "scan.pdf", "application/pdf", "Title", "Two lines\nhere", bytes)
        assertEquals("document-11416", doc.id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/profile-9/add-document", request.url.encodedPath)
        val form = form(request.body!!.utf8())
        assertEquals(setOf("file", "file_name", "content_type", "title", "description"), form.keys)
        assertEquals("scan.pdf", form["file_name"])
        assertEquals("application/pdf", form["content_type"])
        assertEquals("Title", form["title"])
        assertEquals("Two lines\nhere", form["description"])
        assertTrue(bytes.contentEquals(java.util.Base64.getDecoder().decode(form["file"])))
    }

    @Test
    fun addDocumentOmitsAMissingTitleAndDescription() = runTest {
        server.enqueue(ok(Fixtures.read("document-pdf.json")))
        client().addDocument("profile-9", "a.pdf", "application/pdf", null, null, byteArrayOf(1, 2))
        assertEquals(setOf("file", "file_name", "content_type"), form(server.takeRequest().body!!.utf8()).keys)
    }

    @Test
    fun updateTagUntagAndDeletePostToTheDocumentRoutes() = runTest {
        server.enqueue(ok(Fixtures.read("document-pdf.json")))
        server.enqueue(ok("""{"results":[]}"""))
        server.enqueue(ok("""{"results":[]}"""))
        server.enqueue(ok("""{"result":"Deleted"}"""))
        val client = client()
        client.updateDocument("document-5", listOf("title" to "New"))
        client.tagDocument("document-5", "profile-2")
        client.untagDocument("document-5", "profile-2")
        client.deleteDocument("document-5")
        val requests = List(4) { server.takeRequest() }
        assertEquals(listOf("/api/document-5/update", "/api/document-5/tag/profile-2", "/api/document-5/untag/profile-2", "/api/document-5/delete"),
            requests.map { it.url.encodedPath })
        assertTrue(requests.all { it.method == "POST" })
        assertEquals("title=New", requests[0].body!!.utf8())
    }

    @Test
    fun documentOfADeletedOneIsNull() = runTest {
        server.enqueue(ok(Fixtures.read("document-deleted.json")))
        assertNull(client().document("document-11424"))
    }
}
