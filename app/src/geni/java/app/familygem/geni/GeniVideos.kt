package app.familygem.geni

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Log
import android.view.Menu
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.familygem.BuildConfig
import app.familygem.Global
import app.familygem.U
import app.familygem.geni.api.VideoTypes
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.sync.DeleteRules
import app.familygem.geni.sync.JobKind
import app.familygem.geni.sync.VideoEntry
import app.familygem.geni.sync.VideoSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.folg.gedcom.model.Media
import org.folg.gedcom.model.Person
import java.io.File

/**
 * Geni videos of a profile: the list with on-demand download and opening, an explicit upload, and for a video on the device
 * the actions of its context menu (details, tag, untag) and delete dialog (remove from this device, untag on Geni, delete on Geni).
 * Nothing changes on Geni without the user's tap; every change goes through the upload queue.
 */
object GeniVideos {

    /** The list of the person's videos on Geni; a tap downloads (if needed) and opens one. */
    fun show(activity: Activity, treeId: Int, geniId: String, personGedcomId: String, onChanged: () -> Unit) {
        val owner = activity as? LifecycleOwner ?: return
        val loader = Geni.videoLoader(activity)
        Toast.makeText(activity, R.string.geni_videos_loading, Toast.LENGTH_SHORT).show()
        owner.lifecycleScope.launch {
            // A failed listing (offline) still opens the dialog: an upload is queued without the network
            var failure: String? = null
            val listing = try {
                loader.list(treeId, geniId, personGedcomId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = activity.getString(R.string.geni_videos_failed, e.message ?: e.javaClass.simpleName)
                null
            }
            if (listing != null && listing.removed > 0) {
                Toast.makeText(activity, activity.getString(R.string.geni_videos_removed, listing.removed), Toast.LENGTH_SHORT).show()
                onChanged()
            }
            val rows = listing?.entries.orEmpty().toMutableList()
            val adapter = object : ArrayAdapter<VideoEntry>(activity, 0, rows) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View = row(activity, getItem(position)!!)
            }
            val dialog = AlertDialog.Builder(activity).setTitle(R.string.geni_videos).setAdapter(adapter, null)
                .setNeutralButton(R.string.geni_video_upload, null).setNegativeButton(R.string.geni_close, null).create()
            if (rows.isEmpty()) dialog.setMessage(failure ?: activity.getString(R.string.geni_videos_empty))
            dialog.show()
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                dialog.dismiss()
                chooseFile(activity, treeId, geniId, personGedcomId, onChanged)
            }
            dialog.listView.setOnItemClickListener { _, _, position, _ ->
                val entry = rows[position]
                owner.lifecycleScope.launch {
                    if (!entry.playable) {
                        // No playback URL yet: the next listing may carry one
                        Toast.makeText(activity, R.string.geni_video_unavailable_message, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    try {
                        // Also for a copy already on the device: the media is attached again if the person lost it (an unsaved tree)
                        if (loader.download(treeId, geniId, personGedcomId, entry.video)) {
                            rows[position] = entry.copy(downloaded = true)
                            adapter.notifyDataSetChanged()
                            onChanged()
                        }
                        openCopy(activity, treeId, geniId, entry.video.id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Toast.makeText(activity, activity.getString(R.string.geni_video_download_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun row(activity: Activity, entry: VideoEntry): View {
        val dp = activity.resources.displayMetrics.density
        val video = entry.video
        val title = TextView(activity).apply { text = video.title ?: video.id; textSize = 16f }
        val state = TextView(activity).apply {
            text = activity.getString(when {
                !entry.playable -> R.string.geni_video_unavailable
                entry.downloaded -> R.string.geni_video_on_device
                else -> R.string.geni_video_tap_to_download
            })
            textSize = 13f
            alpha = 0.7f
        }
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            addView(title)
            addView(state)
        }
    }

    /** Opens the device's copy of the video with the app the user picks, as the file screen does for any media. */
    private suspend fun openCopy(context: Context, treeId: Int, geniId: String, videoId: String) {
        val link = Geni.store(context).photoLinks(treeId, geniId).firstOrNull { it.geniPhotoId == videoId } ?: return
        val file = link.fileName?.let { File(AppTreeHost.mediaDir(treeId), it) }?.takeIf { it.isFile } ?: return
        val uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(Intent.createChooser(intent, null))
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(context, R.string.geni_video_open_failed, Toast.LENGTH_LONG).show()
        }
    }

    // ---- Upload ----

    private var pickerCounter = 0

    /** Asks the system for a file, with no screen of its own: the result comes through the activity's result registry. */
    private fun chooseFile(activity: Activity, treeId: Int, geniId: String, personGedcomId: String, onDone: () -> Unit) {
        val host = activity as? ComponentActivity ?: return
        Toast.makeText(activity, R.string.geni_video_upload_hint, Toast.LENGTH_SHORT).show()
        lateinit var launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>
        launcher = host.activityResultRegistry.register("geni-video-${pickerCounter++}", ActivityResultContracts.OpenDocument()) { uri ->
            launcher.unregister()
            if (uri != null) describe(activity, treeId, geniId, personGedcomId, uri, onDone)
        }
        launcher.launch(arrayOf("video/mp4"))
    }

    /** Checks the chosen file, then asks for the title and description before anything is queued. */
    private fun describe(activity: Activity, treeId: Int, geniId: String, personGedcomId: String, uri: Uri, onDone: () -> Unit) {
        val owner = activity as? LifecycleOwner ?: return
        // The provider may be slow: its answers are read off the main thread
        owner.lifecycleScope.launch {
            val (name, size, contentType) = try {
                withContext(Dispatchers.IO) { readMeta(activity, uri) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("GeniSync", "Reading the chosen video failed", e)
                Toast.makeText(activity, activity.getString(R.string.geni_video_upload_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                return@launch
            }
            confirm(activity, treeId, geniId, personGedcomId, uri, name, size, contentType, onDone)
        }
    }

    private fun readMeta(activity: Activity, uri: Uri): Triple<String, Long, String?> {
        val resolver = activity.contentResolver
        var name = "video.mp4"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.let { name = it }
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
        var contentType = VideoTypes.contentTypeFor(name)
        if (contentType == null && resolver.getType(uri) == "video/mp4") {
            // A name without the extension: the provider says it is an MP4
            name = name.substringBeforeLast('.') + ".mp4"
            contentType = VideoTypes.contentTypeFor(name)
        }
        return Triple(name, size, contentType)
    }

    private fun confirm(
        activity: Activity, treeId: Int, geniId: String, personGedcomId: String, uri: Uri, name: String, size: Long, contentType: String?, onDone: () -> Unit
    ) {
        if (contentType == null) {
            Toast.makeText(activity, R.string.geni_video_unsupported, Toast.LENGTH_LONG).show()
            return
        }
        if (size > VideoTypes.MAX_UPLOAD_BYTES) {
            Toast.makeText(activity, R.string.geni_video_too_big, Toast.LENGTH_LONG).show()
            return
        }
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val title = EditText(activity).apply {
            setHint(R.string.geni_photo_edit_title)
            setText(name.substringBeforeLast('.'))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val description = EditText(activity).apply {
            setHint(R.string.geni_photo_edit_description)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3
        }
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(title)
            addView(description)
        }
        val fileName = name
        AlertDialog.Builder(activity).setTitle(R.string.geni_video_upload).setView(form).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                queueUpload(activity, treeId, geniId, personGedcomId, uri, fileName,
                    app.familygem.geni.map.PhotoDetails.clean(title.text.toString()), app.familygem.geni.map.PhotoDetails.clean(description.text.toString()), onDone)
            }.show()
    }

    /**
     * Copies the file into the tree's media folder, adds its media to the person (marked as waiting, so it is no photo) and queues the upload.
     * The file and the job outlive the app: after a restart the queue sends it.
     */
    private fun queueUpload(
        activity: Activity, treeId: Int, geniId: String, personGedcomId: String, uri: Uri, name: String,
        title: String?, description: String?, onDone: () -> Unit
    ) {
        val owner = activity as? LifecycleOwner ?: return
        owner.lifecycleScope.launch {
            val failure = try {
                val stored = withContext(Dispatchers.IO) { copyIn(activity, treeId, uri, name) }
                try {
                    val added = AppTreeHost.edit(treeId) { gedcom ->
                        val person = gedcom.people.orEmpty().firstOrNull { it.id == personGedcomId && GeniMapper.getGeniId(it) == geniId } ?: return@edit false
                        person.addMedia(Media().apply {
                            file = stored
                            setFileTag("FILE")
                            this.title = title
                            GeniMapper.setTag(this, VideoSync.QUEUED_TAG, "Y")
                        })
                        true
                    }
                    if (!added) error("the person is no longer in the tree")
                    withContext(Dispatchers.IO) {
                        Geni.queue(activity).enqueueAddVideo(treeId, geniId, personGedcomId, stored, title, description, "Video: ${title ?: stored}")
                    }
                } catch (e: Exception) {
                    // Also on cancellation: when no job was saved for the file, neither the media nor the copy may stay as a video nobody sends
                    withContext(NonCancellable) {
                        val jobSaved = try {
                            Geni.store(activity).jobs(treeId).any { it.key == "vid:$personGedcomId:$stored" }
                        } catch (check: Exception) {
                            true // Unknown: keep the media, a job may depend on it
                        }
                        if (!jobSaved) {
                            try {
                                AppTreeHost.edit(treeId) { gedcom ->
                                    gedcom.people.orEmpty().firstOrNull { it.id == personGedcomId }?.let { person ->
                                        person.media = person.media.orEmpty().filterNot {
                                            it.file == stored && GeniMapper.getTag(it, VideoSync.QUEUED_TAG) != null
                                        }.toMutableList()
                                    }
                                }
                            } catch (cleanup: Exception) {
                                Log.w("GeniSync", "Removing the media of an unqueued video failed", cleanup)
                            }
                            withContext(Dispatchers.IO) { File(AppTreeHost.mediaDir(treeId), stored).delete() }
                        }
                    }
                    throw e
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("GeniSync", "Queueing a video upload failed", e)
                e.message ?: e.javaClass.simpleName
            }
            if (failure == null) {
                GeniSync.kick(activity, treeId)
                Toast.makeText(activity, R.string.geni_video_upload_queued, Toast.LENGTH_LONG).show()
                onDone()
            } else Toast.makeText(activity, activity.getString(R.string.geni_video_upload_failed, failure), Toast.LENGTH_LONG).show()
        }
    }

    /** Copies the chosen file into the media folder under a plain name that is not taken; refuses a file over the size Geni takes. */
    private fun copyIn(context: Context, treeId: Int, uri: Uri, name: String): String {
        val dir = AppTreeHost.mediaDir(treeId)
        val clean = name.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').ifEmpty { "video.mp4" }
        val base = clean.substringBeforeLast('.', clean)
        val ext = clean.substringAfterLast('.', "")
        // The name is reserved atomically: two copies at once never take the same one
        var target = File(dir, clean)
        var n = 2
        while (!target.createNewFile()) target = File(dir, "$base-${n++}.$ext")
        val part = File.createTempFile("geni-upload-", ".part", dir)
        var published = false
        try {
            var total = 0L
            (context.contentResolver.openInputStream(uri) ?: error("the file cannot be read")).use { input ->
                part.outputStream().use { out ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > VideoTypes.MAX_UPLOAD_BYTES) error(context.getString(R.string.geni_video_too_big))
                        out.write(buffer, 0, read)
                    }
                }
            }
            if (total == 0L) error("the file is empty")
            if (!part.renameTo(target)) error("the file cannot be saved")
            published = true
            return target.name
        } finally {
            part.delete()
            if (!published) target.delete() // Only the placeholder: a published copy stays
        }
    }

    // ---- A video on the device ----

    private const val MENU_ID = 30

    /** Context menu of a media that is a Geni video: details, tag and untag. [onDone] refreshes the screen after a change. */
    fun addMenuItems(context: Context, menu: Menu, person: Person, media: Media, onDone: () -> Unit) {
        val treeId = Global.settings.openTree
        val videoId = GeniMapper.getTag(media, VideoSync.VIDEO_TAG) ?: return
        val geniId = GeniMapper.getGeniId(person) ?: return
        menu.add(0, MENU_ID, 0, R.string.geni_video_edit).setOnMenuItemClickListener {
            GeniPhotoEditor.show(context, treeId, person, media, videoId, onDone, kind = JobKind.UPDATE_VIDEO)
            true
        }
        menu.add(0, MENU_ID + 1, 0, R.string.geni_video_tag).setOnMenuItemClickListener {
            chooseToTag(context, treeId, person, videoId, onDone)
            true
        }
        menu.add(0, MENU_ID + 2, 0, R.string.geni_video_untag).setOnMenuItemClickListener {
            confirmUntag(context, treeId, geniId, person, videoId, onDone)
            true
        }
    }

    private fun queue(context: Context, treeId: Int, done: Int, onDone: () -> Unit, work: suspend (app.familygem.geni.sync.SyncQueue) -> Unit) {
        val owner = context as? LifecycleOwner ?: return
        owner.lifecycleScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { work(Geni.queue(context)) }
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("GeniSync", "Queueing a video change failed", e)
                false
            }
            if (ok) {
                GeniSync.kick(context, treeId)
                Toast.makeText(context, done, Toast.LENGTH_LONG).show()
                onDone()
            } else Toast.makeText(context, R.string.geni_delete_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun chooseToTag(context: Context, treeId: Int, person: Person, videoId: String, onDone: () -> Unit) {
        val others = Global.gc.people.orEmpty().filter { it.id != person.id && GeniMapper.getGeniId(it)?.startsWith("profile-") == true }
        if (others.isEmpty()) {
            Toast.makeText(context, R.string.geni_photo_tag_none, Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(context).setTitle(R.string.geni_video_tag).setNegativeButton(android.R.string.cancel, null)
            .setItems(others.map { U.properName(it) }.toTypedArray()) { _, which ->
                val chosen = others[which]
                val chosenProfile = GeniMapper.getGeniId(chosen)!!
                val chosenId = chosen.id
                val label = "Video tag: ${U.properName(chosen)}"
                queue(context, treeId, R.string.geni_video_tag_queued, onDone) { q -> q.enqueueVideoTag(treeId, videoId, chosenProfile, chosenId, true, label) }
            }.show()
    }

    private fun confirmUntag(context: Context, treeId: Int, geniId: String, person: Person, videoId: String, onDone: () -> Unit) {
        val personId = person.id
        val label = "Video untag: ${U.properName(person)}"
        AlertDialog.Builder(context).setMessage(R.string.geni_video_untag_confirm).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.geni_video_untag) { _, _ ->
                queue(context, treeId, R.string.geni_video_untag_queued, onDone) { q ->
                    // Stored first, so a failure in the local cleanup cannot lose the untag; the video leaves this person at once
                    q.enqueueVideoTag(treeId, videoId, geniId, personId, false, label)
                    try {
                        Geni.videos(context).removeLocal(treeId, geniId, personId, videoId, detachIfEdited = true)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("GeniSync", "Removing the untagged video $videoId here failed", e)
                    }
                }
            }.show()
    }

    /**
     * Deleting a media that is a Geni video (or one waiting to be sent): handled here, returns true when it took over.
     * Three distinct actions: remove from this device, untag on Geni (this profile only), delete on Geni (only for a managed profile, confirmed).
     */
    fun handleDelete(context: Context, person: Person, media: Media, onDone: () -> Unit): Boolean {
        val treeId = Global.settings.openTree
        val geniId = GeniMapper.getGeniId(person)
        val videoId = GeniMapper.getTag(media, VideoSync.VIDEO_TAG)
        if (!GeniTrees.isGeni(treeId) || geniId == null || videoId == null) return false
        val owner = context as? LifecycleOwner ?: return false
        val personId = person.id
        owner.lifecycleScope.launch {
            val store = Geni.store(context)
            val managed = try {
                DeleteRules.isManaged(store.link(treeId, geniId)?.snapshot, Geni.client(context).managedGuids())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("GeniSync", "Managed profiles not read", e)
                null
            }
            val actions = mutableListOf(R.string.geni_delete_local to { removeHere(context, owner, treeId, geniId, personId, videoId, onDone) },
                R.string.geni_video_untag to { confirmUntag(context, treeId, geniId, person, videoId, onDone) })
            // Remote deletion only from a managed profile; offline or on an error it is simply not offered
            if (managed == true) actions += R.string.geni_delete_geni to { confirmDelete(context, owner, treeId, geniId, personId, videoId, onDone) }
            AlertDialog.Builder(context).setTitle(R.string.geni_video_delete_title).setNegativeButton(android.R.string.cancel, null)
                .setItems(actions.map { context.getString(it.first) }.toTypedArray()) { _, which -> actions[which].second() }.show()
        }
        return true
    }

    private fun removeHere(context: Context, owner: LifecycleOwner, treeId: Int, geniId: String, personId: String, videoId: String, onDone: () -> Unit) {
        owner.lifecycleScope.launch {
            try {
                val removed = withContext(Dispatchers.IO) { Geni.videos(context).removeLocal(treeId, geniId, personId, videoId) }
                if (removed) {
                    Toast.makeText(context, R.string.geni_video_removed_local, Toast.LENGTH_SHORT).show()
                    onDone()
                } else Toast.makeText(context, R.string.geni_delete_failed, Toast.LENGTH_LONG).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("GeniSync", "Removing video $videoId failed", e)
                Toast.makeText(context, R.string.geni_delete_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Second confirmation; then the deletion goes through the queue. The copies leave the tree only after Geni confirms. */
    private fun confirmDelete(context: Context, owner: LifecycleOwner, treeId: Int, geniId: String, personId: String, videoId: String, onDone: () -> Unit) {
        AlertDialog.Builder(context).setMessage(R.string.geni_video_delete_confirm).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.geni_delete_geni) { _, _ ->
                owner.lifecycleScope.launch {
                    val queued = try {
                        withContext(Dispatchers.IO) { Geni.queue(context).enqueueDeleteVideo(treeId, videoId, personId, "Video: $videoId") }
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("GeniSync", "Queueing the delete of video $videoId failed", e)
                        false
                    }
                    if (queued) {
                        GeniSync.kick(context, treeId)
                        Toast.makeText(context, R.string.geni_delete_queued, Toast.LENGTH_LONG).show()
                        onDone()
                    } else Toast.makeText(context, R.string.geni_delete_failed, Toast.LENGTH_LONG).show()
                }
            }.show()
    }
}
