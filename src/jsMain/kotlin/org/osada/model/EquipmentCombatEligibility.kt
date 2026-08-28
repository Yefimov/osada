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
internal const val ATTR_MASK_CANNOT_BUY = 128
internal const val ATTR_MASK_NO_AI_BUY = 256
internal const val ATTR_MASK_NO_PROTOTYPE = 131072
internal const val ATTR_MASK_CAN_AIR_ATK = 32768

/**
 * OG's `Can't Naval Atk`, bit 18 — the fourth of the four target-type prohibitions, and the last
 * to get a reader (2026-08-27). *"Forbids initiating an attack against naval targets"*: the same
 * shape as [ATTR_MASK_CANNOT_ATTACK_SOFT] / [ATTR_MASK_CANNOT_ATTACK_HARD] /
 * [ATTR_MASK_CANNOT_ATTACK_AIR], applied to [org.osada.UnitType.SEA], and read in exactly the same
 * place ([canAttackTargetType]) — so a record's four prohibitions can never be answered by four
 * different rules.
 *
 * **Universal, with no ruleset key**, for §L.3's reason: OSADA already runs OG's target-type
 * matrix and simply read three quarters of it. Its three siblings have never been behind a key
 * either, and putting the fourth alone behind one would say the matrix is optional, which it is
 * not.
 *
 * 866 of the 56,970 merged records carry it, 601 of them with a non-zero `navalatk` — so it does
 * take shots away, which is the point. By class the population is exactly what a naval-attack
 * prohibition should look like: Air Defence, Naval Transport, Air Transport, Carrier and
 * Fortification lead it, and there is no Tank or Anti-Tank at all.
 *
 * **This bit is the one place PM's `attr` and OG's `attr` disagree outright** (§K.8): PM's engine
 * reads 262144 as `isPurchasable`. `fix_pm_abilities.py` therefore excluded it from `KEEP_MASK`
 * when it rebuilt `eqp-adlerkorps`'s PM-only records from their OG twins, and
 * `fix_pm_cant_naval_atk.py` cleared it on the 57 `eqp-pacific`-only records that script never
 * covered. Nothing may read this mask on a record whose `attr` came from Panzer Marshal.
 */
internal const val ATTR_MASK_CANNOT_ATTACK_NAVAL = 262144

/**
 * OG's `Air Transportable`, `attr` bit 14 — *"transport can be transported by plane"* (manual §7.2).
 *
 * **This file's catalog header used to call it redundant with the `embark` field, and the data says
 * otherwise.** Over the 5,937 shipped ground-transport records, 568 have `embark` above NAVAL,
 * 506 carry this bit, and only **401 have both** — related fields, not one field written twice. So
 * it gets its own reader (2026-08-27), and the two are ORed: a prime mover may fly if EITHER source
 * says so, which is the "supplements" shape `Capture Flag` and `AD Support` already use and the
 * direction that takes least from the player.
 *
 * See [org.osada.rules.UnitCapabilities.transportSurvivesAirlift].
 */
internal const val ATTR_MASK_AIR_TRANSPORTABLE = 16384

/**
 * OG's `Air support`, `attr` bit 13 — *"can supply air units, the same than an airfield"*
 * (manual §7.2). WIRED 2026-08-27.
 *
 * 632 shipped records carry it, and the population is exactly the sentence: capital ships that
 * carried floatplanes (Battleship 231, Cruiser 131, Battle Cruiser 121), destroyers (86) and
 * forward depots on the Fortification class (31). Read by `MovementRules.hasAirfield`, which is the
 * one predicate that answers *"is this aircraft properly based?"*.
 */
internal const val ATTR_MASK_AIR_SUPPORT = 8192

/**
 * OG's `Evade` (`Special4` bit 7, `attr2` bit 7) — *"unit has a 50% probability of evading any
 * attack, like submarines"* (manual §7.2). WIRED 2026-08-27.
 *
 * 409 shipped records carry it, and the population reads as a grant rather than a class default:
 * 322 Recon (10% of that class), 32 Infantry, and only 4 of 710 submarines. That distribution is
 * what settles how OG's `class_evade` table is meant to be read — see [org.osada.rules.Evade].
 */
internal const val ATTR2_MASK_EVADE = 128

/**
 * OG's `Jet (Stealth)`, `SpecialEx` 62.3 (`attrEx` bit 19) — **effect established 2026-08-27**.
 *
 * `OG_ABILITY_AUDIT.md` §7.1.1 filed it `CONFIRMED-BIT, UNCONFIRMED-EFFECT`, unable to determine
 * what it does from the manual or from OG's own UI, and warned against guessing from the name. The
 * author's specials reference has it: **a jet can be intercepted by ground air-defence only if the
 * intercepting unit also carries `Jet (Stealth)`.** It is a jet-versus-jet-interceptor rule, not a
 * general cloak — it does not touch spotting, and it does not stop a FIGHTER intercepting.
 *
 * 2,053 shipped records carry it, and the population is exactly right for a jet-age marker: Level
 * Bomber 610, Air Defence 365, Fighter 296 — the aircraft on one side of it and the missile
 * batteries meant to catch them on the other. See [org.osada.rules.AAInterception].
 */
internal const val ATTR_EX_MASK_JET_STEALTH = 524288

/**
 * OG's `Partizan`, `SpecialEx` 61.2 (`attrEx` bit 10) — the unit is **not stopped by adjacent
 * enemies**, which is `Battlefield Intelligence`'s behaviour. Wired 2026-08-27.
 *
 * The manual's *"cannot be surprised"* is a simplification of the same thing: what actually happens
 * is that the formation is not halted on entering a zone of control, so the ambush that a halt sets
 * up never arises. 720 records carry it and 680 are Infantry, which is what a partisan roster looks
 * like. See [org.osada.rules.UnitCapabilities.ignoresZoneOfControl].
 */
internal const val ATTR_EX_MASK_PARTIZAN = 1024

/**
 * OG's `Exploit Success`, `SpecialEx` 61.3 (`attrEx` bit 11) — after an ordinary attack that kills
 * the defender or forces it to retreat, the attacker may spend its REMAINING MOVEMENT. Wired
 * 2026-08-27; 456 records, 401 of them Infantry.
 *
 * **It is not Overrun and must not be collapsed into it.** Overrun predicts a zero-loss kill, skips
 * the normal combat entirely, and lets the unit go on moving AND firing. This one fights the normal
 * combat, spends the attack, and gives back only the movement. See
 * [org.osada.rules.UnitCapabilities.exploitsSuccess].
 */
internal const val ATTR_EX_MASK_EXPLOIT_SUCCESS = 2048

/**
 * OG's `Kamikaze`, `SpecialEx` 62.0 (`attrEx` bit 16) — the formation does not come back. Wired
 * 2026-08-27; 61 records (Tactical Bomber 17, Submarine 17, Destroyer 9 — the suicide craft).
 *
 * **Two models, chosen by the efile's own `kamikaze` key**, which `eqp-lxf` sets to 1:
 *
 *  - **default (`kamikaze=0`)**: destroyed after taking part in combat, or once its last ammunition
 *    is spent;
 *  - **`kamikaze=1`**, the *"extended missile rules"*: it cannot resupply at all, and is destroyed
 *    when its FUEL runs out rather than its ammunition.
 *
 * See [org.osada.rules.Kamikaze].
 */
internal const val ATTR_EX_MASK_KAMIKAZE = 65536

/**
 * OG's `Torpedo bomber`, `SpecialEx` 61.0 (`attrEx` bit 8) — may attack an adjacent unit while both
 * it and its target are over SEA terrain. Wired 2026-08-27; 385 records, 323 of them Tactical
 * Bombers.
 *
 * A grant, and a narrow one: it adds the range-1 attack over water and changes nothing about
 * ammunition, target class or the number of attacks a turn. See
 * [org.osada.rules.UnitCapabilities.isTorpedoBomber].
 */
internal const val ATTR_EX_MASK_TORPEDO_BOMBER = 256

/**
 * OG's `Saboteur`, `SpecialEx` 62.2 (`attrEx` bit 18) — a pre-combat attempt to disable the
 * defender outright, at the cost of one ammunition point. Wired 2026-08-27; **10 shipped records**
 * carry it (Infantry 6, Fighter 3, Recon 1), the smallest population any wired ability has.
 * See [org.osada.rules.Sabotage].
 */
internal const val ATTR_EX_MASK_SABOTEUR = 262144

/**
 * OG's `Cannot use dirt airfields` (`Special4` bit 2, `attr2` bit 2) — *"unit can't refuel nor
 * deploy in airfields defined as dirt or built by sappers during the scenario"*. WIRED 2026-08-27,
 * behind `RuleKey.BUILD_AND_REPAIR`, because that rule is what creates a sapper's strip in the
 * first place. Jets and heavy bombers carry it. See [org.osada.rules.AirfieldQuality].
 */

internal const val ATTR2_MASK_NO_DIRT_AIRFIELDS = 4

/**
 * OG's `Rocket bomber` (`Special4` bit 3, `attr2` bit 3) — *"unit can attack ground units within
 * full range"*, which `OG_ABILITY_AUDIT.md` §7.10 states more exactly as *"can attack ground units
 * within its firing range using any hex types"*. WIRED 2026-08-27 as an exemption from §6.18's
 * TERRAIN check and nothing else; 999 shipped records carry it. See
 * [org.osada.rules.ExtendedLos].
 */
internal const val ATTR2_MASK_ROCKET_BOMBER = 8

/**
 * OG's `SingleFireSup.` (`SpecialEx` 61.7, `attrEx` bit 15) — *"one fire-support action per turn"*,
 * a restriction on the SUPPORTING unit rather than on anything it supports. WIRED 2026-08-27; 166
 * shipped records carry it. See [org.osada.rules.UnitCapabilities.supportsOnlyOncePerTurn].
 */
internal const val ATTR_EX_MASK_SINGLE_FIRE_SUP = 32768

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
 * OG's `Supply Unit`, `SpecialEx` bit 62.4 (`attrEx` bit 20) — a MOBILE DEPOT. A unit carrying it,
 * or whose organic transport carries it, resupplies adjacent friendly formations on the Depot's
 * terms rather than the field's. WIRED 2026-08-28; see [org.osada.rules.DepotSupply].
 *
 * **0 of 56,970 shipped records carry it**, which is why it stayed descriptive-only for so long.
 * The rule exists now because its behaviour is documented in full and the bit is its own gate —
 * content that authors it gets the mechanic; content that does not cannot notice.
 */
internal const val ATTR_EX_MASK_SUPPLY_UNIT = 1048576

/**
 * OG's `No Need Station` (`attrEx` bit 7) — the formation may entrain and detrain anywhere on
 * rail, with no railroad station at either end. WIRED 2026-08-28; see
 * [org.osada.rules.RailTransport].
 *
 * 11,003 shipped records carry it. It does nothing until a scenario gives its player a rail
 * transport pool, which no shipped scenario does yet (`railtrans`).
 */
internal const val ATTR_EX_MASK_NO_NEED_STATION = 128

/**
 * OG's `Carrier Deploy` (`attr` bit 19) — *"permits deployment on carriers and dirt airfields"*.
 * WIRED 2026-08-28; see [org.osada.rules.CarrierDeploy].
 *
 * It is a DEPLOYMENT permission, not a hangar: the aircraft may be placed onto a friendly carrier's
 * hex during deployment, which it otherwise may not be. The dirt-airfield half waits on the per-hex
 * dirt flag, which is still unlocated.
 */
internal const val ATTR_MASK_CARRIER_DEPLOY = 524288

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

/** OG's `NoSurrender`: the unit is never destroyed-as-surrendered for a retreat it cannot make.
 *  See the `attr` table above for how the bit was identified. */
fun Equipment.hasNoSurrender(eqid: Int): Boolean = (equipmentMap[eqid]?.attr?.and(ATTR_MASK_NO_SURRENDER) ?: 0) != 0

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
        org.osada.UnitType.SEA.value -> attacker.attr.and(ATTR_MASK_CANNOT_ATTACK_NAVAL) == 0
        else -> true
    }

private fun canAttackAirTarget(attacker: EquipmentData): Boolean =
    attacker.attr.and(ATTR_MASK_CANNOT_ATTACK_AIR) == 0 &&
        (isDedicatedAntiAir(attacker) || attacker.attr.and(ATTR_MASK_CAN_AIR_ATK) != 0)

/** The classes whose own definition includes engaging aircraft, so [ATTR_MASK_CAN_AIR_ATK] adds
 *  nothing to them and its movement condition does not apply. */
private fun isDedicatedAntiAir(attacker: EquipmentData): Boolean {
    val attackerClass = attacker.uclass
    return attackerClass == org.osada.UnitClass.AIR_DEFENCE.value ||
        attackerClass == org.osada.UnitClass.FIGHTER.value ||
        attackerClass == org.osada.UnitClass.LEVEL_BOMBER.value ||
        attackerClass == org.osada.UnitClass.TACTICAL_BOMBER.value ||
        attackerClass == org.osada.UnitClass.BATTLESHIP.value ||
        attackerClass == org.osada.UnitClass.BATTLE_CRUISER.value ||
        attackerClass == org.osada.UnitClass.LIGHT_CRUISER.value ||
        attackerClass == org.osada.UnitClass.AIR_TRANSPORT.value
}

/**
 * Whether this shot is one that OG's `CAN Air Atk` grants, and therefore one that OG allows only
 * from a formation that **has not moved this turn**.
 *
 * OG states the ability as *"can attack air units, if it hasn't moved"* — one sentence with two
 * halves, of which OSADA read only the first until 2026-08-27
 * (`docs/og-fidelity-plan.md` §M.1's approximations table). The condition is attached to the GRANT,
 * not to the target: a Fighter or an Air Defence gun engages aircraft because of what it is, moves
 * and fires like any other unit, and is untouched here. Only a record that would be refused the
 * shot but for this bit has to stand still to take it, which is the reading that cannot overstate
 * the rule — the alternative would ground every interceptor in the game.
 *
 * 1,881 of the 56,970 merged records carry the bit and 1,824 of them are outside the dedicated
 * anti-air classes, so it is the bit rather than the class that is deciding in almost every case.
 *
 * State lives in `rules/AttackEligibility.blockedByMovedAirGrant`, which is what asks this; a
 * record on its own cannot know whether it has moved.
 */
fun Equipment.airAttackNeedsAStillAttacker(
    attackerEqid: Int,
    defenderEqid: Int,
): Boolean {
    val attacker = equipmentMap[attackerEqid]
    return attacker != null &&
        equipmentMap[defenderEqid]?.target == org.osada.UnitType.AIR.value &&
        !isDedicatedAntiAir(attacker) &&
        attacker.attr.and(ATTR_MASK_CAN_AIR_ATK) != 0
}
