package org.osada.ai

import org.osada.ActionType
import org.osada.model.Cell
import org.osada.model.ExtendedCell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.getAttackableUnit
import org.osada.rules.AiOrders
import org.osada.rules.GameRules
import org.osada.rules.getUnitAttackCells
import org.osada.rules.isAir
import org.osada.rules.isGround
import org.osada.rules.isSea

/**
 * [AI]'s per-unit move and attack target selection. Split out purely to keep [AI] within the
 * project's function-count/class-size limits -- not expected to be called from elsewhere.
 */
internal object AITurnPlanning {
    /** Moves [unit] to its best reachable cell, if any beats staying put; returns whether it moved. */
    fun tryMove(
        aiUnit: AIUnit,
        unit: GameUnit,
        currentPos: Cell,
        moveRange: Array<ExtendedCell>,
        state: AIEvaluationState,
        reservedCells: MutableList<Cell>,
        enemyCells: MutableList<Cell>,
        addAction: (ActionType, Array<dynamic>) -> Unit,
    ): Boolean {
        val bestCell = findBestMoveCell(unit, currentPos, moveRange, state) ?: return false
        reservedCells.add(bestCell)
        val index = reservedIndex(bestCell, enemyCells)
        if (index >= 0) enemyCells.removeAt(index)
        enemyCells.add(currentPos)
        addAction(ActionType.MOVE, arrayOf(unit, bestCell))
        aiUnit.didMove = true
        aiUnit.newPosition = bestCell
        return true
    }

    private fun findBestMoveCell(
        unit: GameUnit,
        currentPos: Cell,
        moveRange: Array<ExtendedCell>,
        state: AIEvaluationState,
    ): Cell? {
        val currentExtended = ExtendedCell(currentPos.row, currentPos.col).apply { canMove = true }
        var bestScore = AIPositionEvaluation.evaluatePosition(unit, currentExtended, state)
        if (GameRules.isGround(unit)) {
            bestScore += AIPositionEvaluation.objectiveScore(currentPos, state.ownVictoryHexes).score
        }
        bestScore += authoredObjectiveScore(unit, currentPos, state)
        bestScore += unit.entrenchment * ENTRENCHMENT_BONUS
        bestScore += AIAttackEvaluation.evaluateAttacksFromPosition(unit, currentPos, state)

        var bestCell: Cell? = null
        for (cell in moveRange) {
            val score = scoreMoveCandidate(unit, cell, state) ?: continue
            if (score > bestScore) {
                bestCell = cell
                bestScore = score
            }
        }
        return bestCell
    }

    /**
     * The scenario author's own **objective hex** for [unit] (`.xscn` `@58`/`@59`), scored exactly
     * as OSADA scores its own objectives so the two are on one scale and the planner can still
     * prefer a better position when one exists.
     *
     * Zero for the 469,632 formations that carry no order, so nothing changes for them. Order
     * resolution -- inheritance from another formation, and release once the unit is within the
     * authored distance -- is `rules/AiOrders`' job, not this file's.
     */
    private fun authoredObjectiveScore(
        unit: GameUnit,
        cell: Cell,
        state: AIEvaluationState,
    ): Int {
        val target = AiOrders.objectiveOf(state.map, unit) ?: return 0
        return AIPositionEvaluation.objectiveScore(cell, listOf(target)).score
    }

    private fun scoreMoveCandidate(
        unit: GameUnit,
        cell: ExtendedCell,
        state: AIEvaluationState,
    ): Int? {
        var score = AIPositionEvaluation.objectiveScore(cell, state.ownVictoryHexes).score
        if (GameRules.isAir(unit)) score = (score * AIR_POSITION_SCORE_MULTIPLIER).toInt()
        if (GameRules.isSea(unit)) score = 0
        // Added AFTER the per-class scaling above, and deliberately: that scaling exists to temper
        // OSADA's own victory-hex heuristic for aircraft and ships, and the author's own order for
        // this one formation is not a heuristic.
        score += authoredObjectiveScore(unit, cell, state)
        score += AIPositionEvaluation.evaluatePosition(unit, cell, state)
        if (score <= BLOCKED_MOVE_SCORE_THRESHOLD) return null
        score += AIAttackEvaluation.evaluateAttacksFromPosition(unit, cell, state)
        return score
    }

    /** Fires [unit] at its best-scoring attackable target, if any beats holding fire. */
    fun tryAttack(
        aiUnit: AIUnit,
        unit: GameUnit,
        map: GameMap,
        state: AIEvaluationState,
        enemyStates: MutableMap<Int, EnemyUnit>,
        addAction: (ActionType, Array<dynamic>) -> Unit,
    ): Boolean {
        val best = findBestAttackTarget(unit, aiUnit, map, state)
        val target = best.target ?: return false
        val enemyState = enemyStates.getOrPut(target.id) { EnemyUnit(target) }
        enemyState.isAttacked = true
        enemyState.losses += best.kills
        if (enemyState.losses > target.strength) enemyState.isKilled = true
        addAction(ActionType.ATTACK, arrayOf(unit, target))
        aiUnit.didAttack = true
        aiUnit.didResupplyReinforce = true
        return true
    }

    private fun findBestAttackTarget(
        unit: GameUnit,
        aiUnit: AIUnit,
        map: GameMap,
        state: AIEvaluationState,
    ): BestAttack {
        val originalHex = unit.getHex()
        val tempHex = resolveAttackHex(unit, aiUnit, map, originalHex)
        var bestScore = -(RISK_WEIGHT * lossTable[unit.unitData().uclass])
        if (tempHex?.victorySide != -1) bestScore -= VICTORY_HEX_ATTACK_PENALTY
        var bestKills = 0
        var bestTarget: GameUnit? = null

        val attackCells = GameRules.getUnitAttackCells(map.map, unit, map.rows, map.cols)
        for (cell in attackCells) {
            val result = AIAttackEvaluation.evaluateAttack(unit, cell, false, state)
            if (result.score > bestScore) {
                bestScore = result.score
                bestKills = result.kills
                bestTarget =
                    map.map
                        ?.getOrNull(cell.row)
                        ?.getOrNull(cell.col)
                        ?.getAttackableUnit(unit, false)
            }
        }
        if (aiUnit.didMove && originalHex != null) unit.setHex(originalHex)
        return BestAttack(bestTarget, bestKills)
    }

    /** Temporarily swaps [unit] onto its post-move hex for attack-range purposes, if it moved. */
    private fun resolveAttackHex(
        unit: GameUnit,
        aiUnit: AIUnit,
        map: GameMap,
        originalHex: Hex?,
    ): Hex? {
        if (!aiUnit.didMove || aiUnit.newPosition == null) return originalHex
        val tempHex = map.map?.getOrNull(aiUnit.newPosition!!.row)?.getOrNull(aiUnit.newPosition!!.col)
        if (tempHex != null) unit.setHex(tempHex)
        return tempHex
    }
}

/** Best attack target found for a unit this turn, and the kills its best attack would inflict. */
internal data class BestAttack(
    val target: GameUnit?,
    val kills: Int,
)
