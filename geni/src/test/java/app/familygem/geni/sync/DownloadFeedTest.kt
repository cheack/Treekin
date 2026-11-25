package app.familygem.geni.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFeedTest {

    private val feed = DownloadFeed()

    /** Like GeniPrefetch: listed as queued, run under the tree's mutex, removed whatever happens. */
    private fun kotlinx.coroutines.CoroutineScope.pass(mutex: Mutex, kind: DownloadKind, body: suspend (DownloadProgress) -> Unit) = launch {
        val waiting = feed.queue(1, kind, "profile-1")
        try {
            mutex.withLock { waiting.run { body(it) } }
        } finally {
            waiting.remove()
        }
    }

    @Test
    fun secondPassOfATreeIsQueuedUntilTheFirstEnds() = runTest {
        val mutex = Mutex()
        val release = CompletableDeferred<Unit>()
        pass(mutex, DownloadKind.KEEP_OFFLINE) { release.await() }
        pass(mutex, DownloadKind.KEEP_OFFLINE) { release.await() }
        runCurrent()
        assertEquals(listOf(true, false), feed.tasks.value.map { it.active })
        release.complete(Unit)
        runCurrent()
        assertEquals(emptyList<DownloadTask>(), feed.tasks.value)
    }

    @Test
    fun queuedPassBecomesActiveWhenItGetsTheMutex() = runTest {
        val mutex = Mutex()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        pass(mutex, DownloadKind.PREFETCH) { first.await() }
        pass(mutex, DownloadKind.DAILY_REFRESH) { second.await() }
        runCurrent()
        first.complete(Unit)
        runCurrent()
        val task = feed.tasks.value.single()
        assertEquals(DownloadKind.DAILY_REFRESH, task.kind)
        assertTrue(task.active)
        second.complete(Unit)
    }

    @Test
    fun cancelledWhileQueuedOrRunningLeavesNothing() = runTest {
        val mutex = Mutex()
        val running = pass(mutex, DownloadKind.KEEP_OFFLINE) { CompletableDeferred<Unit>().await() }
        val waiting = pass(mutex, DownloadKind.KEEP_OFFLINE) { }
        runCurrent()
        assertEquals(2, feed.tasks.value.size)
        waiting.cancel()
        runCurrent()
        assertEquals(1, feed.tasks.value.size)
        running.cancel()
        runCurrent()
        assertEquals(emptyList<DownloadTask>(), feed.tasks.value)
    }

    @Test
    fun failureRemovesTheTask() = runTest {
        try {
            feed.track(1, DownloadKind.ALBUM_LIST, "profile-1") { throw IllegalStateException("boom") }
        } catch (e: IllegalStateException) {
        }
        assertEquals(emptyList<DownloadTask>(), feed.tasks.value)
    }

    @Test
    fun nestedWorkIsAStageCountedOnce() = runTest {
        val inside = CompletableDeferred<List<DownloadTask>>()
        val release = CompletableDeferred<Unit>()
        val outer = async {
            feed.track(1, DownloadKind.KEEP_OFFLINE, "profile-1") { progress ->
                progress.step()
                feed.track(1, DownloadKind.ALBUM_DOWNLOAD, "profile-2") { album ->
                    album.total(3)
                    album.step()
                    feed.track(1, DownloadKind.PHOTO, "profile-2") {
                        inside.complete(feed.tasks.value)
                        release.await()
                    }
                }
                assertEquals(emptyList<DownloadStage>(), feed.tasks.value.single().stages)
            }
        }
        val task = inside.await().single()
        assertEquals(DownloadKind.KEEP_OFFLINE, task.kind)
        assertEquals(1, task.done)
        assertEquals(listOf(DownloadKind.ALBUM_DOWNLOAD, DownloadKind.PHOTO), task.stages.map { it.kind })
        assertEquals(1 to 3, task.stages[0].done to task.stages[0].total)
        release.complete(Unit)
        outer.await()
        assertEquals(emptyList<DownloadTask>(), feed.tasks.value)
    }

    @Test
    fun separateCoroutinesAreSeparateTasks() = runTest {
        val release = CompletableDeferred<Unit>()
        launch { feed.track(1, DownloadKind.PHOTO, "profile-1") { release.await() } }
        launch { feed.track(1, DownloadKind.ALBUM_LIST, "profile-2") { release.await() } }
        runCurrent()
        assertEquals(listOf(DownloadKind.PHOTO, DownloadKind.ALBUM_LIST), feed.tasks.value.map { it.kind })
        release.complete(Unit)
    }
}
