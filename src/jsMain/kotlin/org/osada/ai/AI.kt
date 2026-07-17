package org.osada.ai

import org.osada.ActionType
import org.osada.GameHolder
import org.osada.MovMethod
import org.osada.RoadType
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.Cell
import org.osada.model.Equipment
import org.osada.model.EquipmentData
import org.osada.model.ExtendedCell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.Player
import org.osada.rules.GameRules
import org.osada.terrainEntrenchment
import org.osada.terrainInitiative
import kotlin.js.json
import kotlin.random.Random

class AI(private val player: Player, private val map: GameMap) {

    private class EnemyUnit(val unit: GameUnit) {
        var isAttacked: Boolean = false
        var losses: Int = 0
        var isKilled: Boolean = false
    }

    private val actions: MutableList<dynamic> = mutableListOf()

    private var availablePrestige: Int = player.prestige
    private val reservedCells: MutableList<Cell> = mutableListOf()
    private val enemyCells: MutableList<Cell> = mutableListOf()
    private val enemyStates: MutableMap<Int, EnemyUnit> = mutableMapOf()
    private var ownVictoryHexes: MutableList<Cell> = mutableListOf()
    private var enemyVictoryHexes: MutableList<Cell> = mutableListOf()

    @JsName("buildActions")
    fun buildActions() {
        actions.clear()
        availablePrestige = player.prestige

        purchaseAndDeployPhase()

        val allUnits = map.getUnits()
        reservedCells.clear()
        enemyCells.clear()
        ownVictoryHexes = map.sidesVictoryHexes.getOrElse(player.side) { mutableListOf() }
        enemyVictoryHexes = map.sidesVictoryHexes.getOrElse(1 - player.side) { mutableListOf() }
        enemyStates.clear()

        val unitQueue: MutableList<AIUnit> = mutableListOf()
        allUnits.filter { it.player?.id == player.id }.forEach { unit ->
            val aiUnit = AIUnit(unit)
            val uclass = unit.unitData().uclass
            if (uclass == UnitClass.ARTILLERY.value ||
                uclass == UnitClass.FORTIFICATION.value ||
                (GameRules.isAir(unit) && uclass != UnitClass.AIR_TRANSPORT.value) ||
                (GameRules.isSea(unit) && uclass != UnitClass.NAVAL_TRANSPORT.value)
            ) {
                unitQueue.add(0, aiUnit)
            } else {
                unitQueue.add(aiUnit)
            }
        }

        while (unitQueue.isNotEmpty()) {
            val current = unitQueue[0]
            considerReinforce(current)
            considerResupply(current)
            considerMove(current)
            considerAttack(current)
            if (current.noReinforce && current.noResupply && current.noAttack && current.noMove) {
                unitQueue.removeAt(0)
            }
        }
    }

    /** Buys reinforcements with surplus prestige and deploys undeployed core units. */
    private fun purchaseAndDeployPhase() {
        val prestige = player.prestige
        if (prestige > PRESTIGE_RESERVE) {
            val buyPrestige = prestige - PRESTIGE_RESERVE
            var saveAmount = (buyPrestige * SAVE_RATIO).toInt()
            if (saveAmount < 300) saveAmount = 0
            val expensiveBudget = buyPrestige - saveAmount
            selectUnits(saveAmount, EXPENSIVE_CLASSES).units.forEach { player.buyUnit(it, -1) }
            selectUnits(expensiveBudget, CHEAP_CLASSES).units.forEach { player.buyUnit(it, -1) }
        }
        val coreUnits = player.getCoreUnitList()
        val deployHexes = map.getDeployHexes(player.side)
        deployHexes.forEach { cell ->
            coreUnits.forEachIndexed { index, unit ->
                if (!unit.isDeployed) map.deployPlayerUnit(player, index, cell.row, cell.col)
            }
        }
        GameHolder.instance?.ui?.handleReinforcementDeployment()
    }

    @JsName("getAction")
    fun getAction(): dynamic? {
        if (actions.isEmpty()) return null
        return actions.removeAt(0)
    }

    private fun addAction(type: ActionType, params: Array<dynamic>) {
        actions.add(json(Pair("type", type.value), Pair("param", params)))
    }

    private fun considerReinforce(aiUnit: AIUnit) {
        val unit = aiUnit.unit
        if (!GameRules.canReinforce(map, unit) ||
            aiUnit.didResupplyReinforce ||
            aiUnit.didAttack ||
            aiUnit.didMove ||
            unit.unitData().uclass == UnitClass.FORTIFICATION.value ||
            availablePrestige <= 0
        ) {
            aiUnit.noReinforce = true
            return
        }
        if ((aiUnit.noAttack && aiUnit.noMove) || unit.strength < REINFORCE_STRENGTH_THRESHOLD) {
            val costPerStrength = GameRules.calculateUnitCostPerStrength(unit)
            if (costPerStrength <= 0) {
                aiUnit.noReinforce = true
                return
            }
            val reinforceValue = GameRules.getReinforceValue(map, unit)
            var amount = availablePrestige / costPerStrength
            if (amount > reinforceValue) amount = reinforceValue
            if (reinforceValue > 0 && amount > 0) {
                addAction(ActionType.REINFORCE, arrayOf(unit))
                availablePrestige -= amount * costPerStrength
                aiUnit.didResupplyReinforce = true
                return
            }
        }
        if (aiUnit.noAttack && aiUnit.noMove) aiUnit.noReinforce = true
    }

    private fun considerResupply(aiUnit: AIUnit) {
        val unit = aiUnit.unit
        if (!GameRules.canResupply(map, unit) ||
            aiUnit.didResupplyReinforce ||
            aiUnit.didAttack ||
            aiUnit.didMove
        ) {
            aiUnit.noResupply = true
            return
        }
        if ((aiUnit.noAttack && aiUnit.noMove) ||
            unit.ammo < AMMO_THRESHOLD ||
            (GameRules.unitUsesFuel(unit) && unit.fuel < FUEL_THRESHOLD)
        ) {
            addAction(ActionType.RESUPPLY, arrayOf(unit))
            aiUnit.didResupplyReinforce = true
        } else if (aiUnit.noAttack && aiUnit.noMove) {
            aiUnit.noResupply = true
        }
    }

    private fun considerMove(aiUnit: AIUnit) {
        val unit = aiUnit.unit
        if (aiUnit.didMove || aiUnit.didResupplyReinforce) {
            aiUnit.noMove = true
            return
        }
        val moveRange = GameRules.getMoveRange(map, unit)
        if (moveRange.isEmpty()) {
            aiUnit.noMove = true
            return
        }
        val currentPos = unit.getPos() ?: return
        val currentExtended = ExtendedCell(currentPos.row, currentPos.col).apply { canMove = true }

        var bestScore = evaluatePosition(unit, currentExtended)
        if (GameRules.isGround(unit)) bestScore += objectiveScore(currentPos, true).score
        bestScore += unit.entrenchment * ENTRENCHMENT_BONUS
        bestScore += evaluateAttacksFromPosition(unit, currentPos)

        var bestCell: Cell? = null
        for (cell in moveRange) {
            var score = objectiveScore(cell, true).score
            if (GameRules.isAir(unit)) score = (score * 0.3).toInt()
            if (GameRules.isSea(unit)) score = 0
            score += evaluatePosition(unit, cell)
            if (score <= -2000) continue
            score += evaluateAttacksFromPosition(unit, cell)
            if (score > bestScore) {
                bestCell = cell
                bestScore = score
            }
        }

        if (bestCell != null) {
            reservedCells.add(bestCell)
            val index = reservedIndex(bestCell)
            if (index >= 0) enemyCells.removeAt(index)
            currentPos.let { enemyCells.add(it) }
            addAction(ActionType.MOVE, arrayOf(unit, bestCell))
            aiUnit.didMove = true
            aiUnit.newPosition = bestCell
        } else {
            aiUnit.noMove = true
        }
    }

    private fun considerAttack(aiUnit: AIUnit) {
        val unit = aiUnit.unit
        if (unit.hasFired || aiUnit.didAttack || aiUnit.didResupplyReinforce) {
            aiUnit.noAttack = true
            return
        }

        val originalHex = unit.getHex()
        var tempHex: Hex? = originalHex
        var bestScore = -(RISK_WEIGHT * lossTable[unit.unitData().uclass])
        var bestKills = 0
        var bestTarget: GameUnit? = null

        if (aiUnit.didMove && aiUnit.newPosition != null) {
            tempHex = map.map?.getOrNull(aiUnit.newPosition!!.row)?.getOrNull(aiUnit.newPosition!!.col)
            if (tempHex != null) unit.setHex(tempHex)
        }

        if (tempHex?.victorySide != -1) bestScore -= 50

        val attackCells = GameRules.getUnitAttackCells(map.map, unit, map.rows, map.cols)
        for (cell in attackCells) {
            val result = evaluateAttack(unit, cell, false)
            if (result.score > bestScore) {
                bestScore = result.score
                bestKills = result.kills
                bestTarget = map.map?.getOrNull(cell.row)?.getOrNull(cell.col)?.getAttackableUnit(unit, false)
            }
        }

        if (aiUnit.didMove && originalHex != null) unit.setHex(originalHex)

        if (bestTarget != null) {
            val state = enemyStates.getOrPut(bestTarget.id) { EnemyUnit(bestTarget) }
            state.isAttacked = true
            state.losses += bestKills
            if (state.losses > bestTarget.strength) state.isKilled = true
            addAction(ActionType.ATTACK, arrayOf(unit, bestTarget))
            aiUnit.didAttack = true
            aiUnit.didResupplyReinforce = true
        } else if (aiUnit.noMove || aiUnit.didMove) {
            aiUnit.noAttack = true
        }
    }

    private fun evaluateAttack(attacker: GameUnit, cell: Cell, fullOnly: Boolean): AttackResult {
        val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return AttackResult(0, 0)
        val defender = hex.getAttackableUnit(attacker, false) ?: return AttackResult(0, 0)
        if (!GameRules.canInitiateAttack(attacker, defender)) return AttackResult(0, 0)

        var score = 0
        var kills = 0
        val state = enemyStates[defender.id]
        if (state != null) {
            if (state.isKilled) return if (fullOnly) AttackResult(0, 0) else AttackResult(CANCELLED_ATTACK_SCORE, 0)
            if (state.isAttacked) score += 100
        }

        val combat = GameRules.calculateCombatResults(attacker, defender, map.getUnits().toList(), true, true)
        score +=
            combat.kills * killTable[defender.unitData().uclass] - combat.losses * lossTable[attacker.unitData().uclass]
        kills = combat.kills

        if (combat.losses >= attacker.strength ||
            attacker.strength.toDouble() / combat.losses < ATTACK_LOSS_RATIO_LIMIT
        ) {
            return if (fullOnly) AttackResult(score, kills) else AttackResult(CANCELLED_ATTACK_SCORE, kills)
        }

        val defenderHex = defender.getHex()
        if (defenderHex?.victorySide != -1) score += 350
        if (defenderHex != null && !defenderHex.isSpotted(attacker.player?.side ?: -1)) {
            score = (score * UNSEEN_TARGET_MULTIPLIER).toInt()
        }

        return AttackResult((score * randomFactor()).toInt(), kills)
    }

    private fun evaluateAttacksFromPosition(unit: GameUnit, cell: Cell): Int {
        val originalHex = unit.getHex() ?: return 0
        val targetHex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return 0
        unit.setHex(targetHex)
        val attackCells = GameRules.getUnitAttackCells(map.map, unit, map.rows, map.cols)
        var total = 0
        for (attackCell in attackCells) {
            total += evaluateAttack(unit, attackCell, true).score
        }
        unit.setHex(originalHex)
        return total
    }

    private fun evaluatePosition(unit: GameUnit, cell: Cell): Int {
        val originalHex = unit.getHex() ?: return 0
        val unitClass = unit.unitData().uclass
        val unitSide = unit.player?.side ?: return 0
        val enemySide = 1 - unitSide

        if (reservedCells.any { it.row == cell.row && it.col == cell.col }) return -5000
        val reserved = reservedIndex(cell) >= 0
        val isCurrent = cell is ExtendedCell && cell.canMove || reserved
        if (!isCurrent) return -5000

        val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return 0
        var score = 0
        if (!hex.isSpotted(enemySide)) score -= 40
        if (!hex.isSpotted(unitSide)) score += 30

        val deployment = hex.isDeployment
        if (hex.victorySide == -1 && deployment != -1 && deployment == player.id) score -= 100

        if (unitClass == UnitClass.FIGHTER.value ||
            unitClass == UnitClass.TACTICAL_BOMBER.value ||
            unitClass == UnitClass.LEVEL_BOMBER.value
        ) {
            return (score * randomFactor()).toInt()
        }

        score += scoreTerrain(unit, unitClass, hex, originalHex)
        score += scoreVictoryCapture(unitClass, hex, unit)
        score += scoreAdjacent(unit, cell, unitSide)

        return (score * randomFactor()).toInt()
    }

    /** Terrain-type bonuses: entrenchment, initiative, river penalty, close-combat, flag. */
    private fun scoreTerrain(unit: GameUnit, unitClass: Int, hex: Hex, originalHex: Hex): Int {
        var score = 0
        if (GameRules.canEntrench(unit) && unit.entrenchment < terrainEntrenchment[hex.terrain]) score += 50
        if (terrainInitiative[originalHex.terrain] < terrainInitiative[hex.terrain]) score += 20
        if ((hex.terrain == TerrainType.RIVER.value || hex.terrain == TerrainType.STREAM.value) &&
            hex.road == RoadType.NONE.value
        ) {
            score -= 70
        }
        if (GameRules.isCloseCombatTerrain(hex.terrain)) {
            score += when (unitClass) {
                UnitClass.INFANTRY.value -> 120
                UnitClass.ARTILLERY.value, UnitClass.ANTI_TANK.value, UnitClass.AIR_DEFENCE.value -> 10
                else -> -50
            }
        }
        if (hex.flag != -1 && hex.flag != unit.player?.country && GameRules.isGround(unit)) score += 50
        return score
    }

    /** Bonus for moving onto a capturable victory hex. */
    private fun scoreVictoryCapture(unitClass: Int, hex: Hex, unit: GameUnit): Int {
        val isVictory = hex.victorySide != -1
        if (!isVictory) return 0
        val hasUnit = hex.unit != null
        val unitMatches = hex.unit?.id == unit.id
        val enemyState = hex.unit?.let { enemyStates[it.id] }
        val isEffectivelyEmpty = !hasUnit || unitMatches || (enemyState?.isKilled == true)
        // JS applies this bonus to ANY capturable victory hex (own or enemy); the
        // owner-side check it computes there is dead code, never gating the bonus.
        if (!isEffectivelyEmpty) return 0
        return when (unitClass) {
            UnitClass.ARTILLERY.value -> 50
            UnitClass.INFANTRY.value -> 600
            UnitClass.TANK.value -> 350
            else -> 300
        }
    }

    /** Bonuses from friendly/enemy units and victory hexes adjacent to the candidate cell. */
    private fun scoreAdjacent(unit: GameUnit, cell: Cell, unitSide: Int): Int {
        val unitClass = unit.unitData().uclass
        var score = 0
        val adjacent = GameRules.getAdjacent(cell.row, cell.col)
        for (neighbor in adjacent) {
            if (neighbor.row < 0 ||
                neighbor.col < 0 ||
                neighbor.row >= map.rows - 1 ||
                neighbor.col >= map.cols - 1
            ) {
                continue
            }
            val neighborHex = map.map?.getOrNull(neighbor.row)?.getOrNull(neighbor.col) ?: continue
            val neighborUnit = neighborHex.getUnit()
            if (neighborUnit != null && neighborUnit.id != unit.id) {
                if (!GameRules.isEnemy(unit, neighborUnit) && reservedIndex(neighbor) < 0) {
                    score += 20
                    if (neighborUnit.unitData().uclass == UnitClass.ARTILLERY.value) score += 80
                } else {
                    score += if (unitClass == UnitClass.ARTILLERY.value) -100 else 40
                }
            }
            if (neighborHex.victorySide != -1) {
                val friendly = map.getPlayer(neighborHex.owner).side == unitSide
                score += when (unitClass) {
                    UnitClass.ARTILLERY.value, UnitClass.FLAK.value -> 50
                    UnitClass.INFANTRY.value -> if (friendly) 70 else 100
                    else -> if (friendly) 50 else 70
                }
            }
        }
        return score
    }

    private fun objectiveScore(cell: Cell, ownObjectives: Boolean = true): ObjectiveResult {
        val targets = if (ownObjectives) ownVictoryHexes else enemyVictoryHexes
        var bestScore = 0
        var bestCell: Cell? = null
        for (target in targets) {
            val dist = GameRules.distance(cell.row, cell.col, target.row, target.col)
            if (dist <= 0) return ObjectiveResult(cell, OBJECTIVE_PROXIMITY_BASE + 100)
            val score = OBJECTIVE_PROXIMITY_BASE / dist
            if (score > bestScore) {
                bestScore = score
                bestCell = target
            }
        }
        return ObjectiveResult(bestCell, bestScore)
    }

    private fun reservedIndex(cell: Cell): Int {
        enemyCells.forEachIndexed { index, c ->
            if (c.row == cell.row && c.col == cell.col) return index
        }
        return -1
    }

    private fun randomFactor(): Double = RANDOM_FACTORS[Random.nextInt(0, RANDOM_FACTORS.size)]

    private fun selectUnits(budget: Int, classes: List<UnitClass>): PurchaseResult {
        val result = mutableListOf<Int>()
        val country = player.country + 1
        var remaining = budget
        var classIndex = 0
        var exhausted = false
        val year = GameHolder.instance?.scenario?.date?.getFullYear() ?: 9999
        val month = (GameHolder.instance?.scenario?.date?.getMonth() ?: 0) + 1

        while (remaining > 0) {
            if (classIndex > classes.lastIndex) {
                if (exhausted) break
                classIndex = 0
            }
            val candidates = Equipment.getCountryEquipmentByClass(classes[classIndex], country).toMutableList()
            val iterator = candidates.iterator()
            while (iterator.hasNext()) {
                val eqid = iterator.next()
                val cost = GameRules.calculateUnitCosts(eqid, -1)
                // Equipment.equipment is dynamic (JS interop); cast once so the year/month
                // comparison below is a proper Int comparison rather than a dynamic operand
                // (Int < dynamic is an overload-ambiguity compile error, dynamic > Int isn't —
                // the concrete type has to be on the same side consistently to avoid that trap).
                val data = Equipment.equipment[eqid].unsafeCast<EquipmentData?>()
                if (cost > remaining ||
                    cost > MAX_UNIT_COST ||
                    cost < MIN_UNIT_COST ||
                    data?.movmethod == MovMethod.DEEP_NAVAL.value ||
                    // Lower bound only (matches the original year-only check's own scope — it
                    // never gated on yearexpired either, only adding month precision here, not a
                    // new upper-bound rule): not yet available this year, or available later this
                    // same year than the current month.
                    (
                        data != null &&
                            (year < data.yearavailable || (year == data.yearavailable && month < data.monthavailable))
                        )
                ) {
                    iterator.remove()
                }
            }
            if (candidates.isNotEmpty()) {
                val choice = candidates[Random.nextInt(0, candidates.size)]
                val cost = GameRules.calculateUnitCosts(choice, -1)
                remaining -= cost
                result.add(choice)
                exhausted = false
            } else {
                exhausted = true
            }
            classIndex++
        }
        return PurchaseResult(remaining, result)
    }
}
