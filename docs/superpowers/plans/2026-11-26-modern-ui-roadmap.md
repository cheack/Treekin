# Modern UI roadmap

**Goal:** Treekin looks and feels like its own modern Android app. The screens people use most are rebuilt as Geni-native code with a current design. The hard, generic parts of Family Gem stay underneath.

**Approach (decided 2026-11-26):** keep the fork and replace screens one at a time, rather than rewriting. Kept from upstream: the tree diagram engine (`gedcomgraph`, `DiagramFragment`), GEDCOM dates and their editor, media (picking, cropping, zoom), export (GEDCOM, ZIP, PDF, PNG), notifications and translations. Rebuilt: Home, profile card, editor, relatives and family, people and search, account screens.

## Where design is in 2026 (checked 2026-11-26)

- **Material 3 Expressive** is Google's current design language. It extends Material 3 with expressive color, shape morphing (`MaterialShapes`), physics-based motion (`MotionScheme`) and bolder type, and it matches Android 16's system UI. In Compose, `MaterialExpressiveTheme` replaces `MaterialTheme`. Compose Material 3 1.4.0 is stable and 1.5.0 is in beta (2026-10), and most expressive APIs only arrive in 1.5.
- Common themes in 2026 app design: content-first, uncluttered screens; large photos; dark mode; gestures and predictive back; edge-to-edge; accessibility (large text, contrast); modular "bento" layouts; restrained translucency.
- Sources: [Material 3 in Compose](https://developer.android.com/jetpack/compose/themes/material3), [Compose Material 3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3?hl=en), [Androidify sample](https://android-developers.googleblog.com/2025/05/androidify-building-delightful-ui-with-compose.html), [2026 trend roundup](https://muz.li/blog/whats-changing-in-mobile-app-design-ui-patterns-that-matter-in-2026/). Stage 0 checks m3.material.io directly before deciding.

## Principles

- **One tree, Geni only:** there are no local trees left to keep upstream's screens for, so a rebuilt screen replaces the upstream one everywhere. The upstream screen remains reachable only for what the new one doesn't cover yet (expert details, sources, notes).
- **Own code in the fork's packages:** new screens live in `app/src/geni` (package `app.familygem.geni.ui.*`). Upstream gets only a one-line `GENI-HOOK` where it starts the old screen, registered in `geni/UPSTREAM-HOOKS.md`.
- **Jetpack Compose** for new screens, mixed into the existing Views app (`ComposeView`) until the shell is replaced.
- **Same data and save path:** screens read the GEDCOM person and the Geni link snapshot through ViewModels, and save through `TreeUtil.save`, so the sync hook and upload queue stay unchanged.
- **Design before code:** every screen is mocked up and approved before it is built.
- **Original look goes:** the Appearance setting (Original / Geni Light / Geni Dark) becomes Light / Dark / System, with an optional "colors from wallpaper" (dynamic color) if the design keeps it.

## Stages

### 0. Design direction and design system

What comes out: an approved look for the app, as a design system plus mockups of the main screens, before any code.

1. **Research** (a short written brief):
   - read the Material 3 Expressive guidelines (m3.material.io): color, type, shape, motion, components;
   - look at how genealogy apps organize a person, a family and a tree: FamilySearch Tree, MyHeritage, Ancestry, the Geni site and app. This is for information architecture only; their look, names and assets are not copied;
   - decide the questions below.
2. **Decisions** (recorded in the stage plan):
   - Brand palette or dynamic color, or both (brand by default, wallpaper colors as an option).
   - Typography (a single variable font, or Roboto Flex as Expressive suggests) and the shape scale.
   - Navigation: a bottom bar (Home · Tree · People · Account) replacing the drawer, or a navigation rail on tablets.
   - Density and photo style (round portraits, cover photo on the profile).
   - Compose Material 3 1.5 (expressive APIs, beta now and probably stable by the time code starts) or 1.4 stable with Material 3 only.
3. **Design system in Claude Design:** a Design System artifact (claude.ai, type "Design System") with color tokens for light and dark, type scale, shapes, spacing, elevation, motion tokens, and the core components: person row, person card, fact row, section header, photo tile, the "synced / waiting / conflict" sync badges, empty states.
4. **Mockups in Claude Design:** a Design canvas artifact (type "Design") with artboards for Home, Profile, Edit profile, Family / relatives, Tree (restyled cards), People / search, Login and onboarding, Sync backlog, Settings, in light and dark, phone and tablet. You review them in the browser, comment on them, and the next pass changes them.
5. **Approval:** the mockups and tokens are frozen as v1. Later changes go through the design artifacts first.

### 1. Foundation

What comes out: Compose in the app, the theme built from the design tokens, and the test setup, with no visible change yet.

- Compose BOM and the Kotlin 2.3 Compose compiler plugin in `app/geni.gradle`; Material 3 version from stage 0.
- `TreekinTheme` (colors, type, shapes, motion) generated from the design system's tokens; light, dark, optional dynamic color; edge-to-edge and predictive back.
- Shared components from the design system as composables in `app.familygem.geni.ui.components`.
- Screenshot tests (Roborazzi or Paparazzi) for components and screens in light, dark, large font and RTL; Compose UI tests for flows.
- A `ScreenHost` that puts a composable screen into the existing `MainActivity` / fragment back stack, so the drawer and Back keep working until stage 7.
- `GeniAppearance` is reduced to the new setting, and the old palette overlays are removed after the last View screen that needs them is gone.

### 2. Home

Home is already fork code: port it to Compose and the new design first. It's the lowest-risk way to prove the stack.

- Sections from the mockup: upcoming birthdays and anniversaries, recent changes, sync status at a glance.
- Checked on the emulator: light/dark, large font, rotation, Russian.

### 3. Profile card

Replaces `ProfileActivity` for people of the tree.

- Header with portrait and cover, name, lifespan, sync badge; key facts; family (parents, partners, children) as person rows; photos; "On Geni" data (from the full-editing roadmap, stage 4).
- Actions: edit, add relative, photos, keep offline, delete (existing Geni flows reused).
- Notes, sources and other GEDCOM details open the upstream detail screens (kept).
- Hook: wherever upstream starts `ProfileActivity`, one line routes to the new screen.

### 4. Edit profile

Replaces `PersonEditorActivity` and the event screen for synced facts.

- Sections from the mockup (Names, Life events, About, Privacy), editing in place or in sheets; date entry reuses upstream's date parser and a new picker UI.
- Same rules as PR #34's `GeniProfileEditor` (only changed fields, validation, legacy records), which move from View binding to a ViewModel and keep their tests.
- The fields added by the full-editing roadmap (built first, in the old form) move here with their tests.

### 5. Family and adding relatives

Replaces the family card and the add-relative flow.

- Family view (couple, marriage and divorce, children), add parent / partner / child / sibling with the new editor, adoption and foster (full-editing stage 5).
- Linking an existing person and the multiple-marriages choice keep upstream's logic underneath.

### 6. People and search

Replaces the persons list.

- Search with filters (name, surname, place, years), sorted lists, alphabet index; optionally Geni's `profile/search` for people not yet on the device.

### 7. App shell and remaining screens

- New navigation shell (bottom bar or rail) replacing the drawer `MainActivity`; deep links from notifications.
- Login and onboarding, Geni account, Sync backlog and conflicts, Settings, About.
- After this the upstream drawer and the hidden trees list only remain as fallback code.

### 8. Tree view restyle

- The diagram engine stays. Cards, lines, selection and controls follow the design system (the existing `GeniDiagram` hooks), with zoom and center controls in the new style.
- Later option: draw cards with Compose inside the diagram's layout, if it stays fast with large trees.

## Cross-cutting

- **Accessibility:** TalkBack labels, 200% font scale, contrast, touch targets; part of each screen's checks.
- **Adaptive layouts:** list-detail on tablets and foldables (people list + profile side by side).
- **Translations:** new strings in English and Russian first. Upstream's translated strings are reused where the meaning is the same, and the other 37 languages get a translation pass before a public release.
- **Performance:** startup and scrolling of large trees measured with Macrobenchmark before and after each screen.
- **Upstream merges:** each replaced screen removes upstream code from the paths users take. Watch the weekly trial-merge CI; when merges stop being worth it, decide to stop merging.

## Order with the full-editing roadmap

Decided 2026-11-26: the full-editing roadmap is built first, all of it, in the current screens; the UI code (stages 1–8) comes after it. Stage 0 (design) runs now, and draws every field and screen the full-editing roadmap adds (date ranges, structured places, event descriptions, privacy, unknown sex, other languages, read-only Geni data, adoption and foster, photo tags, documents, videos, merge, follow, projects), so nothing is redesigned later.

Design so far, on the [Treekin UI directions](https://claude.ai/artifact/8aAr3fXFUMUbN1oikZEuGv) canvas: the page "Directions (A vs B)" compares A "Expressive" (Material 3 Expressive) with B "Quiet" (minimal). **A was chosen.** The page "Core screens (A)" has the first full set with every future field: profile, edit profile, event editor (date ranges, structured place, description), name in another language, family (adopted and foster children), add a child (relationship, email at creation, privacy), photo details and tags, photos / documents / videos, and the more-actions sheet (merge, follow, project). Still to design in stage 0: the design system artifact, Home, people and search, tree, login and onboarding, sync backlog and conflicts, settings, dark mode, tablet layouts.

Each stage gets its own detailed plan (tasks, tests, emulator checks) before work starts.
