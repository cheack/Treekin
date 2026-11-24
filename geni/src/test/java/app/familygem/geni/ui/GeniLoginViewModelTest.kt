package app.familygem.geni.ui

import app.familygem.geni.api.GeniAuthRequiredException
import app.familygem.geni.auth.AuthResult
import app.familygem.geni.sync.TreeOwnership
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GeniLoginViewModelTest {

    private val login = AuthResult.Success("candidate", Long.MAX_VALUE)

    private class FakeFlow : LoginFlow {
        var profile: suspend () -> AccountProfile = { AccountProfile("p1", "V P") }
        /** Complete tree of the account, null for none. */
        var existing: suspend (String) -> Int? = { null }
        /** A tree of another account is on the device. */
        var other = false
        /** Replaces the answer built from [existing] and [other]. */
        var ownershipOverride: TreeOwnership? = null
        var create: suspend () -> Int = { 7 }
        override var canCreateTrees = true
        var createCalls = 0
        val accepted = mutableListOf<AuthResult.Success>()
        /** "accept" and "create" in the order they happened. */
        val calls = mutableListOf<String>()
        val profileCandidates = mutableListOf<AuthResult.Success?>()
        override suspend fun profile(candidate: AuthResult.Success?): AccountProfile {
            profileCandidates += candidate
            return profile.invoke()
        }
        override suspend fun ownership(profileId: String): TreeOwnership =
            ownershipOverride ?: if (other) TreeOwnership.Other else existing(profileId)?.let { TreeOwnership.Own(it, complete = true) } ?: TreeOwnership.None
        override suspend fun createTree(): Int {
            createCalls++
            calls += "create"
            return create()
        }
        override fun accept(candidate: AuthResult.Success) {
            accepted += candidate
            calls += "accept"
        }
    }

    private fun TestScope.model(flow: FakeFlow) =
        GeniLoginViewModel(flow, CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

    @Test
    fun freshLoginCreatesOnceAndSurvivesObserverChange() = runTest {
        val flow = FakeFlow()
        val gate = CompletableDeferred<Int>()
        flow.create = { gate.await() }
        val vm = model(flow)
        vm.completeLogin(login)
        val observerA = launch { vm.state.collect { } }
        runCurrent()
        assertEquals(LoginState.Creating("V P"), vm.state.value)
        observerA.cancel() // Rotation: the first screen is gone, the operation goes on
        val observerB = launch { vm.state.collect { } }
        gate.complete(7)
        advanceUntilIdle()
        assertEquals(LoginState.ReadyToOpen("V P", 7, created = true), vm.state.value)
        assertEquals(1, flow.createCalls)
        observerB.cancel()
    }

    @Test
    fun freshLoginOpensExistingWithoutCreate() = runTest {
        val flow = FakeFlow()
        val gate = CompletableDeferred<Int?>()
        flow.existing = { gate.await() }
        val vm = model(flow)
        vm.completeLogin(login)
        runCurrent()
        assertEquals(LoginState.LoadingProfile, vm.state.value)
        gate.complete(5)
        advanceUntilIdle()
        assertEquals(LoginState.ReadyToOpen("V P", 5, created = false), vm.state.value)
        assertEquals(0, flow.createCalls)
    }

    @Test
    fun openAccountNeverOpens() = runTest {
        val flow = FakeFlow()
        flow.existing = { 5 }
        val vm = model(flow)
        vm.openAccount()
        advanceUntilIdle()
        assertEquals(LoginState.Account("V P", canCreate = false), vm.state.value)
    }

    @Test
    fun openAccountOffersCreateWhenNoTree() = runTest {
        val vm = model(FakeFlow())
        vm.openAccount()
        advanceUntilIdle()
        assertEquals(LoginState.Account("V P", canCreate = true), vm.state.value)
    }

    @Test
    fun claimIsOnceAndReleaseKeepsId() = runTest {
        val vm = model(FakeFlow().apply { existing = { 7 } })
        vm.completeLogin(login)
        advanceUntilIdle()
        assertTrue(vm.claim(7))
        assertFalse(vm.claim(7))
        vm.release(7)
        assertEquals(LoginState.ReadyToOpen("V P", 7, created = false), vm.state.value)
        assertTrue(vm.claim(7))
        vm.opened(7)
        assertEquals(LoginState.Opened, vm.state.value) // A replacement screen sees it and leaves too
        assertFalse(vm.claim(7))
    }

    @Test
    fun openedAfterResetIsRejected() = runTest {
        val vm = model(FakeFlow().apply { existing = { 7 } })
        vm.completeLogin(login)
        advanceUntilIdle()
        assertTrue(vm.claim(7))
        vm.reset() // Logout while the tree is being opened
        assertFalse(vm.opened(7))
        assertFalse(vm.openFailed(7))
        assertEquals(LoginState.Idle, vm.state.value)
    }

    @Test
    fun openFailedDoesNotReadyAgain() = runTest {
        val vm = model(FakeFlow().apply { existing = { 7 } })
        vm.completeLogin(login)
        advanceUntilIdle()
        assertTrue(vm.claim(7))
        vm.openFailed(7)
        assertEquals(LoginState.Failed(LoginState.Kind.OPEN_FAILED, null, "V P", canCreate = false), vm.state.value)
        assertFalse(vm.claim(7))
    }

    @Test
    fun manualCreateWhileAutoCreateRunsSharesOperation() = runTest {
        val flow = FakeFlow()
        val gate = CompletableDeferred<Int>()
        flow.create = { gate.await() }
        val vm = model(flow)
        vm.completeLogin(login)
        runCurrent()
        vm.createTree()
        runCurrent()
        gate.complete(7)
        advanceUntilIdle()
        assertEquals(1, flow.createCalls)
        assertEquals(LoginState.ReadyToOpen("V P", 7, created = true), vm.state.value)
    }

    @Test
    fun secondLoginDuringCreationDoesNotCreateAgain() = runTest {
        val flow = FakeFlow()
        val gate = CompletableDeferred<Int>()
        var tree: Int? = null
        flow.existing = { tree }
        flow.create = { gate.await().also { tree = it } }
        val vm = model(flow)
        vm.completeLogin(login)
        runCurrent()
        vm.completeLogin(login) // Newer login while the first creation is still writing the tree
        runCurrent()
        gate.complete(7)
        advanceUntilIdle()
        assertEquals(1, flow.createCalls)
        assertEquals(LoginState.ReadyToOpen("V P", 7, created = false), vm.state.value)
    }

    @Test
    fun loginCancelledWhileWaitingForCreationDoesNotCreate() = runTest {
        val flow = FakeFlow()
        val gate = CompletableDeferred<Int>()
        flow.create = { gate.await() }
        val vm = model(flow)
        vm.completeLogin(login)
        runCurrent()
        vm.completeLogin(login) // Waits for the first creation
        runCurrent()
        vm.reset() // Logout: the waiting one is cancelled and must not create a tree afterwards
        gate.complete(7)
        advanceUntilIdle()
        assertEquals(1, flow.createCalls)
        assertEquals(LoginState.Idle, vm.state.value)
    }

    @Test
    fun createFailureThenRetryReusesRegisteredTree() = runTest {
        val flow = FakeFlow()
        flow.create = { throw IOException("offline") }
        val vm = model(flow)
        vm.completeLogin(login)
        advanceUntilIdle()
        assertEquals(LoginState.Failed(LoginState.Kind.OFFLINE, null, "V P", canCreate = true), vm.state.value)
        flow.existing = { 9 } // The failed attempt had registered the tree before it broke
        vm.createTree()
        advanceUntilIdle()
        assertEquals(LoginState.ReadyToOpen("V P", 9, created = false), vm.state.value)
        assertEquals(1, flow.createCalls)
    }

    @Test
    fun resetDropsOlderResult() = runTest {
        val flow = FakeFlow()
        val gate = CompletableDeferred<AccountProfile>()
        flow.profile = { gate.await() }
        val vm = model(flow)
        vm.completeLogin(login)
        runCurrent()
        vm.reset()
        gate.complete(AccountProfile("p1", "Old"))
        advanceUntilIdle()
        assertEquals(LoginState.Idle, vm.state.value)
        assertEquals(0, flow.createCalls)
    }

    @Test
    fun newerLoginWinsOverOlderOne() = runTest {
        val flow = FakeFlow()
        val old = CompletableDeferred<AccountProfile>()
        var calls = 0
        flow.profile = { if (calls++ == 0) old.await() else AccountProfile("p2", "New") }
        flow.existing = { if (it == "p2") 2 else 1 }
        val vm = model(flow)
        vm.completeLogin(login)
        runCurrent()
        vm.completeLogin(login)
        advanceUntilIdle()
        old.complete(AccountProfile("p1", "Old"))
        advanceUntilIdle()
        assertEquals(LoginState.ReadyToOpen("New", 2, created = false), vm.state.value)
    }

    @Test
    fun authRequiredMapsToKind() = runTest {
        val flow = FakeFlow()
        flow.profile = { throw GeniAuthRequiredException() }
        val vm = model(flow)
        vm.openAccount()
        advanceUntilIdle()
        assertEquals(LoginState.Failed(LoginState.Kind.AUTH_REQUIRED, null, null, canCreate = false), vm.state.value)
    }

    @Test
    fun offlineProfileKeepsLoginWithoutCreate() = runTest {
        val flow = FakeFlow()
        flow.profile = { throw IOException("no network") }
        val vm = model(flow)
        vm.openAccount()
        advanceUntilIdle()
        assertEquals(LoginState.Failed(LoginState.Kind.OFFLINE, null, null, canCreate = false), vm.state.value)
    }

    @Test
    fun noHostMeansNoCreate() = runTest {
        val flow = FakeFlow().apply { canCreateTrees = false }
        val vm = model(flow)
        vm.completeLogin(login)
        advanceUntilIdle()
        assertEquals(LoginState.Account("V P", canCreate = false), vm.state.value)
        assertEquals(0, flow.createCalls)
    }

    @Test
    fun otherAccountIsRefusedBeforeItsLoginIsStored() = runTest {
        val flow = FakeFlow().apply { other = true }
        val vm = model(flow)
        vm.completeLogin(login)
        advanceUntilIdle()
        assertEquals(LoginState.Failed(LoginState.Kind.ACCOUNT_MISMATCH, null, "V P", canCreate = false), vm.state.value)
        assertEquals(listOf<AuthResult.Success?>(login), flow.profileCandidates) // Asked with the candidate token
        assertTrue(flow.accepted.isEmpty()) // Not stored, so no queued upload can run under it
        assertEquals(0, flow.createCalls)
        vm.createTree() // The button is not there; a call changes nothing
        advanceUntilIdle()
        assertEquals(0, flow.createCalls)
        assertTrue(flow.accepted.isEmpty())
    }

    @Test
    fun ownerLoginIsStoredBeforeTheTreeOpens() = runTest {
        val flow = FakeFlow().apply { existing = { 5 } }
        val vm = model(flow)
        vm.completeLogin(login)
        advanceUntilIdle()
        assertEquals(listOf(login), flow.accepted)
        assertEquals(LoginState.ReadyToOpen("V P", 5, created = false), vm.state.value)
    }

    @Test
    fun firstLoginStoresTheLoginAndCreates() = runTest {
        val flow = FakeFlow()
        val vm = model(flow)
        vm.completeLogin(login)
        advanceUntilIdle()
        assertEquals(listOf(login), flow.accepted)
        assertEquals(1, flow.createCalls)
        assertEquals(listOf("accept", "create"), flow.calls) // Nothing is created under a login that is not stored yet
    }

    @Test
    fun loginWaitsForACreationThatIsStillRegisteringItsTree() = runTest {
        val flow = FakeFlow()
        val gate = CompletableDeferred<Int>()
        flow.create = { gate.await().also { flow.other = true } } // Once registered, the tree refuses every other account
        val vm = model(flow)
        val first = AuthResult.Success("first", Long.MAX_VALUE)
        vm.completeLogin(first)
        runCurrent() // The first login is creating the tree
        vm.completeLogin(login) // Another account's login arrives before the registration ended
        runCurrent()
        assertTrue(flow.accepted == listOf(first)) // Not stored yet: it waits
        gate.complete(7)
        advanceUntilIdle()
        assertEquals(listOf(first), flow.accepted) // Refused, never stored
        assertEquals(LoginState.Failed(LoginState.Kind.ACCOUNT_MISMATCH, null, "V P", canCreate = false), vm.state.value)
    }

    @Test
    fun loginDroppedWhileTheAccountIsLookedUpIsNotStored() = runTest {
        val flow = FakeFlow()
        val gate = CompletableDeferred<AccountProfile>()
        flow.profile = { gate.await() }
        val vm = model(flow)
        vm.completeLogin(login)
        runCurrent()
        vm.reset()
        gate.complete(AccountProfile("p1", "V P"))
        advanceUntilIdle()
        assertTrue(flow.accepted.isEmpty())
        assertEquals(0, flow.createCalls)
    }

    @Test
    fun interruptedSetupIsFinishedNotReopenedEmpty() = runTest {
        val flow = FakeFlow().apply {
            ownershipOverride = TreeOwnership.Own(5, complete = false)
            create = { 5 }
        }
        val vm = model(flow)
        vm.completeLogin(login)
        advanceUntilIdle()
        assertEquals(1, flow.createCalls) // createTree() finishes the setup of tree 5
        assertEquals(LoginState.ReadyToOpen("V P", 5, created = true), vm.state.value)
    }

    @Test
    fun interruptedSetupOffersTheButtonOnSavedLogin() = runTest {
        val flow = FakeFlow().apply { ownershipOverride = TreeOwnership.Own(5, complete = false) }
        val vm = model(flow)
        vm.openAccount()
        advanceUntilIdle()
        assertEquals(LoginState.Account("V P", canCreate = true), vm.state.value)
    }

    @Test
    fun lookupOfTheCandidateFailingOfflineStoresNothing() = runTest {
        val flow = FakeFlow().apply { profile = { throw IOException("no network") } }
        val vm = model(flow)
        vm.completeLogin(login)
        advanceUntilIdle()
        assertEquals(LoginState.Failed(LoginState.Kind.OFFLINE, null, null, canCreate = false), vm.state.value)
        assertTrue(flow.accepted.isEmpty())
        assertTrue(vm.holdsState()) // A rotation must not replace this by the logged-out view
    }

    @Test
    fun savedLoginOfAnotherAccountIsShownAsMismatch() = runTest {
        val vm = model(FakeFlow().apply { other = true })
        vm.openAccount()
        advanceUntilIdle()
        assertEquals(LoginState.Failed(LoginState.Kind.ACCOUNT_MISMATCH, null, "V P", canCreate = false), vm.state.value)
    }
}
