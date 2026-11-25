package app.familygem.geni.sync

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "tree", primaryKeys = ["treeId"])
data class GeniTreeEntity(val treeId: Int, val rootGeniId: String, val prefetchDepth: Int, @ColumnInfo(defaultValue = "''") val ownerGeniId: String)

@Entity(tableName = "link", primaryKeys = ["treeId", "geniId"])
data class GeniLinkEntity(
    val treeId: Int,
    val geniId: String,
    val gedcomId: String,
    val fullyLoaded: Boolean,
    val geniUpdatedAt: String?,
    val snapshot: String?,
    val loadedAtMillis: Long,
    val verifiedHash: Int?,
    @ColumnInfo(defaultValue = "0") val orphaned: Boolean
)

/** Upload job; anchors and params are JSON. */
@Entity(tableName = "job", indices = [Index(value = ["treeId", "key"], unique = true)])
data class UploadJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val treeId: Int,
    val key: String,
    val kind: String,
    val geniId: String?,
    val personGedcomId: String?,
    val familyGedcomId: String?,
    val anchors: String,
    val params: String,
    val label: String,
    val localHash: Int,
    val state: String,
    val error: String?,
    val version: Int,
    val confirmedByProfileId: String?
)

@Entity(tableName = "photo_link", primaryKeys = ["treeId", "geniProfileId", "geniPhotoId"])
data class PhotoLinkEntity(
    val treeId: Int,
    val geniProfileId: String,
    val geniPhotoId: String,
    val gedcomMediaId: String?,
    val isMain: Boolean,
    val marker: String?,
    val fileName: String?,
    val state: String
)

@Entity(tableName = "feed_event", primaryKeys = ["treeId", "kind", "geniId"])
data class FeedRecordEntity(val treeId: Int, val kind: String, val geniId: String, val gedcomId: String?, val name: String?, val atMillis: Long)

@Dao
interface SyncDao {
    @Query("SELECT * FROM tree WHERE treeId = :treeId")
    suspend fun tree(treeId: Int): GeniTreeEntity?

    @Upsert
    suspend fun saveTree(tree: GeniTreeEntity)

    @Query("UPDATE tree SET rootGeniId = :newRoot WHERE treeId = :treeId AND rootGeniId = :oldRoot")
    suspend fun updateRoot(treeId: Int, oldRoot: String, newRoot: String): Int

    @Query("SELECT * FROM tree")
    suspend fun trees(): List<GeniTreeEntity>

    @Query("SELECT * FROM link WHERE treeId = :treeId AND geniId = :geniId")
    suspend fun link(treeId: Int, geniId: String): GeniLinkEntity?

    @Query("SELECT * FROM link WHERE treeId = :treeId")
    suspend fun links(treeId: Int): List<GeniLinkEntity>

    @Query("SELECT * FROM link WHERE treeId = :treeId AND geniId LIKE 'union-%'")
    suspend fun unionLinks(treeId: Int): List<GeniLinkEntity>

    @Upsert
    suspend fun saveLinks(links: List<GeniLinkEntity>)

    @Query("DELETE FROM link WHERE treeId = :treeId")
    suspend fun deleteLinks(treeId: Int)

    @Query("DELETE FROM tree WHERE treeId = :treeId")
    suspend fun deleteTreeRow(treeId: Int)

    @Query("UPDATE link SET verifiedHash = :hash WHERE treeId = :treeId AND geniId = :geniId AND gedcomId = :gedcomId AND snapshot IS :snapshot")
    suspend fun setVerifiedHash(treeId: Int, geniId: String, gedcomId: String, snapshot: String?, hash: Int): Int

    @Query("UPDATE link SET snapshot = :snapshot, geniUpdatedAt = :geniUpdatedAt, verifiedHash = NULL WHERE treeId = :treeId AND geniId = :geniId AND snapshot IS :expected")
    suspend fun updateSnapshot(treeId: Int, geniId: String, expected: String?, snapshot: String, geniUpdatedAt: String?): Int

    @Query("UPDATE link SET orphaned = :orphaned WHERE treeId = :treeId AND geniId = :geniId")
    suspend fun setOrphaned(treeId: Int, geniId: String, orphaned: Boolean)

    @Query("DELETE FROM link WHERE treeId = :treeId AND geniId = :geniId")
    suspend fun deleteLink(treeId: Int, geniId: String)

    @Query("SELECT * FROM link WHERE treeId = :treeId AND gedcomId = :gedcomId LIMIT 1")
    suspend fun linkByGedcomId(treeId: Int, gedcomId: String): GeniLinkEntity?

    @Query("SELECT * FROM job WHERE treeId = :treeId ORDER BY id")
    suspend fun jobs(treeId: Int): List<UploadJobEntity>

    @Query("SELECT * FROM job ORDER BY id")
    suspend fun allJobs(): List<UploadJobEntity>

    @Query("SELECT * FROM job ORDER BY id")
    fun observeJobs(): Flow<List<UploadJobEntity>>

    @Upsert
    suspend fun saveJob(job: UploadJobEntity): Long

    @Query("DELETE FROM job WHERE id = :id")
    suspend fun deleteJob(id: Long)

    @Query("DELETE FROM job WHERE id = :id AND version = :version")
    suspend fun deleteJobIfVersion(id: Long, version: Int): Int

    @Query("DELETE FROM job WHERE treeId = :treeId")
    suspend fun deleteJobs(treeId: Int)

    @Query("SELECT * FROM photo_link WHERE treeId = :treeId")
    suspend fun photoLinks(treeId: Int): List<PhotoLinkEntity>

    @Query("SELECT * FROM photo_link WHERE treeId = :treeId AND geniProfileId = :geniProfileId")
    suspend fun photoLinks(treeId: Int, geniProfileId: String): List<PhotoLinkEntity>

    @Upsert
    suspend fun savePhotoLink(link: PhotoLinkEntity)

    @Query("DELETE FROM photo_link WHERE treeId = :treeId AND geniProfileId = :geniProfileId AND geniPhotoId = :geniPhotoId")
    suspend fun deletePhotoLink(treeId: Int, geniProfileId: String, geniPhotoId: String)

    @Query("DELETE FROM photo_link WHERE treeId = :treeId")
    suspend fun deletePhotoLinks(treeId: Int)

    @Upsert
    suspend fun saveFeedRecords(records: List<FeedRecordEntity>)

    @Query("DELETE FROM feed_event WHERE atMillis < :sinceMillis")
    suspend fun pruneFeedRecords(sinceMillis: Long)

    @Query("SELECT * FROM feed_event WHERE treeId = :treeId AND atMillis >= :sinceMillis")
    suspend fun feedRecords(treeId: Int, sinceMillis: Long): List<FeedRecordEntity>

    @Query("DELETE FROM feed_event WHERE treeId = :treeId")
    suspend fun deleteFeedRecords(treeId: Int)
}

@Database(entities = [GeniTreeEntity::class, GeniLinkEntity::class, UploadJobEntity::class, PhotoLinkEntity::class, FeedRecordEntity::class], version = 7, exportSchema = false)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun dao(): SyncDao

    companion object {
        /** Version 2: upload jobs and the verified hash of links. Existing links keep their snapshots. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `link` ADD COLUMN `verifiedHash` INTEGER")
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `job` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `treeId` INTEGER NOT NULL, " +
                            "`key` TEXT NOT NULL, `kind` TEXT NOT NULL, `geniId` TEXT, `personGedcomId` TEXT, `familyGedcomId` TEXT, " +
                            "`anchors` TEXT NOT NULL, `params` TEXT NOT NULL, `label` TEXT NOT NULL, `localHash` INTEGER NOT NULL, " +
                            "`state` TEXT NOT NULL, `error` TEXT, `version` INTEGER NOT NULL)"
                )
                connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_job_treeId_key` ON `job` (`treeId`, `key`)")
            }
        }

        /** Version 3: links of records deleted locally are marked, so a reused GEDCOM ID is not taken for them. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `link` ADD COLUMN `orphaned` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Version 4: links between Geni photos and GEDCOM media records. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `photo_link` (`treeId` INTEGER NOT NULL, `geniProfileId` TEXT NOT NULL, " +
                            "`geniPhotoId` TEXT NOT NULL, `gedcomMediaId` TEXT, `isMain` INTEGER NOT NULL, `marker` TEXT, " +
                            "`fileName` TEXT, `state` TEXT NOT NULL, PRIMARY KEY(`treeId`, `geniProfileId`, `geniPhotoId`))"
                )
            }
        }

        /** Version 5: the Geni account that confirmed a delete job; jobs from before stay null and need a new confirmation. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `job` ADD COLUMN `confirmedByProfileId` TEXT")
            }
        }

        /** Version 6: the Geni account that owns a tree; trees from before are owned by their root's account. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE `tree` ADD COLUMN `ownerGeniId` TEXT NOT NULL DEFAULT ''")
                connection.execSQL("UPDATE `tree` SET `ownerGeniId` = `rootGeniId`")
            }
        }

        /** Version 7: events of the Home feed that the tree cannot tell (profiles created on Geni, people who left the tree). */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `feed_event` (`treeId` INTEGER NOT NULL, `kind` TEXT NOT NULL, `geniId` TEXT NOT NULL, " +
                            "`gedcomId` TEXT, `name` TEXT, `atMillis` INTEGER NOT NULL, PRIMARY KEY(`treeId`, `kind`, `geniId`))"
                )
            }
        }

        /** Every migration, in order: the app and the tests open the database with this same list. */
        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
    }
}

/** Sync store in the Room database geni_sync.db. */
class RoomSyncStore(private val dao: SyncDao) : SyncStore {

    constructor(context: Context) : this(
        Room.databaseBuilder(context.applicationContext, SyncDatabase::class.java, "geni_sync.db")
            .addMigrations(*SyncDatabase.MIGRATIONS).build().dao()
    )

    override suspend fun tree(treeId: Int) = dao.tree(treeId)?.let { GeniTreeInfo(it.treeId, it.rootGeniId, it.prefetchDepth, it.ownerGeniId) }

    override suspend fun saveTree(info: GeniTreeInfo) = dao.saveTree(GeniTreeEntity(info.treeId, info.rootGeniId, info.prefetchDepth, info.ownerGeniId))
    override suspend fun updateRoot(treeId: Int, oldRoot: String, newRoot: String) = dao.updateRoot(treeId, oldRoot, newRoot) > 0

    override suspend fun trees() = dao.trees().map { GeniTreeInfo(it.treeId, it.rootGeniId, it.prefetchDepth, it.ownerGeniId) }

    override suspend fun link(treeId: Int, geniId: String) = dao.link(treeId, geniId)?.toLink()

    override suspend fun links(treeId: Int) = dao.links(treeId).map { it.toLink() }

    override suspend fun unionLinks(treeId: Int) = dao.unionLinks(treeId).map { it.toLink() }

    override suspend fun saveLinks(links: List<GeniLink>) = dao.saveLinks(links.map {
        GeniLinkEntity(it.treeId, it.geniId, it.gedcomId, it.fullyLoaded, it.geniUpdatedAt, it.snapshot, it.loadedAtMillis, it.verifiedHash, it.orphaned)
    })

    override suspend fun linkByGedcomId(treeId: Int, gedcomId: String) = dao.linkByGedcomId(treeId, gedcomId)?.toLink()

    override suspend fun deleteLink(treeId: Int, geniId: String) = dao.deleteLink(treeId, geniId)

    override suspend fun setVerifiedHash(treeId: Int, geniId: String, gedcomId: String, snapshot: String?, hash: Int) =
        dao.setVerifiedHash(treeId, geniId, gedcomId, snapshot, hash) > 0

    override suspend fun updateSnapshot(treeId: Int, geniId: String, expected: String?, snapshot: String, geniUpdatedAt: String?) =
        dao.updateSnapshot(treeId, geniId, expected, snapshot, geniUpdatedAt) > 0

    override suspend fun setOrphaned(treeId: Int, geniId: String, orphaned: Boolean) = dao.setOrphaned(treeId, geniId, orphaned)

    override suspend fun photoLinks(treeId: Int) = dao.photoLinks(treeId).map { it.toPhotoLink() }

    override suspend fun photoLinks(treeId: Int, geniProfileId: String) = dao.photoLinks(treeId, geniProfileId).map { it.toPhotoLink() }

    override suspend fun savePhotoLink(link: PhotoLink) = dao.savePhotoLink(
        PhotoLinkEntity(link.treeId, link.geniProfileId, link.geniPhotoId, link.gedcomMediaId, link.isMain, link.marker, link.fileName, link.state.name)
    )

    override suspend fun deletePhotoLink(treeId: Int, geniProfileId: String, geniPhotoId: String) =
        dao.deletePhotoLink(treeId, geniProfileId, geniPhotoId)

    override suspend fun saveFeedRecords(records: List<FeedRecord>, keepSinceMillis: Long) {
        dao.saveFeedRecords(records.map { FeedRecordEntity(it.treeId, it.kind.name, it.geniId, it.gedcomId, it.name, it.atMillis) })
        dao.pruneFeedRecords(keepSinceMillis)
    }

    override suspend fun feedRecords(treeId: Int, sinceMillis: Long) = dao.feedRecords(treeId, sinceMillis).mapNotNull { row ->
        // A kind written by a newer build is skipped, not a crash
        FeedRecordKind.entries.firstOrNull { it.name == row.kind }?.let { FeedRecord(row.treeId, it, row.geniId, row.gedcomId, row.name, row.atMillis) }
    }

    override suspend fun deleteTree(treeId: Int) {
        dao.deleteFeedRecords(treeId)
        dao.deletePhotoLinks(treeId)
        dao.deleteJobs(treeId)
        dao.deleteLinks(treeId)
        dao.deleteTreeRow(treeId)
    }

    override suspend fun jobs(treeId: Int) = dao.jobs(treeId).map { it.toJob() }

    override suspend fun allJobs() = dao.allJobs().map { it.toJob() }

    override fun observeJobs(): Flow<List<UploadJob>> = dao.observeJobs().map { list -> list.map { it.toJob() } }

    override suspend fun saveJob(job: UploadJob): Long {
        val rowId = dao.saveJob(job.toEntity())
        if (job.id != 0L) return job.id // Upsert returns -1 when it updates
        // A new job whose key is taken: the upsert fell back to updating ID 0 and stored nothing
        check(rowId > 0) { "Job ${job.key} of tree ${job.treeId} already exists" }
        return rowId
    }

    override suspend fun deleteJob(id: Long) = dao.deleteJob(id)

    override suspend fun deleteJobIfVersion(id: Long, version: Int) = dao.deleteJobIfVersion(id, version) > 0

    private fun PhotoLinkEntity.toPhotoLink() =
        PhotoLink(treeId, geniProfileId, geniPhotoId, gedcomMediaId, isMain, marker, fileName, PhotoState.valueOf(state))

    private fun GeniLinkEntity.toLink() = GeniLink(treeId, geniId, gedcomId, fullyLoaded, geniUpdatedAt, snapshot, loadedAtMillis, verifiedHash, orphaned)

    private val gson = Gson()
    private val anchorsType = object : TypeToken<List<String>>() {}.type
    private val paramsType = object : TypeToken<List<List<String>>>() {}.type

    private fun UploadJob.toEntity() = UploadJobEntity(
        id, treeId, key, kind.name, geniId, personGedcomId, familyGedcomId,
        gson.toJson(anchors), gson.toJson(params.map { listOf(it.first, it.second) }),
        label, localHash, state.name, error, version, confirmedByProfileId
    )

    private fun UploadJobEntity.toJob() = UploadJob(
        id, treeId, key, JobKind.valueOf(kind), geniId, personGedcomId, familyGedcomId,
        gson.fromJson<List<String>>(anchors, anchorsType),
        gson.fromJson<List<List<String>>>(params, paramsType).map { it[0] to it[1] },
        label, localHash, JobState.valueOf(state), error, version, confirmedByProfileId
    )
}
