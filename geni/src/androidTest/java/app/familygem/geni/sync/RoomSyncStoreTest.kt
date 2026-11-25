package app.familygem.geni.sync

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomSyncStoreTest {

    private val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, SyncDatabase::class.java).build()
    private val store = RoomSyncStore(db.dao())

    @After
    fun tearDown() = db.close()

    private fun link(geniId: String, gedcomId: String, loaded: Boolean = false) = GeniLink(1, geniId, gedcomId, loaded, null, "{}", 0)

    @Test
    fun savesAndReadsTree() = runTest {
        store.saveTree(GeniTreeInfo(1, "profile-1"))
        assertEquals(GeniTreeInfo(1, "profile-1", 3), store.tree(1))
    }

    @Test
    fun upsertsLinks() = runTest {
        store.saveLinks(listOf(link("profile-1", "I1")))
        store.saveLinks(listOf(link("profile-1", "I1", loaded = true)))
        assertEquals(listOf(link("profile-1", "I1", loaded = true)), store.links(1))
    }

    @Test
    fun deleteTreeRemovesLinks() = runTest {
        store.saveTree(GeniTreeInfo(1, "profile-1"))
        store.saveLinks(listOf(link("profile-1", "I1"), link("union-1", "F1")))
        store.deleteTree(1)
        assertNull(store.tree(1))
        assertEquals(emptyList<GeniLink>(), store.links(1))
    }

    private fun job(key: String, state: JobState = JobState.PENDING) = UploadJob(
        treeId = 1, key = key, kind = JobKind.UPDATE_PROFILE, geniId = "profile-1",
        anchors = listOf("child:I3", "partner:I1"),
        params = listOf("birth[date][day]" to "", "birth[location][place_name]" to "Kazan, \"Tatarstan\"", "first_name" to "Ivan"),
        label = "Ivan", localHash = -7, state = state, error = if (state == JobState.FAILED) "Access Denied" else null
    )

    @Test
    fun jobsRoundTrip() = runTest {
        val id = store.saveJob(job("profile:profile-1", JobState.FAILED))
        assertEquals(listOf(job("profile:profile-1", JobState.FAILED).copy(id = id)), store.jobs(1))
        store.saveJob(store.jobs(1).single().copy(state = JobState.PENDING, error = null, version = 1))
        assertEquals(JobState.PENDING, store.jobs(1).single().state)
        assertEquals(1, store.jobs(1).single().version)
        val second = store.saveJob(job("add:I5").copy(treeId = 2))
        assertEquals(listOf("profile:profile-1", "add:I5"), store.allJobs().map { it.key })
        store.deleteJob(second)
        assertEquals(emptyList<UploadJob>(), store.jobs(2))
    }

    @Test
    fun deleteTreeDeletesJobs() = runTest {
        store.saveJob(job("profile:profile-1"))
        store.deleteTree(1)
        assertEquals(emptyList<UploadJob>(), store.jobs(1))
    }

    @Test
    fun linkByGedcomId() = runTest {
        store.saveLinks(listOf(link("profile-1", "I1"), link("union-1", "F1")))
        assertEquals("union-1", store.linkByGedcomId(1, "F1")?.geniId)
        assertNull(store.linkByGedcomId(1, "F2"))
    }

    @Test
    fun migratesFromVersion1() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "geni_sync_migration_test.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { v1 ->
            v1.execSQL("CREATE TABLE IF NOT EXISTS `tree` (`treeId` INTEGER NOT NULL, `rootGeniId` TEXT NOT NULL, `prefetchDepth` INTEGER NOT NULL, PRIMARY KEY(`treeId`))")
            v1.execSQL("CREATE TABLE IF NOT EXISTS `link` (`treeId` INTEGER NOT NULL, `geniId` TEXT NOT NULL, `gedcomId` TEXT NOT NULL, `fullyLoaded` INTEGER NOT NULL, `geniUpdatedAt` TEXT, `snapshot` TEXT, `loadedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`treeId`, `geniId`))")
            v1.execSQL("INSERT INTO link VALUES (1, 'profile-1', 'I1', 1, '170', '{\"id\":\"profile-1\"}', 5)")
            v1.version = 1
        }
        val migrated = Room.databaseBuilder(context, SyncDatabase::class.java, name).addMigrations(*SyncDatabase.MIGRATIONS).build()
        try {
            val migratedStore = RoomSyncStore(migrated.dao())
            assertEquals(listOf(GeniLink(1, "profile-1", "I1", true, "170", """{"id":"profile-1"}""", 5, null, orphaned = false)), migratedStore.links(1))
            val id = migratedStore.saveJob(job("profile:profile-1"))
            assertEquals(id, migratedStore.jobs(1).single().id)
            migratedStore.savePhotoLink(photoLink("profile-1", "photo-1"))
            assertEquals(1, migratedStore.photoLinks(1).size)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun confirmingAccountOfADeleteRoundTrips() = runTest {
        val delete = job("delete:profile-2").copy(kind = JobKind.DELETE_PROFILE, geniId = "profile-2", confirmedByProfileId = "profile-1")
        val id = store.saveJob(delete)
        assertEquals(delete.copy(id = id), store.jobs(1).single())
        store.saveJob(store.jobs(1).single().copy(state = JobState.FAILED, error = "wrong account", version = 1))
        assertEquals("profile-1", store.jobs(1).single().confirmedByProfileId)
    }

    @Test
    fun migratesFromVersion4KeepingJobsWithoutAnAccount() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "geni_sync_migration_4_test.db"
        context.deleteDatabase(name)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { v4 ->
            v4.execSQL("CREATE TABLE IF NOT EXISTS `job` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `treeId` INTEGER NOT NULL, " +
                    "`key` TEXT NOT NULL, `kind` TEXT NOT NULL, `geniId` TEXT, `personGedcomId` TEXT, `familyGedcomId` TEXT, " +
                    "`anchors` TEXT NOT NULL, `params` TEXT NOT NULL, `label` TEXT NOT NULL, `localHash` INTEGER NOT NULL, " +
                    "`state` TEXT NOT NULL, `error` TEXT, `version` INTEGER NOT NULL)")
            v4.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_job_treeId_key` ON `job` (`treeId`, `key`)")
            v4.execSQL("CREATE TABLE IF NOT EXISTS `tree` (`treeId` INTEGER NOT NULL, `rootGeniId` TEXT NOT NULL, `prefetchDepth` INTEGER NOT NULL, PRIMARY KEY(`treeId`))")
            v4.execSQL("CREATE TABLE IF NOT EXISTS `link` (`treeId` INTEGER NOT NULL, `geniId` TEXT NOT NULL, `gedcomId` TEXT NOT NULL, `fullyLoaded` INTEGER NOT NULL, `geniUpdatedAt` TEXT, `snapshot` TEXT, `loadedAtMillis` INTEGER NOT NULL, `verifiedHash` INTEGER, `orphaned` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`treeId`, `geniId`))")
            v4.execSQL("CREATE TABLE IF NOT EXISTS `photo_link` (`treeId` INTEGER NOT NULL, `geniProfileId` TEXT NOT NULL, `geniPhotoId` TEXT NOT NULL, `gedcomMediaId` TEXT, `isMain` INTEGER NOT NULL, `marker` TEXT, `fileName` TEXT, `state` TEXT NOT NULL, PRIMARY KEY(`treeId`, `geniProfileId`, `geniPhotoId`))")
            v4.execSQL("INSERT INTO tree VALUES (1, 'profile-7', 3)")
            v4.execSQL("INSERT INTO job VALUES (1, 1, 'delete:profile-2', 'DELETE_PROFILE', 'profile-2', NULL, NULL, '[]', '[]', 'Anna', 0, 'PENDING', NULL, 0)")
            v4.execSQL("INSERT INTO job VALUES (2, 1, 'profile:profile-1', 'UPDATE_PROFILE', 'profile-1', NULL, NULL, '[]', '[[\"occupation\",\"Pilot\"]]', 'Ivan', 5, 'PENDING', NULL, 0)")
            v4.version = 4
        }
        val migrated = Room.databaseBuilder(context, SyncDatabase::class.java, name).addMigrations(*SyncDatabase.MIGRATIONS).build()
        try {
            assertEquals("profile-7", RoomSyncStore(migrated.dao()).tree(1)?.ownerGeniId) // A tree from before is owned by its root's account
            val jobs = RoomSyncStore(migrated.dao()).jobs(1)
            assertEquals(listOf("delete:profile-2", "profile:profile-1"), jobs.map { it.key })
            assertEquals(listOf(null, null), jobs.map { it.confirmedByProfileId }) // Older deletes need a fresh confirmation
            assertEquals(listOf("occupation" to "Pilot"), jobs[1].params)
            assertEquals(JobState.PENDING, jobs[0].state)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun feedRecordsRoundTripPruneAndLeaveWithTheTree() = runTest {
        store.saveFeedRecords(listOf(
            FeedRecord(1, FeedRecordKind.ADDED, "profile-1", "I1", null, 1_000),
            FeedRecord(1, FeedRecordKind.GONE_ON_GENI, "profile-2", null, "Ivan P", 2_000),
            FeedRecord(2, FeedRecordKind.REMOVED_FROM_DEVICE, "profile-3", null, "Anna P", 3_000)
        ), keepSinceMillis = 0)
        // The last one of a tree, kind and profile wins
        store.saveFeedRecords(listOf(FeedRecord(1, FeedRecordKind.ADDED, "profile-1", "I1", null, 1_500)), keepSinceMillis = 0)
        assertEquals(
            setOf(FeedRecord(1, FeedRecordKind.ADDED, "profile-1", "I1", null, 1_500), FeedRecord(1, FeedRecordKind.GONE_ON_GENI, "profile-2", null, "Ivan P", 2_000)),
            store.feedRecords(1, 0).toSet()
        )
        assertEquals(listOf("profile-2"), store.feedRecords(1, 1_600).map { it.geniId }) // Only from the given time
        store.saveFeedRecords(emptyList(), keepSinceMillis = 1_800) // Older records are dropped
        assertEquals(listOf("profile-2"), store.feedRecords(1, 0).map { it.geniId })
        store.deleteTree(1)
        assertTrue(store.feedRecords(1, 0).isEmpty())
        assertEquals(listOf("profile-3"), store.feedRecords(2, 0).map { it.geniId })
    }

    @Test
    fun conflictJobRoundTrips() = runTest {
        val id = store.saveJob(job("profile:profile-1", JobState.CONFLICT).copy(error = "changed on Geni meanwhile: occupation"))
        val saved = store.jobs(1).single()
        assertEquals(id, saved.id)
        assertEquals(JobState.CONFLICT, saved.state)
        assertEquals("changed on Geni meanwhile: occupation", saved.error)
    }

    @Test
    fun saveJobWithTakenKeyThrows() = runTest {
        store.saveJob(job("profile:profile-1"))
        val error = runCatching { store.saveJob(job("profile:profile-1")) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertEquals(1, store.jobs(1).size)
    }

    @Test
    fun deleteJobIfVersionKeepsNewerVersion() = runTest {
        val id = store.saveJob(job("profile:profile-1"))
        store.saveJob(store.jobs(1).single().copy(version = 1))
        assertFalse(store.deleteJobIfVersion(id, 0))
        assertEquals(1, store.jobs(1).size)
        assertTrue(store.deleteJobIfVersion(id, 1))
        assertEquals(emptyList<UploadJob>(), store.jobs(1))
    }

    @Test
    fun deleteLink() = runTest {
        store.saveLinks(listOf(link("profile-1", "I1"), link("profile-2", "I2")))
        store.deleteLink(1, "profile-1")
        assertEquals(listOf("profile-2"), store.links(1).map { it.geniId })
    }

    @Test
    fun conditionalLinkWrites() = runTest {
        store.saveLinks(listOf(link("profile-1", "I1").copy(fullyLoaded = true, loadedAtMillis = 9)))
        assertFalse(store.setVerifiedHash(1, "profile-1", "I1", "old", 5))
        assertTrue(store.setVerifiedHash(1, "profile-1", "I1", "{}", 5))
        assertEquals(5, store.link(1, "profile-1")!!.verifiedHash)
        assertFalse(store.updateSnapshot(1, "profile-1", "old", """{"id":"profile-1"}""", "77"))
        assertEquals("{}", store.link(1, "profile-1")!!.snapshot)
        assertTrue(store.updateSnapshot(1, "profile-1", "{}", """{"id":"profile-1"}""", "77"))
        val updated = store.link(1, "profile-1")!!
        assertEquals("""{"id":"profile-1"}""", updated.snapshot)
        assertEquals("77", updated.geniUpdatedAt)
        assertNull(updated.verifiedHash)
        assertEquals(9L, updated.loadedAtMillis)
        store.setOrphaned(1, "profile-1", true)
        assertTrue(store.link(1, "profile-1")!!.orphaned)
    }

    private fun photoLink(profile: String, photo: String) =
        PhotoLink(1, profile, photo, "M1", true, "/p/photo-1.jpg", "photo-1.jpg", PhotoState.DOWNLOADED)

    @Test
    fun photoLinksRoundTrip() = runTest {
        store.savePhotoLink(photoLink("profile-1", "photo-1"))
        store.savePhotoLink(photoLink("profile-2", "photo-2").copy(gedcomMediaId = null, fileName = null, state = PhotoState.PENDING_DOWNLOAD))
        store.savePhotoLink(photoLink("profile-1", "photo-1").copy(marker = "/p/new.jpg"))
        assertEquals(listOf("/p/new.jpg"), store.photoLinks(1, "profile-1").map { it.marker })
        assertEquals(2, store.photoLinks(1).size)
        assertEquals(PhotoState.PENDING_DOWNLOAD, store.photoLinks(1, "profile-2").single().state)
        store.deletePhotoLink(1, "profile-1", "photo-1")
        assertEquals(listOf("photo-2"), store.photoLinks(1).map { it.geniPhotoId })
    }

    @Test
    fun deleteTreeDeletesPhotoLinks() = runTest {
        store.savePhotoLink(photoLink("profile-1", "photo-1"))
        store.deleteTree(1)
        assertEquals(emptyList<PhotoLink>(), store.photoLinks(1))
    }

    /**
     * One observer, started before the first write and kept open, as the backlog screen keeps it:
     * each check waits (bounded) for the next matching emission of that same subscription.
     */
    @Test
    fun observedJobsFollowInsertsStateChangesAndDeletes() = kotlinx.coroutines.runBlocking<Unit> {
        val seen = kotlinx.coroutines.channels.Channel<List<UploadJob>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val observer = launch(kotlinx.coroutines.Dispatchers.Default) { store.observeJobs().collect { seen.send(it) } }
        suspend fun next(match: (List<UploadJob>) -> Boolean): List<UploadJob> = kotlinx.coroutines.withTimeout(5_000) {
            var jobs = seen.receive()
            while (!match(jobs)) jobs = seen.receive()
            jobs
        }
        try {
            assertEquals(emptyList<UploadJob>(), next { true })
            val id = store.saveJob(job("profile:profile-1"))
            assertEquals(listOf(id), next { it.size == 1 }.map { it.id })
            store.saveJob(store.jobs(1).single().copy(state = JobState.FAILED, error = "x", version = 1))
            val failed = next { it.singleOrNull()?.state == JobState.FAILED }.single()
            assertEquals(id to 1, failed.id to failed.version) // Same ID and version as stored
            store.saveJob(job("profile:profile-2").copy(treeId = 2))
            next { it.size == 2 }
            store.deleteJob(id)
            assertEquals(listOf(2), next { it.size == 1 }.map { it.treeId })
            store.deleteTree(2)
            next { it.isEmpty() }
        } finally {
            observer.cancel()
        }
    }
}
