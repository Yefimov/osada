package org.osada.rules

import org.osada.TerrainType
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Supply
import kotlin.math.roundToInt

/**
 * Resupply (ammo/fuel) and reinforcement (strength) rules, including the terrain and
 * adjacent-enemy penalties that reduce how much can be restored. Extracted from the
 * former `GameRules` god-object. Faithful port of the `osada.js` supply helpers.
 */
object SupplyRules {

    /** How much ammo/fuel (and transport ammo/fuel) [unit] would gain by resupplying. */
    fun getResupplyValue(map: GameMap, unit: GameUnit, full: Boolean = false): Supply {
        if (!canResupply(map, unit)) return Supply(0, 0, 0, 0)
        val data = unit.unitData(true)
        var ammoNeeded = data.ammo - unit.ammo
        var fuelNeeded = data.fuel - unit.fuel
        if (UnitPredicates.isAir(unit) || UnitPredicates.isSea(unit)) return Supply(ammoNeeded, fuelNeeded, 0, 0)
        var transportAmmoNeeded = 0
        var transportFuelNeeded = 0
        unit.transport?.let { tr ->
            val trData = tr.unitData()
            transportAmmoNeeded = trData.ammo - tr.ammo
            transportFuelNeeded = trData.fuel - tr.fuel
        }
        val pos = unit.getPos() ?: return Supply(0, 0, 0, 0)
        val adjacentEnemies = HexGeometry.getAdjacent(pos.row, pos.col).count { cell ->
            val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col)
            hex?.unit?.player?.side != unit.player?.side && hex?.unit != null
        }
        val hex = unit.getHex()
        if (full && (adjacentEnemies > 0 || hex?.terrain != TerrainType.CITY.value)) {
            return Supply(0, 0, 0, 0)
        }
        var terrainMod = 1.0
        if (hex?.terrain != TerrainType.CITY.value) terrainMod /= 1.3
        if (adjacentEnemies in 1..2) terrainMod /= 1.5
        if (adjacentEnemies > 2) terrainMod /= 3.0
        // JS rounds (not truncates) and clamps only ammo/fuel to a minimum of 1;
        // the transport values are rounded as-is (0 stays 0 for a transportless unit).
        val ammo = kotlin.math.max(1.0, ammoNeeded * terrainMod)
        val fuel = kotlin.math.max(1.0, fuelNeeded * terrainMod)
        return Supply(
            ammo.roundToInt(),
            fuel.roundToInt(),
            (transportAmmoNeeded * terrainMod).roundToInt(),
            (transportFuelNeeded * terrainMod).roundToInt(),
        )
    }

    /** How much strength [unit] would gain by reinforcing (optionally over full strength). */
    fun getReinforceValue(map: GameMap, unit: GameUnit, overStrength: Boolean = false): Int {
        if (!canReinforce(map, unit, overStrength)) return 0
        var strengthNeeded = 10 - unit.strength
        if (overStrength) strengthNeeded = 10 + (unit.experience / 100.0).roundToInt() - unit.strength
        if (UnitPredicates.isAir(unit)) return strengthNeeded
        val pos = unit.getPos() ?: return 0
        val adjacentEnemies = HexGeometry.getAdjacent(pos.row, pos.col).count { cell ->
            val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col)
            hex?.unit?.player?.side != unit.player?.side && hex?.unit != null
        }
        val hex = unit.getHex()
        var modifier = 1.0
        if (hex?.terrain != TerrainType.CITY.value) modifier /= 1.3
        if (adjacentEnemies in 1..2) modifier /= 1.5
        if (adjacentEnemies > 2) modifier /= 3.0
        // JS returns Math.round(d) with no minimum clamp.
        return (strengthNeeded * modifier).roundToInt()
    }

    /** True when [unit] is eligible to resupply (hasn't acted, needs supply, valid terrain). */
    fun canResupply(map: GameMap, unit: GameUnit): Boolean {
        if (unit.hasMoved || unit.hasFired || unit.hasResupplied) return false
        val data = unit.unitData(true)
        val needsAmmo = unit.ammo < data.ammo
        val needsFuel = unit.fuel < data.fuel
        var transportNeedsAmmo = false
        var transportNeedsFuel = false
        unit.transport?.let { tr ->
            val trData = tr.unitData()
            transportNeedsAmmo = tr.ammo < trData.ammo
            transportNeedsFuel = tr.fuel < trData.fuel
        }
        if (!needsAmmo && !needsFuel && !transportNeedsAmmo && !transportNeedsFuel) return false
        return UnitPredicates.isGround(unit) ||
            (UnitPredicates.isAir(unit) && MovementRules.hasAirfield(map, unit)) ||
            (UnitPredicates.isSea(unit) && unit.getHex()?.terrain != TerrainType.PORT.value)
    }

    /** True when [unit] is eligible to reinforce (optionally over its full strength). */
    fun canReinforce(map: GameMap, unit: GameUnit, overStrength: Boolean = false): Boolean {
        if (unit.hasOverstrength) return false
        if (overStrength) {
            // Overstrength applies only to a unit already at full strength (>=10);
            // JS guards `10 > strength` (strength < 10), which was inverted here.
            if (unit.strength < 10 ||
                unit.experience < 100 ||
                unit.strength >= 10 + (unit.experience / 100.0).roundToInt()
            ) {
                return false
            }
        } else {
            if (unit.hasResupplied || unit.strength >= 10) return false
        }
        return UnitPredicates.isGround(unit) ||
            (UnitPredicates.isAir(unit) && MovementRules.hasAirfield(map, unit)) ||
            (UnitPredicates.isSea(unit) && unit.getHex()?.terrain != TerrainType.PORT.value)
    }
}
