# Geni sync roadmap

Design: [2026-11-16-geni-sync-design.md](../specs/2026-11-16-geni-sync-design.md).
Each stage gets its own detailed plan with a step checklist; steps are ticked (`- [x]`) as they are done and committed.
The items under each stage are the deliverables; the stage's plan breaks them into tested steps.
See the [progress log](#progress-log) at the end for what landed when.

## 1. API client and login

Plan: [2026-11-16-geni-1-api-login.md](2026-11-16-geni-1-api-login.md)

- [x] Config from `geni.properties` (`geniAppId`, `geniHost`, `geniRedirectUri`); no client secret in the app
- [x] Rate limiter driven by `X-API-Rate-Limit` / `X-API-Rate-Remaining` / `X-API-Rate-Window`
- [x] OAuth mobile flow: authorize URL and redirect parsing (token, denial)
- [x] Encrypted token store; API client with bearer token, 401 and 429 handling
- [x] Login screen and "Geni account" item in the trees menu (hook in `TreesActivity`)
- [x] Manual check on the sandbox: login, cancel, redirect scheme, real token lifetime

### Deferred from the stage 1 review

- [ ] Make OAuth `state` mandatory once it is confirmed that Geni echoes it
- [ ] "Session expired, log in again" message after 401 or expiry; clear an expired token from the store
- [ ] Show `geni_login_failed` and the code instead of the raw Geni error body
- [ ] Handle no browser (`ActivityNotFoundException`) on login
- [ ] Fall back to the profile ID when the name is empty
- [ ] Keep the status message on rotation; avoid a second profile request on rotation
- [ ] Log out also calls `/platform/oauth/invalidate_token`
- [ ] Token store off the main thread; thread-safe `Geni.client` cache; cancellable HTTP calls
- [ ] Lower-case the redirect scheme; escape `geni.properties` values in `buildConfigField`
- [ ] Tests: 429 waits a window, 429 then 429 fails, `X-API-Rate-Remaining: 0` delays the next call
- [ ] Check on the sandbox: token alphabet (`+`), HTTP code for a revoked token

## 2. Read path

Plan: [2026-11-16-geni-2-read-path.md](2026-11-16-geni-2-read-path.md)

- [x] Sandbox exploration: real responses of `profile`, `immediate-family`, `union`; dates and locations format
- [ ] Show Geni profile photos (`mugshot_urls`) in FamilyGem media; upload new photos (`profile-X/add-photo`)
- [x] Room database in `:geni`: Geni tree marker, ID mapping (GEDCOM ID ↔ Geni ID), snapshots
- [x] Mapping Geni → GEDCOM: profile → `INDI`, union → `FAM`, `_GENI_ID`, standard fields, `_GENI_*` for the rest
- [x] Create a Geni tree from the logged-in user's profile (entry in the trees list, root = own profile)
- [x] Lazy load: opening a person fetches `immediate-family` and merges it into the cache (hook in person card or diagram)
- [x] Edge people marked as not fully loaded; opening them triggers a fetch
- [x] Background prefetch within N steps of the root (N = 3, setting), WorkManager
- [x] "Keep offline" action: download a person's branch to a chosen depth
- [x] Measure size and time of a radius-3 prefetch and of saving a large cache JSON

### Deferred from the stage 2 review

- [ ] Read and save links inside the tree lock, or merge flags, so overlapping loads do not reset `fullyLoaded`/`loadedAtMillis`
- [ ] "Keep offline" only for Geni trees and when logged in
- [ ] Partners with a known gender first, so an unknown-gender partner cannot cause two `HUSB`
- [ ] Catch Room errors in the person-open path and in `GeniLoader.load`
- [ ] `NonCancellable` around save and links after the merge block
- [ ] Thread-safe `Geni.client`; `treeExists` on the main thread
- [ ] Shared file lock with upstream saves of `N.json`
- [ ] Create the Geni tree outside the login screen's scope, so leaving the screen cannot leave a half-made tree
- [ ] `refreshData` (generation count) off the main thread for large trees; measure a tree of about 5000 people

## 3. Write path

Plan: [2026-11-17-geni-3-write-path.md](2026-11-17-geni-3-write-path.md)

- [x] Hook in `TreeUtil.saveJson`: signal that a Geni tree was written
- [x] Diff of the whole tree against snapshots, with a hash per record
- [x] Upload queue in Room; job types: update profile, add child/parent/partner (a sibling is a child of the parents' union), update union
- [x] Mapping GEDCOM → Geni for uploads; local-only data (sources, notes, extra events) never uploaded
- [x] People created offline: later jobs refer to them by GEDCOM ID, resolved to the Geni ID when they run
- [x] Queue runner: order, rate limit, retries with back-off (in-process, not WorkManager: upstream cancels all work on save)
- [x] Permanent failures kept and shown to the user (list of failed changes)

### Deferred from the review of PR 1 (Tasks 1–4)

- [ ] Real round-trip test: upload params → simulated Geni profile → `ofSnapshot` equals the local projection
- [x] `FamilyMerger`: clear `verifiedHash` when the snapshot changes (CodeRabbit, PR 1)
- [ ] `parseDate`: reject days that do not exist in the month (31 FEB)
- [ ] Name text after the closing slash ("Ivan /Petrov/ Jr") is invisible to sync

### Deferred from the review of PR 2 (Tasks 5–7)

- [ ] A new person in two families is added through one; the other relation stays local without notice (Geni has no call to link existing profiles): show it to the user
- [x] `Uploader.refreshSnapshot` read-modify-write can overwrite a loader's link write made at the same time (`fullyLoaded`, `loadedAtMillis`)
- [x] A load of a profile added without a snapshot applies every Geni field (`FamilyMerger` with `previous = null`) and can overwrite a local edit not uploaded yet (CodeRabbit, PR 2)
- [ ] A person deleted during the add request whose GEDCOM ID is reused at once gets the new link and tag (CodeRabbit, PR 2)
- [ ] When the tag write after an add fails and the person is renamed, the record is ambiguous and its edits are not uploaded: show it to the user (CodeRabbit, PR 2)

### Found in the sandbox check (Task 10)

- [ ] No check with a 4xx from Geni on the phone (the sandbox tree has no profile the user cannot edit); covered by unit tests only
- [ ] The network watch resumes uploads as soon as Android reports a network; DNS may not be ready yet, so the first try can still back off

### Deferred from the review of stage 4 PR 1

- [ ] A death or divorce cleared on Geni removes the local fact with its notes, sources and media (an emptied DEAT or DIV would still mean dead or divorced); other events and occupation are emptied instead
- [ ] Links without a snapshot made by builds before stage 4 (only a hash of what was sent) keep the fill-empty-fields rule on loads: a field cleared locally after such an add comes back from Geni. New builds store what was sent as the snapshot instead

Moved out of stage 3: photo upload goes with "Show Geni profile photos" (stage 2); delete profile goes with the delete rules (stage 5).

## 4. Refresh and conflicts

Plan: [2026-11-17-geni-4-refresh-conflicts.md](2026-11-17-geni-4-refresh-conflicts.md)

- [x] Refresh on open: fetch current Geni version, update cache and snapshot when nothing is pending
- [x] Daily refresh of radius N on an unmetered network
- [x] Refresh updates only Geni-mapped fields; local-only data is kept
- [x] Conflict check before upload using `updated_at`; paused job when someone else changed the profile
- [x] Conflict screen: both versions, choice per field (based on `share/CompareActivity`)
- [x] Merged profiles (`merged_into`): remap to the surviving ID
- [x] Deleted or hidden profiles: removed from the cache unless they have pending changes; otherwise the user decides

### Deferred from the review of PR 6 (Tasks 8–10)

- [ ] A merged profile answered only under the survivor's ID (the sandbox answers under the requested ID with `merged_into`)
- [ ] Survivor cached by another load between the check and the move: two people with one Geni ID
- [ ] A person kept as local after "deleted on Geni" can be relinked by a later load that returns the old profile
- [ ] Resume a "deleted on Geni" resolution or a profile move interrupted between the tree save and the link writes
- [ ] A deleted profile whose person has only local data (no upload job): offer the keep-or-remove choice too
- [ ] With Auto Save off, a removal or move changes links before the tree is saved; reconcile if the user discards it
- [ ] Daily refresh while the app stays open for days on the same Wi-Fi: add a time-based trigger
- [ ] A Wi-Fi trigger that arrives while the tree's daily pass is still finishing is dropped until the next trigger
- [ ] Daily refresh time kept per tree ID: clear it when a tree is deleted, and do not save it if the root or depth changed during the pass
- [ ] A family emptied by a removal keeps marriage or divorce edits not uploaded yet (compare with its union snapshot)

## 5. Upstream UI rules

Plan: [2026-11-18-geni-5-upstream-ui.md](2026-11-18-geni-5-upstream-ui.md)

- [x] Hide merge, shared-tree import and sharing for Geni trees
- [x] Delete person in a Geni tree: only managed profiles, with explicit confirmation, then `profile-X/delete`; otherwise blocked
- [x] "Remove from this device" action (cache only; in the delete dialog)
- [x] Block unlinking a spouse or child in Geni trees (no API for it)
- [x] Backup restore of a Geni tree: a restored backup is a new local tree (new tree ID), so nothing to reset
- [x] GEDCOM export stays allowed

### Deferred from stage 5

- [ ] A person removed from this device returns as an edge person when a relative is loaded (a removed-marker would need a Room migration)
- [ ] "Remove from this device" and the not-managed / offline dialogs are covered by unit tests only, not checked on the phone
- [ ] An edit of a profile made after "Delete on Geni" was queued is not dropped from the queue (only an update already waiting is)
- [ ] `GeniGlue` reads the Geni tree IDs with a blocking Room read on the first hook (main thread), and a failed read leaves hooks treating Geni trees as local until the next hook; use an explicit unknown state
- [x] A delete queued under one Geni account can be sent under another after a login change: record the confirming account on the job
- [ ] A family refresh started before a delete can restore the profile after the local cleanup: keep a record of confirmed deletions for the loader
- [ ] Local removal after Geni confirmed a delete is best effort: a failure leaves the person on the device with the link dropped; persist a cleanup-only phase
- [ ] With Auto Save off, "Remove from this device" drops links and jobs before the tree is saved; revert brings the person back unlinked
- [ ] `onTreeDeleted` clears Room rows asynchronously: a tree created with a reused ID before it runs loses its rows
- [ ] A concurrent reload after a GEDCOM ID edit can have its new link deleted by the removal (delete the link only if it is still the one read)
- [ ] A removal with Auto Save on does not check that the file write succeeded before dropping links
- [ ] The save hook of a removal can start the worker before a queued delete on Geni is cancelled
- [ ] `enqueueDelete` and the removal of the waiting edit are not one transaction
- [ ] Backup restore and GEDCOM export of a Geni tree not re-checked on the phone (by design they touch no Geni data)

## 6. Photos

Plan: [2026-11-18-geni-6-photos.md](2026-11-18-geni-6-photos.md). Design: [2026-11-18-geni-photos-design.md](../specs/2026-11-18-geni-photos-design.md). Replaces the stage 2 photo item.

- [x] PR 1: photo API client (list, get, upload, delete, download) and sandbox check
- [x] PR 2: main photo read: shown on card and diagram, kept current on refresh (phone-checked on sandbox)
- [x] PR 3: album read: browse, on-demand download, "Keep offline" option (phone-checked on sandbox)
- [x] PR 4: upload: add photo (first one becomes main), offline queue and failure list (scaling, upload and first-becomes-main checked against the sandbox from the phone; the add-photo screens were not driven by hand)
- [x] PR 5: delete: "Delete" in the media tab offers removal from the device or, on managed profiles, deletion on Geni; photos deleted on Geni leave the device when the album is read (phone-checked on sandbox)

### Deferred from the review of photos PR 1

- [ ] `PhotoDownloader`: the derived client inherits the supplied client's cache and cookie jar; set `cache(null)` and `CookieJar.NO_COOKIES` (with a test that no `Cookie` header is sent)
- [ ] `PhotoDownloader` accepts any https host; consider allowing only Geni photo hosts
- [x] Check downloaded bytes are an image (magic bytes or decode) before creating the media entry (done in PR 2)

### Deferred from the review of photos PR 2

- [ ] Decode a downloaded photo fully (not only its first bytes) before it replaces the current one; a truncated JPEG now passes (needs Android `BitmapFactory`; maybe compare with `Content-Length` in `PhotoDownloader` instead)
- [ ] With auto-save off, a photo change is only in memory: the old file is deleted and the link saved before the tree is saved, so closing without saving leaves a media entry with no file; tie the link and file retirement to the deferred save, and check the real media attachment, not only the link, before skipping a sync
- [ ] Photos of a whole family download one after another on load; consider a background pass with a second refresh per photo

### Deferred from photos PR 3

- [ ] "Keep offline" with photos costs one extra API call per person; watch the rate limit on large branches
- [ ] Album repair racing a main-photo change has no per-profile lock; main status is read just before saving, which narrows the window
- [ ] Album thumbnails load through Glide over plain https without auth; check they still work if Geni signs thumbnail URLs per request

### Deferred from photos PR 4

- [x] Crop right after adding a photo uploaded the uncropped file; new photo jobs now wait for the crop decision (PR #23)
- [ ] `PhotoScaler`: huge images can decode to about 50 MB (an out-of-memory is now caught as a failed upload); PNG transparency turns black after re-encoding to JPEG; only EXIF orientation 6 is tested
- [ ] Switching the photo upload on re-plans only trees saved in this process; after a cold start the photos wait for the next save of each tree
- [ ] Local photos of Geni people from earlier builds upload on the first save after the update (the switch defaults to on), with no notice
- [ ] A photo whose tag edit failed after upload can get a second local media on the next photo sync (found by tag only)
- [x] A pending photo job of an ambiguous person (tag lost and snapshot differs) is kept and could be sent to the stored profile; drop PENDING photo jobs of ambiguous people (keep FAILED and DISMISSED) so they are planned again after the person is resolved
- [ ] Old photo jobs keep their old label; the pending count may include photo jobs while the switch is off

### Deferred from photos PR 5

- [ ] An empty (successful) album read removes every unedited downloaded non-main photo of the profile; consider a guard if Geni can return an empty list for hidden photos
- [x] `AlbumLoader` can download again a photo whose `delphoto:` job is still pending (short window, heals on the next album read); skip photo IDs with a pending delete job (PR #25)
- [ ] `reconcileAlbum` racing a just finished upload could drop the new photo's local media (re-downloadable from the album)
- [x] The main photo "Delete on Geni" only queues the job; the photo leaves the device at the next refresh, so it stays visible after the confirmation; refresh the profile when the job is done
- [ ] Photo media with local data (title or note) that was deleted on Geni stays with a stale link and is not reported to the user
- [ ] The photo dialog is built before the removal runs: if `PhotoSync.sync` promotes the photo to main in between, local removal can still take the current main photo off the device (check main status inside `removeLocal`)
- [ ] `PhotoSync.deleteFileIfUnused`: a coroutine cancelled after the link is deleted but before the file delete leaves an orphan file; run the delete in `NonCancellable`
- [x] A profile load that fetched before a "Delete on Geni" finished can still name the deleted photo as main, and `PhotoSync.sync` would download and attach it again; a process-lifetime `PhotoDeletionGuard` records confirmed deletions and serializes attachment per tree (also covers the album download)
- [ ] The deletion record is in memory only: a stale Geni response after a process restart can still bring a deleted photo back (needs a persisted record and a retention policy)

## 7. Geni-first onboarding

Plan: `docs/superpowers/plans/2026-11-20-geni-onboarding.md`. Decision (2026-11-20): the app is for Geni only.

- [x] Empty trees list opens "Log in with Geni" instead of the "add a tree" prompt (one hook in `TreesActivity`, once per process; Back shows the normal list)
- [x] A fresh login opens the account's tree, creating it first if there is none (no button press); on failure the account screen keeps the "Create tree" button and the message
- [x] `GeniLoader.createTree(rootGeniId)` and the developer tool `GeniImportTool` (androidTest) that copies a local tree into the sandbox account for checks
- [x] Second login with the tree already there opens it, no second tree (checked 2026-11-20 and again 2026-11-23)
- [x] Rotation during the browser return, profile lookup and creation keeps one tree and one diagram: login state retained in `GeniLoginViewModel` (checked 2026-11-23 on the sandbox emulator)
- [x] Build without `geni.properties` assembles and keeps the empty-tree prompt (checked 2026-11-23)

### Deferred from Geni-first onboarding

- [x] Several Geni trees: no, by design. One tree per installation, owned by the account that made it (`ownerGeniId`, separate from the root that can move on a merge); another account is refused until **Delete tree**. Managed profiles stay out of scope
- [x] Hide the upstream new-tree entries: Add tree screen, GEDCOM and share-link intents, shared-tree banner and install referrer, backup Recover tab are gone; the empty trees list offers the Geni account button
- [ ] The "Tap to add a family tree" bubble does not show after Back from the login on a fresh install (the first-start branch waits for the Play install referrer, which Treekin does not use)

## 8. Sync backlog

One live screen, **Sync backlog** in the trees menu, for every Geni tree: what is being sent, downloaded, held back and what needs the user. Retry, dismiss and conflict choices stay in **Geni account**.

- [x] Menu item and `GeniSyncBacklogActivity` (non-exported, toolbar and Up like the account screen); opening it reads local state only, no sync and no network
- [x] `SyncStore.observeJobs()` (Room `Flow` on the `job` table); `SyncQueue.status` exposes the exact job and version in flight, so a newer version planned meanwhile shows as waiting, and a stored SENDING row with no live attempt shows as interrupted until the recovery marks it failed
- [x] Waiting reasons from the code that decides them: back-off time and login stop from `GeniSync` (`Geni.uploadWaits`), photos setting off, crop hold and deferred person check from `SyncQueue.waitReason`; otherwise "Queued"
- [x] Process-local `DownloadFeed` (`Geni.downloads`): initial prefetch, Keep offline and daily refresh queued until they get the tree's mutex; person refresh and root load in `GeniLoader`; album listing and Download all in `AlbumLoader`; actual file transfers in `PhotoSync`. Nested work is a stage of its parent, and a task leaves when its coroutine ends, fails or is cancelled
- [x] Sections In progress / Waiting / Needs attention, operation, tree, target and status; indeterminate progress when the total is unknown; empty, loading and unreadable states; no params, tokens or photo URLs shown
- [x] Checked on the emulator (sandbox): empty state; Keep offline active with its stage and a second pass queued; rotation during the pass shows the same single task (one pass ran); rows clear when work ends; offline profile edit waits with the retry time and leaves the list after reconnecting

### Not checked by hand for the sync backlog

- [ ] Crop hold and photo upload rows (the emulator's file picker crashes; needs the phone)
- [ ] Interrupted photo add after stopping the process, then retry with the duplicate warning from Geni account
- [ ] Failed and conflicting jobs under Needs attention, dismiss or resolve them in Geni account and watch the backlog update
- [ ] Deleting the tree removes its rows; reopening signed out
- [ ] Direct person refresh, daily refresh, album listing, a single photo and Download all one by one on a device (unit tests cover the loader side)
- [ ] A download still queued or running when its tree is deleted can show under a new tree that reuses the ID; bind rows to a tree incarnation, not the numeric ID (CodeRabbit security summary, PR #30)

## 9. Home screen: recent events

A **Home** screen opens with the tree: upcoming birthdays (next 14 days) and recent birthdays and profile changes (today and the 90 days before), built from the people on this device. The diagram stays in the drawer. Geni's API has no feed, birthdays or per-profile history endpoint (checked 2026-11-25), so the feed is local.

- [x] `HomeFragment` (fork code in `app.familygem.geni.home`) in the drawer above Diagram; `furnishMenu()` finds the counted items by ID; rotation and Back (Home → Diagram → Back) keep Home
- [x] Ordinary opens go to Home: startup auto-load, a tap on the tree, and `AppTreeHost.openTree` after the account flow (`HomeFragment.OPEN` extra). Birthday notification taps, `Choice.*` pickers and every other MainActivity start keep the diagram or their screen
- [x] `RecentEvents` feed builder (date and zone as inputs): Notifier's birthday rule (alive, single D M Y date), the tree's lifespan per occurrence, February 29 on February 28, year rollover; the day of birth itself is not a birthday
- [x] Wedding anniversaries (exact MARR date, two living partners, no divorce or annulment; the row opens the family) and memorial days (exact DEAT date), both up to the tree's lifespan in years; upcoming and recent like birthdays, with heart and flower icons
- [x] Profile added and removed from the sync's `feed_event` table (Room v7, rows older than 100 days pruned): **Profile added** from Geni's `created_at` (now requested with every profile) on each load, or the moment this app creates the profile, shown only while the person is in the tree under that profile; **Deleted or merged on Geni** (refresh, or the conflict choice not to keep), **Deleted on Geni** (by the user, after Geni confirms) and **Removed from this device**, with the name kept since the person is gone; removal rows open nothing
- [x] Profile changes from each person's `CHAN` (its stored zone, else the device one), one per person, merged into the day groups; malformed and future timestamps skipped. No Geni request, no tree write
- [x] Rows show the person's main photo, or a silhouette by sex, a cake or pencil icon by event kind, and open the profile; refreshed on every return; empty state; English and Russian strings with age plurals (a **Birthday photos** link to the Media tab was tried and dropped)
- [x] Checked on the emulator: **Profile added** rows from the sandbox's `created_at` after a signed-in load; tree tap and offline auto-load open Home; edit → back shows the change once under Today; rotation; Home → Diagram → Back; Russian dates and plurals; simulated notification tap opens Diagram on the person; Link existing person opens the Persons picker; `SingleTreeTest` passes

### Home on every launch, Diagram renamed Tree

- [x] Every launch opens Home, whatever the old **Load tree at startup** setting said (the switch is gone from Settings): the last opened tree if it is still saved, else the sole saved tree (`StartupTree.pick`); the tree already in memory is reused, so unsaved edits survive; a launcher intent reaching the open trees list (`singleTask`) opens Home too, while Back from Home leaves the list open
- [x] No trees list at launch: `LauncherActivity` loads the tree itself (`StartupTree.launch`) and starts Home alone; `TreesActivity`'s startup block and `onNewIntent` are upstream's again
- [x] The trees list is hidden: Back from the last screen of a tree closes the app (`TreeMenu.leaveTree`, pickers still return to their caller); the drawer gets **Geni account**, **Sync backlog** and **Settings**; the Tree settings toolbar gets **Info**, **Export GEDCOM** and **Save ZIP backup** (expert) and **Delete tree** (then the empty list opens the Geni login). Pending edits are saved before an export and the open tree is reloaded after it. The list still opens when there is no tree to show (fresh install, after a crash, unreadable file), and Find errors stays there (bound to `TreesActivity`)
- [x] After a crash the next launch stays on the trees list once (`StartupTree.skipNextLoad` from `Global.exceptionHandler`, replacing upstream's switch-off of `loadTree`)
- [x] Home is MainActivity's default; callers that show a person in Tree pass `HomeFragment.OPEN_TREE` (`treeIntent`): birthday notification taps and `U.finishParentSelection` from a profile or family. `Choice.*` pickers unchanged
- [x] Visible "Diagram" is **Tree** (each locale's own upstream word for tree) and its display settings are **Tree view settings**, generated by `scripts/gen_geni_strings.py`; resource IDs and the stored `diagram` settings key unchanged
- [x] The drawer's **Trees** shortcut is gone
- [x] Checked on the emulator, offline: legacy `loadTree` false and true, a sole tree with an invalid last-opened ID, empty install (onboarding), unreadable tree file (list usable), crash recovery (`am crash`), drawer in normal and expert mode, English and Russian names, saved display settings kept, PNG export, Tree from a profile and from a simulated notification tap, Link existing person picker, rotation in Tree then Back to Home, Back from Home to the list

- [ ] Export GEDCOM writes an empty file and shows a NullPointerException (`String.length()` in `GedcomWriter`) for the emulator's Geni tree, from the old trees list as well as from Tree settings: find the null value the Geni mapper leaves (found 2026-11-25)

### Not checked by hand for Home

- [ ] Fresh install → Geni onboarding → setup opens one Home screen (needs a sandbox login; `GeniLoginViewModelTest` passes)
- [x] A missing or unreadable tree file leaves the user at the tree list (checked 2026-11-25 with the startup change)
- [ ] **Deleted or merged on Geni** and **Deleted on Geni** rows on the device (unit tests only); **Removed from this device** was checked
- [ ] Geni `updated_at` of cached profiles as a second source of "Profile updated" (it would show edits made on Geni by others)

## 10. Profile editor fields

The person editor of a Geni tree offers the fields Geni's profile API can write and read back, and only those event choices; local trees keep upstream's editor. Plan: [2026-11-26-geni-profile-edit-fields.md](2026-11-26-geni-profile-edit-fields.md).

- [x] Facts → Event offers Birth, Baptism (BAPM), Occupation, Death and Burial; an existing synced fact opens instead of a second one; the event screen offers only synced fields; sex choices male/female, Unknown shown only
- [x] Modify: Birth surname, Suffix, Also known as, Baptism, Cause of death, Burial, Occupation, About me; only changed fields are written, in place; a new date Geni cannot store keeps the form open; Death on a living person opens Modify with Dead switched on in the form only
- [x] First, middle and display names round-trip apart (`_GENI_FIRST_NAME`, `_GENI_MIDDLE_NAME`, `_GENI_DISPLAY_NAME`); records from before inherit them from the snapshot; the main name row and the header open Modify
- [x] Name title (`title`, top-level on v1, checked on the sandbox) apart from the GEDCOM `TITL` fact
- [x] Checked on the emulator against the sandbox: existing profile edit, unchanged save without a job, offline new relative sent once, legacy Christening and Residence kept

### Deferred from the profile editor fields

- [ ] `ProfileFields` gained fields, so its hash changed: a link without a snapshot but with a verified hash looks edited once after the upgrade (Geni then only fills empty fields; a gone profile is kept rather than removed)
- [ ] The birth name keeps the old given part when the first or middle name changes locally (until Geni's next name change)

## 11. Full editing (everything the API can write)

Roadmap: [2026-11-26-geni-full-editing-roadmap.md](2026-11-26-geni-full-editing-roadmap.md). Each stage gets its own plan before work starts.

- [x] Exact dates and places: date ranges (before, after, between), structured locations (PRs #35, #36); event descriptions are not synced, Geni drops them
- [x] Privacy and unknown sex (`public`, `gender`; PR #37). Curator flags (`locked`, `merge_note`) stay hidden and read-only until a curator account can be checked on the sandbox
- [ ] Names in other languages (`names` per locale)
- [ ] Read-only Geni data on the card (email, phones, current residence, custom events, managers)
- [ ] Adopted and foster children (`relationship_modifier`, `adopted_children`, `foster_children`)
- [ ] Photo details, tags and main photo (`photo/update`, `tag`, `untag`, `add-mugshot` with `photo_id`, albums)
- [x] Documents ([#44](https://github.com/cheack/Treekin/pull/44), [plan](2026-11-28-geni-documents.md))
- [x] Videos ([plan](2026-11-28-geni-videos.md))
- [ ] Merge duplicates on Geni, follow, projects

## 12. Modern UI (own screens, one at a time)

Roadmap: [2026-11-26-modern-ui-roadmap.md](2026-11-26-modern-ui-roadmap.md). Keep the fork; rebuild the most-used screens in Compose with Material 3 Expressive; designs made and approved in Claude Design first. The code starts after section 11 (full editing); the design (stage 0) runs now and already includes the future fields.

- [ ] 0. Design direction: research brief, decisions, design system and screen mockups (Claude Design artifacts), approved — direction A chosen; core and app screens drawn with all future fields, dark and tablet (2026-11-26); design system still open
- [ ] 1. Foundation: Compose, `TreekinTheme` from the tokens, shared components, screenshot tests
- [ ] 2. Home in Compose
- [ ] 3. Profile card
- [ ] 4. Edit profile (takes over the full-editing fields)
- [ ] 5. Family and adding relatives
- [ ] 6. People and search
- [ ] 7. App shell (bottom bar), login, account, sync backlog, settings
- [ ] 8. Tree view restyle

## Other tasks

- [x] Register a Geni sandbox app and fill `geni.properties` (`geniAppId`, `geniHost=sandbox.geni.com`)
- [x] Own app ID `net.cheack.treekin` and name "Treekin" (in `app/geni.gradle` and `app/src/geni/res`)
- [x] Redirect scheme `net.cheack.treekin://authorize` instead of `app.familygem.geni://` (also change the Callback URL in the Geni app)
- [x] Rename the GitHub repository to `cheack/Treekin`
- [x] Replace "Family Gem" in other strings (example tree, Premium): generated overlay `app/src/geni/res/values*/strings.xml` via `scripts/gen_geni_strings.py` (the about screen shows `app_name`; its `www.familygem.app` link and the Premium purchase itself are still upstream's)
- [x] `NumberPicker` divider fix from Sternbach-Software/GeniForAndroid: not needed, upstream hides the divider through the theme (`AppTheme.Wheel.TransparentLines`) and no code uses reflection on it
- [x] CI: weekly trial merge of `upstream/master`, build and tests (`.github/workflows/upstream-check.yml`, Mondays 06:00 UTC and manual dispatch; a failed run is the alert)
- [x] PR merge policy: merge commits, not squash (`gh pr merge N --repo cheack/Treekin --merge`), so a merged branch shows as merged in git; the repo deletes the branch after merge (`delete_branch_on_merge`). PRs #35-#39 were squashed before this rule
- [x] Settings → Appearance: Original (Family Gem look, follows the device theme), Geni Light (the default), Geni Dark (own palette, light or dark always, horizontal photo cards and straight connectors); applies at once and survives a restart. Checked on the emulator in device light and dark mode; PNG/PDF export of each mode not opened by hand (the emulator's file picker crashes).

### Geni branding audit (2026-11-20, against https://www.geni.com/platform/developer/help/branding and `/terms`)

- [x] App name has no "Geni"; the cards use their own look, no Geni logo, name or colours
- [x] Login button reads "Log in with Geni" (ru "Войти через Geni")
- [ ] Disclaimer on the About screen: "not endorsed, operated, or sponsored by Geni.com" (after PR 15 lands: put a text view where the Premium advertisement was removed)
- [ ] Geni logo next to the login form, linked to geni.com, at least 100x40 px, unaltered, less prominent than the app's own logo (decide first: the logo files are Geni's trademarked assets, licensed only for branding compliance; check that shipping them in a GPL-3.0 repo is acceptable)
- [ ] Own privacy policy and user agreement, linked where users open the app (Terms XI.B) and before obtaining user data; link from login and About (policy `docs/PRIVACY.md` linked from About and the Geni account screen; user agreement still open)
- [x] Check that the launcher icon is "sufficiently different" from Geni's logo (new own tree icon)

### Deferred from the review of the cards branch

- [ ] RTL: the stripe corner radii in `person_border_*` (overlay) do not flip with `gravity="start"`; add `drawable-ldrtl` variants or drop the radii
- [ ] The overlay `person_border_*` drawables also restyle minicards, small person rows, the person list and the merge screens (all keep at least 5dp padding); check them on the phone and decide whether they should keep the stripe
- [ ] Not checked on the phone: light theme, a right-to-left locale, a 60-character name, PNG/PDF export of the diagram

## Progress log

One line per merged PR or finished stage, newest first: what now works for the user, and the PR.

- 2026-11-28: A Geni profile has a **Geni documents** menu: the documents Geni lists, download on demand and open in a viewer, upload of a PDF or image up to 5 MB (queued offline, restart-safe, sent once), edit title, description and date, tag, untag, and a delete dialog with remove from this device, untag on Geni and (managed profiles, confirmed) delete on Geni (full-editing stage 7, PR 1). Text documents are listed without a file: the API does not return their text. Video upload works in the sandbox and follows in PR 2. [#44](https://github.com/cheack/Treekin/pull/44)
- 2026-11-28: Photos of a Geni profile can be edited from the Media menu: title, description and date (single dates; Geni stores no range on a photo), set as main photo, tag another person, untag (full-editing stage 6). Edits queue offline, a conflict or refusal keeps the draft in the sync backlog, both profiles of a shared photo show the same details, and nothing is downloaded again. The API has no album route, so album create and rename stay on the site. [#42](https://github.com/cheack/Treekin/pull/42), [#43](https://github.com/cheack/Treekin/pull/43)
- 2026-11-28: A child or sibling can be added to a Geni tree as Birth, Adopted or Foster, and the lineage Geni returns shows on the Family screen and the Relatives tab, offline too (full-editing stage 5). The API cannot convert an existing child, so that stays on the site; `profile/add-sibling` cannot pick a family, so siblings go through the child routes. [#41](https://github.com/cheack/Treekin/pull/41)
- 2026-11-28: Facts of a Geni profile has a read-only "On Geni" section from the saved snapshot (relationship, residence, managers, claimed, curator, big-tree and merge flags, other timeline events, Open on Geni), and a new relative can be given an optional email that goes with the one add (full-editing stage 4). Sandbox: `events` is the whole family timeline, `email` and `phone_numbers` are never returned. [#39](https://github.com/cheack/Treekin/pull/39)
- 2026-11-28: Names in other languages sync with Geni: Modify has a language list (add from Geni's 138 locales, edit, remove) for people already saved; each language merges and conflicts on its own, labeled with the language; Facts rows of a language open Modify and cannot be moved or deleted. Sandbox: all eight name fields round-trip per locale, clearing every field removes the locale, an unsupported code fails the whole request (full-editing stage 3). [#38](https://github.com/cheack/Treekin/pull/38)
- 2026-11-28: Profiles can be made public or private and the sex can be unknown, synced with Geni: Modify has a Privacy switch (works for deceased people; a living person is always private on Geni, so it shows off and explained) and the sex chooser offers Unknown again, which clears the sex on Geni. Legacy "unknown" placeholders never write. Curator fields (`locked`, merge note) stay hidden: the sandbox never returns them and gives no way to tell a curator account (full-editing stage 2). [#37](https://github.com/cheack/Treekin/pull/37)
- 2026-11-28: Places keep their parts apart and sync with Geni: place name, city, county, state, country, three street lines and coordinates, for birth, baptism, death, burial, marriage and divorce. Editing a place name in the person editor changes only the place name (city and country stay); each part has its own row in the event screen and is saved or cleared alone; conflicts show street and coordinates. Changes queued before the upgrade no longer clear parts they never touched. The sandbox showed Geni stores coordinates only together with a place or address (full-editing stage 1 finished). [#36](https://github.com/cheack/Treekin/pull/36)
- 2026-11-27: Date ranges sync with Geni in both directions: "before 1900", "after 1900", "between 1840 and 1845" (also `FROM … TO …`), each end with its own "about", in the person editor and in the event screens (marriage and divorce included); a new date Geni cannot store is refused with the usual error. Ranges saved by older versions as a single date are not overwritten. Checked on the sandbox, which also showed that Geni keeps the parts of a place independent and drops event descriptions (full-editing stage 1, first half; places come next). [#35](https://github.com/cheack/Treekin/pull/35)
- 2026-11-26: In a Geni tree the person editor offers the fields Geni stores (first, middle and last name, birth surname, suffix, also known as, name title, display name, baptism, cause of death, burial, occupation, about me) and Facts → Event offers only Birth, Baptism, Occupation, Death and Burial; only changed fields are sent, older records make no extra write, and facts with local details are kept on a clear (PR [#34](https://github.com/cheack/Treekin/pull/34)).
- 2026-11-25: The trees list is hidden: the app opens straight on Home with a fade and Back from it closes the app; the drawer has Geni account, Sync backlog and Settings, and Tree settings has Info, Export GEDCOM and ZIP backup (expert) and Delete tree, with edits blocked during an export (PR [#33](https://github.com/cheack/Treekin/pull/33)).
- 2026-11-25: Every launch opens the saved tree on **Home** (the startup switch is gone; after a crash the list shows once); the diagram is called **Tree** in all shipped languages, with **Tree view settings**; the drawer has no Trees shortcut, Back from the tree leads to the trees list (PR [#32](https://github.com/cheack/Treekin/pull/32)).
- 2026-11-25: **Home screen** opens with the tree: upcoming birthdays, wedding anniversaries and memorial days, and recent profile updates, profiles added on Geni and people removed, with photos and icons, all from data on the device (PR [#31](https://github.com/cheack/Treekin/pull/31)).
- 2026-11-25: **Sync backlog** in the trees menu shows live what is sent to Geni, downloaded, waiting (with the reason) and what needs attention, also offline or signed out (PR [#30](https://github.com/cheack/Treekin/pull/30)).
- 2026-11-24: Treekin keeps one tree: the first Geni login creates it, later logins of the same account reopen it (offline too), another account is refused until the tree is deleted, and there is no other way to make a tree (no Add tree, GEDCOM or share-link import, backup restore) (PR [#29](https://github.com/cheack/Treekin/pull/29)).
- 2026-11-24: Settings → Appearance with Original, Geni Light (the default) and Geni Dark: the whole app and the family tree diagram change at once, and the choice survives a restart; Geni cards have gradients, framed photos or silhouettes, and a mourning band (PR [#28](https://github.com/cheack/Treekin/pull/28)).
- 2026-11-24: After a photo is deleted on Geni, an older profile response or a download already running no longer brings it back or replaces the new main photo, in the profile loader and the album download alike; this holds until the app restarts (PR [#27](https://github.com/cheack/Treekin/pull/27)).
- 2026-11-24: When a "Delete on Geni" of a main photo completes (also after reconnecting), the photo leaves the open profile's header and Media tab without reopening it, and a replacement main photo from Geni is shown; a photo with your title or note stays as your own (PR [#26](https://github.com/cheack/Treekin/pull/26)).
- 2026-11-24: A photo whose "Delete on Geni" is still queued is no longer downloaded again from the Geni album by a tap, "Download all" or "Keep offline"; failed or dismissed deletes and other trees do not block it (PR [#25](https://github.com/cheack/Treekin/pull/25)).
- 2026-11-24: A queued photo goes to Geni only while the saved tree and the link still match its owner to the profile on the job; an unclear match drops the waiting photo and a later save queues it again (PR [#24](https://github.com/cheack/Treekin/pull/24)).
- 2026-11-24: A newly added photo waits for the crop prompt and editor before it goes to Geni, so Geni gets the cropped image, or the original if you decline or cancel (PR [#23](https://github.com/cheack/Treekin/pull/23)).
- 2026-11-24: Photos uploaded to Geni are always re-encoded as fresh JPEGs, so GPS and other embedded metadata of the source file are no longer sent; the local original is untouched (PR [#22](https://github.com/cheack/Treekin/pull/22)).
- 2026-11-23: A queued "Delete on Geni" runs only under the Geni account that confirmed it; after signing in to another account it shows a failed change telling the user to sign back in and retry, and older queued deletes need a fresh confirmation; the person stays on the device until Geni confirms (PR [#21](https://github.com/cheack/Treekin/pull/21)).
- 2026-11-23: The Geni account screen has a Privacy policy link under Log in with Geni, also shown signed in and in unconfigured builds; a toast appears when no browser can open it; the policy opens through one shared `GeniPrivacyPolicy` (PR [#20](https://github.com/cheack/Treekin/pull/20)).
- 2026-11-23: Rotating the device during the Geni login return, tree creation or opening no longer loses the automatic open or makes a second tree or diagram; login state is kept in `GeniLoginViewModel` (PR [#19](https://github.com/cheack/Treekin/pull/19)).
- 2026-11-20: About screen shows a "not affiliated with Geni/MyHeritage" disclaimer and a privacy policy link; new tree launcher icon; `docs/PRIVACY.md` added (PR [#18](https://github.com/cheack/Treekin/pull/18)).
- 2026-11-20: Geni-first onboarding (PR [#17](https://github.com/cheack/Treekin/pull/17)): an empty trees list opens the Geni login; a fresh login creates and opens the account's tree; unsaved edits and the edit lock survive opening a tree after login; the import tool can upload photos and refuses Geni trees.
- 2026-11-20: Horizontal person cards (PR [#16](https://github.com/cheack/Treekin/pull/16)): diagram cards are all the same size, photo on the left, a coloured sex stripe and tint, straight elbow connection lines instead of curves; the login button reads "Log in with Geni"; a branding audit is in the roadmap.
- 2026-11-20: Premium advertisement hidden (PR [#15](https://github.com/cheack/Treekin/pull/15)): the About screen no longer offers FamilyGem Premium.
- 2026-11-20: Other tasks (PR [#14](https://github.com/cheack/Treekin/pull/14)): the example tree and the Premium messages say "Treekin" in every language; a weekly CI job trial-merges the original FamilyGem, builds and tests (fails when attention is needed). The NumberPicker divider needed no fix.
- 2026-11-20: Geni photos, part 5 (PR [#13](https://github.com/cheack/Treekin/pull/13)): Delete in a person's media tab on a Geni photo offers "Remove from this device" (the photo can be downloaded again from the album) or, on profiles you manage, "Delete on Geni" after a second confirmation (offline-safe, waits in the queue); the main Geni photo shows an explanation instead; photos deleted on Geni leave the device when the album is read. Stage 6 done.
- 2026-11-20: Geni photos, part 3 (PR [#11](https://github.com/cheack/Treekin/pull/11)): the person menu has "Geni photos": the album with thumbnails, tap a photo to download it or "Download all"; "Keep offline" can also fetch all album photos; when the main photo changes on Geni the old one stays on the device as an album photo.
- 2026-11-20: Geni photos, part 4 (PR [#12](https://github.com/cheack/Treekin/pull/12)): a photo added to a person of a Geni tree is uploaded to Geni (scaled to 2048 px, rotated upright), waits offline, shows failures in the account list; the first photo becomes the main one on Geni, later ones go to the album. The account screen has a switch "Send new photos to Geni" (on by default) to keep new photos on the device.
- 2026-11-19: Geni photos, part 2 (PR [#9](https://github.com/cheack/Treekin/pull/9)): the main Geni photo of each loaded person now downloads to the device and shows on the card and in the person's media; an unchanged photo is not downloaded again, a changed one replaces the old, one removed on Geni leaves the device. Also (PR [#10](https://github.com/cheack/Treekin/pull/10)): the `_GENI_ID` tag on the card can no longer be edited or deleted.
- 2026-11-18: Geni photos, part 1 (PR [#8](https://github.com/cheack/Treekin/pull/8)): the app can list, read, upload and delete Geni photos and download photo files safely (no token sent to photo hosts, size cap, cancellable). Nothing is visible to the user yet; the main photo comes next.
- 2026-11-18: UI rules for Geni trees (PR [#7](https://github.com/cheack/Treekin/pull/7)): sharing, merging and comparing are hidden for the Geni tree; unlinking a relative is refused with an explanation; deleting a person offers "Remove from this device" and, for profiles the user manages, "Delete on Geni" after a second confirmation; the user's own profile cannot be removed. Stage 5 done.

- 2026-11-18: Refresh and conflicts, part 3 (PR [#6](https://github.com/cheack/Treekin/pull/6)): Geni trees refresh daily on Wi-Fi and an open card re-checks Geni after an hour; profiles deleted on Geni leave the device unless edited locally (then the user chooses), merged profiles move to the survivor. Stage 4 done.
- 2026-11-17: Refresh and conflicts, part 2 (PR [#5](https://github.com/cheack/Treekin/pull/5)): the Geni account screen lists conflicts; for each field the user keeps their value or takes Geni's, and only kept values are uploaded.
- 2026-11-17: Refresh and conflicts, part 1 (PR [#4](https://github.com/cheack/Treekin/pull/4)): a load from Geni no longer overwrites an edit waiting for upload, and an upload no longer overwrites what someone changed on Geni meanwhile (other fields are merged, the same field stops as a conflict).
- 2026-11-17: Write path, part 3 (PR [#3](https://github.com/cheack/Treekin/pull/3)): edits of a Geni tree are uploaded after each save, offline changes wait for the network, and the Geni account screen shows waiting and failed changes. Stage 3 done.
- 2026-11-17: Write path, part 2 (PR [#2](https://github.com/cheack/Treekin/pull/2)): saved changes become upload jobs (updates of profiles and unions, new children, parents and partners), sent to Geni in order with retries, never adding a person twice. Not wired into the app yet.
- 2026-11-17: Write path, part 1 (PR [#1](https://github.com/cheack/Treekin/pull/1)): edits are compared with the Geni version field by field and turned into Geni update parameters; an upload queue in the sync database. Nothing is uploaded yet.
- 2026-11-17: The app is called Treekin (`net.cheack.treekin`), installs next to Family Gem; the repository is `cheack/Treekin`; CodeRabbit reviews PRs.
- 2026-11-16: Stage 2, read path: a Geni tree is created from the user's own profile; opening a person loads their relatives; relatives within 3 steps are prefetched in background; "Keep offline" downloads a branch. Checked on the sandbox and a Pixel 6.
- 2026-11-16: Stage 1: login to Geni with the mobile OAuth flow, encrypted token, API client with rate limiting.
