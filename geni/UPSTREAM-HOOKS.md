# Upstream hooks

Every change to upstream FamilyGem files made by this fork.
Each code hook is marked with a `GENI-HOOK` comment; after a merge from upstream run `git grep GENI-HOOK` and compare with this list.

| File | Change | Why |
|---|---|---|
| `settings.gradle` | `include ':geni'` | Adds the Geni module. |
| `app/build.gradle` | `apply from: 'geni.gradle'` | Fork build changes live in `app/geni.gradle`. |
| `.gitignore` | `geni.properties` | Keeps Geni app credentials out of git. |
| `app/src/main/java/app/familygem/TreesActivity.kt` | `GeniGlue.addTreesMenu(this, bar.menu)` after `bar.inflateMenu(R.menu.trees_menu)` | "Geni account" item in the trees list menu. |
| `app/src/main/java/app/familygem/TreesActivity.kt` | `if (trees.isEmpty() && GeniGlue.startOnboarding(this)) Unit` as the first branch of the startup `if/else` chain in `onCreate` | Empty trees list opens the Geni login (once per process) instead of the "add a tree" prompt. |
| `build.gradle` | KSP Gradle plugin on the buildscript classpath | Room code generation in `:geni`; the plugin must share the classloader with AGP. |
| `app/src/main/java/app/familygem/profile/ProfileActivity.kt` | `GeniGlue.onPersonOpened(this, person) { refresh() }` at the end of `onCreate` | Loads the person's relatives from Geni when the card opens. |
| `app/src/main/java/app/familygem/profile/ProfileActivity.kt` | `GeniGlue.addProfileMenu(this, menu, person)` in `onCreateOptionsMenu` | "Keep offline" item for people of Geni trees. |
| `app/src/main/java/app/familygem/util/TreeUtil.kt` | `GeniGlue.onTreeDeleted(treeId)` at the start of `deleteTree` | Drops the Geni rows of a deleted tree, so a new tree with the same ID does not inherit them. |
| `app/src/main/java/app/familygem/util/TreeUtil.kt` | `GeniGlue.onTreeSaved(gedcom, treeId)` at the end of `saveJson` | Plans Geni uploads from every saved Geni tree. |
| `app/src/main/java/app/familygem/TreesActivity.kt` | `GeniGlue.trimTreeMenu(treeId, menu)` before `popup.setOnMenuItemClickListener` | Hides share, merge and compare from the menu of a Geni tree. |
| `app/src/main/java/app/familygem/profile/RelativesFragment.kt` | `GeniGlue.blockUnlink(context)` at the start of the Unlink in the family tab | Geni's API cannot remove a relationship: refused in Geni trees. |
| `app/src/main/java/app/familygem/main/DiagramFragment.kt` | `GeniGlue.blockUnlink(context)` at the start of the Unlink in the diagram menu | Geni's API cannot remove a relationship: refused in Geni trees. |
| `app/src/main/java/app/familygem/DetailActivity.java` | `GeniGlue.blockUnlink(context)` at the start of the Unlink family member (family card) | Geni's API cannot remove a relationship: refused in Geni trees. |
| `app/src/main/java/app/familygem/profile/ProfileActivity.kt` | `GeniGlue.handleDelete(context, person) { refresh }` at the start of Delete (menu id 5) | In a Geni tree: remove from this device, or delete on Geni (managed profiles), instead of upstream's delete. |
| `app/src/main/java/app/familygem/main/PersonsFragment.kt` | `GeniGlue.handleDelete(context, person) { refresh }` at the start of Delete person (menu id 6) | In a Geni tree: remove from this device, or delete on Geni (managed profiles), instead of upstream's delete. |
| `app/src/main/java/app/familygem/main/DiagramFragment.kt` | `GeniGlue.handleDelete(context, person) { refresh }` at the start of Delete (menu id 7) | In a Geni tree: remove from this device, or delete on Geni (managed profiles), instead of upstream's delete. |
| `app/src/main/java/app/familygem/profile/RelativesFragment.kt` | `GeniGlue.handleDelete(context, person) { refresh }` at the start of Delete (menu id 316) | In a Geni tree: remove from this device, or delete on Geni (managed profiles), instead of upstream's delete. |
| `app/src/main/java/app/familygem/DetailActivity.java` | `GeniGlue.handleDelete(context, person) { refresh }` at the start of Delete family member (case 23) | In a Geni tree: remove from this device, or delete on Geni (managed profiles), instead of upstream's delete. |
| `app/src/main/java/app/familygem/merge/MergeViewModel.kt` | `|| GeniGlue.isGeniTree(it.id)` in the filter of `trees` | A Geni tree is not offered as the second tree of a merge. |
| `app/src/main/java/app/familygem/profile/FactsFragment.kt` | `if (GeniGlue.isLockedTag(obj)) return` at the start of the `GedcomTag` branch of `placeEvent` | The `_GENI_ID` tag is shown but cannot be opened, edited or deleted from the card. |
| `app/src/main/java/app/familygem/profile/MediaFragment.kt` | `GeniGlue.handleDeleteMedia(context, person, media) { refresh }` at the start of Delete (menu id 8) | A Geni photo (tagged `_GENI_PHOTO_ID`): remove from this device, or delete on Geni (managed profiles); the main photo only via Geni. |
| `app/src/main/java/app/familygem/AboutActivity.java` | `GeniGlue.INSTANCE.hidePremium(this)` at the end of `onCreate` | Removes the upstream Premium advertisement (purchase of FamilyGem Premium) from the About screen. |
| `app/src/main/java/app/familygem/main/DiagramFragment.kt` | `GeniGlue.connect(path, x1, y1, x2, y2, toPxFloat(20F))` replaces `path.cubicTo(...)` for a `CurveLine` in `Lines.invalidate` | Straight elbow connections instead of S-curves. |
| `app/src/main/java/app/familygem/util/FileUtil.kt` | In `setFileAndProposeCropping`: `GeniGlue.holdPhoto(...)` before the crop prompt; release on No, dismissal or launch failure, hand-over to the editor on Yes (`geniSession` argument of `cropImage`, passed as intent extra `CropImageActivity.GENI_SESSION`) | The upload of a new photo to Geni waits for the user's crop decision. |
| `app/src/main/java/app/familygem/CropImageActivity.kt` | When started with `GENI_SESSION`: crops into a temporary sibling file, `GeniGlue.commitCrop` renames it over the original, `GeniGlue.releasePhoto` in `onDestroy` when leaving without a crop; an expired session exits untouched | The photo goes to Geni cropped, or intact if the crop is cancelled. Standalone crops are unchanged. |
