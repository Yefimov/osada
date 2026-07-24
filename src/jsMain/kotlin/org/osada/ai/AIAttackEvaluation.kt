package org.osada.ai

import org.osada.model.Cell
import org.osada.model.GameUnit
import org.osada.model.getAttackableUnit
import org.osada.model.getUnits
import org.osada.rules.GameRules
import org.osada.rules.calculateCombatResults
import org.osada.rules.canInitiateAttack
import org.osada.rules.getUnitAttackCells

/**
 * [AI]'s single-cell attack scoring. Split out purely to keep [AI] within the project's
 * function-count/class-size limits -- not expected to be called from elsewhere.
 */
internal object AIAttackEvaluation {
    /** Score/kills for [attacker] striking the attackable unit at [cell], or a zero result if none. */
    fun evaluateAttack(
        attacker: GameUnit,
        cell: Cell,
        fullOnly: Boolean,
        state: AIEvaluationState,
    ): AttackResult {
        val hex =
            state.map.map
                ?.getOrNull(cell.row)
                ?.getOrNull(cell.col)
        val defender = hex?.getAttackableUnit(attacker, false)
        if (defender == null || !GameRules.canInitiateAttack(attacker, defender)) return AttackResult(0, 0)

        val enemyState = state.enemyStates[defender.id]
        return if (enemyState?.isKilled == true) {
            if (fullOnly) AttackResult(0, 0) else AttackResult(CANCELLED_ATTACK_SCORE, 0)
        } else {
            scoreAttack(attacker, defender, enemyState, fullOnly, state)
        }
    }

    private fun scoreAttack(
        attacker: GameUnit,
        defender: GameUnit,
        enemyState: EnemyUnit?,
        fullOnly: Boolean,
        state: AIEvaluationState,
    ): AttackResult {
        var score = if (enemyState?.isAttacked == true) ALREADY_ATTACKED_BONUS else 0
        val combat = GameRules.calculateCombatResults(attacker, defender, state.map.getUnits().toList(), true, true)
        score +=
            combat.kills * killTable[defender.unitData().uclass] - combat.losses * lossTable[attacker.unitData().uclass]
        val kills = combat.kills

        val tooCostly =
            combat.losses >= attacker.strength || attacker.strength.toDouble() / combat.losses < ATTACK_LOSS_RATIO_LIMIT
        if (tooCostly) {
            return if (fullOnly) AttackResult(score, kills) else AttackResult(CANCELLED_ATTACK_SCORE, kills)
        }

        val defenderHex = defender.getHex()
        if (defenderHex?.victorySide != -1) score += VICTORY_TARGET_BONUS
        if (defenderHex != null && !defenderHex.isSpotted(attacker.player?.side ?: -1)) {
            score = (score * UNSEEN_TARGET_MULTIPLIER).toInt()
        }
        return AttackResult((score * randomFactor()).toInt(), kills)
    }

    /** Total attack-score gained by [unit] if it moved to [cell] before firing. */
    fun evaluateAttacksFromPosition(
        unit: GameUnit,
        cell: Cell,
        state: AIEvaluationState,
    ): Int {
        val originalHex = unit.getHex()
        val targetHex =
            if (originalHex == null) {
                null
            } else {
                state.map.map
                    ?.getOrNull(cell.row)
                    ?.getOrNull(cell.col)
            }
        if (originalHex == null || targetHex == null) return 0
        unit.setHex(targetHex)
        val attackCells = GameRules.getUnitAttackCells(state.map.map, unit, state.map.rows, state.map.cols)
        var total = 0
        for (attackCell in attackCells) {
            total += evaluateAttack(unit, attackCell, true, state).score
        }
        unit.setHex(originalHex)
        return total
    }
}
