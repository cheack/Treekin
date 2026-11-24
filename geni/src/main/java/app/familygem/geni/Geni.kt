package app.familygem.geni

import android.content.Context
import android.util.Log
import app.familygem.geni.api.GeniClient
import app.familygem.geni.api.RateLimiter
import app.familygem.geni.auth.EncryptedTokenStore
import app.familygem.geni.sync.AlbumLoader
import app.familygem.geni.sync.ConflictResolver
import app.familygem.geni.sync.GeniLoader
import app.familygem.geni.sync.PhotoDeletionGuard
import app.familygem.geni.sync.PhotoScaler
import app.familygem.geni.sync.PhotoSync
import app.familygem.geni.sync.RoomSyncStore
import app.familygem.geni.sync.SyncQueue
import app.familygem.geni.sync.SyncStore
import app.familygem.geni.sync.Uploader
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Entry point of the Geni.com integration, called from upstream code through GENI-HOOK lines. */
object Geni {

    val config: GeniConfig by lazy { GeniConfig.fromBuildConfig() }

    /** True when the build has a Geni app ID (from geni.properties). */
    val isConfigured: Boolean
        get() = config.isConfigured

    /** One limiter for the whole app, because Geni counts all requests of the user together. */
    private val limiter by lazy { RateLimiter { System.currentTimeMillis() } }

    /** Photos Geni confirmed deleted, shared by the uploader and both loaders for the life of the process. */
    private val photoGuard = PhotoDeletionGuard()

    private var client: GeniClient? = null
    private var store: SyncStore? = null

    /** Starts the uploads of every tree with pending changes; set by the app glue, called after login and "Retry all". */
    @Volatile
    var resumeUploads: (() -> Unit)? = null

    /** Access to FamilyGem trees, set by the app glue. */
    @Volatile
    var host: TreeHost? = null

    fun client(context: Context): GeniClient = client ?: GeniClient(
        baseUrl = "https://${config.host}/api/".toHttpUrl(),
        tokens = EncryptedTokenStore(context.applicationContext),
        limiter = limiter
    ).also { client = it }

    /** IDs of deleted trees whose Geni rows are still being removed: a new tree must not take one, or the cleanup would wipe it. */
    private val cleaning = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

    fun isCleaning(treeId: Int) = treeId in cleaning

    /** Forgets the Geni data of a deleted tree in [scope]. The ID stays taken until the rows are gone. */
    fun forgetTree(context: Context, treeId: Int, scope: kotlinx.coroutines.CoroutineScope) {
        GeniTrees.remove(treeId)
        cleaning.add(treeId)
        scope.launch { try { store(context).deleteTree(treeId) } finally { cleaning.remove(treeId) } }
    }

    @Synchronized
    fun store(context: Context): SyncStore = store ?: RoomSyncStore(context.applicationContext).also { store = it }

    private var queue: SyncQueue? = null

    /** The upload queue; one instance, so its per-tree locks are shared. */
    @Synchronized
    fun queue(context: Context): SyncQueue = queue ?: run {
        val uploader = Uploader(client(context), store(context), host ?: error("Geni tree host not installed"),
            prepare = PhotoScaler::scaled, photos = PhotoSync(store(context), host ?: error("Geni tree host not installed"), photoGuard))
        SyncQueue(store(context), uploader, { GeniSettings.uploadPhotos(context) }) { job, outcome -> Log.d("GeniSync", "Tree ${job.treeId} ${job.kind} ${job.key}: $outcome") }
            .also { queue -> uploader.onTreeEdited = queue::setLocal }
    }.also { queue = it }

    fun resolver(context: Context): ConflictResolver =
        ConflictResolver(client(context), store(context), host ?: error("Geni tree host not installed"), queue(context))

    fun loader(context: Context): GeniLoader {
        val treeHost = host ?: error("Geni tree host not installed")
        val store = store(context)
        return GeniLoader(
            client(context), store, treeHost,
            photos = PhotoSync(store, treeHost, photoGuard),
            onPhotoError = { profileId, e -> Log.w("GeniSync", "Photo of $profileId not synced", e) }
        )
    }

    fun albumLoader(context: Context): AlbumLoader {
        val treeHost = host ?: error("Geni tree host not installed")
        return AlbumLoader(client(context), store(context), PhotoSync(store(context), treeHost, photoGuard), treeHost::mediaDir)
    }
}
