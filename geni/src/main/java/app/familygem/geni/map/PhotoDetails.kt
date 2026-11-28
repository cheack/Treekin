package app.familygem.geni.map

import app.familygem.geni.api.GeniPhoto
import org.folg.gedcom.model.Media

/** The three details of a Geni photo that can be edited. */
enum class PhotoField { TITLE, DESCRIPTION, DATE }

/**
 * Title, description and date of a Geni photo as the tree keeps them: [date] is a GEDCOM date ("12 MAR 1960", "BEF 1900").
 * A field Geni does not have is null, never an empty text.
 */
data class PhotoDetails(val title: String?, val description: String?, val date: String?) {

    operator fun get(field: PhotoField) = when (field) {
        PhotoField.TITLE -> title
        PhotoField.DESCRIPTION -> description
        PhotoField.DATE -> date
    }

    /** These details with [field] taken from [other]. */
    fun with(field: PhotoField, other: PhotoDetails) = when (field) {
        PhotoField.TITLE -> copy(title = other.title)
        PhotoField.DESCRIPTION -> copy(description = other.description)
        PhotoField.DATE -> copy(date = other.date)
    }

    /** The fields in which [other] differs. */
    fun differences(other: PhotoDetails) = PhotoField.entries.filter { get(it) != other[it] }

    /** These details as job parameters (see [fromParams]); an empty value stands for nothing. */
    fun toParams(prefix: String) = PhotoField.entries.map { "$prefix${it.name}" to (get(it) ?: "") }

    companion object {
        val EMPTY = PhotoDetails(null, null, null)

        /** The details Geni lists for [photo]; [EMPTY] for a photo without details, so check [GeniPhoto.detailsKnown] first. */
        fun of(photo: GeniPhoto) = PhotoDetails(clean(photo.title), clean(photo.description), GeniMapper.gedcomDate(photo.date))

        /** The details [toParams] wrote with [prefix]; a missing entry is nothing. */
        fun fromParams(params: List<Pair<String, String>>, prefix: String): PhotoDetails {
            fun value(field: PhotoField) = params.firstOrNull { it.first == "$prefix${field.name}" }?.second?.takeIf { it.isNotEmpty() }
            return PhotoDetails(value(PhotoField.TITLE), value(PhotoField.DESCRIPTION), value(PhotoField.DATE))
        }

        /** A text as Geni keeps it: line ends as `\n`, blank is nothing. Edge spaces stay: Geni stores what it is given. */
        fun clean(text: String?): String? = text?.replace("\r\n", "\n")?.takeIf { it.isNotBlank() }

        const val TITLE_TAG = "_GENI_PHOTO_TITLE"
        const val DESCRIPTION_TAG = "_GENI_PHOTO_DESCRIPTION"
        const val DATE_TAG = "_GENI_PHOTO_DATE"

        /** Set once the details of Geni were stored, so "Geni has no title" can be told from "not read yet". */
        const val SYNCED_TAG = "_GENI_PHOTO_SYNCED"

        /** What the user sees for [media]: its title and the stored description and date. */
        fun shown(media: Media) = PhotoDetails(
            clean(media.title), clean(GeniMapper.getTag(media, DESCRIPTION_TAG)), GeniMapper.getTag(media, DATE_TAG)?.takeIf { it.isNotBlank() }
        )

        /** The title Geni had when the media was last synced; null when it had none or was never read (see [isSynced]). */
        fun syncedTitle(media: Media) = clean(GeniMapper.getTag(media, TITLE_TAG))

        fun isSynced(media: Media) = GeniMapper.getTag(media, SYNCED_TAG) != null

        /**
         * Takes Geni's [details] into [media]. Description and date are stored as Geni has them. The title goes to the
         * media's own title unless the user gave it another one (it differs from the last synced title), which stays;
         * [force] replaces it anyway (Geni confirmed a value just sent). Returns true if anything of the media changed.
         */
        fun apply(media: Media, details: PhotoDetails, force: Boolean = false): Boolean {
            val before = listOf(media.title, GeniMapper.getTag(media, TITLE_TAG), GeniMapper.getTag(media, DESCRIPTION_TAG),
                GeniMapper.getTag(media, DATE_TAG), GeniMapper.getTag(media, SYNCED_TAG))
            val title = clean(media.title)
            if (force || title == null || title == syncedTitle(media)) media.title = details.title
            GeniMapper.setTag(media, TITLE_TAG, details.title)
            GeniMapper.setTag(media, DESCRIPTION_TAG, details.description)
            GeniMapper.setTag(media, DATE_TAG, details.date)
            GeniMapper.setTag(media, SYNCED_TAG, "Y")
            return before != listOf(media.title, GeniMapper.getTag(media, TITLE_TAG), GeniMapper.getTag(media, DESCRIPTION_TAG),
                GeniMapper.getTag(media, DATE_TAG), GeniMapper.getTag(media, SYNCED_TAG))
        }

        /** Puts the [fields] of [details] on [media] as the user's own edit: the synced values stay as they were. Returns true if anything changed. */
        fun applyOwn(media: Media, details: PhotoDetails, fields: Collection<PhotoField>): Boolean {
            val before = shown(media)
            for (field in fields) when (field) {
                PhotoField.TITLE -> media.title = details.title
                PhotoField.DESCRIPTION -> GeniMapper.setTag(media, DESCRIPTION_TAG, details.description)
                PhotoField.DATE -> GeniMapper.setTag(media, DATE_TAG, details.date)
            }
            return before != shown(media)
        }

        /** True if the user changed the title here (it is not the one synced from Geni). */
        fun hasOwnTitle(media: Media) = clean(media.title)?.let { it != syncedTitle(media) } == true
    }
}
