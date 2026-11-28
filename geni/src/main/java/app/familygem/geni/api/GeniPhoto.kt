package app.familygem.geni.api

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

/**
 * Geni photo; [urls] holds the size URLs only (thumb, small, medium, large, original, ...).
 * [title], [description] and [date] are null when Geni has none (it leaves a cleared field out) and mean nothing
 * when [detailsKnown] is false: a photo made from a mugshot URL carries no details at all.
 */
data class GeniPhoto(
    val id: String,
    val title: String?,
    val contentType: String?,
    val urls: Map<String, String>,
    val updatedAt: String?,
    val albumId: String?,
    val description: String? = null,
    val date: GeniDate? = null,
    val detailsKnown: Boolean = true
) {
    /** URL of the first size name present, or null. */
    fun urlFor(vararg preferred: String): String? = preferred.firstNotNullOfOrNull { urls[it] }
}

private class RawPhoto(
    val id: String?,
    val title: String?,
    @SerializedName("content_type") val contentType: String?,
    val sizes: Map<String, String>?,
    @SerializedName("updated_at") val updatedAt: String?,
    @SerializedName("album_id") val albumId: String?,
    val description: String?,
    val date: GeniDate?
)

private fun toPhoto(json: String): GeniPhoto {
    val raw = Gson().fromJson(json, RawPhoto::class.java)
    return GeniPhoto(
        raw.id ?: throw GeniApiException(200, "photo without id"), raw.title, raw.contentType,
        raw.sizes.orEmpty().filterKeys { it != "url" },
        raw.updatedAt, raw.albumId, raw.description, raw.date
    )
}

/** A single photo or an upload response. */
fun parsePhoto(json: String): GeniPhoto = toPhoto(JsonParser.parseString(json).asJsonObject.toString())

/** Photos of a batch response; empty when there are none. */
fun parsePhotos(json: String): List<GeniPhoto> = rawResults(json).map { toPhoto(it) }

/** Main photo of a profile, from its mugshot URLs; null when it has none. */
fun mugshotToPhoto(profile: GeniProfile): GeniPhoto? {
    val urls = profile.mugshotUrls ?: return null
    val url = urls["url"] ?: return null
    return GeniPhoto(geniId(url), null, null, urls.filterKeys { it != "url" }, null, null, detailsKnown = false)
}
