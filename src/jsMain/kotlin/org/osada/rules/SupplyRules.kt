package org.osada.rules

import org.osada.TerrainType
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Supply
import org.osada.model.isWorking
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey
import kotlin.math.roundToInt

/**
 * Resupply (ammo/fuel) and reinforcement (strength) rules. The terrain and adjacent-enemy penalties
 * that reduce how much can be restored live in [SupplyContextRules], which also explains them to
 * the UI. Extracted from the former `GameRules` god-object. Faithful port of the `osada.js` supply
 * helpers.
 */
object SupplyRules {
    private const val EXPERIENCE_STRENGTH_DIVISOR = 100
    private const val OVERSTRENGTH_MIN_EXPERIENCE = 100

    /**
     * True when [unit]'s type/terrain make it eligible for a supply action at all.
     *
     * **The naval branch carried an inverted terrain test until 2026-08-18**
     * (`docs/og-fidelity-plan.md` A.2): it refused a warship standing in a PORT and allowed one in
     * open water. OG manual 6.23 names the port as the place a warship is resupplied
     * (*"naval units in a port that end the turn there"*), so the one hex the rule certainly permits
     * was the one hex OSADA forbade. The exclusion is deleted rather than reversed -- forbidding
     * resupply at sea is not an alternative design either, and reversing it would strand every fleet
     * the 502 shipped scenarios operate away from a port.
     */
    private fun isSupplyEligibleType(
        map: GameMap,
        unit: GameUnit,
    ): Boolean {
        // OG's `Kamikaze` under the efile's "extended missile rules" (`kamikaze=1`): the unit
        // "is not able to resupply" at all. Under the default model it resupplies normally right up
        // to the attack that consumes it -- see `rules/Kamikaze`.
        // Two abilities refuse supply outright before terrain is even asked:
        //  * OG's `Kamikaze` under the efile's "extended missile rules" (`kamikaze=1`) -- "not able
        //    to resupply" at all; under the default model it resupplies normally right up to the
        //    attack that consumes it (`rules/Kamikaze`);
        //  * OG's `Saboteur` -- a sabotaged formation "cannot reinforce/resupply" until it recovers.
        if (!Kamikaze.canResupply(unit) || unit.sabotaged) return false
        val groundEligible = UnitPredicates.isGround(unit)
        val airEligible = UnitPredicates.isAir(unit) && MovementRules.hasAirfield(map, unit)
        val seaEligible = UnitPredicates.isSea(unit)
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
            // OG 6.23 resupplies "ground units that do nothing in the turn" and names no terrain;
            // OSADA additionally requires a CITY hex. `docs/og-fidelity-plan.md` A.3 item 2 records
            // that as a BALANCE decision rather than a defect -- relaxing it hands every idle
            // formation in the field a free full refit -- and required that it move behind a key if
            // it moved at all. `ground_auto_supply`, `city_only` by default.
            //
            // The adjacent-enemy condition is NOT part of the key: a formation being shot at is not
            // idle in either game, and OG's own wording ("do nothing in the turn") agrees.
            val offCityBlocks = !ActiveRuleset.flag(RuleKey.GROUND_AUTO_SUPPLY, false)
            // A city shelled into rubble stops resupplying anybody until it is repaired -- OG's
            // "unusable until Repaired", read through `Hex.isWorking`.
            val outOfSupplyTerrain = offCityBlocks && hex?.isWorking(TerrainType.CITY.value) != true
            // Looked up ONCE: `supplierFor` walks the six neighbours, and this arithmetic asks the
            // same question twice.
            val fromDepot = DepotSupply.supplierFor(map, unit) != null
            val fieldRefused = (adjacentEnemies > 0 || outOfSupplyTerrain) && !fromDepot
            if (full && fieldRefused) {
                Supply(0, 0, 0, 0)
            } else {
                // Depot supply ignores the terrain supply factor AND enemy ZOC pressure: NOKORP
                // names both as restrictions that apply to `supply_ex` mode 2, not to a Depot.
                val terrainMod =
                    if (fromDepot) 1.0 else SupplyContextRules.supplyPenaltyModifier(hex, adjacentEnemies)
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
                unit.basicStrength - unit.strength
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

    /**
     * Strength ceiling [unit]'s experience allows it to be raised to by overstrength: one point per
     * **completed** experience bar.
     *
     * **Integer division, not [roundToInt] (2026-08-18, `docs/og-fidelity-plan.md` A.1).** Rounding
     * gave a half-finished bar a whole strength point -- 150 XP bought two extra points where OG
     * manual 6.7 grants one (*"Each experience bar allows for one strength point over the normal
     * maximum"*), and the Overstrength button's own description repeats the same rule. A partial bar
     * now buys nothing, which is what both surfaces already promised the player.
     *
     * **The base is the formation's own, not a hardcoded 10 (2026-08-30).** OG's rule is
     * *"overstrength equals BASE strength plus experience bars"*, and base strength is
     * [GameUnit.basicStrength] — scenario unit `@23`, which nothing read until that day. A
     * formation with no authored value still gets 10, so nothing outside the 361 patched scenarios
     * moves.
     */
    fun overstrengthCap(unit: GameUnit): Int = unit.basicStrength + unit.experience / EXPERIENCE_STRENGTH_DIVISOR

    /** True when [unit] is eligible to resupply (hasn't acted, needs supply, valid terrain). */
    fun canResupply(
        map: GameMap,
        unit: GameUnit,
    ): Boolean {
        // A Depot lifts the moved/fired bar -- OG's own changelog lists "units failing to resupply
        // from an adjacent Depot after moving or firing" as a BUG it fixed, and NOKORP names those
        // restrictions for `supply_ex` mode 2 alone (`rules/DepotSupply`). The once-per-turn latch
        // still holds either way; nothing supplies the same formation twice.
        val fromDepot = DepotSupply.supplierFor(map, unit) != null
        val spentThisTurn = unit.hasResupplied || (!fromDepot && (unit.hasMoved || unit.hasFired))
        // "Any other value restricts units to resupply only from Depots and/or Cities/Ports."
        val permitted = !spentThisTurn && DepotSupply.permitsSupply(map, unit)
        return permitted && needsSupply(unit) && isSupplyEligibleType(map, unit)
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
                // Overstrength applies only to a unit already at full strength;
                // JS guards `10 > strength` (strength < 10), which was inverted here.
                // "Full" is the formation's own BASE strength, not a hardcoded 10 -- a unit
                // authored 10/5 is already above its base and is not a candidate.
                unit.strength < unit.basicStrength ||
                    unit.experience < OVERSTRENGTH_MIN_EXPERIENCE ||
                    unit.strength >= overstrengthCap(unit)
            } else {
                unit.hasResupplied || unit.strength >= unit.basicStrength
            }
        return if (blocked) false else isSupplyEligibleType(map, unit)
    }
}
