package app.familygem.geni.api

import app.familygem.geni.Fixtures
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeniPhotoTest {

    @Test
    fun parsesAlbumFixture() {
        val photos = parsePhotos(Fixtures.read("photo-list.json"))
        assertEquals(listOf("photo-2176", "photo-2175"), photos.map { it.id })
        val first = photos[0]
        assertEquals("Avatar", first.title)
        assertEquals("image/jpeg", first.contentType)
        assertEquals("1165801", first.albumId)
        assertEquals("1794854549", first.updatedAt)
        assertFalse(first.urls.containsKey("url"))
        assertTrue(first.urls.containsKey("thumb"))
        assertTrue(first.urls.containsKey("original"))
    }

    @Test
    fun parsesSinglePhoto() {
        val photo = parsePhoto(Fixtures.read("photo-single.json"))
        assertTrue(photo.id.startsWith("photo-"))
        assertFalse(photo.urls.isEmpty())
    }

    @Test
    fun parsesUploadResponse() {
        val photo = parsePhoto(Fixtures.read("photo-upload-response.json"))
        assertEquals("photo-2177", photo.id)
        assertEquals("ApiTest", photo.title)
        assertNotNull(photo.urlFor("original"))
    }

    @Test
    fun urlForPicksFirstPresentName() {
        val photo = GeniPhoto("photo-1", null, null, mapOf("small" to "s", "large" to "l"), null, null)
        assertEquals("s", photo.urlFor("thumb", "small", "large"))
        assertEquals("l", photo.urlFor("original", "large"))
        assertNull(photo.urlFor("original"))
    }

    @Test
    fun mugshotGivesPhotoIdFromUrlKey() {
        val profile = Gson().fromJson(Fixtures.read("profile-mugshot.json"), GeniProfile::class.java)
        val photo = mugshotToPhoto(profile)!!
        assertEquals("photo-2175", photo.id)
        assertFalse(photo.urls.containsKey("url"))
        assertNotNull(photo.urlFor("large"))
    }

    @Test
    fun emptyMugshotGivesNull() {
        assertNull(mugshotToPhoto(GeniProfile(id = "profile-1")))
        assertNull(mugshotToPhoto(GeniProfile(id = "profile-1", mugshotUrls = emptyMap())))
        assertNull(mugshotToPhoto(GeniProfile(id = "profile-1", mugshotUrls = mapOf("large" to "x"))))
    }

    @Test
    fun emptyResultsGivesEmptyList() {
        assertTrue(parsePhotos("""{"results":[]}""").isEmpty())
        assertTrue(parsePhotos("{}").isEmpty())
    }

    @Test
    fun parsePhotoRejectsBodyWithoutId() {
        val error = runCatching { parsePhoto("""{"result":"Deleted"}""") }.exceptionOrNull()
        assertTrue(error is GeniApiException)
        assertEquals(200, (error as GeniApiException).code)
    }

    @Test
    fun parsePhotosRejectsResultsItemWithoutId() {
        val error = runCatching {
            parsePhotos("""{"results":[{"id":"photo-1"},{"title":"no id"}]}""")
        }.exceptionOrNull()
        assertTrue(error is GeniApiException)
        assertEquals(200, (error as GeniApiException).code)
    }

    @Test
    fun parsesDescriptionAndDateOfAPhoto() {
        val photo = parsePhoto(Fixtures.read("photo-details-single.json"))
        assertEquals("Wedding day", photo.title)
        assertEquals("First line\nSecond line", photo.description)
        assertEquals(GeniDate(1950, 5, 3, true), photo.date)
        assertTrue(photo.detailsKnown)
    }

    @Test
    fun aPhotoWithoutDetailsHasNoneAndAMugshotKnowsNone() {
        val plain = parsePhoto(Fixtures.read("photo-single.json"))
        assertNull(plain.description)
        assertNull(plain.date)
        assertTrue(plain.detailsKnown)
        val mugshot = mugshotToPhoto(GeniProfile(id = "profile-1", mugshotUrls = mapOf("url" to "https://api.example/photo-7", "medium" to "m")))!!
        assertFalse(mugshot.detailsKnown)
        assertNull(mugshot.title)
    }

    @Test
    fun aRangeDateIsAcceptedByGeniButReadBackAsItsFirstDate() {
        // Sandbox 2026-11: the update answers with the range, a later read has only the first date, so photo ranges are not offered
        assertEquals("before", parsePhoto(Fixtures.read("photo-date-range-update-response.json")).date?.range)
        val readBack = parsePhoto(Fixtures.read("photo-date-range-read-back.json"))
        assertNull(readBack.date?.range)
        assertEquals(1960, readBack.date?.year)
    }
}
