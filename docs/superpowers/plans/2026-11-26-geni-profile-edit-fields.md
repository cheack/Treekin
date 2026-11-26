# Geni Profile Edit Fields Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Make the profile editor for Geni trees offer fields that FamilyGem can round-trip through Geni’s profile API.

**Architecture:** Keep the existing GEDCOM editor and select the Geni controls by tree type. Reuse the existing mapper, upload queue and conflict resolution for the new controls. Add sync mappings where a control requires data the current mapper discards.

**Tech Stack:** Android Views, Java, Kotlin, GEDCOM, Gson and the Geni API v1.

**Spec:** [Field audit and decisions](#field-audit-and-decisions), based on the user’s Residence and Burial report and the existing [Geni sync design](../specs/2026-11-16-geni-sync-design.md).

## Global Constraints

- Apply the editor changes to Geni trees, including new relatives and offline edits. Local trees keep the FamilyGem editor and full GEDCOM menus.
- Preserve existing local data, including Residence, Christening, event attachments and supplementary GEDCOM fields. Keep local notes, sources, media and relationships accessible through their existing workflows.
- Use the current save and upload path. Update only changed API fields. A second save with the same values produces no write.
- Use existing Android and test dependencies. Run the repository’s wrapperless Gradle build with JDK 21, Gradle 9.9.0 and the configured Android SDK.

## Review Focus

- A tree upgraded from the current app can edit an occupation without sending a pre-existing split first or middle name. Task 3 covers this.
- A legacy Christening and a new Baptism can coexist without retagging the Christening. Task 1 covers this.
- An existing burial with a citation retains the same fact and citation when its date is edited. Task 2 covers this.
- A previously saved date range remains intact when another field is edited. Task 2 covers this.
- A concurrent change to a name on Geni enters the existing conflict flow. Task 3 covers this.

---

## Field audit and decisions

The short **Modify** form in `PersonEditorActivity.java` currently exposes given name, surname, sex, birth date/place, deceased status and death date/place. Residence and Burial are entries in **Facts → + → Event** in `ProfileActivity.kt`. The uploader uses `POST /api/profile-ID/update`. The request parameters on that endpoint determine which controls can write. A field listed only under returned fields does not establish write support. [Geni profile update](https://www.geni.com/platform/developer/help/api?path=profile/update&version=1)

| Field | Current FamilyGem behavior | Proposed Geni editor |
| --- | --- | --- |
| Given name and surname | Given is combined and later split on the first space. Surname already syncs. | Use distinct First name, Middle name and Last name fields. Preserve multiword first names. |
| Birth surname, Suffix, Also known as | Mapped as a GEDCOM name of type `birth`, main name suffix and main name nickname. Outside the short form. | Add fields backed by those existing mappings. |
| Display name | The API field mask requests `display_name`, but `GeniProfile` does not decode it. | Add a separate Display name field if the API returns the explicit override. Preserve normal first/last name fields. |
| Name title | `title` is a documented write parameter. The v1 response table lists localized `names`, and omits a top-level `title`. The old API lists it. FamilyGem’s `TITL` is a GEDCOM fact. | Verify the v1 read-back path and empty-value behavior before exposing a Name title field. Keep the GEDCOM `TITL` fact local. |
| Birth, Deceased, Death | Already in the short form and synced. | Keep. Add Cause of death to the deceased section using `DEAT.cause`. |
| Baptism / Christening | Geni maps `baptism` to `BAPM` in both directions. The menu’s main choice is Christening (`CHR`), which the writer skips. `BAPM` currently appears under Other. | Offer Baptism date/place in Modify and one Baptism (`BAPM`) choice in Facts. Keep existing `CHR` data as local. |
| Burial | `BURI` already syncs date/place via Facts, consistent with the reported site behavior. | Keep in Facts and add date/place to Modify. |
| Occupation | `OCCU.value` syncs through `/update`. Occupation date/place have no matching write parameter. | Add Occupation text. Offer text only for newly created Geni occupation facts. |
| About me | Already synced via the person’s `_GENI_ABOUT` extension. | Add a multiline About me field. |
| Sex | Geni writes male/female. Unknown and clearing are excluded by `ProfileFields.of` and `GeniParams.profile`. | Display an existing unknown value. Offer male/female as synced changes until the API behavior for writing unknown or clearing is verified. |
| Residence and other GEDCOM-only events | `RESI`, `CREM`, `ADOP`, `EDUC`, `RELI`, generic events and the remaining Other entries have no mapping in this profile writer. Geni returns `current_residence` as the profile’s current address. | Omit these choices from the Geni event creation menu. Keep existing facts accessible. |
| Contact, privacy, administrative and localized fields | Email and phone numbers appear in reads. `public`, `locked`, `merge_note` and localized `names` have documented write parameters. | Keep account, permissions, merge and multilingual controls outside this basic profile editor. |

Sources for the API distinctions are the [v1 profile read](https://www.geni.com/platform/developer/help/api?path=profile&version=1), [v1 profile update](https://www.geni.com/platform/developer/help/api?path=profile/update&version=1), [v1 update-basics](https://www.geni.com/platform/developer/help/api?path=profile/update-basics&version=1), and [v0 profile read](https://www.geni.com/platform/developer/help/api?path=profile&version=0). Repository evidence comes from `GeniClient.PROFILE_FIELDS`, `Uploader.update`, `GeniMapper.applyProfile`, `ProfileFields.of`, and `GeniParams.profile`. This audit used the docs and repository. Authenticated write/read-back checks belong to the implementation’s sandbox verification.

## File structure

| Files | Responsibility |
| --- | --- |
| `app/src/main/java/app/familygem/profile/ProfileActivity.kt`, `detail/EventActivity.java`, `profile/FactsFragment.kt` | Geni Facts choices and alternate edit entry points |
| `app/src/main/java/app/familygem/PersonEditorActivity.java`, `app/src/main/res/layout/person_editor_activity.xml` | Common editor lifecycle, Geni-only sections and save |
| `app/src/geni/java/app/familygem/geni/GeniProfileEditor.kt` | Geni-specific GEDCOM binding and validation, using `GeniGlue.isGeniTree(treeId: Int): Boolean` from the normal entry points |
| `geni/src/main/java/app/familygem/geni/api/GeniModels.kt`, `geni/src/main/java/app/familygem/geni/map/{Fields,FieldGroups,GeniMapper,GeniParams}.kt` | Name parts, display name, and existing profile field projections and writes |
| `geni/src/test/java/app/familygem/geni/map/{GeniMapperTest,GeniParamsTest}.kt`, `geni/src/test/java/app/familygem/geni/sync/UploadPlannerTest.kt`, `app/src/androidTest/java/app/familygem/geni/GeniProfileEditorTest.kt` | Mapper, payload, queue and editor regression coverage |

### Task 1 — Match Facts creation to the Geni writer

**Files** — Modify `ProfileActivity.kt` (`setupFab` and its event dispatch), `EventActivity.java` (`format`), and `FactsFragment.kt` (sex chooser). Create `app/src/androidTest/java/app/familygem/geni/GeniProfileEditorTest.kt`.

**Interfaces** — Consume `GeniGlue.isGeniTree(treeId: Int): Boolean`, `Memory.getLeaderObject()` and the existing person/event save methods. The Geni menu creates GEDCOM tags `BIRT`, `BAPM`, `OCCU`, `DEAT` and `BURI`.

- [x] Add `factsMenuUsesGeniTagsAndKeepsLegacyRecords()`. Assert the Geni menu contains Birth, Baptism, Occupation, Death and Burial and omits Christening, Residence and Other. Open Baptism, save `1904`, and assert the person has `BAPM` with date `1904` and the original `CHR` and `RESI` facts with their original values. Open an ordinary tree and assert its existing event menu entries remain visible.
- [x] Run `gradle --console=plain :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.familygem.geni.GeniProfileEditorTest`. Expect the Geni menu assertion to fail.
- [x] Change `setupFab()` to build the Geni entries with stable dispatch IDs tied directly to the tag. For an already present synced tag, open its first fact so a second fact cannot silently miss the sync projection. In `EventActivity.format()`, offer Date/Place for supported events and Cause for Death. For Occupation offer Value, and use the existing legacy detail presentation for already-populated local fields. Keep local attachments accessible. Keep the Facts sex chooser consistent with the supported changes and display any existing unknown value.
- [x] Rerun the instrumentation test. Expect success. Commit the task as `Match Geni profile fact choices to sync`.

### Task 2 — Expose fields already supported by sync

**Files** — Modify `PersonEditorActivity.java`, `person_editor_activity.xml`, `ProfileActivity.kt` and `GeniMapper.kt` (`applyProfile`, Occupation branch). Create `app/src/geni/java/app/familygem/geni/GeniProfileEditor.kt`. Extend `GeniProfileEditorTest.kt`, `GeniMapperTest.kt` and `GeniParamsTest.kt`. Add missing English and Russian labels in the app or Geni string resources as appropriate.

**Interfaces** — Add `GeniProfileEditor(activity: PersonEditorActivity)`, `bind(person: Person): Unit`, `finishEditingAndValidate(): Boolean` and `saveTo(person: Person): Unit`. Use the existing first matching `EventFact`, `DateEditorLayout`, `Name` and `GeniMapper.getTag/setTag`. Use the first non-birth name as the primary name.

- [x] Add `modifyOffersMappedFieldsOnlyOnGeniTrees()`, `editKeepsBurialCitationAndUnchangedDateRange()` and `clearingMappedFieldsQueuesClears()`. Assert the existing `BURI` with citation `S1` keeps it after its date changes from `1901` to `1902`. Assert an unchanged birth date `BET 1840 AND 1845` stays intact and contributes no birth parameters. Assert clearing Occupation, Cause of death, About me and Burial date/place produces the corresponding empty values and leaves unrelated groups out of the write. Also assert a remote occupation-text update preserves an existing local occupation date and place. Open Death from Facts on a living profile and cancel. Assert that no `DEAT` record or write is created.
- [x] Run the Task 1 instrumentation command and `gradle --console=plain :geni:testDebugUnitTest --tests '*GeniMapperTest' --tests '*GeniParamsTest'`. Expect the missing editor controls and loss of local occupation date/place to fail.
- [x] Bind Birth surname, Suffix, Also known as, Baptism date/place, Burial date/place, Occupation, Cause of death and About me to the existing mapped records. Initialize the editor after the GEDCOM and person are available, including new relatives. Disable the Geni sex radio buttons’ tap-to-clear behavior and make Unknown display-only. Finish date editing before validation. Permit blanks and unchanged legacy dates. For newly changed dates that `GeniParams.parseDate` cannot represent, show a field error and keep the form open before mutating the person. Keep Cause of death in the deceased group and reuse the existing deceased switch semantics. Route the Geni Facts Death command for an existing living person to this form with the deceased switch preselected in the form state only. Update existing facts in place and avoid creating empty facts. Preserve local occupation date/place on remote text updates. Keep the normal `TreeUtil.save` entry point and make the added widgets participate in focus and saved view state.
- [x] Rerun both commands. Expect success. Commit as `Expose synced Geni profile fields in Modify`.

### Task 3 — Round-trip first name, middle name and display name

**Files** — Modify `GeniModels.kt`, `Fields.kt`, `FieldGroups.kt`, `GeniMapper.kt`, `GeniParams.kt`, `PersonEditorActivity.java`, `GeniProfileEditor.kt`, `person_editor_activity.xml` and `FactsFragment.kt`. Extend the map, planner and editor tests listed above.

**Interfaces** — Add `GivenNameParts(first: String?, middle: String?)` and optional `givenParts: GivenNameParts?` to `ProfileFields`. Keep `given: String?` for existing GEDCOM display and upload-planner matching. Add `GeniMapper.getGivenNameParts(person: Person): GivenNameParts?` and `setGivenNameParts(person: Person, parts: GivenNameParts): Unit`. Store parts in the person’s GEDCOM extensions using `_GENI_FIRST_NAME` and `_GENI_MIDDLE_NAME`. If the sandbox confirms the Display name override, add `displayName: String?` to `GeniProfile` and `ProfileFields`, using `_GENI_DISPLAY_NAME` for the GEDCOM value. The field mask and fallback snapshot already carry `display_name`.

- [x] On a disposable sandbox profile, use `POST profile-ID/update` then a fresh v1 GET with `display_name,title,names` to check set and clear for Display name and Name title. Record the returned key, locale and cleared representation. Enable Display name only when the explicit override can be distinguished from the computed display name. Use the observed response shape in the fixture. Carry the Name title findings into Task 4.
- [x] Add `keepsMultiwordFirstNameWhenMiddleNameChanges()`, `legacyCombinedNameDoesNotQueueANameWrite()` and `concurrentNameEditsConflict()`. Assert editing `Mary Ann` + `Louise` to `Mary Ann` + `Rose` sends only `middle_name=Rose` among name parameters, and a concurrent remote change to Middle name conflicts in `NAMES`. Assert a legacy record with given `Mary Ann Louise` and snapshot first `Mary Ann` / middle `Louise` queues only the edited occupation. If Display name is enabled, add `displayNameSetAndClearRoundTrip()`. Assert `M. A. Example` is imported and editable, clearing it sends `display_name=`, and an old local record with no display-name extension and that cached remote value sends no clear on an occupation edit.
- [x] Run `gradle --console=plain :geni:testDebugUnitTest --tests '*GeniMapperTest' --tests '*GeniParamsTest' --tests '*UploadPlannerTest'`. Expect missing projection or incorrect payload assertions to fail.
- [x] Populate explicit name parts during import and refresh. Keep the combined GEDCOM `Name.value` in sync with the parts. Ignore stale part tags if their combined value disagrees with the actual GEDCOM name. In `GeniParams.profile` and profile `threeWay`, resolve a legacy local record without explicit parts to the baseline split when the combined given value matches. If the combined value changed or there is no baseline, use the existing first-space fallback. Include normalized explicit parts in name comparisons and the mapper’s remote change predicate, including when only the split changed. If verified, add `DISPLAY_NAME` as a separate profile group and write only its change. Preserve the distinction between a missing local extension (inherit the baseline) and an explicitly empty override (send a clear), for example with an empty tagged value. Keep the internal extension rows out of normal Facts editing.
- [x] Bind First name, Middle name and any verified Display name control in `GeniProfileEditor`. Save explicit parts only for new records or when edited. For an existing person with no stored parts, use `Geni.store(context).linkByGedcomId(treeId, personId)?.snapshot` in the activity lifecycle to prefill from the cached profile when the combined given value matches. Use the first-space fallback for a new or local-only name. Complete initial binding before accepting input. Route the primary synced name row’s edit action to Modify so both entry points use the same fields.
- [x] Rerun the unit and instrumentation commands. Expect success. Commit as `Round-trip Geni name fields independently`.

### Task 4 — Verify API read-back and release behavior

**Files** — Record test results in this plan. For a confirmed Name title mapping, the owning files are `GeniModels.kt`, `GeniClient.kt`, `Fields.kt`, `FieldGroups.kt`, `GeniMapper.kt`, `GeniParams.kt`, `SnapshotOverlay.kt` if the verified response differs from the request shape, and the Geni editor files from Task 3.

**Interfaces** — Use the sandbox read-back findings from Task 3. When Title is verified, add `title: String?` to `ProfileFields` and use `_GENI_NAME_TITLE` for its GEDCOM extension. Save via the same editor and field-specific diff as Display name.

- [ ] Enable Name title only after confirming a stable read-back path. Add `nameTitleRoundTripsIndependentlyOfGedcomTitle()` with a read-back fixture for `Dr.`, an update and clear assertion for API `title`, and a no-write assertion for the existing GEDCOM `TITL` entry `Professor`. Run the Task 3 unit command and confirm failure before implementing the verified decode, field diff and editor binding. Rerun the command and confirm success. Record the sandbox result if Title stays out of the form.
- [ ] On the sandbox, edit an existing profile’s Baptism, Burial, Occupation, Cause of death, About me and enabled name fields. Check the website and reload the app. Verify the same values on both sides, then save again and check for zero new write jobs. Repeat with a new relative saved offline, then reconnect and confirm a single add. Confirm that a legacy local Residence still exists after refresh.
- [ ] Run `gradle --console=plain :geni:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug` and the Task 1 instrumentation command. Expect `BUILD SUCCESSFUL` from both. Record results and commit any verified title mapping with its fixtures.
