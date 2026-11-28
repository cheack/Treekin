package app.familygem.geni.sync

import app.familygem.geni.FakeTreeHost
import app.familygem.geni.Fixtures
import app.familygem.geni.api.GeniDate
import app.familygem.geni.api.GeniPhoto
import app.familygem.geni.api.parsePhoto
import app.familygem.geni.api.parsePhotos
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.PhotoDetails
import app.familygem.geni.map.PhotoField
import kotlinx.coroutines.test.runTest
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Media
import org.folg.gedcom.model.Person
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Title, description and date of Geni photos in the tree: refresh without downloads, shared photos, local edits kept. */
class PhotoDetailsSyncTest {

    private val host = FakeTreeHost()
    private val store = InMemorySyncStore()
    private val gedcom = Gedcom()
    private val downloads = mutableListOf<String>()
    private val sync = PhotoSync(store, host) { url, target ->
        downloads += url
        target.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3))
        "image/jpeg"
    }
    private val treeId: Int

    init {
        for ((id, geniId) in listOf("I1" to "profile-1", "I2" to "profile-2")) gedcom.addPerson(Person().apply { this.id = id; GeniMapper.setGeniId(this, geniId) })
        treeId = kotlinx.coroutines.runBlocking { host.createTree("t", gedcom) }
    }

    private fun person(id: String) = gedcom.people.single { it.id == id }
    private fun mediaOf(personId: String, photoId: String = "photo-2243") =
        person(personId).media.single { GeniMapper.getTag(it, PhotoSync.PHOTO_TAG) == photoId }

    private fun photo(id: String = "photo-2243", title: String? = null, description: String? = null, date: GeniDate? = null, known: Boolean = true) =
        GeniPhoto(id, title, "image/jpeg", mapOf("medium" to "https://photos.example/$id/medium.jpg?hash=H"), null, null, description, date, known)

    private val may1950 = GeniDate(1950, 5, 3, true)

    /** The photo downloaded for both people, as two profiles tagged in it end up in the tree. */
    private suspend fun downloadedForBoth(photo: GeniPhoto) {
        assertTrue(sync.addAlbumPhoto(treeId, "profile-1", "I1", photo))
        assertTrue(sync.addAlbumPhoto(treeId, "profile-2", "I2", photo))
    }

    @Test
    fun fixtureParsesDescriptionAndDate() {
        val photos = parsePhotos(Fixtures.read("photo-details-list.json"))
        val wedding = photos.single { it.id == "photo-2243" }
        assertEquals(PhotoDetails("Wedding day", "First line\nSecond line", "ABT 3 MAY 1950"), PhotoDetails.of(wedding))
        // Geni leaves cleared fields out: a photo with no description and no date has neither
        val plain = photos.single { it.id == "photo-2242" }
        assertEquals(PhotoDetails("p3", null, "1960"), PhotoDetails.of(plain))
        assertTrue(plain.detailsKnown)
    }

    @Test
    fun aDownloadStoresTheDetailsOfGeni() = runTest {
        assertTrue(sync.addAlbumPhoto(treeId, "profile-1", "I1", photo(title = "Wedding day", description = "Two\nlines", date = may1950)))
        val media = mediaOf("I1")
        assertEquals("Wedding day", media.title)
        assertEquals(PhotoDetails("Wedding day", "Two\nlines", "ABT 3 MAY 1950"), PhotoDetails.shown(media))
        assertTrue(PhotoDetails.isSynced(media))
        assertFalse(PhotoDetails.hasOwnTitle(media))
    }

    @Test
    fun aRefreshUpdatesBothProfilesOfASharedPhotoWithoutDownloading() = runTest {
        downloadedForBoth(photo(title = "Old", description = "Before"))
        val before = downloads.size
        val changed = sync.refreshDetails(treeId, listOf(photo(title = "New title", description = "After", date = may1950)))
        assertEquals(1, changed)
        for (id in listOf("I1", "I2")) assertEquals(PhotoDetails("New title", "After", "ABT 3 MAY 1950"), PhotoDetails.shown(mediaOf(id)))
        assertEquals(before, downloads.size)
    }

    @Test
    fun aClearedFieldOnGeniClearsTheLocalOne() = runTest {
        downloadedForBoth(photo(title = "T", description = "D", date = may1950))
        assertEquals(1, sync.refreshDetails(treeId, listOf(photo())))
        assertEquals(PhotoDetails.EMPTY, PhotoDetails.shown(mediaOf("I1")))
        assertNull(mediaOf("I2").title)
        // A repeat of the same refresh changes nothing
        assertEquals(0, sync.refreshDetails(treeId, listOf(photo())))
    }

    @Test
    fun aMugshotWithoutDetailsLeavesTheValuesAlone() = runTest {
        downloadedForBoth(photo(title = "T", description = "D", date = may1950))
        assertEquals(0, sync.refreshDetails(treeId, listOf(photo(known = false))))
        assertEquals(PhotoDetails("T", "D", "ABT 3 MAY 1950"), PhotoDetails.shown(mediaOf("I1")))
    }

    @Test
    fun anOldLinkWithoutDetailsGetsThemAtTheFirstListing() = runTest {
        sync.addAlbumPhoto(treeId, "profile-1", "I1", photo(title = "Seen"))
        val media = mediaOf("I1")
        // As a link made before details were synced: no stored description, date or marker
        for (tag in listOf(PhotoDetails.DESCRIPTION_TAG, PhotoDetails.DATE_TAG, PhotoDetails.TITLE_TAG, PhotoDetails.SYNCED_TAG)) GeniMapper.setTag(media, tag, null)
        assertFalse(PhotoDetails.isSynced(media))
        assertEquals(1, sync.refreshDetails(treeId, listOf(photo(title = "Seen", description = "Now", date = may1950))))
        assertEquals(PhotoDetails("Seen", "Now", "ABT 3 MAY 1950"), PhotoDetails.shown(media))
        assertTrue(PhotoDetails.isSynced(media))
    }

    @Test
    fun aTitleOfTheUserSurvivesARefreshAndStaysTheirs() = runTest {
        assertTrue(sync.addAlbumPhoto(treeId, "profile-1", "I1", photo(title = "Geni title")))
        val media = mediaOf("I1")
        media.title = "My own title"
        assertTrue(PhotoDetails.hasOwnTitle(media))
        sync.refreshDetails(treeId, listOf(photo(title = "Geni title 2", description = "D")))
        assertEquals("My own title", media.title)
        assertEquals("Geni title 2", PhotoDetails.syncedTitle(media))
        assertEquals("D", PhotoDetails.shown(media).description)
        assertTrue(PhotoDetails.hasOwnTitle(media))
    }

    @Test
    fun anExistingLocalTitleOfAnOldLinkIsNotOverwrittenOnUpgrade() = runTest {
        sync.addAlbumPhoto(treeId, "profile-1", "I1", photo())
        val media = mediaOf("I1")
        GeniMapper.setTag(media, PhotoDetails.SYNCED_TAG, null)
        media.title = "Granny in 1950"
        sync.refreshDetails(treeId, listOf(photo(title = "IMG_0042")))
        assertEquals("Granny in 1950", media.title)
        assertTrue(PhotoDetails.hasOwnTitle(media))
    }

    @Test
    fun aPendingEditOfThePhotoIsNotOverwrittenByARefresh() = runTest {
        downloadedForBoth(photo(title = "Before"))
        sync.applyOwnEdit(treeId, "photo-2243", PhotoDetails("Mine", "My text", null), PhotoField.entries)
        store.saveJob(UploadJob(treeId = treeId, key = "updphoto:photo-2243", kind = JobKind.UPDATE_PHOTO, geniId = "photo-2243", params = emptyList(), label = "x", localHash = 0))
        assertEquals(0, sync.refreshDetails(treeId, listOf(photo(title = "Before"))))
        assertEquals(PhotoDetails("Mine", "My text", null), PhotoDetails.shown(mediaOf("I1")))
        assertEquals(PhotoDetails("Mine", "My text", null), PhotoDetails.shown(mediaOf("I2")))
        // A dismissed edit is given up: Geni's values come back (the title stays as the user's own)
        val job = store.jobs(treeId).single()
        store.saveJob(job.copy(state = JobState.DISMISSED))
        sync.refreshDetails(treeId, listOf(photo(title = "Before", description = "Geni text")))
        assertEquals("Geni text", PhotoDetails.shown(mediaOf("I1")).description)
    }

    @Test
    fun anOwnEditShowsAtOnceOnEveryProfileAndKeepsTheSyncedValues() = runTest {
        downloadedForBoth(photo(title = "Before", description = "D"))
        assertTrue(sync.applyOwnEdit(treeId, "photo-2243", PhotoDetails("After", "D", "1960"), listOf(PhotoField.TITLE, PhotoField.DATE)))
        for (id in listOf("I1", "I2")) {
            val media = mediaOf(id)
            assertEquals(PhotoDetails("After", "D", "1960"), PhotoDetails.shown(media))
            assertEquals("Before", PhotoDetails.syncedTitle(media))
        }
        assertFalse(sync.applyOwnEdit(treeId, "photo-2243", PhotoDetails("After", "D", "1960"), listOf(PhotoField.TITLE)))
    }

    @Test
    fun confirmedValuesReplaceTheLocalOnesOnBothProfiles() = runTest {
        downloadedForBoth(photo(title = "Before"))
        sync.applyOwnEdit(treeId, "photo-2243", PhotoDetails("Typed  ", null, null), listOf(PhotoField.TITLE))
        assertTrue(sync.applyConfirmed(treeId, photo(title = "Typed", description = "Confirmed")))
        for (id in listOf("I1", "I2")) {
            assertEquals(PhotoDetails("Typed", "Confirmed", null), PhotoDetails.shown(mediaOf(id)))
            assertFalse(PhotoDetails.hasOwnTitle(mediaOf(id)))
        }
        assertFalse(sync.applyConfirmed(treeId, photo(known = false)))
    }

    @Test
    fun aDeletedPhotoNeverGetsDetails() = runTest {
        downloadedForBoth(photo(title = "T"))
        sync.guard.confirm(treeId, "photo-2243")
        assertEquals(0, sync.refreshDetails(treeId, listOf(photo(title = "Changed"))))
        assertFalse(sync.applyConfirmed(treeId, photo(title = "Changed")))
        assertEquals("T", mediaOf("I1").title)
    }

    @Test
    fun aSyncedTitleDoesNotKeepADeletedAlbumPhotoOnTheDevice() = runTest {
        // Titled by Geni, not by the user: when Geni stops listing it, it goes like any album photo
        sync.addAlbumPhoto(treeId, "profile-1", "I1", photo("photo-8", title = "From Geni"))
        sync.addAlbumPhoto(treeId, "profile-1", "I1", photo("photo-9", title = "Mine later"))
        mediaOf("I1", "photo-9").title = "Changed by the user"
        assertEquals(1, sync.reconcileAlbum(treeId, "profile-1", "I1", emptyList()))
        assertEquals(listOf("photo-9"), person("I1").media.map { GeniMapper.getTag(it, PhotoSync.PHOTO_TAG) })
        assertFalse(File(host.mediaDir(treeId), "geni-photo-8.jpg").exists())
        assertTrue(File(host.mediaDir(treeId), "geni-photo-9.jpg").exists())
    }

    @Test
    fun aMediaWithoutAPhotoTagIsNotTouched() = runTest {
        person("I1").addMedia(Media().apply { file = "own.jpg"; title = "Own" })
        sync.refreshDetails(treeId, listOf(photo(title = "Other")))
        assertEquals("Own", person("I1").media.single().title)
    }
}
