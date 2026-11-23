package app.familygem.geni.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.StringRes

/** Opens a published legal document in the browser; shared by the privacy policy and the terms of use. */
internal object GeniLegalDocs {

    fun open(activity: Activity, url: String, @StringRes noBrowser: Int) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, noBrowser, Toast.LENGTH_LONG).show()
        }
    }
}
