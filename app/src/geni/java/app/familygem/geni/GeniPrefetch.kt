package app.familygem.geni

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import app.familygem.geni.sync.AlbumLoader
import app.familygem.geni.sync.DownloadKind
import app.familygem.geni.sync.DownloadProgress
import app.familygem.geni.sync.LoadPolicy
import app.familygem.geni.sync.LoadResult
import app.familygem.geni.sync.SyncStore
import app.familygem.geni.sync.Prefetcher
import app.familygem.geni.sync.RelativesIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Loads Geni relatives in background, up to a number of relationship steps from a person.
 * Runs in the app process, not in WorkManager: upstream Notifier calls WorkManager.cancelAllWork() on every tree save,
 * which would cancel the work after its first load.
 */
object GeniPrefetch {

    private const val TAG = "GeniPrefetch"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutexes = mutableMapOf<Int, Mutex>()
    /** Trees with a daily refresh queued or running. */
    private val dailyRunning = mutableSetOf<Int>()

    /**
     * Queues a prefetch after any other prefetch of the same tree.
     * @param kind How the sync backlog names it: the first pass after the tree was made, or "Keep offline"
     */
    fun enqueue(context: Context, treeId: Int, startGeniId: String, depth: Int, includePhotos: Boolean = false, kind: DownloadKind = DownloadKind.PREFETCH) {
        queue(context.applicationContext, treeId, kind, startGeniId) { app, progress ->
            run(app, treeId, startGeniId, depth, unmeteredOnly = false, includePhotos = includePhotos, progress = progress)
        }
    }

    /** Runs [block] after the other passes of the tree; listed in the backlog as queued until it holds the tree's mutex. */
    private fun queue(app: Context, treeId: Int, kind: DownloadKind, geniId: String?, block: suspend (Context, DownloadProgress) -> Unit) {
        val mutex = synchronized(mutexes) { mutexes.getOrPut(treeId) { Mutex() } }
        scope.launch {
            val waiting = Geni.downloads.queue(treeId, kind, geniId)
            try {
                mutex.withLock { waiting.run { block(app, it) } }
            } finally {
                waiting.remove() // Also when cancelled while waiting for the mutex
            }
        }
    }

    /** @return False if the pass stopped before visiting everyone */
    private suspend fun run(
        context: Context, treeId: Int, start: String, depth: Int, unmeteredOnly: Boolean, includePhotos: Boolean = false, progress: DownloadProgress
    ): Boolean {
        if (!withContext(Dispatchers.Main) { AppTreeHost.treeExists(treeId) }) return true
        val store = Geni.store(context)
        val loader = Geni.loader(context)
        val album = if (includePhotos) Geni.albumLoader(context) else null
        val began = System.currentTimeMillis()
        val prefetcher = Prefetcher(
            loader = { id ->
                loader.load(treeId, id).also { result ->
                    if (album != null && result is LoadResult.Loaded) downloadAlbum(album, store, treeId, result.profileId ?: id)
                    if (result is LoadResult.Loaded) progress.step() // People loaded; the total of a branch pass is not known
                }
            },
            relatives = { RelativesIndex.relatives(store.unionLinks(treeId), it) },
            isFresh = { id ->
                val fresh = store.link(treeId, id)?.let { !LoadPolicy.shouldLoad(it, System.currentTimeMillis()) } ?: false
                // A person loaded earlier is skipped, but its photos may still be missing
                if (fresh && album != null) downloadAlbum(album, store, treeId, id)
                fresh
            },
            mayLoad = { !unmeteredOnly || !metered(context) }
        )
        val loads = prefetcher.run(start, depth)
        Log.d(TAG, "Tree $treeId: $loads loads from $start, depth $depth, in ${System.currentTimeMillis() - began} ms" +
                if (prefetcher.completed) "" else ", stopped")
        return prefetcher.completed
    }

    /** Photos are a bonus: a failure here never stops the prefetch. */
    private suspend fun downloadAlbum(album: AlbumLoader, store: SyncStore, treeId: Int, geniId: String) {
        try {
            val gedcomId = store.link(treeId, geniId)?.gedcomId ?: return
            album.downloadAll(treeId, geniId, gedcomId) { photo, e -> Log.w(TAG, "Photo ${photo.id} of $geniId not downloaded", e) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Album of $geniId not downloaded", e)
        }
    }

    private fun metered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager?.activeNetwork == null || manager.isActiveNetworkMetered
    }

    /**
     * Refreshes the radius of every Geni tree around its root, once a day and only on an unmetered network:
     * people loaded more than a day ago are loaded again, so changes made by others on Geni reach the cache.
     */
    fun dailyRefresh(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                val metered = metered(app)
                val prefs = app.getSharedPreferences("geni_refresh", Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                for (tree in Geni.store(app).trees()) {
                    if (!withContext(Dispatchers.Main) { AppTreeHost.treeExists(tree.treeId) }) continue
                    val key = "tree${tree.treeId}"
                    // Reserved in memory before queueing, so a second trigger (app start and network) does not queue it again;
                    // the time is saved only after a complete pass, so a pass cut short (app closed, login needed,
                    // metered network, failed loads) is due again at the next trigger
                    val admitted = synchronized(dailyRunning) {
                        tree.treeId !in dailyRunning &&
                                LoadPolicy.dailyRefreshDue(prefs.getLong(key, -1).takeIf { it >= 0 }, now, metered) &&
                                dailyRunning.add(tree.treeId)
                    }
                    if (!admitted) continue
                    Log.d(TAG, "Daily refresh of tree ${tree.treeId}")
                    queue(app, tree.treeId, DownloadKind.DAILY_REFRESH, tree.rootGeniId) { context, progress ->
                        try {
                            val info = Geni.store(context).tree(tree.treeId) ?: return@queue // The root may have moved meanwhile
                            if (run(context, info.treeId, info.rootGeniId, info.prefetchDepth, unmeteredOnly = true, progress = progress)) {
                                prefs.edit().putLong(key, System.currentTimeMillis()).apply()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "Daily refresh of tree ${tree.treeId} failed", e)
                        } finally {
                            synchronized(dailyRunning) { dailyRunning.remove(tree.treeId) }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Daily refresh failed", e)
            }
        }
    }
}
