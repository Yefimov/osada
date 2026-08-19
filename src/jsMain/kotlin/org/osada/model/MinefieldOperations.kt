package org.osada.model

import org.osada.rules.MineAbilities
import org.osada.rules.Minefields

/*
 * The two minefield commands, as [GameMap] extensions beside the other unit operations
 * (`docs/og-fidelity-plan.md` C.1, OG manual 9.9).
 *
 * Kept out of [UnitDeployOperations] deliberately: these are not supply or deployment, they mutate
 * HEX state rather than unit state, and both are gated on a ruleset key the other operations know
 * nothing about. Eligibility itself is not re-decided here — `UnitActionAvailability` already
 * answered it and the UI only offers an enabled chip — but each command re-checks the facts it would
 * be unsafe to assume, because a multiplayer command handler can reach them without a chip ever
 * being drawn.
 */

/**
 * Lays a minefield on [unit]'s own hex for two ammunition points and ends its turn.
 *
 * OG requires the unit to have taken no previous action that turn; that half is enforced by
 * `UnitActionAvailability.layMines` and re-asserted here through the same three flags.
 */
fun GameMap.layMinefield(unit: GameUnit): MineActionResult {
    val hex = unit.getHex()
    val side = unit.player?.side ?: -1
    val spentItsTurn = unit.hasMoved || unit.hasFired || unit.hasResupplied
    val allowed =
        hex != null &&
            side >= 0 &&
            MineAbilities.canDropMines(unit) &&
            !spentItsTurn &&
            unit.getAmmo() >= Minefields.LAY_MINES_AMMO_COST
    if (!allowed || hex == null) return MineActionResult.NOT_ALLOWED
    undoState.invalidate(unit, UndoInvalidation.IRREVERSIBLE_ACTION)
    unit.ammo -= Minefields.LAY_MINES_AMMO_COST
    Minefields.lay(hex, side)
    endUnitTurnForMineAction(unit)
    return MineActionResult.LAID
}

/**
 * Attempts to clear the minefield [unit] is standing in.
 *
 * OG: *"The attempt can fail, and a failed attempt suppresses the unit."* A failure therefore costs
 * the turn and adds a suppression point ([GameUnit.hits]) rather than doing nothing — otherwise
 * "can fail" would be indistinguishable from "always succeeds, eventually".
 *
 * The unit may have MOVED onto the field this turn: OG imposes the no-previous-action rule on
 * laying, not on clearing, and a sapper that walked to the minefield has done exactly what it was
 * sent to do.
 */
fun GameMap.clearMinefield(unit: GameUnit): MineActionResult {
    val hex = unit.getHex()
    val allowed =
        hex != null &&
            hex.mines != 0 &&
            MineAbilities.canClearMines(unit) &&
            !unit.hasFired &&
            !unit.hasResupplied
    if (!allowed || hex == null) return MineActionResult.NOT_ALLOWED
    undoState.invalidate(unit, UndoInvalidation.IRREVERSIBLE_ACTION)
    val success = MineAbilities.clearAttemptSucceeds()
    if (success) Minefields.clearAll(hex) else unit.hits++
    endUnitTurnForMineAction(unit)
    return if (success) MineActionResult.CLEARED else MineActionResult.FAILED_ATTEMPT
}

/** Both commands spend the formation's whole turn, the way Supply and Reinforce already do, and
 *  both drop the selection overlays so a stale move range cannot be walked afterwards. */
private fun GameMap.endUnitTurnForMineAction(unit: GameUnit) {
    unit.hasMoved = true
    unit.hasFired = true
    unit.moveLeft = 0
    // Laying or clearing reveals the unit for the same reason firing does: the work is visible.
    // `tempSpotted` only -- `setSpotRange` must NOT be called here. It is an add/remove pair over a
    // reference count, and an unmatched add leaves the fog permanently lifted (the failure recorded
    // on `Hex.clearSpotted`).
    unit.tempSpotted = true
    delAttackSel()
    delMoveSel()
}
