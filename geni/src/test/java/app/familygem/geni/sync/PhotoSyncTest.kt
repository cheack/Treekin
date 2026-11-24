package app.familygem.geni.sync

import app.familygem.geni.FakeTreeHost
import app.familygem.geni.api.GeniPhoto
import app.familygem.geni.api.GeniProfile
import app.familygem.geni.map.GeniMapper
import kotlinx.coroutines.async
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
import java.io.IOException

class PhotoSyncTest {

    private val host = FakeTreeHost()
    private val store = InMemorySyncStore()
    private val gedcom = Gedcom()
    private val downloads = mutableListOf<String>()
    private var failNext = false
    private var bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)

    private val sync = PhotoSync(store, host) { url, target ->
        downloads += url
        if (failNext) throw IOException("network")
        target.writeBytes(bytes)
        "image/jpeg"
    }

    private val treeId: Int

    init {
        gedcom.addPerson(Person().apply { id = "I1"; GeniMapper.setGeniId(this, "profile-1") })
        treeId = kotlinx.coroutines.runBlocking { host.createTree("t", gedcom) }
    }

    private fun profile(photoId: String?, query: String = "H.1") = GeniProfile(
        id = "profile-1",
        mugshotUrls = photoId?.let {
            mapOf(
                "large" to "https://photos.example/$it/large.jpg?hash=$query",
                "medium" to "https://photos.example/$it/medium.jpg?hash=$query",
                "url" to "https://api.example/$it"
            )
        }
    )

    private val person get() = gedcom.people.single()
    private fun dir() = host.mediaDir(treeId)

    @Test
    fun aDownloadThatSpansTheConfirmedDeletionLeavesNothingBehind() = runTest {
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resume = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = PhotoSync(store, host, sync.guard) { _, target ->
            started.complete(Unit)
            resume.await()
            target.writeBytes(bytes)
            "image/jpeg"
        }
        val result = this.async { slow.sync(treeId, profile("photo-7"), "I1") }
        started.await()
        sync.guard.confirm(treeId, "photo-7")
        resume.complete(Unit)
        assertFalse(result.await())
        assertTrue(person.media.isEmpty())
        assertTrue(store.photoLinks(treeId).isEmpty())
        assertTrue(dir().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aPhotoConfirmedDeletedIsNotDownloadedAtAll() = runTest {
        sync.guard.confirm(treeId, "photo-7")
        assertFalse(sync.sync(treeId, profile("photo-7"), "I1"))
        assertFalse(sync.addAlbumPhoto(treeId, "profile-1", "I1", GeniPhoto("photo-7", null, "image/jpeg", mapOf("medium" to "https://p.example/7.jpg"), null, null)))
        assertTrue(downloads.isEmpty())
    }

    @Test
    fun newPhotoIsDownloadedAndAttachedAsPrimary() = runTest {
        assertTrue(sync.sync(treeId, profile("photo-7"), "I1"))
        assertEquals(listOf("https://photos.example/photo-7/medium.jpg?hash=H.1"), downloads)
        val media = person.media.single()
        assertEquals("geni-photo-7.jpg", media.file)
        assertEquals("Y", media.primary)
        assertEquals("photo-7", GeniMapper.getTag(media, PhotoSync.PHOTO_TAG))
        assertTrue(File(dir(), "geni-photo-7.jpg").isFile)
        val link = store.photoLinks(treeId, "profile-1").single()
        assertEquals(PhotoLink(treeId, "profile-1", "photo-7", null, true, "https://photos.example/photo-7/medium.jpg", "geni-photo-7.jpg", PhotoState.DOWNLOADED), link)
    }

    @Test
    fun unchangedPhotoIsNotDownloadedAgainEvenWithNewSignedUrl() = runTest {
        sync.sync(treeId, profile("photo-7", "H.1"), "I1")
        assertFalse(sync.sync(treeId, profile("photo-7", "H.2"), "I1"))
        assertEquals(1, downloads.size)
        assertEquals(1, person.media.size)
    }

    @Test
    fun changedMainPhotoBecomesPrimaryAndOldOneStaysAsAlbumPhoto() = runTest {
        sync.sync(treeId, profile("photo-7"), "I1")
        assertTrue(sync.sync(treeId, profile("photo-8"), "I1"))
        assertEquals(listOf("geni-photo-7.jpg", "geni-photo-8.jpg"), person.media.map { it.file })
        assertEquals(listOf(null, "Y"), person.media.map { it.primary })
        assertTrue(File(dir(), "geni-photo-7.jpg").isFile)
        assertTrue(File(dir(), "geni-photo-8.jpg").isFile)
        assertEquals(mapOf("photo-7" to false, "photo-8" to true), store.photoLinks(treeId).associate { it.geniPhotoId to it.isMain })
    }

    @Test
    fun albumPhotoBecomingMainIsNotDownloadedAgain() = runTest {
        sync.sync(treeId, profile("photo-7"), "I1")
        val album = GeniPhoto("photo-8", null, null, mapOf("medium" to "https://photos.example/photo-8/medium.jpg"), null, null)
        assertTrue(sync.addAlbumPhoto(treeId, "profile-1", "I1", album))
        assertEquals(2, downloads.size)
        assertEquals(null, person.media.single { GeniMapper.getTag(it, PhotoSync.PHOTO_TAG) == "photo-8" }.primary)
        assertTrue(sync.sync(treeId, profile("photo-8"), "I1"))
        assertEquals(2, downloads.size)
        assertEquals(2, person.media.size)
        assertEquals("Y", person.media.single { GeniMapper.getTag(it, PhotoSync.PHOTO_TAG) == "photo-8" }.primary)
        assertEquals(mapOf("photo-7" to false, "photo-8" to true), store.photoLinks(treeId).associate { it.geniPhotoId to it.isMain })
    }

    @Test
    fun albumPhotoIsAddedOnceAsPlainMedia() = runTest {
        val album = GeniPhoto("photo-9", "Wedding", null, mapOf("medium" to "https://photos.example/photo-9/medium.jpg"), null, null)
        assertTrue(sync.addAlbumPhoto(treeId, "profile-1", "I1", album))
        assertFalse(sync.addAlbumPhoto(treeId, "profile-1", "I1", album))
        assertEquals(1, downloads.size)
        val media = person.media.single()
        assertEquals("geni-photo-9.jpg", media.file)
        assertNull(media.primary)
        assertEquals(PhotoLink(treeId, "profile-1", "photo-9", null, false, "https://photos.example/photo-9/medium.jpg", "geni-photo-9.jpg", PhotoState.DOWNLOADED), store.photoLinks(treeId).single())
    }

    @Test
    fun mainPhotoGoneLeavesAlbumPhotos() = runTest {
        sync.sync(treeId, profile("photo-7"), "I1")
        val album = GeniPhoto("photo-9", null, null, mapOf("medium" to "https://photos.example/photo-9/medium.jpg"), null, null)
        sync.addAlbumPhoto(treeId, "profile-1", "I1", album)
        assertTrue(sync.sync(treeId, profile(null), "I1"))
        assertEquals(listOf("geni-photo-9.jpg"), person.media.map { it.file })
        assertEquals(listOf("photo-9"), store.photoLinks(treeId).map { it.geniPhotoId })
    }

    @Test
    fun photoGoneOnGeniIsRemoved() = runTest {
        sync.sync(treeId, profile("photo-7"), "I1")
        assertTrue(sync.sync(treeId, profile(null), "I1"))
        assertTrue(person.media.isEmpty())
        assertFalse(File(dir(), "geni-photo-7.jpg").exists())
        assertTrue(store.photoLinks(treeId).isEmpty())
    }

    @Test
    fun noPhotoAndNoLinkChangesNothing() = runTest {
        person.addMedia(Media().apply { file = "mine.jpg" })
        assertFalse(sync.sync(treeId, profile(null), "I1"))
        assertEquals(listOf("mine.jpg"), person.media.map { it.file })
    }

    @Test
    fun failedDownloadKeepsOldPhoto() = runTest {
        sync.sync(treeId, profile("photo-7"), "I1")
        failNext = true
        val error = runCatching { sync.sync(treeId, profile("photo-8"), "I1") }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(listOf("geni-photo-7.jpg"), person.media.map { it.file })
        assertTrue(File(dir(), "geni-photo-7.jpg").isFile)
        assertEquals(listOf("photo-7"), store.photoLinks(treeId).map { it.geniPhotoId })
        assertEquals(listOf("geni-photo-7.jpg"), dir().list()!!.toList())
    }

    @Test
    fun nonImageIsRejected() = runTest {
        bytes = "<html>denied</html>".toByteArray()
        val error = runCatching { sync.sync(treeId, profile("photo-7"), "I1") }.exceptionOrNull()
        assertTrue(error is IOException)
        assertTrue(person.media.isEmpty())
        assertTrue(store.photoLinks(treeId).isEmpty())
        assertEquals(emptyList<String>(), dir().list()!!.toList())
    }

    @Test
    fun localMediaStaysAndGeniPhotoBecomesPrimary() = runTest {
        person.addMedia(Media().apply { file = "mine.jpg"; primary = "Y" })
        sync.sync(treeId, profile("photo-7"), "I1")
        assertEquals(listOf("mine.jpg", "geni-photo-7.jpg"), person.media.map { it.file })
        assertNull(person.media[0].primary)
        assertEquals("Y", person.media[1].primary)
    }

    @Test
    fun personWithOtherGeniIdIsIgnored() = runTest {
        GeniMapper.setGeniId(person, "profile-2")
        assertFalse(sync.sync(treeId, profile("photo-7"), "I1"))
        assertTrue(person.media.isEmpty())
        assertTrue(store.photoLinks(treeId).isEmpty())
    }

    @Test
    fun missingFileIsDownloadedAgain() = runTest {
        sync.sync(treeId, profile("photo-7"), "I1")
        File(dir(), "geni-photo-7.jpg").delete()
        assertTrue(sync.sync(treeId, profile("photo-7"), "I1"))
        assertEquals(2, downloads.size)
        assertEquals(1, person.media.size)
    }

    @Test
    fun repairingMainPhotoViaAlbumKeepsMainStatus() = runTest {
        sync.sync(treeId, profile("photo-7"), "I1")
        File(dir(), "geni-photo-7.jpg").delete()
        val album = GeniPhoto("photo-7", null, null, mapOf("medium" to "https://photos.example/photo-7/medium.jpg"), null, null)
        sync.addAlbumPhoto(treeId, "profile-1", "I1", album)
        assertTrue(store.photoLinks(treeId, "profile-1").single().isMain)
        assertEquals("Y", person.media.single().primary)
    }

    @Test
    fun reusedMediaGetsTheNewFileName() = runTest {
        sync.sync(treeId, profile("photo-7"), "I1")
        File(dir(), "geni-photo-7.jpg").delete()
        val album = GeniPhoto("photo-7", null, null, mapOf("medium" to "https://photos.example/photo-7/medium.png"), null, null)
        val png = PhotoSync(store, host) { _, target -> target.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1)); "image/png" }
        png.addAlbumPhoto(treeId, "profile-1", "I1", album)
        assertEquals("geni-photo-7.png", person.media.single().file)
        assertEquals("geni-photo-7.png", store.photoLinks(treeId).single().fileName)
    }

    @Test
    fun sharedFileStaysWhileAnotherProfileUsesIt() = runTest {
        gedcom.addPerson(Person().apply { id = "I2"; GeniMapper.setGeniId(this, "profile-2") })
        sync.sync(treeId, profile("photo-7"), "I1")
        sync.sync(treeId, profile("photo-7").copy(id = "profile-2"), "I2")
        assertTrue(sync.sync(treeId, profile(null), "I1"))
        assertTrue(File(dir(), "geni-photo-7.jpg").isFile)
        assertTrue(sync.sync(treeId, profile(null).copy(id = "profile-2"), "I2"))
        assertFalse(File(dir(), "geni-photo-7.jpg").exists())
    }

    @Test
    fun removeLocalDropsMediaLinkAndFile() = runTest {
        gedcom.addPerson(Person().apply { id = "I2"; GeniMapper.setGeniId(this, "profile-2") })
        val owner = gedcom.people.first { it.id == "I1" }
        owner.addMedia(Media().apply { file = "mine.jpg" })
        sync.sync(treeId, profile("photo-7"), "I1")
        sync.sync(treeId, profile("photo-7").copy(id = "profile-2"), "I2")
        assertTrue(sync.removeLocal(treeId, "profile-1", "I1", "photo-7"))
        assertEquals(listOf("mine.jpg"), owner.media.map { it.file })
        assertEquals(listOf("profile-2"), store.photoLinks(treeId).map { it.geniProfileId })
        assertTrue(File(dir(), "geni-photo-7.jpg").isFile) // Another profile's link still uses it
        assertTrue(sync.removeLocal(treeId, "profile-2", "I2", "photo-7"))
        assertFalse(File(dir(), "geni-photo-7.jpg").exists())
        assertFalse(sync.removeLocal(treeId, "profile-1", "I1", "photo-7"))
    }

    private fun albumPhoto(n: Int) = GeniPhoto("photo-$n", null, null, mapOf("medium" to "https://p.example/$n/m.jpg"), null, null)

    private suspend fun addAlbum(vararg ns: Int) = ns.forEach { sync.addAlbumPhoto(treeId, "profile-1", "I1", albumPhoto(it)) }

    @Test
    fun reconcileRemovesAlbumPhotoMissingOnGeni() = runTest {
        addAlbum(1, 2)
        assertEquals(1, sync.reconcileAlbum(treeId, "profile-1", "I1", listOf(albumPhoto(1))))
        assertEquals(listOf("geni-photo-1.jpg"), person.media.map { it.file })
        assertEquals(listOf("photo-1"), store.photoLinks(treeId).map { it.geniPhotoId })
        assertFalse(File(dir(), "geni-photo-2.jpg").exists())
    }

    @Test
    fun reconcileKeepsMainPhoto() = runTest {
        sync.sync(treeId, profile("photo-7"), "I1")
        assertEquals(0, sync.reconcileAlbum(treeId, "profile-1", "I1", emptyList()))
        assertEquals(1, person.media.size)
        assertEquals(1, store.photoLinks(treeId).size)
    }

    @Test
    fun reconcileKeepsPhotoWithLocalTitleNoteOrSource() = runTest {
        addAlbum(1, 2, 3)
        person.media.single { it.file == "geni-photo-1.jpg" }.title = "Wedding"
        person.media.single { it.file == "geni-photo-2.jpg" }.addNote(org.folg.gedcom.model.Note().apply { value = "mine" })
        person.media.single { it.file == "geni-photo-3.jpg" }.addNoteRef(org.folg.gedcom.model.NoteRef().apply { ref = "N1" })
        assertEquals(0, sync.reconcileAlbum(treeId, "profile-1", "I1", emptyList()))
        assertEquals(3, person.media.size)
        assertEquals(3, store.photoLinks(treeId).size)
        assertTrue(File(dir(), "geni-photo-2.jpg").isFile)
    }

    @Test
    fun keepsLinkAndFileWhenTaggedMediaIsNotInline() = runTest {
        addAlbum(1, 2)
        // "Make shared media": the media left the person, which holds only a reference
        person.media = person.media.filter { it.file != "geni-photo-1.jpg" }.toMutableList()
        assertEquals(0, sync.reconcileAlbum(treeId, "profile-1", "I1", listOf(albumPhoto(2))))
        assertFalse(sync.removeLocal(treeId, "profile-1", "I1", "photo-1"))
        assertEquals(setOf("photo-1", "photo-2"), store.photoLinks(treeId).map { it.geniPhotoId }.toSet())
        assertTrue(File(dir(), "geni-photo-1.jpg").isFile)
    }

    @Test
    fun reconcileKeepsPhotosStillInAlbum() = runTest {
        addAlbum(1, 2)
        assertEquals(0, sync.reconcileAlbum(treeId, "profile-1", "I1", listOf(albumPhoto(1), albumPhoto(2))))
        assertEquals(2, person.media.size)
    }

    @Test
    fun reconcileIgnoresOtherProfiles() = runTest {
        gedcom.addPerson(Person().apply { id = "I2"; GeniMapper.setGeniId(this, "profile-2") })
        addAlbum(1)
        sync.addAlbumPhoto(treeId, "profile-2", "I2", albumPhoto(5))
        assertEquals(1, sync.reconcileAlbum(treeId, "profile-1", "I1", emptyList()))
        assertEquals(listOf("profile-2"), store.photoLinks(treeId).map { it.geniProfileId })
        assertEquals(1, gedcom.people.first { it.id == "I2" }.media.size)
    }

    @Test
    fun detectsImageHeaders() {
        fun file(vararg b: Int) = File.createTempFile("img", null).apply { writeBytes(ByteArray(b.size) { b[it].toByte() }); deleteOnExit() }
        assertTrue(PhotoSync.looksLikeImage(file(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        assertTrue(PhotoSync.looksLikeImage(file(0x47, 0x49, 0x46, 0x38, 0x39, 0x61)))
        assertTrue(PhotoSync.looksLikeImage(file(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50)))
        assertFalse(PhotoSync.looksLikeImage(file(0x3C, 0x68, 0x74, 0x6D, 0x6C)))
        assertFalse(PhotoSync.looksLikeImage(file(1, 2)))
    }
}
