package org.osada.rules

import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.getPlayers
import org.osada.scenario.Scenario

/**
 * Open General's **extended victory conditions**, manual §3.7 — the ways to win a scenario that are
 * not "take all the victory hexes".
 *
 * > *"Some of these conditions can be asked for simultaneously, but you must only meet **one** of
 * > them to win the scenario."* — `Manual_OSuite-Scenario.pdf` §3.7
 *
 * That sentence is the whole shape of this object: everything here is an ALTERNATIVE route to
 * victory, never an extra hurdle. A scenario that authors none behaves exactly as it did before.
 *
 * ### The four, and where each stands
 *
 * | §3.7 | condition | here |
 * |---|---|---|
 * | 3.7.1 | Must-Survive Units | **built here** — the per-unit flag is `@43` bit 0 |
 * | 3.7.2 | Typed victory hexes | not built |
 * | 3.7.3 | Hold N victory hexes | built, `Scenario.checkTimedOutcome`, per side |
 * | 3.7.4 | **Retreat N units to an Escape Hex** | **built here** |
 * | — | Kill N enemy units | **built here** (not in the v0.1 manual; `@1021` bit 4) |
 *
 * ### Escape hexes — what they are
 *
 * > *"First you must check the **Units to Retreat** checkbox ... and type the number of units to
 * > retreat for any side. Then you must set at least one **Escape Hex (EH)** in the map, for the
 * > units to retreat to."* — §3.7.4
 *
 * A formation that ends its move on an escape hex it may use is **withdrawn** — taken off the map,
 * counted, and gone. When a side has withdrawn as many as its author asked for, that side wins.
 *
 * **The hex says which kinds it accepts, and OG splits them.** [Hex.escapeGround] and
 * [Hex.escapeAir] are separate flags (`.xscn` `@13` bit 3 and `@12` bit 4) and a hex may carry
 * either or both — 419 corpus hexes are ground exits, 156 air. An aircraft cannot walk out through
 * a ground exit and a division cannot fly out through an air one.
 *
 * **Withdrawal is not destruction**, and the difference matters in three places: the formation is
 * not a casualty, its loss is not credited to the enemy, and in a campaign it is not struck off the
 * core roster. It left the map under orders, which is the entire point of the objective.
 *
 * ### An inference, named: whose escape hex is it?
 *
 * OG marks a hex as an exit and states a per-side quota, but nothing published says a given hex
 * belongs to a side. **Any formation may use any escape hex**, and its own side's counter goes up.
 * That is the reading the data supports — a quota of `(0, 4)` means side 1 must get four out, and
 * says nothing about which hexes. If OG turns out to own escape hexes per side, [withdrawalSide] is
 * the one function to change.
 */
object ExtendedVictory {
    /** Whether [unit] may leave the map through [hex] — the air/ground split is OG's own. */
    fun canWithdrawThrough(
        unit: GameUnit,
        hex: Hex,
    ): Boolean = if (UnitPredicates.isAir(unit)) hex.escapeAir else hex.escapeGround

    /** The side credited when [unit] walks off the map. See the KDoc's named inference. */
    private fun withdrawalSide(unit: GameUnit): Int? = unit.player?.side

    /**
     * Withdraw [unit] through [hex] if it may go, returning true when it left.
     *
     * Called as a move completes, so a formation that merely passes over an exit is unaffected —
     * OG's wording throughout §3.7 and §3.4 is about where a unit ENDS its move.
     */
    fun withdraw(
        scenario: Scenario,
        unit: GameUnit,
        hex: Hex,
    ): Boolean {
        val side = withdrawalSide(unit)
        val quota = scenario.retreatUnitsPerSide.getOrNull(side ?: -1) ?: 0
        if (side == null || quota <= 0 || !canWithdrawThrough(unit, hex)) return false
        scenario.unitsWithdrawn[side] = scenario.unitsWithdrawn[side] + 1
        // Off the map, but NOT destroyed: no casualty, no kill credited, no core-roster loss.
        // `GameMap.updateUnitList` only sweeps `destroyed`, so the removal is done here.
        hex.delUnit(unit)
        scenario.map.units.remove(unit)
        GameRules.setZOCRange(scenario.map, unit, false)
        GameRules.setSpotRange(scenario.map, unit, false)
        return true
    }

    /** Whether [side] has got enough formations out to win by §3.7.4. */
    fun retreatObjectiveMet(
        scenario: Scenario,
        side: Int,
    ): Boolean {
        val quota = scenario.retreatUnitsPerSide.getOrNull(side) ?: 0
        return quota > 0 && scenario.unitsWithdrawn.getOrNull(side).orZero() >= quota
    }

    /**
     * Whether [side] has LOST by manual §3.7.1 — too few of its Must-Survive Units are still alive.
     *
     * > *"type the number of the MSU that need to survive **not to lose** the scenario"*
     *
     * **The only extended condition that is a DEFEAT rather than a victory**, which is why it is
     * not part of [satisfiedSide]: meeting the others wins you the scenario, failing this one loses
     * it, and the two cannot share a code path.
     *
     * A quota of 0 means no requirement, not *"all of them"* — `zero_msu` in
     * `EFILE_NOKORP/equip.cfg` exists precisely to allow *"defining zero msu to survives, not
     * meaning 'all msu must survive'"*. A side with no quota, or no designated units, can never
     * lose this way.
     */
    fun mustSurviveObjectiveFailed(
        scenario: Scenario,
        map: GameMap,
        side: Int,
    ): Boolean {
        val required = scenario.mustSurvivePerSide.getOrNull(side) ?: 0
        if (required <= 0) return false
        val alive =
            map.units.count { unit ->
                unit.mustSurvive && !unit.destroyed && unit.player?.side == side
            }
        return alive < required
    }

    /** The side that has LOST by §3.7.1, or null. Separate from [satisfiedSide] by design. */
    fun defeatedSide(
        scenario: Scenario,
        map: GameMap,
    ): Int? =
        map
            .getPlayers()
            .map { it.side }
            .distinct()
            .firstOrNull { mustSurviveObjectiveFailed(scenario, map, it) }

    /** Whether [side] has destroyed enough enemy formations to win by the kill condition. */
    fun killObjectiveMet(
        scenario: Scenario,
        side: Int,
    ): Boolean {
        val quota = scenario.killUnitsPerSide.getOrNull(side) ?: 0
        return quota > 0 && scenario.unitsKilled.getOrNull(side).orZero() >= quota
    }

    /** The side that has met an extended objective, or null. Checked after every move and kill. */
    fun satisfiedSide(
        scenario: Scenario,
        map: GameMap,
    ): Int? =
        map
            .getPlayers()
            .map { it.side }
            .distinct()
            .firstOrNull { retreatObjectiveMet(scenario, it) || killObjectiveMet(scenario, it) }

    private fun Int?.orZero(): Int = this ?: 0
}
