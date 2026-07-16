package org.osada.ai

import org.osada.*
import org.osada.model.*

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

/** Jitter factors applied to all evaluation scores to avoid deterministic play. */
internal val RANDOM_FACTORS = listOf(0.8, 0.85, 0.9, 0.95, 1.0, 1.1, 1.15, 1.2)

/** Unit classes the AI prefers to buy with surplus prestige (most expensive first). */
internal val EXPENSIVE_CLASSES = listOf(UnitClass.FIGHTER, UnitClass.TACTICAL_BOMBER)

/** Unit classes the AI cycles through for budget purchases. */
internal val CHEAP_CLASSES = listOf(
    UnitClass.INFANTRY, UnitClass.INFANTRY, UnitClass.ARTILLERY,
    UnitClass.ANTI_TANK, UnitClass.TANK, UnitClass.INFANTRY, UnitClass.TANK
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
internal val killTable = listOf(0, 50, 25, 30, 10, 10, 5, 150, 150, 10, 50, 100, 100, 150, 50, 50, 50, 100, 150, 50, 50, 50)

/** Result of a single-cell attack evaluation. */
internal data class AttackResult(val score: Int, val kills: Int)

/** Result of the nearest-objective proximity calculation. */
internal data class ObjectiveResult(val cell: Cell?, val score: Int)

/** Result of the unit-purchase selection loop. */
internal data class PurchaseResult(val prestige: Int, val units: List<Int>)
