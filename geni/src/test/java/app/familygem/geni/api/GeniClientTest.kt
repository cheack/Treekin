package app.familygem.geni.api

import app.familygem.geni.auth.AuthResult
import app.familygem.geni.auth.InMemoryTokenStore
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeniClientTest {

    private val server = MockWebServer()
    private val tokens = InMemoryTokenStore()
    private val profileJson = """{"id":"profile-1","first_name":"Ann","updated_at":"1700000000"}"""

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private fun TestScope.client() = GeniClient(
        baseUrl = server.url("/api/"),
        tokens = tokens,
        limiter = RateLimiter { testScheduler.currentTime },
        now = { 1_000L }
    )

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    @Test
    fun sendsBearerTokenAndParsesProfile() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(ok(profileJson))
        val profile = client().currentProfile()
        assertEquals("profile-1", profile.id)
        assertEquals("Ann", profile.firstName)
        val request = server.takeRequest()
        assertEquals("Bearer abc", request.headers["Authorization"])
        assertEquals("/api/profile", request.url.encodedPath)
    }

    @Test
    fun noTokenThrowsAuthRequiredWithoutRequest() = runTest {
        val error = runCatching { client().currentProfile() }.exceptionOrNull()
        assertTrue(error is GeniAuthRequiredException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun expiredTokenThrowsAuthRequired() = runTest {
        tokens.save(AuthResult.Success("abc", 500L))
        val error = runCatching { client().currentProfile() }.exceptionOrNull()
        assertTrue(error is GeniAuthRequiredException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun unauthorizedClearsToken() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(MockResponse.Builder().code(401).build())
        val error = runCatching { client().currentProfile() }.exceptionOrNull()
        assertTrue(error is GeniAuthRequiredException)
        assertNull(tokens.get())
    }

    @Test
    fun tooManyRequestsRetriesOnce() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(MockResponse.Builder().code(429).addHeader("X-API-Rate-Window", "10").build())
        server.enqueue(ok(profileJson))
        assertEquals("profile-1", client().currentProfile().id)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun serverErrorThrowsApiException() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(MockResponse.Builder().code(500).body("boom").build())
        val error = runCatching { client().currentProfile() }.exceptionOrNull()
        assertEquals(500, (error as GeniApiException).code)
    }

    @Test
    fun getJsonReturnsRawBodyOfPath() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(ok("""{"focus":{"id":"profile-1"}}"""))
        assertEquals("""{"focus":{"id":"profile-1"}}""", client().getJson("profile-1/immediate-family"))
        assertEquals("/api/profile-1/immediate-family", server.takeRequest().url.encodedPath)
    }

    @Test
    fun postSendsFormWithBearerToken() = runTest {
        tokens.save(AuthResult.Success("t", 10_000L))
        server.enqueue(ok(profileJson))
        client().post("profile-1/update", listOf("first_name" to "Ivan", "birth[date][year]" to "1960"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("Bearer t", request.headers["Authorization"])
        assertEquals("/api/profile-1/update", request.url.encodedPath)
        assertEquals("first_name=Ivan&birth%5Bdate%5D%5Byear%5D=1960", request.body!!.utf8())
    }

    @Test
    fun postReturnsBody() = runTest {
        tokens.save(AuthResult.Success("t", 10_000L))
        server.enqueue(ok(profileJson))
        assertEquals(profileJson, client().post("profile-1/update", listOf("occupation" to "")))
    }

    @Test
    fun post401ClearsToken() = runTest {
        tokens.save(AuthResult.Success("t", 10_000L))
        server.enqueue(MockResponse.Builder().code(401).build())
        val error = runCatching { client().post("profile-1/update", emptyList()) }.exceptionOrNull()
        assertTrue(error is GeniAuthRequiredException)
        assertNull(tokens.get())
    }

    @Test
    fun post429RetriesOnce() = runTest {
        tokens.save(AuthResult.Success("t", 10_000L))
        server.enqueue(MockResponse.Builder().code(429).addHeader("X-API-Rate-Window", "10").build())
        server.enqueue(ok(profileJson))
        assertEquals(profileJson, client().post("profile-1/update", listOf("a" to "b")))
        assertEquals(2, server.requestCount)
        server.takeRequest()
        assertEquals("a=b", server.takeRequest().body!!.utf8())
    }

    @Test
    fun rejectedCheckAfter429NeverReplays() = runTest {
        tokens.save(AuthResult.Success("t", 10_000L))
        server.enqueue(MockResponse.Builder().code(429).addHeader("X-API-Rate-Window", "10").build())
        server.enqueue(ok(profileJson))
        var checks = 0
        val error = runCatching { client().post("profile-1/add-photo", listOf("a" to "b"), beforeSend = { ++checks == 1 }) }.exceptionOrNull()
        assertTrue(error is GeniSendRejectedException)
        assertEquals(2, checks)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun rejectedCheckSendsNothing() = runTest {
        tokens.save(AuthResult.Success("t", 10_000L))
        val error = runCatching { client().addPhoto("profile-1", "a.jpg", null, byteArrayOf(1)) { false } }.exceptionOrNull()
        assertTrue(error is GeniSendRejectedException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun post400ThrowsApiException() = runTest {
        tokens.save(AuthResult.Success("t", 10_000L))
        val body = """{"error":{"type":"ApiException","message":"Invalid union status: divorced"}}"""
        server.enqueue(MockResponse.Builder().code(400).body(body).build())
        val error = runCatching { client().post("union-1/update", listOf("status" to "divorced")) }.exceptionOrNull()
        assertEquals(400, (error as GeniApiException).code)
        assertEquals(body, error.message)
    }

    @Test
    fun requestToAnotherHostIsRefused() = runTest {
        tokens.save(app.familygem.geni.auth.AuthResult.Success("secret", Long.MAX_VALUE))
        val error = runCatching { client().getJson("https://other.example/collect") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals(0, server.requestCount)
        assertTrue(runCatching { client().post("http://other.example/x", emptyList()) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun redirectToAnotherHostIsNotFollowed() = runTest {
        tokens.save(app.familygem.geni.auth.AuthResult.Success("secret", Long.MAX_VALUE))
        val other = MockWebServer().apply { start() }
        try {
            other.enqueue(ok("{}"))
            server.enqueue(MockResponse.Builder().code(307).addHeader("Location", other.url("/collect").toString()).build())
            runCatching { client().post("profile-1/update", listOf("about_me" to "private")) }
            assertEquals(0, other.requestCount) // The body never left the Geni origin
        } finally {
            other.close()
        }
    }

    @Test
    fun managedGuidsReadsAllPagesUntilEmpty() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(ok("""{"results":[{"guid":"11","name":"A"},{"guid":"12","name":"B"}],"total_count":3,"page":1}"""))
        server.enqueue(ok("""{"results":[{"guid":"13","name":"C"}],"total_count":3,"page":2}"""))
        server.enqueue(ok("""{"results":[],"total_count":3,"page":3}"""))
        assertEquals(setOf("11", "12", "13"), client().managedGuids())
        assertEquals("/api/user/managed-profiles", server.takeRequest().url.encodedPath)
        assertEquals("2", server.takeRequest().url.queryParameter("page"))
    }

    @Test
    fun managedGuidsStopsWhenAPageAddsNothingNew() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        val page = """{"results":[{"guid":"11"},{"guid":"12"}],"total_count":2}"""
        repeat(3) { server.enqueue(ok(page)) }
        assertEquals(setOf("11", "12"), client().managedGuids())
        assertEquals(2, server.requestCount)
    }

    private fun photoJson(id: String) = """{"id":"photo-$id","title":"T$id","sizes":{"thumb":"https://x/$id.jpg"}}"""

    @Test
    fun photosReadsAllPages() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(ok("""{"results":[${photoJson("1")},${photoJson("2")}],"total_count":3}"""))
        server.enqueue(ok("""{"results":[${photoJson("3")}],"total_count":3}"""))
        server.enqueue(ok("""{"results":[]}"""))
        assertEquals(listOf("photo-1", "photo-2", "photo-3"), client().photos("profile-9").map { it.id })
        assertEquals("/api/profile-9/photos", server.takeRequest().url.encodedPath)
        assertEquals("2", server.takeRequest().url.queryParameter("page"))
    }

    @Test
    fun photosStopsWhenPageAddsNothing() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        val page = """{"results":[${photoJson("1")}],"total_count":1}"""
        repeat(3) { server.enqueue(ok(page)) }
        assertEquals(1, client().photos("profile-9").size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun photosPath404ThrowsGeniApiException() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(MockResponse.Builder().code(404).body("{}").build())
        try {
            client().photos("profile-9")
            org.junit.Assert.fail("expected exception")
        } catch (e: GeniApiException) {
            assertEquals(404, e.code)
        }
    }

    @Test
    fun addPhotoSendsBase64FileNameAndTitle() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(ok(photoJson("5")))
        val bytes = ByteArray(300) { it.toByte() }
        val photo = client().addPhoto("profile-9", "a.jpg", "Title", bytes)
        assertEquals("photo-5", photo.id)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/profile-9/add-photo", request.url.encodedPath)
        val form = request.body!!.utf8().split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to java.net.URLDecoder.decode(v, "UTF-8")
        }
        assertEquals("a.jpg", form["file_name"])
        assertEquals("Title", form["title"])
        assertTrue(!form["file"]!!.contains("\n"))
        assertTrue(bytes.contentEquals(java.util.Base64.getDecoder().decode(form["file"])))
    }

    @Test
    fun addPhotoOmitsNullTitle() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(ok(photoJson("5")))
        client().addPhoto("profile-9", "a.jpg", null, byteArrayOf(1, 2))
        assertTrue(!server.takeRequest().body!!.utf8().contains("title="))
    }

    @Test
    fun deletePhotoPostsToPhotoDelete() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(ok("""{"result":"Deleted"}"""))
        client().deletePhoto("photo-2175")
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/photo-2175/delete", request.url.encodedPath)
    }

    @Test
    fun photoReadsSingle() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(ok(photoJson("7")))
        assertEquals("photo-7", client().photo("photo-7").id)
        assertEquals("/api/photo-7", server.takeRequest().url.encodedPath)
    }

    @Test
    fun deleteAuthorizationReadsAccountAndManagedGuidsWithOneLogin() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(ok(profileJson))
        server.enqueue(ok("""{"results":[{"guid":"11"}]}"""))
        server.enqueue(ok("""{"results":[]}"""))
        val client = client()
        val authorization = client.deleteAuthorization()
        assertEquals("profile-1", authorization.accountId)
        assertEquals(setOf("11"), authorization.managedGuids)
        assertEquals(listOf("Bearer abc"), List(3) { server.takeRequest().headers["Authorization"] }.distinct())
        assertTrue(client.isCurrent(authorization))
        tokens.save(AuthResult.Success("other", 10_000L))
        assertTrue(!client.isCurrent(authorization))
    }

    @Test
    fun deleteProfileChecksTheAccountThenPostsWithTheSameBearer() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(ok(profileJson))
        server.enqueue(ok("""{"result":"Deleted"}"""))
        client().deleteProfile("profile-7", "profile-1")
        assertEquals("/api/profile", server.takeRequest().url.encodedPath)
        val post = server.takeRequest()
        assertEquals("/api/profile-7/delete", post.url.encodedPath)
        assertEquals("Bearer abc", post.headers["Authorization"])
    }

    @Test
    fun deleteProfileUnderAnotherAccountSendsNoPost() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(ok(profileJson))
        val error = runCatching { client().deleteProfile("profile-7", "profile-2") }.exceptionOrNull()
        assertTrue(error is GeniAccountMismatchException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun deleteProfileWithoutConfirmationSendsNothing() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        val error = runCatching { client().deleteProfile("profile-7", null) }.exceptionOrNull()
        assertTrue(error is GeniNotConfirmedException)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun deleteProfileStopsBeforeTheReplayWhenTheLoginChanged() = runTest {
        tokens.save(AuthResult.Success("abc", 10_000L))
        server.enqueue(ok(profileJson))
        server.enqueue(MockResponse.Builder().code(429).addHeader("X-API-Rate-Window", "10").build())
        server.dispatcher.let { } // Default queue dispatcher
        val client = client()
        val error = runCatching {
            // The login changes once the 429 has been answered, before the rate-limit replay
            kotlinx.coroutines.coroutineScope {
                val call = async { client.deleteProfile("profile-7", "profile-1") }
                while (server.requestCount < 2) kotlinx.coroutines.yield()
                tokens.save(AuthResult.Success("other", 10_000L))
                call.await()
            }
        }.exceptionOrNull()
        assertTrue(error is GeniSessionChangedException)
        assertEquals(2, server.requestCount)
    }
}
