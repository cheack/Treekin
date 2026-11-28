package app.familygem.geni

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.familygem.Global
import app.familygem.R as AppR
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.ProfileDetails
import org.folg.gedcom.model.Person

/**
 * The "On Geni" section of the Facts tab: what Geni knows of a linked profile beyond the mapped fields, read from the saved snapshot
 * (no request, so it also shows offline and follows every profile refresh). Rows are read-only; they never reach the GEDCOM or an upload.
 */
object GeniProfileDetails {

    /** The details of the person's own profile link, or null for a person without a matching, live link or without snapshot data. */
    fun of(context: android.content.Context, person: Person): ProfileDetails? {
        if (!GeniFacts.isGeniTree()) return null
        val geniId = GeniMapper.getGeniId(person)?.takeIf { it.startsWith("profile-") } ?: return null
        return try {
            val treeId = Global.settings.openTree
            val link = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) { Geni.store(context).linkByGedcomId(treeId, person.id) }
            if (link == null || link.geniId != geniId || link.orphaned) null else ProfileDetails.ofSnapshot(link.snapshot)
        } catch (e: Exception) {
            Log.w("GeniSync", "Details of ${person.id} not read", e)
            null
        }
    }

    /** Adds the section to [layout] after the facts; nothing for a person without data to show. */
    fun place(context: android.content.Context, inflater: LayoutInflater, layout: LinearLayout, person: Person) {
        val details = of(context, person) ?: return
        val url = details.browserUrl(Geni.config.host)
        val rows = rows(context, details)
        if (rows.isEmpty() && url == null) return
        val title = inflater.inflate(AppR.layout.profile_facts_item, layout, false)
        title.isClickable = false
        title.findViewById<TextView>(AppR.id.profileFact_title).text = context.getString(R.string.geni_on_geni)
        title.findViewById<TextView>(AppR.id.profileFact_text).text = context.getString(R.string.geni_on_geni_note)
        layout.addView(title)
        for ((label, value) in rows) {
            val row = inflater.inflate(AppR.layout.profile_facts_item, layout, false)
            row.isClickable = false
            row.findViewById<TextView>(AppR.id.profileFact_title).text = label
            row.findViewById<TextView>(AppR.id.profileFact_text).text = value
            layout.addView(row)
        }
        if (url != null) {
            val row = inflater.inflate(AppR.layout.profile_facts_item, layout, false)
            row.findViewById<TextView>(AppR.id.profileFact_title).text = context.getString(R.string.geni_open_on_geni)
            row.findViewById<TextView>(AppR.id.profileFact_text).visibility = View.GONE
            row.setOnClickListener {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: Exception) {
                    Toast.makeText(context, url, Toast.LENGTH_LONG).show()
                }
            }
            layout.addView(row)
        }
    }

    /** Label and text of each row to show; a flag Geni omitted gets none, an explicit false shows as No. */
    fun rows(context: android.content.Context, d: ProfileDetails): List<Pair<String, String>> = buildList {
        fun yesNo(value: Boolean) = context.getString(if (value) AppR.string.yes else AppR.string.no)
        d.relationship?.let { add(context.getString(R.string.geni_detail_relationship) to it) }
        d.email?.let { add(context.getString(R.string.geni_detail_email) to it) }
        if (d.phones.isNotEmpty()) add(context.getString(R.string.geni_detail_phones) to d.phones.joinToString("\n"))
        d.residence?.let { add(context.getString(R.string.geni_detail_residence) to it) }
        d.managers?.takeIf { it > 0 }?.let { add(context.getString(R.string.geni_detail_managers) to context.resources.getQuantityString(R.plurals.geni_detail_managers_count, it, it)) }
        d.claimed?.let { add(context.getString(R.string.geni_detail_claimed) to yesNo(it)) }
        d.curator?.let { add(context.getString(R.string.geni_detail_curator) to yesNo(it)) }
        d.bigTree?.let { add(context.getString(R.string.geni_detail_big_tree) to yesNo(it)) }
        d.mergePending?.let { add(context.getString(R.string.geni_detail_merge_pending) to yesNo(it)) }
        d.events.forEach { add(it.name to listOfNotNull(it.date, it.place).joinToString(" · ")) }
    }
}
