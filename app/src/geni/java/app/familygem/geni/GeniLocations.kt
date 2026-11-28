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

    /**
     * What is known of a record's Geni version: whether it is linked, and its last snapshot. A linked record without a readable
     * snapshot is [unavailable]: its structured location must not be written yet, or the parts it holds would be lost.
     */
    data class Lookup(val linked: Boolean, val snapshot: String?) {
        val unavailable: Boolean get() = linked && snapshot == null
    }

    fun lookup(context: Context, gedcomId: String?): Lookup {
        gedcomId ?: return Lookup(false, null)
        return try {
            val treeId = Global.settings.openTree
            val link = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { Geni.store(context).linkByGedcomId(treeId, gedcomId) }
            Lookup(link != null, link?.snapshot)
        } catch (e: Exception) {
            android.util.Log.w("GeniSync", "Snapshot of $gedcomId not read", e)
            Lookup(true, null)
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
