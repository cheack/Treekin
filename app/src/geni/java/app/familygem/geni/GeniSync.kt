package app.familygem.geni

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import app.familygem.geni.sync.JobState
import app.familygem.geni.sync.LocalTree
import app.familygem.geni.sync.RunResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Runs the Geni upload queue in background, in the app process (not WorkManager: upstream Notifier calls
 * WorkManager.cancelAllWork() on every tree save). One run per tree at a time; temporary errors back off
 * from 30 s to 30 min, and a network coming back resumes them at once; a missing login stops the tree until [resume].
 */
object GeniSync {

    private const val TAG = "GeniSync"
    private const val FIRST_DELAY = 30_000L
    private const val MAX_DELAY = 30 * 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = mutableSetOf<Int>()
    private val again = mutableSetOf<Int>()
    private val delays = mutableMapOf<Int, Long>()
    /** The one waiting retry of each tree; [resume] cancels it, so a tree never has two retry chains. */
    private val retries = mutableMapOf<Int, Job>()

    /** Plans the uploads of a saved tree, then runs them. */
    fun onSaved(context: Context, treeId: Int, local: LocalTree) {
        val app = context.applicationContext
        scope.launch {
            try {
                Geni.queue(app).onSaved(treeId, local)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Planning tree $treeId failed", e)
            }
            kick(app, treeId)
        }
    }

    /** Runs the pending jobs of a tree, or asks the run in progress to look again when it ends. */
    fun kick(context: Context, treeId: Int) {
        val app = context.applicationContext
        synchronized(running) {
            if (treeId in running) {
                again += treeId
                return
            }
            running += treeId
        }
        scope.launch {
            var result = RunResult.RETRY_LATER
            try {
                do {
                    synchronized(running) { again -= treeId }
                    result = Geni.queue(app).runPending(treeId)
                } while (result == RunResult.EMPTY && synchronized(running) { treeId in again })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Uploads of tree $treeId stopped", e)
            } finally {
                val rerun = synchronized(running) {
                    running -= treeId
                    again.remove(treeId)
                }
                if (rerun && result != RunResult.RETRY_LATER) kick(app, treeId)
            }
            when (result) {
                RunResult.EMPTY -> synchronized(delays) { delays.remove(treeId) }
                RunResult.NEEDS_LOGIN -> Log.d(TAG, "Tree $treeId waits for login")
                RunResult.RETRY_LATER -> {
                    val wait = synchronized(delays) {
                        val next = delays[treeId]?.let { minOf(it * 2, MAX_DELAY) } ?: FIRST_DELAY
                        delays[treeId] = next
                        next
                    }
                    Log.d(TAG, "Tree $treeId retries in ${wait / 1000} s")
                    val retry = scope.launch(start = CoroutineStart.LAZY) {
                        delay(wait)
                        // Only the registered retry runs: one replaced or cancelled meanwhile stops here
                        val self = coroutineContext.job
                        val current = synchronized(retries) { (retries[treeId] === self).also { if (it) retries.remove(treeId) } }
                        if (current) kick(app, treeId)
                    }
                    synchronized(retries) { retries.put(treeId, retry) }?.cancel()
                    retry.start()
                }
            }
        }
    }

    /** Runs every tree that has pending uploads, with no back-off: after app start, login or "Retry all". */
    fun resume(context: Context) {
        val app = context.applicationContext
        scope.launch {
            try {
                synchronized(delays) { delays.clear() }
                synchronized(retries) { retries.values.toList().also { retries.clear() } }.forEach { it.cancel() }
                Geni.store(app).allJobs()
                    .filter { it.state == JobState.PENDING || it.state == JobState.SENDING }
                    .map { it.treeId }.distinct()
                    .forEach { kick(app, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Resuming uploads failed", e)
            }
        }
    }

    private var watching = false

    /** Resumes the uploads whenever a network with internet becomes available (they back off while offline). */
    fun watchNetwork(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (watching) return
            watching = true
        }
        try {
            val manager = app.getSystemService(ConnectivityManager::class.java) ?: error("no ConnectivityManager")
            val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            manager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                /** Networks known to be unmetered: each one that becomes so triggers a check (the active one decides). */
                private val unmetered = mutableSetOf<Network>()

                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available: resuming uploads")
                    resume(app)
                    GeniPrefetch.dailyRefresh(app) // Wi-Fi may be back after a day
                }

                // A network can become unmetered without reconnecting
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    val now = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    val became = synchronized(unmetered) { if (now) unmetered.add(network) else { unmetered.remove(network); false } }
                    if (became) GeniPrefetch.dailyRefresh(app)
                }

                override fun onLost(network: Network) {
                    synchronized(unmetered) { unmetered.remove(network) }
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Watching the network failed", e)
            synchronized(this) { watching = false } // Tried again on the next call
        }
    }
}
