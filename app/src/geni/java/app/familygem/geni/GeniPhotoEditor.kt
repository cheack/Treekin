package app.familygem.geni

import android.content.Context
import android.text.InputType
import android.util.Log
import android.view.Menu
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.familygem.Global
import app.familygem.U
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.map.GeniParams
import app.familygem.geni.map.PhotoDetails
import app.familygem.geni.sync.PhotoSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.folg.gedcom.model.Media
import org.folg.gedcom.model.Person

/** "Edit Geni photo details" of a Geni photo in the Media tab: title, description and date go to Geni through the queue. */
object GeniPhotoEditor {

    /**
     * Adds the action to the context menu of [media] when it is a Geni photo of a person of a Geni tree
     * (tagged with its Geni photo ID); any other media gets nothing. [onDone] refreshes the screen after a change.
     */
    fun addMenuItem(context: Context, menu: Menu, person: Person, media: Media, onDone: () -> Unit) {
        GeniGlue.install()
        val treeId = Global.settings.openTree
        val geniId = GeniMapper.getGeniId(person)
        val photoId = GeniMapper.getTag(media, PhotoSync.PHOTO_TAG)
        // Only inline media: the photos synced from Geni are never shared media records
        if (!GeniTrees.isGeni(treeId) || geniId == null || photoId == null || media.id != null) return
        menu.add(0, MENU_ID, 0, R.string.geni_photo_edit).setOnMenuItemClickListener {
            show(context, treeId, person, media, photoId, onDone)
            true
        }
        if (media.primary == null) menu.add(0, MENU_ID + 1, 0, R.string.geni_photo_set_main).setOnMenuItemClickListener {
            val personId = person.id
            val label = "Main photo: ${U.properName(person)}"
            queue(context, treeId, R.string.geni_photo_set_main_queued, onDone) { q ->
                q.enqueueSetMainPhoto(treeId, geniId, photoId, personId, label)
            }
            true
        }
        menu.add(0, MENU_ID + 2, 0, R.string.geni_photo_tag).setOnMenuItemClickListener {
            chooseToTag(context, treeId, person, photoId, onDone)
            true
        }
        menu.add(0, MENU_ID + 3, 0, R.string.geni_photo_untag).setOnMenuItemClickListener {
            // Read on the main thread: the tree is main-thread state
            val isMain = media.primary != null
            val personId = person.id
            val label = "Untag: ${U.properName(person)}"
            AlertDialog.Builder(context).setMessage(R.string.geni_photo_untag_confirm).setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.geni_photo_untag) { _, _ ->
                    queue(context, treeId, R.string.geni_photo_untag_queued, onDone) { q ->
                        q.enqueuePhotoTag(treeId, photoId, geniId, personId, false, label)
                        // A main photo goes when Geni has chosen another; any other leaves this person at once. The untag is queued already
                        if (!isMain) try {
                            PhotoSync(Geni.store(context), AppTreeHost).removeLocal(treeId, geniId, personId, photoId)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w("GeniSync", "Removing the untagged photo $photoId here failed", e)
                        }
                    }
                }.show()
            true
        }
    }

    private const val MENU_ID = 9

    /** Runs [work] with the queue off the main thread, then kicks the upload and shows [done]; a failure shows the failed message. */
    private fun queue(context: Context, treeId: Int, done: Int, onDone: () -> Unit, work: suspend (app.familygem.geni.sync.SyncQueue) -> Unit) {
        val owner = context as? LifecycleOwner ?: return
        owner.lifecycleScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { work(Geni.queue(context)) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("GeniSync", "Queueing a photo change failed", e)
                false
            }
            if (ok) {
                GeniSync.kick(context, treeId)
                Toast.makeText(context, done, Toast.LENGTH_LONG).show()
                onDone()
            } else Toast.makeText(context, R.string.geni_photo_edit_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** Lists the other people of the tree who are on Geni; the chosen one is tagged in the photo on Geni. */
    private fun chooseToTag(context: Context, treeId: Int, person: Person, photoId: String, onDone: () -> Unit) {
        val others = Global.gc.people.orEmpty().filter { it.id != person.id && GeniMapper.getGeniId(it)?.startsWith("profile-") == true }
        if (others.isEmpty()) {
            Toast.makeText(context, R.string.geni_photo_tag_none, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(context).setTitle(R.string.geni_photo_tag).setNegativeButton(android.R.string.cancel, null)
            .setItems(others.map { U.properName(it) }.toTypedArray()) { _, which ->
                val chosen = others[which]
                val chosenProfile = GeniMapper.getGeniId(chosen)!!
                val chosenId = chosen.id
                val label = "Tag: ${U.properName(chosen)}"
                queue(context, treeId, R.string.geni_photo_tag_queued, onDone) { q ->
                    q.enqueuePhotoTag(treeId, photoId, chosenProfile, chosenId, true, label)
                }
            }.show()
    }

    private fun show(context: Context, treeId: Int, person: Person, media: Media, photoId: String, onDone: () -> Unit) {
        val owner = context as? LifecycleOwner ?: return
        val shown = PhotoDetails.shown(media)
        // Taken when the dialog opens: a refresh while it is open must not change the starting point of this edit
        val base = if (PhotoDetails.isSynced(media)) PhotoDetails(PhotoDetails.syncedTitle(media), shown.description, shown.date) else shown
        val pad = (16 * context.resources.displayMetrics.density).toInt()
        fun field(hint: Int, text: String?, multiline: Boolean) = EditText(context).apply {
            setHint(hint)
            setText(text)
            inputType = InputType.TYPE_CLASS_TEXT or (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else 0) or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            if (multiline) minLines = 3
        }
        val title = field(R.string.geni_photo_edit_title, shown.title, false)
        val description = field(R.string.geni_photo_edit_description, shown.description, true)
        val date = field(R.string.geni_photo_edit_date, shown.date, false).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val hint = TextView(context).apply { setText(R.string.geni_photo_edit_date_hint); textSize = 12f }
        val form = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            listOf(title, description, date, hint).forEach { addView(it) }
        }
        val dialog = AlertDialog.Builder(context).setTitle(R.string.geni_photo_edit).setView(ScrollView(context).apply { addView(form) })
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
        dialog.setOnShowListener {
            // Set here so a date Geni cannot store keeps the dialog open
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val typed = date.text.toString().trim()
                val parsed = if (typed.isEmpty()) null else GeniParams.parseDate(typed)
                // An unchanged date stays as it is, even a range set on the website; a new one must be a single date
                if (typed.isNotEmpty() && (parsed == null || parsed.range != null) && !typed.equals(shown.date, ignoreCase = true)) {
                    date.error = context.getString(R.string.geni_photo_edit_date_invalid)
                    return@setOnClickListener
                }
                val wanted = PhotoDetails(PhotoDetails.clean(title.text.toString()), PhotoDetails.clean(description.text.toString()), if (typed.equals(shown.date, ignoreCase = true)) shown.date else GeniMapper.gedcomDate(parsed))
                val fields = shown.differences(wanted)
                dialog.dismiss()
                if (fields.isEmpty()) return@setOnClickListener // Unchanged: nothing is queued
                val label = "Photo details: ${U.properName(person)}"
                owner.lifecycleScope.launch {
                    val queued = try {
                        withContext(Dispatchers.IO) {
                            // The job is stored first so a failure in the local update cannot lose the edit
                            Geni.queue(context).enqueueUpdatePhoto(treeId, photoId, person.id, label, base, wanted, fields)
                            PhotoSync(Geni.store(context), AppTreeHost).applyOwnEdit(treeId, photoId, wanted, fields)
                        }
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("GeniSync", "Queueing the details of photo $photoId failed", e)
                        false
                    }
                    if (queued) {
                        GeniSync.kick(context, treeId)
                        Toast.makeText(context, R.string.geni_photo_edit_queued, Toast.LENGTH_LONG).show()
                        onDone()
                    } else Toast.makeText(context, R.string.geni_photo_edit_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
        dialog.show()
    }
}
