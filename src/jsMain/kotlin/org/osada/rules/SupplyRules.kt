package org.osada.rules

import org.osada.TerrainType
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Supply
import kotlin.math.roundToInt

/**
 * Resupply (ammo/fuel) and reinforcement (strength) rules. The terrain and adjacent-enemy penalties
 * that reduce how much can be restored live in [SupplyContextRules], which also explains them to
 * the UI. Extracted from the former `GameRules` god-object. Faithful port of the `osada.js` supply
 * helpers.
 */
object SupplyRules {
    private const val FULL_STRENGTH = 10
    private const val EXPERIENCE_STRENGTH_DIVISOR = 100.0
    private const val OVERSTRENGTH_MIN_EXPERIENCE = 100

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
        val ammoNeeded = maxAmmo(unit) - unit.ammo
        val fuelNeeded = maxFuel(unit) - unit.fuel
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
            val adjacentEnemies = SupplyContextRules.countAdjacentEnemies(map, unit, pos)
            if (full && (adjacentEnemies > 0 || hex?.terrain != TerrainType.CITY.value)) {
                Supply(0, 0, 0, 0)
            } else {
                val terrainMod = SupplyContextRules.supplyPenaltyModifier(hex, adjacentEnemies)
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
                overstrengthCap(unit) - unit.strength
            } else {
                FULL_STRENGTH - unit.strength
            }
        val pos = unit.getPos()
        return if (UnitPredicates.isAir(unit)) {
            strengthNeeded
        } else if (pos == null) {
            0
        } else {
            val adjacentEnemies = SupplyContextRules.countAdjacentEnemies(map, unit, pos)
            val modifier = SupplyContextRules.supplyPenaltyModifier(unit.getHex(), adjacentEnemies)
            // JS returns Math.round(d) with no minimum clamp.
            (strengthNeeded * modifier).roundToInt()
        }
    }

    /** Maximum ammunition [unit] can carry, including the Support/Ammunition attachment's bonus
     *  and any attachment's ammo-malus penalty (`docs/design/attachments.md` Tier 1). */
    fun maxAmmo(unit: GameUnit): Int =
        unit.unitData(true).ammo + Attachments.bonus(unit, Attachments.SLOT_SUPPORT_AMMO) +
            Attachments.ammoPenalty(unit)

    /** Maximum fuel [unit] can carry, including the Fuel Pods attachment's bonus. That bonus is
     *  flat or a percentage of base fuel depending on the efile's `attach_minfuel` -- resolved
     *  inside [Attachments.bonus]. */
    fun maxFuel(unit: GameUnit): Int =
        unit.unitData(true).fuel + Attachments.bonus(unit, Attachments.SLOT_FUEL_PODS) +
            Attachments.fuelPenalty(unit)

    /** True when [unit] (or its organic transport) is missing ammo or fuel. The "is there anything
     *  to restore?" half of [canResupply], named so the UI can distinguish a fully supplied unit
     *  from one blocked by terrain or a spent action. */
    fun needsSupply(unit: GameUnit): Boolean {
        val needsAmmo = unit.ammo < maxAmmo(unit)
        val needsFuel = unit.fuel < maxFuel(unit)
        var transportNeedsAmmo = false
        var transportNeedsFuel = false
        unit.transport?.let { tr ->
            val trData = tr.unitData()
            transportNeedsAmmo = tr.ammo < trData.ammo
            transportNeedsFuel = tr.fuel < trData.fuel
        }
        return needsAmmo || needsFuel || transportNeedsAmmo || transportNeedsFuel
    }

    /** Strength ceiling [unit]'s experience allows it to be raised to by overstrength. */
    fun overstrengthCap(unit: GameUnit): Int =
        FULL_STRENGTH + (unit.experience / EXPERIENCE_STRENGTH_DIVISOR).roundToInt()

    /** True when [unit] is eligible to resupply (hasn't acted, needs supply, valid terrain). */
    fun canResupply(
        map: GameMap,
        unit: GameUnit,
    ): Boolean {
        if (unit.hasMoved || unit.hasFired || unit.hasResupplied) return false
        return if (!needsSupply(unit)) false else isSupplyEligibleType(map, unit)
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
                    unit.strength >= overstrengthCap(unit)
            } else {
                unit.hasResupplied || unit.strength >= FULL_STRENGTH
            }
        return if (blocked) false else isSupplyEligibleType(map, unit)
    }
}
