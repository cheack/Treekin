package app.familygem.geni

import android.app.Activity
import android.util.Log
import android.view.Menu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import app.familygem.Global
import android.content.Context
import app.familygem.U
import app.familygem.geni.map.GeniMapper
import app.familygem.geni.api.DeleteAuthorization
import app.familygem.geni.sync.DeleteQueued
import app.familygem.geni.sync.DeleteDecision
import app.familygem.geni.sync.DeleteRules
import app.familygem.geni.sync.PersonRemoval
import app.familygem.geni.sync.PersonPhotoChanges
import app.familygem.geni.sync.PhotoSync
import org.folg.gedcom.model.Media
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.familygem.geni.sync.LoadPolicy
import app.familygem.geni.sync.LoadResult
import app.familygem.geni.sync.LocalTree
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.folg.gedcom.model.Gedcom
import org.folg.gedcom.model.Person

/** Entry points called by one-line GENI-HOOK calls in upstream code; connects :geni with the app. */
object GeniGlue {

    private var resumed = false

    private var treesLoaded = false

    /** Loads the IDs of the Geni trees once, before any hook asks: a hook must never see a Geni tree as a local one. */
    private fun loadTrees() {
        if (treesLoaded) return
        try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) { GeniTrees.load(Geni.store(Global.context)) }
            treesLoaded = true
        } catch (e: Exception) {
            Log.e("GeniSync", "Geni trees not read", e) // Tried again by the next hook
        }
    }

    fun install() {
        loadTrees()
        if (Geni.host == null) Geni.host = AppTreeHost
        if (!resumed && Geni.isConfigured) {
            resumed = true
            Geni.resumeUploads = { GeniSync.resume(Global.context) }
            GeniSync.resume(Global.context) // Uploads left by an earlier run of the app
            GeniPrefetch.dailyRefresh(Global.context)
        }
        // Registers once; a registration that failed is tried again here
        if (Geni.isConfigured) GeniSync.watchNetwork(Global.context)
    }

    /** Plans Geni uploads after a Geni tree was written. Never breaks the save: errors are only logged. */
    suspend fun onTreeSaved(gedcom: Gedcom, treeId: Int) {
        try {
            install()
            if (!Geni.isConfigured || Geni.store(Global.context).tree(treeId) == null) return
            // Captured on the saving thread, like upstream's own JSON write of the same tree
            GeniSync.onSaved(Global.context, treeId, LocalTree.capture(gedcom))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("GeniSync", "Planning uploads of tree $treeId failed", e)
        }
    }

    fun addTreesMenu(activity: Activity, menu: Menu) {
        install()
        GeniHooks.addTreesMenu(activity, menu)
    }

    /** True if the Geni login was opened instead of the "add a tree" prompt of an empty trees list. */
    fun startOnboarding(activity: Activity): Boolean {
        install()
        return GeniHooks.startOnboarding(activity)
    }

    /** Removes the upstream Premium advertisement (purchase of FamilyGem Premium) from the About screen. */
    fun hidePremium(activity: androidx.appcompat.app.AppCompatActivity) {
        val manager = activity.supportFragmentManager
        manager.findFragmentById(app.familygem.R.id.about_premium)?.let {
            manager.beginTransaction().remove(it).commitNow()
        }
        activity.findViewById<android.view.View>(app.familygem.R.id.about_premium)?.visibility = android.view.View.GONE
        addAboutNotes(activity)
    }

    /** Adds the "not affiliated with Geni" disclaimer and the privacy policy and terms of use links to the About screen. */
    private fun addAboutNotes(activity: AppCompatActivity) {
        val premium = activity.findViewById<android.view.View>(app.familygem.R.id.about_premium) ?: return
        val parent = premium.parent as? android.widget.LinearLayout ?: return
        val gap = (16 * activity.resources.displayMetrics.density).toInt()
        val disclaimer = android.widget.TextView(activity).apply {
            setText(R.string.geni_about_disclaimer)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, gap)
        }
        val policy = android.widget.TextView(activity, null, 0, app.familygem.R.style.AppTheme_Link).apply {
            setText(R.string.geni_privacy_policy)
            setOnClickListener { app.familygem.geni.ui.GeniPrivacyPolicy.open(activity) }
        }
        val terms = android.widget.TextView(activity, null, 0, app.familygem.R.style.AppTheme_Link).apply {
            setText(R.string.geni_terms)
            setOnClickListener { app.familygem.geni.ui.GeniTerms.open(activity) }
        }
        parent.addView(disclaimer)
        parent.addView(policy, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
        parent.addView(terms, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    /**
     * Connects two cards with straight segments (vertical, horizontal, vertical) instead of upstream's S-curve.
     * The horizontal bar sits [barOffset] below the start so that siblings of different heights share one bar;
     * a gap shorter than twice the offset puts it in the middle.
     */
    fun connect(path: android.graphics.Path, x1: Float, y1: Float, x2: Float, y2: Float, barOffset: Float) {
        val middle = y1 + (y2 - y1).coerceIn(-barOffset * 2, barOffset * 2) / 2
        path.lineTo(x1, middle)
        path.lineTo(x2, middle)
        path.lineTo(x2, y2)
    }

    /**
     * Holds the upload of a new photo of a Geni tree until the user decides about cropping it.
     * Returns the session to pass to [releasePhoto] or to the crop editor, or 0 if the tree is not a Geni tree.
     */
    fun holdPhoto(treeId: Int, file: String): Long {
        install()
        if (!Geni.isConfigured || !GeniTrees.isGeni(treeId)) return 0
        return Geni.queue(Global.context).holds.acquire(treeId, file)
    }

    /** Lets the held photo go to Geni; idempotent. */
    fun releasePhoto(session: Long) {
        if (session == 0L) return
        Geni.queue(Global.context).holds.release(session)?.let { GeniSync.kick(Global.context, it) }
    }

    /** True while the crop session still holds its photo (false after a release or a process restart). */
    fun isCropSessionActive(session: Long): Boolean {
        if (session == 0L) return false
        install()
        return Geni.queue(Global.context).holds.isActive(session)
    }

    /**
     * Replaces [original] with the finished crop [temp] and releases the photo, unless the session was released.
     * Returns null if it was released (nothing touched), else whether the file was replaced (the hold stays if not).
     */
    fun commitCrop(session: Long, temp: java.io.File, original: java.io.File): Boolean? {
        val result = Geni.queue(Global.context).holds.commit(session) { temp.length() > 0 && temp.renameTo(original) }
        if (!result.active) return null
        if (result.ok) result.treeId?.let { GeniSync.kick(Global.context, it) }
        return result.ok
    }

    /** True for a Geni tree: its people must not be merged into other trees. */
    fun isGeniTree(treeId: Int): Boolean {
        install()
        return GeniTrees.isGeni(treeId)
    }

    /** Hides share, merge and compare from the menu of a Geni tree. */
    fun trimTreeMenu(treeId: Int, menu: Menu) {
        install()
        GeniHooks.trimTreeMenu(treeId, menu)
    }

    /** The link to the Geni profile must not be edited or deleted by hand: the tag is shown but not opened. */
    fun isLockedTag(tag: org.folg.gedcom.model.GedcomTag): Boolean = tag.tag == app.familygem.geni.map.GeniMapper.GENI_ID_TAG

    /** In a Geni tree an unlink is refused: Geni's API cannot remove a relationship. True when the caller must not unlink. */
    fun blockUnlink(context: android.content.Context): Boolean {
        install()
        if (!GeniTrees.isGeni(Global.settings.openTree)) return false
        AlertDialog.Builder(context).setMessage(R.string.geni_unlink_blocked).setPositiveButton(android.R.string.ok, null).show()
        return true
    }

    /**
     * Deleting a person in a Geni tree: handled here instead of upstream's delete. Returns true when the caller must stop
     * (a dialog follows); false for a person that is not on Geni, which upstream deletes as usual.
     * [onDone] refreshes the screen after the person was removed from this device.
     */
    fun handleDelete(context: Context, person: Person, onDone: () -> Unit): Boolean {
        install()
        val treeId = Global.settings.openTree
        val geniId = GeniMapper.getGeniId(person)
        if (!GeniTrees.isGeni(treeId) || geniId == null) return false
        val owner = context as? LifecycleOwner ?: return false
        owner.lifecycleScope.launch {
            val store = Geni.store(context)
            val name = U.properName(person)
            when (DeleteRules.decide(true, geniId, store.tree(treeId)?.rootGeniId)) {
                DeleteDecision.Upstream -> Unit
                DeleteDecision.BlockedRoot -> AlertDialog.Builder(context).setMessage(R.string.geni_delete_root)
                    .setPositiveButton(android.R.string.ok, null).show()
                DeleteDecision.Dialog -> {
                    // Managed profiles are listed by guid; offline or on an error the Geni choice is simply not offered
                    // The account and its managed profiles come from one login; the confirmation is bound to that account
                    val authorization = try {
                        Geni.client(context).deleteAuthorization()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("GeniSync", "Managed profiles not read", e)
                        null
                    }
                    val managed = authorization?.let { DeleteRules.isManaged(store.link(treeId, geniId)?.snapshot, it.managedGuids) }
                    val dialog = AlertDialog.Builder(context).setTitle(name)
                        .setMessage(context.getString(R.string.geni_delete_message) + when (managed) {
                            true -> ""
                            false -> "\n\n" + context.getString(R.string.geni_delete_not_managed)
                            null -> "\n\n" + context.getString(R.string.geni_delete_unchecked)
                        })
                        .setPositiveButton(R.string.geni_delete_local) { _, _ ->
                            owner.lifecycleScope.launch {
                                try {
                                    if (withContext(Dispatchers.IO) { PersonRemoval(store, AppTreeHost).removeFromTree(treeId, geniId) }) onDone()
                                    else Toast.makeText(context, R.string.geni_delete_failed, Toast.LENGTH_LONG).show()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    Log.e("GeniSync", "Removing $geniId from tree $treeId failed", e)
                                    Toast.makeText(context, R.string.geni_delete_failed, Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                    if (managed == true && authorization != null)
                        dialog.setNeutralButton(R.string.geni_delete_geni) { _, _ -> confirmDeleteOnGeni(context, treeId, geniId, name, authorization) }
                    dialog.show()
                }
            }
        }
        return true
    }

    /**
     * Deleting a media of a Geni person: handled here when the media is a Geni photo (tagged with its Geni photo ID).
     * Returns true when it took over (a dialog follows); false for any other media, which upstream deletes as usual.
     * [onDone] refreshes the screen after the photo was removed from this device.
     */
    fun handleDeleteMedia(context: Context, person: Person, media: Media, onDone: () -> Unit): Boolean {
        install()
        val treeId = Global.settings.openTree
        val geniId = GeniMapper.getGeniId(person)
        val photoId = GeniMapper.getTag(media, PhotoSync.PHOTO_TAG)
        if (!GeniTrees.isGeni(treeId) || geniId == null || photoId == null) return false
        val owner = context as? LifecycleOwner ?: return false
        owner.lifecycleScope.launch {
            val store = Geni.store(context)
            val isMain = store.photoLinks(treeId, geniId).any { it.geniPhotoId == photoId && it.isMain }
            val managed = try {
                DeleteRules.isManaged(store.link(treeId, geniId)?.snapshot, Geni.client(context).managedGuids())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("GeniSync", "Managed profiles not read", e)
                null
            }
            val dialog = AlertDialog.Builder(context).setMessage(
                context.getString(if (isMain) R.string.geni_photo_main else R.string.geni_photo_delete_local) +
                    if (managed == true) "" else "\n\n" + context.getString(
                        if (managed == false) R.string.geni_delete_not_managed else R.string.geni_delete_unchecked)
            ).setNegativeButton(android.R.string.cancel, null)
            if (isMain) {
                // The main photo is only removed by deleting it on Geni
                if (managed == true) dialog.setPositiveButton(R.string.geni_delete_geni) { _, _ -> confirmDeletePhoto(context, owner, treeId, geniId, person.id, photoId, false, onDone) }
                else dialog.setPositiveButton(android.R.string.ok, null)
            } else {
                dialog.setPositiveButton(R.string.geni_delete_local) { _, _ ->
                    owner.lifecycleScope.launch {
                        try {
                            val removed = withContext(Dispatchers.IO) { PhotoSync(store, AppTreeHost).removeLocal(treeId, geniId, person.id, photoId) }
                            // Refused (e.g. the media was made shared): say so instead of doing nothing
                            if (removed) onDone() else Toast.makeText(context, R.string.geni_delete_failed, Toast.LENGTH_LONG).show()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e("GeniSync", "Removing photo $photoId failed", e)
                            Toast.makeText(context, R.string.geni_delete_failed, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                if (managed == true) dialog.setNeutralButton(R.string.geni_delete_geni) { _, _ -> confirmDeletePhoto(context, owner, treeId, geniId, person.id, photoId, true, onDone) }
            }
            dialog.show()
        }
        return true
    }

    /** Second confirmation; then the photo is deleted on Geni through the queue, and first removed here when [removeHere]. */
    private fun confirmDeletePhoto(
        context: Context, owner: LifecycleOwner, treeId: Int, geniId: String, personId: String, photoId: String,
        removeHere: Boolean, onDone: () -> Unit
    ) {
        AlertDialog.Builder(context).setMessage(R.string.geni_photo_delete_confirm)
            .setPositiveButton(R.string.geni_delete_geni) { _, _ ->
                owner.lifecycleScope.launch {
                    val queued = try {
                        withContext(Dispatchers.IO) {
                            // The job is stored first so a failure in the local cleanup cannot lose the deletion
                            Geni.queue(context).enqueueDeletePhoto(treeId, photoId, personId, photoId)
                            if (removeHere) PhotoSync(Geni.store(context), AppTreeHost).removeLocal(treeId, geniId, personId, photoId)
                        }
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("GeniSync", "Queueing the delete of photo $photoId failed", e)
                        false
                    }
                    if (queued) {
                        GeniSync.kick(context, treeId)
                        Toast.makeText(context, R.string.geni_delete_queued, Toast.LENGTH_LONG).show()
                        onDone()
                    } else Toast.makeText(context, R.string.geni_delete_failed, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun confirmDeleteOnGeni(context: Context, treeId: Int, geniId: String, name: String, authorization: DeleteAuthorization) {
        AlertDialog.Builder(context).setTitle(name).setMessage(R.string.geni_delete_confirm)
            .setPositiveButton(R.string.geni_delete_geni) { _, _ ->
                scope.launch {
                    // Another login while the dialog was open: ownership must be checked again for that account
                    val result = if (!Geni.client(context).isCurrent(authorization)) R.string.geni_delete_login_changed else try {
                        when (Geni.queue(context).enqueueDelete(treeId, geniId, name, authorization.accountId)) {
                            DeleteQueued.QUEUED -> R.string.geni_delete_queued
                            DeleteQueued.OTHER_ACCOUNT -> R.string.geni_delete_other_account
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e("GeniSync", "Queueing the delete of $geniId failed", e)
                        R.string.geni_delete_failed
                    }
                    if (result == R.string.geni_delete_queued) GeniSync.kick(context, treeId)
                    withContext(Dispatchers.Main) { Toast.makeText(context, result, Toast.LENGTH_LONG).show() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private var loginToastShown = false
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** Forgets the Geni data of a deleted tree, so a new tree with the same ID does not inherit it. */
    fun onTreeDeleted(treeId: Int) {
        install()
        Geni.forgetTree(Global.context, treeId, scope)
    }

    /** Loads the person's relatives from Geni when the person card opens, if needed; then refreshes the card. */
    fun onPersonOpened(activity: Activity, person: Person?, refresh: () -> Unit) {
        install()
        val geniId = person?.let { GeniMapper.getGeniId(it) } ?: return
        val owner = activity as? LifecycleOwner ?: return
        val treeId = Global.settings.openTree
        // A main photo deleted on Geni (or its replacement) arrives while this profile stays open
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                PersonPhotoChanges.of(treeId, person.id).collect { withContext(Dispatchers.Main) { refresh() } }
            }
        }
        owner.lifecycleScope.launch {
            val link = Geni.store(activity).link(treeId, geniId)
            if (!LoadPolicy.shouldLoad(link, System.currentTimeMillis(), LoadPolicy.OPEN_REFRESH_MILLIS)) return@launch
            when (val result = Geni.loader(activity).load(treeId, geniId)) {
                is LoadResult.Loaded -> refresh()
                is LoadResult.Gone -> if (result.removed) {
                    Toast.makeText(activity, R.string.geni_gone_card, Toast.LENGTH_LONG).show()
                    activity.finish() // The person left the tree
                }
                LoadResult.NeedsLogin -> if (!loginToastShown) {
                    loginToastShown = true
                    Toast.makeText(activity, R.string.geni_login_again, Toast.LENGTH_LONG).show()
                }
                else -> Unit // Offline or Geni error: the cached data stays
            }
        }
    }

    /** Adds "Geni photos" and "Keep offline" to the menu of a person card of a Geni tree. */
    fun addProfileMenu(activity: Activity, menu: Menu, person: Person?) {
        install()
        val geniId = person?.let { GeniMapper.getGeniId(it) } ?: return
        val treeId = Global.settings.openTree
        menu.add(R.string.geni_album).setOnMenuItemClickListener {
            GeniAlbum.show(activity, treeId, geniId, person.id) { (activity as? app.familygem.profile.ProfileActivity)?.refresh() }
            true
        }
        menu.add(R.string.geni_keep_offline).setOnMenuItemClickListener {
            val depths = arrayOf("1", "2", "3", "4", "5")
            AlertDialog.Builder(activity)
                .setTitle(R.string.geni_keep_offline_depth)
                .setSingleChoiceItems(depths, 2) { dialog, which ->
                    dialog.dismiss()
                    AlertDialog.Builder(activity)
                        .setMessage(R.string.geni_keep_offline_photos)
                        .setPositiveButton(R.string.geni_keep_offline_with_photos) { _, _ -> startKeepOffline(activity, treeId, geniId, which + 1, true) }
                        .setNegativeButton(R.string.geni_keep_offline_no_photos) { _, _ -> startKeepOffline(activity, treeId, geniId, which + 1, false) }
                        .show()
                }.show()
            true
        }
    }

    private fun startKeepOffline(activity: Activity, treeId: Int, geniId: String, depth: Int, photos: Boolean) {
        GeniPrefetch.enqueue(activity, treeId, geniId, depth, photos)
        Toast.makeText(activity, R.string.geni_keep_offline_started, Toast.LENGTH_SHORT).show()
    }
}
