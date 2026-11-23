package app.familygem.geni.ui

import app.familygem.geni.api.GeniAuthRequiredException
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

    private class FakeFlow : LoginFlow {
        var profile: suspend () -> AccountProfile = { AccountProfile("p1", "V P") }
        var existing: suspend (String) -> Int? = { null }
        var create: suspend () -> Int = { 7 }
        override var canCreateTrees = true
        var createCalls = 0
        override suspend fun profile() = profile.invoke()
        override suspend fun existingTree(profileId: String) = existing(profileId)
        override suspend fun createTree(): Int {
            createCalls++
            return create()
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
        vm.completeLogin()
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
        vm.completeLogin()
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
        vm.completeLogin()
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
        vm.completeLogin()
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
        vm.completeLogin()
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
        vm.completeLogin()
        runCurrent()
        vm.createTree()
        runCurrent()
        gate.complete(7)
        advanceUntilIdle()
        assertEquals(1, flow.createCalls)
        assertEquals(LoginState.ReadyToOpen("V P", 7, created = true), vm.state.value)
    }

    @Test
    fun createFailureThenRetryReusesRegisteredTree() = runTest {
        val flow = FakeFlow()
        flow.create = { throw IOException("offline") }
        val vm = model(flow)
        vm.completeLogin()
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
        vm.completeLogin()
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
        vm.completeLogin()
        runCurrent()
        vm.completeLogin()
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
        vm.completeLogin()
        advanceUntilIdle()
        assertEquals(LoginState.Account("V P", canCreate = false), vm.state.value)
        assertEquals(0, flow.createCalls)
    }
}
