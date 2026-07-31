package org.osada.rules

import org.osada.LeaderType
import org.osada.UnitClass
import org.osada.model.ATTR_MASK_CAPTURE_FLAG
import org.osada.model.ATTR_MASK_COMBAT_SUPPORT
import org.osada.model.ATTR_MASK_SUPPORT_FIRE
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Leaders
import org.osada.rules.UnitCapabilities.CAPTURING_CLASSES
import org.osada.rules.UnitCapabilities.canCaptureHex
import org.osada.rules.UnitCapabilities.hasSupportFire
import org.osada.rules.UnitCapabilities.isHeadquarters

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

    fun isHeadquarters(data: EquipmentData): Boolean {
        val words = data.name.split(' ', '-', '/', '(', ')')
        return data.name.contains("headquarters", ignoreCase = true) || words.any { it.equals("HQ", ignoreCase = true) }
    }

    fun hasPhasedMovement(data: EquipmentData): Boolean = data.uclass == UnitClass.RECON.value

    fun canOverrun(data: EquipmentData): Boolean = data.uclass == UnitClass.TANK.value

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
     * Measured over 46,978 `eqp-united` records, all three toggles that survive the importer are
     * **rare on the very class they default to**, which only makes sense if the bit means "reverse":
     *
     * | bit | ability | on its own default class | on all other classes |
     * |---|---|---|---|
     * | 0 | Lasting Sup. | 12% of Tactical Bomber | 8,622 |
     * | 10 | Recon Skill | **0.3% of Recon** (10 of 2,880) | 3,848 |
     * | 12 | Support Fire | 13% of Artillery (700 of 5,254) | 7,237 |
     *
     * Recon Skill settles it: a grant would be set on nearly every Recon unit, and it is set on ten.
     *
     * So OG's effective rule is `classDefault xor bit`. Against PM's plain `uclass == ARTILLERY`
     * this keeps support fire on the 87% of artillery that has it, removes it from the 700 records
     * OG explicitly switches off, and adds the 7,237 non-artillery records OG switches on — 74% of
     * Anti-Tank, 63% of Flak, 49% of Destroyer, 46% of Fortification, 41% of Cruiser. 11,791 records
     * end up with the role against PM's 5,254.
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
     */
    fun hasAirDefenceFire(data: EquipmentData): Boolean = data.uclass in AIR_DEFENCE_FIRE_CLASSES && data.airatk > 0

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
     * The name rule was badly wrong, measured over 46,978 `eqp-united` records: 1,368 carry bit 16,
     * the name test matched 227 records, of which 211 agree — so it **missed 1,157 (85%)** and
     * invented 16. The misses are not edge cases: `04 General Staff`, `21 Alpini`, `24 KOP`,
     * `30 Bruckenpioniere`, commissars, squadron leaders.
     *
     * [isHeadquarters] is kept for the equipment card's descriptive "headquarters" note, which is a
     * label and not a capability.
     */
    fun hasCombatSupport(unit: GameUnit): Boolean =
        unit.unitData(true).attr and ATTR_MASK_COMBAT_SUPPORT != 0 ||
            Leaders.unitHasLeader(unit, LeaderType.COMBAT_SUPPORT)

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
}
