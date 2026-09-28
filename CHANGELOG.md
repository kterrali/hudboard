# Changelog

All notable changes to HudBoard are documented in this file.

Format: [Semantic Versioning](https://semver.org/).

---

## [2.7.0] — 2026-09-28 — Public release prep

### Added
- Apache-2.0 LICENSE file
- Comprehensive README.md
- .github/workflows/build.yml for Hangar auto-publish
- Issue templates (`.github/ISSUE_TEMPLATE/bug_report.md`)
- `plugin.yml`: `authors: [Kterrali]`, real GitHub URL

---

## [2.6.5] — 2026-09-27 — Dialog Polish FR

### Changed
- All Paper Dialogs translated to French
- All in-game menus translated to French (`PanelEditMenu`, `DataPointEditorMenu`, `PlaceholderBrowser`)
- Simplified dialog hints — removed dev-only comments
- `lang.yml`: removed 25 obsolete entries (legacy modes, dev-only keys)
- `lang.yml`: added `gui.no-data-points` (was missing — caused "missing lang key" red error)
- `lang.yml`: fixed false `Paper 1.21.1` info string

---

## [2.6.4] — 2026-09-24 — Banner colors fixed

### Fixed
- Banner used MiniMessage `<gradient>` tags that converted to legacy `§x` hex sequences — some Paper consoles render these as gibberish instead of colors. Switched to **named colors only** (`§f`/`§a`/`§c`/`§8`/`§6`) which are supported everywhere since 1.7.

---

## [2.6.3] — 2026-09-24 — Banner MiniMessage + version sync

### Fixed
- Banner MiniMessage tags were printed as literal text. Now serialized through `LegacyComponentSerializer` so console renders them.
- `plugin.yml` version was hardcoded to `1.3.16` — now templated from `pom.xml`.

### Added
- Startup brand banner with status summary (PAPI / profiles / groups / manual keys)
- `/hudboard debug log <off|warning|info|fine|all>` — runtime log level toggle

---

## [2.6.2] — 2026-09-24 — Tab completer fix

### Fixed
- `/hudboard panel edit|info|undo|redo` tab completer suggested profile names instead of placed-panel names. Now suggests `test-1, test-2, test-3, test-4` for a profile named `test`.
- When admin types a profile name in a placed-panel command, a friendly hint lists the placed panels for that profile.

---

## [2.6.1] — 2026-09-24 — Command cleanup + silent break

### Removed
- Duplicate `panel <sub>` commands (`move`, `tp`, `rename`, `edit`, `remove`) — kept top-level shortcuts
- `panel save` — auto-save covers it
- Several obsolete `lang.yml` keys

### Changed
- `/hudboard help` updated for all current commands + Phase 2 features (`preview`, `group`, `manual`, `template`, `undo`, `redo`, `repair`)
- Tab completer updated for Phase 2 commands
- Player break-panel protection is now **fully silent** (no message, no sound)

---

## [2.5.0] — 2026-09-24 — Final edge cases

### Added
- `/hudboard panel repair <name>|all` — force-load chunk + re-spawn missing frames
- Horizontal auto-snap for wall profiles
- Pre-bake cache LRU cap (64 entries/panel, ~1MB max)
- `GradientCrossTileTest` (2 tests) — verified gradient cross-tile is **NOT** a bug

---

## [2.4.1] — 2026-09-24 — Auto-fit cross-tile

### Fixed
- Text auto-fit now works across tiles (`maxWidth = tilesW × 128 − 2×padding`)
- Added `dp.alignMode: "tile"|"panel"` — "panel" applies align across full panel width

---

## [2.4.0] — 2026-09-24 — Backup + multi-tile

### Added
- Auto-backup every `backup-interval-minutes` (default 30), 10 retained
- `InfoPanel.disabledWorlds` per-panel override
- Multi-tile chunk check (corners not just origin)
- `util/PlacementSnap.snap()` — auto-snap vertical + horizontal
- Pre-bake cache (O(1) hashmap lookup at 20Hz)

---

## [2.3.0] — 2026-09-23 — Undo/redo + templates + gradient helper

### Added
- `InfoPanelInstance.snapshotForUndo/undo/redo` (32-deep LRU)
- `/hudboard panel undo|redo <name>`
- `ADD_DP_TEMPLATES` (player/tps/balance/time/alert/blank)
- `/hudboard template list|show`
- `/hudboard preview <profile>` — render data points in chat
- Multi-action dialog with `Add gradient` button
- `data-points.bg` (MiniMessage opaque rect behind text)
- Auto-fit shrink font 1pt at a time
- `data-points.align` (left|center|right)
- `data-points.padding` (0..32)
- `MapPalette.matchColor` palette cache (~50k ops/sec saved)

---

## [2.1.1] — 2026-09-22 — Kerning fix

### Fixed
- `Kt errali` → `Kterrali` (TextLayout.getAdvance() per-char width + cache collision fix)

### Added
- `TextLayoutWidthTest` (3 tests) — regression guard

---

## [2.1.0] — 2026-09-22 — Visual polish

### Added
- 2× render buffer (256×256) with bilinear downsample to 128×128
- Drop shadow (+1, +1 black) + 8-direction outline
- Dialog Bold font (Font.BOLD) for clearer rendering

---

## [2.0.1] — 2026-09-21 — Manual placeholder YAML

### Added
- `com.hudboard.data.ManualKeysStorage` (YAML CRUD in `manual-keys.yml`)
- `/hudboard manual list|add|remove|clear`

---

## [2.0.0] — 2026-09-21 — Distance filter

### Added
- Per-player `distanceSquared` check against `panelViewDistance` (default 48)
- `tickGifFrames()` skips empty worlds

---

## [1.4.0] — 2026-09-20 — Custom placeholder groups + bulk-edit

### Added
- `PlaceholderGroup` + `PlaceholderGroupManager` (YAML `groups.yml`)
- `/hudboard group list|create|add|remove|delete`
- Bulk-edit `base-color` — `/hudboard panel bulk color` (or the in-game `Bulk base-color` button)

---

## [1.3.16] — 2026-09-19 — Silent console

### Changed
- `discoverPapiIdentifiers()` defaults to `logDetails=false` — verbose PAPI logs only via `/hudboard debug papi`

---

## [1.3.15] — 2026-09-19 — Pagination

### Added
- Placeholder browser pagination (36 per page, slot 50/52 prev/next)

---

## [1.3.10] — 2026-09-19 — PAPI eCloud lookup

### Added
- `PapiECloud.getInstalledExpansionNames()` with 5min cache
- `/hudboard debug papi` verbose diagnostic

---

## [1.3.9] — 2026-09-18 — Paper Dialog for manual placeholder

### Changed
- Manual placeholder entry now opens a Paper Dialog (was `ConversationFactory`)

---

## [1.3.8] — 2026-09-18 — "Disallowed chat character" crash fix

### Fixed
- All `p.sendMessage(Component.text("§..."))` rewritten to use `Component.text("plain text").color(NamedTextColor.X)` to avoid Paper's JSON rejection of `§` in chat strings

---

## [1.3.7] — 2026-09-17 — Shulker barrier system REMOVED

### Changed
- All panels walkable (no more Shulker obstruction)
- Orphan Shulker cleanup on `reattachFromWorld()`

---

## [1.3.6] — 2026-09-17 — Shulker system defensive fix

### Added
- `expectedAirCenter` Location field on `PanelTileItemFrame` for `spawnBarrier()`

---

[Earlier versions documented in `HUDBOARD_HANDOVER.md`]
