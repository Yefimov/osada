package org.osada.model

import org.osada.*
import org.osada.rules.GameRules

/**
 * Executes unit movement along a path and manages the single-move undo stack.
 * Extracted from the former [GameMap] god-class (SRP).
 */
internal class MoveExecutor(private val gameMap: GameMap) {

    fun canUndoMove(unit: GameUnit): Boolean = gameMap.undoState.unit?.id == unit.id

    fun moveUnit(unit: GameUnit, row: Int, col: Int): MovementResults {
        val result = MovementResults()
        val map = gameMap.map ?: return result
        val from = unit.getPos() ?: return result
        val fromHex = map[from.row][from.col]
        val side = unit.player?.side ?: return result
        val enemySide = 1 - side
        val canCapture = GameRules.canCapture(unit)
        val path = GameRules.getShortestPath(from, Cell(row, col), gameMap.currentMoveRange)
        if (path.isEmpty() || path[0] == null) return result

        if (unit.player?.type == PlayerType.HUMAN_LOCAL) {
            gameMap.undoState.clear()
            gameMap.undoState.unit = unit
            gameMap.undoState.savedUnit = GameUnit(unit.eqid).apply { copy(unit); setHex(unit.getHex()) }
        }

        var totalCost = 0
        for (i in path.indices) {
            val cell = path[i]
            if (i > 0 && !GameRules.canPassInto(map, unit, cell)) {
                if (!Leaders.unitHasLeader(unit, LeaderType.BATTLEFIELD_INTELLIGENCE)
                    && !Leaders.unitHasLeader(unit, LeaderType.SKILLED_ASSAULT)
                ) {
                    unit.isSurprised = true
                    result.surpriseCell.add(cell)
                }
                break
            }
            if (unit.player?.type == PlayerType.HUMAN_LOCAL
                || unit.player?.type == PlayerType.AI_SCRIPTED
                || map[cell.row][cell.col].isSpotted(enemySide)
            ) {
                result.isVisible = true
                if (cell is ExtendedCell) cell.isVisible = true
            }
            result.passedCells.add(cell)
            totalCost += if (cell is ExtendedCell) cell.cost else 1
        }

        val last = result.passedCells.lastOrNull() ?: return result
        val toHex = map[last.row][last.col]
        if (last.row == row && last.col == col && canCapture) {
            val capture = gameMap.captureHex(toHex, unit)
            result.isCapture = capture.isCapture
            if (capture.isWin) result.isVictorySide = side
        }

        if (totalCost < 0) totalCost = 0
        unit.move(totalCost)
        GameRules.setZOCRange(gameMap, unit, false)
        GameRules.setSpotRange(gameMap, unit, false)
        fromHex.delUnit(unit)
        toHex.setUnit(unit)
        unit.facing = GameRules.getDirection(from.row, from.col, last.row, last.col) ?: unit.facing
        GameRules.setZOCRange(gameMap, unit, true)
        val newlySpotted = GameRules.setSpotRange(gameMap, unit, true)
        gameMap.setMoveRange(unit)
        gameMap.setAttackRange(unit)
        gameMap.undoState.unit = if (newlySpotted == 0 && !unit.isSurprised && unit.player?.type == PlayerType.HUMAN_LOCAL) unit else null
        return result
    }

    fun undoLastMove() {
        val unit = gameMap.undoState.unit ?: return
        val saved = gameMap.undoState.savedUnit ?: return
        val from = unit.getPos() ?: return
        val savedPos = saved.getPos() ?: return
        val fromHex = gameMap.map?.getOrNull(from.row)?.getOrNull(from.col) ?: return
        val savedHex = gameMap.map?.getOrNull(savedPos.row)?.getOrNull(savedPos.col) ?: return
        unit.copy(saved)
        GameRules.setZOCRange(gameMap, unit, false)
        GameRules.setSpotRange(gameMap, unit, false)
        fromHex.delUnit(unit)
        savedHex.setUnit(unit)
        GameRules.setZOCRange(gameMap, unit, true)
        GameRules.setSpotRange(gameMap, unit, true)
        gameMap.selectUnit(unit)
        gameMap.undoState.oldOwner?.let { fromHex.owner = it }
        gameMap.undoState.oldFlag?.let { fromHex.flag = it }
        gameMap.undoState.oldVictorySide?.let { vs ->
            val player = gameMap.getPlayer(unit.player?.id ?: 0)
            gameMap.updateVictorySides(1 - player.side, fromHex.getPos())
            fromHex.victorySide = vs
        }
        gameMap.undoState.prestigeGain?.let { unit.player?.prestige = unit.player?.prestige?.minus(it) ?: 0 }
        gameMap.undoState.scoreGain?.let { unit.player?.updateScore(-it) }
        gameMap.undoState.clear()
    }
}
