package app.familygem.geni.sync

enum class JobKind { UPDATE_PROFILE, UPDATE_UNION, ADD_PARTNER, ADD_CHILD, DELETE_PROFILE, ADD_PHOTO, DELETE_PHOTO, UPDATE_PHOTO, TAG_PHOTO, UNTAG_PHOTO, SET_MAIN_PHOTO }

/** PENDING runs; FAILED waits for the user; DISMISSED is hidden and kept so the same change is not planned again. */
enum class JobState {
    PENDING, SENDING, FAILED, DISMISSED,
    /** Geni changed the same fields meanwhile: waits for the user to choose, never runs by itself. */
    CONFLICT
}

/**
 * A change waiting to be uploaded to Geni. One job per record, identified by [key] within the tree:
 * `profile:<geniId>`, `union:<geniId>`, `add:<personGedcomId>`, `delete:<geniId>`, `delphoto:<photoId>`, `updphoto:<photoId>`, `tags:<photoId>:<profileId>`, `mainphoto:<profileId>` or `photo:<personGedcomId>:<fileName>`.
 * @param anchors For adds: relatives to attach the new profile to, as "child:<gedcomId>" or "partner:<gedcomId>"
 * @param params Form parameters for Geni; an UPDATE_PHOTO has the details it started from and the wanted ones as `base:<FIELD>` and `want:<FIELD>` pairs (see [app.familygem.geni.map.PhotoDetails.toParams]); an add of an adopted or foster child also has [MODIFIER_PARAM]
 * A TAG_PHOTO or UNTAG_PHOTO has the photo as [geniId] and the profile as `profile` param; a SET_MAIN_PHOTO has the profile as [geniId] and `photo_id`.
 * @param label What the user sees in the list of failed changes
 * @param localHash Hash of the local projection the job was planned from
 * @param confirmedByProfileId For deletes: the Geni account (its profile ID) that confirmed it; null for jobs from before this was recorded
 * @param version Increased on every change of the job, so a finished upload does not delete a newer version
 * @param paramsFormat How the params were made: 0 for jobs queued before places were structured, whose `place_name` change also
 *  carries empty city, county, state and country; [CURRENT_PARAMS_FORMAT] for jobs that send only the parts that changed
 */
data class UploadJob(
    val id: Long = 0,
    val treeId: Int,
    val key: String,
    val kind: JobKind,
    val geniId: String? = null,
    val personGedcomId: String? = null,
    val familyGedcomId: String? = null,
    val anchors: List<String> = emptyList(),
    val params: List<Pair<String, String>>,
    val label: String,
    val localHash: Int,
    val state: JobState = JobState.PENDING,
    val error: String? = null,
    val version: Int = 0,
    val confirmedByProfileId: String? = null,
    val paramsFormat: Int = CURRENT_PARAMS_FORMAT
) {
    /** The params to send: a job of format 0 does not clear the structured parts its place edit never meant to clear. */
    fun paramsToSend(): List<Pair<String, String>> {
        if (paramsFormat >= CURRENT_PARAMS_FORMAT) return params
        val renamed = params.filter { it.first.endsWith("[location][place_name]") }.map { it.first.removeSuffix("[location][place_name]") }.toSet()
        return params.filterNot { (key, value) ->
            value.isEmpty() && LEGACY_CLEARS.any { part -> renamed.any { key == "$it[location][$part]" } }
        }
    }

    /** The params that describe the profile: without the modifier, which only tells Geni how the add links the child. */
    fun profileParams(): List<Pair<String, String>> = paramsToSend().filter { it.first != MODIFIER_PARAM }

    companion object {
        /** Geni's lineage of a new child ("adopt" or "foster"), sent with the add only; a birth child has none. */
        const val MODIFIER_PARAM = "relationship_modifier"
        /** Prefixes of the params of an UPDATE_PHOTO: the details it started from and the ones it wants. */
        const val BASE_PREFIX = "base:"
        const val WANT_PREFIX = "want:"
        const val CURRENT_PARAMS_FORMAT = 1
        private val LEGACY_CLEARS = listOf("city", "county", "state", "country")
    }
}
