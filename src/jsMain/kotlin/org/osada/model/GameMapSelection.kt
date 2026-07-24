package org.osada.model

import org.osada.rules.GameRules
import org.osada.rules.getMoveRange
import org.osada.rules.getUnitAttackCells

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
}

fun GameMap.delMoveSel() {
    currentMoveRange.forEach { cell ->
        map?.getOrNull(cell.row)?.getOrNull(cell.col)?.isMoveSel = false
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
    range.forEach { cell ->
        currentMoveRange.add(cell)
        if (cell.canMove) {
            map?.getOrNull(cell.row)?.getOrNull(cell.col)?.isMoveSel = true
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
