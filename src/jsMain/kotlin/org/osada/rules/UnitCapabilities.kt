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
            "Multiple Combat Support units stack."

    const val RECON_MOVEMENT_DESCRIPTION =
        "Phased Movement: reconnaissance units may move again while movement points remain. " +
            "Each movement segment spends points normally."

    const val TANK_OVERRUN_DESCRIPTION =
        "Overrun: an adjacent attack that destroys the defender, costs at most 1 strength and does not catch this " +
            "tank surprised lets it continue moving and restores 1 movement point."

    fun isHeadquarters(data: EquipmentData): Boolean {
        val words = data.name.split(' ', '-', '/', '(', ')')
        return data.name.contains("headquarters", ignoreCase = true) || words.any { it.equals("HQ", ignoreCase = true) }
    }

    fun hasPhasedMovement(data: EquipmentData): Boolean = data.uclass == UnitClass.RECON.value

    fun canOverrun(data: EquipmentData): Boolean = data.uclass == UnitClass.TANK.value

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
