package app.familygem.geni.ui

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.familygem.geni.map.GeniLocales
import app.familygem.geni.map.LocaleGroup
import app.familygem.geni.Geni
import app.familygem.geni.GeniSettings
import app.familygem.geni.R
import app.familygem.geni.auth.AuthResult
import app.familygem.geni.auth.EncryptedTokenStore
import app.familygem.geni.auth.OAuth
import app.familygem.geni.auth.TokenStore
import app.familygem.geni.sync.GONE
import app.familygem.geni.sync.JobKind
import app.familygem.geni.sync.JobState
import app.familygem.geni.sync.SyncQueue
import app.familygem.geni.sync.UploadJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Logs in to Geni with the OAuth mobile flow and shows the logged-in user. Also receives the OAuth redirect. */
class GeniLoginActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var loginButton: Button
    private lateinit var logoutButton: Button
    private lateinit var createTreeButton: Button
    private lateinit var pendingStatus: TextView
    private lateinit var failedButton: Button
    private lateinit var conflictsButton: Button
    private lateinit var tokens: TokenStore
    private lateinit var model: GeniLoginViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.geni_activity_login)
        setSupportActionBar(findViewById<Toolbar>(R.id.geni_login_toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        status = findViewById(R.id.geni_login_status)
        progress = findViewById(R.id.geni_login_progress)
        loginButton = findViewById(R.id.geni_login_button)
        logoutButton = findViewById(R.id.geni_logout_button)
        createTreeButton = findViewById(R.id.geni_create_tree_button)
        pendingStatus = findViewById(R.id.geni_pending_status)
        failedButton = findViewById(R.id.geni_failed_button)
        failedButton.setOnClickListener { showFailed() }
        conflictsButton = findViewById(R.id.geni_conflicts_button)
        conflictsButton.setOnClickListener { chooseConflict() }
        val photosSwitch = findViewById<SwitchCompat>(R.id.geni_upload_photos_switch)
        photosSwitch.isChecked = GeniSettings.uploadPhotos(applicationContext)
        photosSwitch.setOnCheckedChangeListener { _, on ->
            GeniSettings.setUploadPhotos(applicationContext, on)
            // Planned again either way: photos added meanwhile are queued, and waiting ones are dropped when it goes off
            if (Geni.isConfigured && Geni.host != null) lifecycleScope.launch {
                val queue = Geni.queue(applicationContext)
                Geni.store(applicationContext).trees().forEach { queue.replan(it.treeId) }
                if (on) Geni.resumeUploads?.invoke()
            }
        }
        findViewById<android.view.View>(R.id.geni_privacy_policy_link).setOnClickListener { GeniPrivacyPolicy.open(this) }
        findViewById<android.view.View>(R.id.geni_terms_link).setOnClickListener { GeniTerms.open(this) }
        if (!Geni.isConfigured) {
            status.setText(R.string.geni_not_configured)
            return
        }
        tokens = EncryptedTokenStore(applicationContext)
        model = ViewModelProvider(this, GeniLoginViewModelFactory(applicationContext))[GeniLoginViewModel::class.java]
        // The operation lives in the ViewModel: a recreated screen shows its state and opens the tree it made
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) } }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.state.filterIsInstance<LoginState.ReadyToOpen>().collect { if (model.claim(it.treeId)) openAndClose(it) }
            }
        }
        loginButton.setOnClickListener {
            val state = OAuth.newState()
            tokens.pendingState = state
            startActivity(Intent(Intent.ACTION_VIEW, OAuth.authorizeUrl(Geni.config, state).toUri()))
        }
        createTreeButton.setOnClickListener { model.createTree() }
        logoutButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.geni_logout_confirm)
                .setPositiveButton(R.string.geni_logout) { _, _ ->
                    tokens.clear()
                    model.reset()
                    showLoggedOut(null)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
        // On recreation Android gives back the original launch intent, redirect included: it must not be handled again
        if (savedInstanceState == null) handleIntent(intent) else showSavedState()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun showSavedState() {
        if (tokens.get() == null) {
            // A login being checked, or refused, is not stored: the retained state is shown instead of the logged-out view
            if (!model.holdsState()) {
                model.reset()
                showLoggedOut(null)
            }
        } else if (model.state.value == LoginState.Idle) model.openAccount() // Not after rotation: the retained state is shown instead
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (Geni.isConfigured) handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val redirect = intent.data?.takeIf { intent.action == Intent.ACTION_VIEW }?.toString()
        if (redirect == null) {
            showSavedState()
            return
        }
        // The pending state is used only once, so a redirect replayed later (e.g. after process death) is rejected
        val expectedState = tokens.pendingState
        tokens.pendingState = null
        when (val result = OAuth.parseRedirect(redirect, System.currentTimeMillis(), expectedState)) {
            // The model finds the account with this login first and stores it only if the account may use the tree
            is AuthResult.Success -> model.completeLogin(result)
            is AuthResult.Denied -> {
                model.reset()
                showLoggedOut(result.message)
            }
            AuthResult.Invalid -> {
                model.reset()
                showLoggedOut(getString(R.string.geni_login_failed))
            }
        }
    }

    /** Draws the state kept by the ViewModel. */
    private fun render(state: LoginState) {
        when (state) {
            LoginState.Idle -> Unit
            LoginState.LoadingProfile -> {
                progress.visibility = View.VISIBLE
                loginButton.visibility = View.GONE
                logoutButton.visibility = View.GONE
                createTreeButton.visibility = View.GONE
                status.text = null
            }
            is LoginState.Account -> showAccount(state.name, busy = false, canCreate = state.canCreate)
            is LoginState.Creating -> showAccount(state.name, busy = true, canCreate = true)
            is LoginState.ReadyToOpen -> showAccount(state.name, busy = true, canCreate = false)
            is LoginState.Opening -> showAccount(state.name, busy = true, canCreate = false)
            LoginState.Opened -> finish()
            is LoginState.Failed -> when (state.kind) {
                LoginState.Kind.AUTH_REQUIRED -> showLoggedOut(null)
                LoginState.Kind.OFFLINE -> showFailure(state, getString(if (tokens.get() != null) R.string.geni_offline else R.string.geni_offline_login))
                LoginState.Kind.OPEN_FAILED -> showFailure(state, getString(R.string.geni_open_failed))
                LoginState.Kind.ACCOUNT_MISMATCH -> showFailure(state, getString(R.string.geni_account_mismatch))
                LoginState.Kind.OTHER -> showFailure(state, state.message ?: getString(R.string.geni_login_failed))
            }
        }
    }

    private fun showAccount(name: String, busy: Boolean, canCreate: Boolean) {
        progress.visibility = if (busy) View.VISIBLE else View.GONE
        loginButton.visibility = View.GONE
        status.text = getString(R.string.geni_logged_in_as, name)
        logoutButton.visibility = View.VISIBLE
        createTreeButton.visibility = if (canCreate) View.VISIBLE else View.GONE
        createTreeButton.isEnabled = !busy
    }

    /** A recoverable failure: a stored login is kept and, if the account has no tree yet, the button tries again. */
    private fun showFailure(state: LoginState.Failed, text: String) {
        progress.visibility = View.GONE
        // A login that was not stored (offline check, other account) leaves the way to sign in again
        val loggedIn = tokens.get() != null
        loginButton.visibility = if (loggedIn) View.GONE else View.VISIBLE
        logoutButton.visibility = if (loggedIn) View.VISIBLE else View.GONE
        createTreeButton.visibility = if (state.canCreate) View.VISIBLE else View.GONE
        createTreeButton.isEnabled = true
        status.text = text
    }

    /**
     * Shows the diagram of the tree. Once started it is finished even if this screen is recreated meanwhile,
     * so the replacement screen cannot open a second diagram. It then leaves, so Back goes to the trees list.
     */
    private suspend fun openAndClose(tree: LoginState.ReadyToOpen) {
        // All of it is not cancellable: a result lost to a rotation or pause would let the next screen open a second diagram
        val opened = withContext(NonCancellable) {
            val ok = try {
                Geni.host?.openTree(this@GeniLoginActivity, tree.treeId) == true
            } catch (e: Exception) {
                android.util.Log.w("GeniSync", "Opening tree ${tree.treeId} failed", e)
                false
            }
            if (ok) {
                val settled = model.opened(tree.treeId) // False after a logout or a newer login: this screen stays
                if (settled && tree.created) Toast.makeText(applicationContext, R.string.geni_tree_created, Toast.LENGTH_LONG).show()
                settled
            } else {
                model.openFailed(tree.treeId)
                false
            }
        }
        if (opened) finish()
    }

    /** Uploads go on in background: while the screen is visible the counts are read again every few seconds. */
    private var queueWatch: Job? = null

    override fun onResume() {
        super.onResume()
        if (!Geni.isConfigured) return
        queueWatch = lifecycleScope.launch {
            while (true) {
                showQueue()
                delay(3_000)
            }
        }
    }

    override fun onPause() {
        queueWatch?.cancel()
        queueWatch = null
        super.onPause()
    }

    /** Number of changes waiting for upload, and the button to the failed ones. */
    private fun showQueue() {
        lifecycleScope.launch {
            val jobs = Geni.store(applicationContext).allJobs()
            val pending = jobs.count { it.state == JobState.PENDING || it.state == JobState.SENDING }
            val failed = jobs.count { it.state == JobState.FAILED }
            pendingStatus.text = getString(R.string.geni_pending_changes, pending)
            pendingStatus.visibility = if (pending > 0) View.VISIBLE else View.GONE
            failedButton.text = getString(R.string.geni_failed_changes, failed)
            failedButton.visibility = if (failed > 0) View.VISIBLE else View.GONE
            val conflicts = jobs.count { it.state == JobState.CONFLICT }
            conflictsButton.text = getString(R.string.geni_conflicts, conflicts)
            conflictsButton.visibility = if (conflicts > 0) View.VISIBLE else View.GONE
        }
    }

    /** Lists the failed changes with Geni's reason; they can be retried or dismissed together. */
    private fun showFailed() {
        lifecycleScope.launch {
            val failed = Geni.store(applicationContext).allJobs().filter { it.state == JobState.FAILED }
            if (failed.isEmpty()) return@launch showQueue()
            val text = failed.joinToString("\n\n") { "${it.label}\n${it.error.orEmpty()}" }
            AlertDialog.Builder(this@GeniLoginActivity)
                .setTitle(R.string.geni_failed_title)
                .setMessage(text)
                .setPositiveButton(R.string.geni_retry_all) { _, _ ->
                    val ids = failed.map { it.id }.toSet() // Only the jobs shown here, also after confirmation
                    if (failed.any { it.kind == JobKind.ADD_CHILD || it.kind == JobKind.ADD_PARTNER || it.kind == JobKind.ADD_PHOTO || it.kind == JobKind.ADD_DOCUMENT || it.kind == JobKind.ADD_VIDEO }) confirmRetryAdds(ids) else retryAll(ids)
                }
                .setNegativeButton(R.string.geni_dismiss_all) { _, _ -> queueAction { dismissFailed(failed.map { it.id }.toSet()) } }
                .setNeutralButton(R.string.geni_close, null)
                .show()
        }
    }

    /** Lets the user pick one of the jobs stopped by a conflict. */
    private fun chooseConflict() {
        lifecycleScope.launch {
            val conflicts = Geni.store(applicationContext).allJobs().filter { it.state == JobState.CONFLICT }
            when (conflicts.size) {
                0 -> showQueue()
                1 -> showConflict(conflicts[0])
                else -> AlertDialog.Builder(this@GeniLoginActivity)
                    .setItems(conflicts.map { it.label }.toTypedArray()) { _, which -> showConflict(conflicts[which]) }
                    .show()
            }
        }
    }

    /** Both values of each conflicting field (Geni's chosen by default); "Apply" keeps the user's choice. */
    private fun showConflict(job: UploadJob) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val fields = try {
                Geni.resolver(applicationContext).details(job)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progress.visibility = View.GONE
                val message = if (e is IOException) getString(R.string.geni_offline) else getString(R.string.geni_conflict_failed, e.message)
                Toast.makeText(this@GeniLoginActivity, message, Toast.LENGTH_LONG).show()
                return@launch
            }
            progress.visibility = View.GONE
            if (fields.singleOrNull()?.group == GONE) {
                AlertDialog.Builder(this@GeniLoginActivity)
                    .setTitle(job.label)
                    .setMessage(R.string.geni_gone_message)
                    .setPositiveButton(R.string.geni_gone_keep) { _, _ -> resolveConflict(job, setOf(GONE), setOf(GONE)) }
                    .setNegativeButton(R.string.geni_gone_remove) { _, _ -> resolveConflict(job, emptySet(), setOf(GONE)) }
                    .setNeutralButton(R.string.geni_close, null)
                    .show()
                return@launch
            }
            val padding = (16 * resources.displayMetrics.density).toInt()
            val layout = LinearLayout(this@GeniLoginActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding * 3 / 2, padding / 2, padding * 3 / 2, 0)
                addView(TextView(context).apply { setText(R.string.geni_conflict_intro) })
            }
            val mineButtons = fields.associate { field ->
                val name = resources.getIdentifier("geni_group_${field.group}", "string", packageName)
                val locale = LocaleGroup.localeOf(field.group)
                layout.addView(TextView(this@GeniLoginActivity).apply {
                    text = if (locale != null) getString(R.string.geni_group_locale, GeniLocales.label(locale)) else if (name != 0) getString(name) else field.group
                    setPadding(0, padding, 0, 0)
                    setTypeface(typeface, Typeface.BOLD)
                })
                val mine = RadioButton(this@GeniLoginActivity).apply { text = getString(R.string.geni_conflict_mine, field.mine); id = View.generateViewId() }
                val geni = RadioButton(this@GeniLoginActivity).apply { text = getString(R.string.geni_conflict_geni, field.geni); id = View.generateViewId() }
                layout.addView(RadioGroup(this@GeniLoginActivity).apply {
                    addView(mine)
                    addView(geni)
                    check(geni.id)
                })
                field.group to mine
            }
            AlertDialog.Builder(this@GeniLoginActivity)
                .setTitle(job.label)
                .setView(ScrollView(this@GeniLoginActivity).apply { addView(layout) })
                .setPositiveButton(R.string.geni_conflict_apply) { _, _ ->
                    resolveConflict(job, mineButtons.filterValues { it.isChecked }.keys, fields.map { it.group }.toSet())
                }
                .setNegativeButton(R.string.geni_close, null)
                .show()
        }
    }

    private fun resolveConflict(job: UploadJob, keepMine: Set<String>, shown: Set<String>? = null) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                Geni.resolver(applicationContext).resolve(job, keepMine, shown)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = if (e is IOException) getString(R.string.geni_offline) else getString(R.string.geni_conflict_failed, e.message)
                Toast.makeText(this@GeniLoginActivity, message, Toast.LENGTH_LONG).show()
            } finally {
                Geni.resumeUploads?.invoke() // Also when the screen closed during the resolution; safe to repeat
            }
            progress.visibility = View.GONE
            showQueue()
        }
    }

    private fun confirmRetryAdds(ids: Set<Long>) {
        AlertDialog.Builder(this)
            .setMessage(R.string.geni_retry_adds_warning)
            .setPositiveButton(R.string.geni_retry_anyway) { _, _ -> retryAll(ids) }
            .setNegativeButton(R.string.geni_close, null)
            .show()
    }

    private fun retryAll(ids: Set<Long>) = queueAction {
        retryFailed(ids)
        Geni.resumeUploads?.invoke()
    }

    private fun queueAction(action: suspend SyncQueue.() -> Unit) {
        lifecycleScope.launch {
            Geni.queue(applicationContext).action()
            showQueue()
        }
    }

    private fun showLoggedOut(message: String?) {
        createTreeButton.visibility = View.GONE
        progress.visibility = View.GONE
        status.text = message
        loginButton.visibility = View.VISIBLE
        logoutButton.visibility = View.GONE
    }
}
