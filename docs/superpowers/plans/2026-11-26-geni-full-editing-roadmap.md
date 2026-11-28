# Geni full editing roadmap

**Goal:** in a Geni tree, everything the Geni API can write is editable in the app, so the app can replace the website for everyday editing. Anything the API can only read is shown read-only. Anything the API cannot reach is listed at the end, so nobody plans for it.

**Sources:** Geni API v1 reference, read 2026-11-26: `profile`, `profile/update`, `profile/update-basics`, `profile/add-*`, `union`, `union/update`, `event`, `date`, `location`, `phone_number`, `photo/*`, `photo_album/*`, `document/*`, `video/*`, `project/*`, `profile/merge`, `profile/follow`, `profile/add-mugshot`. The base is the field audit in [2026-11-26-geni-profile-edit-fields.md](2026-11-26-geni-profile-edit-fields.md), which is done.

**Rule for every stage:** check the write and the read-back on the sandbox first. Record the response shape in the stage's plan. Only then build the UI. A field the docs list but the sandbox doesn't round-trip stays read-only.

## Where we are (after PR #41)

| Area | Synced both ways | Gap |
|---|---|---|
| Names | first, middle, last, maiden, suffix, nicknames, display name, title; the same fields per language (`names`, PR #38) | languages of a person not saved yet (sent with the next save) |
| Sex | male, female, unknown (clears the sex; PR #37) | — |
| Profile events (birth, baptism, death, burial) | date incl. ranges (`range`, `end_*`, `end_circa`; PR #35), location in parts (`place_name`, `city`, `county`, `state`, `country`, `street_address1-3`, `latitude`, `longitude`; each part alone; PR #36) | event `description` (Geni drops it) |
| Other profile fields | is_alive, cause of death, occupation, about me, `public` (PR #37) | `locked` and `merge_note` (read-only, never returned for a basic account), curator controls |
| Contact data | `email` of a new relative, sent with the add (PR #39) | `email` of an existing profile, `phone_numbers` and `current_residence` stay site-only (shown read-only in "On Geni" when Geni returns them) |
| Unions | marriage, divorce incl. date ranges and structured location (same rules as profile events) | — (lineage of each child is read from `adopted_children` / `foster_children`; PR #41) |
| Relatives | add parent/partner/child/sibling; `relationship_modifier` (adopt / foster) when adding a child or sibling (PR #41) | change the lineage of an existing child (no API call); merge duplicates |
| Photos | add, delete, read main and album | title / description / date edit, tag and untag profiles, set an existing photo as main (`add-mugshot` with `photo_id`), albums |
| Documents, videos | — | everything (add, update, tag, untag, delete) |
| Projects, follow | — | add profiles to a project, follow / unfollow |

## Stages

Each stage is one plan and one or two PRs, in this order. Earlier stages touch every event and every profile. Later ones are separate features.

### 1. Exact dates and places (done: PR #35 dates, PR #36 places)

What the user gets: dates like "before 1900", "between 1840 and 1845" and "about 1850 – about 1855" sync with Geni. Places keep city, region and country apart, and an event can have a description.

- API: the Date structure has `range` (`before`, `after`, `between`) with `end_year`, `end_month`, `end_day`, `end_circa`. Location has `city`, `county`, `state`, `country`, `place_name`, `street_address1-3`, `latitude`, `longitude`. Event has `description`.
- Mapping: GEDCOM `BEF`, `AFT`, `BET … AND …` and `FROM … TO …` go to `range`. Each part keeps its own `ABT` as `circa` / `end_circa`. `GeniParams.parseDate` and `GeniMapper.gedcomDate` grow from one date to two. The place stays GEDCOM text. Geni's structured parts are kept in a `_GENI_LOCATION` extension of the fact, and only `place_name` is rewritten when the user edits the text, so Geni's structured parts are no longer wiped. The description maps to the fact's `NOTE` or its own extension (decide on the sandbox: does Geni show it on the site?).
- The date error added in PR #34 ("Geni cannot store this date") then only applies to free-text dates and other calendars.
- Verify first: write and read back each `range`, a `between` with `end_circa`, a location with only `city` + `country`, and clearing each of them.
- Touches: `GeniModels` (`GeniDate`, `GeniLocation`, `GeniEvent`), `GeniParams`, `GeniMapper`, `Fields` (`EventFields` gains range parts), `SnapshotOverlay`, and the date validation in `GeniProfileEditor`. Unions get the same change for free.

**Sandbox results (2026-11-27, profile and union events):**

- Date keys `<event>[date][range|year|month|day|circa|end_year|end_month|end_day|end_circa]` work on `profile/update`, `union/update` and `add-child`. GET returns `range`, `end_*` and `formatted_date`; `circa` and `end_circa` are omitted when false. `between`, `before` and `after` round-trip, also with `circa` on `before`, and with only an approximate end.
- A single date replaces the whole date: sending only `year` and `circa` also removes `range` and `end_*`; sending the range keys empty works too (the app sends them). Clearing year plus the other parts removes the event.
- Location keys `<event>[location][place_name|city|county|state|country|street_address1-3|latitude|longitude]` are independent: setting or clearing one leaves the others. GET adds `country_code` and `formatted_location`. The old behaviour (clear city, county, state and country when `place_name` changes) was never needed.
- Second pass (2026-11-28, disposable profile-36543 and union-10107; sanitized GETs in `geni/src/test/resources/geni/*location*.json`): a location of city and country only is stored. Street lines come back as `street_address1-3` and are part of `formatted_location`; coordinates come back as numbers (`longitude: 0.0` is a value, kept). Several keys cleared in one POST (`street_address3` and `latitude`) work, and each clear removes only its own key; `place_name` edited or cleared leaves city, country, street and coordinates. Union events have the same keys under `marriage[location]` (the union JSON also repeats them as top-level `marriage_date` / `marriage_location`, which the app does not read). A street-only event with no date is stored. **Coordinates alone are not stored** (a POST of only latitude and longitude returns no event; clearing the last text part of a location drops the coordinates with it), so the app treats coordinates without a text part as none (`LocationParts.storable`). A burial on a living profile is not stored (Geni ignores it), like death.
- `<event>[description]` is accepted and silently dropped (a profile `GET` never returns it, single line or multiline): not synced.

**Status of stage 1:**

- [x] Sandbox checks for dates (all ranges, approximate ends, replacement, clear; profile, union and add-child) and for locations and descriptions
- [x] Date ranges round-trip: `GeniDate`, `parseDate`, `gedcomDate`, `EventFields`, `SnapshotOverlay`, legacy start-of-range records left alone (PR #35)
- [x] Same date rules in Modify and in the event screens, marriage and divorce included; date pickers keep `ABT` on each end (PR #35)
- [x] Structured places: `_GENI_LOCATION` extension (JSON of the location keys on the fact, `PLAC` keeps the joined text), a place text edit changes only `place_name`, legacy joined-text places inherit Geni's parts without a write, queued jobs from before upgrade do not replay the old implicit clears (PR #36)
- [x] Verified structured fields shown in the event screen: place name, city, county, state, country, street lines and coordinates for birth, baptism, death, burial, marriage and divorce; editable, each part saved and cleared alone (PR #36)
- [x] Event descriptions: not synced, Geni drops them (decided from the sandbox)

### 2. Privacy, sex and the remaining profile flags (done: PR #37; curator fields wait for a curator account)

What the user gets: a profile can be made public or private, sex can be unknown, and curator flags are visible.

- API: `public` (update, add-*), `gender` (values not documented), `locked` and `merge_note` (update; probably curators only).
- Verify first: the accepted `gender` values (`unknown`? empty?) and whether clearing works; whether a non-curator's `locked` / `merge_note` write is refused, and what the response looks like.
- Mapping: `public` and `locked` go to person extensions (`_GENI_PUBLIC`, `_GENI_LOCKED`). Unknown sex becomes `SEX U` again in the editor. `merge_note` is shown read-only unless the sandbox shows the user can write it.
- UI: a Privacy switch in Modify. Curator fields only appear when the API reports the user as a curator (`curator` field).

**Sandbox results (2026-11-28, disposable profile-36543 and union-10107; sanitized fixtures `profile*privacy*.json`, `profile-sex-cleared.json`, `profile-merge-note-forms.json`):**

- `gender=unknown` and an empty `gender` both clear the sex: the field is omitted from every later GET (single and batch), the POST succeeds, and `gender=female`/`male` sets it again. On creation (`profile-X/add-child`, `union-X/add-child`) `gender=unknown` gives a profile without gender, same as omitting it.
- `public` is always returned, an explicit `false` included. A **living** profile is always `public: false`: `public=true` is accepted and ignored (profile and union paths, add-child, add-partner, update). A **deceased** profile takes both values, also on creation. Omitted on creation: `false` (private), living and deceased. Union `add-child` and profile `add-child`/`add-partner` behave the same.
- `locked` and `merge_note` (a string with spaces needs real form encoding) return a normal response on a basic account but are silently dropped: a GET never returns either, with or without `fields=`. `curator` is never returned either, and `user` gives only `name` and `account_type` ("basic"). So there is no account-role signal and no verified curator read or write: curator controls stay hidden and `locked` / `merge_note` are read-only (parsed if Geni ever returns them, string or array).

**Status of stage 2:**

- [x] Sandbox checks (PR #37): gender unknown/empty/restore, `public` on update, add-child (profile and union), add-partner, living and deceased, `locked` / `merge_note` / curator role signal
- [x] `public` read, `_GENI_PUBLIC` extension, own conflict group `PUBLIC`, written only when set and changed (never `true` for a living person, after `is_alive` in the same save); `locked` read into `_GENI_LOCKED`, never written; `merge_note` parsed in the model only
- [x] Unknown sex: Geni's missing gender is `SEX U`; `gender=unknown` is sent only to clear a sex Geni has; a legacy `SEX U` or a missing SEX fact never writes; Modify and the Facts chooser offer Unknown
- [x] Modify: Privacy switch (off and explained for a living person, "not known yet" for a linked person whose stored version has no privacy), untouched control keeps Geni's creation default, explicit Private sends `public=false`
- [x] Unit tests and 8 new Espresso tests (privacy on, explicit Private, living, Dead off and on, ordinary tree, unknown sex, legacy placeholder); CodeRabbit rounds fixed the switch across recreation: Android does not restore the Privacy switch, the form is rebuilt from the person
- [ ] Curator fields: need a verified account role signal (a curator account); hidden until then. To finish: sign in with a curator account on the sandbox (or Geni itself), check whether `curator`, `locked` and `merge_note` are returned and writable, then show and edit them only for that role

### 3. Names in other languages (done: PR #38)

What the user gets: the same names in another language or script (e.g. Cyrillic and Latin), like the site's language tabs.

- API: `names` is a hash of locale → name fields, written as `names[<locale>][<field>]=…` on `profile-X/update`.
- Mapping: one extra GEDCOM `NAME` per locale after the main and birth names, marked `_GENI_LOCALE` (GEDCOM 5.5.1 has no language for a `NAME`), its parts in tags like the main name's. Kept out of the main-name logic (`LocaleNames.main` / `birth`). Only changed locales and fields are sent.
- UI: a language list under the names in Modify ("Add a language"), for people that already exist; the Facts row of a language opens Modify and cannot be moved or deleted there.

**Sandbox results (2026-11-28, disposable profile-36543; sanitized fixtures `profile-names-*.json`):**

- Every field round-trips per locale: `first_name`, `middle_name`, `last_name`, `maiden_name`, `suffix`, `display_name`, `nicknames` (a comma separated text) and `title`. A write of one field (`names[de][last_name]`) keeps every other field of that locale and the other locales.
- An empty value clears one field. Clearing every field of a locale removes the locale; with no other locale left `names` is omitted from the response (the client then stores `"names":{}`, so a stored version without `names` means one from before they were read).
- `names[en-US]` is the primary name (the top-level fields); it is returned only while another locale exists. The `locale` query parameter on a GET changes nothing. Top-level `maiden_name`, `display_name`, `nicknames` and `title` fall back to another locale while the primary has none (known limit: clearing such a primary field cannot stick).
- An unsupported locale (`xx`, `DE`, `de-AT`, `pt-BR`) fails the whole request with 400 "Unsupported locale". The accepted codes are the catalog of `/platform/developer/help/locales` (138, `pt-BZ` for Brazilian Portuguese, `zh-CN`, `zh-TW`, `en-UK`): built into `GeniLocales`, never sent for a code outside it.
- The batch `profile?ids=…&fields=…,names` returns the same `names`.

**Status of stage 3:**

- [x] Sandbox checks: fields, partial update, clearing, removal of a locale and of the last one, viewer locale, case and unsupported codes, batch read
- [x] Read and store: `names` in the field mask and the model, `LocaleNames` (one tagged `NAME` per locale), main and birth name selection skips them everywhere (mapper, projection, editor, Facts, gone-profile check), a removed locale with notes or sources stays as an ordinary name, an old snapshot never removes anything, JSON save/reload and GEDCOM export/reimport checked
- [x] Sync by locale: `GeniParams` sends only the changed fields of the changed locales, a removed locale sends every field empty, `SnapshotOverlay` follows (keeps an empty `names`, keeps the primary entry in step), each locale is its own conflict group (`LOCALE:de`, labeled with the language) in the merger, uploader and conflict resolver
- [x] Modify: language list with Add (catalog), edit and Remove, drafts survive a rotation, Cancel stores nothing; Facts rows of a language open Modify, show their language and cannot be moved or deleted
- [ ] Names of a new relative in other languages: a profile is created without them, and the next save sends them (the form is not offered for a person not saved yet)

### 4. Read-only profile data, shown like the site (done: PR #39)

What the user gets: the card shows what Geni knows even where the API can't write it.

- Fields: `email`, `phone_numbers`, `current_residence` (Location), `events` (custom events, "must be requested"), `managers`, `claimed`, `curator`, `big_tree`, `merge_pending`, `relationship` (to the signed-in user).
- Mapping: kept in the link snapshot only, not in GEDCOM. Shown in a "On Geni" section of the Facts tab, with a link to open the profile on the site for editing there.
- New relatives: `email` can be set in the new-person form (add-* only), with a note that it can only be changed on the site afterwards.
- Verify first: the shape of `events` when requested (fields, whether custom events appear), and whether `email` of other people is returned at all (privacy).

**Status of stage 4:**

- [x] Sandbox checks: single and batch GET agree. `events` is the profile's whole timeline, not custom events: its own birth plus the births and unions of relatives ("Birth of Sasha P", "Marriage of …"), each with `name`, `date.formatted_date`, `location.formatted_location`; the batch returns it only when asked. `email` and `phone_numbers` were never returned, for any profile (own, managed, other, one created with an email). `claimed` and `relationship` come with an explicit false/text; `managers` only on managed profiles; `big_tree`, `merge_pending` and `curator` were not returned (`is_curator` is, always false); `current_residence` is writable by `profile/update`. Fixtures `profile-details-*.json` (the custom-event one is synthetic: the sandbox cannot create one)
- [x] Read from the snapshot: the batch asks for the fields, `ProfileDetails` reads them tolerantly from the saved snapshot (missing key or odd shape leaves the rest), nothing in `ProfileFields`, the mapper or GEDCOM
- [x] "On Geni" in Facts of a linked profile: read-only rows (relationship, email, phone, residence, managers, claimed, curator, big tree, merge pending, explicit false shown as No), events that are not the profile's own vital facts or unions listed once, "Open on Geni" only for an https `profile_url` on the configured host; read from the saved snapshot, so offline and after every refresh; no section in an ordinary tree
- [x] Email of a new relative: profile `add-child`, `add-parent`, `add-partner` and union `add-child`, `add-partner` all accept `email` without error, so the field is on every new-relative form. The sandbox never returns the email and cannot show an invitation, so the form says Geni may send one. The value waits in a `new_email` table (Room v9) bound to the new person's ID, goes with the one add POST only, and is dropped after the add, when the person is linked or when the person leaves the tree; never in the job, a snapshot, an update or GEDCOM
- [ ] Email of an existing profile, phone numbers and managers' names stay site-only (nothing returned to show)

### 5. Adoption and foster relationships (done: PR #41)

What the user gets: a child or sibling can be added as adopted or foster, and existing ones show it.

- API: `relationship_modifier` = `adopt` / `foster` on `add-child` and `add-sibling`; the union returns `adopted_children` and `foster_children`.
- Mapping: GEDCOM `PEDI adopted` / `foster` on the child's family link (`ParentFamilyRef`), read from and written to the union lists.
- Verify first: whether `union/add-child` also accepts the modifier, and whether an existing child can be changed afterwards (probably not; then changing it stays site-only).

**Sandbox findings (2026-11-28):**

- `relationship_modifier=adopt` / `foster` is accepted by `union/add-child`, `profile/add-child` and `profile/add-sibling`; no modifier makes a birth child. An unknown value fails the request.
- The union read-back (single GET and the batch `union?ids=…`, no field mask needed) lists the child in `children` and also in `adopted_children` or `foster_children`. Geni leaves an empty list out, so a missing list means none. Fixture `unions-lineage-batch.json`.
- `union/add-child` puts the child in that union. `profile/add-child` always makes a new single-parent union (the profile's other unions are left alone) and the modifier applies to it. `profile/add-sibling` picks the pivot's first parent union, with or without the modifier: it cannot target another one (extra `union` / `union_id` parameters are ignored), so it is not used: a sibling is a child of the chosen family and goes through the two child routes.
- An existing child cannot be converted: `profile/update` and `union/update` with `relationship_modifier` change nothing. Changing the lineage of an existing child stays site-only.

**Status of stage 5:**

- [x] Sandbox checks: the three routes, read-back, omitted lists, sibling routing, conversion
- [x] Read: `GeniUnion` decodes both lists; `GeniMapper.applyUnion` sets `PEDI adopted` / `foster` on the child's own link to that family (updated in place, other links and local-only marks such as `sealing` kept); a child listed in `children` but in neither list is a birth child and loses an old adopted or foster mark; a response without `children` changes nothing; independent of marriage and divorce conflicts
- [x] Create: `LocalFamily.lineages` from the children's `PEDI`; the planner adds `relationship_modifier` to the `ADD_CHILD` job (never for Birth or a job queued before); `Uploader.add` sends it once with the add through the linked union or the single-parent route, keeps it out of the profile snapshot, and fails before any POST when no route exists
- [x] Modify: Lineage choice (Birth default, Adopted, Foster) for a new child or sibling in a Geni tree, also from the Family screen; set on the new person's own `ParentFamilyRef` before the save (the sibling case no longer shares the ref with the pivot); the Lineage action is read-only for a person already on Geni
- [ ] Changing the lineage of an existing child stays on the site (no API call)

### 6. Photo details, tags and main photo

What the user gets: photo title, description and date are editable. People can be tagged in a photo and removed from it. Any photo can become the main photo.

- API: `photo/update` (`title`, `description`, `date`), `photo/tag/profile-X`, `photo/untag/profile-X`, `profile/add-mugshot` with `photo_id`, `photo_album/add` and `update` (`name`).
- Mapping: the Media record of a Geni photo (`_GENI_PHOTO_ID`) gets `TITL` and its date. Tags are the profiles the photo is linked to: a media link added on another person of the tree means "tag", removing it means "untag" (needs the `tags` read).
- Verify first: `add-mugshot` with `photo_id` (in 2026-11 it returned `null` without it), what `tags` returns, and whether `photo/update` accepts the same Date JSON as events.
- The roadmap's "no call found to set an existing photo as main" is solved by this if the sandbox confirms it.

### 7. Documents and videos

What the user gets: documents (scans, certificates, text) and videos of a profile, added and tagged like on the site.

- API: `document/add` (`file` base64 or `text`, `title`, `description`, `date`, `labels`, `source_url`), `document/update`, `document/tag` and `untag`, `document/delete`, `profile/documents`. `video/add`, `video/update`, `video/tag` and `untag`, `video/delete`, `profile/videos`.
- Mapping: GEDCOM media with `_GENI_DOCUMENT_ID` / `_GENI_VIDEO_ID`, the same pattern as photos (download on demand, upload queue jobs, delete only for managed profiles). Text documents can map to a shared note.
- Verify first: file size limits, the response of `text` documents, and whether a video upload works through the API at all.

### 8. Merging duplicates, following, projects

What the user gets: a duplicate found in the app can be merged on Geni (or a merge requested). People can be followed, and profiles added to a Geni project.

- API: `profile-A/merge/profile-B` (merges with edit rights on both, else files a request), `profile/follow` and `unfollow`, `project/add_profiles` (`profile_ids`), `project/add_documents`, `project/update`.
- Mapping: upstream's merge screens stay hidden. Merge is a Geni action from the profile menu, followed by a reload (the loader already handles `merged_into`).
- Verify first: what the response of a requested (not done) merge looks like, and how the app should show "merge requested".
- Projects and follow are small and independent. Do them only if the user wants them.

## What the API cannot do (stays on the website)

- Change `email`, `phone_numbers` or `current_residence` of an existing profile (read-only in the API).
- Create or edit custom timeline events (`events` is read-only, and there is no event resource).
- Remove a relationship (no call; the app keeps blocking it).
- Change a child from biological to adopted or foster after it was added (stage 5 checked: `profile/update` and `union/update` ignore `relationship_modifier`).
- About-me text in other languages (`detail_strings` is read-only), discussions, and editing or deleting comments. Comments can only be added (`photo/comment`, `document/comment`, `video/comment`).

## Order and dependencies

1 → 2 → 3 change the profile projection, `GeniParams` and the snapshot overlay, so they go one after another. 4 and 5 are done. 6 → 7 share the media pattern. 8 is independent.

This roadmap goes before the modern UI roadmap ([2026-11-26-modern-ui-roadmap.md](2026-11-26-modern-ui-roadmap.md)). The new fields are built in today's screens (`GeniProfileEditor`, the Facts and media tabs), and the UI rebuild takes them over with their tests.

Each stage gets its own detailed plan (tasks, tests, sandbox checks) before work starts, like [2026-11-26-geni-profile-edit-fields.md](2026-11-26-geni-profile-edit-fields.md).
