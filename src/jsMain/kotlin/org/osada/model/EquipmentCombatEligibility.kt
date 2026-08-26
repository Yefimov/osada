package org.osada.model

/*
 * `Equipment.attr` -- FULLY DECODED 2026-07-27. Read this before adding or trusting a mask.
 *
 * `attr` is not a PM invention: for every OG-imported record it is three of OG's own one-byte
 * ability fields packed by the importer (`tools/og-import/csv_to_eqp.py:105`):
 *
 *     attr = Special1 + (Special2 shl 8) + (Special3 shl 16)
 *
 * so bits 0..7 are Special1, 8..15 Special2, 16..23 Special3. OG's equipment view lists its
 * abilities in four columns of thirteen; the FIRST EIGHT ROWS of each column are one of those bytes,
 * in order. The remaining five rows of each column, and all of column four, live in fields the CSV
 * export does not carry -- **those abilities are simply not present in our data** (`No Leader`,
 * `All weather`, the mine specials, `Combat Support`'s neighbours, `No ZOC`, `Capture Flag`'s
 * column-four entries, `Evade`, `Kamikaze`, `Supply unit`, ...). See `OG_ABILITY_AUDIT.md`.
 *
 * | bit | mask | OG ability | bit | mask | OG ability |
 * |---|---|---|---|---|---|
 * | 0 | 1 | Drop mines (see below) | 12 | 4096 | **Support Fire** |
 * | 1 | 2 | Can Blow | 13 | 8192 | Air Support |
 * | 2 | 4 | **Ignore trench** | 14 | 16384 | Air Transportable |
 * | 3 | 8 | **Bridge** | 15 | 32768 | **CAN Air Atk** |
 * | 4 | 16 | **Can't Soft Atk** | 16 | 65536 | Combat Support |
 * | 5 | 32 | **Can't Hard Atk** | 17 | 131072 | No Prototype |
 * | 6 | 64 | **Can't Air Atk** | 18 | 262144 | **Can't Naval Atk** |
 * | 7 | 128 | **Can't Buy** | 19 | 524288 | Carrier Deploy |
 * | 8 | 256 | No AI buy | 20 | 1048576 | Capture Flag |
 * | 9 | 512 | Mountain | 21 | 2097152 | Mechanized |
 * | 10 | 1024 | Recon Skill | 22 | 4194304 | Marine |
 * | 11 | 2048 | Dismount | 23 | 8388608 | **NoSurrender** |
 *
 * **How it was established — nine independent confirmations, no contradictions.** Bits 2..6 were
 * already in this file as faithful ports of `openpanzer.js`'s numeric masks and land exactly on the
 * matching column-one rows. The user then read two records in OG's own equipment view:
 *
 *  - `Yak-9P` (`attr 384` = bits 7+8) -- reported true: **only** `Can't Buy` and `No AI buy`.
 *  - BASEKORP `Fort`, OG index `E 335` (`attr 8392704` = bits 12+23) -- reported true: **only**
 *    `Support Fire` and `NoSurrender`.
 *
 * Corroborated across all 46,978 `eqp-united` records: `Yak-9R` (a recon variant) carries bits
 * 4+5+6+10 = can't attack soft/hard/air, plus Recon Skill; `Yak-9D SQNLDR` carries Combat Support;
 * every OLGCW bunker carries Can't Buy + No AI buy + Support Fire + NoSurrender. Bit 23 is on 58%
 * immobile records and 56% of the Fortification class; bit 12 on 74% of Anti-Tank, 63% of Flak, 49%
 * of Destroyer -- the defensive-fire archetypes.
 *
 * **Two masks this file previously guessed were WRONG, and are corrected here:**
 *  - `262144` was `ATTR_MASK_PURCHASABLE`. It is **`Can't Naval Atk`**. That settles the doubt
 *    recorded in `DEFERRED.md` §1.5/§7.25 about the old ground-transport gate: it was testing naval
 *    attack capability. Purchasability is bit 7 (`Can't Buy`), INVERTED.
 *  - `32768` was `ATTR_MASK_SPECIAL_ANTI_AIR`. It is **`CAN Air Atk`** -- the same idea (a unit that
 *    may attack aircraft although its class would not), so the one use of it is unaffected; only the
 *    name was wrong.
 *
 * WIDENED 2026-08-19 (`docs/og-fidelity-plan.md` §C). `attr` itself still stops at bit 23 -- it is
 * three OG special bytes packed by the importer, unchanged -- but two more fields now carry the
 * other two: `EquipmentData.attr2` is `Special4` (8 bits) and `EquipmentData.attrEx` is
 * `SpecialEx` bytes 0..2 (24 bits, packed the identical way `attr` packs Special1..3). Together the
 * three fields cover all 52 of OG's equipment specials -- nothing OG can express is unreadable any
 * more. `eqp-united`'s existing records were back-filled additively by
 * `tools/eqp-merge/add_special4_specialex.py`, which re-identifies each record's true (efile,
 * ECode) against 8 of the 11 OG-imported efiles whose `equip.xeqp` layout is cracked (cc76,
 * pzliga, kaiser, atomic, basekorp, lxf, gce, ag). `csv_to_eqp.py` now packs both fields going
 * forward for any efile it can read.
 *
 * COMPLETED 2026-08-23 (`docs/og-fidelity-plan.md` §J). The other three efiles are readable through
 * OpenSuite's `*_sel.xeqp` exports, which use the same 4 + N*122 layout; `tools/eqp-merge/
 * verify_specials.py` audits EVERY shipped record's `attr`/`attr2`/`attrEx` against OG's own binary
 * and back-filled 440 `attr2` and 1,996 `attrEx` values that had been 0 for want of a readable
 * source. `attr` itself was faithful apart from five column-shifted EFILE_ATOMIC CSV rows, also
 * corrected. ONE gap is permanent and deliberate: `eqp-olgcw`'s `SpecialEx` is manufactured by
 * OpenSuite's up-conversion from the 73-byte `equip97.eqp` (which has no such field) and is a pure
 * function of the unit class, so its 2,681 records keep `attrEx = 0` -- "OG never authored this",
 * proven byte by byte in §J.2, not "we failed to import it".
 *
 * ```
 * attr2  = Special4
 * attrEx = SpecialExByte0 + (SpecialExByte1 shl 8) + (SpecialExByte2 shl 16)
 * ```
 *
 * | `attr2` bit | mask | OG ability | | `attrEx` bit | mask | OG ability |
 * |---|---|---|---|---|---|---|
 * | 0 | 1 | Build/Repair | | 0 | 1 | No Leader |
 * | 1 | 2 | Dismount After Move | | 1 | 2 | Lasting Sup. |
 * | 2 | 4 | No Dirt Airfields | | 2 | 4 | **All Weather** |
 * | 3 | 8 | Rocket Bomber | | 3 | 8 | **Overrun toggle** |
 * | 4 | 16 | Cut LOS | | 4 | 16 | No Ammo penalty |
 * | 5 | 32 | Allow LOF | | 5 | 32 | No Intercept Air |
 * | 6 | 64 | No ZOC | | 6 | 64 | **Clear mines** |
 * | 7 | 128 | Evade | | 7 | 128 | NoNeedStation |
 * | | | | | 8 | 256 | Torpedo bomber |
 * | | | | | 9 | 512 | Counter Battery |
 * | | | | | 10 | 1024 | Partizan |
 * | | | | | 11 | 2048 | Exploit Success |
 * | | | | | 12 | 4096 | Anti Sub (ASW) |
 * | | | | | 13 | 8192 | **AD Support** |
 * | | | | | 14 | 16384 | *(unused)* |
 * | | | | | 15 | 32768 | SingleFireSup. |
 * | | | | | 16 | 65536 | Kamikaze |
 * | | | | | 17 | 131072 | **AirDropMines** |
 * | | | | | 18 | 262144 | Saboteur |
 * | | | | | 19 | 524288 | Jet (Stealth) -- CONFIRMED-BIT, UNCONFIRMED-EFFECT; do not guess one from the name |
 * | | | | | 20 | 1048576 | Supply Unit |
 * | | | | | 21..23 | | *(unused)* |
 *
 * Bold = read by the engine today (in this file or its callers). The rest have a named
 * `equipment.mechanics.*` line in the purchase window (`EquipmentWindowBuilder.equipmentMechanicsNote`)
 * but no gameplay subsystem yet -- deliberately: each would need its own design under
 * `ruleset-profiles.md` §2's admission rule (a mechanic like `Kamikaze` or `Partizan` is not a
 * bitmask read, it is new combat/movement behaviour). The full bit->name table (source byte and
 * bit, with how each was verified) is `tools/og-import/OG_ABILITY_AUDIT.md` section 7.1.1; the
 * binary layout it is read from is documented in `tools/og-import/xeqp_to_csv.py`.
 *
 * These are `internal` rather than `private` because `EquipmentAbilityCatalog.kt`'s ability list
 * reads the same constants, so a unit's catalog line and its actual combat-eligibility check can
 * never name different bits.
 */
internal const val ATTR_MASK_IGNORES_ENTRENCHMENT = 4
internal const val ATTR_MASK_BRIDGE = 8
internal const val ATTR_MASK_CANNOT_ATTACK_SOFT = 16
internal const val ATTR_MASK_CANNOT_ATTACK_HARD = 32
internal const val ATTR_MASK_CANNOT_ATTACK_AIR = 64
private const val ATTR_MASK_CANNOT_BUY = 128
internal const val ATTR_MASK_CAN_AIR_ATK = 32768

/**
 * OG's `Support Fire`, bit 12 — a **TOGGLE that reverses the class default**, not a grant.
 *
 * Fire support is on by default for Artillery; this bit flips whichever way the class defaults. See
 * `UnitCapabilities.hasSupportFire` for the evidence (the surviving toggles are all rare on the
 * class they default to; `Recon Skill` is on 10 of 2,880 Recon records).
 *
 * **The three attributes OG's own string template renders as a PAIR of opposite labels are the
 * three toggles, and all three are read as toggles now** (the last, `Dismount`, was wired
 * 2026-08-25): `Support Fire` (845/846) here, `Overrun toggle` ([ATTR_EX_MASK_OVERRUN_TOGGLE]) and
 * `Dismount` (843/844, [ATTR_MASK_DISMOUNT]). `Recon Skill` ([ATTR_MASK_RECON_SKILL]) is read the
 * same `classDefault xor bit` way on `OG_ABILITY_AUDIT.md` §2's wording, but it is NOT one of the
 * paired three — do not cite it as the third when counting them.
 *
 * **Bit 0 was `Lasting Sup.` here until 2026-08-18 and is not.** A controlled OpenSuite staircase on
 * `E:05242 Train - RTP` (25 saves, one checkbox each, every step moving exactly one bit) put
 * `Lasting Sup.` at `SpecialEx` 60.1 — outside `attr` entirely — and bit 0 at **`Drop mines`**, a
 * plain grant, not a toggle. The population settles it beyond argument: the 478 LXF records carrying
 * bit 0 are Engineers/Commandos/Partisans, destroyers and motor launches (`ML`), minesweepers
 * (`Bird MS`), submarines, cruisers, and the maritime-patrol bombers that actually laid mines
 * (`Hampden I`, `Catalina II`, `Ventura GR.V`). It also explains the one anomaly
 * `OG_ABILITY_AUDIT.md` §7.5 had recorded and shrugged at — `Repair Crew`'s unexplained extra bit 0.
 * An engineering unit carrying `Drop mines` is coherent; one carrying lasting suppression was not.
 * See `OG_ABILITY_AUDIT.md` §7.1 for the full run.
 */
internal const val ATTR_MASK_SUPPORT_FIRE = 4096

/**
 * OG's `Drop mines`, bit 0 — a plain grant: the unit can lay a land minefield on the hex it stands
 * on (OG manual 9.9, two ammunition points, no previous action that turn).
 *
 * **This bit was documented as `Lasting Sup.` until 2026-08-18 and never read by anything.** The
 * controlled OpenSuite staircase recorded in `OG_ABILITY_AUDIT.md` §7.1.1 put `Lasting Sup.` at
 * `SpecialEx` 60.1, outside `attr` entirely, and this bit at `Drop mines`. The population settles
 * it: the 478 `eqp-lxf` records carrying it are engineers, commandos and partisans, destroyers and
 * motor launches, minesweepers, submarines, and the maritime-patrol bombers that actually laid
 * mines. See [org.osada.rules.Minefields], which is the only reader.
 *
 * Its two companions are NOT in our data and must not be faked from this one: `Clear mines` is
 * `SpecialEx` 60.6 and `AirDropMines` is `SpecialEx` 62.1, both outside the three special bytes the
 * importer packs. `MineAbilities.canClearMines` documents the stand-in it uses meanwhile.
 */
internal const val ATTR_MASK_DROP_MINES = 1

/**
 * OG's `Mechanized`, bit 21 — a plain grant: the crew rides its own prime mover, so the gun may
 * move and still fire in the same turn.
 *
 * Read by [org.osada.rules.AttackEligibility.blockedByMoveThenFire], which only ever consults it
 * while the `heavy_move_fire` ruleset key asks for OG's ordering. Under OSADA Default there is no
 * ordering restriction at all, so the bit decides nothing — that is the deliberate default, chosen
 * so no shipped campaign is re-tuned.
 *
 * **Acceptance-test it on `eqp-basekorp` or `eqp-comww2`, never on an LXF campaign**: `Mechanized`
 * is set on ZERO LXF records, so the four deployed LXF campaigns would show no change and read as a
 * failure (`docs/og-fidelity-plan.md` B.1).
 */
internal const val ATTR_MASK_MECHANIZED = 2097152

/**
 * OG's `Combat Support`, bit 16 — a plain grant (not one of the six toggles): the unit lends
 * experience bars to adjacent friendlies. Confirmed by BASEKORP `43 HQ` (`E 3814`), whose only
 * enabled ability is `Combat Support` and whose `attr` is exactly `65536`.
 */
internal const val ATTR_MASK_COMBAT_SUPPORT = 65536

/**
 * OG's `Capture Flag`, bit 20 — a grant that **supplements** the default capturing classes rather
 * than replacing them, which is how it is distinguishable from noise: it is set on 0.7% of the four
 * classes that already capture (Tank: 1 record in 3,024) and 37.7% of everything else. Confirmed by
 * BASEKORP `Gaz-3` (`E 528`) and `Armoured Train` (`E 2814`). See `UnitCapabilities.canCaptureHex`.
 */
internal const val ATTR_MASK_CAPTURE_FLAG = 1048576

/** OG's `NoSurrender`: never destroyed-as-surrendered for a retreat it cannot make. Bit 23.
 *  internal (not private): also read by EquipmentAbilityCatalog.kt, see the note above. */
internal const val ATTR_MASK_NO_SURRENDER = 8388608

/**
 * OG's `Mountain`, bit 9 — mountain-trained infantry. WIRED 2026-08-25.
 *
 * It reuses the movement-cost path the `Alpine Training` leader already runs on
 * (`MoveRangeCalculation.terrainColumn`): hill, mountain and rough ground are costed as clear.
 * That lands the unit on the same cost `ALL_TERRAIN_LEG` (OG's own "Mountain Leg" movement method,
 * `movTable` row 11) pays for those three columns, so a mountain-trained rifle regiment climbs
 * exactly as well as one whose movement METHOD says mountain — which is how OG expresses it for
 * most records. Movement only: terrain defence, entrenchment and close combat still read the real
 * terrain, the same limit `Alpine Training` documents.
 */
internal const val ATTR_MASK_MOUNTAIN = 512

/**
 * OG's `Marine`, bit 22 — amphibious-assault troops. WIRED 2026-08-25.
 *
 * The landing leg of a disembark is the one move where `GameUnit.carrier` is negative
 * (`UnitMountOperations.disembarkUnit` negates it and `GameUnit.move` clears it again), so
 * [org.osada.model.landsReadyFromTransport] can recognise it without a new "just landed" flag:
 * a marine coming off a naval transport keeps whatever movement it has left instead of having its
 * turn spent by the landing.
 */
internal const val ATTR_MASK_MARINE = 4194304

/**
 * OG's `Dismount`, bit 11 — a **TOGGLE that reverses the class default**, the third of the three
 * and the last to be wired (2026-08-25).
 *
 * Mounted infantry dismounts when attacked and fights with its own stats instead of its
 * transport's; that is on by default for Infantry, and this bit flips whichever way the class
 * defaults. The toggle reading is not inferred — OG's own localisation template renders this one
 * attribute as two opposite labels, line 843 `NO dismount when attacked` and line 844
 * `Dismount if attacked`, exactly as it does for [ATTR_MASK_SUPPORT_FIRE] (845/846) and
 * [ATTR_EX_MASK_OVERRUN_TOGGLE] (872/873).
 *
 * 2,628 of the 56,970 shipped records carry it. Until this was wired, `AttackCalculation` gave
 * dismount to **every** mounted infantry record unconditionally and the bit drew a grey badge that
 * meant nothing — the exact shape of approximation §I closed for `Recon Skill`, `Overrun` and
 * `AD Support`. See [org.osada.rules.UnitCapabilities.dismountsWhenAttacked].
 */
internal const val ATTR_MASK_DISMOUNT = 2048

/**
 * OG's `Can Blow`, bit 1 — the unit can demolish a bridge it stands on, and (where the efile sets
 * `blow_any_terrain`) destroy the hex's terrain feature outright. OG manual §9.3.1 / §9.3.7.
 *
 * WIRED 2026-08-25 behind `RuleKey.BUILD_AND_REPAIR`. 5,047 shipped records carry it. See
 * [org.osada.rules.Engineering].
 */
internal const val ATTR_MASK_CAN_BLOW = 2

/**
 * OG's `Build/Repair` (`Special4` bit 0, `attr2` bit 0) — the Sapper ability. The unit can build a
 * bridge, fortification, airfield, port or railroad station on a qualifying hex, and repair a
 * destroyed one. OG manual §9.3.2-§9.3.6 and §9.3.8.
 *
 * WIRED 2026-08-25 behind `RuleKey.BUILD_AND_REPAIR`. 1,298 shipped records carry it. See
 * [org.osada.rules.Engineering].
 */
internal const val ATTR2_MASK_BUILD_REPAIR = 1

/**
 * OG's `Cut LOS` (`Special4` bit 4, `attr2` bit 4) — the unit blocks line of sight for BOTH sides,
 * the way a forest or a city hex does. Read only under `RuleKey.EXTENDED_LOS`; 252 shipped records
 * carry it.
 */
internal const val ATTR2_MASK_CUT_LOS = 16

/**
 * OG's `Allow LOF` (`Special4` bit 5, `attr2` bit 5) — the unit does NOT block an enemy's line of
 * fire, so ranged fire passes through the hex it occupies. The counterpart of [ATTR2_MASK_CUT_LOS],
 * and likewise read only under `RuleKey.EXTENDED_LOS`. 561 shipped records carry it.
 */
internal const val ATTR2_MASK_ALLOW_LOF = 32

/**
 * OG's `No ZOC` (`Special4` bit 6, `attr2` bit 6) — the unit projects no Zone of Control at all,
 * so an enemy moving past it is neither stopped nor slowed. WIRED 2026-08-25.
 *
 * `OG_ABILITY_AUDIT.md` recorded this as *"not representable"* on the grounds that `setZOCRange`
 * gives every non-air unit a full ZOC with no opt-out. **That ruling is superseded**: ZOC is now
 * asked per unit in [org.osada.rules.UnitCapabilities.projectsZoneOfControl], which the places
 * that build or test a ZOC funnel through. 609 shipped records carry it.
 */
internal const val ATTR2_MASK_NO_ZOC = 64

/**
 * OG's `Counter Battery` (`SpecialEx` 61.1, `attrEx` bit 9) — artillery that answers enemy
 * artillery firing on a friendly unit, once per turn. OG manual §9.4, an optional rule in its own
 * right (it is NOT part of §9.6's extended naval set, where an earlier draft of
 * `docs/og-fidelity-plan.md` §C filed it).
 *
 * WIRED 2026-08-25 behind `RuleKey.COUNTERBATTERY`; 818 shipped records carry it. See
 * [org.osada.rules.CounterBatteryFire].
 */
internal const val ATTR_EX_MASK_COUNTER_BATTERY = 512

/**
 * OG's `Dismount after movement` (`Special4` bit 1, `attr2` bit 1; template line 860) — the
 * formation may leave its transport after it has already moved, which every other mounted
 * formation may not.
 *
 * WIRED 2026-08-26; 662 shipped records carry it, and every one of them is a mountable ground
 * class (Infantry 427, Artillery 157, Recon 32, Ground Transport 19, Anti-Tank 12). A plain grant,
 * not a toggle — see [org.osada.rules.UnitCapabilities.dismountsAfterMove], and do not confuse it
 * with [ATTR_MASK_DISMOUNT], which decides which STATISTICS a mounted formation fights with.
 */
internal const val ATTR2_MASK_DISMOUNT_AFTER_MOVE = 2

/**
 * OG's `Cannot get a leader` (`SpecialEx` bit 0, `attrEx` bit 0; template line 869) — this
 * equipment never produces a commander.
 *
 * WIRED 2026-08-26; 537 shipped records carry it, led by Infantry (207), Tactical Bomber (91),
 * Fortification (69) and Flak (35). Read by both of OSADA's leader mechanics through
 * [org.osada.rules.UnitCapabilities.canProduceLeader]: the legacy integer leader and Phase 2 hero
 * emergence. It blocks PRODUCTION only — a formation that already has a commander and upgrades
 * into such a record keeps them.
 */
internal const val ATTR_EX_MASK_NO_LEADER = 1

/**
 * OG's `No run out ammo penalty` (`SpecialEx` bit 4, `attrEx` bit 4; template line 874) — the
 * formation does not pay OG 6.23's halvings for fighting with an empty magazine.
 *
 * WIRED 2026-08-26 behind `RuleKey.DRY_UNIT_PENALTIES`, which is the only rule that imposes those
 * halvings; 824 shipped records carry it, led by Submarine (141), Tactical Bomber (123), Destroyer
 * (118) and Air Transport (92). It lifts the penalties and NOT the prohibition — see
 * [org.osada.rules.UnitCapabilities.ignoresDryAmmoPenalty].
 */
internal const val ATTR_EX_MASK_NO_AMMO_PENALTY = 16

/** Whether this equipment is mountain-trained — see [ATTR_MASK_MOUNTAIN]. */
fun EquipmentData.isMountainTrained(): Boolean = attr and ATTR_MASK_MOUNTAIN != 0

/** Whether this equipment lands from a naval transport with its move intact — see [ATTR_MASK_MARINE]. */
fun EquipmentData.landsReadyFromTransport(): Boolean = attr and ATTR_MASK_MARINE != 0

/**
 * OG's `Clear mines`, `SpecialEx` bit 60.6 (`attrEx` bit 6). See [org.osada.rules.MineAbilities].
 *
 * This constant opens the block of `attrEx` masks for the `SpecialEx` abilities wired into
 * gameplay. See this file's header for the complete bit table, including the ones that are NOT
 * wired and live only in `EquipmentAbilityCatalog.kt`.
 */
internal const val ATTR_EX_MASK_CLEAR_MINES = 64

/**
 * OG's `AirDropMines`, `SpecialEx` bit 62.1 (`attrEx` bit 17) — an air unit's ability to lay mines
 * from the air. **Has a prerequisite OG enforces in its own UI**: greyed out unless the unit also
 * carries `Drop mines` (`attr` bit 0). See [org.osada.rules.MineAbilities.canDropMines].
 */
internal const val ATTR_EX_MASK_AIR_DROP_MINES = 131072

/**
 * OG's `All Weather`, `SpecialEx` bit 60.2 (`attrEx` bit 2) — the equipment-level override that
 * lets a unit attack (and be attacked) despite bad weather. `WeatherCombatRules.isAllWeather`
 * previously read only the `ALL_WEATHER_COMBAT` leader trait; this is the second, equipment-level
 * source `OG_ABILITY_AUDIT.md` §2 named as missing. Do not double-apply: a unit with both the bit
 * and the leader trait is still just "all-weather", not doubly so.
 */
internal const val ATTR_EX_MASK_ALL_WEATHER = 4

/**
 * OG's `Overrun toggle`, `SpecialEx` bit 60.3 (`attrEx` bit 3) — a **TOGGLE that reverses the Tank
 * class default**, exactly the shape `Support Fire` and `Recon Skill` already have (see
 * `UnitCapabilities.hasSupportFire`'s header for why toggles must be read as `classDefault xor
 * bit`, never as a plain grant). `UnitCapabilities.canOverrun` previously read the Tank class alone
 * — `docs/og-fidelity-plan.md`'s own approximations list named this the OVR badge's stand-in.
 */
internal const val ATTR_EX_MASK_OVERRUN_TOGGLE = 8

/**
 * OG's `Recon Skill`, `attr` bit 10 (already inside the original 24-bit `attr` word — no import
 * widening needed) — a **TOGGLE that reverses the Recon class default**. Measured on 2,880 Recon
 * records: set on only 10 of them (0.3%), which settles it as a toggle rather than a grant
 * (`OG_ABILITY_AUDIT.md` §2's "FOUR OF THESE BITS ARE TOGGLES" table). `UnitCapabilities.hasPhasedMovement`
 * previously read the Recon class alone — the plan's other named approximation, alongside OVR above.
 */
internal const val ATTR_MASK_RECON_SKILL = 1024

/**
 * OG's `AD Support`, `SpecialEx` bit 61.5 (`attrEx` bit 13) — the equipment-level anti-air support
 * grant `OG_ABILITY_AUDIT.md` §2 named as the third missing piece behind OSADA's class-only AA
 * badge (alongside RCN/OVR above). **Supplements** [org.osada.rules.UnitCapabilities.hasAirDefenceFire]'s
 * class list rather than replacing it, the same shape `Capture Flag` supplements its four classes:
 * it is a grant, not a toggle, so OR is correct and a class already in the AA set is unaffected by
 * also carrying the bit.
 */
internal const val ATTR_EX_MASK_AD_SUPPORT = 8192

/**
 * OG's `ASW` (Anti Submarine Warfare), `SpecialEx` bit 61.4 (`attrEx` bit 12) — a plain grant,
 * `Manual_OG-en.pdf` §7.2: *"ASW: can attack submarines."* See [canAttackSubmarineTarget].
 */
internal const val ATTR_EX_MASK_ANTI_SUB = 4096

/**
 * OG's `No Intercept Air`, `SpecialEx` bit 60.5 (`attrEx` bit 5) — narrower than its name and the
 * manual's own "Intercept toggle" paragraph suggest. `OG_ABILITY_AUDIT.md` §2's measured table
 * (cross-checked against OG's own interception rules) is the more precise source: it "disables
 * movement interception for AD/FlaK/Fighter... not necessarily all AD support" and leaves ordinary
 * defensive AA fire **unaffected**. So this is a veto on the INTERCEPTION path specifically
 * (`AAInterception.isEligibleInterceptor` / `visibleThreatHexes`), never on
 * [org.osada.rules.UnitCapabilities.hasAirDefenceFire] itself — that would also suppress the
 * badge and ordinary defensive fire, which OG does not do.
 */
internal const val ATTR_EX_MASK_NO_INTERCEPT_AIR = 32

/**
 * OG's `Lasting Suppression`, `SpecialEx` bit 60.1 (`attrEx` bit 1) — `Manual_OG-en.pdf` §7.2:
 * *"suppression inflicted by the unit lasts until end of turn."* The second, equipment-level
 * source of the same effect the `SHOCK_TACTICS` leader trait already grants
 * (`GameUnit.lastingHits`) — see [org.osada.rules.UnitCapabilities.hasLastingSuppression], which
 * ORs the two exactly as `docs/og-fidelity-plan.md` §0.1 anticipated this bit being wired: *"this
 * unit's `hits` survive the victim's `unitEndTurn`"*, not a second suppression statistic.
 */
internal const val ATTR_EX_MASK_LASTING_SUPPRESSION = 2

/** Attribute-bitmask combat eligibility checks, split out of [Equipment] to keep its function count in bounds. */
fun Equipment.isBridge(eqid: Int): Boolean = (equipmentMap[eqid]?.attr?.and(ATTR_MASK_BRIDGE) ?: 0) != 0

fun Equipment.ignoresEntrenchment(eqid: Int): Boolean =
    (equipmentMap[eqid]?.attr?.and(ATTR_MASK_IGNORES_ENTRENCHMENT) ?: 0) != 0

/**
 * OG's `Can't Buy`, inverted: whether this record may be bought at all.
 *
 * **Was reading the wrong bit** (`262144`, now known to be `Can't Naval Atk`) until the `attr` table
 * above was decoded. Corrected rather than deleted even though **nothing calls it today** — a
 * function named `isPurchasable` that silently answered "can it shoot at ships?" is a trap for the
 * next reader. Wiring it into the Purchase list is a separate decision (`DEFERRED.md` §7.32):
 * `Can't Buy` is set on a great many scenario-only records, so honouring it would visibly shrink
 * what the player may buy in every imported campaign.
 */
fun Equipment.isPurchasable(eqid: Int): Boolean = (equipmentMap[eqid]?.attr?.and(ATTR_MASK_CANNOT_BUY) ?: 0) == 0

/** OG's `NoSurrender`: the unit is never destroyed-as-surrendered for a retreat it cannot make.
 *  See the `attr` table above for how the bit was identified. */
fun Equipment.hasNoSurrender(eqid: Int): Boolean = (equipmentMap[eqid]?.attr?.and(ATTR_MASK_NO_SURRENDER) ?: 0) != 0

/**
 * A bare Ground Transport is never bought as a unit of its own. A transport is acquired by
 * ATTACHING it to a unit at purchase time (`eqUserSel.eqtransport`), which this does not affect --
 * only the "buy a Horse as your combat unit" case, which has no defensible reading: it cannot
 * attack, cannot capture, and exists solely to carry something.
 *
 * **This replaced an attr-bit gate on 2026-07-26 (user request).** The previous rule permitted a
 * transport whose `attr` had bit 262144 -- which DEFERRED.md §1.5/§1.7 then recorded as
 * "purchasable" -- with a per-country fallback for countries that never set the bit. That fallback
 * did NOT fire for every country: 29 of 289 `eqp-united` countries do set the bit on a transport,
 * and country 20 (USSR) flags only 4 of its 28, refusing the other 24.
 *
 * **262144 has since been identified, and it was never a purchasability bit: it is `Can't Naval
 * Atk`** -- see this file's own `attr` table above, and DEFERRED.md §7.32/§7.44. That explains every
 * number the old note called "suspect": only 1,060 of 46,978 records carry it (2.3%) with **zero
 * Tank and zero Anti-tank** (class 2 = 0/3,024, class 4 = 0/3,186), which is nonsense for
 * purchasability and exactly right for a naval-attack prohibition.
 *
 * Purchasability is bit 7, `Can't Buy`, inverted -- `Equipment.isPurchasable` reads it and currently
 * has no caller (DEFERRED.md §7.32 item 2c). **So the standing advice is unchanged but for a new
 * reason:** do not restore an attr-based gate *here*, because no attr bit describes what this
 * function decides. The flat class rule below is the rule.
 *
 * (This comment and the file header used to contradict each other -- the header naming the bit while
 * this block called it unidentified. DEFERRED.md §7.44 asked for them to be collapsed; done
 * 2026-07-28.)
 */
fun Equipment.isPurchasableGroundTransport(eqid: Int): Boolean =
    equipmentMap[eqid]?.uclass != org.osada.UnitClass.GROUND_TRANSPORT.value

fun Equipment.canInitiateAttackOnUnitType(
    attackerEqid: Int,
    defenderEqid: Int,
): Boolean {
    val attacker = equipmentMap[attackerEqid]
    val defender = equipmentMap[defenderEqid]
    if (attacker == null || defender == null) return false
    return canAttackSubmarineTarget(attacker, defender) && canAttackTargetType(attacker, defender.target)
}

/**
 * Whether [attacker] may target a submarine: the class default, **plus** OG's `ASW` (Anti
 * Submarine Warfare) grant (`SpecialEx` bit 61.4, `attrEx` bit 12) -- the same `CAN Air Atk`
 * shape [canAttackAirTarget] already uses for aircraft targets, added 2026-08-19. `Manual_OG-en.pdf`
 * §7.2 states it as a plain grant, not a toggle: *"ASW: can attack submarines"*.
 */
private fun canAttackSubmarineTarget(
    attacker: EquipmentData,
    defender: EquipmentData,
): Boolean {
    if (defender.uclass != org.osada.UnitClass.SUBMARINE.value) return true
    return attacker.uclass == org.osada.UnitClass.DESTROYER.value ||
        attacker.uclass == org.osada.UnitClass.TACTICAL_BOMBER.value ||
        attacker.attrEx.and(ATTR_EX_MASK_ANTI_SUB) != 0
}

private fun canAttackTargetType(
    attacker: EquipmentData,
    target: Int,
): Boolean =
    when (target) {
        org.osada.UnitType.SOFT.value -> attacker.attr.and(ATTR_MASK_CANNOT_ATTACK_SOFT) == 0
        org.osada.UnitType.HARD.value -> attacker.attr.and(ATTR_MASK_CANNOT_ATTACK_HARD) == 0
        org.osada.UnitType.AIR.value -> canAttackAirTarget(attacker)
        else -> true
    }

private fun canAttackAirTarget(attacker: EquipmentData): Boolean {
    if (attacker.attr.and(ATTR_MASK_CANNOT_ATTACK_AIR) != 0) return false
    val attackerClass = attacker.uclass
    val isDedicatedAntiAir =
        attackerClass == org.osada.UnitClass.AIR_DEFENCE.value ||
            attackerClass == org.osada.UnitClass.FIGHTER.value ||
            attackerClass == org.osada.UnitClass.LEVEL_BOMBER.value ||
            attackerClass == org.osada.UnitClass.TACTICAL_BOMBER.value ||
            attackerClass == org.osada.UnitClass.BATTLESHIP.value ||
            attackerClass == org.osada.UnitClass.BATTLE_CRUISER.value ||
            attackerClass == org.osada.UnitClass.LIGHT_CRUISER.value ||
            attackerClass == org.osada.UnitClass.AIR_TRANSPORT.value
    return isDedicatedAntiAir || attacker.attr.and(ATTR_MASK_CAN_AIR_ATK) != 0
}
