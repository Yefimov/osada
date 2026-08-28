package org.osada.model

import org.osada.rules.AAInterception
import org.osada.rules.AutoMount
import org.osada.rules.GameRules
import org.osada.rules.UnitPredicates
import org.osada.rules.getMoveRange
import org.osada.rules.getUnitAttackCells
import org.osada.uiSettings

/** Unit-selection & move/attack-range management for [GameMap], split out to keep its function
 *  count in bounds. */
fun GameMap.setCurrentUnit(unit: GameUnit?) {
    currentUnit = unit
}

fun GameMap.delCurrentUnit() {
    currentUnit?.let { if (it.carrier < 0) it.toggleEmbark() }
    currentUnit = null
    delMoveSel()
    delAttackSel()
    // Selecting anything else leaves the Barrage targeting mode: the offered hexes belonged to the
    // formation that is no longer selected (OG 9.2, `model/BarrageOperations`).
    uiSettings.barrageMode = false
    clearBarrageTargeting()
    uiSettings.railMode = false
    clearRailTargeting()
}

fun GameMap.delMoveSel() {
    currentMoveRange.forEach { cell ->
        map?.getOrNull(cell.row)?.getOrNull(cell.col)?.apply {
            isMoveSel = false
            isAaThreat = false
            needsTransport = false
        }
    }
    currentMoveRange.clear()
}

fun GameMap.delAttackSel() {
    currentAttackRange.forEach { cell ->
        map?.getOrNull(cell.row)?.getOrNull(cell.col)?.isAttackSel = false
    }
    currentAttackRange.clear()
}

fun GameMap.setMoveRange(unit: GameUnit) {
    delMoveSel()
    val range = GameRules.getMoveRange(this, unit)
    // AA threat overlay (DEFERRED.md §1.1): only meaningful for the aircraft that would actually
    // be intercepted, and only ever built from SPOTTED AA -- hidden AA must never be drawn, or the
    // ambush the mechanic depends on is gone.
    val threatSide = currentPlayer?.side
    val threatened =
        if (UnitPredicates.isAir(unit) && threatSide != null) {
            AAInterception.visibleThreatHexes(this, threatSide, unit)
        } else {
            emptySet()
        }
    range.forEach { cell ->
        currentMoveRange.add(cell)
        val hex = map?.getOrNull(cell.row)?.getOrNull(cell.col)
        if (cell.canMove) {
            hex?.isMoveSel = true
        }
        if ((cell.row to cell.col) in threatened) {
            hex?.isAaThreat = true
        }
    }
    addTransportReach(unit)
}

/**
 * Marks the hexes this formation can reach only by mounting its own transport, and adds them to the
 * same move range everything else hit-tests against — so one click both mounts and drives
 * (`rules/AutoMount`, `MoveExecutor.autoMountForMove`).
 *
 * They are ordinary move cells with one extra flag: the renderer draws them dashed and the cursor
 * turns into a truck over them, which is how Open General says the same thing. A no-op for every
 * formation without organic transport, which is most of them.
 */
private fun GameMap.addTransportReach(unit: GameUnit) {
    AutoMount.transportOnlyCells(this, unit).forEach { cell ->
        currentMoveRange.add(cell)
        map?.getOrNull(cell.row)?.getOrNull(cell.col)?.apply {
            isMoveSel = true
            needsTransport = true
        }
    }
}

fun GameMap.getCurrentMoveRange(): Array<Cell> = currentMoveRange.toTypedArray()

fun GameMap.setAttackRange(unit: GameUnit) {
    delAttackSel()
    val cells = GameRules.getUnitAttackCells(this.map ?: return, unit, rows, cols)
    cells.forEach { cell ->
        currentAttackRange.add(cell)
        map?.getOrNull(cell.row)?.getOrNull(cell.col)?.isAttackSel = true
    }
}

fun GameMap.selectUnit(unit: GameUnit): Boolean {
    if (unit.player?.id != currentPlayer?.id) return false
    delCurrentUnit()
    delMoveSel()
    delAttackSel()
    setCurrentUnit(unit)
    if (unit.carrier < 0) {
        unit.carrier = -unit.carrier
        disembarkUnit(unit)
    }
    if (!unit.hasMoved && unit.carrier >= 0) setMoveRange(unit)
    if (!unit.hasFired) setAttackRange(unit)
    return true
}
