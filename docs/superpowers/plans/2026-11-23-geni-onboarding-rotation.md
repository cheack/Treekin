# Geni Onboarding Survives Rotation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** After a browser login Treekin opens the account's tree (creating it if needed) exactly once, even when the device rotates during profile lookup, tree creation or the open.

**Architecture:** An activity-scoped `GeniLoginViewModel` owns the login-to-tree operation (profile lookup, existing-tree check, creation) and exposes it as `StateFlow<LoginState>`. A finished operation leaves `ReadyToOpen(treeId)` in the state until an activity claims it and `TreeHost.openTree` succeeds. `GeniLoginActivity` keeps OAuth intent handling and navigation, and only renders state. The VM talks to Geni through a small `LoginFlow` interface so it is unit-testable without Android.

**Tech Stack:** Kotlin, kotlinx-coroutines (+ `kotlinx-coroutines-test` 1.11.0), AndroidX lifecycle (ViewModel, `repeatOnLifecycle`), JUnit 4.

**Spec:** the handoff pasted in the session (2026-11-23); baseline in `docs/superpowers/plans/2026-11-20-geni-onboarding.md` (emulator notes, Task 2 step 4) and README roadmap section 7.

## Global Constraints

- New code Kotlin, in `geni/src/main/java/app/familygem/geni/ui/`; no upstream (`app/`) file changes needed.
- No Gradle wrapper. Run from the build copy of the onboarding plan with JDK 21, system Gradle 9.9.0, Android SDK 37.2: `gradle --console=plain :geni:testDebugUnitTest :app:assembleDebug`.
- Keep `GeniHooks.shouldOnboard` and the unconfigured-build fallback (`Geni.isConfigured == false` path in `onCreate`) unchanged.
- The VM holds no Activity/View reference; uses `applicationContext` only (inside the `LoginFlow` implementation).
- Keep `NonCancellable` around `createTree` (no half-made tree when the screen closes). Keep `AppTreeHost.openTree` edit lock and already-open-tree path unchanged.
- OAuth success/denied/invalid handling unchanged; recreation must not re-handle the callback or call `Geni.resumeUploads` again for it.
- Opening the account screen with a saved token must not auto-open a tree.
- Process-death recovery is out of scope; a saved-token entry after process restart must still work.
- Do not touch the existing local README edit (PR #18 link) beyond adding the new lines. Commit message trailer: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` (project memory overrides global rule). Ask nothing; stage is autonomous, but never reply on GitHub without asking.

## Review Focus

- Rotation while `existingTree` lookup is suspended: replacement gets the same tree ID, no create call.
- Logout (or a newer login) while an older operation is running: the older result must never reach `ReadyToOpen`.
- `createTree` fails after it registered the tree (`GeniLoader.createTree` saves the tree row before `load`): manual retry must reuse that tree through `existingTree`, not create a second one.
- Activity cancelled mid-`openTree`: claim is released, replacement opens once; host returning false shows `geni_open_failed` and does not loop.
- Back/Up while `Creating`: creation finishes in the background (NonCancellable), no delayed jump to the diagram.

---

## File Structure

- Create `geni/src/main/java/app/familygem/geni/ui/GeniLoginViewModel.kt`: `LoginFlow`, `LoginState`, `GeniLoginViewModel`, and a `GeniLoginViewModelFactory`.
- Create `geni/src/test/java/app/familygem/geni/ui/GeniLoginViewModelTest.kt`.
- Modify `geni/src/main/java/app/familygem/geni/ui/GeniLoginActivity.kt` (`loadProfile`, `createTree`, `openAndClose`, `showSavedState`, `handleIntent`, logout).
- Modify `geni/build.gradle` only if Task 3 step 1 finds a missing lifecycle artifact.
- Modify docs: `2026-11-20-geni-onboarding.md`, `README.md`.

### Task 1: Retained login-to-tree operation (ViewModel, test first)

**Files:**
- Create: `geni/src/main/java/app/familygem/geni/ui/GeniLoginViewModel.kt`
- Test: `geni/src/test/java/app/familygem/geni/ui/GeniLoginViewModelTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  data class AccountProfile(val id: String, val name: String)
  interface LoginFlow {
      suspend fun profile(): AccountProfile
      /** Null when the host is missing or the account has no live tree. */
      suspend fun existingTree(profileId: String): Int?
      /** True when trees can be created at all (host installed). */
      val canCreateTrees: Boolean
      suspend fun createTree(): Int
  }
  sealed interface LoginState {
      object Idle : LoginState
      object LoadingProfile : LoginState
      data class Account(val name: String, val canCreate: Boolean) : LoginState   // shown, nothing to open
      data class Creating(val name: String) : LoginState
      data class ReadyToOpen(val name: String, val treeId: Int, val created: Boolean) : LoginState
      data class Opening(val name: String, val treeId: Int) : LoginState          // claimed by an activity
      data class Failed(val kind: Kind, val message: String?, val name: String?, val canCreate: Boolean) : LoginState
      enum class Kind { OFFLINE, AUTH_REQUIRED, OTHER }
  }
  class GeniLoginViewModel(flow: LoginFlow, scope: CoroutineScope? = null) : ViewModel() {
      val state: StateFlow<LoginState>
      fun openAccount()                  // saved token: Account/Failed, never opens
      fun completeLogin()                // fresh OAuth success: opens existing tree or creates
      fun createTree()                   // manual: reuses existingTree if present, else creates
      fun claim(treeId: Int): Boolean    // ReadyToOpen -> Opening, true once
      fun release(treeId: Int)           // Opening -> ReadyToOpen (cancelled attempt)
      fun opened(treeId: Int)            // Opening -> Account (consumed)
      fun openFailed(treeId: Int)        // Opening -> Account, no auto retry
      fun reset()                        // logout: cancel work, drop old results, Idle
  }
  ```
  `Failed.kind == AUTH_REQUIRED` means the activity shows the logged-out screen.
- Consumes: nothing from earlier tasks.

- [ ] **Step 1: Write failing tests** in `GeniLoginViewModelTest` (JUnit 4, `runTest`, `Dispatchers.setMain(StandardTestDispatcher())` in `@Before`, VM built with `scope = backgroundScope`-like test scope; `LoginFlow` fake with `CompletableDeferred` gates and call counters). Test names and assertions:
  - `freshLoginCreatesOnceAndSurvivesObserverChange`: `existingTree` returns null; `completeLogin()`; gate `createTree` open; collect `state` in observer A, cancel A while `createTree` suspended, attach observer B; release gate. B sees `ReadyToOpen(treeId = 7, created = true)`; `createCalls == 1`.
  - `freshLoginOpensExistingWithoutCreate`: `existingTree` returns 5; `completeLogin()`; state `ReadyToOpen(treeId = 5, created = false)`; `createCalls == 0`; same result when observer swapped while `existingTree` is suspended.
  - `openAccountNeverOpens`: `existingTree` returns 5; `openAccount()`; final state `Account(canCreate = false)`, never `ReadyToOpen`.
  - `claimIsOnceAndReleaseKeepsId`: after `ReadyToOpen(7)`: `claim(7)` true, `claim(7)` false, `release(7)`, `claim(7)` true; `opened(7)` leaves `Account`; `claim(7)` false afterwards.
  - `openFailedDoesNotReadyAgain`: `claim(7)`, `openFailed(7)`; state is `Account`, `claim(7)` false.
  - `manualCreateWhileAutoCreateRunsSharesOperation`: `completeLogin()` with gated create, then `createTree()`; `createCalls == 1`.
  - `createFailureThenRetryReusesRegisteredTree`: first `createTree` throws `IOException`, state `Failed(OFFLINE, canCreate = true)`; fake `existingTree` now returns 9; `createTree()` → `ReadyToOpen(9, created = false)`, `createCalls == 1`.
  - `resetDropsOlderResult`: `completeLogin()` with gated `profile`; `reset()`; release gate; state stays `Idle`. Also `completeLogin()` then second `completeLogin()` (new account id): only the second result is published.
  - `authRequiredMapsToKind`: `profile()` throws `GeniAuthRequiredException` → `Failed(AUTH_REQUIRED)`.
- [ ] **Step 2: Run to verify failure**
  Run: `gradle --console=plain :geni:testDebugUnitTest --tests '*GeniLoginViewModelTest*'`
  Expected: FAIL (compilation: `GeniLoginViewModel` unresolved).
- [ ] **Step 3: Implement** `GeniLoginViewModel.kt` per the Interfaces block. Approach: one `Job?` field plus an `Int` generation counter; every entry point (`openAccount`, `completeLogin`, `reset`) bumps the generation and cancels the previous job, and every state write is guarded by `generation == mine`. `createTree()` returns early if state is `Creating`; otherwise it runs `existingTree(profileId)` first, then `flow.createTree()`. `claim` uses `MutableStateFlow.compareAndSet`. Exceptions map: `CancellationException` rethrown, `GeniAuthRequiredException` → `AUTH_REQUIRED`, `IOException` → `OFFLINE`, other → `OTHER` with `localizedMessage`. Also add `GeniLoginViewModelFactory(flow)` and a production `AppLoginFlow(context)` implementing `LoginFlow` over `Geni.client(...).currentProfile()`, `Geni.loader(...).existingTree`, `withContext(NonCancellable) { Geni.loader(...).createTree() }`, `canCreateTrees = Geni.host != null`.
- [ ] **Step 4: Run tests to verify pass** (same command). Expected: PASS, all nine.
- [ ] **Step 5: Commit** `Retain the Geni login-to-tree operation in a ViewModel`.

### Task 2: Wire the activity to the retained state

**Files:**
- Modify: `geni/src/main/java/app/familygem/geni/ui/GeniLoginActivity.kt` (lines ~103-203 and the logout listener ~92-101)
- Modify: `geni/build.gradle` only if needed.

**Interfaces:**
- Consumes: `GeniLoginViewModel`, `LoginState`, `AppLoginFlow`, `GeniLoginViewModelFactory` from Task 1.

- [ ] **Step 1: Check lifecycle availability.** Confirm `ViewModelProvider`, `repeatOnLifecycle` and `viewModelScope` resolve in `:geni` (appcompat 1.8.0 pulls them in). If not, add `implementation 'androidx.lifecycle:lifecycle-viewmodel:2.11.0'` (version used by `:app`'s `lifecycle-process`). Run `gradle --console=plain :geni:compileDebugKotlin`; expected: no errors.
- [ ] **Step 2: Replace the screen logic.**
  - `private val model by lazy { ViewModelProvider(this, GeniLoginViewModelFactory(AppLoginFlow(applicationContext)))[GeniLoginViewModel::class.java] }`; created only after the `Geni.isConfigured` check.
  - `handleIntent`: `AuthResult.Success` still saves the token and calls `Geni.resumeUploads`, then `model.completeLogin()`. No-redirect path → `showSavedState()`.
  - `showSavedState()`: if `tokens.get() == null` → `model.reset(); showLoggedOut(null)`; else `if (model.state.value == LoginState.Idle) model.openAccount()` (so a recreation with retained state does not start a competing lookup).
  - `onCreate`: start one `lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.RESUMED) { model.state.collect(::render) } }`; replace `savedInstanceState == null` branching with: `if (savedInstanceState == null) handleIntent(intent) else showSavedState()` (keep).
  - `render(state)`: `LoadingProfile`/`Creating` → progress visible, login/logout/create hidden or create disabled; `Account` → status text `geni_logged_in_as`, logout visible, create button per `canCreate`; `Failed` → `AUTH_REQUIRED` → `tokens` untouched, `showLoggedOut(null)`; `OFFLINE` → `geni_offline` (create button re-enabled when `canCreate`, logout visible); `OTHER` → message or `geni_login_failed`; `ReadyToOpen` → `if (model.claim(treeId)) openAndClose(treeId, created)`.
  - `openAndClose(treeId, created)`: `try { if (Geni.host?.openTree(this, treeId) == true) { model.opened(treeId); finish() } else { model.openFailed(treeId); status.setText(R.string.geni_open_failed) } } catch (e: CancellationException) { model.release(treeId); throw e }`. Show the `geni_tree_created` toast only when `created`.
  - Create button → `model.createTree()`. Logout confirm → `tokens.clear(); model.reset(); showLoggedOut(null)`.
  - Delete the activity's own `loadProfile`/`createTree` coroutines and the now-unused `NonCancellable`/`withContext`/`GeniAuthRequiredException` imports only if unused.
- [ ] **Step 3: Build and unit tests.**
  Run: `gradle --console=plain :geni:testDebugUnitTest :app:assembleDebug`
  Expected: BUILD SUCCESSFUL.
- [ ] **Step 4: Commit** `Open the Geni tree from the retained login state after rotation`.

### Task 3: Emulator verification and unconfigured build

**Files:**
- Modify: `docs/superpowers/plans/2026-11-20-geni-onboarding.md`

- [ ] **Step 1: Sandbox emulator** (`Pixel_8_API_36`, per project memory): install the debug build from the build copy; `adb shell pm clear net.cheack.treekin`; log in and rotate repeatedly (`adb shell settings put system user_rotation N` every ~1 s) during the browser return and tree creation. Expected: exactly one new tree in the list, one diagram launch, no Create tap.
- [ ] **Step 2: Repeat login.** Log out, log in to the same account, rotate during lookup. Expected: existing tree opens, tree count unchanged.
- [ ] **Step 3: Manual create and failures.** Rotate during manual "Create Geni tree" (tree opens); airplane mode before create (error text, button usable, retry works; check no duplicate tree).
- [ ] **Step 4: Saved-token entry.** Open the account screen with a saved token (no auto-open, Back/Up leaves cleanly); `adb shell am kill net.cheack.treekin` then relaunch: account screen still loads.
- [ ] **Step 5: Unconfigured build** in a separate copy without `geni.properties`: `gradle --console=plain :app:assembleDebug` succeeds; install, fresh start shows the normal empty-tree prompt.
- [ ] **Step 6: Record results** in the Task 2 step 4 notes of `2026-11-20-geni-onboarding.md` (replace the "known minor issue" sentence with the new result, list what was not checked). Commit `Record the rotation checks in the onboarding plan`.

### Task 4: Roadmap and PR

**Files:**
- Modify: `docs/superpowers/plans/README.md` (section 7, progress log at top; keep the existing PR #18 edit)

- [ ] **Step 1:** In section 7 split the unchecked "Not checked" item into three: `- [x]` second login with tree present (passed 2026-11-20), rotation result (tick when Task 3 passes), `- [x]`/`- [ ]` no-`geni.properties` build. Add a dated progress-log line after merge with the PR link.
- [ ] **Step 2:** Push branch `geni-onboarding-rotation`, open PR into `geni`, wait for CodeRabbit, address findings (ask before replying on GitHub), final whole-branch review on sonnet, merge, add the progress-log line, commit.

## Self-Review notes

- Coverage: ViewModel retention (T1), wiring and claim/release (T2), all acceptance criteria and the unconfigured build (T3), docs (T4). Process death stays out of scope; saved-token restart is checked in T3 step 4.
- Types match across tasks: `LoginState`, `claim`/`release`/`opened`/`openFailed`, `AppLoginFlow`.
