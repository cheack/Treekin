package app.familygem.geni.home

import android.content.Intent
import android.util.Log
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.familygem.Global
import app.familygem.Memory
import app.familygem.R
import app.familygem.U
import app.familygem.detail.FamilyActivity
import app.familygem.main.BaseFragment
import app.familygem.profile.ProfileActivity
import app.familygem.geni.Geni
import app.familygem.geni.sync.FeedRecord
import app.familygem.util.FileUtil
import app.familygem.util.sex
import com.bumptech.glide.Glide
import org.folg.gedcom.model.Person
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import app.familygem.geni.R as GR

/** Treekin's first screen of a tree: upcoming birthdays and recent events of the people on this device. */
class HomeFragment : BaseFragment(R.layout.geni_home_fragment) {

    companion object {
        /** MainActivity extra: open on Tree (DiagramFragment) showing [app.familygem.Global.indi], instead of Home. */
        const val OPEN_TREE = "${app.familygem.BuildConfig.APPLICATION_ID}.openTree"

        /** The MainActivity intent of an ordinary tree opening: Home is MainActivity's default. */
        fun intent(context: android.content.Context): Intent = Intent(context, app.familygem.main.MainActivity::class.java)

        /** The MainActivity intent that shows a particular person in Tree. */
        @JvmStatic
        fun treeIntent(context: android.content.Context): Intent = intent(context).putExtra(OPEN_TREE, true)
    }

    private sealed class Item {
        data class Header(val text: String) : Item()
        /** [person] is null for a removal (the person left the tree); [partner] is the second partner of a wedding anniversary. */
        data class Row(val event: FeedEvent, val person: Person?, val partner: Person?, val upcoming: Boolean) : Item()
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
        // Nothing may keep the destroyed views: the insets callback captures the list, the adapter is shared with the next view
        interfacer = null
        loading?.cancel()
        list?.adapter = null
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

    private var loading: Job? = null

    /** Reads the sync's records off the main thread, then builds the feed from them and the tree. */
    override fun showContent() {
        val treeId = Global.settings.openTree
        val context = requireContext().applicationContext
        loading?.cancel()
        loading = viewLifecycleOwner.lifecycleScope.launch {
            val since = Instant.now().minus(RecentEvents.RECENT_DAYS + 1, ChronoUnit.DAYS).toEpochMilli()
            val records = try {
                withContext(Dispatchers.IO) { Geni.store(context).feedRecords(treeId, since) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("GeniHome", "Feed records not read", e)
                emptyList()
            }
            if (Global.settings.openTree == treeId) render(records)
        }
    }

    private fun render(records: List<FeedRecord>) {
        val gedcom = Global.gc ?: return
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val feed = RecentEvents.build(
            gedcom.people.orEmpty(), gedcom.families.orEmpty(), Global.settings.currentTree.settings.lifeSpan, now, zone, records
        )
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
        if (event.kind.isRemoval) return Item.Row(event, null, null, upcoming)
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

    /** The person's main photo, or a silhouette by sex when there is none (or the person is gone): every row has a picture. */
    private fun showPhoto(image: ImageView, person: Person?) {
        // A recycled row may still be loading another person's photo, or running the fallback of a failed load
        (image.getTag(R.id.tag_job) as? Job)?.cancel()
        image.setTag(R.id.tag_job, null)
        Glide.with(image).clear(image)
        image.setImageDrawable(null)
        if (person == null) {
            image.visibility = View.VISIBLE
            image.setImageResource(R.drawable.geni_silhouette)
            return
        }
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
            val name = person?.let { U.properName(it) } ?: event.name ?: event.personId
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
                FeedEvent.Kind.PROFILE_ADDED -> parts += getString(GR.string.geni_home_profile_added)
                FeedEvent.Kind.PROFILE_UPDATED -> parts += getString(GR.string.geni_home_profile_updated)
                FeedEvent.Kind.GONE_ON_GENI -> parts += getString(GR.string.geni_home_gone_on_geni)
                FeedEvent.Kind.DELETED_ON_GENI -> parts += getString(GR.string.geni_home_deleted_on_geni)
                FeedEvent.Kind.REMOVED_FROM_DEVICE -> parts += getString(GR.string.geni_home_removed_from_device)
            }
            view.findViewById<TextView>(R.id.geni_home_detail).apply {
                text = parts.joinToString(" · ")
                val icon = when (event.kind) {
                    FeedEvent.Kind.BIRTHDAY -> GR.drawable.geni_cake
                    FeedEvent.Kind.WEDDING_ANNIVERSARY -> GR.drawable.geni_heart
                    FeedEvent.Kind.MEMORIAL -> GR.drawable.geni_flower
                    FeedEvent.Kind.PROFILE_ADDED -> GR.drawable.geni_person_add
                    FeedEvent.Kind.PROFILE_UPDATED -> GR.drawable.geni_edit
                    FeedEvent.Kind.GONE_ON_GENI, FeedEvent.Kind.DELETED_ON_GENI, FeedEvent.Kind.REMOVED_FROM_DEVICE -> GR.drawable.geni_person_remove
                }
                setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            }
            showPhoto(view.findViewById(R.id.geni_home_image), person)
            val target = view.findViewById<View>(R.id.geni_home_person)
            if (person == null) { // Nothing to open: the person left the tree
                target.setOnClickListener(null)
                target.isClickable = false
            } else target.setOnClickListener {
                // A wedding anniversary opens the couple's family, the other events the person
                event.familyId?.let { openFamily(it) } ?: openProfile(person)
            }
        }
    }
}
