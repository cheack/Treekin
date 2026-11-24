package app.familygem.geni.sync

import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.ProfileFields
import app.familygem.geni.map.UnionFields
import org.folg.gedcom.model.Gedcom
import java.util.concurrent.atomic.AtomicLong

/** A person of a saved tree, reduced to what the upload planner needs. */
data class LocalPerson(
    val gedcomId: String,
    val geniId: String?,
    val label: String,
    val fields: ProfileFields,
    val media: List<LocalMedia> = emptyList()
)

/** An inline media of a person: [file] is the plain file name, [geniPhotoId] is set once the photo is on Geni. */
data class LocalMedia(val file: String, val title: String?, val geniPhotoId: String?)

/** A family of a saved tree: partners (husbands then wives) and children by GEDCOM ID. */
data class LocalFamily(
    val gedcomId: String,
    val geniId: String?,
    val partners: List<String>,
    val children: List<String>,
    val fields: UnionFields
)

/**
 * Snapshot of a saved tree taken on the saving thread, so planning can run later without touching the live tree.
 * @param stamp Order of the capture: captures are planned in background and may arrive out of order
 */
data class LocalTree(val people: List<LocalPerson>, val families: List<LocalFamily>, val stamp: Long = 0) {
    companion object {
        private val captures = AtomicLong()
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")

        /** One person reduced to what the planner needs (also used to read a saved person alone). */
        fun capturePerson(person: org.folg.gedcom.model.Person): LocalPerson {
            val fields = ProfileFields.of(person)
            val label = listOfNotNull(fields.given, fields.surname).joinToString(" ").ifEmpty { person.id }
            val media = person.media.orEmpty().mapNotNull { m ->
                if (m.file?.contains("://") != false) return@mapNotNull null // Not a file of this device
                // Only a plain file name of the tree's media folder, and only an image, is a candidate
                if ('/' in m.file || '\\' in m.file || m.file.isEmpty()) return@mapNotNull null
                if (m.file.substringAfterLast('.', "").lowercase() !in IMAGE_EXTENSIONS) return@mapNotNull null
                val file = m.file
                LocalMedia(file, m.title, GeniMapper.getTag(m, PhotoSync.PHOTO_TAG))
            }
            return LocalPerson(person.id, GeniMapper.getGeniId(person), label, fields, media)
        }

        fun capture(gedcom: Gedcom): LocalTree = LocalTree(
            stamp = captures.incrementAndGet(),
            people = gedcom.people.orEmpty().map { capturePerson(it) },
            families = gedcom.families.orEmpty().map { family ->
                LocalFamily(
                    gedcomId = family.id,
                    geniId = GeniMapper.getGeniId(family),
                    partners = family.husbandRefs.orEmpty().map { it.ref } + family.wifeRefs.orEmpty().map { it.ref },
                    children = family.childRefs.orEmpty().map { it.ref },
                    fields = UnionFields.of(family)
                )
            }
        )
    }
}
