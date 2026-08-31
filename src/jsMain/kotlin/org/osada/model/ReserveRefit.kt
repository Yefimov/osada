package org.osada.model

import org.osada.rules.CostCalculator
import org.osada.rules.GreenReplacements

/**
 * Refitting units in the reserve tray between battles — the paid replacement for the free
 * end-of-scenario refit that `Player.setPlayerToHQ` used to hand out (2026-08-01).
 *
 * ## Why the free refit went away
 *
 * `setPlayerToHQ` restored every surviving unit to full strength, ammo and fuel, unconditionally,
 * for nothing. That made losses almost costless — a formation ground down to 2 strength arrived at
 * the next battle indistinguishable from one that had not been touched — and it silently voided
 * every authored `resupply` campaign effect: seven of the twenty-eight dialogue choices in the two
 * shipping campaigns promise "full resupply" as a reward, and each was applying
 * `strength.coerceAtLeast(10)` to units the HQ pass had already set to 10. Those choices now mean
 * something again.
 *
 * ## The rates
 *
 * A unit in the tray is at headquarters, so it refits at the **city rate** — the same full rate
 * [org.osada.rules.SupplyRules] gives a unit standing in a friendly city with no enemy adjacent,
 * and the rate OG's own tips describe ("if a unit is not in a friendly city, port, or airfield, the
 * unit gets less supply"). Units already on the map are untouched by this file and keep using the
 * existing per-hex resupply, penalties and all.
 *
 * Strength costs prestige at [CostCalculator.reinforceCostPerStrength], exactly as reinforcing does
 * in battle -- OG's `elite_cost` included, because "one economy, two places" has to survive an
 * efile that prices replacements at anything but 100%. Ammo and fuel are free, exactly as
 * resupplying does in battle.
 *
 * ## `green_autorefit`, and why this file is where it lands
 *
 * > *"green_autorefit -- If automatic refit should use greens, thus reducing experience (0..1).
 * >  default 0, autorefit use elite."* -- `OPENTXT_SAMPLE/equip.cfg`
 *
 * This tray pass IS OG's automatic refit, and its default has always been the elite one: full
 * price, no experience lost. An efile that sets `green_autorefit` asks for the other kind, so the
 * pass switches to [GreenReplacements]' price and its experience arithmetic together -- never one
 * without the other, or the player would be charged the discount and keep the veterancy. `eqp-gce`
 * is the only shipped efile that sets it.
 *
 * ## Why not `GameUnit.reinforce`
 *
 * That marks the unit `hasMoved`/`hasFired`, which is right for an action taken during a battle and
 * wrong here: those flags are what `GameMap.isInitialDeploymentWindow` reads, so a unit refitted at
 * HQ and then deployed would count as having already acted and would slam the commander-transfer
 * window shut before the player had done anything.
 */
internal object ReserveRefit {
    private const val FULL_STRENGTH = 10

    /** What a refit of one unit would restore and what it would cost. */
    data class Quote(
        val strengthPoints: Int,
        val strengthCost: Int,
        val needsSupply: Boolean,
    ) {
        /** True when there is anything at all to do — some strength to buy, or free supply to top up. */
        val isNeeded: Boolean get() = strengthPoints > 0 || needsSupply
    }

    /** Outcome of a bulk refit, for the confirmation line. */
    data class Summary(
        val unitsRefitted: Int,
        val strengthRestored: Int,
        val prestigeSpent: Int,
        val unitsUnaffordable: Int,
    )

    /** The units this file acts on: the player's own, still in the tray. */
    fun refittable(player: Player): List<GameUnit> = player.getCoreUnitList().filter { !it.isDeployed }

    /**
     * Prestige per restored point for this pass: green when the efile asks for a green autorefit,
     * elite otherwise. One function, so the quote the player is shown and the charge they take can
     * never disagree.
     */
    private fun costPerStrength(unit: GameUnit): Int =
        if (GreenReplacements.autorefitUsesGreens()) {
            GreenReplacements.costPerStrength(unit)
        } else {
            CostCalculator.reinforceCostPerStrength(unit, false)
        }

    /** Full-rate quote for [unit]; [Quote.isNeeded] is false for a unit already at full readiness. */
    fun quote(unit: GameUnit): Quote {
        val missing = (FULL_STRENGTH - unit.strength).coerceAtLeast(0)
        return Quote(
            strengthPoints = missing,
            strengthCost = missing * costPerStrength(unit),
            needsSupply = needsSupply(unit),
        )
    }

    /**
     * Refits [unit] as far as [player] can afford, and reports what was actually applied.
     *
     * Partial by design, mirroring `Player.reinforceUnit`: a player who cannot afford to bring a
     * formation all the way back should still be able to buy it part of the way, rather than being
     * refused and left with prestige they had no use for. Ammo and fuel are restored either way —
     * they are free, so affordability never gates them.
     */
    fun refit(
        player: Player,
        unit: GameUnit,
    ): Quote {
        val wanted = quote(unit)
        if (!wanted.isNeeded) return Quote(0, 0, false)
        val perPoint = costPerStrength(unit)
        val affordablePoints =
            if (perPoint <= 0) wanted.strengthPoints else minOf(wanted.strengthPoints, player.prestige / perPoint)
        val spent = affordablePoints * perPoint
        if (affordablePoints > 0) {
            // Experience BEFORE the strength changes: the dilution is a weighted average of the
            // veterans present now and the intake joining them.
            if (GreenReplacements.autorefitUsesGreens()) {
                unit.experience = GreenReplacements.experienceAfter(unit, affordablePoints)
            }
            unit.strength += affordablePoints
            player.prestige -= spent
        }
        if (wanted.needsSupply) unit.refillAmmoFuel()
        return Quote(affordablePoints, spent, wanted.needsSupply)
    }

    /**
     * Refits every unit in the tray, weakest first.
     *
     * The order is the point: with prestige too short to restore everything, spending it on the
     * formations closest to being destroyed does more than spreading it across units that are
     * nearly whole. It also makes the result reproducible instead of depending on tray order.
     */
    fun refitAll(player: Player): Summary {
        var units = 0
        var strength = 0
        var spent = 0
        var unaffordable = 0
        refittable(player)
            .filter { quote(it).isNeeded }
            .sortedBy { it.strength }
            .forEach { unit ->
                val wanted = quote(unit)
                val applied = refit(player, unit)
                if (applied.strengthPoints > 0 || applied.needsSupply) units++
                strength += applied.strengthPoints
                spent += applied.strengthCost
                if (applied.strengthPoints < wanted.strengthPoints) unaffordable++
            }
        return Summary(units, strength, spent, unaffordable)
    }

    /** Whether [unit] (or its transport) is carrying less than a full load of ammo or fuel. */
    private fun needsSupply(unit: GameUnit): Boolean {
        val data = unit.unitData(useReal = true)
        val transportShort =
            unit.transport?.let { tr ->
                val trData = tr.unitData()
                tr.ammo < trData.ammo || tr.fuel < trData.fuel
            } ?: false
        return unit.ammo < data.ammo || unit.fuel < data.fuel || transportShort
    }
}
