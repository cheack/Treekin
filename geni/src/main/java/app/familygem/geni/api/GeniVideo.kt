package app.familygem.geni.api

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

/**
 * Geni video of a profile. [urls] holds the size URLs: `original` and the other sizes are JPEG posters, the playable MP4 is only
 * `video`. Geni leaves `sizes` out of some answers (an update), so [videoUrl] may be null for a video that is playable later:
 * it is then listed as unavailable until a fresh read supplies it. [tags] are the profile IDs tagged in it.
 */
data class GeniVideo(
    val id: String,
    val title: String?,
    val description: String?,
    val date: GeniDate?,
    val urls: Map<String, String>,
    val updatedAt: String?,
    val tags: List<String>
) {
    /** URL of the playable MP4, null while Geni gave none. */
    val videoUrl: String? get() = urls["video"]

    /** URL of a poster image, null when there is none. */
    val posterUrl: String? get() = listOf("original", "medium", "small", "thumb").firstNotNullOfOrNull { urls[it] }
}

private class RawVideo(
    val id: String?,
    val title: String?,
    val description: String?,
    val date: GeniDate?,
    val sizes: Map<String, String>?,
    @SerializedName("updated_at") val updatedAt: String?,
    val tags: List<String>?
)

private fun toVideo(json: String): GeniVideo? {
    val raw = Gson().fromJson(json, RawVideo::class.java)
    return GeniVideo(
        raw.id ?: return null, raw.title, raw.description, raw.date,
        raw.sizes.orEmpty().filterKeys { it != "url" }, raw.updatedAt, raw.tags.orEmpty().map { geniId(it) }
    )
}

/** A single video or an upload response; null when the answer has no video (one deleted on Geni answers an empty list). */
fun parseVideo(json: String): GeniVideo? = if (JsonParser.parseString(json).asJsonObject.has("id")) toVideo(json) else null

/** Videos of a batch response; empty when there are none. */
fun parseVideos(json: String): List<GeniVideo> = rawResults(json).mapNotNull { toVideo(it) }
