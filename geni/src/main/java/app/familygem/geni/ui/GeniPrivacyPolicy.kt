package app.familygem.geni.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import app.familygem.geni.R

/** Opens the published privacy policy; shared by the Geni account screen and About. */
object GeniPrivacyPolicy {

    const val URL = "https://github.com/cheack/Treekin/blob/geni/docs/PRIVACY.md"

    fun open(activity: Activity) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(URL)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.geni_privacy_policy_no_browser, Toast.LENGTH_LONG).show()
        }
    }
}
