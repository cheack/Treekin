package app.familygem.geni.home

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.ActionBar
import androidx.core.view.ViewCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.familygem.Global
import app.familygem.Memory
import app.familygem.R
import app.familygem.U
import app.familygem.detail.FamilyActivity
import app.familygem.main.BaseFragment
import app.familygem.profile.ProfileActivity
import app.familygem.util.FileUtil
import app.familygem.util.sex
import com.bumptech.glide.Glide
import org.folg.gedcom.model.Person
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import app.familygem.geni.R as GR

/** Treekin's first screen of a tree: upcoming birthdays and recent events of the people on this device. */
class HomeFragment : BaseFragment(R.layout.geni_home_fragment) {

    companion object {
        /** MainActivity extra: an ordinary tree opening, shown on Home instead of the diagram. */
        const val OPEN = "${app.familygem.BuildConfig.APPLICATION_ID}.openHome"

        /** The MainActivity intent of an ordinary tree opening. */
        fun intent(context: android.content.Context): Intent =
            Intent(context, app.familygem.main.MainActivity::class.java).putExtra(OPEN, true)
    }

    private sealed class Item {
        data class Header(val text: String) : Item()
        /** [partner] is the second partner of a wedding anniversary. */
        data class Row(val event: FeedEvent, val person: Person, val partner: Person?, val upcoming: Boolean) : Item()
    }

    private val items = mutableListOf<Item>()
    private val adapter = HomeAdapter()
    private var list: RecyclerView? = null
    private var empty: TextView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val recycler = view.findViewById<RecyclerView>(R.id.geni_home_list)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.adapter = adapter
        list = recycler
        empty = view.findViewById(R.id.geni_home_empty)
        val side = U.dpToPx(8F)
        interfacer = { insets ->
            recycler.updatePadding(
                insets.left + side, 0, insets.right + side, insets.bottom + resources.getDimensionPixelSize(R.dimen.bottom_padding)
            )
        }
        ViewCompat.requestApplyInsets(view)
    }

    override fun onDestroyView() {
        list = null
        empty = null
        super.onDestroyView()
    }

    override fun updateToolbar(bar: ActionBar, menu: Menu, inflater: MenuInflater) {
        bar.title = getString(GR.string.geni_home)
    }

    /** Rebuilt at every return to Home: edits and newly loaded relatives show up at once. */
    override fun onResume() {
        mayShow = true
        super.onResume()
    }

    override fun showContent() {
        val gedcom = Global.gc ?: return
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val feed = RecentEvents.build(gedcom.people.orEmpty(), gedcom.families.orEmpty(), Global.settings.currentTree.settings.lifeSpan, now, zone)
        val today = now.atZone(zone).toLocalDate()
        items.clear()
        if (feed.upcoming.isNotEmpty()) {
            items += Item.Header(getString(GR.string.geni_home_upcoming))
            feed.upcoming.forEach { event -> row(gedcom, event, true)?.let { items += it } }
        }
        if (feed.recent.isNotEmpty()) {
            items += Item.Header(getString(GR.string.geni_home_recent))
            feed.recent.forEach { (date, events) ->
                items += Item.Header(dayText(date, today, zone))
                events.forEach { event -> row(gedcom, event, false)?.let { items += it } }
            }
        }
        @Suppress("NotifyDataSetChanged") // The whole feed is rebuilt
        adapter.notifyDataSetChanged()
        empty?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun row(gedcom: org.folg.gedcom.model.Gedcom, event: FeedEvent, upcoming: Boolean): Item.Row? {
        val person = gedcom.getPerson(event.personId) ?: return null
        val partner = event.partnerId?.let { gedcom.getPerson(it) ?: return null }
        return Item.Row(event, person, partner, upcoming)
    }

    /** "Today", "Yesterday", "Tomorrow" or the localized date with the weekday, [short] in a row. */
    private fun dayText(date: LocalDate, today: LocalDate, zone: ZoneId, short: Boolean = false): String = when (date) {
        today -> getString(GR.string.geni_home_today)
        today.minusDays(1) -> getString(GR.string.geni_home_yesterday)
        today.plusDays(1) -> getString(GR.string.geni_home_tomorrow)
        else -> {
            var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY
            if (short) flags = flags or DateUtils.FORMAT_ABBREV_ALL
            flags = flags or if (date.year == today.year) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR
            DateUtils.formatDateTime(requireContext(), date.atStartOfDay(zone).toInstant().toEpochMilli(), flags)
        }
    }

    private fun openProfile(person: Person) {
        Memory.setLeader(person)
        startActivity(Intent(requireContext(), ProfileActivity::class.java))
    }

    private fun openFamily(familyId: String) {
        val family = Global.gc?.getFamily(familyId) ?: return
        Memory.setLeader(family)
        startActivity(Intent(requireContext(), FamilyActivity::class.java))
    }

    /** The person's main photo, or a silhouette by sex when there is none: every row has a picture. */
    private fun showPhoto(image: ImageView, person: Person) {
        Glide.with(image).clear(image) // A recycled row may still be loading another person's photo
        image.setImageDrawable(null)
        FileUtil.showMainMedia(person, image)
        if (image.visibility != View.VISIBLE) {
            image.visibility = View.VISIBLE
            image.setImageResource(if (person.sex.isFemale()) R.drawable.geni_silhouette_female else R.drawable.geni_silhouette)
        }
    }

    private inner class HomeAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) = if (items[position] is Item.Header) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val layout = if (viewType == 0) R.layout.geni_home_header else R.layout.geni_home_row
            return object : RecyclerView.ViewHolder(LayoutInflater.from(parent.context).inflate(layout, parent, false)) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView
            when (val item = items[position]) {
                is Item.Header -> (view as TextView).text = item.text
                is Item.Row -> bindRow(view, item)
            }
        }

        private fun bindRow(view: View, item: Item.Row) {
            val event = item.event
            val person = item.person
            val name = U.properName(person)
            view.findViewById<TextView>(R.id.geni_home_name).text = item.partner?.let { "$name & ${U.properName(it)}" } ?: name
            val parts = mutableListOf<String>()
            if (item.upcoming) parts += dayText(event.date, LocalDate.now(), ZoneId.systemDefault(), short = true)
            val years = event.years
            when (event.kind) {
                FeedEvent.Kind.BIRTHDAY -> {
                    if (!item.upcoming) parts += getString(GR.string.geni_home_birthday)
                    val plural = if (item.upcoming) GR.plurals.geni_home_turns else GR.plurals.geni_home_turned
                    parts += resources.getQuantityString(plural, years, years)
                }
                FeedEvent.Kind.WEDDING_ANNIVERSARY -> parts += resources.getQuantityString(GR.plurals.geni_home_wedding_years, years, years)
                FeedEvent.Kind.MEMORIAL -> parts += resources.getQuantityString(GR.plurals.geni_home_memorial_years, years, years)
                FeedEvent.Kind.PROFILE_UPDATED -> parts += getString(GR.string.geni_home_profile_updated)
            }
            view.findViewById<TextView>(R.id.geni_home_detail).apply {
                text = parts.joinToString(" · ")
                val icon = when (event.kind) {
                    FeedEvent.Kind.BIRTHDAY -> GR.drawable.geni_cake
                    FeedEvent.Kind.WEDDING_ANNIVERSARY -> GR.drawable.geni_heart
                    FeedEvent.Kind.MEMORIAL -> GR.drawable.geni_flower
                    FeedEvent.Kind.PROFILE_UPDATED -> GR.drawable.geni_edit
                }
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            }
            showPhoto(view.findViewById(R.id.geni_home_image), person)
            view.findViewById<View>(R.id.geni_home_person).setOnClickListener {
                // A wedding anniversary opens the couple's family, the other events the person
                event.familyId?.let { openFamily(it) } ?: openProfile(person)
            }
        }
    }
}
