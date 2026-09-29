package org.osada.model

import org.osada.rules.RailTransport

/**
 * The map-side half of OG's railway transport: boarding and leaving a train, with the selection
 * rebuilt around the result.
 *
 * Split from [RailTransport] itself exactly as `BarrageOperations` is split from `Barrage` — the
 * rule owns what is legal and what the action does, this owns the selection, and every caller (the
 * local player, a replayed multiplayer order, a test) takes the same path.
 */
internal fun GameMap.entrainUnit(unit: GameUnit): Boolean {
    val boarded = RailTransport.entrain(this, unit)
    if (boarded) {
        undoState.invalidate(unit, UndoInvalidation.IRREVERSIBLE_ACTION)
        reselect(unit)
    }
    return boarded
}

/**
 * Takes [unit] off its train. Undo is invalidated because the move being undone was the train's,
 * and replaying it would put a formation back aboard a train the pool has already been given back.
 */
internal fun GameMap.detrainUnit(unit: GameUnit): Boolean {
    val left = RailTransport.detrain(this, unit)
    if (left) {
        undoState.invalidate(unit, UndoInvalidation.IRREVERSIBLE_ACTION)
        reselect(unit)
    }
    return left
}

/** The unit's movement just changed shape (a train's or its own), so both ranges are rebuilt. */
private fun GameMap.reselect(unit: GameUnit) {
    delMoveSel()
    delAttackSel()
    selectUnit(unit)
}
