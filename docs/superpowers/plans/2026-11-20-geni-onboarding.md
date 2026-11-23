# Geni-first onboarding Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Treekin is a Geni client: with no trees the first screen is "Log in with Geni", and a fresh login creates and opens the user's Geni tree without a button press.

**Architecture:** One upstream hook in `TreesActivity` (empty tree list) calls `GeniHooks.startOnboarding`. Everything else lives in `:geni`: `GeniLoginActivity` creates the tree right after a fresh login and asks the host to open it. Upstream features stay in the code and menus.

**Tech Stack:** Kotlin, coroutines, JUnit 4 (`:geni` unit tests), emulator `Pixel_8_API_36` for the screen flow.

**Decision (user, 2026-11-20):** the app is for Geni only; the upstream "new tree" prompt must not be the first screen, and the tree is created automatically after login.

## Global Constraints

- One-line GENI-HOOK in upstream, listed in `geni/UPSTREAM-HOOKS.md`.
- No Geni logo or name in the app name; no change to upstream tree-creation screens.
- The Geni tree is always rooted at the logged-in user's profile, except in the dev tool (Task 1 `rootGeniId`).
- Build and test only in an rsync copy (memory `build-env`).

## Review Focus

- Back from the login screen returns to the normal empty list with the "new tree" prompt (no loop, onboarding starts once per process).
- Login succeeds, tree creation fails (offline, 429): the account screen stays with the "Create tree" button and the message; nothing is created twice.
- A user who already has a Geni tree for this profile and logs in again: that tree is opened, not a second one created.
- Rotating the screen during creation does not start a second creation.
- `Geni.isConfigured` false (build without `geni.properties`): the old prompt is shown.

---

### Task 1: Create a tree from a chosen root and open it (`:geni`)

**Files:**
- Modify: `geni/src/main/java/app/familygem/geni/sync/GeniLoader.kt` (`createTree(title: String? = null, rootGeniId: String? = null)`: root profile is `client.profiles(listOf(id))` for a given id, else `currentProfile()`)
- Modify: `geni/src/main/java/app/familygem/geni/TreeHost.kt` (`fun openTree(activity: android.app.Activity, treeId: Int)`), `geni/src/test/.../FakeTreeHost.kt` (records `opened`), `app/src/geni/java/app/familygem/geni/AppTreeHost.kt` (opens the tree like TreesActivity: `TreeUtil.openGedcom(treeId, false)` then `MainActivity`)
- Modify: `geni/src/main/java/app/familygem/geni/ui/GeniLoginActivity.kt`
- Test: `geni/src/test/java/app/familygem/geni/sync/GeniLoaderTest.kt`

**Interfaces:**
- Produces: `GeniLoader.createTree(title, rootGeniId)`, `TreeHost.openTree(activity, treeId)`.

- [ ] **Step 1: Failing test** `createTreeFromGivenRoot`: the fake client serves profile-2; `loader().createTree(rootGeniId = "profile-2")` stores tree info with `rootGeniId == "profile-2"`, title is the profile name. Run `:geni:testDebugUnitTest --tests '*GeniLoaderTest*'`: FAIL (no parameter).
- [ ] **Step 2: Implement** the parameter; PASS.
- [ ] **Step 3: Host and screen.** Add `openTree` to the interface, fake and app host. In `GeniLoginActivity`, a redirect handled as `AuthResult.Success` calls `loadProfile(afterLogin = true)`; with `afterLogin`: if `existingTree(profile.id)` exists, open it, else create the tree (same code as the button, guarded by `createTreeButton.isEnabled`/a `creating` flag that survives rotation in `lifecycleScope`), then `Geni.host.openTree(this, treeId)` and `finish()`. On failure the existing error text and the button stay.
- [ ] **Step 4: Build and unit tests** in the rsync copy: `:geni:testDebugUnitTest :app:assembleDebug`. Expected: no output.
- [ ] **Step 5: Commit.**

### Task 2: First screen is the Geni login (hook)

**Files:**
- Modify: `app/src/main/java/app/familygem/TreesActivity.kt:94` (`else if (Global.settings.trees.isEmpty()) { if (!app.familygem.geni.GeniGlue.startOnboarding(this)) welcome.show() } // GENI-HOOK`)
- Modify: `geni/.../GeniHooks.kt` (`startOnboarding(activity): Boolean`), `app/src/geni/.../GeniGlue.kt` (forward), `geni/UPSTREAM-HOOKS.md` (row)
- Test: `geni/src/test/.../GeniHooksTest.kt` for the decision function `GeniHooks.shouldOnboard(configured: Boolean, alreadyShown: Boolean)`: false when not configured or already shown, else true.

- [ ] **Step 1: Failing test** for `shouldOnboard` (three cases). FAIL.
- [ ] **Step 2: Implement** `shouldOnboard` and `startOnboarding` (static `shown` flag set when it starts the login screen with `Intent(activity, GeniLoginActivity)`; returns whether it started). PASS.
- [ ] **Step 3: Hook and inventory row.** Build.
- [x] **Step 4: Emulator check** (2026-11-20: second login after logout opened the existing "V P" tree, list unchanged at 6 entries; fresh install after `pm clear` creates and opens the tree by itself; manual "Create Geni tree" survives rotation; rapid rotation (every 1.5 s) while returning from the browser login lost the automatic creation, the button recovered it; fixed by the retained login state, plan `2026-11-23-geni-onboarding-rotation.md`)
- [x] **Rotation re-check** (2026-11-23, emulator Pixel_8_API_36, sandbox, real rotation every 0.6-1 s with `adb shell cmd window user-rotation lock 0|1`, `settings put system user_rotation` does NOT rotate the emulator; checked with screenshots and `dumpsys activity`): fresh login after `pm clear` with rotation during the browser return and creation gave one tree and one diagram (stack: Trees + one MainActivity), no Create tap; logout + login with rotation opened the existing tree, list unchanged (one entry); account screen with a saved token survives rotation and does not open a tree; manual "Create Geni tree" with rotation during creation gave one tree and one diagram; offline create showed "No connection", the message and button survived rotation, retry online created the tree; app killed and relaunched, saved-token account screen loads without opening a tree. Not checked: rotation exactly inside the profile lookup window (too short to hit by script, covered by the unit tests), logout racing a running creation (unit test only).
- [x] **Build without `geni.properties`** (2026-11-23, separate copy): assembles; fresh start shows "Tap to add a family tree". (fresh install, `adb shell pm clear`): launch shows the Geni account screen; Back shows the Trees list with the add-tree prompt; log in (user) opens the tree diagram; a second login with an existing tree opens it without creating another (check the tree count). Report what was not checked.
- [ ] **Step 5: Commit.**

### Task 3: Docs

- [ ] Roadmap: section "Geni-first onboarding" with these items ticked, deferred list (several Geni trees/managed profiles; hiding upstream new-tree entries), progress-log lines for PRs #15 and #16 and this PR after merge.
- [ ] Commit; push; PR; CodeRabbit loop; merge.
