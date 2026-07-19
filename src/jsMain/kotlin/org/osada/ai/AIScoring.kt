package org.osada.ai

import org.osada.UnitClass
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import kotlin.random.Random

/** Tuning constants for reinforce/resupply/purchase decisions. */
internal const val REINFORCE_STRENGTH_THRESHOLD = 4
internal const val AMMO_THRESHOLD = 2
internal const val FUEL_THRESHOLD = 5
internal const val ENTRENCHMENT_BONUS = 50
internal const val PRESTIGE_RESERVE = 1000
internal const val SAVE_RATIO = 0.3
internal const val MAX_UNIT_COST = 900
internal const val MIN_UNIT_COST = 120

/** Tuning constants for position/attack evaluation. */
internal const val OBJECTIVE_PROXIMITY_BASE = 4000
internal const val RISK_WEIGHT = 4
internal const val CANCELLED_ATTACK_SCORE = -1000
internal const val UNSEEN_TARGET_MULTIPLIER = 0.3
internal const val ATTACK_LOSS_RATIO_LIMIT = 2.5
internal const val MIN_SAVE_AMOUNT = 300
internal const val AIR_POSITION_SCORE_MULTIPLIER = 0.3
internal const val BLOCKED_MOVE_SCORE_THRESHOLD = -2000
internal const val VICTORY_HEX_ATTACK_PENALTY = 50
internal const val ALREADY_ATTACKED_BONUS = 100
internal const val VICTORY_TARGET_BONUS = 350
internal const val BLOCKED_POSITION_SCORE = -5000
internal const val UNSPOTTED_BY_ENEMY_PENALTY = 40
internal const val UNSPOTTED_BY_SELF_BONUS = 30
internal const val OWN_DEPLOYMENT_HEX_PENALTY = 100
internal const val OBJECTIVE_REACHED_BONUS = 100

/** [AIPositionEvaluation] terrain-scoring tuning constants: bonuses/penalties for a candidate hex. */
internal const val TERRAIN_ENTRENCH_BONUS = 50
internal const val TERRAIN_INITIATIVE_BONUS = 20
internal const val RIVER_NO_ROAD_PENALTY = 70
internal const val CLOSE_COMBAT_INFANTRY_BONUS = 120
internal const val CLOSE_COMBAT_SUPPORT_BONUS = 10
internal const val CLOSE_COMBAT_OTHER_PENALTY = 50
internal const val NEUTRAL_FLAG_BONUS = 50

/** [AIPositionEvaluation] victory-capture tuning constants: bonus for moving onto a capturable victory hex. */
internal const val VICTORY_CAPTURE_ARTILLERY_BONUS = 50
internal const val VICTORY_CAPTURE_INFANTRY_BONUS = 600
internal const val VICTORY_CAPTURE_TANK_BONUS = 350
internal const val VICTORY_CAPTURE_DEFAULT_BONUS = 300

/** [AIPositionEvaluation] adjacency tuning constants: bonuses from neighbouring units/victory hexes. */
internal const val ADJACENT_FRIENDLY_BONUS = 20
internal const val ADJACENT_FRIENDLY_ARTILLERY_BONUS = 80
internal const val ADJACENT_ENEMY_ARTILLERY_PENALTY = 100
internal const val ADJACENT_ENEMY_BONUS = 40
internal const val ADJACENT_VICTORY_SUPPORT_BONUS = 50
internal const val ADJACENT_VICTORY_INFANTRY_FRIENDLY_BONUS = 70
internal const val ADJACENT_VICTORY_INFANTRY_ENEMY_BONUS = 100
internal const val ADJACENT_VICTORY_FRIENDLY_BONUS = 50
internal const val ADJACENT_VICTORY_ENEMY_BONUS = 70

/** Jitter factors applied to all evaluation scores to avoid deterministic play. */
internal val RANDOM_FACTORS = listOf(0.8, 0.85, 0.9, 0.95, 1.0, 1.1, 1.15, 1.2)

/** Unit classes the AI prefers to buy with surplus prestige (most expensive first). */
internal val EXPENSIVE_CLASSES = listOf(UnitClass.FIGHTER, UnitClass.TACTICAL_BOMBER)

/** Unit classes the AI cycles through for budget purchases. */
internal val CHEAP_CLASSES =
    listOf(
        UnitClass.INFANTRY,
        UnitClass.INFANTRY,
        UnitClass.ARTILLERY,
        UnitClass.ANTI_TANK,
        UnitClass.TANK,
        UnitClass.INFANTRY,
        UnitClass.TANK,
    )

/**
 * Per-class casualty cost used when weighing attack risk.
 * Index matches [UnitClass.value]; 0 = unknown/unused slot.
 */
internal val lossTable = listOf(0, 5, 5, 10, 10, 5, 10, 40, 20, 10, 5, 30, 30, 40, 15, 10, 10, 40, 40, 10, 10, 10)

/**
 * Per-class kill reward used when evaluating combat profitability.
 * Index matches [UnitClass.value]; 0 = unknown/unused slot.
 */
internal val killTable =
    listOf(0, 50, 25, 30, 10, 10, 5, 150, 150, 10, 50, 100, 100, 150, 50, 50, 50, 100, 150, 50, 50, 50)

/** Result of a single-cell attack evaluation. */
internal data class AttackResult(
    val score: Int,
    val kills: Int,
)

/** Result of the nearest-objective proximity calculation. */
internal data class ObjectiveResult(
    val cell: Cell?,
    val score: Int,
)

/** Result of the unit-purchase selection loop. */
internal data class PurchaseResult(
    val prestige: Int,
    val units: List<Int>,
)

/** Per-target attack/kill bookkeeping accumulated across an [AI] turn's attack decisions. */
internal class EnemyUnit(
    val unit: GameUnit,
) {
    var isAttacked: Boolean = false
    var losses: Int = 0
    var isKilled: Boolean = false
}

/**
 * Read-only snapshot of [AI]'s turn state, passed to the evaluation/planning objects so they
 * don't need direct access to [AI]'s private fields. [reservedCells], [enemyCells] and
 * [enemyStates] are live views over AI's mutable collections (never reassigned, only cleared),
 * so mutations made through AI remain visible here without reconstructing this per call.
 */
internal class AIEvaluationState(
    val map: GameMap,
    val player: Player,
    val reservedCells: List<Cell>,
    val enemyCells: List<Cell>,
    val enemyStates: Map<Int, EnemyUnit>,
    val ownVictoryHexes: List<Cell>,
)

/** Jitter multiplier applied to evaluation scores; see [RANDOM_FACTORS]. */
internal fun randomFactor(): Double = RANDOM_FACTORS[Random.nextInt(0, RANDOM_FACTORS.size)]

/** Index of [cell] within [enemyCells] (cells vacated by a friendly move this turn), or -1. */
internal fun reservedIndex(
    cell: Cell,
    enemyCells: List<Cell>,
): Int {
    enemyCells.forEachIndexed { index, c ->
        if (c.row == cell.row && c.col == cell.col) return index
    }
    return -1
}
