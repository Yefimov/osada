package org.osada.rules

import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.rules.ruleset.ActiveRuleset
import org.osada.rules.ruleset.RuleKey

/**
 * Open General's operational fuel model for aircraft (OG manual 6.23,
 * `docs/og-fidelity-plan.md` B.3), behind the `air_fuel` ruleset key.
 *
 * Two rules, one key, because in OG they are one model:
 *
 *  1. **A sortie has a floor.** Taking off at all spends at least a third of the aircraft's full
 *     movement in fuel, however short the hop. Without it the second rule is trivially avoided by
 *     flying one hex a turn, and an air force never runs out of anything.
 *  2. **An aircraft that runs dry away from base is lost.** *"If it is a plane and isn't in an
 *     airfield or adjacent hex, it crashes and it is destroyed."* OSADA alone merely refuses to
 *     move it, which leaves a stranded aircraft sitting on the map indefinitely.
 *
 * **The trigger for rule 2 is being OUT OF FUEL, not being away from base.** That distinction is
 * the whole reason this is safe to ship: `docs/og-fidelity-plan.md` B.3 names OSADA's behaviour as
 * *"OSADA only stops the aircraft moving"*, so the rule OG adds is what happens to an aircraft the
 * engine has already immobilised. Destroying every unbased aircraft regardless of fuel would delete
 * authored deployments in scenarios that were never written for it.
 *
 * **Off by default, by an owner decision** rather than by caution: the crash rule is genuinely
 * punitive and OSADA Default is deliberately the gentler game. It is on in Open General Fidelity.
 *
 * Every loss is reported. A formation that disappears between turns with no line in the Turn Report
 * is the same failure `tools/og-import/DEFERRED.md` §1.1 forbids for AA interception, and an
 * end-of-turn sweep is even less visible than movement damage -- see [strandedAircraft]'s callers in
 * `model/GameMap.endTurn`.
 */
internal object AirOperations {
    /** OG's stated fraction: a sortie spends *"a minimum of one third of full movement"* in fuel. */
    private const val SORTIE_FUEL_DIVISOR = 3

    /** Stable Turn Report token for the one loss this model can cause. Never display text: the
     *  report is re-rendered on a live language change; see CombatLogQueries.addAttritionLoss. */
    const val LOSS_OUT_OF_FUEL = "out_of_fuel"

    /** Whether the model runs at all. Every function here returns the "OSADA today" answer when
     *  this is false, so no call site needs a second guard. */
    fun enabled(): Boolean = ActiveRuleset.flag(RuleKey.AIR_FUEL, false)

    /** True when [unit] is an aircraft this model has anything to say about. A plane that burns no
     *  fuel at all (a few authored fortification-like air records do) is left alone: a floor on a
     *  zero cost is still zero, and a unit that can never run dry can never crash. */
    private fun governed(unit: GameUnit): Boolean =
        enabled() && UnitPredicates.isAir(unit) && UnitPredicates.unitUsesFuel(unit)

    /**
     * The minimum a sortie costs, in MOVEMENT POINTS, for [unit].
     *
     * Rounded UP, on the same principle [UnitConditionPenalties] rounds its halvings down: this is a
     * cost the player deliberately switched on, and rounding it in the aircraft's favour would blunt
     * the rule at exactly the short movement ranges it exists to price. A six-point bomber pays two;
     * an eight-point fighter pays three.
     */
    private fun sortieMinimum(unit: GameUnit): Int {
        val full = unit.unitData(useReal = true).movpoints
        return (full + SORTIE_FUEL_DIVISOR - 1) / SORTIE_FUEL_DIVISOR
    }

    /**
     * Movement points [unit] has already spent this turn.
     *
     * Derived from the counter `unitEndTurn` resets rather than from a second field: a new per-unit
     * statistic would have to be serialized, restored and kept in step with every other reset, for a
     * number `moveLeft` already carries.
     *
     * Reads `moveLeft` DIRECTLY and not `getMovesLeft()`, which is not the same question:
     * `getMovesLeft` reports 0 for a formation that has spent its action and reports the transport's
     * allowance for a mounted one, so it conflates "has none left" with "has used them all". Only the
     * raw counter says how much of the sortie has been flown.
     */
    private fun pointsSpentThisTurn(unit: GameUnit): Int =
        (unit.unitData(useReal = true).movpoints - unit.moveLeft).coerceAtLeast(0)

    /** Fuel-bearing movement points charged for a turn in which [spent] points were used: the floor
     *  applies to the SORTIE, so a turn in which the aircraft never moved is free. */
    private fun chargedForTurn(
        unit: GameUnit,
        spent: Int,
    ): Int = if (spent <= 0) 0 else maxOf(spent, sortieMinimum(unit))

    /**
     * Movement points to charge fuel for when [unit] moves [cost] more points this turn.
     *
     * Expressed as the DIFFERENCE between the turn's total charge before and after the step, which
     * is what makes the floor a per-sortie cost rather than a per-move one: a Recon-style aircraft
     * that moves twice in a turn under phased movement pays the floor once, not twice.
     *
     * Returns [cost] unchanged whenever the key is off or [unit] is not an aircraft, so
     * `model/GameUnitActions.move` reads exactly one number either way.
     */
    fun chargedMovePoints(
        unit: GameUnit,
        cost: Int,
    ): Int {
        if (!governed(unit)) return cost
        val before = pointsSpentThisTurn(unit)
        return chargedForTurn(unit, before + cost) - chargedForTurn(unit, before)
    }

    /**
     * Movement points [unit]'s remaining fuel can actually pay for, for the move-range preview.
     *
     * The inverse of [chargedMovePoints], and it has to exist separately: an aircraft that cannot
     * afford the whole sortie floor cannot take off at all, so the honest preview is zero rather
     * than "as far as the raw fuel divides". Promising a route the aircraft cannot finish is the
     * failure `docs/og-fidelity-plan.md` B.2 already called the real work in the snow-fuel rule.
     *
     * [fuelPerPoint] is `WeatherCombatRules.fuelPerMovePoint()`, passed in so the two fuel rules
     * compound in one place rather than each halving the other's arithmetic.
     */
    fun affordableMovePoints(
        unit: GameUnit,
        fuelPerPoint: Int,
    ): Int {
        val budget = unit.getFuel() / fuelPerPoint
        if (!governed(unit)) return budget
        val spent = pointsSpentThisTurn(unit)
        val minimum = sortieMinimum(unit)
        return when {
            // Still on the ground: the first point of movement costs the whole floor.
            spent <= 0 -> if (budget < minimum) 0 else budget
            // Airborne and already past the floor: every further point costs its own fuel.
            spent >= minimum -> budget
            // Airborne but still inside the floor it has already paid: those points are free.
            else -> budget + minimum - spent
        }
    }

    /**
     * Aircraft belonging to [side] that OG destroys at the end of that side's turn: out of fuel, and
     * with no airfield or friendly carrier on or beside their hex.
     *
     * [MovementRules.hasAirfield] is the same "properly based" predicate automatic air resupply
     * already uses, so an aircraft is never destroyed on a hex that would have refuelled it -- the
     * two rules cannot disagree.
     *
     * Returns the units rather than destroying them so the caller can report each loss before
     * sweeping it; see `model/GameMap.endTurn`.
     */
    fun strandedAircraft(
        map: GameMap,
        side: Int,
    ): List<GameUnit> {
        if (!enabled()) return emptyList()
        return map.units.filter { unit ->
            unit.player?.side == side &&
                !unit.destroyed &&
                governed(unit) &&
                unit.getFuel() <= 0 &&
                !MovementRules.hasAirfield(map, unit)
        }
    }

    /** Where [unit] was lost, for the Turn Report row. Null only for a unit with no position at
     *  all, which the sweep cannot produce but the type allows. */
    fun lossPosition(unit: GameUnit): Cell? = unit.getPos()
}
