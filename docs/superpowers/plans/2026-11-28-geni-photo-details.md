# Geni Photo Details, Tags and Main Photo (full-editing stage 6)

**Goal:** Edit the title, description and date of an existing Geni photo from the Media tab of a linked person. Tags, main-photo choice and albums follow in a second PR.

Two PRs: **PR 1 photo details** (this plan, sections "Sandbox contract" and "Design"), **PR 2 tags, main photo** (section "PR 2").

## Sandbox contract (2026-11-28, signed-in sandbox build, two disposable linked profiles and three test photos)

| Check | Result |
|---|---|
| `POST photo-X/update` `title`, `description`, `date[day]`/`[month]`/`[year]`/`[circa]` | All accepted, the response is the photo. An update of one field keeps the others. Fixture `photo-details-single.json` |
| Clear a field | An empty value (`title=`, `description=`, `date[year]=`, `date[month]=`, `date[day]=`, `date[circa]=false`) clears it; Geni then **leaves the field out** of every response (photo and list) |
| Multiline description | `\n` round-trips exactly; markup is stored as text, not escaped (`<b>` stays) |
| Partial date | Year only, month and year, full date and `circa` all round-trip (`{"month":7,"year":1948,"circa":true}`) |
| Date range (`range` before/after/between, `end_*`) | The update response shows the range, a later `GET photo-X` and `profile-X/photos` show only the first date. **Not offered.** Fixtures `photo-date-range-update-response.json` and `photo-date-range-read-back.json` |
| `GET photo-X` against `GET profile-X/photos` | Same fields and shape (`title`, `description`, `date`, `tags`, `sizes`). `date.formatted_date` is extra and ignored |
| Photo shared by two profiles | One photo, `tags` lists both profile URLs; each profile's `photos` list has it with the same details. Fixture `photo-details-list.json` |
| `POST photo-X/tag/profile-Y` | Answers the tagged profiles (`{"results":[profile…],"total_count":N}`); `GET photo-X/tags` gives the same. Both profiles' `photos` lists contain the photo afterwards |
| `POST photo-X/untag/profile-Y` | Same answer shape; the profile's `photos` list loses it. A photo with no tag has **no `tags` key**; untagging the last profile answers `{"results":[]}` |
| Untag the profile whose main photo it is | The profile gets another of its photos as main (`mugshot_urls` named the next photo); the untagged photo stays on Geni |
| `POST profile-X/add-mugshot` `photo_id=photo-Y` | Works (earlier `null` was only the missing `photo_id`): answers the photo, `mugshot_urls.url` names `photo-Y`, the former main stays in the list. Works for an untagged photo too and tags the profile. `photo_id=2241` and the guid fail: `Invalid node ID` |
| Albums | `photo_album/add`, `album/add`, `profile-X/add-album`, `photo_album-N`, `album-N`, `profile-X/photo_albums` all answer the user object (no such route). **Album create and rename are not available through the API; dropped from the plan.** `album_id` on a photo is a read-only number |
| Permissions | Only photos of the own sandbox account could be exercised (all succeeded); an edit by another curator cannot be tried here. The editor is offered for every Geni photo and a refusal shows in the sync backlog with the draft kept |

Sandbox leftovers: profile-36580 `PhA Probe` (photos 2241 and 2243, main 2242 after the probes), profile-36581 `PhB Probe` (photo 2242), children of profile-36431; photo-2182 of Ivan P holds probe details (title `Persist_check`).

## Design (PR 1)

- `GeniPhoto` has `description`, `date` and `detailsKnown` (false for a photo made from a mugshot URL, which carries no details). `PhotoDetails` (title, description, GEDCOM date) is the tree's view; `GeniParams.photo(old, new)` makes the form, an empty value clears; photo dates are single dates (`GeniParams.isPhotoDate`).
- Media of a Geni photo: GEDCOM `TITL` is the title, `_GENI_PHOTO_DESCRIPTION` and `_GENI_PHOTO_DATE` hold the rest, `_GENI_PHOTO_TITLE` is the title Geni had at the last sync and `_GENI_PHOTO_SYNCED` marks that details were read. A title that differs from the synced one is the user's own: it is never overwritten by a refresh and keeps the media when Geni deletes the photo (`PhotoDetails.hasOwnTitle`, used by `PhotoSync.isEdited`). An old link without the marker keeps its title.
- `PhotoSync.refreshDetails` applies a listing to every person's media with that photo ID, no download; `AlbumLoader.list` calls it; a download applies details too. Skipped: photos listed without details, deleted ones, and photos with a queued edit (`updphoto:<id>` not dismissed).
- `UPDATE_PHOTO` job, key `updphoto:<photoId>` (one per photo in the tree, not per profile), queued by `SyncQueue.enqueueUpdatePhoto`. Params are the starting values (`base:*`) and the wanted ones (`want:*`); a second edit keeps the first base and the other wanted fields; a job wanting its base is dropped. The planner never removes it.
- `Uploader.updatePhoto`: reads the photo first (a lost response whose write succeeded sends nothing), fails with the draft kept when Geni changed a wanted field to something else than the base or the wanted value (the base moves to Geni's value so Retry overwrites), checks the job is still current, posts only changed fields, then applies the confirmed values to every media of the photo (`PhotoSync.applyConfirmed`).
- UI: "Edit Geni photo details" in the Media context menu through `GeniPhotoEditor.addMenuItem` (hook in `UPSTREAM-HOOKS.md`); the edit shows at once on every media of the photo (`applyOwnEdit`) and goes through the queue; Cancel or an unchanged save queues nothing; a range or unparsable new date keeps the dialog open.

## Status

**PR 1:**

- [x] Sandbox contract recorded, fixtures saved
- [x] `GeniPhoto`, `GeniClient.updatePhoto`, `GeniParams.photo`
- [x] `PhotoSync` / `AlbumLoader` reconcile details, titles of the user kept, no download
- [x] `UPDATE_PHOTO` in `SyncQueue` / `Uploader`, conflict and rejection keep the draft
- [x] Editor action and hook
- [x] Unit tests (`PhotoDetailsSyncTest`, `UploadPhotoDetailsTest`, queue, client, params, parse, album)
- [x] Emulator check: edit offline, reconnect, value on Geni, restart, conflict with an earlier edit shown in the backlog and replaced by Retry

**PR 2:**

- [x] Tag and untag (`TAG_PHOTO` / `UNTAG_PHOTO`, key `tags:<photoId>:<profileId>`, latest wish wins): "Tag a person on Geni" lists the other Geni people of the tree; the photo is downloaded for them after Geni confirms. "Untag on Geni" removes only that person's attachment (a non-main one at once, the main one when Geni names a replacement); the file stays while another person uses it, the photo stays on Geni. A queued untag stops an album download of the photo for that profile. "Remove from this device" and "Delete on Geni" are unchanged.
- [x] Set as main on Geni (`SET_MAIN_PHOTO`, key `mainphoto:<profileId>`, `add-mugshot` with `photo_id`): shown for a photo that is not primary; after Geni confirms, `PhotoSync.sync` makes it primary from the cached file, the former main stays as a plain photo. `add-mugshot` answers `null` (and changes nothing) when the photo is already main (fixture `photo-add-mugshot-already-main.json`).
- [x] ~~Album create and rename~~: no API route (see the table)
- [x] Emulator check: set main, tag a second person, untag a non-main photo, each confirmed by a fresh API read. An unsaved change to the open tree is lost when the app is stopped (autosave off, as upstream).
