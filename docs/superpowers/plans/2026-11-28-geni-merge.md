# Merge on Geni (full-editing stage 8, PR 1)

**Goal:** A duplicate found in a Geni tree can be merged on Geni from the profile menu; the tree then shows the survivor. Following and projects stay out of this PR (the roadmap leaves them for separate requests).

## Sandbox contract (2026-11-28, signed-in sandbox build, disposable profiles 36582 / 36583)

| Check | Result |
|---|---|
| `POST profile-A/merge/profile-B`, both managed by the account | `{"result":"profile-36583 merged into profile-36582 with conflicts"}` (fixture `profile-merge-response.json`). The text only says what happened; the app never parses it |
| Read-back of B right after | `deleted: true`, `merged_into` the URL of A (fixture `profile-merge-readback.json`, batch read of both). A is `deleted: false`; its `unions` gained B's union |
| `immediate-family` of the merged-away B | Still answers, with B as focus, so the loader's gone-profile path works for it |
| Pending request: `profile-36582/merge/profile-36587`, the second profile belongs to another account (private, not managed by this one) | `{"result":"profile-36587 requested for a merge into profile-36582"}`. Both profiles stay alive. Reading with `requested_merges` in the field list: **the survivor** lists `"requested_merges":["https://api.sandbox.geni.com/profile-36587"]` and `merge_pending: true` (the other profile only has `merge_pending: true`). Fixture `profile-merge-requested.json`. Repeating the same POST answers the same text and adds no second entry |

Sandbox leftovers: 36582 (MergeA Probe, survivor), 36583 merged into it, 36584 / 36585 (MergeC / MergeD Dup, not merged), pending request 36587 into 36582 (profile of the second account, guid 1174415).

## Design

- `GeniClient.mergeProfiles(survivor, absorbed, confirmedBy)`: both must be `profile-N` and different; pinned to one login and the account that confirmed (the tree's `ownerGeniId`) exactly like `deleteProfile`; one POST.
- `ProfileMerger` (sync): `merge` sends the POST, `reconcile` reads the absorbed profile back and only a `deleted` profile whose `merged_into` is the survivor changes the tree. Results: `Merged`, `RefreshNeeded`, `Requested`, `NotCompleted`, `Unknown`, `Rejected`, `NeedsLogin`, `AccountChanged`, `NotLinked`.
  - A 4xx answer is `Rejected` and never read back. A lost answer or a 5xx is read back (reads only, never a second POST).
  - `Requested` only when the absorbed profile is alive and the survivor's `requested_merges` names it (the batch read of both profiles). A profile still alive with no such entry is `NotCompleted` ("Geni did not merge the profiles; check on Geni"). Both records stay in either case.
- `GeniLoader.gone`: for a profile merged into one already cached, the absorbed person is removed (kept when it holds local work, under the existing rules), and a tree root on it moves to the survivor (`updateRoot` plus the diagram root); `ownerGeniId` is never touched. The survivor keeps its GEDCOM record.
- UI (`GeniMerge`, app/src/geni): menu item "Merge on Geni" in `GeniGlue.addProfileMenu`; pick one of the other `profile-N` people of the tree, confirm (both names and life dates; the open profile survives), one merge per pair at a time. `RefreshNeeded` and `Unknown` offer "Check again" (`reconcile`).
- No upstream file changed beyond the existing menu hook, so `UPSTREAM-HOOKS.md` is unchanged.

## Status

- [x] Sandbox: completed merge and read-back
- [x] Sandbox: filed request (second account, profile 36587)
- [x] Client, merger, loader root remap, unit tests (`ProfileMergerTest`, `GeniClientTest`)
- [x] Menu, picker, confirmation, retry, strings (en, ru)
- [x] Emulator: menu item, picker and confirmation dialog shown; the merge itself was not sent from the UI (it would consume a shared test profile)
