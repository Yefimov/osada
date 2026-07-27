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

    /**
     * Whether [eqid] may be given a ground transport.
     *
     * PM tests `groundweight != 0` and nothing else (`openpanzer.js:2707`), and that is why
     * **Landmines were offered a truck**. `groundweight` doubles as a transport-compatibility
     * bitmask, and 96 FORTIFICATION records in `eqp-atomic` — Landmines, bunkers, emplacements —
     * carry bit `0x8000`. Seven `eqp-lxf`/`eqp-united` ground transports carry the same bit
     * (`Civilians`, `Refugees`, `A4 Transport`, `SdKfz 3`, `Opel Blitz`), so the mask test passed
     * and the equipment window listed them.
     *
     * FORTIFICATION is excluded and no other class is. In particular **do not exclude on
     * `movpoints == 0`**, which looks like the more general rule and is wrong: towed artillery is
     * exactly a zero-movement unit that must be transportable (`26cm MinenWrf M17`, gw 32, mov 0).
     * Emplacements are the case that has no defensible reading — an entrenchment is built, not
     * loaded onto a lorry.
     */
    fun isTransportable(eqid: Int): Boolean {
        val data = if (eqid < 1) null else Equipment.equipment[eqid]
        return data != null &&
            data.uclass != UnitClass.FORTIFICATION.value &&
            data.groundweight > 0
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
