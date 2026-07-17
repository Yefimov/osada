package org.osada.rules

import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.GameUnit
import org.osada.model.Transport
import org.osada.unitEntrenchRate

/**
 * Stateless predicates classifying a unit by movement domain (air/sea/ground/train),
 * resource usage (fuel/ammo) and simple per-unit capabilities (mount/unmount/capture/
 * entrench). Extracted from the former `GameRules` god-object: these answer "what kind
 * of unit is this?" without touching the map. Faithful port of the equivalent
 * `osada.js` helpers.
 */
object UnitPredicates {

    fun isAir(unit: GameUnit?): Boolean {
        if (unit == null) return false
        return unit.unitData().movmethod == MovMethod.AIR.value
    }

    fun isSea(unit: GameUnit?): Boolean {
        if (unit == null) return false
        return unit.unitData().uclass in UnitClass.SUBMARINE.value..UnitClass.LIGHT_CRUISER.value
    }

    fun isGround(unit: GameUnit?): Boolean {
        if (unit == null) return false
        return unit.unitData().uclass <= UnitClass.AIR_DEFENCE.value
    }

    fun isTrain(unit: GameUnit?): Boolean {
        if (unit == null) return false
        val movmethod = unit.unitData().movmethod
        // RAIL is the real movement method (Constants.kt); DEEP_NAVAL is kept for compatibility
        // with efiles authored against the legacy "repurpose the unused deep-naval slot for
        // trains" convention (e.g. eqp-adlerkorps's Armoured Train) — trains and deep-naval ships
        // never coexist on the same map, so the slot was free.
        return isGround(unit) && (movmethod == MovMethod.RAIL.value || movmethod == MovMethod.DEEP_NAVAL.value)
    }

    fun isCloseCombatTerrain(terrain: Int): Boolean = terrain == TerrainType.CITY.value ||
        terrain == TerrainType.FOREST.value ||
        terrain == TerrainType.MOUNTAIN.value ||
        terrain == TerrainType.FORTIFICATION.value

    fun isEnemy(a: GameUnit?, b: GameUnit?): Boolean {
        if (a == null || b == null) return false
        return a.player?.side != b.player?.side
    }

    fun isTransportable(eqid: Int): Boolean {
        if (eqid < 1) return false
        return (Equipment.equipment[eqid]?.groundweight ?: 0) > 0
    }

    fun unitUsesFuel(unit: GameUnit): Boolean = unitUsesFuelData(unit.unitData())

    fun unitUsesFuel(transport: Transport): Boolean = unitUsesFuelData(transport.unitData())

    private fun unitUsesFuelData(data: EquipmentData): Boolean {
        if (data.fuel == 0) return false
        val method = data.movmethod
        return method != MovMethod.LEG.value &&
            method != MovMethod.TOWED.value &&
            method != MovMethod.ALL_TERRAIN_LEG.value
    }

    fun unitLowFuel(unit: GameUnit, threshold: Int): Boolean {
        if (!unitUsesFuel(unit)) return false
        return if (!unit.isMounted) unit.fuel < threshold else unit.transport?.fuel ?: 0 < threshold
    }

    fun unitUsesAmmo(unit: GameUnit): Boolean = unit.unitData().ammo > 0

    fun unitLowAmmo(unit: GameUnit, threshold: Int): Boolean {
        if (!unitUsesAmmo(unit)) return false
        return if (!unit.isMounted) unit.ammo < threshold else unit.transport?.ammo ?: 0 < threshold
    }

    fun canCapture(unit: GameUnit): Boolean {
        if (isAir(unit)) return false
        val unitClass = unit.unitData().uclass
        if (unit.isMounted &&
            (
                unitClass == UnitClass.ANTI_TANK.value ||
                    unitClass == UnitClass.FLAK.value ||
                    unitClass == UnitClass.ARTILLERY.value ||
                    unitClass == UnitClass.AIR_DEFENCE.value
                )
        ) {
            return false
        }
        return true
    }

    fun canEntrench(unit: GameUnit): Boolean {
        if (unit == null || unit.carrier != 0) return false
        return unitEntrenchRate[unit.unitData().uclass] > 0
    }

    fun canMount(unit: GameUnit): Boolean = !unit.hasMoved && isGround(unit) && unit.transport != null

    fun canUnmount(unit: GameUnit): Boolean = !unit.hasMoved && unit.isMounted
}
