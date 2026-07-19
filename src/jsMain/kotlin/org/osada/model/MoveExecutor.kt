package org.osada.model

import org.osada.LeaderType
import org.osada.PlayerType
import org.osada.rules.GameRules
import org.osada.rules.canCapture
import org.osada.rules.canPassInto
import org.osada.rules.getDirection
import org.osada.rules.getShortestPath
import org.osada.rules.setSpotRange
import org.osada.rules.setZOCRange

/**
 * Executes unit movement along a path and manages the single-move undo stack.
 * Extracted from the former [GameMap] god-class (SRP).
 */
internal class MoveExecutor(
    // Internal (not private): MoveExecutorHelpers.kt's resolveUndoContext/resolveUndoPositions/
    // resolveUndoHexes extension functions (moved out to keep this class under detekt's
    // TooManyFunctions budget) read this from another file.
    internal val gameMap: GameMap,
) {
    fun canUndoMove(unit: GameUnit): Boolean = gameMap.undoState.unit?.id == unit.id

    fun moveUnit(
        unit: GameUnit,
        row: Int,
        col: Int,
    ): MovementResults {
        val result = MovementResults()
        val setup = setupMove(unit, row, col) ?: return result
        saveUndoState(unit)
        val totalCost = traversePath(unit, setup, result)
        val last = result.passedCells.lastOrNull()
        if (last != null) {
            applyMove(unit, setup, row, col, last, totalCost, result)
        }
        return result
    }

    private fun setupMove(
        unit: GameUnit,
        row: Int,
        col: Int,
    ): MoveSetup? {
        val map = gameMap.map
        val from = unit.getPos()
        val side = unit.player?.side
        if (map == null || from == null || side == null) return null
        val path = GameRules.getShortestPath(from, Cell(row, col), gameMap.currentMoveRange)
        return if (path.isEmpty()) {
            null
        } else {
            MoveSetup(map, from, map[from.row][from.col], side, path)
        }
    }

    private fun saveUndoState(unit: GameUnit) {
        if (unit.player?.type != PlayerType.HUMAN_LOCAL) return
        gameMap.undoState.clear()
        gameMap.undoState.unit = unit
        gameMap.undoState.savedUnit =
            GameUnit(unit.eqid).apply {
                copy(unit)
                setHex(unit.getHex())
            }
    }

    private fun traversePath(
        unit: GameUnit,
        setup: MoveSetup,
        result: MovementResults,
    ): Int {
        val enemySide = 1 - setup.side
        var totalCost = 0
        for (i in setup.path.indices) {
            val cell = setup.path[i]
            if (i > 0 && !GameRules.canPassInto(setup.map, unit, cell)) {
                markSurprise(unit, cell, result)
                break
            }
            if (isCellVisible(unit, setup.map, cell, enemySide)) {
                result.isVisible = true
                if (cell is ExtendedCell) cell.isVisible = true
            }
            result.passedCells.add(cell)
            totalCost += if (cell is ExtendedCell) cell.cost else 1
        }
        return totalCost
    }

    private fun markSurprise(
        unit: GameUnit,
        cell: Cell,
        result: MovementResults,
    ) {
        if (!Leaders.unitHasLeader(unit, LeaderType.BATTLEFIELD_INTELLIGENCE) &&
            !Leaders.unitHasLeader(unit, LeaderType.SKILLED_ASSAULT)
        ) {
            unit.isSurprised = true
            result.surpriseCell.add(cell)
        }
    }

    private fun isCellVisible(
        unit: GameUnit,
        map: Array<Array<Hex>>,
        cell: Cell,
        enemySide: Int,
    ): Boolean {
        val type = unit.player?.type
        return type == PlayerType.HUMAN_LOCAL ||
            type == PlayerType.AI_SCRIPTED ||
            map[cell.row][cell.col].isSpotted(enemySide)
    }

    private fun applyMove(
        unit: GameUnit,
        setup: MoveSetup,
        row: Int,
        col: Int,
        last: Cell,
        totalCostIn: Int,
        result: MovementResults,
    ) {
        val from = setup.from
        val toHex = setup.map[last.row][last.col]
        if (last.row == row && last.col == col && GameRules.canCapture(unit)) {
            val capture = gameMap.captureHex(toHex, unit)
            result.isCapture = capture.isCapture
            if (capture.isWin) result.isVictorySide = setup.side
        }

        val totalCost = if (totalCostIn < 0) 0 else totalCostIn
        unit.move(totalCost)
        GameRules.setZOCRange(gameMap, unit, false)
        GameRules.setSpotRange(gameMap, unit, false)
        setup.fromHex.delUnit(unit)
        toHex.setUnit(unit)
        unit.facing = GameRules.getDirection(from.row, from.col, last.row, last.col) ?: unit.facing
        GameRules.setZOCRange(gameMap, unit, true)
        val newlySpotted = GameRules.setSpotRange(gameMap, unit, true)
        gameMap.setMoveRange(unit)
        gameMap.setAttackRange(unit)
        gameMap.undoState.unit = if (isUndoable(newlySpotted, unit)) unit else null
    }

    private fun isUndoable(
        newlySpotted: Int,
        unit: GameUnit,
    ): Boolean = newlySpotted == 0 && !unit.isSurprised && unit.player?.type == PlayerType.HUMAN_LOCAL

    fun undoLastMove() {
        val ctx = resolveUndoContext() ?: return
        val unit = ctx.unit
        val fromHex = ctx.fromHex
        unit.copy(ctx.saved)
        GameRules.setZOCRange(gameMap, unit, false)
        GameRules.setSpotRange(gameMap, unit, false)
        fromHex.delUnit(unit)
        ctx.savedHex.setUnit(unit)
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

    private class MoveSetup(
        val map: Array<Array<Hex>>,
        val from: Cell,
        val fromHex: Hex,
        val side: Int,
        val path: List<Cell>,
    )

    // Internal (not private): MoveExecutorHelpers.kt's resolveUndoHexes extension function
    // (moved out to keep this class under detekt's TooManyFunctions budget) constructs this
    // from another file.
    internal class UndoContext(
        val unit: GameUnit,
        val saved: GameUnit,
        val fromHex: Hex,
        val savedHex: Hex,
    )
}
