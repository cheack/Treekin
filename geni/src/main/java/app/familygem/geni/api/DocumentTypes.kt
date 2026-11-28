package app.familygem.geni.api

import java.io.File

/**
 * The files Geni accepts as documents, as the sandbox answered (2026-11-28): PDF, JPEG, PNG, GIF and TIFF (stored as JPEG), and
 * `text/plain`, whose content cannot be read back. Any other type (Word, zip, binary) is refused with "Unsupported content type".
 */
object DocumentTypes {

    /** Geni cut the connection of an 8 MB upload and took a 5 MB one; the app never sends more than the size verified to work. */
    const val MAX_UPLOAD_BYTES = 5_000_000L

    /** Largest document file the app downloads. */
    const val MAX_DOWNLOAD_BYTES = 30_000_000L

    private val byExtension = mapOf(
        "pdf" to "application/pdf", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "gif" to "image/gif", "tif" to "image/tiff", "tiff" to "image/tiff"
    )

    /** The content type to send for a file called [fileName], null when Geni takes no such document. */
    fun contentTypeFor(fileName: String): String? = byExtension[fileName.substringAfterLast('.', "").lowercase()]

    /** True for a content type a downloaded document file may have (the original of a PDF or an image). */
    fun isFile(contentType: String): Boolean {
        val type = contentType.substringBefore(';').trim().lowercase()
        return type == "application/pdf" || type.startsWith("image/")
    }

    /** File extension for a downloaded document, from the type Geni served. */
    fun extension(contentType: String, url: String): String = when (contentType.substringBefore(';').trim().lowercase()) {
        "application/pdf" -> "pdf"
        "image/png" -> "png"
        "image/gif" -> "gif"
        "image/tiff" -> "tif"
        "image/jpeg", "image/jpg" -> "jpg"
        else -> url.substringBefore('?').substringAfterLast('.', "").takeIf { it.length in 2..4 } ?: "bin"
    }

    /** True if the file starts like a PDF, JPEG, PNG, GIF or TIFF. */
    fun looksLikeDocument(file: File): Boolean {
        val head = ByteArray(8)
        val n = file.inputStream().use { it.read(head) }
        if (n < 4) return false
        fun starts(vararg bytes: Int) = bytes.indices.all { head[it] == bytes[it].toByte() }
        return starts(0x25, 0x50, 0x44, 0x46) || starts(0xFF, 0xD8, 0xFF) || starts(0x89, 0x50, 0x4E, 0x47) ||
                starts(0x47, 0x49, 0x46, 0x38) || starts(0x49, 0x49, 0x2A, 0x00) || starts(0x4D, 0x4D, 0x00, 0x2A)
    }
}
