# Geni full editing roadmap

**Goal:** in a Geni tree, everything the Geni API can write is editable in the app, so the app can replace the website for everyday editing. Anything the API can only read is shown read-only. Anything the API cannot reach is listed at the end, so nobody plans for it.

**Sources:** Geni API v1 reference, read 2026-11-26: `profile`, `profile/update`, `profile/update-basics`, `profile/add-*`, `union`, `union/update`, `event`, `date`, `location`, `phone_number`, `photo/*`, `photo_album/*`, `document/*`, `video/*`, `project/*`, `profile/merge`, `profile/follow`, `profile/add-mugshot`. The base is the field audit in [2026-11-26-geni-profile-edit-fields.md](2026-11-26-geni-profile-edit-fields.md), which is done.

**Rule for every stage:** check the write and the read-back on the sandbox first. Record the response shape in the stage's plan. Only then build the UI. A field the docs list but the sandbox doesn't round-trip stays read-only.

## Where we are (after PR #34)

| Area | Synced both ways | Gap |
|---|---|---|
| Names | first, middle, last, maiden, suffix, nicknames, display name, title | localized `names` per language |
| Sex | male, female | unknown / clear not verified |
| Profile events (birth, baptism, death, burial) | date `year/month/day/circa`, location as `place_name` only | date ranges (`range`, `end_*`, `end_circa`), structured location (`city`, `county`, `state`, `country`, `street_address1-3`, `latitude`, `longitude`), event `description` |
| Other profile fields | is_alive, cause of death, occupation, about me | `public`, `locked`, `merge_note` |
| Contact data | — | `email` (writable only when a relative is created), `phone_numbers` and `current_residence` (read-only) |
| Unions | marriage, divorce (same limits as profile events) | ranges, structured location, description; `adopted_children` / `foster_children` read |
| Relatives | add parent/partner/child/sibling | `relationship_modifier` (adopt / foster) when adding a child or sibling; merge duplicates |
| Photos | add, delete, read main and album | title / description / date edit, tag and untag profiles, set an existing photo as main (`add-mugshot` with `photo_id`), albums |
| Documents, videos | — | everything (add, update, tag, untag, delete) |
| Projects, follow | — | add profiles to a project, follow / unfollow |

## Stages

Each stage is one plan and one or two PRs, in this order. Earlier stages touch every event and every profile. Later ones are separate features.

### 1. Exact dates and places

What the user gets: dates like "before 1900", "between 1840 and 1845" and "about 1850 – about 1855" sync with Geni. Places keep city, region and country apart, and an event can have a description.

- API: the Date structure has `range` (`before`, `after`, `between`) with `end_year`, `end_month`, `end_day`, `end_circa`. Location has `city`, `county`, `state`, `country`, `place_name`, `street_address1-3`, `latitude`, `longitude`. Event has `description`.
- Mapping: GEDCOM `BEF`, `AFT`, `BET … AND …` and `FROM … TO …` go to `range`. Each part keeps its own `ABT` as `circa` / `end_circa`. `GeniParams.parseDate` and `GeniMapper.gedcomDate` grow from one date to two. The place stays GEDCOM text. Geni's structured parts are kept in a `_GENI_LOCATION` extension of the fact, and only `place_name` is rewritten when the user edits the text, so Geni's structured parts are no longer wiped. The description maps to the fact's `NOTE` or its own extension (decide on the sandbox: does Geni show it on the site?).
- The date error added in PR #34 ("Geni cannot store this date") then only applies to free-text dates and other calendars.
- Verify first: write and read back each `range`, a `between` with `end_circa`, a location with only `city` + `country`, and clearing each of them.
- Touches: `GeniModels` (`GeniDate`, `GeniLocation`, `GeniEvent`), `GeniParams`, `GeniMapper`, `Fields` (`EventFields` gains range parts), `SnapshotOverlay`, and the date validation in `GeniProfileEditor`. Unions get the same change for free.

### 2. Privacy, sex and the remaining profile flags

What the user gets: a profile can be made public or private, sex can be unknown, and curator flags are visible.

- API: `public` (update, add-*), `gender` (values not documented), `locked` and `merge_note` (update; probably curators only).
- Verify first: the accepted `gender` values (`unknown`? empty?) and whether clearing works; whether a non-curator's `locked` / `merge_note` write is refused, and what the response looks like.
- Mapping: `public` and `locked` go to person extensions (`_GENI_PUBLIC`, `_GENI_LOCKED`). Unknown sex becomes `SEX U` again in the editor. `merge_note` is shown read-only unless the sandbox shows the user can write it.
- UI: a Privacy switch in Modify. Curator fields only appear when the API reports the user as a curator (`curator` field).

### 3. Names in other languages

What the user gets: the same names in another language or script (e.g. Cyrillic and Latin), like the site's language tabs.

- API: `names` is a hash of locale → name fields (`first_name`, `middle_name`, `last_name`, `maiden_name`, `display_name`, … — check the field list, since the locales page did not load).
- Mapping: one extra GEDCOM `NAME` per locale, with `_GENI_LOCALE` (and `LANG` for export), kept out of the main-name logic. Only changed locales are sent, as `names[de][last_name]=…`.
- Verify first: which fields a locale accepts, how a locale is removed, and how `name` / `display_name` change with the viewer's language.
- UI: a language list under the names in Modify ("Add a language").

### 4. Read-only profile data, shown like the site

What the user gets: the card shows what Geni knows even where the API can't write it.

- Fields: `email`, `phone_numbers`, `current_residence` (Location), `events` (custom events, "must be requested"), `managers`, `claimed`, `curator`, `big_tree`, `merge_pending`, `relationship` (to the signed-in user).
- Mapping: kept in the link snapshot only, not in GEDCOM. Shown in a "On Geni" section of the Facts tab, with a link to open the profile on the site for editing there.
- New relatives: `email` can be set in the new-person form (add-* only), with a note that it can only be changed on the site afterwards.
- Verify first: the shape of `events` when requested (fields, whether custom events appear), and whether `email` of other people is returned at all (privacy).

### 5. Adoption and foster relationships

What the user gets: a child or sibling can be added as adopted or foster, and existing ones show it.

- API: `relationship_modifier` = `adopt` / `foster` on `add-child` and `add-sibling`; the union returns `adopted_children` and `foster_children`.
- Mapping: GEDCOM `PEDI adopted` / `foster` on the child's family link (`ParentFamilyRef`), read from and written to the union lists.
- Verify first: whether `union/add-child` also accepts the modifier, and whether an existing child can be changed afterwards (probably not; then changing it stays site-only).

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
- Change a child from biological to adopted or foster after it was added (unless stage 5 finds a way).
- About-me text in other languages (`detail_strings` is read-only), discussions, and editing or deleting comments. Comments can only be added (`photo/comment`, `document/comment`, `video/comment`).

## Order and dependencies

1 → 2 → 3 change the profile projection, `GeniParams` and the snapshot overlay, so they go one after another. 4 needs no writes and can go any time. 5 depends on how the relatives sync builds family links. 6 → 7 share the media pattern. 8 is independent.

This roadmap goes before the modern UI roadmap ([2026-11-26-modern-ui-roadmap.md](2026-11-26-modern-ui-roadmap.md)). The new fields are built in today's screens (`GeniProfileEditor`, the Facts and media tabs), and the UI rebuild takes them over with their tests.

Each stage gets its own detailed plan (tasks, tests, sandbox checks) before work starts, like [2026-11-26-geni-profile-edit-fields.md](2026-11-26-geni-profile-edit-fields.md).
