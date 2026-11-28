package app.familygem.geni.api

import java.io.File

/**
 * What the sandbox answered for videos (2026-11-28): `add-video` takes `video/mp4` and returns a playable MP4 in `sizes.video`,
 * a transcoded copy served as `application/octet-stream` (a 2.4 MB upload came back as 245 KB). Uploads of 4 MB and 6.9 MB timed out.
 */
object VideoTypes {

    /** A 2.4 MB upload worked, 4 MB and larger ones were cut off by Geni; the app sends at most a little above the largest size verified. */
    const val MAX_UPLOAD_BYTES = 2_500_000L

    /** Largest video file the app downloads. */
    const val MAX_DOWNLOAD_BYTES = 100_000_000L

    /** The content type to send for a file called [fileName], null when it is not an MP4. */
    fun contentTypeFor(fileName: String): String? = if (fileName.substringAfterLast('.', "").lowercase() == "mp4") "video/mp4" else null

    /** True for a content type a downloaded video may be served with: Geni's CDN sends the MP4 as `application/octet-stream`. */
    fun isFile(contentType: String): Boolean {
        val type = contentType.substringBefore(';').trim().lowercase()
        return type == "video/mp4" || type == "application/octet-stream"
    }

    /** True if the file is an ISO base media file: `ftyp` box at byte 4. */
    fun looksLikeMp4(file: File): Boolean {
        val head = ByteArray(12)
        val n = file.inputStream().use { it.read(head) }
        return n >= 12 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() && head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
    }
}
