package app.familygem.geni.api

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

/**
 * Geni document: a scan or a text of a profile. [urls] holds the size URLs (`original` is the whole file of a PDF or an image).
 * [tags] are the profile IDs tagged in it. A text document ([isText]) has no file: its `original` URL is a placeholder
 * icon and the text itself is not readable through the API.
 */
data class GeniDocument(
    val id: String,
    val title: String?,
    val description: String?,
    val contentType: String?,
    val date: GeniDate?,
    val pageCount: Int?,
    val urls: Map<String, String>,
    val updatedAt: String?,
    val tags: List<String>
) {
    val isText get() = contentType?.substringBefore(';')?.trim().equals("text/plain", ignoreCase = true)

    /** URL of the complete file, null for a text document. */
    val fileUrl: String? get() = if (isText) null else urlFor("original")

    fun urlFor(vararg preferred: String): String? = preferred.firstNotNullOfOrNull { urls[it] }
}

private class RawDocument(
    val id: String?,
    val title: String?,
    val description: String?,
    @SerializedName("content_type") val contentType: String?,
    val date: GeniDate?,
    @SerializedName("page_count") val pageCount: Int?,
    val sizes: Map<String, String>?,
    @SerializedName("updated_at") val updatedAt: String?,
    val tags: List<String>?
)

private fun toDocument(json: String): GeniDocument? {
    val raw = Gson().fromJson(json, RawDocument::class.java)
    return GeniDocument(
        raw.id ?: return null, raw.title, raw.description, raw.contentType, raw.date, raw.pageCount,
        raw.sizes.orEmpty().filterKeys { it != "url" }, raw.updatedAt, raw.tags.orEmpty().map { geniId(it) }
    )
}

/** A single document or an upload response; null when the answer has no document (one deleted on Geni answers an empty list). */
fun parseDocument(json: String): GeniDocument? = if (JsonParser.parseString(json).asJsonObject.has("id")) toDocument(json) else null

/** Documents of a batch response; empty when there are none. */
fun parseDocuments(json: String): List<GeniDocument> = rawResults(json).mapNotNull { toDocument(it) }
