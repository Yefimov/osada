package org.osada.rules

import org.osada.GameHolder
import org.osada.LeaderType
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.ATTR2_MASK_DISMOUNT_AFTER_MOVE
import org.osada.model.ATTR2_MASK_NO_ZOC
import org.osada.model.ATTR_EX_MASK_AD_SUPPORT
import org.osada.model.ATTR_EX_MASK_ALL_WEATHER
import org.osada.model.ATTR_EX_MASK_EXPLOIT_SUCCESS
import org.osada.model.ATTR_EX_MASK_JET_STEALTH
import org.osada.model.ATTR_EX_MASK_LASTING_SUPPRESSION
import org.osada.model.ATTR_EX_MASK_NO_AMMO_PENALTY
import org.osada.model.ATTR_EX_MASK_NO_INTERCEPT_AIR
import org.osada.model.ATTR_EX_MASK_NO_LEADER
import org.osada.model.ATTR_EX_MASK_OVERRUN_TOGGLE
import org.osada.model.ATTR_EX_MASK_PARTIZAN
import org.osada.model.ATTR_EX_MASK_SINGLE_FIRE_SUP
import org.osada.model.ATTR_EX_MASK_TORPEDO_BOMBER
import org.osada.model.ATTR_MASK_AIR_TRANSPORTABLE
import org.osada.model.ATTR_MASK_CAPTURE_FLAG
import org.osada.model.ATTR_MASK_COMBAT_SUPPORT
import org.osada.model.ATTR_MASK_DISMOUNT
import org.osada.model.ATTR_MASK_MECHANIZED
import org.osada.model.ATTR_MASK_RECON_SKILL
import org.osada.model.ATTR_MASK_SUPPORT_FIRE
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.rules.UnitCapabilities.CAPTURING_CLASSES
import org.osada.rules.UnitCapabilities.canCaptureHex
import org.osada.rules.UnitCapabilities.hasSupportFire
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * Intrinsic, equipment-defined capabilities that are neither leaders nor purchased attachments.
 *
 * `TooManyFunctions` is suppressed rather than split: this is a registry with **one function per
 * OG equipment attribute**, and the whole value of having them in one place is that the
 * class-default vs grant vs `classDefault xor bit` shape of each is readable side by side.
 * Splitting by an arbitrary count would separate attributes that are only correct when compared
 * against each other -- the mistake that left `Dismount` unwired while its two fellow toggles
 * were not.
 */
@Suppress("TooManyFunctions")
object UnitCapabilities {
    /**
     * Unit classes that flip a hex's owner and flag by occupying it **by default**. OG's
     * `Capture Flag` attribute adds to this set per equipment record — see [canCaptureHex].
     *
     * Panzer Marshal has no class check at all (`openpanzer.js:3926` `captureHex`), which is how a
     * destroyer came to "capture" the port at N_Kiel without ownership ever transferring — see
     * `DEFERRED.md` §5.4, settled toward PM on 2026-07-20 and reversed toward OG on 2026-07-26.
     *
     * Artillery, air defence, aircraft, ships, transports and fortifications may still occupy and
     * hold a hex, and still deny it to the enemy, without taking it.
     */
    private val CAPTURING_CLASSES =
        setOf(
            UnitClass.INFANTRY.value,
            UnitClass.TANK.value,
            UnitClass.RECON.value,
            UnitClass.ANTI_TANK.value,
        )

    /**
     * Classes with fire support ON BY DEFAULT. OG's `Support Fire` attribute **reverses** this — see
     * [hasSupportFire].
     *
     * Sole source of truth, shared by `CombatResolver.isSupportFireEligible` (the rule) and
     * `EquipmentMarkings` (the badge) so the two cannot drift — the mistake §4.6 records.
     */
    private val SUPPORT_FIRE_CLASSES = setOf(UnitClass.ARTILLERY.value)

    /**
     * Classes that fire back at an AIR attacker on behalf of an adjacent friendly defender.
     *
     * Fighter is in the set deliberately and faithfully: `openpanzer.js:2642` tests
     * `flak || airDefence || fighter`, so a friendly fighter parked beside a bombed unit really
     * does fire back. That is why a Yak-1 carries the AA badge.
     */
    private val AIR_DEFENCE_FIRE_CLASSES =
        setOf(
            UnitClass.FLAK.value,
            UnitClass.AIR_DEFENCE.value,
            UnitClass.FIGHTER.value,
        )

    /**
     * OG's *"BB, CV && BC can fire as flaks"* — the capital-ship classes the scenario option
     * `opt_capitals_as_flak` adds to [AIR_DEFENCE_FIRE_CLASSES]. **197 of the 397 deployed
     * scenarios author it**, wired 2026-08-30.
     *
     * > *"BB, CV & BC can fire as FlaKs: those ship classes can defend from air attacks with a
     * > range of 1, and attack planes at their range."* — `Manual_OSuite-Scenario.pdf` p.23
     *
     * OG's three abbreviations map onto OSADA's own classes exactly: BB = Battleship, CV = Carrier,
     * BC = Battle Cruiser. **Cruiser and Light Cruiser are deliberately excluded** — OG names three
     * classes and OSADA has four where OG's `CShip` has one (`docs/og-sources.md`), so adding the
     * two OG does not name would arm ships OG leaves unarmed. That is the reading that cannot
     * overstate; if `CShip` turns out to cover all four, this set is the one line to widen.
     *
     * The `airatk > 0` test in [hasAirDefenceFire] still applies, so this cannot give a ship an
     * anti-air role its own equipment record does not support.
     */
    private val CAPITAL_FLAK_CLASSES =
        setOf(
            UnitClass.BATTLESHIP.value,
            UnitClass.CARRIER.value,
            UnitClass.BATTLE_CRUISER.value,
        )

    /**
     * Whether this equipment has phased movement: OG's class default for Recon, **reversed** by
     * its `Recon Skill` attribute (`attr` bit 10) — the same `classDefault xor bit` shape as
     * [hasSupportFire], not a plain grant. `Recon Skill` was already inside the original 24-bit
     * `attr` word (no importer widening needed for this one), but was read only as the Recon class
     * until 2026-08-19 — `docs/og-fidelity-plan.md`'s own approximations list named this as one of
     * three badges (RCN/OVR/AA) sourced from class alone rather than OG's real equipment toggle.
     *
     * Measured over 46,978 `eqp-united` records: bit 10 is set on 10 of 2,880 Recon records
     * (0.3%) and 3,848 non-Recon ones — a grant would land on nearly every Recon unit, not ten, so
     * the toggle reading is the only one the data supports (`OG_ABILITY_AUDIT.md` §2).
     */
    fun hasPhasedMovement(data: EquipmentData): Boolean =
        if (equipmentTogglesEnabled()) {
            (data.uclass == UnitClass.RECON.value) xor (data.attr and ATTR_MASK_RECON_SKILL != 0)
        } else {
            data.uclass == UnitClass.RECON.value
        }

    /**
     * Whether OG's per-record toggles decide [hasPhasedMovement] and [canOverrun], or the class
     * alone does ([RuleKey.EQUIPMENT_TOGGLES]).
     *
     * Both helpers consult it rather than their callers, and that placement is the point: the
     * BADGE and the RULE read the same function, so a unit can never wear a mark stating
     * something the engine will not do. Until 2026-08-25 they could — see the key's own
     * documentation for what that cost.
     */
    fun equipmentTogglesEnabled(): Boolean = ActiveRuleset.flag(RuleKey.EQUIPMENT_TOGGLES, false)

    /**
     * Classes OG makes fire BEFORE moving unless they are mechanized: the heavy weapons that have to
     * be unlimbered to shoot.
     *
     * Only consulted while `heavy_move_fire` asks for OG's ordering -- OSADA Default has no move/fire
     * ordering restriction at all, and adding one universally would re-tune every shipped scenario.
     */
    private val MOVE_THEN_FIRE_RESTRICTED_CLASSES =
        setOf(
            UnitClass.ARTILLERY.value,
            UnitClass.AIR_DEFENCE.value,
        )

    /** Whether OG's move/fire ordering restriction applies to this equipment's class at all. */
    fun isHeavyWeapon(data: EquipmentData): Boolean = data.uclass in MOVE_THEN_FIRE_RESTRICTED_CLASSES

    /**
     * Whether this equipment carries OG's `Mechanized` attribute (`attr` bit 21): the crew rides its
     * own prime mover, so the gun is exempt from the fire-before-moving restriction.
     *
     * A plain grant, not one of the six toggles -- it sits in `Special3` and is read exactly as it
     * is written. The `Mechanized Veteran` leader is the second source of the same exemption, the
     * same way `Reconnaissance Movement` is the second source of phased movement; see
     * [AttackEligibility.blockedByMoveThenFire], which ORs them.
     */
    fun isMechanized(data: EquipmentData): Boolean = data.attr and ATTR_MASK_MECHANIZED != 0

    /**
     * Whether this equipment may Overrun: OG's class default for Tank, **reversed** by its
     * `Overrun toggle` attribute (`SpecialEx` bit 60.3, `attrEx` bit 3) — `classDefault xor bit`,
     * the same shape [hasPhasedMovement] and [hasSupportFire] already use. Wired 2026-08-19 once
     * `attrEx` widened the import past `attr`'s 24 bits; the plan's own approximations list named
     * the plain Tank-class read as the OVR badge's stand-in for OG's real toggle.
     *
     * **The toggle reading is proven, not inferred** — OG's own localisation template
     * (`OPENTXT_SAMPLE/strings-en-template.txt`) carries this attribute as a PAIR of alternative
     * labels, line 872 `Can Overrun` and line 873 `Cannot Overrun`, exactly as it does for
     * [hasSupportFire] (845/846) and `Dismount` (843/844). See [hasSupportFire]'s doc for the full
     * block. A grant would need one label, not two opposite ones.
     *
     * So a TANK carrying the bit shows **no** OVR badge, and that is faithful: asked about
     * `OT-133`, `OT-134` and `BT-7TU` (EFILE_ATOMIC, all Tank class, all carrying `attrEx` bit 3),
     * the answer is that OG switches Overrun off for those three itself. The flame-tank and
     * command-tank variants are precisely the ones an efile author would exclude from an overrun
     * charge.
     *
     * `eqp-united` population: 353 records carry the bit after the 2026-08-23 back-fill
     * (`docs/og-fidelity-plan.md` §J). One source of inertness remains and is deliberate: a record
     * whose only source is `eqp-olgcw` keeps `attrEx` at 0 because that efile's format has no
     * `SpecialEx` field at all (§J.2), so the Tank-class default stands for it.
     */
    fun canOverrun(data: EquipmentData): Boolean =
        if (equipmentTogglesEnabled()) {
            (data.uclass == UnitClass.TANK.value) xor (data.attrEx and ATTR_EX_MASK_OVERRUN_TOGGLE != 0)
        } else {
            data.uclass == UnitClass.TANK.value
        }

    /**
     * Whether this equipment flips a hex's owner and flag: [CAPTURING_CLASSES] by default, **plus**
     * anything carrying OG's `Capture Flag` attribute (`attr` bit 20).
     *
     * Takes [EquipmentData] rather than a unit so the equipment card can state it too; the caller
     * decides whether to pass the real unit or its transport.
     *
     * **`Capture Flag` supplements the class default rather than replacing it**, which is what makes
     * this safe to read. Measured over 46,978 `eqp-united` records, the flag is essentially never set
     * on a class that already captures — Infantry 1.0%, **Tank 0.0% (1 of 3,024)**, Recon 0.1%,
     * Anti-Tank 0.8%, 0.7% across the four — against 37.7% of every other class. Aircraft, which can
     * never capture, are likewise almost untouched (Fighter 0.1%, bombers none). A flag meaning
     * anything else would not distribute like that. Bit 20 itself is confirmed twice over: BASEKORP
     * `Gaz-3` (`E 528`) and `Armoured Train` (`E 2814`) both report `Capture Flag` in OG's equipment
     * view and both match their predicted `attr` exactly.
     *
     * **This resolves §5.4's apparent contradiction rather than overriding it.** That entry read
     * "only ground units take a hex" off a measurement at N_Kiel, where a destroyer occupied a port
     * and ownership never moved. N_Kiel runs `eqp-kaiser`, and **0 of KAISER's 288 naval records
     * carry bit 20** — so the observation was right and only its generalisation was wrong. Naval
     * capture is per-efile authored data, not an engine rule: `ag` also grants it to no ship and
     * `comww2` to 1%, while `atomic` grants it to 99%, `olgcw` 94%, `olgww2` 77%, `basekorp` 74% and
     * `lxf` 68%.
     */
    fun canCaptureHex(data: EquipmentData): Boolean =
        data.uclass in CAPTURING_CLASSES || data.attr and ATTR_MASK_CAPTURE_FLAG != 0

    /**
     * Whether this equipment fires back at a GROUND attacker on behalf of an adjacent friendly
     * defender: OG's class default, **reversed** by its `Support Fire` attribute (`attr` bit 12).
     *
     * **`Support Fire` is a TOGGLE, not a grant** — `OG_ABILITY_AUDIT.md` §2 says so
     * (`reverses fire support, on by default for Artillery`) and warns that reading any of OG's six
     * toggles as "has ability X" is wrong for half the units carrying it. The data agrees decisively.
     * Measured over 46,978 `eqp-united` records, both toggles that survive the importer are
     * **rare on the very class they default to**, which only makes sense if the bit means "reverse":
     *
     * | bit | ability | on its own default class | on all other classes |
     * |---|---|---|---|
     * | 10 | Recon Skill | **0.4% of Recon** (14 of 3,312) | 4,977 |
     * | 12 | Support Fire | 13.9% of Artillery (893 of 6,409) | 8,357 |
     *
     * Recon Skill settles it: a grant would be set on nearly every Recon unit, and it is set on 14.
     *
     * **PROVEN OUTRIGHT 2026-08-23, from OG's own UI strings**, after the owner asked directly of
     * artillery units wearing the SUP badge unevenly: *"Some Artillery units have SUP. Why not all
     * of them? Are you sure that you are not wrong?"* OG's own localisation template
     * (`OPENTXT_SAMPLE/strings-en-template.txt`, in the Open General install) is ground truth
     * here, and its "Special attributes"
     * block (lines 837-877) gives ONE attribute two alternative renderings wherever the attribute
     * is a toggle:
     *
     * ```
     * 845: NO support fire        <- shown when the unit's class defaults to support fire
     * 846: Support fire           <- shown when it does not
     * 872: Can Overrun            <- the same pair for `Overrun toggle`
     * 873: Cannot Overrun
     * 843: NO dismount when attacked / 844: Dismount if attacked   -- and for `Dismount`
     * ```
     *
     * and the same file's PEBM letter-code list spells the artillery case out in full: line 424
     * reads **"Artillery without support fire"**. One bit, two opposite meanings depending on the
     * class — which is `classDefault xor bit` exactly. Three toggles are named this way and OSADA
     * reads all three; no fourth attribute in that block is paired, so no other bit is a toggle.
     *
     * So the answer to "why not all artillery" is: OG itself switches support fire OFF for 893 of
     * its 6,409 artillery records, and ON for 8,357 records outside the class — 75% of Anti-Tank,
     * 58% of Flak, 50% of Destroyer, 34% of Fortification and Cruiser, and 18% of Infantry, which
     * is why a campaign drawing on an efile that uses the bit heavily can field infantry that all
     * carry SUP. That is OG's authored data, not an OSADA rule.
     *
     * **A third row was dropped 2026-08-18: "bit 0 Lasting Sup., 12% of Tactical Bomber".** Bit 0 is
     * `Drop mines`, a plain grant, and those 12% are maritime-patrol minelayers; `Lasting Sup.` is
     * `SpecialEx` 60.1, which is `attrEx` bit 1 — imported since 2026-08-19 and read by
     * [hasLastingSuppression], not the unimported field this comment called it until 2026-08-26.
     * Proved by controlled staircase — `OG_ABILITY_AUDIT.md` §7.1.1. The conclusion below is
     * unaffected either way: it never rested on that row.
     *
     * So OG's effective rule is `classDefault xor bit`. Against PM's plain `uclass == ARTILLERY`
     * this keeps support fire on the 86% of artillery that has it, removes it from the 893 records
     * OG explicitly switches off, and adds the 8,357 non-artillery records OG switches on. 13,873
     * records end up with the role against PM's 6,409.
     *
     * No class filter is layered on top of the toggle: 109 of the switched-on records are aircraft,
     * and whether OG lets an aircraft support-fire against a ground attacker is unverified — but
     * `isSupportFireEligible` still runs `AttackEligibility.canInitiateAttack`, which gates on ammo
     * and on the can't-attack-soft/hard bits. Recorded in `DEFERRED.md` §7.32.
     */
    fun hasSupportFire(data: EquipmentData): Boolean =
        (data.uclass in SUPPORT_FIRE_CLASSES) xor (data.attr and ATTR_MASK_SUPPORT_FIRE != 0)

    /**
     * The class check alone over-promises: it granted the AA badge to units that cannot fire on
     * aircraft at all. Measured in `eqp-lxf`: 4 Flak and 9 Air Defence records have `airatk = 0` —
     * every one of them a RADAR set (`Mobile Radar`, `SCR-584`, `SCR-268`, `RDT-4 Folaga`). The
     * real rule then rejects them anyway, because `CombatResolver.isSupportFireEligible` also runs
     * `AttackEligibility.canInitiateAttack`, which fails on a zero air attack. So the badge claimed
     * a role combat would never grant — the §4.6 failure mode, in the very predicate §7.14 shared
     * to prevent it. Sharing the class list was not enough; the capability has to be checked too.
     *
     * **Extended 2026-08-19** with OG's `AD Support` grant (`SpecialEx` bit 61.5, `attrEx` bit 13),
     * added 2026-08-19. `AD Support` is a plain grant, not a toggle — it **supplements**
     * [AIR_DEFENCE_FIRE_CLASSES] the same way `Capture Flag` supplements its four ground classes,
     * so OR is correct here: a class already in the set is unaffected by also carrying the bit,
     * and a unit outside the set gains the role only by carrying it. `docs/og-fidelity-plan.md`'s
     * approximations list named this the AA badge's third missing source, alongside RCN/OVR above.
     * No separate `airatk > 0` guard is needed on the bit path: `AttackEligibility.canFire` already
     * requires a nonzero `airatk` before any air target can be engaged, regardless of which path
     * granted eligibility — measured over `eqp-united`, only 1 of 745 `AD Support` records has
     * `airatk = 0`, so this is not a live concern either way.
     */
    fun hasAirDefenceFire(data: EquipmentData): Boolean {
        val capitalFires =
            GameHolder.instance?.scenario?.capitalShipsAsFlak == true &&
                data.uclass in CAPITAL_FLAK_CLASSES
        val byClass = data.uclass in AIR_DEFENCE_FIRE_CLASSES || capitalFires
        return (byClass && data.airatk > 0) || data.attrEx and ATTR_EX_MASK_AD_SUPPORT != 0
    }

    /**
     * Whether [unit] lends experience bars to adjacent friendlies — OG's `Combat Support` equipment
     * ability (`attr` bit 16), or the Combat Support leader.
     *
     * **Was sourced from the unit's NAME** (`isHeadquarters`: does it contain "HQ" or
     * "headquarters"?). `OG_ABILITY_AUDIT.md` labelled that `INFERENCE`-grade and §1 of that file
     * states the rule flatly: *"Never infer a layer from a displayed name."* Bit 16 was confirmed
     * 2026-07-27 by two records read in OG's own equipment view — BASEKORP `43 HQ` (`E 3814`), whose
     * only enabled ability is `Combat Support` and whose `attr` is exactly `65536`, and `Komissar`,
     * which reports it among five.
     *
     * The name rule was badly wrong, measured over the 56,970 shipped `eqp-united` records: 1,645
     * carry bit 16, the name test matched 306 records, of which 290 agree — so it **missed 1,355**
     * and invented 16. The misses are not edge cases: `04 General Staff`, `70 Estado Mayor`,
     * `21 Alpini`, `24 KOP`, commissars, squadron leaders. Nor are the inventions: five of the
     * sixteen (`HQ-1`, `HQ-2`, `HQ-7`, `HQ-17`, `Hong Qi HQ-1`) are Chinese surface-to-air
     * missiles whose designation merely begins "HQ".
     *
     * `isHeadquarters` was deleted 2026-08-23. It survived until then as the source of the
     * equipment card's descriptive "headquarters" note, on the reasoning that a label is not a
     * capability — but the note it produced is the Combat Support rule stated in full, so on the
     * card it read as exactly the capability claim the badge makes. Both surfaces read the bit now.
     */
    fun hasCombatSupport(unit: GameUnit): Boolean =
        unit.unitData(true).attr and ATTR_MASK_COMBAT_SUPPORT != 0 ||
            Leaders.unitHasLeader(unit, LeaderType.COMBAT_SUPPORT)

    /**
     * The equipment-record half of [hasCombatSupport]: OG's `Combat Support` grant (`attr` bit 16)
     * read straight off the record, with no live unit and so no leader-conferred half. For the
     * catalog/purchase-list surfaces ([EquipmentMarkings], [equipmentMechanicsNote]) that show a
     * badge before a unit exists to own a leader.
     */
    fun grantsCombatSupport(data: EquipmentData): Boolean = data.attr and ATTR_MASK_COMBAT_SUPPORT != 0

    /** Sum of experience bars lent by adjacent friendly Combat Support units. */
    fun combatSupportBars(
        units: List<GameUnit>,
        recipient: GameUnit,
    ): Int {
        val pos = recipient.getPos()
        val side = recipient.player?.side
        if (pos == null || side == null) return 0
        val recipientIsAir = UnitPredicates.isAir(recipient)
        return units.sumOf { supporter ->
            val supporterPos = supporter.getPos()
            val eligible =
                supporter !== recipient &&
                    !supporter.destroyed &&
                    supporterPos != null &&
                    supporter.player?.side == side &&
                    UnitPredicates.isAir(supporter) == recipientIsAir &&
                    HexGeometry.distance(pos.row, pos.col, supporterPos.row, supporterPos.col) == 1 &&
                    hasCombatSupport(supporter)
            if (eligible) UnitExperience.bars(supporter) else 0
        }
    }

    /**
     * Whether [unit] carries OG's `All Weather` equipment special (`SpecialEx` bit 60.2, `attrEx`
     * bit 2) — allows attacking (and being attacked) despite bad weather, the equipment-level
     * source `WeatherCombatRules.isAllWeather` was missing until 2026-08-19: it previously read
     * only [LeaderType.ALL_WEATHER_COMBAT]. Read on the unit's REAL equipment (`useReal = true`,
     * matching [hasCombatSupport]) rather than a carrier/transport it happens to be riding.
     */
    fun hasAllWeather(unit: GameUnit): Boolean = unit.unitData(true).attrEx and ATTR_EX_MASK_ALL_WEATHER != 0

    /**
     * Whether suppression [unit] inflicts survives the round wrap instead of clearing at the
     * ordinary per-round reset (`model/GameUnitLifecycle.kt`'s `unitEndTurn`, see §0.1.1) — OG's
     * `Lasting Suppression` equipment special (`SpecialEx` bit 60.1, `attrEx` bit 1) and the
     * `SHOCK_TACTICS` leader trait are two independent sources of the identical effect. Wired
     * 2026-08-19 exactly as `docs/og-fidelity-plan.md` §0.1 said it must be when it became
     * reachable: *"this unit's `hits` survive the victim's `unitEndTurn`"*, not a second
     * suppression statistic — [GameUnit.lastingHits] is that same field either way.
     */
    fun hasLastingSuppression(unit: GameUnit): Boolean =
        Leaders.unitHasLeader(unit, LeaderType.SHOCK_TACTICS) ||
            unit.unitData(true).attrEx and ATTR_EX_MASK_LASTING_SUPPRESSION != 0

    /**
     * Whether OG's `No Intercept Air` special (`SpecialEx` bit 60.5, `attrEx` bit 5) vetoes [unit]
     * from the interception system specifically. **Not** a general anti-air eligibility check —
     * `OG_ABILITY_AUDIT.md` §2's measured table is explicit that this bit "disables movement
     * interception for AD/FlaK/Fighter... [and leaves] ordinary defensive AA fire unaffected", so
     * it is read only by [org.osada.rules.AAInterception]'s interception path, never by
     * [hasAirDefenceFire] (which also drives the AA badge and would incorrectly hide it too).
     */
    fun hasNoInterceptAir(unit: GameUnit): Boolean = unit.unitData(true).attrEx and ATTR_EX_MASK_NO_INTERCEPT_AIR != 0

    /**
     * Whether mounted [data] dismounts when attacked and fights with its OWN statistics instead of
     * its transport's: OG's class default for Infantry, **reversed** by the `Dismount` attribute
     * (`attr` bit 11) — `classDefault xor bit`, the same shape [hasSupportFire] and [canOverrun]
     * use.
     *
     * **The third and last of OG's three paired toggles to be wired (2026-08-25), and the one this
     * object's own documentation claimed was already read.** [hasSupportFire]'s note said *"three
     * toggles are named this way and OSADA reads all three"* and counted [hasPhasedMovement] as
     * the third — but `Recon Skill` is not one of the paired attributes in OG's string template;
     * `Dismount` (lines 843 `NO dismount when attacked` / 844 `Dismount if attacked`) is. Until
     * this function existed, `AttackCalculation.resolveCombatContext` gave the dismount to every
     * mounted Infantry record unconditionally, and the 2,628 shipped records carrying the bit drew
     * a grey "decoded, not executed" badge that was telling the truth.
     *
     * Takes [EquipmentData] rather than a unit so the equipment card can state it too; the caller
     * passes the unit's REAL equipment, never the transport it is riding — the ability belongs to
     * the passengers, not to the truck.
     */
    fun dismountsWhenAttacked(data: EquipmentData): Boolean =
        (data.uclass == UnitClass.INFANTRY.value) xor (data.attr and ATTR_MASK_DISMOUNT != 0)

    /**
     * Whether [unit] projects a Zone of Control onto its neighbouring hexes at all: everything
     * non-air does, **except** a record carrying OG's `No ZOC` attribute (`attr2` bit 6).
     *
     * `OG_ABILITY_AUDIT.md` filed this ability as **"no — and not representable"**, on the reading
     * that `MovementRules.setZOCRange` hands every non-air unit a full ZOC with no opt-out. That
     * was true of the code as it then stood and is not any more: `setZOCRange` is a single choke
     * point that adds and removes one reference count, so declining to enter the loop is the whole
     * implementation, and both readers of the result (`MoveRangeCalculation`'s movement floor and
     * `MoveExecutorHelpers.stoppedByUnseenZoc`) go through `Hex.isZOC`. 609 shipped records carry
     * the bit.
     *
     * **Read on the unit's REAL equipment on purpose.** `setZOCRange` is called once to add and
     * once to remove, and the two calls must agree or the hex's reference count drifts permanently
     * — a unit that mounted a transport between them would otherwise remove a ZOC it never added.
     * `useReal = true` cannot change while a unit is on the map.
     *
     * **The air exemption became conditional on 2026-08-27.** OG §6.30 says air units *"usually"*
     * have no zone of control, and the word is a scenario option: see [AirZoneOfControl], which
     * satisfies the same add/remove symmetry because both of its inputs are fixed for the whole
     * scenario. `No ZOC` still beats it — a record that says it projects none projects none,
     * whatever class it is.
     */
    fun projectsZoneOfControl(unit: GameUnit): Boolean =
        (!UnitPredicates.isAir(unit) || AirZoneOfControl.enabled()) &&
            unit.unitData(true).attr2 and ATTR2_MASK_NO_ZOC == 0

    /**
     * Whether [data] is exempt from the penalties an empty formation pays — OG's `No run out ammo
     * penalty` (`attrEx` bit 4, template line 874), 824 of the 56,970 shipped records.
     *
     * **Exempt from the PENALTIES, not from the prohibition.** OG 6.23 says a unit with no
     * ammunition *"cannot attack and defends with halved unsuppressed strength"* and has *"halved
     * initiative"*; this bit is named for the penalty, so it lifts the two halvings
     * ([UnitConditionPenalties.dryInitiative] and its defence half) and leaves
     * `AttackEligibility.canFire`'s "no ammo, no attack" exactly where it is. Reading it as a
     * licence to attack from an empty magazine would be inventing a mechanic from a name, which
     * `OG_ABILITY_AUDIT.md` §1 forbids.
     *
     * Inert unless `dry_unit_penalties` is on, because there is no penalty to be exempt from
     * otherwise — so this changes nothing outside the Open General Fidelity profile. The
     * population is what the name predicts: submarines (141), tactical bombers (123), destroyers
     * (118), air transports (92) — units whose ammunition is a sortie rather than a magazine.
     */
    fun ignoresDryAmmoPenalty(data: EquipmentData): Boolean = data.attrEx and ATTR_EX_MASK_NO_AMMO_PENALTY != 0

    /**
     * OG's `SingleFireSup.` (`SpecialEx` 61.7, `attrEx` bit 15) — *"one fire-support action per
     * turn"*. Wired 2026-08-27; 166 shipped records carry it.
     *
     * **A restriction on the SUPPORTER, not on what it supports.** OG §6.24 lets an ordinary
     * battery answer for every neighbour attacked within its range, as often as it is called on;
     * this bit is the exception that spends the gun after one answer. Read in
     * `CombatResolver.isSupportFireEligible` against [org.osada.model.GameUnit.hasSupportedThisTurn],
     * which `CombatApplication` sets when a support shot is actually committed — never when one is
     * merely previewed, or the attack forecast would spend a gun that has not fired.
     *
     * **Its shape is the one `hasInterceptedThisTurn` already had**, deliberately: OG's own
     * §6.24 pairs support fire with interception (*"the air equivalent of support fire is
     * interception... fighters can only do one interception each turn"*), so the once-per-turn
     * restriction and the flag that expresses it are the same idea on the two sides of that
     * sentence. It inherits that flag's limitation as well — neither survives a save.
     */
    fun supportsOnlyOncePerTurn(data: EquipmentData): Boolean = data.attrEx and ATTR_EX_MASK_SINGLE_FIRE_SUP != 0

    /**
     * Whether an organic transport may be carried aboard an AIR transport with the formation that
     * owns it, rather than being left on the airfield. Wired 2026-08-27, **narrowed the same day**.
     *
     * > *"AirTransportable special is only needed to be set for transport"* — OpenGen changelog
     *
     * That sentence settles what the manual left ambiguous. OG's older text says the organic
     * transport *"must be also Airmobile/Airborne"*, and PG2 required the special on BOTH the unit
     * and its transport; OG requires it on the **transport alone**, and the `embark` field is not
     * an alternative route.
     *
     * **The first build ORed the two** — special OR `embark >= Airmobile` — reasoning from the two
     * fields' partial overlap (401 of the 673 shipped ground transports that carry either). That
     * was too permissive: it flew 167 transports OG leaves on the ground. Only the bit decides now.
     *
     * 506 of the 5,937 shipped ground-transport records carry it, so the great majority of prime
     * movers stay behind when their formation is airlifted — which is the rule, and why the Embark
     * action says so before the player commits.
     */
    fun transportSurvivesAirlift(data: EquipmentData): Boolean = data.attr and ATTR_MASK_AIR_TRANSPORTABLE != 0

    /**
     * OG's `Jet (Stealth)` (`attrEx` bit 19) — a jet that ground air defence can only intercept
     * with a jet-capable interceptor. Wired 2026-08-27; 2,053 records.
     */
    fun hasJetStealth(data: EquipmentData): Boolean = data.attrEx and ATTR_EX_MASK_JET_STEALTH != 0

    /**
     * OG's `Partizan` (`attrEx` bit 10) — the formation is not halted by an enemy zone of control.
     * Wired 2026-08-27; 720 records, 680 of them Infantry.
     *
     * **This is the same behaviour as the `Superior Maneuver` commander**, which OSADA already
     * runs, so the ability joins that predicate rather than adding a second way to ignore a ZOC.
     * The manual's *"cannot be surprised"* is a simplification of it: a formation that is never
     * halted never walks into the ambush a halt sets up.
     */
    fun ignoresZoneOfControl(data: EquipmentData): Boolean = data.attrEx and ATTR_EX_MASK_PARTIZAN != 0

    /**
     * OG's `Exploit Success` (`attrEx` bit 11) — after an ordinary attack that kills the defender
     * or forces it to retreat, the attacker keeps its remaining movement. Wired 2026-08-27; 456
     * records, 401 of them Infantry.
     *
     * **Not Overrun.** Overrun predicts a zero-loss kill, skips normal combat and returns BOTH
     * movement and the shot; this fights the combat, spends the shot, and gives back only the
     * movement. `AttackCalculation.resolveOverrunAndExperienceGain` owns the other one.
     */
    fun exploitsSuccess(data: EquipmentData): Boolean = data.attrEx and ATTR_EX_MASK_EXPLOIT_SUCCESS != 0

    /**
     * OG's `Torpedo bomber` (`attrEx` bit 8) — may attack an adjacent unit while both it and its
     * target are over SEA terrain. Wired 2026-08-27; 385 records, 323 of them Tactical Bombers.
     *
     * A narrow grant: it adds the range-1 attack over water and nothing else — not ammunition, not
     * target class, not the number of attacks in a turn. Read by
     * `AttackEligibility.torpedoRunPermitted`.
     */
    fun isTorpedoBomber(data: EquipmentData): Boolean = data.attrEx and ATTR_EX_MASK_TORPEDO_BOMBER != 0

    /**
     * Whether OG's torpedo run is available: [isTorpedoBomber], the target adjacent, and **both
     * hexes over open sea** — the three conditions OG states and no more.
     *
     * It ADDS to `AttackEligibility.canFire`'s "can it hurt anything but aircraft?" gate rather
     * than replacing it: a torpedo bomber whose record already carries a naval attack was never
     * refused, and this is for the one whose only weapon is the torpedo.
     */
    @Suppress("ReturnCount") // three unresolvable inputs, each of which means "no torpedo run"
    fun torpedoRunPermitted(
        attacker: GameUnit,
        defender: GameUnit,
    ): Boolean {
        val aPos = attacker.getPos() ?: return false
        val dPos = defender.getPos() ?: return false
        val grid =
            GameHolder.instance
                ?.scenario
                ?.map
                ?.map ?: return false
        val overSea = { row: Int, col: Int ->
            grid.getOrNull(row)?.getOrNull(col)?.terrain == TerrainType.OCEAN.value
        }
        return isTorpedoBomber(attacker.unitData(true)) &&
            HexGeometry.distance(aPos.row, aPos.col, dPos.row, dPos.col) <= 1 &&
            overSea(aPos.row, aPos.col) &&
            overSea(dPos.row, dPos.col)
    }

    /**
     * Whether [data] can ever produce a commander — the inverse of OG's `Cannot get a leader`
     * (`attrEx` bit 0, template line 869), which 537 shipped records carry.
     *
     * **Production, not possession**, which is exactly what the shipped string already promised the
     * player (*"this equipment never produces a commander"*). Two mechanics produce one in OSADA
     * and both consult this: the legacy integer leader ([Leaders.generateLeader], which also covers
     * the leaders a scenario's own units are born with) and Phase 2 hero emergence
     * (`HeroCampaign.attemptEmergence`). A core formation that ALREADY has a commander and then
     * upgrades into such a record keeps them and keeps progressing — `HeroCampaign.progressCommander`
     * is untouched — because a hero belongs to the formation, not to the equipment it is currently
     * issued.
     *
     * The population reads like the rule: Infantry (207) — militia, penal and partisan records —
     * then Tactical Bomber (91), Fortification (69) and Flak (35).
     */
    fun canProduceLeader(data: EquipmentData): Boolean = data.attrEx and ATTR_EX_MASK_NO_LEADER == 0

    /**
     * Whether mounted [data] steps down by itself once its ride ends — OG's `Dismount after
     * movement` (`attr2` bit 1, template line 860, manual §7.2: *"unit dismounts from its transport
     * after completing movement"*), 662 shipped records.
     *
     * A plain grant, and an AUTOMATIC one. OG 8.3's base rule is that a formation *"can only mount
     * or dismount before moving"* and one that moved aboard its transport stays there until its next
     * turn; this bit is the exception to that, so it is applied by `MoveExecutor.dismountAfterMove`
     * rather than being offered as an action. It costs nothing and requires no movement left.
     *
     * **A permission reading stood for part of 2026-08-26** — that the record MAY dismount after
     * moving, spending what movement remained — and was corrected against the manual the same day
     * (§Q). Nothing in OG's rules charges movement for it.
     *
     * Distinct from [dismountsWhenAttacked], which is about which statistics a mounted formation
     * FIGHTS with and is a toggle of the Infantry class default. The population is every mountable
     * ground class — Infantry 427, Artillery 157, Recon 32, Ground Transport 19, Anti-Tank 12.
     */
    fun dismountsAfterMove(data: EquipmentData): Boolean = data.attr2 and ATTR2_MASK_DISMOUNT_AFTER_MOVE != 0
}
