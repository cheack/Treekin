package app.familygem.geni

import android.content.Context
import app.familygem.Global
import app.familygem.geni.map.EventFields
import app.familygem.geni.map.LocationParts
import app.familygem.geni.map.ProfileFields
import app.familygem.geni.map.UnionFields
import org.folg.gedcom.model.EventFact

/** The structured places of a Geni tree as the editors see them: a fact resolved against Geni's last version of its record. */
object GeniLocations {

    /** Geni's last version of the person or family with this GEDCOM ID in the open tree; null if there is none or it cannot be read. */
    fun snapshot(context: Context, gedcomId: String?): String? {
        gedcomId ?: return null
        return try {
            val treeId = Global.settings.openTree
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { Geni.store(context).linkByGedcomId(treeId, gedcomId)?.snapshot }
        } catch (e: Exception) {
            android.util.Log.w("GeniSync", "Snapshot of $gedcomId not read", e)
            null
        }
    }

    /** The event with this GEDCOM [tag] in a snapshot of a person, or of a family if [family]. */
    fun baseline(snapshot: String?, tag: String, family: Boolean): EventFields? =
        snapshot?.let { if (family) UnionFields.ofSnapshot(it)?.event(tag) else ProfileFields.ofSnapshot(it)?.event(tag) }

    /**
     * The structured location the fact has: its stored parts, or for a legacy fact the ones of [baseline] (its place text
     * edited since is the new place name). Empty for a fact without place data.
     */
    fun effective(fact: EventFact?, baseline: EventFields?): LocationParts =
        EventFields.of(fact)?.resolvedAgainst(baseline)?.locationParts ?: LocationParts.EMPTY
}
