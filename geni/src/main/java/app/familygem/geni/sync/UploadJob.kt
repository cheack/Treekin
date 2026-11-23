package app.familygem.geni.sync

enum class JobKind { UPDATE_PROFILE, UPDATE_UNION, ADD_PARTNER, ADD_CHILD, DELETE_PROFILE, ADD_PHOTO, DELETE_PHOTO }

/** PENDING runs; FAILED waits for the user; DISMISSED is hidden and kept so the same change is not planned again. */
enum class JobState {
    PENDING, SENDING, FAILED, DISMISSED,
    /** Geni changed the same fields meanwhile: waits for the user to choose, never runs by itself. */
    CONFLICT
}

/**
 * A change waiting to be uploaded to Geni. One job per record, identified by [key] within the tree:
 * `profile:<geniId>`, `union:<geniId>`, `add:<personGedcomId>`, `delete:<geniId>`, `delphoto:<photoId>` or `photo:<personGedcomId>:<fileName>`.
 * @param anchors For adds: relatives to attach the new profile to, as "child:<gedcomId>" or "partner:<gedcomId>"
 * @param params Form parameters for Geni
 * @param label What the user sees in the list of failed changes
 * @param localHash Hash of the local projection the job was planned from
 * @param confirmedByProfileId For deletes: the Geni account (its profile ID) that confirmed it; null for jobs from before this was recorded
 * @param version Increased on every change of the job, so a finished upload does not delete a newer version
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
    val confirmedByProfileId: String? = null
)
