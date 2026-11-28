package app.familygem.geni.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.familygem.geni.Geni
import app.familygem.geni.R
import app.familygem.geni.api.GeniProfile
import app.familygem.geni.backlog.BacklogItem
import app.familygem.geni.backlog.BacklogOperation
import app.familygem.geni.backlog.BacklogSection
import app.familygem.geni.backlog.BacklogStatus
import app.familygem.geni.backlog.SyncBacklog
import app.familygem.geni.sync.DownloadKind
import app.familygem.geni.sync.QueueStatus
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Date

/**
 * What Treekin is sending to Geni, downloading, holding back and what needs the user, across every Geni tree.
 * Reads only what the device knows: opening it starts no sync and needs no network or login.
 * Failed changes and conflicts are handled in the Geni account screen.
 */
class GeniSyncBacklogActivity : AppCompatActivity() {

    private sealed interface UiState {
        data class Ready(val items: List<BacklogItem>, val names: Map<Pair<Int, String>, String>) : UiState
        data object Unreadable : UiState
    }

    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var loading: ProgressBar
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.geni_activity_sync_backlog)
        setSupportActionBar(findViewById<Toolbar>(R.id.geni_backlog_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        scroll = findViewById(R.id.geni_backlog_scroll)
        list = findViewById(R.id.geni_backlog_list)
        loading = findViewById(R.id.geni_backlog_loading)
        message = findViewById(R.id.geni_backlog_message)
        // Only observes: the work lives in the app's runners, so a rotation or a new visit shows it as it is
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { backlog().collect(::render) } }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun backlog(): Flow<UiState> {
        val app = applicationContext
        val names = mutableMapOf<Pair<Int, String>, String>() // Kept while collecting: a name rarely changes during a download
        return flow {
            // Inside the flow, so a store that cannot be opened shows as unreadable
            val store = Geni.store(app)
            val queue = Geni.queueOrNull(app)
            val rows = combine(
                store.observeJobs(), queue?.status ?: MutableStateFlow(QueueStatus()), Geni.uploadWaits.waits, Geni.downloads.tasks
            ) { jobs, status, waits, downloads ->
                SyncBacklog.build(jobs, status, waits, downloads) { queue?.waitReason(it) }
            }
            emitAll(rows.map { items ->
                for (item in items) for (geniId in listOfNotNull(item.targetGeniId, (item.status as? BacklogStatus.Downloading)?.stage?.geniId)) {
                    val key = item.treeId to geniId
                    if (key !in names) profileName(item.treeId, geniId)?.let { names[key] = it }
                }
                UiState.Ready(items, names.toMap())
            })
        }.flowOn(Dispatchers.IO).catch<UiState> { e ->
            if (e is CancellationException) throw e
            Log.e("GeniSync", "Sync backlog not read", e)
            emit(UiState.Unreadable)
        }
    }

    /** The name of a cached profile, from its last loaded snapshot; null if not cached yet. */
    private suspend fun profileName(treeId: Int, geniId: String): String? = try {
        Geni.store(applicationContext).link(treeId, geniId)?.snapshot?.let { Gson().fromJson(it, GeniProfile::class.java) }?.let { profile ->
            profile.name?.takeIf { it.isNotBlank() }
                ?: listOfNotNull(profile.firstName, profile.lastName).joinToString(" ").takeIf { it.isNotBlank() }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null // The row shows without a name
    }

    private fun render(state: UiState) {
        loading.visibility = View.GONE
        if (state is UiState.Unreadable) return showMessage(getString(R.string.geni_backlog_unreadable))
        state as UiState.Ready
        val host = Geni.host
        // A deleted tree's rows go with it, also before its stored jobs are wiped
        val items = state.items.filter { host?.treeExists(it.treeId) != false && !Geni.isCleaning(it.treeId) }
        if (items.isEmpty()) return showMessage(getString(R.string.geni_backlog_empty))
        message.visibility = View.GONE
        scroll.visibility = View.VISIBLE
        val focusedKey = focusedRowKey()
        // Views are kept by key and filled again in place: a progress update leaves the list and the focus as they are
        val wanted = mutableListOf<View>()
        for (section in BacklogSection.entries) {
            val rows = items.filter { it.section == section }
            if (rows.isEmpty()) continue
            wanted += headers.getOrPut(section) { header(section) }
            for (item in rows) wanted += rowFor(item.key).also { it.bind(item, state.names, host?.treeTitle(item.treeId)) }.view
        }
        val keys = items.map { it.key }.toSet()
        rowViews.keys.retainAll(keys) // Finished work leaves
        val unchanged = list.childCount == wanted.size && wanted.indices.all { list.getChildAt(it) === wanted[it] }
        if (!unchanged) {
            list.removeAllViews()
            wanted.forEach { list.addView(it) }
            // A row that moved to another section was detached: the keyboard focus goes back to it
            focusedKey?.let { key -> rowViews[key]?.view?.takeIf { it.isFocusable && !it.hasFocus() }?.requestFocus() }
        }
    }

    private val headers = mutableMapOf<BacklogSection, View>()
    private val rowViews = mutableMapOf<String, Row>()

    private fun rowFor(key: String) = rowViews.getOrPut(key) { Row(key) }

    /** Key of the row holding the keyboard focus, if any. */
    private fun focusedRowKey(): String? {
        var view: View? = list.findFocus()
        while (view != null && view !== list) {
            (view.tag as? String)?.let { return it }
            view = view.parent as? View
        }
        return null
    }

    private fun showMessage(text: String) {
        scroll.visibility = View.GONE
        message.visibility = View.VISIBLE
        message.text = text
    }

    private val dp get() = resources.displayMetrics.density

    private fun header(section: BacklogSection): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((16 * dp).toInt(), (20 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt())
        addView(TextView(context).apply {
            setText(when (section) {
                BacklogSection.IN_PROGRESS -> R.string.geni_backlog_in_progress
                BacklogSection.WAITING -> R.string.geni_backlog_waiting
                BacklogSection.NEEDS_ATTENTION -> R.string.geni_backlog_needs_attention
            })
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(typeface, Typeface.BOLD)
            accessibilityHeading()
        })
        if (section == BacklogSection.NEEDS_ATTENTION) addView(TextView(context).apply {
            setText(R.string.geni_backlog_attention_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            alpha = 0.7f
        })
    }

    private fun TextView.accessibilityHeading() {
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    /** The views of one backlog row, kept while its key stays in the list. */
    private inner class Row(key: String) {
        private val operation = TextView(this@GeniSyncBacklogActivity).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }
        private val where = TextView(this@GeniSyncBacklogActivity).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f) }
        private val status = TextView(this@GeniSyncBacklogActivity).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            alpha = 0.7f
        }
        private val progress = ProgressBar(this@GeniSyncBacklogActivity, null, android.R.attr.progressBarStyleHorizontal)
        val view = LinearLayout(this@GeniSyncBacklogActivity).apply {
            tag = key
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            addView(operation)
            addView(where)
            addView(status)
            addView(progress, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        fun bind(item: BacklogItem, names: Map<Pair<Int, String>, String>, treeTitle: String?) {
            val target = item.target ?: item.targetGeniId?.let { names[item.treeId to it] }
            operation.text = getString(operationLabel(item.operation))
            val line = listOfNotNull(treeTitle, target).joinToString(" · ")
            where.text = line
            where.visibility = if (line.isEmpty()) View.GONE else View.VISIBLE
            status.text = statusText(item, names)
            if (item.section == BacklogSection.IN_PROGRESS) {
                progress.visibility = View.VISIBLE
                val total = item.total
                // A branch pass, a listing or a single file has no known size
                val known = total != null && total > 0
                if (progress.isIndeterminate != !known) progress.isIndeterminate = !known
                if (known) {
                    progress.max = total!!
                    progress.progress = item.done ?: 0
                }
            } else progress.visibility = View.GONE
            // Retry, dismiss and conflict choices stay in the account screen, with the warning before a retried add
            val attention = item.section == BacklogSection.NEEDS_ATTENTION
            if (view.isClickable != attention) {
                view.isClickable = attention
                view.isFocusable = attention
                if (attention) {
                    val outValue = TypedValue()
                    theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
                    view.setBackgroundResource(outValue.resourceId)
                    view.setOnClickListener { startActivity(Intent(this@GeniSyncBacklogActivity, GeniLoginActivity::class.java)) }
                } else {
                    view.background = null
                    view.setOnClickListener(null)
                    view.isClickable = false // setOnClickListener(null) leaves it clickable
                }
            }
        }
    }

    private fun statusText(item: BacklogItem, names: Map<Pair<Int, String>, String>): String = when (val status = item.status) {
        BacklogStatus.Sending -> getString(R.string.geni_backlog_sending)
        is BacklogStatus.Downloading -> {
            val stage = status.stage
            val parts = mutableListOf<String>()
            if (stage == null) parts += getString(R.string.geni_backlog_downloading)
            else {
                parts += getString(operationLabel(BacklogOperation.of(stage.kind)))
                stage.geniId?.let { names[item.treeId to it] }?.takeIf { stage.kind != DownloadKind.ROOT_LOAD }?.let { parts += it }
            }
            val stageTotal = stage?.total
            when {
                stageTotal != null && stageTotal > 0 -> parts += getString(R.string.geni_backlog_count_of, stage.done, stageTotal)
                item.total != null -> parts += getString(R.string.geni_backlog_count_of, item.done ?: 0, item.total)
                item.done != null -> parts += getString(R.string.geni_backlog_count_done, item.done)
            }
            parts.joinToString(" · ")
        }
        BacklogStatus.QueuedDownload -> getString(R.string.geni_backlog_queued_download)
        BacklogStatus.Queued -> getString(R.string.geni_backlog_queued)
        BacklogStatus.PhotosOff -> getString(R.string.geni_backlog_photos_off)
        BacklogStatus.CropHold -> getString(R.string.geni_backlog_crop_hold)
        BacklogStatus.Verifying -> getString(R.string.geni_backlog_verifying)
        BacklogStatus.NeedsLogin -> getString(R.string.geni_backlog_needs_login)
        is BacklogStatus.RetryAt -> getString(R.string.geni_backlog_retry_at, DateFormat.getTimeFormat(this).format(Date(status.atMillis)))
        BacklogStatus.Interrupted -> getString(R.string.geni_backlog_interrupted)
        BacklogStatus.Failed -> getString(R.string.geni_backlog_failed)
        BacklogStatus.FailedInterrupted -> getString(R.string.geni_backlog_failed_interrupted)
        BacklogStatus.Conflict -> getString(R.string.geni_backlog_conflict)
    }

    private fun operationLabel(operation: BacklogOperation) = when (operation) {
        BacklogOperation.UPDATE_PROFILE -> R.string.geni_backlog_op_update_profile
        BacklogOperation.UPDATE_RELATIONSHIP -> R.string.geni_backlog_op_update_relationship
        BacklogOperation.ADD_PERSON -> R.string.geni_backlog_op_add_person
        BacklogOperation.DELETE_PERSON -> R.string.geni_backlog_op_delete_person
        BacklogOperation.ADD_PHOTO -> R.string.geni_backlog_op_add_photo
        BacklogOperation.DELETE_PHOTO -> R.string.geni_backlog_op_delete_photo
        BacklogOperation.UPDATE_PHOTO -> R.string.geni_backlog_op_update_photo
        BacklogOperation.TAG_PHOTO -> R.string.geni_backlog_op_tag_photo
        BacklogOperation.UNTAG_PHOTO -> R.string.geni_backlog_op_untag_photo
        BacklogOperation.SET_MAIN_PHOTO -> R.string.geni_backlog_op_set_main_photo
        BacklogOperation.PREFETCH -> R.string.geni_backlog_op_prefetch
        BacklogOperation.KEEP_OFFLINE -> R.string.geni_backlog_op_keep_offline
        BacklogOperation.DAILY_REFRESH -> R.string.geni_backlog_op_daily_refresh
        BacklogOperation.PERSON_REFRESH -> R.string.geni_backlog_op_person_refresh
        BacklogOperation.ROOT_LOAD -> R.string.geni_backlog_op_root_load
        BacklogOperation.ALBUM_LIST -> R.string.geni_backlog_op_album_list
        BacklogOperation.ALBUM_DOWNLOAD -> R.string.geni_backlog_op_album_download
        BacklogOperation.PHOTO_DOWNLOAD -> R.string.geni_backlog_op_photo_download
        BacklogOperation.ADD_DOCUMENT -> R.string.geni_backlog_op_add_document
        BacklogOperation.DELETE_DOCUMENT -> R.string.geni_backlog_op_delete_document
        BacklogOperation.UPDATE_DOCUMENT -> R.string.geni_backlog_op_update_document
        BacklogOperation.TAG_DOCUMENT -> R.string.geni_backlog_op_tag_document
        BacklogOperation.UNTAG_DOCUMENT -> R.string.geni_backlog_op_untag_document
        BacklogOperation.DOCUMENT_LIST -> R.string.geni_backlog_op_document_list
        BacklogOperation.DOCUMENT_DOWNLOAD -> R.string.geni_backlog_op_document_download
    }
}
