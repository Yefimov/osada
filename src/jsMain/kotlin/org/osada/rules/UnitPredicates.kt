package org.osada.rules

import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.Equipment
import org.osada.model.GameUnit
import org.osada.unitEntrenchRate

/**
 * Stateless predicates classifying a unit by movement domain (air/sea/ground/train),
 * resource usage (fuel/ammo) and simple per-unit capabilities (mount/unmount/capture/
 * entrench). Extracted from the former `GameRules` god-object: these answer "what kind
 * of unit is this?" without touching the map. Faithful port of the equivalent
 * `osada.js` helpers.
 */
object UnitPredicates {
    private val HEAVY_WEAPON_CLASSES =
        setOf(
            UnitClass.ANTI_TANK.value,
            UnitClass.FLAK.value,
            UnitClass.ARTILLERY.value,
            UnitClass.AIR_DEFENCE.value,
        )

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

    fun isCloseCombatTerrain(terrain: Int): Boolean =
        terrain == TerrainType.CITY.value ||
            terrain == TerrainType.FOREST.value ||
            terrain == TerrainType.MOUNTAIN.value ||
            terrain == TerrainType.FORTIFICATION.value

    fun isEnemy(
        a: GameUnit?,
        b: GameUnit?,
    ): Boolean {
        if (a == null || b == null) return false
        return a.player?.side != b.player?.side
    }

    fun isTransportable(eqid: Int): Boolean {
        if (eqid < 1) return false
        return (Equipment.equipment[eqid]?.groundweight ?: 0) > 0
    }

    fun canCapture(unit: GameUnit): Boolean {
        if (isAir(unit)) return false
        val unitClass = unit.unitData().uclass
        val isHeavyWeaponClass = unitClass in HEAVY_WEAPON_CLASSES
        return !(unit.isMounted && isHeavyWeaponClass)
    }

    fun canEntrench(unit: GameUnit): Boolean {
        if (unit.carrier != 0) return false
        return unitEntrenchRate[unit.unitData().uclass] > 0
    }

    fun canMount(unit: GameUnit): Boolean = !unit.hasMoved && isGround(unit) && unit.transport != null

    fun canUnmount(unit: GameUnit): Boolean = !unit.hasMoved && unit.isMounted
}
