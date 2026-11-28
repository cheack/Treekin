# Geni Videos (full-editing stage 7, PR 2)

**Goal:** Browse, download, play, upload and manage the Geni videos of a linked person. MP4 only, played through Android's file-opening flow.

## Sandbox contract (2026-11-28, signed-in sandbox build, disposable profiles 36580 / 36581, synthetic ffmpeg clips)

| Check | Result |
|---|---|
| `POST profile-X/add-video` `file` (base64), `file_name`, `content_type=video/mp4`, `title`, `description` | Works; answers `video-N` with `sizes` (JPEG posters: `medium`, `small`, `thumb`, `thumb2`, **`original` is a JPEG**) and **`sizes.video`, a playable `.mp4`**. It **tags the profile itself**. Fixture `video-added.json` |
| Size | A 0.45 MB, 1.8 MB and 2.4 MB clip were accepted (2.4 MB took about 8 s). A 4.0 MB and a 6.9 MB upload timed out (120 s) and **created nothing**. The app sends at most 2,500,000 bytes (`VideoTypes.MAX_UPLOAD_BYTES`); the real limit lies between 2.4 and 4 MB and is not documented |
| Download | `sizes.video` is a signed CDN URL, needs no token, but is **transcoded**: the 2.4 MB upload came back as 245 KB, and it is served as `application/octet-stream` (not `video/mp4`). The app accepts that type and `video/mp4`, and checks the `ftyp` box of the file |
| `GET profile-X/videos` | Newest first, paginated (`?page=N`, an empty page `{"results":[],"total_count":N,"page":2,...}` ends it). **A list of one video comes as the bare object** (`rawResults` handles it). Every listed item carries `sizes` including `video`. Fixtures `videos-page1.json`, `videos-page2-empty.json`, `videos-single-bare.json` |
| `GET video-X` | Same fields as the list. Right after the upload the answer already had `sizes.video`: no processing delay was seen |
| `POST video-X/update` | `title`, `description` and `date[day|month|year|circa]` are set and cleared one at a time (empty clears, Geni then omits the field; year alone and circa work). **The update answer sometimes has no `sizes`** (fixture `video-updated-no-sizes.json`), so a video without a playback URL is kept, listed as unavailable |
| `tag` / `untag` | `video-X/tag/profile-Y` answers the tagged profiles; `untag` answers the remaining ones; the video stays in the other profile's list. Untagging the last profile leaves a video with no `tags` key that is in no list |
| `delete` | `{"result":"Deleted"}`, a repeat answers the same; `GET video-X` of a deleted one answers `{"results":[]}` (fixture `video-deleted.json`), the profile list no longer has it |
| Permissions | Only the own account could be tried; a refusal by another curator cannot be reproduced here. It shows in the backlog and the draft stays |

Sandbox leftovers: videos 289 (profile-36581), 291, 294, 295, 296 (profile-36580), 292 (untagged, in no list), 290.

## Design

Mirrors the documents design ([the documents plan](2026-11-28-geni-documents.md)); nothing new is stored in Room.

- A video is tracked per profile in the existing photo link table, keyed `video-N`. `PhotoSync.reconcileAlbum` and `DocumentSync.reconcile` skip video links; `VideoSync.reconcile` only touches `video-` links. Media tag `_GENI_VIDEO_ID`, queued `_GENI_VIDEO_QUEUED`, detached `_GENI_VIDEO_DETACHED`; none is ever a photo candidate (`LocalTree.capturePerson`).
- `GeniVideo` (api; `videoUrl` is only `sizes.video`, the posters are never the playable file), `GeniClient.videos` (all pages, any failure throws, never partial), `video`, `addVideo`, `updateVideo`, `tagVideo`, `untagVideo`, `deleteVideo`.
- `VideoTypes`: MP4 only, upload cap, magic-byte check. The shared `PhotoDownloader` takes `accept = VideoTypes::isFile` (100 MB cap, no token, no redirect).
- `VideoSync`, `VideoLoader`: like the documents, one file `geni-video-N.mp4` for all profiles tagged in it; a video with no playback URL stays in the list as unavailable and is never attached.
- Jobs `ADD_VIDEO` (`vid:<person>:<file>`, an add: never sent twice by itself), `UPDATE_VIDEO` (`updvid:<id>`, the photo-details machinery), `TAG_VIDEO` / `UNTAG_VIDEO` (`vidtags:<id>:<profile>`), `DELETE_VIDEO` (`delvid:<id>`).
- UI (`GeniVideos`, app flavor): menu **Geni videos** (list works offline-failed too, tap downloads and opens as `video/mp4` through `FileProvider`), **Upload a video** (system picker, MP4, up to 2.5 MB), context menu of a video media (edit details, tag, untag), delete dialog with **Remove from this device**, **Untag on Geni** and, for a managed profile after a second confirmation, **Delete on Geni**.

## Tests

`GeniVideoTest` (fixtures, exact upload form, pages, failed page, poster-only), `VideoSyncTest` (download, shared file, wrong type or not an MP4, no token, untag/delete races, failed or partial listing keeps the cache, unavailable entry, photo and document refreshes leave videos alone), `UploadVideoTest` (exact add request, lost answer never re-sent, restart, retry of a failed tag sends no create, edit sends only the changed field, refusal keeps the draft, size cap and non-MP4).

## Sandbox check on the emulator (signed in, autosave on during the check, restored after)

- Upload queued **offline** from the system picker (`DevClip1.mp4`, title and description dialog), app stopped, network back, a person card opened (that is the hook that resumes the queue): exactly one video (`video-297`) on Geni, the media got `_GENI_VIDEO_ID`.
- A Geni-only video (`video-291`, tagged by the sandbox tool): the list showed "Tap to download and play", the tap downloaded `geni-video-291.mp4` (transcoded copy) and the system viewer played it. In airplane mode the media opened from Media and played.
- Tag a second profile from the media menu: Geni tagged both, the file was reused (one `geni-video-291.mp4` on disk, no second download). Untag the first: Geni kept the second; the second profile's media played offline.
- Delete on Geni (offered only for a managed profile, after a confirmation): `GET video-291` answered `{"results":[]}`, no profile list had it, the media and the file left the device and a later listing did not bring it back. The older-listing race is covered by `aDeletedVideoIsNeverBroughtBackByAnOldListingOrAnInFlightDownload`.
- Not repeated on the device: a rejected edit (unit tests), the missing remote delete on an unmanaged profile, a poster-only listing (unit tests).
- With autosave off, a queued video's media is lost if the app is stopped before the user saves, like any unsaved edit; the queued job then fails with "removed from this device before it was sent".
