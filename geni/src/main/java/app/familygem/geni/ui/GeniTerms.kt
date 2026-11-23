package app.familygem.geni.ui

import android.app.Activity
import app.familygem.geni.R

/** Opens the published Treekin user agreement; shared by the Geni account screen and About. */
object GeniTerms {

    const val URL = "https://github.com/cheack/Treekin/blob/geni/docs/TERMS.md"

    fun open(activity: Activity) = GeniLegalDocs.open(activity, URL, R.string.geni_terms_no_browser)
}
