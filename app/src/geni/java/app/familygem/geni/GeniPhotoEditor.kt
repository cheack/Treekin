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
    }

    private const val MENU_ID = 9

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
