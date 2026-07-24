package org.osada.rules

import org.osada.TerrainType
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.Supply
import kotlin.math.roundToInt

/**
 * Resupply (ammo/fuel) and reinforcement (strength) rules, including the terrain and
 * adjacent-enemy penalties that reduce how much can be restored. Extracted from the
 * former `GameRules` god-object. Faithful port of the `osada.js` supply helpers.
 */
object SupplyRules {
    private const val FULL_STRENGTH = 10
    private const val OFF_CITY_SUPPLY_PENALTY = 1.3
    private const val LIGHT_ENEMY_SUPPLY_PENALTY = 1.5
    private const val HEAVY_ENEMY_SUPPLY_PENALTY = 3.0
    private const val EXPERIENCE_STRENGTH_DIVISOR = 100.0
    private const val OVERSTRENGTH_MIN_EXPERIENCE = 100
    private const val PERCENT = 100

    data class SupplyContext(
        val efficiencyPercent: Int,
        val label: String,
    )

    /** Count of adjacent enemy units around [pos], used by both supply-penalty calculations. */
    private fun countAdjacentEnemies(
        map: GameMap,
        unit: GameUnit,
        pos: Cell,
    ): Int =
        HexGeometry.getAdjacent(pos.row, pos.col).count { cell ->
            val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col)
            hex?.unit?.player?.side != unit.player?.side && hex?.unit != null
        }

    /** Terrain/adjacent-enemy penalty multiplier shared by resupply and reinforce math. */
    private fun supplyPenaltyModifier(
        hex: Hex?,
        adjacentEnemies: Int,
    ): Double {
        var modifier = 1.0
        if (hex?.terrain != TerrainType.CITY.value) modifier /= OFF_CITY_SUPPLY_PENALTY
        if (adjacentEnemies in 1..2) modifier /= LIGHT_ENEMY_SUPPLY_PENALTY
        if (adjacentEnemies > 2) modifier /= HEAVY_ENEMY_SUPPLY_PENALTY
        return modifier
    }

    /** Player-facing explanation of the exact local modifier used by manual supply/reinforcement. */
    fun getSupplyContext(
        map: GameMap,
        unit: GameUnit,
    ): SupplyContext =
        when {
            UnitPredicates.isAir(unit) -> SupplyContext(PERCENT, "airfield/carrier supply")
            UnitPredicates.isSea(unit) -> SupplyContext(PERCENT, "naval supply")
            else -> {
                val pos = unit.getPos()
                if (pos == null) {
                    SupplyContext(0, "no supply position")
                } else {
                    val adjacentEnemies = countAdjacentEnemies(map, unit, pos)
                    val inCity = unit.getHex()?.terrain == TerrainType.CITY.value
                    val efficiency =
                        (supplyPenaltyModifier(unit.getHex(), adjacentEnemies) * PERCENT).roundToInt()
                    val location = if (inCity) "city supply" else "field supply"
                    val pressure =
                        when {
                            adjacentEnemies == 0 -> ""
                            adjacentEnemies == 1 -> ", 1 adjacent enemy"
                            else -> ", $adjacentEnemies adjacent enemies"
                        }
                    SupplyContext(efficiency, "$location$pressure")
                }
            }
        }

    /** True when [unit]'s type/terrain make it eligible for a supply action at all. */
    private fun isSupplyEligibleType(
        map: GameMap,
        unit: GameUnit,
    ): Boolean {
        val groundEligible = UnitPredicates.isGround(unit)
        val airEligible = UnitPredicates.isAir(unit) && MovementRules.hasAirfield(map, unit)
        val seaEligible = UnitPredicates.isSea(unit) && unit.getHex()?.terrain != TerrainType.PORT.value
        return groundEligible || airEligible || seaEligible
    }

    /** How much ammo/fuel (and transport ammo/fuel) [unit] would gain by resupplying. */
    fun getResupplyValue(
        map: GameMap,
        unit: GameUnit,
        full: Boolean = false,
    ): Supply {
        if (!canResupply(map, unit)) return Supply(0, 0, 0, 0)
        return computeResupplyValue(map, unit, full)
    }

    private fun computeResupplyValue(
        map: GameMap,
        unit: GameUnit,
        full: Boolean,
    ): Supply {
        val data = unit.unitData(true)
        val ammoNeeded = data.ammo - unit.ammo
        val fuelNeeded = data.fuel - unit.fuel
        if (UnitPredicates.isAir(unit) || UnitPredicates.isSea(unit)) return Supply(ammoNeeded, fuelNeeded, 0, 0)
        var transportAmmoNeeded = 0
        var transportFuelNeeded = 0
        unit.transport?.let { tr ->
            val trData = tr.unitData()
            transportAmmoNeeded = trData.ammo - tr.ammo
            transportFuelNeeded = trData.fuel - tr.fuel
        }
        val pos = unit.getPos()
        val hex = unit.getHex()
        return if (pos == null) {
            Supply(0, 0, 0, 0)
        } else {
            val adjacentEnemies = countAdjacentEnemies(map, unit, pos)
            if (full && (adjacentEnemies > 0 || hex?.terrain != TerrainType.CITY.value)) {
                Supply(0, 0, 0, 0)
            } else {
                val terrainMod = supplyPenaltyModifier(hex, adjacentEnemies)
                // JS rounds (not truncates) and clamps only ammo/fuel to a minimum of 1;
                // the transport values are rounded as-is (0 stays 0 for a transportless unit).
                val ammo = kotlin.math.max(1.0, ammoNeeded * terrainMod)
                val fuel = kotlin.math.max(1.0, fuelNeeded * terrainMod)
                Supply(
                    ammo.roundToInt(),
                    fuel.roundToInt(),
                    (transportAmmoNeeded * terrainMod).roundToInt(),
                    (transportFuelNeeded * terrainMod).roundToInt(),
                )
            }
        }
    }

    /** How much strength [unit] would gain by reinforcing (optionally over full strength). */
    fun getReinforceValue(
        map: GameMap,
        unit: GameUnit,
        overStrength: Boolean = false,
    ): Int {
        if (!canReinforce(map, unit, overStrength)) return 0
        val strengthNeeded =
            if (overStrength) {
                FULL_STRENGTH + (unit.experience / EXPERIENCE_STRENGTH_DIVISOR).roundToInt() - unit.strength
            } else {
                FULL_STRENGTH - unit.strength
            }
        val pos = unit.getPos()
        return if (UnitPredicates.isAir(unit)) {
            strengthNeeded
        } else if (pos == null) {
            0
        } else {
            val adjacentEnemies = countAdjacentEnemies(map, unit, pos)
            val modifier = supplyPenaltyModifier(unit.getHex(), adjacentEnemies)
            // JS returns Math.round(d) with no minimum clamp.
            (strengthNeeded * modifier).roundToInt()
        }
    }

    /** True when [unit] is eligible to resupply (hasn't acted, needs supply, valid terrain). */
    fun canResupply(
        map: GameMap,
        unit: GameUnit,
    ): Boolean {
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
        val needsUnitSupply = needsAmmo || needsFuel
        val needsTransportSupply = transportNeedsAmmo || transportNeedsFuel
        val needsSupply = needsUnitSupply || needsTransportSupply
        return if (!needsSupply) {
            false
        } else {
            isSupplyEligibleType(map, unit)
        }
    }

    /** True when [unit] is eligible to reinforce (optionally over its full strength). */
    fun canReinforce(
        map: GameMap,
        unit: GameUnit,
        overStrength: Boolean = false,
    ): Boolean {
        if (unit.hasOverstrength) return false
        val blocked =
            if (overStrength) {
                // Overstrength applies only to a unit already at full strength (>=10);
                // JS guards `10 > strength` (strength < 10), which was inverted here.
                unit.strength < FULL_STRENGTH ||
                    unit.experience < OVERSTRENGTH_MIN_EXPERIENCE ||
                    unit.strength >= FULL_STRENGTH + (unit.experience / EXPERIENCE_STRENGTH_DIVISOR).roundToInt()
            } else {
                unit.hasResupplied || unit.strength >= FULL_STRENGTH
            }
        return if (blocked) false else isSupplyEligibleType(map, unit)
    }
}
