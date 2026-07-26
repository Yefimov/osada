package org.osada.ai

import org.osada.RoadType
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.Cell
import org.osada.model.ExtendedCell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.TerrainEx
import org.osada.model.getPlayer
import org.osada.rules.GameRules
import org.osada.rules.canEntrench
import org.osada.rules.distance
import org.osada.rules.getAdjacent
import org.osada.rules.isCloseCombatTerrain
import org.osada.rules.isEnemy
import org.osada.rules.isGround
import org.osada.terrainInitiative

/**
 * [AI]'s candidate-cell scoring: terrain, victory-hex capture, and adjacency bonuses. Split out
 * purely to keep [AI] within the project's function-count/class-size limits -- not expected to
 * be called from elsewhere.
 */
internal object AIPositionEvaluation {
    /** Score for [unit] occupying [cell] this turn: blocked/unreachable cells score as penalties. */
    fun evaluatePosition(
        unit: GameUnit,
        cell: Cell,
        state: AIEvaluationState,
    ): Int {
        val originalHex = unit.getHex()
        val unitSide = unit.player?.side
        val hex =
            if (originalHex == null || unitSide == null) {
                null
            } else {
                state.map.map
                    ?.getOrNull(cell.row)
                    ?.getOrNull(cell.col)
            }
        val isBlocked =
            state.reservedCells.any { it.row == cell.row && it.col == cell.col } ||
                !((cell is ExtendedCell && cell.canMove) || reservedIndex(cell, state.enemyCells) >= 0)

        return when {
            originalHex == null || unitSide == null || hex == null -> 0
            isBlocked -> BLOCKED_POSITION_SCORE
            else -> computePositionScore(unit, cell, hex, originalHex, unitSide, state)
        }
    }

    private fun computePositionScore(
        unit: GameUnit,
        cell: Cell,
        hex: Hex,
        originalHex: Hex,
        unitSide: Int,
        state: AIEvaluationState,
    ): Int {
        val unitClass = unit.unitData().uclass
        val enemySide = 1 - unitSide
        var score = 0
        if (!hex.isSpotted(enemySide)) score -= UNSPOTTED_BY_ENEMY_PENALTY
        if (!hex.isSpotted(unitSide)) score += UNSPOTTED_BY_SELF_BONUS

        val deployment = hex.isDeployment
        if (hex.victorySide == -1 && deployment != -1 && deployment == state.player.id) {
            score -= OWN_DEPLOYMENT_HEX_PENALTY
        }

        val isAirCombatUnit =
            unitClass == UnitClass.FIGHTER.value ||
                unitClass == UnitClass.TACTICAL_BOMBER.value ||
                unitClass == UnitClass.LEVEL_BOMBER.value
        if (!isAirCombatUnit) {
            score += scoreTerrain(unit, unitClass, hex, originalHex)
            score += scoreVictoryCapture(unitClass, hex, unit, state.enemyStates)
            score += scoreAdjacent(unit, cell, unitSide, state)
        }
        return (score * randomFactor()).toInt()
    }

    /** Terrain-type bonuses: entrenchment, initiative, river penalty, close-combat, flag. */
    private fun scoreTerrain(
        unit: GameUnit,
        unitClass: Int,
        hex: Hex,
        originalHex: Hex,
    ): Int {
        var score = 0
        if (GameRules.canEntrench(unit) && unit.entrenchment < TerrainEx.baseEntrenchment(hex.terrain)) {
            score += TERRAIN_ENTRENCH_BONUS
        }
        if (terrainInitiative[originalHex.terrain] < terrainInitiative[hex.terrain]) score += TERRAIN_INITIATIVE_BONUS
        if ((hex.terrain == TerrainType.RIVER.value || hex.terrain == TerrainType.STREAM.value) &&
            hex.road == RoadType.NONE.value
        ) {
            score -= RIVER_NO_ROAD_PENALTY
        }
        if (GameRules.isCloseCombatTerrain(hex.terrain)) {
            score +=
                when (unitClass) {
                    UnitClass.INFANTRY.value -> CLOSE_COMBAT_INFANTRY_BONUS
                    UnitClass.ARTILLERY.value, UnitClass.ANTI_TANK.value, UnitClass.AIR_DEFENCE.value ->
                        CLOSE_COMBAT_SUPPORT_BONUS
                    else -> -CLOSE_COMBAT_OTHER_PENALTY
                }
        }
        if (hex.flag != -1 && hex.flag != unit.player?.country && GameRules.isGround(unit)) score += NEUTRAL_FLAG_BONUS
        return score
    }

    /** Bonus for moving onto a capturable victory hex. */
    private fun scoreVictoryCapture(
        unitClass: Int,
        hex: Hex,
        unit: GameUnit,
        enemyStates: Map<Int, EnemyUnit>,
    ): Int {
        val isVictory = hex.victorySide != -1
        val hasUnit = hex.unit != null
        val unitMatches = hex.unit?.id == unit.id
        val enemyState = hex.unit?.let { enemyStates[it.id] }
        val isEffectivelyEmpty = !hasUnit || unitMatches || (enemyState?.isKilled == true)
        // JS applies this bonus to ANY capturable victory hex (own or enemy); the
        // owner-side check it computes there is dead code, never gating the bonus.
        return if (!isVictory || !isEffectivelyEmpty) {
            0
        } else {
            when (unitClass) {
                UnitClass.ARTILLERY.value -> VICTORY_CAPTURE_ARTILLERY_BONUS
                UnitClass.INFANTRY.value -> VICTORY_CAPTURE_INFANTRY_BONUS
                UnitClass.TANK.value -> VICTORY_CAPTURE_TANK_BONUS
                else -> VICTORY_CAPTURE_DEFAULT_BONUS
            }
        }
    }

    /** Bonuses from friendly/enemy units and victory hexes adjacent to the candidate cell. */
    private fun scoreAdjacent(
        unit: GameUnit,
        cell: Cell,
        unitSide: Int,
        state: AIEvaluationState,
    ): Int {
        val unitClass = unit.unitData().uclass
        var score = 0
        GameRules.getAdjacent(cell.row, cell.col).forEach { neighbor ->
            if (isOutOfBounds(neighbor, state.map)) return@forEach
            val neighborHex =
                state.map.map
                    ?.getOrNull(neighbor.row)
                    ?.getOrNull(neighbor.col) ?: return@forEach
            score += scoreAdjacentUnit(unit, unitClass, neighbor, neighborHex, state)
            score += scoreAdjacentVictoryHex(unitClass, neighborHex, unitSide, state.map)
        }
        return score
    }

    private fun isOutOfBounds(
        neighbor: Cell,
        map: GameMap,
    ): Boolean = neighbor.row < 0 || neighbor.col < 0 || neighbor.row >= map.rows - 1 || neighbor.col >= map.cols - 1

    private fun scoreAdjacentUnit(
        unit: GameUnit,
        unitClass: Int,
        neighbor: Cell,
        neighborHex: Hex,
        state: AIEvaluationState,
    ): Int {
        val neighborUnit = neighborHex.getUnit()
        if (neighborUnit == null || neighborUnit.id == unit.id) return 0
        val isFriendlyUnreserved =
            !GameRules.isEnemy(unit, neighborUnit) && reservedIndex(neighbor, state.enemyCells) < 0
        return if (isFriendlyUnreserved) {
            var bonus = ADJACENT_FRIENDLY_BONUS
            if (neighborUnit.unitData().uclass == UnitClass.ARTILLERY.value) bonus += ADJACENT_FRIENDLY_ARTILLERY_BONUS
            bonus
        } else if (unitClass == UnitClass.ARTILLERY.value) {
            -ADJACENT_ENEMY_ARTILLERY_PENALTY
        } else {
            ADJACENT_ENEMY_BONUS
        }
    }

    private fun scoreAdjacentVictoryHex(
        unitClass: Int,
        neighborHex: Hex,
        unitSide: Int,
        map: GameMap,
    ): Int {
        if (neighborHex.victorySide == -1) return 0
        val friendly = map.getPlayer(neighborHex.owner).side == unitSide
        return when (unitClass) {
            UnitClass.ARTILLERY.value, UnitClass.FLAK.value -> ADJACENT_VICTORY_SUPPORT_BONUS
            UnitClass.INFANTRY.value ->
                if (friendly) ADJACENT_VICTORY_INFANTRY_FRIENDLY_BONUS else ADJACENT_VICTORY_INFANTRY_ENEMY_BONUS
            else -> if (friendly) ADJACENT_VICTORY_FRIENDLY_BONUS else ADJACENT_VICTORY_ENEMY_BONUS
        }
    }

    /** Nearest of [targets] to [cell]: score falls off with hex distance, capped at reaching it. */
    fun objectiveScore(
        cell: Cell,
        targets: List<Cell>,
    ): ObjectiveResult {
        var bestScore = 0
        var bestCell: Cell? = null
        for (target in targets) {
            val dist = GameRules.distance(cell.row, cell.col, target.row, target.col)
            if (dist <= 0) return ObjectiveResult(cell, OBJECTIVE_PROXIMITY_BASE + OBJECTIVE_REACHED_BONUS)
            val score = OBJECTIVE_PROXIMITY_BASE / dist
            if (score > bestScore) {
                bestScore = score
                bestCell = target
            }
        }
        return ObjectiveResult(bestCell, bestScore)
    }
}
