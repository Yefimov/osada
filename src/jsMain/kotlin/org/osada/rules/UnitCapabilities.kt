package org.osada.rules

import org.osada.LeaderType
import org.osada.UnitClass
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Leaders

/** Intrinsic, equipment-defined capabilities that are neither leaders nor purchased attachments. */
object UnitCapabilities {
    const val EXPERIENCE_PER_BAR = 100

    const val HEADQUARTERS_SUPPORT_DESCRIPTION =
        "Combat Support: lends this unit's experience bars to adjacent friendly units on the same air/ground layer. " +
            "Multiple Combat Support units stack. " +
            "(Detected from the unit's name — a genuine Combat Support special or leader may be missed, " +
            "or a unit merely named \"HQ\" may be flagged in error.)"

    const val RECON_MOVEMENT_DESCRIPTION =
        "Phased Movement: reconnaissance units may move again while movement points remain. " +
            "Each movement segment spends points normally."

    const val TANK_OVERRUN_DESCRIPTION =
        "Overrun: an adjacent attack that destroys the defender, costs at most 1 strength and does not catch this " +
            "tank surprised lets it continue moving and restores 1 movement point."

    const val SUPPORT_FIRE_DESCRIPTION =
        "Support Fire: when a friendly unit is attacked in close combat by a ground unit, this artillery fires on " +
            "the attacker from up to its gun range away. It fires in addition to the defender, and does not have to " +
            "be adjacent to either side."

    const val AIR_DEFENCE_FIRE_DESCRIPTION =
        "Anti-Air: when a friendly unit is attacked by aircraft, this unit fires on the attacking aircraft from up " +
            "to its gun range away. It does NOT yet intercept aircraft that merely fly through or land inside its " +
            "range — only attacks on a friendly unit trigger it."

    /**
     * Unit classes that flip a hex's owner and flag by occupying it.
     *
     * Open General restricts capture to ground combat units. Panzer Marshal has no class check at
     * all (`openpanzer.js:3926` `captureHex`), which is how a destroyer came to "capture" the port
     * at N_Kiel without ownership ever transferring — see `DEFERRED.md` §5.4, where keeping PM's
     * behaviour was settled on 2026-07-20 and then reversed on 2026-07-26 in favour of OG.
     *
     * Artillery, air defence, aircraft, ships, transports and fortifications may still occupy and
     * hold a hex, and still deny it to the enemy; they simply never take it.
     */
    private val CAPTURING_CLASSES =
        setOf(
            UnitClass.INFANTRY.value,
            UnitClass.TANK.value,
            UnitClass.RECON.value,
            UnitClass.ANTI_TANK.value,
        )

    /**
     * Classes that fire back at a GROUND attacker on behalf of an adjacent friendly defender.
     *
     * Sole source of truth, shared by `CombatResolver.isSupportFireEligible` (the rule) and
     * `EquipmentMarkings` (the badge) so the two cannot drift — the mistake §4.6 records.
     */
    private val SUPPORT_FIRE_CLASSES = setOf(UnitClass.ARTILLERY.value)

    /** Classes that fire back at an AIR attacker on behalf of an adjacent friendly defender. */
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

    /** See [CAPTURING_CLASSES]. Takes [EquipmentData] rather than a unit so the equipment card can
     *  state it too; the caller decides whether to pass the real unit or its transport. */
    fun canCaptureHex(data: EquipmentData): Boolean = data.uclass in CAPTURING_CLASSES

    fun hasSupportFire(data: EquipmentData): Boolean = data.uclass in SUPPORT_FIRE_CLASSES

    fun hasAirDefenceFire(data: EquipmentData): Boolean = data.uclass in AIR_DEFENCE_FIRE_CLASSES

    fun hasCombatSupport(unit: GameUnit): Boolean =
        isHeadquarters(unit.unitData(true)) || Leaders.unitHasLeader(unit, LeaderType.COMBAT_SUPPORT)

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
