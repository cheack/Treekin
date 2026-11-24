package app.familygem

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import app.familygem.geni.GeniGlue
import java.io.File
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import app.familygem.databinding.CropImageActivityBinding
import app.familygem.util.toPxFloat
import com.bumptech.glide.Glide
import com.canhub.cropper.CropImageOptions
import com.canhub.cropper.CropImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Activity to crop image. */
class CropImageActivity : AppCompatActivity(), CropImageView.OnSetImageUriCompleteListener, CropImageView.OnCropImageCompleteListener {

    private lateinit var imageView: CropImageView

    /** GENI-HOOK: crop of a new photo whose upload to Geni waits for it (0 for a standalone crop). */
    private var geniSession = 0L
    private var original: File? = null
    private var temp: File? = null
    private var committed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        geniSession = intent.getLongExtra(GENI_SESSION, 0L)
        if (geniSession != 0L) {
            // Expired (process restarted): the file is already as it was last written, do not touch it
            if (!GeniGlue.isCropSessionActive(geniSession)) {
                finish()
                return
            }
            original = File(intent.data!!.path!!)
            temp = File(original!!.parentFile, "." + original!!.name + ".crop")
        }
        val barStyle = SystemBarStyle.dark(ResourcesCompat.getColor(resources, R.color.black_veiled, null))
        enableEdgeToEdge(barStyle, barStyle)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.elevation = 0F
        CropImageActivityBinding.inflate(layoutInflater).run {
            setContentView(root)
            val imageUri = intent.data!!
            cropImageView.apply {
                setImageUriAsync(imageUri)
                setImageCropOptions(
                    CropImageOptions(
                        initialCropWindowPaddingRatio = .07F,
                        borderLineThickness = 1F.toPxFloat(),
                        borderCornerThickness = 4F.toPxFloat(),
                        borderCornerOffset = (-3F).toPxFloat()
                    )
                )
                customOutputUri = temp?.let { Uri.fromFile(it) } ?: imageUri
                guidelines = CropImageView.Guidelines.OFF
                setOnCropImageCompleteListener(this@CropImageActivity)
            }
            imageView = cropImageView
        }
    }

    override fun onSetImageUriComplete(view: CropImageView, uri: Uri, error: Exception?) {
        if (error != null) {
            Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCropImageComplete(view: CropImageView, result: CropImageView.CropResult) {
        if (geniSession != 0L) {
            onGeniCropComplete(result)
            return
        }
        if (result.isSuccessful) {
            setResult(RESULT_OK, Intent()) // For previous FileActivity
            Global.edited = true
            lifecycleScope.launch(Dispatchers.IO) {
                Glide.get(this@CropImageActivity).clearDiskCache()
                withContext(Dispatchers.Main) { onBackPressedDispatcher.onBackPressed() }
            }
        } else {
            Toast.makeText(this, result.error?.message, Toast.LENGTH_LONG).show()
        }
    }

    /** The finished crop replaces the original in one rename, and only then the photo is released to Geni. */
    private fun onGeniCropComplete(result: CropImageView.CropResult) {
        val temp = temp ?: return
        if (!result.isSuccessful) {
            temp.delete()
            Toast.makeText(this, result.error?.message, Toast.LENGTH_LONG).show() // The hold stays: retry or leave
            return
        }
        when (GeniGlue.commitCrop(geniSession, temp, original!!)) {
            true -> {
                committed = true
                Global.edited = true
                if (isDestroyed) return // Rotated meanwhile: the new editor sees the released session and leaves
                setResult(RESULT_OK, Intent())
                lifecycleScope.launch(Dispatchers.IO) {
                    Glide.get(this@CropImageActivity).clearDiskCache()
                    withContext(Dispatchers.Main) { onBackPressedDispatcher.onBackPressed() }
                }
            }
            false -> {
                temp.delete()
                Toast.makeText(this, R.string.something_wrong, Toast.LENGTH_LONG).show()
            }
            null -> { // Released meanwhile: never overwrite
                temp.delete()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Leaving the editor without a crop (Back, error): the intact original goes to Geni. Rotation keeps the hold
        if (geniSession != 0L && isFinishing) {
            if (!committed) temp?.delete()
            GeniGlue.releasePhoto(geniSession)
        }
    }

    // Back arrow on actionbar
    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return super.onSupportNavigateUp()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(com.canhub.cropper.R.menu.crop_image_menu, menu)
        menu.findItem(com.canhub.cropper.R.id.crop_image_menu_crop).title = getString(R.string.done)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            com.canhub.cropper.R.id.ic_rotate_right_24 -> {
                imageView.rotateImage(90)
                true
            }
            com.canhub.cropper.R.id.ic_flip_24_horizontally -> {
                imageView.flipImageHorizontally()
                true
            }
            com.canhub.cropper.R.id.ic_flip_24_vertically -> {
                imageView.flipImageVertically()
                true
            }
            com.canhub.cropper.R.id.crop_image_menu_crop -> {
                imageView.croppedImageAsync()
                true
            }
            else -> false
        }
    }

    companion object {
        /** GENI-HOOK: ID of the hold that keeps the photo from going to Geni while it is being cropped. */
        const val GENI_SESSION = "geniCropSession"
    }
}
