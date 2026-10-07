package org.osada.model

import org.osada.rules.CarrierHangars

/**
 * Lands [unit] in [container]'s hangar. Irreversible -- Undo cannot put a formation back on a hex it
 * is no longer on -- and the rest of its turn.
 */
internal fun GameMap.landInHangar(
    unit: GameUnit,
    container: GameUnit,
): Boolean {
    if (!CarrierHangars.canBoard(unit, container)) return false
    undoState.invalidate(unit, UndoInvalidation.IRREVERSIBLE_ACTION)
    if (currentUnit === unit) delCurrentUnit()
    unit.hasMoved = true
    unit.hasFired = true
    return CarrierHangars.board(this, unit, container)
}

/**
 * Offers [passenger]'s take-off hexes ([CarrierHangars.launchCells]) through the move highlight,
 * which every deselection already clears. Returns them; empty means there is nowhere to go.
 */
internal fun GameMap.openHangarLaunchTargeting(
    container: GameUnit,
    passenger: GameUnit,
): List<Hex> {
    delCurrentUnit()
    val cells = CarrierHangars.launchCells(this, passenger, container)
    if (cells.isEmpty()) return cells
    hangarLaunch = HangarLaunch(container, passenger)
    cells.forEach { hex ->
        val pos = hex.getPos()
        currentMoveRange.add(pos)
        hex.isMoveSel = true
        markRepaint(pos)
    }
    return cells
}

/** Leaves the take-off choice without launching anything. */
internal fun GameMap.clearHangarLaunchTargeting() {
    if (hangarLaunch == null) return
    hangarLaunch = null
    delMoveSel()
}

/**
 * Puts [passenger] out of [container] onto [hex] and selects it, with its move range drawn: a take-off
 * is followed by the flight, so the next click is where it goes.
 */
internal fun GameMap.launchFromHangar(
    container: GameUnit,
    passenger: GameUnit,
    hex: Hex,
): Boolean {
    clearHangarLaunchTargeting()
    if (!CarrierHangars.launch(this, passenger, container, hex)) return false
    setCurrentUnit(passenger)
    setMoveRange(passenger)
    setAttackRange(passenger)
    return true
}
