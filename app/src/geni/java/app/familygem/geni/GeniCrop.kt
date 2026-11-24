package app.familygem.geni

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.familygem.Global
import app.familygem.R
import com.bumptech.glide.Glide
import com.canhub.cropper.CropImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The crop prompt of a new photo: keeps its Geni upload on hold until the user decides. Main thread only. */
object GeniCrop {

    const val EXTRA = "geniCropSession"

    /** Prompts still open, by photo path: the session that holds each. */
    private val pending = mutableMapOf<String, Long>()

    /**
     * Holds [file] while [dialog] is open; releases on No, dismissal, or when [context] is destroyed with the prompt
     * open (rotation). Yes hands the hold to the editor through [handOver] first, so the dismissal does not release.
     */
    fun watchPrompt(context: Context, dialog: Dialog, treeId: Int, file: File) {
        val session = GeniGlue.holdPhoto(treeId, file.name)
        if (session == 0L) return
        pending[file.absolutePath] = session
        val release = { if (pending.remove(file.absolutePath) == session) GeniGlue.releasePhoto(session) }
        dialog.setOnDismissListener { release() }
        (context as? LifecycleOwner)?.lifecycle?.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = release()
        })
    }

    /** The crop editor of [file] is launched: it takes over the hold of an open prompt, if any. */
    fun handOver(intent: Intent, file: File?) {
        file ?: return
        pending.remove(file.absolutePath)?.let { intent.putExtra(EXTRA, it) }
    }
}

/** Crop editor side of the hold: crops into a private temporary file, replaces the original in one rename, then releases. */
class GeniCropEditor {

    private var session = 0L
    private var original: File? = null
    private var temp: File? = null
    private var committed = false

    /** Where the crop is written, or null for a crop that does not hold a photo. */
    val output: Uri? get() = temp?.let { Uri.fromFile(it) }

    /** True if the editor must close: its session expired (the process restarted), so the file stays as it is. */
    fun start(activity: Activity): Boolean {
        session = activity.intent.getLongExtra(GeniCrop.EXTRA, 0L)
        if (session == 0L) return false
        if (!GeniGlue.isCropSessionActive(session)) {
            activity.finish()
            return true
        }
        val file = File(activity.intent.data!!.path!!)
        original = file
        // One name per editor instance: a crop still running after a rotation never shares a file with the new editor
        temp = File(file.parentFile, ".${file.name}.${System.nanoTime()}.crop")
        return false
    }

    /** True if the crop belongs to a held photo and was handled here. */
    fun complete(activity: AppCompatActivity, result: CropImageView.CropResult): Boolean {
        if (session == 0L) return false
        val temp = temp ?: return true
        if (!result.isSuccessful) {
            temp.delete()
            if (!activity.isDestroyed) Toast.makeText(activity, result.error?.message, Toast.LENGTH_LONG).show() // Hold stays: retry or leave
            return true
        }
        when (GeniGlue.commitCrop(session, temp, original!!)) {
            true -> {
                committed = true
                Global.edited = true
                if (activity.isDestroyed) return true // Rotated meanwhile: the new editor sees the released session and leaves
                activity.setResult(Activity.RESULT_OK, Intent())
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    Glide.get(activity).clearDiskCache()
                    withContext(Dispatchers.Main) { activity.onBackPressedDispatcher.onBackPressed() }
                }
            }
            false -> {
                temp.delete()
                if (!activity.isDestroyed) Toast.makeText(activity, R.string.something_wrong, Toast.LENGTH_LONG).show()
            }
            null -> { // Released meanwhile: never overwrite
                temp.delete()
                activity.finish()
            }
        }
        return true
    }

    /** Leaving the editor without a crop (Back, error): the intact original goes to Geni. Rotation keeps the hold. */
    fun destroy(activity: Activity) {
        if (session == 0L || !activity.isFinishing) return
        if (!committed) temp?.delete()
        GeniGlue.releasePhoto(session)
    }
}
