# Verification scripts

## `check_kotlin_js_consistency.py`

Static check that compares Kotlin source constants with the reference legacy JS
(`src/jsMain/resources/openpanzer/js/openpanzer-legacy-2.3.14.js`) and validates
`index.html`.

Run manually:

```bash
python scripts/check_kotlin_js_consistency.py
```

Or as part of the Gradle `check` task:

```bash
./gradlew check
```

Checks performed:
- `index.html` includes `js/openpanzer-legacy-2.3.14.js`.
- The legacy JS contains the expected top-level game symbols
  (`GameState`, `GameRules`, `Unit`, `Map`, `Scenario`, `Campaign`, `AI`, `UI`,
  `Render`, ...).
- `VERSION` and `UNIT_MAX_EXPERIENCE` match between Kotlin `Constants.kt` and
  legacy JS.
- Movement tables `movTableDry`, `movTableFrozen`, `movTableMud` are identical
  between Kotlin and legacy JS.

## `analyze_legacy.py`

Quick comparison of the two bundled legacy JS files
(`openpanzer-legacy-2.3.14.js` vs `openpanzer-legacy.js`).

Run manually:

```bash
python scripts/analyze_legacy.py
```

## `apply_scenario_wallpapers.py`

Owns the scenario wallpaper mapping: which "chapter" key art each campaign operation
opens on. A campaign is covered by 2-4 images grouped by theatre/season/phase, not one
image per scenario.

It writes both consumers from a single table in the script:

- `briefing.background` in each campaign JSON under
  `resources/campaigns/data/` - the scenario briefing backdrop;
- `resources/campaigns/wallpapers.js` - a generated `scenarioWallpapers` global
  (keyed by scenario XML file name) that the Scenario Selection dossier banner reads.

Masters live untracked in `art-src/wallpapers/`; the served copies are JPEG q88 under
`resources/ui/wallpapers/`, for the same reason the campaign theater banners are
(see `StartMenuCampaignData.setTheaterArt`).

```bash
# after generating new art, import it under its slug name, then encode + wire
python scripts/apply_scenario_wallpapers.py --import /path/to/generated/pngs
# re-encode and re-wire from the existing masters (idempotent)
python scripts/apply_scenario_wallpapers.py
```

Adding art for a campaign that has none yet means adding its slugs to `SOURCES` and its
scenario grouping to `ASSIGNMENTS`. Campaigns absent from `ASSIGNMENTS` keep the shared
staff-table default and the placeholder banner; as of this writing that is the Czech
Legion, the Soviet Counter-Offensive Campaign, Sim Pobedishi!, Spartacus, the Defeat of
Denikin, and the first three Red Partisans operations.

`scripts/verify/wallpapers-probe.mjs` checks the wiring end to end in a headless browser.

## `scripts/deploy/`

Ships the game and the multiplayer room server to the VPS.

- `deploy.sh` — build, pack, upload, install, restart, health-check. Run from the repository root
  in Git Bash. `--assets` also re-uploads `resources/` (~700 MB, only needed when art, maps or
  scenarios changed), `--bootstrap` runs the one-time host setup, `--reuse-archives` resumes an
  upload that dropped without repacking.
- `bootstrap-server.sh` — idempotent host setup, executed on the VPS by `--bootstrap`.
- `osada.service`, `nginx-osada.conf` — the deployed systemd unit and nginx site.

`docs/multiplayer-server-deployment.md` explains the layout, the environment variables and the
route to a domain with HTTPS.
