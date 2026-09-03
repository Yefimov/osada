# OSADA (project, formerly "openGeneral") / OpenPanzer — porting notes

## Documentation map

This file is the core base: project goal, architecture, build/test, conventions, porting gotchas.
Topic-specific docs live alongside the code they describe:

- **`tools/og-import/README.md`** — Open General → Panzer Marshal campaign import (entry point):
  status, the per-scenario data sources, the conversion pipeline, and critical deployment gotchas.
  Includes the **OG purchase / deployment model** (why some scenarios allow no buying at all).
- **`tools/og-import/SCENARIO_FORMAT_NOTES.md`** — exact binary layouts of OG `.xscn` / `.map` / efile.
- **`tools/og-import/DEFERRED.md`** — running TODO / shortcuts taken / "come back later" items for the import.
- **`tools/og-import/MISSING_FILES.md`** — historical asset-request record. Its status banner says
  no map/equipment file is currently missing; do not re-issue the old download requests.
  **Equipment CSVs are never requestable now** — `xeqp_to_csv.py` rebuilds any efile's export from
  its own `equip.xeqp`. The one user-assisted request still open is the OpenSuite controlled diff
  for the **scenario-instance** Depot flag (`DEFERRED.md` §2.10); the equipment-level `Supply unit`
  special was located on 2026-08-15.
- **`docs/leaders.md`** — how the unit-leader mechanic actually behaves today (two effective traits
  per leader, which classes can never get one, and the three ways the acquisition roll diverges
  from PM). Read before touching `Leaders.kt`.
- **`docs/osada_hero_leader_system_design.md`** — the design brief for replacing that mechanic with
  persistent hero characters, formations and progression.
- **`docs/hero-leader-implementation-phases.md`** — **the delivery plan for that brief: what is
  built, what is not, and which decisions are already settled.** **All five phases are done**
  (formation identity + save migration, acquisition, progression, UI, legendary/mortality/portraits/
  Hall of Fame). Read this before starting any hero/leader work — it exists so decisions are not
  re-litigated. Note it supersedes most of `docs/leaders.md`, which now describes only the legacy
  path that formation-less scenario units still take.
- **`docs/campaign-dialogue-and-consequences.md`** — authored pre-mission dialogue, choices and
  their effects.
- **`docs/design/scenario-events.md`** — the declarative `<events>` block a scenario XML may author:
  proximity/start triggers, gating on campaign flags, unit spawning/conversion and anchored map
  messages. **Read it before staging anything mid-battle** — it records why a third "neutral" side,
  a misused Stealth ability and a campaign-specific AI/combat exception were each rejected.
- **`docs/localization.md`** — the I18n runtime, stable-key conventions, bundle layout and the
  `scripts/check_translations.py` gate. `en` and `ru` are the actively maintained complete UI
  locales. `tly` is a frozen partial overlay with English fallback: preserve existing entries, but
  do not add or require new Talysh translations unless the user explicitly resumes that work.
  Campaign/briefing/unit content domains are planned, not populated.
- **`docs/tutorial.md`** — the Khalkhin Gol tutorial scenario and its scripted demonstration turns.
- **`tools/og-import/OG_ABILITY_AUDIT.md`** — the six independent layers that can modify an OG
  unit (class default / equipment special / scenario-unit property / leader / attachment / rule
  modifier), what OSADA imports from each, and the open questions. Read before adding any "unit
  ability" — they must not be collapsed into one flat set. **§7.1 is the bit→name table** for OG's
  `Special1..4`/`SpecialEx` (all 52 specials have a byte and bit, incl. `Supply unit` =
  `equip.xeqp` bit 62.4). **All 52 are IMPORTED**: `attr` carries `Special1..3`, `attr2` carries
  `Special4` and `attrEx` carries `SpecialEx` b0..2, verified record-for-record against OG's own
  binary by `tools/eqp-merge/verify_specials.py`. The open question is which imported bits a RULE
  reads — `docs/og-fidelity-plan.md` §I.2 has the wired / descriptive-only split, and is the
  authority over any older "not imported" sentence still standing in the audit's dated prose.
  **§AA (2026-08-28) is the current one-page register**, superseding §U.10's and §M's:
  **52 decoded, 51 named masks, 47 badged — and all 47 executed by a rule, 0 descriptive-only.** The count was 15 when this work began
  and 3 for two days; §AA wired the last three, and **none of them needed the system §Y said it was
  blocked on** — `Carrier Deploy` is a DEPLOYMENT permission rather than a hangar,
  `No Need Station` needed a scenario attribute rather than the unconfirmed `.xscn` byte at `+21`,
  and `Supply Unit` is a mobile Depot whose behaviour `DEFERRED.md` §2.10 had documented all along.
  Two of the three are inert on shipped content (no scenario authors `railtrans`, no efile sets
  `supply_ex`) and say so in their own KDoc. `EquipmentAbilityCatalogTest` now ASSERTS the tier is
  empty, so re-opening it is a deliberate act rather than a silent regression. §M is still the place
  that explains the three LEVELS (a bit imported / a rule reading it / the system existing). The
  binary layout they are read from is documented in `tools/og-import/xeqp_to_csv.py`.
- **`tools/icon-audit/out/CURATED_FINDINGS.md`** — the 2026-07-22 campaign icon audit: which
  class-fallback icons were replaced with curated OpenIcons codes, and what is still wrong. Read
  alongside `DEFERRED.md`'s "Equipment" section, which predates it.
- **`docs/design/`** — focused implementation designs. Some are completed historical designs
  (`efile-config.md`, `aa-interception.md`, `terrain-supply-and-initiative.md`, `attachments.md`,
  `hero-presentation.md`, `scenario-events.md`); others are live proposals linked from the player-comfort roadmap. Check
  each design's status and `DEFERRED.md` before treating it as pending work.
- **`docs/design/progressive-side-campaign-rework.md`** — the Russian Civil War campaigns played
  from the Red side. **All four are BUILT as of 2026-09-03** — §11 records `volarm`/`simpob`, §12
  `polsov`/`acampdf2` — and `StartMenuCampaignData.hiddenCampaignFiles` is now empty. Read §11.2
  before running `flip_sides.py` on any campaign: a flip on its own leaves the AI with no army, the
  human with no deployment zone (and therefore no way to buy), and the victory conditions written
  for the other side. §12.1 is the other half of that warning — `flip_sides.py` keeps `side` glued
  to the COUNTRY, and `side` is what indexes every extended victory condition (`holdvictory` vs
  `holdvictory1`, and the per-side pairs `retreatunits`/`killunits`/`msuunits`), so a flipped
  campaign can end up reading the enemy's objective numbers. §12.3 records the constraint that
  shapes any campaign whose hero changes allegiance: `Player.copy` carries the core's COUNTRY from
  the first scenario into every later one, so a campaign has exactly one human nation for its whole
  length. The two patchers `tools/og-import/rework_red_campaigns.py` and
  `rework_red_campaign_text.py` are what close all of this (both idempotent, both carrying a
  per-scenario decision table), and `scripts/verify/red-campaigns-probe.mjs` is what checks the
  result — 65 assertions across the four campaigns, in a real browser.
- **`FUTURE_IDEAS.md`** — historical product ideation with a current disposition table. Many old
  “future” items are now built. Use it to understand intent and separate product projects; use
  `docs/player-comfort-roadmap.md` and `DEFERRED.md` for actionable work.
- **`docs/multiplayer-server-deployment.md`** — the self-hosted VPS that serves the game and hosts
  the multiplayer rooms: what `multiplayer-server/` (Ktor/JVM) owns versus what stays on the host
  client, ports it may not take (Foundry VTT and imchargen share the box), `scripts/deploy/`, and
  the steps to add a domain and HTTPS later. Read it before touching multiplayer transport or
  deployment; it supersedes the Cloudflare Workers plan in
  `docs/OSADA_MULTIPLAYER_TECH_SPEC.md` §33.
- **`docs/player-comfort-roadmap.md`** — the approved modern QoL backlog and explicit non-goals
  (no Restart Turn, no hotkey-remapping screen, no bug-report bundle yet). It links the focused
  designs for resilient saves, action explanations/objectives, fixed hotkeys + F1 help, and optional
  star/skull side-identification markers. It is reconciled with `DEFERRED.md` and records which
  fidelity/import gaps constrain truthful UI copy. Read both before proposing or implementing
  player-comfort work so settled decisions are not reopened.
- **`docs/og-sources.md`** — **where Open General's truth actually lives, and the traps.** Read it
  before inferring anything about OG behaviour. It ranks the sources (the author's own site at
  `luis-guzman.com` beats the manual, which is v0.5 and describes an older engine; then the forum
  changelog; then `EFILE_NOKORP/equip.cfg`, the one installed copy that ships its explanatory
  comments), records the traps that have cost this project real time (OG's unit classes are NOT
  OSADA's — OG class 17 is the Destroyer; PM's `attr` bit 18 is not OG's; an efile that says nothing
  has not said "no"), and the method that keeps working. **This page exists because one rule was
  read wrongly three times before the right source was found.**
- **`docs/og-fidelity-plan.md`** — the itemised OG-gap work register (2026-08-18), audited against
  `Manual_OG-en.pdf` and the runtime. Separates **defects to fix universally** (overstrength bar
  rounding, naval port supply, nine advertised-but-inert leader traits, the stale bundled
  `manual.html`) from **new ruleset keys**, from **subsystems that must exist before any key**, and
  proposes the "Open General Fidelity — partial" profile plus which campaigns should simply stay on
  Author's Vision. **Its §0 settles two recurring questions:** OSADA already has suppression (as
  `GameUnit.hits`, not `Cell.kt`'s dead `defSuppress`/`atkSuppress`) and OG's suppression model
  stays unported; AI stances are blocked until the P3 benchmark exists and must never ship labelled
  "OG AI". Read it with `DEFERRED.md`'s framing note before filing any new fidelity work.
  **Its §G records what was BUILT on 2026-08-18** — every §A defect, all nine advertised-but-inert
  leader traits, five new ruleset keys (schema 4: `heavy_move_fire`, `snow_fuel`,
  `support_fire_range_falloff`, `dry_unit_penalties`, `minefields`) and the complete land-minefield
  subsystem including the importer change and the 19 deployed scenarios whose authored fields were
  recovered. Read §G before assuming any item in that document is still open; the rest of the file
  is still a proposal register. **§M (2026-08-26) is the current status register** — read it before
  claiming any OG system or ability is implemented: it separates a bit being imported from a rule
  reading it from the system existing at all, and lists air missions, rail transport, carrier
  capacity, extended naval, naval mines, barrage, triggers and air ZOC as absent. **§N** wired three
  more abilities and the efile's `blow_any_terrain`; **§O** cracked OG's per-scenario option
  bitfield (`SCENARIO_FORMAT_NOTES.md` has the byte/bit table), imported it into 397 scenario XMLs,
  and gated Build/Blow/Repair and Extended LOS on each scenario's own switches. §O.2 also measures
  what the shipped content actually authors — **air missions: zero scenarios; barrage: 78%** — so
  check it before ranking any absent system. **§R built Barrage** (schema 7): its gate was never a
  special bit but the record's Bomber Size (`EquipmentData.bombsize`), which is also why §L.6's
  "blocked" ruling on it is superseded. **§T built OG's two line-of-fire options** (`TrueDLOF`,
  `UnitsBlockDLOF`) and with them retired the profile's `authored_options` gap entry — the only
  way an entry may leave that list.
  **§U (2026-08-27) worked §M's own recommended order end to end** and is the section to read
  before starting any OG-fidelity work: extended naval (§9.6, schema 9) and Air ZOC (§6.30, schema
  10) are built; seven more `attr` bits are wired; **the railroad-station flag was located**
  (`.xscn` @13 bit 5) and 915 authored stations across 143 scenarios imported, so a sapper now
  builds all five of OG's facilities; carrier `HangarCap` is deployed into the equipment data. Nine
  authored scenario switches are read and one is imported-but-unread (`airmissions`, which **no**
  shipped scenario sets). Its §U.4 records that the Depot correlation search is EXHAUSTED — do not
  re-run it — and its §U.8 records a located-but-unconfirmed candidate for the rail-transport pool
  that must not be built on. `Supply Unit` (0 records) and air missions (0 scenarios) are
  deliberately unbuilt, with the measurement in §U.9.
  **§V finished Build and Repair** — all eight sub-rules — and is worth reading for one reason
  beyond its own content: four items three sections of that document had called undecodable were
  documented all along in **`EFILE_NOKORP/equip.cfg`**, one of the two installed copies that ship
  their explanatory comments (`OPENTXT_SAMPLE/equip.cfg` is the other). **Grep those files for an
  `equip.cfg` key before inferring anything about it — and grep them at the moment you write the
  key's name down, including when what you are writing is a list of keys you believe are
  undocumented. §Z is what it costs not to.** It gives the column order of `build_cost`/`build_turn`/`repair_turn` (Bridge, Airport, Port,
  Fort, Station), the `build_mask`/`blow_mask` code table, and `blow_any_terrain`'s real reach
  (*"any terrain except Ocean, Impas.River, River and Shallow Sea"* — the earlier inference wrongly
  excluded high ground). **§W kept mining the same file**: `build_start_ex`, `build_terr_ex`,
  `build_mask`/`blow_mask`, and all four of `Evade`'s keys (`class_evade`, `zoc_evade`,
  `evade_special`) are documented there, so Build and Repair now reads every `equip.cfg` key OG
  defines for it and `Evade` is a rule rather than a badge. §W.4 records the one reading it had to
  choose — the ability grants evade and `class_evade` sets the odds, not the reverse — and §W.6 is
  the current short list of what is still display-only. **§W.7 is the correction that followed, and
  it is the one to read for method**: the reading was confirmed by OpenGen's own forum changelog,
  one named-as-unsettled sentence was overturned (submarines evade by CLASS, with no bit — proven by
  two efiles that set a submarine `class_evade` and grant the special to none of their submarines),
  and an exclusion nobody had was added verbatim from the 0.70.0 changelog. **The author publishes OG's
  current rules himself, and that is the primary source this project was missing** —
  `luis-guzman.com`'s combat, efile-specials, config-file and changelog pages, plus
  `forum.opengeneral.pl` topic 1286. **Search those before inferring engine behaviour the manual
  does not state**: §W.7 records three successive readings of one rule, the first two built from
  the manual and from `equip.cfg` comments and both wrong, the third from the author's own combat
  page and correct. **§X is that source applied to the whole outstanding queue**: it corrected four
  shipped rules (the destroyer escort's four missing narrowings, `Air Transportable`'s embark
  alternative, `repair_turn`'s column, and blowing clear ground), wired six abilities, and found
  that naval minefields were largely built already — OSADA's mines never had a terrain restriction,
  so what was actually wrong was that `AirDropMines` gates LAND mines from the air rather than all
  of them. **§Z and §AA (2026-08-28) are the two to read before believing any "unknown" in this
  document.** It found that fourteen `equip.cfg` keys §L.11 called undocumented are documented in
  full by the very file §V had just mined, and that three mechanics §Y filed as *blocked on a model
  OSADA does not have* — the Depot, trigger hexes, carrier hangars — are fully specified on the
  author's own pages. **There are TWO commented `equip.cfg` copies** (`OPENTXT_SAMPLE` as well as
  `EFILE_NOKORP`) and `Manual_OSuite-Scenario.pdf` documents the trigger parameters; both are now in
  `docs/og-sources.md`. §Z built five things from that re-read: `exp_unit_cap`/`exp_bar_factor`
  with a bar count clamped in ONE place (`rules/UnitExperience` — GCE's cap of 5000 would
  otherwise have given units fifty bars), `reinf_move`, `allow_pontoon_ex` (**a pontoon crossing now
  costs road cost + 1 in nine of ten efiles** — the biggest behaviour change), graded 1–3 mine
  casualties, and `Cut LOS` on the line of SIGHT, reversing §L.5's narrowing because
  `recomputeSpotting()` already solved the objection it rested on. **§AA then wired the last three abilities and found a
  complete OG combat formula nobody had read**: `critical_hit`, which **`eqp-lxf` sets to 2** and
  which sinks a ship outright — schema 11, `naval_critical_hits`, off by default because no shipped
  campaign was balanced with it running. It also DECODED `elite_cost` (GCE charges a third more for
  replacements and was being billed OSADA's flat rate), `upgrade_ldr` and `noldr_auxunits`, found
  `force_weather` already satisfied and `sh_pg1` not applicable. **"Read" was the wrong word for
  `elite_cost` and stayed wrong for three days: nothing consumed it until §AN.1, and with green
  replacements shipping beside it the unread key made GCE's cheap replacement strictly better than
  its expensive one.** **§Y is the consolidated register
  of what is left**, rewritten twice on 2026-08-28: nothing on it is blocked on not knowing what OG
  does — every entry names a byte offset (Depot flag, dirt airfields, trigger offsets, the rail pool
  at `+21` that must not be built on), an unpublished formula, or hours. The largest thing left is
  **green replacements**, which no list had ever named. Read §Y before starting any OG-fidelity
  work — it says what would unblock each item. **§Y.3's four entries are all BUILT (§AK) and two of
  them were then corrected (§AN); read §AN before treating any line of §Y.3 as open.**
  **§AN (2026-08-31) is the current section**, and three of its five parts are corrections rather
  than new work: `elite_cost` unread made one replacement action pointless (§AN.1); **`ground_carrier`
  is OG's CONTAINER key and not the aircraft hangar** — its own bit 8 says *"allow land units to
  enter naval-class carriers out of port"*, `hangarCap` is on 13 unit classes including 54
  Fortifications, and bit 2 is a garrison supporting the battle from inside its bunker or ship
  (§AN.2); **Fronts and Factions are a built mechanic that needed no mask** — OpenSuite resolves them
  into a per-scenario `.buy4` purchase whitelist, now imported (§AN.3); and **58 of the 502 deployed
  scenarios had been patched from a different battle's binary**, because every `add_*.py` patcher
  resolved a colliding scenario filename to the first efile alphabetically (§AN.4). Scenario options
  now stand at 28 of 37 deployed, and §AN.6 gives each of the nine remaining a disposition.
- **`docs/og-import-rules-backlog.md`** — **the 2026-09-01 register of authored OG fields and the
  rules that read them, worked end to end the same day.** Read it before touching any of: the
  purchase cap (`rules/PurchaseCap`), Make Core enrolment (`CoreUnitListOperations`), the two
  authored leader attributes, Fronts and Factions (`rules/FrontsAndFactions` — the runtime MASKS,
  which compose with `ScenarioPurchaseList`'s `.buy4` list rather than replacing it), the scenario
  author's AI orders (`rules/AiOrders`), authored attachments, or the prototype time frame. It states
  the DECISIONS as well as the code — that the author supplies AI constraints while OSADA's planner
  keeps command; that an authored attachment's lifetime follows the unit because nothing published
  settles it; that zero is a wildcard on both sides of both F/F masks. **Three decoded fields are
  deliberately unbuilt and it says why** (AI stance, blocked by `og-fidelity-plan.md` §0's P3
  benchmark; avoid-auto-hold, which has nothing to suppress here; leader identity, one controlled
  diff short). **Two rows are blocked on a licence, not on code**: OG's per-equipment sound and
  per-scenario music are imported and read, but `README/read_me_first.html` forbids redistributing
  the audio, so `ui/OgSoundLibrary` and `ui/ScenarioMusic` are gated on a manifest this repository
  does not ship and both fall back to the previous behaviour. **Do not copy OG audio into the
  repository** until that permission exists.
- **`docs/design/onboarding-and-content-localization.md`** — unapproved follow-ups found while
  reconciling `FUTURE_IDEAS.md`: measure tutorial/help gaps and migrate authored campaign, briefing
  and unit content into I18n domains without duplicating the existing Khalkhin Gol tutorial.
- **`docs/osada_mobile_browser_ux_technical_spec_en.md`** — implemented adaptive input/layout layer,
  touch combat confirmation and replayable gesture onboarding. Read before changing mobile UI.
- **`docs/OSADA_MULTIPLAYER_TECH_SPEC.md`** — multiplayer behavior and architecture, including the
  implemented host-authoritative MVP and remaining modes/limitations. Its old Cloudflare deployment
  plan is superseded by `docs/multiplayer-server-deployment.md`.
- **`src/jsMain/resources/manual.html`** — bundled full manual. It is inherited, English-only and
  currently ahead of the Kotlin shortcut dispatcher; audit it against current OSADA rules before
  translating. External/local OG and PM manual references are recorded in
  `docs/design/keyboard-shortcuts-and-help.md`.
- **`scripts/README.md`** — repo helper scripts (not part of the OG import pipeline).

**A real OG install is available at `C:\Games\Open General`** (`EFILE_LXF`, `EFILE_ATOMIC`,
`EFILE_KAISER`, …), each efile holding `SCENARIO/*.xscn`, `*.xcam`, optional `*.buy4`, `TerrainEx.txt`
and a `SAVE/` folder of `.xcsv` saves. It is the ground truth for any "what does OG actually do?"
question — prefer measuring it over reasoning from the port. OpenSuite map reports
(`SCENARIO/<code>_map_data.txt`) are the most useful cross-check: they state per-player Victory /
Supply / Ports / Deploy / AirPorts / Owned counts in plain text.

Two local OG references serve different purposes and should not be confused:

- **`C:\Games\Open General`** is the installed game data: campaigns, scenarios, efiles, OpenSuite
  exports, the global `OpenIcons.csv` metadata catalogue and `OpenIcons.keep`.

  **`OpenIcons.keep` is OG's icon-code alias table** (`*Fake;Real`, 2,723 rows). An efile may name an
  icon by a code that matches no file; OG resolves it here first, and only a code in *neither*
  `OpenIcons/` nor this table is genuinely missing. Never conclude "icon X is missing" without
  checking it — `convert_icons.py` not reading it left 1,885 units drawing the wrong subject, from
  Soviet Cossacks shown as a Japanese armoured car to paratroopers in winter camo in June
  (`DEFERRED.md` §7.34).
- **`C:\dev\hexagonal_wargames_source\opengeneral-code-r1715\`** is an Open General **distribution**
  snapshot — **not source code**, despite the directory name. Verified 2026-07-25: it holds
  `OpenIcons/` (19,707 PNG), `installer/`, `ccpack/`, `pg2pack/`, `tools/`, `redist/`, `opendat/` —
  7 `.txt`, 4 `.exe`, 7 `.dll`, and no `.c`/`.cpp`/`.pas`/`.h` anywhere. Its `OpenIcons/` directory
  is the bitmap source used when converting OG 3x3 icons into the browser port's 1x9 strips. Kept
  outside the repo (~1.3 GB, no runtime effect); og-import/icon-audit tooling and
  `scripts/check_kotlin_js_consistency.py` reference it there by absolute path.

  **Consequence: OG runtime behaviour cannot be read from source on this machine.** Questions of the
  form "what does OG actually *do* when X" can only be answered by measuring the installed data,
  by controlled OpenSuite diffs, or from OG documentation — never by reading its code. Only
  **Panzer Marshal** has readable source here (`openpanzer.js`), and it is a different engine.

## Open General import (active workstream)

Importing OG campaigns (USSR / "progressive-side": Red Army, Spanish Republic, etc.) into this port.
This has advanced far beyond the original `bn9s00` vertical slice: multiple efiles, campaigns and
standalone scenarios are deployed. Counts change as imports continue, so use the status banner in
**`tools/og-import/README.md`** and the live register rather than copying an old number here. Two
facts worth knowing even outside that workstream:

- **The served resource tree is `src/jsMain/resources/resources/`** (served at `/resources/`); the
  `Panzer_Marshal_3.2.14_Browser/...` tree (now at `C:\dev\hexagonal_wargames_source\`) is only a
  reference copy with no runtime effect.
- After changing resources run `./gradlew jsProcessResources`; restart `jsBrowserDevelopmentRun` for any
  Kotlin change (it does not watch Kotlin), or hard-reload the browser for resource-only changes.

## Project goal

Port the browser version of the turn-based strategy game **Open Panzer / Panzer Marshal** (Panzer General 2 clone) from the original JavaScript codebase to **Kotlin/JS**, preserving gameplay logic, UI behaviour and save-file compatibility.

## What "Kotlin/JS hybrid" means

- **All game logic and UI code is written in Kotlin** (`src/jsMain/kotlin/...`).
- Gradle compiles Kotlin to JavaScript (`osada.js`, from `rootProject.name`) that runs in the browser.
- **HTML, CSS and binary assets stay exactly as in the original JS version** (`src/jsMain/resources/`). The Kotlin code manipulates the same DOM nodes, uses the same image/sound paths and loads the same scenario/campaign JS lists.
- So the result is not a rewrite of the rendering layer — it is the original UI surface driven by Kotlin-generated JS instead of the original `openpanzer.js`.

## Reference version

Primary reference is **Panzer Marshal 3.2.14** (browser build), kept outside the repo at
`C:\dev\hexagonal_wargames_source\` (see above):

- `C:\dev\hexagonal_wargames_source\Panzer_Marshal_3.2.14_Browser\panzermarshal.com\js\openpanzer.js`
- `C:\dev\hexagonal_wargames_source\Panzer_Marshal_3.2.14_Browser\panzermarshal.com\index.html`
- `C:\dev\hexagonal_wargames_source\Panzer_Marshal_3.2.14_Browser\panzermarshal.com\resources\`

If 3.2.14 is missing assets (campaigns, scenarios, equipment JSONs), fall back to **3.2.10 (Android APK)**:

- `C:\dev\hexagonal_wargames_source\Panzer+Marshal_3.2.10_Android\assets\openpanzer\resources\`

The merged runtime resources live under:

- `src/jsMain/resources/resources/`
- `src/jsMain/resources/css/`
- `src/jsMain/resources/index.html`

The two source archives live outside the repo entirely (see above), so `build.gradle.kts` no
longer needs an exclude for them.

## Current state

The port is complete and fully operational. The codebase has been through a **SOLID refactor** (see Architecture below); god-classes have been decomposed into focused collaborators without changing behavior.

### Save-file compatibility

Save files are plain JSON with top-level keys `scenario`, `players`, `campaign`. The authored
scenario travels in two blocks: `scenario.options` (OG's 27 per-scenario Game-Settings switches,
under their own XML attribute names — an unauthored option writes no key, so absence stays absence)
and the per-player `purchaseCap` / `purchaseList` / `frontFactionSlots`. A save with no `options`
key predates them and is completed from the scenario XML by `scenario/AuthoredOptionsBackfill`;
`scenario/AuthoredScenarioOptions` is the single attribute-to-field table all four consumers share
(XML parser, save writer, save reader, `Scenario.copy`) and is where a new option must be added. The Bizerte and
Operation Uranus save fixtures used for integration tests (load-save round-trip, dossier stats,
victory checks) are embedded as Kotlin string constants — `BIZERTE_SAVE_JSON` in
`src/jsTest/kotlin/org/osada/GameStateBizerteFixture.kt` and `OPERATION_URANUS_SAVE_JSON` in
`GameStateOperationUranusFixture.kt` — generated from a manual save file, not loaded from a
resource at test time. There is no live save file on disk; regenerate the const from a fresh
manual save if the fixture needs updating.

## Architecture — SOLID refactor (complete)

The original port followed `openpanzer.js` structure closely, which produced several
god-objects. These have been decomposed into single-responsibility units **without
changing behavior** (refactor only). God-classes that are `@JsExport` keep a thin facade
preserving their exact public surface (and save-file format / DOM ids); non-exported
god-objects are split outright with call sites kept working via a delegating facade.

- **`rules/GameRules.kt`** (was a ~1055-line `object`) is now a thin delegating facade over
  focused objects: `CombatResolver`, `MovementRules`, `SupplyRules`, `CostCalculator`,
  `UnitPredicates`, `HexGeometry`, and `Dice`. New code should call the specific object;
  `GameRules.*` still works (and `window.GameRules` is still exposed for safety, though the
  HTML never references it). Combat behavior is locked by `CombatTest`/`GameRulesLogicTest`;
  `CostCalculator` and `MovementRules.getShortestPath` are covered by `RulesDecompositionTest`.
- **`GameState.kt`** (`@JsExport`) is a thin facade over `GameStateSerializer` (model→JSON,
  pure), `GameStateDeserializer` (JSON→leaf models, pure), `GameStateRestore` (rebuild the
  live game graph + `applySettings`), and `GameStatePersistence` (localStorage/file I/O; cloud save
  was removed).
  The save round-trip is guarded by `GameStateIntegrationTest` (Bizerte + Operation Uranus).
- **`ui/Render.kt`** is a thin coordinator over a shared `RenderContext` (canvases, images,
  geometry, `cellToScreen`/`screenToCell`/`drawHex`/lifecycle) plus `UnitRenderer`,
  `OverlayRenderer`, `CursorRenderer`, `MapRenderer`, and `MapAnimator`.
- **`ui/UIBuilder.kt`** (was a ~1262-line `object`) is a thin facade over per-screen builders:
  `StartMenuBuilder`, `GameStateMenuBuilder`, `MainMenuBuilder`, `EquipmentWindowBuilder`,
  `UnitInfoBuilder`, `UILayout`, `MessageDialogs`, `TooltipBuilder`, and `DossierBuilder`.
- **`model/GameMap.kt`** (`@JsExport`) is a thin facade over `UnitOperations`
  (mount/embark/upgrade/disband/deploy/reinforce/resupply), `CombatApplication`
  (`attackUnit`/retreat/capture), and `MoveExecutor` (`moveUnit`/`undoLastMove`).
  Grid state and undo state stay on `GameMap`.
- **`ui/UI.kt`** (`@JsExport`) is a thin facade over `AnimationOrchestrator` (move/attack
  animation sequences), `UnitInfoPanel` (unit/equipment info display and unit-context actions),
  `EquipmentWindowController` (equipment window population and buy/upgrade/sell),
  `MenuController` (start-menu and main-menu button handlers, status bar, strategic zoom),
  and `MapInputController` (cursor state and map mouse events).
- **`ai/AI.kt`** has had its companion object removed (constants and result data classes moved
  to package-level `internal` declarations in `AIScoring.kt`, `AIUnit` moved to `AIUnit.kt`),
  and `buildActions` / `evaluatePosition` broken into named helpers
  (`purchaseAndDeployPhase`, `scoreTerrain`, `scoreVictoryCapture`, `scoreAdjacent`).

> Smoke-test caveat: at the start menu with empty localStorage no scenario is loaded, so
> `Render.cacheImages` runs with a null map and logs `console.error("Render: failed to load
> terrain image")` (empty terrain `src`). `verifyProductionSmokeTest` counts any `console.error`
> as a failure, so a fresh first load reports this one pre-existing error. It is data-driven
> (verbatim from the original code path), not a refactor regression. Fixing it (e.g. skipping
> the terrain load when `map == null`) would be a behavior change — flagged, not yet applied.

## Testing strategy

Recommended approach: **TDD + integration fixtures**.

- **Unit tests in `src/jsTest/kotlin/org/osada/`** for pure Kotlin helpers (`Constants`, `GameRules`, `Equipment`, `UIBuilder` DOM helpers).
- **DOM tests** create the required HTML containers in `@BeforeTest` because headless Karma has no full `index.html`.
- **Combat-logic tests** (`CombatTest`) build mock equipment via `Equipment.putEquipment(id, EquipmentData())`, place two `GameUnit`s on a small `GameMap`, and assert **exact** `kills`/`losses`/`defcanfire`/experience from `GameRules.calculateAttackResults(..., useRandom = true)`. `useRandom = true` is the deterministic expected-value path, so results are reproducible. Hand-compute expected values from `openpanzer.js` (`calculateAttackResults` ~line 2333, `f`/`attackValue` ~line 2029) rather than from the Kotlin — the integration tests do **not** check damage numbers, so this is the only guard against combat-formula regressions.
- **Integration tests** can load a save file and verify:
  - `GameState.loadSaveGame()` / `saveGame()` round-trip.
  - Dossier totals match expected values.
  - Victory conditions evaluate correctly after a known turn.
- Static check: `scripts/check_kotlin_js_consistency.py` verifies that key constants (`VERSION`, `UNIT_MAX_EXPERIENCE`, movement tables) match the 3.2.14 JS reference.
- Production smoke test: `./gradlew verifyProductionSmokeTest` builds the production distribution, serves it locally, loads `index.html` in headless Chrome via Puppeteer, and asserts that the start menu is built and no runtime JS errors occur.

Run everything with:

```bash
JAVA_HOME=/c/Users/Илья/.jdks/temurin-25.0.3 ./gradlew check
```

Run the production smoke test with:

```bash
JAVA_HOME=/c/Users/Илья/.jdks/temurin-25.0.3 ./gradlew verifyProductionSmokeTest
```

## Mobile browser experience (touch layer)

Built against `docs/osada_mobile_browser_ux_technical_spec_en.md`. It is an adaptive **input and
layout layer over the existing client** — no rule, AI, scenario-format or save-format changes, and
the desktop experience is untouched. Two foundations carry everything else; touch the foundations
rather than adding a second copy of their logic:

- **Gesture system** — `ui/input/`. `MapGestureReducer` is a *pure* state machine (tap / long press
  / pan / pinch) with no DOM and no game-model dependency, so the regression-prone parts ("a pan
  must never become a tap", "the finger left after a pinch is not a new gesture") are unit-tested in
  `MapGestureReducerTest`. `MapPointerController` is the only DOM adapter: Pointer Events, pointer
  capture, the long-press timer, rAF. `MapInputController` is now a facade over it plus the desktop
  Ctrl+wheel zoom and `MapClickHandler`. Mouse, pen and touch share one pipeline — there is no
  separate touch path, and no `hasTouch` branch that skips dragging.
- **Viewport/layout system** — `ViewportMetrics` is the ONE source of viewport truth (Visual
  Viewport, safe-area insets, measured top-bar/bottom-dock rectangles). `MobileLayoutController`
  turns capability media queries + measured size into semantic `<body>` classes
  (`osada-layout-phone`, `osada-input-coarse`, …) and coalesces every resize trigger into one rAF.
  `resolveLayoutMode` in `LayoutMode.kt` is pure and covered by `LayoutModeTest` for the spec's
  whole viewport matrix.

Consequences worth knowing before editing UI code:

- **Do not reintroduce a hardcoded top-bar height or `window.innerWidth/innerHeight` for layout.**
  `RenderContext` no longer owns HUD geometry; in phone/tablet modes CSS owns `#game`'s box and
  `positionForMobileShell` deliberately clears the inline width/height/left/top so the stylesheet
  can win. `MobileLayoutController.cssOwnsMapViewport` is the flag.
- **`css/mobile.css` loads after `osada-theme.css`** and is driven by the body classes; media
  queries in it are only a first-paint fallback. Never set `display` there on an element whose
  visibility is toggled inline by `makeVisible`/`makeHidden` (`#unit-context`, `#osadaForecast`,
  `#equipment`) — that fight is unwinnable in both directions.
- **Kotlin publishes `--osada-dock-h` / `--osada-vv-h`** from `ViewportMetricsService` because CSS
  cannot measure them; the map's bottom edge tracks the real dock height.
- **Pinch runs preview → commit** (`MapZoomPreview` → `MapZoom.applyLevel`). Preview only rescales
  the wrapper and corrects scroll; the single heavy refresh happens once, at commit.
- **Touch combat is preview-then-confirm** (`TargetPreviewController`): the first tap on an enemy
  shows the forecast, the attack needs a second tap or the Attack button, and eligibility is
  re-checked at confirm time. Preview is local UI state — never serialized, never sent to a peer.
- **`uiSettings.hasTouch` is now only a derived compatibility flag** for renderers; it is no longer
  restored from saves (it describes the device, not the save) and no longer decides layout.
- Verify with `./gradlew verifyMobileSmokeTest` (667×375, coarse pointer, headless Chrome). It is
  separate from `verifyProductionSmokeTest` on purpose — that one is the desktop-regression gate.
  Chrome emulation is not evidence for iOS Safari; real-device results belong in the PR.

## Key conventions

- Keep original JS variable/function names when they are referenced by the original HTML/CSS or by save files.
- Use dynamic JS interop (`js()`, `asDynamic()`) only where the original code relies on loose typing (globals `game`, `campaignlist`, `scenariolist`, DOM properties).
- Do not run `git commit/push/reset/rebase` unless explicitly asked.
- Resource paths in Kotlin match the original: `resources/...`, `index.html`. CSS: `css/base.css` (legacy rules still actually in use — extracted from the original `css/ui.css`, see `scripts/verify/ui-css-audit.mjs`) + `css/osada-theme.css` (the OSADA redesign, loads after and overrides base.css). `css/ui.css` itself is no longer linked from `index.html` — kept on disk only as an extraction reference, do not add new rules to it.

## Porting gotchas (recurring bug classes)

These mistranslations from the minified JS have each caused real bugs. Check for them when porting or reviewing:

- **`x >> 0` is a truncation, not a shift.** In JS `a / 254 + a % 254 >> 0` means `floor(...)` (cast to int), NOT shift-right-by-one. Porting it as `shr 1` halves the value. JS `>> 0` / `| 0` ⇒ Kotlin `.toInt()` on a Double, or integer arithmetic.
- **JS `Math.round` rounds half **up** (ties toward +∞); `kotlin.math.round` rounds half to **even** (banker's).** Use `Double.roundToInt()` (ties toward +∞) to match JS. This matters wherever fractional results land on `x.5` — e.g. combat damage `(q+1)/2`, resupply/reinforce terrain divisions.
- **Integer vs float division.** JS `/` is always float; Kotlin `Int / Int` truncates each step. When the JS keeps a value fractional until a final round (e.g. `isRuggedDefense`, objective scores), force `Double` arithmetic.
- **String/Int through `dynamic`/`js()` calls.** A `js("game.ui.fn")(stringKey)` passes a JS string into a Kotlin `Int` param unchanged; reading it back with `as? Int` then silently returns `null`. JS object keys coerce string↔number, Kotlin does not — normalize at the boundary (`x.toString().toIntOrNull()`).
- **Cross-indexing / attacker↔defender swaps.** JS combat uses terse single letters (`w/y/H/C`, `M/F`, `s/n`); it is easy to index a unit's stat by its *own* target type instead of the *opponent's*. The attacker's attack/defense are selected by the **defender's** target type and vice-versa.
- **JS `&&`/`||` comma-expressions with dead assignments.** Minified code reassigns a variable inside a `&&` that is never read again (e.g. `b && (b = owner==side, p = bonus)` applies `p` unconditionally). Don't port the dead assignment as a real guard.
- **Control-flow placement of `continue`/`break`.** JS `continue` inside one branch of an `if/else` is not the same as a `return@forEach` after the whole block (see `getUnitAttackCells`).
- **Inverted boolean conditions.** `10 > strength` is `strength < 10`; spotting checks need `!enemyVisible || slotIsNull` (a destination must be empty/unspotted). Several early ports inverted these.
- **Stubs left as empty blocks / "omitted for brevity".** Grep for empty `if {}` bodies and such comments — `attackValue` and the mounted-infantry combat case were both silently incomplete.
- **Kotlin extension functions are not callable on `dynamic`.** A call like `(someVal as? dynamic).coerceAtMost(n)` silently returns `null` instead of calling the extension. Cast to the concrete type first (`someVal as? Int`), then call the Kotlin function.
- **`.asDynamic()` on a `dynamic` receiver emits a JS call to a non-existent `asDynamic` method.** Assigning a `js(...)` result to a `dynamic` local and then working with the local is always safe; calling `.asDynamic()` on it will throw at runtime.
- **Kotlin/JS IR mangles property names in `JSON.stringify` output** (e.g. `airMode_1`, `isAI_1`). Code that later reads the plain key names (`data.airMode`, `data.isAI`) will get `undefined`. Fix with manual serialization that emits stable key names, or annotate with `@JsName`.
- **Inline `onclick` strings in dynamically built HTML cannot safely reference Kotlin globals.** Kotlin/JS IR does not guarantee that `window.SomeName` is populated at the time the handler fires. Build DOM elements in Kotlin and attach typed `onclick` listeners instead of injecting strings.

## Combat / rules audit (deep pass vs `openpanzer.js`)

`GameRules.calculateAttackResults` / `attackValue` had multiple porting bugs, all **fixed** (locked by `CombatTest`):
- Attack/defense stats were self-indexed instead of cross-indexed (a tank attacking infantry used `hardatk` instead of `softatk`).
- `attackValue` never subtracted the defender's defense (`p -= r`), conflated the compressed net-attack with the hit threshold, and omitted the artillery/bomber/fortification/naval `target=19` rule and the `overwhelmingAttack`/`resilience` leaders ("omitted for brevity" stub).
- Final damage used `kotlin.math.round` (banker's) instead of `roundToInt`/JS half-up — off-by-one on `x.5` damage.
- Close combat infantry-vs-infantry set the attacker's *attack* to `closedef` instead of its *defense*.
- Mounted non-surprised infantry didn't switch to its base stats (empty stub).
- Submarine target cases and the double-entrenchment bonus (used attacker's entrenchment instead of defender's) were wrong.
- `isRuggedDefense` dropped the `tenaciousDefense` leaders and truncated with integer division.

Movement / costs / AI:
- `GameRules.getRing` built horizontal spans and dropped same-row hex neighbours (a radius-1 ring ≠ the 6 adjacent cells) — broke move/attack range in some directions. Rewrote as a faithful port of JS `s`. Locked by `GameRulesLogicTest` (ring == adjacency, ring == distance disk).
- `getReinforcementDeployPositions` used `canPassInto` (allows a friendly-occupied hex) instead of strict `canMoveInto`.
- `GameUnit.move` used `shr 1` for the ZOC cost (`>> 0` is truncation) — halved movement/fuel cost through enemy ZOC.
- `canReinforce` over-strength check was inverted (`strength >= 10` should be `< 10`).
- `getResupplyValue`/`getReinforceValue` truncated instead of rounding and over-clamped to a minimum of 1.
- `calculateUpgradeCosts` ignored the `transportEqid == -1` (drop-transport) old-cost case.
- AI `evaluatePosition` gated the victory-hex capture bonus on `owner == self` (JS dead code) — the AI undervalued capturing enemy objectives.

## Notes

- 404 warnings for images/sounds during `jsBrowserTest` are expected: Karma serves the test bundle from a temporary context, not from the full resource root. They do not fail tests.
- `jsBrowserTest` passes after re-running with the updated yarn lock and `source-map-loader` dependency.
- Loading saves with units that have a leader whose unit class has no leader list (e.g., flak/fortification/transport/ship classes) previously crashed in `Leaders.getUnitClassLeader`; fixed by using `firstOrNull()`.
- `NATIVE_PLATFORM` is set to `"generic"` for the browser build; the original Android build used `"android"`.
- Cloud save/load was removed because the inherited implementation depended on a hardcoded personal
  gist token. Do not restore it as part of save resilience; the approved roadmap keeps disk export
  and explicitly excludes a cloud account/backend.
