package app.familygem.geni.api

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Downloads a photo file from a signed (expiring) Geni CDN URL.
 * It needs no token: uses its own client without auth, and never follows redirects.
 *
 * @param http base client (connection pool, dispatcher, timeouts, TLS are kept). Its interceptors,
 * network interceptors and authenticators are dropped, so no credential it would add (like the
 * Geni bearer token) can reach the photo host.
 * @param allowHttp permits plain http, but only to loopback hosts (for tests).
 * @param accept the content types it takes (images by default); another one fails the download before a byte is stored.
 */
class PhotoDownloader(
    http: OkHttpClient = OkHttpClient(),
    private val maxBytes: Long = 15_000_000,
    private val allowHttp: Boolean = false,
    private val accept: (String) -> Boolean = { it.startsWith("image/", ignoreCase = true) }
) {

    // Policy applies to any supplied client: no redirects (signed URLs) and a total time cap
    private val http: OkHttpClient = http.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .apply {
            interceptors().clear()
            networkInterceptors().clear()
        }
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .callTimeout(2, TimeUnit.MINUTES)
        .build()

    /** Saves the photo to [target] and returns its content type. */
    suspend fun download(url: String, target: File): String = withContext(Dispatchers.IO) {
        val httpUrl = url.toHttpUrlOrNull() ?: throw IllegalArgumentException("Invalid photo URL")
        require(httpUrl.isHttps || (allowHttp && httpUrl.host in LOOPBACK_HOSTS)) { "Photo URL must be https" }
        val call = http.newCall(Request.Builder().url(httpUrl).get().build())
        // Unique staging file, so concurrent downloads to one target never touch each other's data
        val part = File.createTempFile(target.name + ".", ".part", target.absoluteFile.parentFile)
        try {
            coroutineScope {
                // execute() and source.read() block their thread and ignore coroutine cancellation.
                // This watcher sleeps until the scope is cancelled, then cancels the OkHttp call,
                // which makes the blocked read throw IOException right away.
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        call.cancel()
                    }
                }
                try {
                    fetch(call, target, part)
                } catch (e: IOException) {
                    // Report a cancelled call as cancellation, not as the IOException it caused
                    ensureActive()
                    throw e
                } finally {
                    watcher.cancel()
                }
            }
        } finally {
            part.delete()
        }
    }

    private suspend fun fetch(call: Call, target: File, part: File): String {
        return call.execute().use { response ->
            if (!response.isSuccessful) {
                throw GeniApiException(response.code, "Photo download failed: HTTP ${response.code}")
            }
            val type = response.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
            if (!accept(type)) {
                throw IOException("Unexpected content type: $type")
            }
            val body = response.body ?: throw IOException("Empty response")
            var total = 0L
            body.source().use { source ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = source.read(buffer)
                        if (n < 0) break
                        coroutineContext.ensureActive()
                        total += n
                        if (total > maxBytes) throw IOException("Photo larger than $maxBytes bytes")
                        out.write(buffer, 0, n)
                    }
                }
            }
            if (total == 0L) throw IOException("Empty photo")
            coroutineContext.ensureActive()
            if (!part.renameTo(target)) throw IOException("Cannot save ${target.name}")
            type
        }
    }

    private companion object {
        val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
    }
}
