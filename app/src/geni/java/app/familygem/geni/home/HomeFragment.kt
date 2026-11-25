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
import app.familygem.constant.Extra
import app.familygem.main.BaseFragment
import app.familygem.profile.ProfileActivity
import app.familygem.util.FileUtil
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
        data class Row(val event: FeedEvent, val person: Person, val upcoming: Boolean) : Item()
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
        val feed = RecentEvents.build(gedcom.people.orEmpty(), Global.settings.currentTree.settings.lifeSpan, now, zone)
        val today = now.atZone(zone).toLocalDate()
        items.clear()
        if (feed.upcoming.isNotEmpty()) {
            items += Item.Header(getString(GR.string.geni_home_upcoming))
            feed.upcoming.forEach { event -> gedcom.getPerson(event.personId)?.let { items += Item.Row(event, it, true) } }
        }
        if (feed.recent.isNotEmpty()) {
            items += Item.Header(getString(GR.string.geni_home_recent))
            feed.recent.forEach { (date, events) ->
                items += Item.Header(dayText(date, today, zone))
                events.forEach { event -> gedcom.getPerson(event.personId)?.let { items += Item.Row(event, it, false) } }
            }
        }
        @Suppress("NotifyDataSetChanged") // The whole feed is rebuilt
        adapter.notifyDataSetChanged()
        empty?.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    /** "Today", "Yesterday", "Tomorrow" or the localized date with the weekday. */
    private fun dayText(date: LocalDate, today: LocalDate, zone: ZoneId): String = when (date) {
        today -> getString(GR.string.geni_home_today)
        today.minusDays(1) -> getString(GR.string.geni_home_yesterday)
        today.plusDays(1) -> getString(GR.string.geni_home_tomorrow)
        else -> {
            var flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY
            flags = flags or if (date.year == today.year) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR
            DateUtils.formatDateTime(requireContext(), date.atStartOfDay(zone).toInstant().toEpochMilli(), flags)
        }
    }

    private fun openProfile(person: Person, page: Int? = null) {
        Memory.setLeader(person)
        val intent = Intent(requireContext(), ProfileActivity::class.java)
        page?.let { intent.putExtra(Extra.PAGE, it) }
        startActivity(intent)
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
            view.findViewById<TextView>(R.id.geni_home_name).text = U.properName(person)
            val parts = mutableListOf<String>()
            if (event.kind == FeedEvent.Kind.BIRTHDAY) {
                val today = LocalDate.now()
                if (item.upcoming) parts += dayText(event.date, today, ZoneId.systemDefault())
                else parts += getString(GR.string.geni_home_birthday)
                val plural = if (item.upcoming) GR.plurals.geni_home_turns else GR.plurals.geni_home_turned
                parts += resources.getQuantityString(plural, event.age, event.age)
            } else parts += getString(GR.string.geni_home_profile_updated)
            view.findViewById<TextView>(R.id.geni_home_detail).text = parts.joinToString(" · ")
            FileUtil.showMainMedia(person, view.findViewById<ImageView>(R.id.geni_home_image))
            view.findViewById<View>(R.id.geni_home_person).setOnClickListener { openProfile(person) }
            val photos = view.findViewById<View>(R.id.geni_home_photos)
            // Today's and past birthdays: the person's media, where the photos of the day are kept
            if (event.kind == FeedEvent.Kind.BIRTHDAY && !item.upcoming) {
                photos.visibility = View.VISIBLE
                photos.setOnClickListener { openProfile(person, 0) }
            } else photos.visibility = View.GONE
        }
    }
}
