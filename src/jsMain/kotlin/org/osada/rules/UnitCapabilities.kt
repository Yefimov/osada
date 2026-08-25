package org.osada.rules

import org.osada.LeaderType
import org.osada.UnitClass
import org.osada.model.ATTR_EX_MASK_AD_SUPPORT
import org.osada.model.ATTR_EX_MASK_ALL_WEATHER
import org.osada.model.ATTR_EX_MASK_LASTING_SUPPRESSION
import org.osada.model.ATTR_EX_MASK_NO_INTERCEPT_AIR
import org.osada.model.ATTR_EX_MASK_OVERRUN_TOGGLE
import org.osada.model.ATTR_MASK_CAPTURE_FLAG
import org.osada.model.ATTR_MASK_COMBAT_SUPPORT
import org.osada.model.ATTR_MASK_MECHANIZED
import org.osada.model.ATTR_MASK_RECON_SKILL
import org.osada.model.ATTR_MASK_SUPPORT_FIRE
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.rules.UnitCapabilities.CAPTURING_CLASSES
import org.osada.rules.UnitCapabilities.canCaptureHex
import org.osada.rules.UnitCapabilities.hasSupportFire

/** Intrinsic, equipment-defined capabilities that are neither leaders nor purchased attachments. */
object UnitCapabilities {
    const val EXPERIENCE_PER_BAR = 100

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
        (data.uclass == UnitClass.RECON.value) xor (data.attr and ATTR_MASK_RECON_SKILL != 0)

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
        (data.uclass == UnitClass.TANK.value) xor (data.attrEx and ATTR_EX_MASK_OVERRUN_TOGGLE != 0)

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
     * of them? Are you sure that you are not wrong?"* `C:\Games\Open General\OPENTXT_SAMPLE     * strings-en-template.txt` is OG's own localisation template, and its "Special attributes"
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
     * `SpecialEx` 60.1 and is not imported. Proved by controlled staircase — `OG_ABILITY_AUDIT.md`
     * §7.1.1. The conclusion below is unaffected: it never rested on that row.
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
    fun hasAirDefenceFire(data: EquipmentData): Boolean =
        (data.uclass in AIR_DEFENCE_FIRE_CLASSES && data.airatk > 0) ||
            data.attrEx and ATTR_EX_MASK_AD_SUPPORT != 0

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
            if (eligible) supporter.experience / EXPERIENCE_PER_BAR else 0
        }
    }

    /**
     * Whether [unit] carries OG's `All Weather` equipment special (`SpecialEx` bit 60.2, `attrEx`
     * bit 2) — allows attacking (and being attacked) despite bad weather, the equipment-level
     * source `WeatherCombatRules.isAllWeather` was missing until 2026-08-19: it previously read
     * only [LeaderType.ALL_WEATHER_COMBAT]. Read on the unit's REAL equipment (`useReal = true`,
     * matching [hasCombatSupport]) rather than a carrier/transport it happens to be riding.
     */
    fun hasAllWeather(unit: GameUnit): Boolean =
        unit.unitData(true).attrEx and ATTR_EX_MASK_ALL_WEATHER != 0

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
    fun hasNoInterceptAir(unit: GameUnit): Boolean =
        unit.unitData(true).attrEx and ATTR_EX_MASK_NO_INTERCEPT_AIR != 0
}
