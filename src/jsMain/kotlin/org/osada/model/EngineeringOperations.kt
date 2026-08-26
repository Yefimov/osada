package org.osada.model

import org.osada.rules.Engineering
import org.osada.rules.EngineeringWork
import org.osada.rules.FacilityOwner

/*
 * The Build and Repair commands, as [GameMap] extensions beside [MinefieldOperations]
 * (OG manual 9.3, `rules/Engineering`).
 *
 * Same division of labour as the minefield pair, and for the same reasons: eligibility is decided by
 * `UnitActionAvailability` and the UI only offers an enabled chip, but each command re-checks the
 * facts it would be unsafe to assume, because a multiplayer command handler reaches them without a
 * chip ever being drawn.
 */

/**
 * Orders [unit] to begin [work] on the hex it stands on, spending the prestige and its turn.
 *
 * OG requires the sapper to have taken no previous action (*"must be in a hex and hasn't done any
 * action"*), which is enforced by `UnitActionAvailability.engineering` and re-asserted here through
 * the same three flags — [GameUnit.hasMoved] is included, so a sapper cannot walk to a river and
 * throw a bridge across it in one turn.
 *
 * Demolition is instant and free; construction and repair take turns and, where OG names a figure,
 * prestige. The prestige is charged in full at the START, the way OG's own "The construction of a
 * bridge costs 16 PP" reads, so a job cannot be begun on credit and abandoned.
 */
internal fun GameMap.beginEngineering(
    unit: GameUnit,
    work: EngineeringWork,
): EngineeringActionResult {
    val hex = unit.getHex()
    val player = unit.player
    val side = player?.side ?: -1
    val ready = hex != null && player != null && side >= 0 && mayBegin(unit, work)
    if (!ready || hex == null || player == null) return EngineeringActionResult.NOT_ALLOWED
    undoState.invalidate(unit, UndoInvalidation.IRREVERSIBLE_ACTION)
    if (work.cost > 0) player.prestige -= work.cost
    Engineering.begin(hex, side, work, FacilityOwner(player.id, player.country))
    endUnitTurnForEngineering(unit)
    // A demolition changes terrain THIS INSTANT, and `extended_los` blocks line of sight on terrain,
    // so the spotting reference counts have to be re-derived before anyone reads them again. A
    // multi-turn job changes nothing yet and is rebuilt when it completes (`GameMap.endTurn`).
    if (work.turns == 0) recomputeSpotting()
    return if (work.turns == 0) EngineeringActionResult.DEMOLISHED else EngineeringActionResult.STARTED
}

/**
 * Advances the engineering jobs of the player whose turn is ENDING by one turn, completing any
 * that finish.
 *
 * Called from `GameMap.endTurn`, so a job STARTED this turn does tick at this same turn end: OG's
 * "takes 2 of your turns" counts the turn the formation spends beginning the work, and suppressing
 * the first tick would make every job take one turn longer than its own tooltip promises
 * (`GameMap.endTurn` carries the full note). A completed job changes terrain, so spotting is
 * re-derived once for the whole batch.
 */
internal fun GameMap.advanceEngineering(side: Int) {
    // The player whose turn is ending. Their own jobs are the ones that count down, and the ones
    // completing now fly their flag -- both recorded on the hex when the work began, so an ally
    // ending their turn neither accelerates this player's bridge nor walks off with their airfield
    // (`Hex.constructionPlayer`). `side` is passed only for jobs restored from a save old enough
    // to have no builder recorded, where it is the only thing there is to match on.
    val owner = currentPlayer?.let { FacilityOwner(it.id, it.country) } ?: FacilityOwner.NONE
    if (Engineering.advanceTurn(map, side, owner).isNotEmpty()) {
        invalidateDeployZones()
        recomputeSpotting()
    }
}

/**
 * Every condition the order has to satisfy, in one predicate so [beginEngineering] itself stays
 * a short sequence of effects.
 *
 * Deliberately re-derived here rather than trusted from the chip: a multiplayer command handler
 * reaches this function with no chip ever having been drawn, which is the same reason
 * `MinefieldOperations` re-checks its own facts.
 */
private fun mayBegin(
    unit: GameUnit,
    work: EngineeringWork,
): Boolean {
    val spentItsTurn = unit.hasMoved || unit.hasFired || unit.hasResupplied
    val affordable = (unit.player?.prestige ?: 0) >= work.cost
    return !spentItsTurn && affordable && work in Engineering.availableWork(unit)
}

/** Engineering spends the formation's whole turn, exactly as Supply, Reinforce and the two mine
 *  actions do, and drops the selection overlays so a stale move range cannot be walked afterwards. */
private fun GameMap.endUnitTurnForEngineering(unit: GameUnit) {
    unit.hasMoved = true
    unit.hasFired = true
    unit.moveLeft = 0
    // Visible work, so the unit reveals itself for the same reason firing does. `tempSpotted` only:
    // `setSpotRange` is an add/remove pair over a reference count and an unmatched add leaves the
    // fog permanently lifted (the failure recorded on `Hex.clearSpotted`).
    unit.tempSpotted = true
    delAttackSel()
    delMoveSel()
}
