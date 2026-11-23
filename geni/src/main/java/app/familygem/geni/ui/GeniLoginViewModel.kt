package app.familygem.geni.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.familygem.geni.Geni
import app.familygem.geni.api.GeniAuthRequiredException
import app.familygem.geni.ui.LoginState.Kind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

data class AccountProfile(val id: String, val name: String)

/** What the login screen needs from Geni and from the app; a fake in tests. */
interface LoginFlow {
    suspend fun profile(): AccountProfile

    /** ID of a live tree of this account, null if there is none. */
    suspend fun existingTree(profileId: String): Int?

    /** False when no tree host is installed. */
    val canCreateTrees: Boolean

    suspend fun createTree(): Int
}

sealed interface LoginState {
    object Idle : LoginState
    object LoadingProfile : LoginState
    data class Account(val name: String, val canCreate: Boolean) : LoginState
    data class Creating(val name: String) : LoginState

    /** A tree waits to be shown; stays here until a screen claims it and manages to open it. */
    data class ReadyToOpen(val name: String, val treeId: Int, val created: Boolean) : LoginState

    /** Claimed by a screen that is opening it now. */
    data class Opening(val name: String, val treeId: Int, val created: Boolean) : LoginState

    /** The tree is on screen. Every login screen of this operation, also a replacement made by rotation, should close. */
    object Opened : LoginState

    /** [name] and [canCreate] are known when the profile was loaded. */
    data class Failed(val kind: Kind, val message: String?, val name: String?, val canCreate: Boolean) : LoginState

    enum class Kind { OFFLINE, AUTH_REQUIRED, OPEN_FAILED, OTHER }
}

/**
 * The work between a Geni login and the tree on screen. It lives as long as the login screen,
 * so rotation neither loses the result nor starts the work twice. It never touches views or activities.
 * Every start (or [reset]) makes older work obsolete: its result is dropped.
 */
class GeniLoginViewModel(private val flow: LoginFlow, scope: CoroutineScope? = null) : ViewModel() {

    private val work: CoroutineScope = scope ?: viewModelScope
    private val _state = MutableStateFlow<LoginState>(LoginState.Idle)
    val state: StateFlow<LoginState> = _state
    private var generation = 0
    private var job: Job? = null
    private var profile: AccountProfile? = null
    private val createLock = Mutex()

    /** Saved login: shows the account, never opens a tree. */
    fun openAccount() = start { mine, p -> publish(mine, LoginState.Account(p.name, canCreate(p, existing = flow.existingTree(p.id)))) }

    /** Fresh login: opens the tree of the account, creating it first if there is none. */
    fun completeLogin() = start { mine, p ->
        val existing = flow.existingTree(p.id)
        when {
            existing != null -> publish(mine, LoginState.ReadyToOpen(p.name, existing, created = false))
            flow.canCreateTrees -> create(mine, p)
            else -> publish(mine, LoginState.Account(p.name, canCreate = false))
        }
    }

    /** The "Create tree" button. A tree registered by an attempt that failed later is reused. */
    fun createTree() {
        val p = profile ?: return
        val s = _state.value
        if (!((s is LoginState.Account && s.canCreate) || (s is LoginState.Failed && s.canCreate))) return // Already running or done
        job?.cancel()
        val mine = ++generation
        publish(mine, LoginState.Creating(p.name))
        job = work.launch {
            guarded(mine) {
                val existing = flow.existingTree(p.id)
                if (existing != null) publish(mine, LoginState.ReadyToOpen(p.name, existing, created = false)) else create(mine, p)
            }
        }
    }

    /** True for the one screen that may now open the tree. */
    fun claim(treeId: Int): Boolean {
        while (true) {
            val s = _state.value
            if (s !is LoginState.ReadyToOpen || s.treeId != treeId) return false
            if (_state.compareAndSet(s, LoginState.Opening(s.name, s.treeId, s.created))) return true
        }
    }

    /** The screen was cancelled before the tree was shown: the next screen can claim it again. */
    fun release(treeId: Int) {
        val s = _state.value
        if (s is LoginState.Opening && s.treeId == treeId) _state.compareAndSet(s, LoginState.ReadyToOpen(s.name, s.treeId, s.created))
    }

    /** The tree is on screen: the claim is used up. */
    /** @return false if the claim no longer holds (logout or a newer login came meanwhile). */
    fun opened(treeId: Int): Boolean = settle(treeId) { LoginState.Opened }

    /** The tree could not be read: no automatic retry. */
    fun openFailed(treeId: Int): Boolean = settle(treeId) { LoginState.Failed(Kind.OPEN_FAILED, null, it, canCreate = false) }

    /** Logout: whatever is running or waiting is dropped. */
    fun reset() {
        generation++
        job?.cancel()
        job = null
        profile = null
        _state.value = LoginState.Idle
    }

    private fun settle(treeId: Int, next: (String) -> LoginState): Boolean {
        val s = _state.value
        return s is LoginState.Opening && s.treeId == treeId && _state.compareAndSet(s, next(s.name))
    }

    private fun canCreate(p: AccountProfile, existing: Int?) = flow.canCreateTrees && existing == null

    private fun start(block: suspend (mine: Int, p: AccountProfile) -> Unit) {
        job?.cancel()
        val mine = ++generation
        profile = null
        publish(mine, LoginState.LoadingProfile)
        job = work.launch {
            guarded(mine) {
                val p = flow.profile()
                if (mine == generation) profile = p
                block(mine, p)
            }
        }
    }

    private suspend fun create(mine: Int, p: AccountProfile) {
        publish(mine, LoginState.Creating(p.name))
        // Not cancelled by leaving the screen: a half-made tree would be left behind.
        // One creation at a time, and the tree is looked up again inside the lock: a newer login
        // must reuse the tree an older, still writing creation is about to register.
        val (treeId, created) = withContext(NonCancellable) {
            createLock.withLock { flow.existingTree(p.id)?.let { it to false } ?: (flow.createTree() to true) }
        }
        publish(mine, LoginState.ReadyToOpen(p.name, treeId, created))
    }

    private suspend fun guarded(mine: Int, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val p = profile
            val canCreate = p != null && flow.canCreateTrees
            val failed = when (e) {
                is GeniAuthRequiredException -> LoginState.Failed(Kind.AUTH_REQUIRED, null, p?.name, canCreate = false)
                is IOException -> LoginState.Failed(Kind.OFFLINE, null, p?.name, canCreate)
                else -> LoginState.Failed(Kind.OTHER, e.localizedMessage, p?.name, canCreate)
            }
            publish(mine, failed)
        }
    }

    private fun publish(mine: Int, next: LoginState) {
        if (mine == generation) _state.value = next
    }
}

class GeniLoginViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = GeniLoginViewModel(AppLoginFlow(context.applicationContext)) as T
}

/** The real thing: Geni client, loader and tree host. */
class AppLoginFlow(private val context: Context) : LoginFlow {
    override suspend fun profile(): AccountProfile {
        val p = Geni.client(context).currentProfile()
        return AccountProfile(p.id, p.name ?: listOfNotNull(p.firstName, p.lastName).joinToString(" "))
    }

    override suspend fun existingTree(profileId: String): Int? = if (Geni.host != null) Geni.loader(context).existingTree(profileId) else null

    override val canCreateTrees: Boolean get() = Geni.host != null

    override suspend fun createTree(): Int = Geni.loader(context).createTree()
}
