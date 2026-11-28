# Geni Documents (full-editing stage 7, PR 1)

**Goal:** Browse, open, upload and manage the Geni documents of a linked person: scans, certificates, PDFs. Videos are probed here and built in a second PR.

## Sandbox contract (2026-11-28, signed-in sandbox build, disposable profiles 36580 / 36581, synthetic files)

| Check | Result |
|---|---|
| `POST profile-X/add-document` `file` (base64), `file_name`, `content_type`, `title`, `description` | Works for a 3-page PDF, PNG, JPEG, GIF, TIFF (stored as JPEG) and `text/plain`. **`file_name` and `content_type` are both required**: either missing fails (`undefined method 'content_type'` / `'original_filename'`). It **tags the profile itself**, so no separate tag call. Fixtures `document-pdf.json`, `document-png.json` |
| Unsupported types | Word, zip, octet-stream and video answer `Unsupported content type: ...` (400) |
| Size | 5 MB accepted; an 8 MB and a 12 MB upload were cut off by Geni (the stream was reset after about 20 s, nothing was created). The app sends at most 5,000,000 bytes (`DocumentTypes.MAX_UPLOAD_BYTES`). Geni's limit lies between 5 and 8 MB and is not documented |
| `text` param (multiline) | Creates `content_type: text/plain`, `title`, `description`, `date` accepted. **The text is not readable back**: `GET document-X` has no text field (`fields=text,content,body` all `{}`) and `sizes.original` is a generic icon. So a text document is listed with its details and has no file; **no GEDCOM note is made and a text upload is not offered**. Fixture `document-text.json` |
| Complete original | `sizes.original` of a PDF or image is a signed URL on the photo CDN, byte-identical to the upload (3 pages, 10,663 bytes), needs no token. A PDF has `page_count` |
| `GET document-X` against `GET profile-X/documents` | Same fields. The list is newest first, paginated (`?page=N`, an empty page ends it, `total_count`). **A list of one document comes as the bare object** (`rawResults` handles it). Fixtures `documents-page1.json`, `documents-page2-empty.json`, `documents-single-bare.json` |
| `POST document/add` (standalone) | Same body, **no tags**; then `POST document-X/tag/profile-Y` tags (answers the tagged profiles, like photos). Not used: `profile-X/add-document` does both in one call |
| `POST document-X/update` | `title`, `description`, `date[day|month|year]` are set and cleared one at a time (empty clears; Geni then omits the field). `labels` **only appends** (`labels=` and `labels[]=` do not clear, the latter adds a junk label) and `source_url` is accepted but **never returned**: neither is offered |
| `untag/profile-Y` | Answers the remaining tagged profiles; the document stays in the other profile's list. Untagging the last profile leaves an untagged document (no `tags` key) that is in no list |
| `delete` | `{"result":"Deleted"}`; a repeat answers the same. `GET document-X` of a deleted one answers `{"results":[]}` (fixture `document-deleted.json`), `parseDocument` gives null |
| Permissions | Only the own account could be tried (all succeeded); a refusal by another curator cannot be reproduced here. It shows in the backlog and the draft stays |
| **Video** `profile-X/add-video` and `video/add` (same form) | Work with `file`, `file_name`, `content_type: video/mp4` and `title`: answers `video-N` with `sizes` (poster `.jpg` sizes, `original` is a JPEG) and **`sizes.video` a playable `.mp4` URL** (7,667 bytes, downloaded and it is an MP4); `profile-X/add-video` tags the profile. Fixture `video-added.json`. **Video goes in the second PR** |

Sandbox leftovers: document-11441 (`Doc-from-device`) on profile-36431 (Ivan P), test documents on profiles 36580 / 36581 (11416, 11420, 11421, 11429, 11433 to 11436), videos 289 and 290.

## Design

- **No Room migration.** A document is tracked per profile in the existing photo link table (`PhotoLink`), keyed by `document-N`; document IDs never clash with photo IDs. `PhotoSync.reconcileAlbum` skips document links. Version 9 stores keep their links and jobs untouched.
- `GeniDocument` (api), `GeniClient.documents` (all pages, any failure throws: a listing is never partial), `document`, `addDocument`, `updateDocument`, `tagDocument`, `untagDocument`, `deleteDocument`.
- `DocumentTypes`: the verified formats, the 5 MB upload cap, magic-byte check of a download. `PhotoDownloader` takes an `accept` predicate (documents: PDF or image, 30 MB, still no token and no redirect).
- `DocumentSync` (like `PhotoSync`): download to a staging file, attach as a media tagged `_GENI_DOCUMENT_ID` with the shared title/description/date tags, one file for two profiles (`geni-<document>.<ext>`; an uploaded one keeps its own name), `reconcile` only after a complete listing, `removeLocal`, `deletedOnGeni` (an edited copy stays as the user's own, file kept while anything uses it).
- `DocumentLoader`: list, mark on-device, download on tap.
- Jobs: `ADD_DOCUMENT` (`doc:<person>:<file>`, an add: never sent twice by itself, a lost answer or an interrupted send stays FAILED with "check on Geni"), `UPDATE_DOCUMENT` (`upddoc:<id>`, the photo-details machinery: only changed fields, draft kept when Geni refuses or the document is gone), `TAG_DOCUMENT` / `UNTAG_DOCUMENT` (`doctags:<id>:<profile>`), `DELETE_DOCUMENT` (`deldoc:<id>`). The uploaded file is copied into the tree's media folder at queue time, so the input survives a restart; its media carries `_GENI_DOCUMENT_QUEUED` until the add is confirmed. A queued or uploaded document is **never a photo candidate** (`LocalTree.capturePerson`), also a JPEG scan.
- UI (`GeniDocuments`, app flavor): menu **Geni documents** (list works offline-failed too, so an upload can be queued without the network; tap downloads and opens with the viewer the user picks), **Upload a document** (system file picker through the activity result registry, title and description, checks type and size), context menu of a document media (edit details, tag, untag), and the delete dialog with three distinct actions: **Remove from this device**, **Untag on Geni** (this profile only) and, only for a managed profile after a second confirmation, **Delete on Geni** (the copies leave the tree only after Geni confirms).

## Tests

`GeniDocumentTest` (fixtures, exact request bodies, pages, failed page), `DocumentSyncTest` (download, shared file, oversized, wrong type, no token, untag/delete races, failed or partial listing keeps the cache), `UploadDocumentTest` (exact add request, lost answer never re-sent, restart, retry of a failed tag sends no create, edit sends only the changed field, refusal keeps the draft, no photo job for a JPEG scan).

## Sandbox check on the emulator (signed in, autosave on)

- Upload from the picker: exactly one document on Geni, the media on the person.
- Upload queued **offline**, app stopped, network back, app opened: exactly one document (`document-11445`) appeared once the older queue had drained.
- Tag a second profile: Geni tags both; the file is shared, no second download. Untag the first: Geni keeps the second; the second's card shows the PDF offline.
- Edit details: only the changed field went out (`description`).
- Not repeated on the device: a rejected edit (covered by `aRejectedEditKeepsTheDraftForARetry` and `aDocumentGoneFromGeniFailsTheEditWithTheDraftKept`), the missing remote delete on an unmanaged profile (the dialog only adds the item for `DeleteRules.isManaged`, as for photos and profiles; every sandbox profile is managed by this account) and delete on Geni.
- With autosave off, a media added by the upload action is lost if the app is stopped before the user saves, like any unsaved edit; the queued job then fails with "removed from this device before it was sent" and sends nothing.
