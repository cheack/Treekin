package app.familygem.geni.ui

import android.app.Activity
import app.familygem.geni.R

/** Opens the published privacy policy; shared by the Geni account screen and About. */
object GeniPrivacyPolicy {

    const val URL = "https://github.com/cheack/Treekin/blob/geni/docs/PRIVACY.md"

    fun open(activity: Activity) = GeniLegalDocs.open(activity, URL, R.string.geni_privacy_policy_no_browser)
}
