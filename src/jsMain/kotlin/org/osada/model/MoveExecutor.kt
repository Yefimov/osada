package org.osada.model

import org.osada.LeaderType
import org.osada.PlayerType
import org.osada.hero.HeroCampaign
import org.osada.rules.AAInterception
import org.osada.rules.GameRules
import org.osada.rules.UnitPredicates
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
        // AA interception can destroy the plane mid-path (§3.4, docs/design/aa-interception.md):
        // it is already correctly positioned at the intercepting cell (applyAAInterception moved
        // it there before applying damage), so the normal destroyed-unit sweep finds and removes
        // it from exactly where it fell -- applyMove must NOT also run, or a dead unit would be
        // "placed" a second time.
        if (unit.destroyed) {
            gameMap.undoState.clear()
            gameMap.updateUnitList()
        } else {
            result.passedCells.lastOrNull()?.let { last ->
                applyMove(unit, setup, row, col, last, totalCost, result)
            }
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

    // Two distinct early-exit conditions (blocked terrain, AA interception) genuinely both belong
    // in this per-cell walk -- splitting them apart would relocate the `when`, not simplify it.
    @Suppress("LoopWithTooManyJumpStatements")
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
            if (i > 0 && applyAAInterception(unit, setup, cell, i == setup.path.lastIndex, result)) {
                break
            }
            if (i > 0 && stoppedByUnseenZoc(unit, setup.map, setup.side, cell, enemySide)) {
                result.stoppedByUnseenEnemy = true
                break
            }
        }
        return totalCost
    }

    /** AA interception of a moving aircraft (DEFERRED.md §1.1). Checks the cell [unit] just
     *  entered; if any enemy AA fires, relocates [unit] there (so the combat resolves against
     *  where it actually is, not its stale start-of-move position) and applies one-sided damage.
     *  Returns true when interception fired -- the walk must stop at this cell regardless of
     *  whether the plane survived (docs/design/aa-interception.md §3.4). */
    private fun applyAAInterception(
        unit: GameUnit,
        setup: MoveSetup,
        cell: Cell,
        isDestination: Boolean,
        result: MovementResults,
    ): Boolean {
        val interceptors =
            if (UnitPredicates.isAir(unit)) {
                AAInterception.interceptorsFor(gameMap, unit, cell, isDestination)
            } else {
                emptyList()
            }
        if (interceptors.isEmpty()) return false

        GameRules.setSpotRange(gameMap, unit, false)
        unit.getHex()?.delUnit(unit)
        setup.map[cell.row][cell.col].setUnit(unit)
        GameRules.setSpotRange(gameMap, unit, true)
        result.interceptions.addAll(AAInterception.applyInterception(gameMap, unit, interceptors))
        result.wasIntercepted = true
        return true
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

    /** DEFERRED.md §1.12: whether `AI_SCRIPTED` bypassing fog here (animating in full through
     *  hexes the human side has not spotted) is deliberate was previously undecided. **Decided:
     *  deliberate.** `AI_SCRIPTED` exists only for the Khalkhin Gol tutorial's scripted
     *  demonstration turns (`docs/tutorial.md`), and every other place that branches on player
     *  type treats it the same as `HUMAN_LOCAL` for exactly this reason -- `UICombatLog` logs its
     *  combats, `StatusBarController` narrates its turn -- because a guided demonstration that
     *  vanishes into "fogged, snap to destination" defeats the point of demonstrating it. Do not
     *  gate this on spotting; the off-screen gap this same entry also named is fixed instead, in
     *  `AnimationOrchestrator.isPathEntirelyOffScreen`. */
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
            result.capturePrestige = capture.prestigeGain as? Int ?: 0
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
        // §7.43 reconnaissance evidence. Safe against undo without any bookkeeping: `undoFinality`
        // below already refuses to offer an undo for a move that revealed something, so a credited
        // contact can never be rewound out from under the evidence it granted.
        HeroCampaign.recordReconnaissance(unit, newlySpotted)
        gameMap.setMoveRange(unit)
        gameMap.setAttackRange(unit)
        if (unit.player?.type != PlayerType.HUMAN_LOCAL) {
            // No record was ever saved for a non-local player, and its units never show the action
            // strip -- there is nothing to explain.
            gameMap.undoState.unit = null
            return
        }
        val finality = undoFinality(newlySpotted, unit, result.wasIntercepted, result.stoppedByUnseenEnemy)
        if (finality == null) {
            gameMap.undoState.unit = unit
        } else {
            gameMap.undoState.invalidate(unit, finality)
        }
    }

    /** Null when the move stays undoable, otherwise the single reason it became final. Order is
     *  the reporting order too: the most immediate cause first. */
    private fun undoFinality(
        newlySpotted: Int,
        unit: GameUnit,
        wasIntercepted: Boolean,
        stoppedByUnseenEnemy: Boolean,
    ): UndoInvalidation? =
        when {
            wasIntercepted -> UndoInvalidation.INTERCEPTED
            unit.isSurprised -> UndoInvalidation.SURPRISED
            newlySpotted != 0 -> UndoInvalidation.NEW_INTELLIGENCE
            // Same reasoning as an intercepted move: rewinding a move that a hidden enemy stopped
            // would make probing for hidden units free (DEFERRED.md §7.32 item 4). In practice a
            // stop usually reveals the enemy anyway, which `newlySpotted` already catches -- this
            // covers the case where it stopped without spotting it.
            stoppedByUnseenEnemy -> UndoInvalidation.STOPPED_BY_HIDDEN_ENEMY
            else -> null
        }

    fun undoLastMove() {
        val ctx = resolveUndoContext() ?: return
        val unit = ctx.unit
        val fromHex = ctx.fromHex
        unit.copy(ctx.saved)
        // copy() detaches unit.player onto a throwaway Player; re-point it at the shared
        // instance so refunds below land on the real player, not a copy of a copy.
        val player = gameMap.getPlayer(unit.player?.id ?: 0)
        unit.player = player
        GameRules.setZOCRange(gameMap, unit, false)
        GameRules.setSpotRange(gameMap, unit, false)
        fromHex.delUnit(unit)
        ctx.savedHex.setUnit(unit)
        GameRules.setZOCRange(gameMap, unit, true)
        GameRules.setSpotRange(gameMap, unit, true)
        gameMap.selectUnit(unit)
        gameMap.undoState.oldOwner?.let {
            fromHex.owner = it
            // Undoing a capture takes the hex (and any deploy zone it opened) back too.
            gameMap.invalidateDeployZones()
        }
        gameMap.undoState.oldFlag?.let { fromHex.flag = it }
        gameMap.undoState.oldVictorySide?.let { vs ->
            gameMap.updateVictorySides(1 - player.side, fromHex.getPos())
            fromHex.victorySide = vs
        }
        gameMap.undoState.prestigeGain?.let { player.prestige -= it }
        gameMap.undoState.scoreGain?.let { player.updateScore(-it) }
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
