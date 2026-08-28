package org.osada.model

import org.osada.UnitClass
import org.osada.rules.GameRules
import org.osada.rules.UnitCapabilities
import org.osada.rules.getDisembarkPositions
import org.osada.rules.getEmbarkType
import org.osada.rules.setSpotRange

/**
 * Mount/unmount and embark/disembark unit operations. Split from the former unit-operations
 * component (SRP / function-count limits).
 */
internal class UnitMountOperations(
    private val gameMap: GameMap,
) {
    fun mountUnit(unit: GameUnit) {
        mountUnitHandler(unit)
        gameMap.delMoveSel()
        gameMap.delAttackSel()
        gameMap.selectUnit(unit)
    }

    fun mountUnitHandler(unit: GameUnit) {
        GameRules.setSpotRange(gameMap, unit, false)
        unit.mount()
        GameRules.setSpotRange(gameMap, unit, true)
    }

    fun unmountUnit(unit: GameUnit) {
        gameMap.delMoveSel()
        gameMap.delAttackSel()
        unmountUnitHandler(unit)
        gameMap.selectUnit(unit)
    }

    fun unmountUnitHandler(unit: GameUnit) {
        GameRules.setSpotRange(gameMap, unit, false)
        unit.unmount()
        GameRules.setSpotRange(gameMap, unit, true)
    }

    fun embarkUnit(unit: GameUnit): Boolean {
        val result =
            if (unit.carrier < 0) {
                unit.carrier = -unit.carrier
                true
            } else {
                embarkIntoTransport(unit)
            }
        if (result) {
            gameMap.delMoveSel()
            gameMap.delAttackSel()
            gameMap.selectUnit(unit)
        }
        return result
    }

    private fun embarkIntoTransport(unit: GameUnit): Boolean {
        val type = GameRules.getEmbarkType(gameMap, unit)
        val transportClass = if (type > 0) UnitClass.entries.find { it.value == type } else null
        if (transportClass == null || !unit.embark(transportClass)) return false
        when (type) {
            UnitClass.AIR_TRANSPORT.value -> {
                unit.player?.airTransports =
                    unit.player?.airTransports?.minus(1) ?: 0
                leaveGroundedTransportBehind(unit)
            }

            UnitClass.NAVAL_TRANSPORT.value ->
                unit.player?.navalTransports =
                    unit.player?.navalTransports?.minus(1) ?: 0
        }
        return true
    }

    /**
     * OG's organic-transport rule for an AIRLIFT: *"To be able to carry its organic transport into
     * the air transport, the unit's organic transport must be also Airmobile/Airborne."*
     *
     * A prime mover that cannot fly stays on the airfield — the formation arrives on its own legs.
     * `UnitCapabilities.transportSurvivesAirlift` decides which can, from OG's `Air Transportable`
     * attribute and the transport's own `embark` field together.
     *
     * **Naval embarkation is untouched**, and deliberately: OG states this condition for air
     * transport alone, and a ship that carries a battalion carries its lorries.
     *
     * Wired 2026-08-27. Until then a truck flew wherever its infantry did, which is the one
     * unenforced constraint `docs/og-fidelity-plan.md` §M's approximations table named for
     * `Air Transportable` — *"treated as redundant with `embark`"*.
     */
    private fun leaveGroundedTransportBehind(unit: GameUnit) {
        val transport = unit.transport ?: return
        val data = Equipment.getEquipment(transport.eqid) ?: return
        if (!UnitCapabilities.transportSurvivesAirlift(data)) unit.transport = null
    }

    fun disembarkUnit(unit: GameUnit): Boolean {
        val positions = GameRules.getDisembarkPositions(gameMap, unit)
        if (positions.isEmpty()) return false
        gameMap.delMoveSel()
        gameMap.delAttackSel()
        positions.forEach { cell ->
            gameMap.currentMoveRange.add(cell)
            gameMap.map
                ?.getOrNull(cell.row)
                ?.getOrNull(cell.col)
                ?.isMoveSel = true
        }
        unit.toggleEmbark()
        return true
    }
}
