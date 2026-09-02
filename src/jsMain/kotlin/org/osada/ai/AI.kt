package org.osada.ai

import org.osada.ActionType
import org.osada.GameHolder
import org.osada.UnitClass
import org.osada.model.Cell
import org.osada.model.ExtendedCell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Player
import org.osada.model.buyUnit
import org.osada.model.deployPlayerUnit
import org.osada.model.getDeployHexes
import org.osada.model.getUnits
import org.osada.rules.AiOrders
import org.osada.rules.GameRules
import org.osada.rules.canResupply
import org.osada.rules.getMoveRange
import org.osada.rules.isAir
import org.osada.rules.isSea
import org.osada.rules.unitUsesFuel
import org.osada.ui.handleReinforcementDeployment
import kotlin.js.json

class AI(
    private val player: Player,
    private val map: GameMap,
) {
    private val actions: MutableList<dynamic> = mutableListOf()

    private var availablePrestige: Int = player.prestige
    private val reservedCells: MutableList<Cell> = mutableListOf()
    private val enemyCells: MutableList<Cell> = mutableListOf()
    private val enemyStates: MutableMap<Int, EnemyUnit> = mutableMapOf()
    private var ownVictoryHexes: MutableList<Cell> = mutableListOf()
    private var enemyVictoryHexes: MutableList<Cell> = mutableListOf()
    private lateinit var evalState: AIEvaluationState

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
        evalState = AIEvaluationState(map, player, reservedCells, enemyCells, enemyStates, ownVictoryHexes)

        val unitQueue = buildUnitQueue(allUnits)
        while (unitQueue.isNotEmpty()) {
            val current = unitQueue[0]
            considerReinforce(current)
            considerResupply(current)
            considerMove(current)
            considerAttack(current)
            val unitExhausted = current.noReinforce && current.noResupply && current.noAttack && current.noMove
            if (unitExhausted) unitQueue.removeAt(0)
        }
    }

    private fun buildUnitQueue(allUnits: Array<GameUnit>): MutableList<AIUnit> {
        val unitQueue: MutableList<AIUnit> = mutableListOf()
        allUnits.filter { it.player?.id == player.id }.forEach { unit ->
            val aiUnit = AIUnit(unit)
            if (isPriorityUnit(unit)) unitQueue.add(0, aiUnit) else unitQueue.add(aiUnit)
        }
        return unitQueue
    }

    /** Artillery, fortifications, and non-transport air/sea units act before everything else. */
    private fun isPriorityUnit(unit: GameUnit): Boolean {
        val uclass = unit.unitData().uclass
        return uclass == UnitClass.ARTILLERY.value ||
            uclass == UnitClass.FORTIFICATION.value ||
            (GameRules.isAir(unit) && uclass != UnitClass.AIR_TRANSPORT.value) ||
            (GameRules.isSea(unit) && uclass != UnitClass.NAVAL_TRANSPORT.value)
    }

    /** Buys reinforcements with surplus prestige and deploys undeployed core units. */
    private fun purchaseAndDeployPhase() {
        val prestige = player.prestige
        if (prestige > PRESTIGE_RESERVE) {
            val buyPrestige = prestige - PRESTIGE_RESERVE
            var saveAmount = (buyPrestige * SAVE_RATIO).toInt()
            if (saveAmount < MIN_SAVE_AMOUNT) saveAmount = 0
            val expensiveBudget = buyPrestige - saveAmount
            AIPurchasing.selectUnits(player, saveAmount, EXPENSIVE_CLASSES).units.forEach { player.buyUnit(it, -1) }
            AIPurchasing.selectUnits(player, expensiveBudget, CHEAP_CLASSES).units.forEach { player.buyUnit(it, -1) }
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

    private fun addAction(
        type: ActionType,
        params: Array<dynamic>,
    ) {
        actions.add(json(Pair("type", type.value), Pair("param", params)))
    }

    private fun considerReinforce(aiUnit: AIUnit) {
        val unit = aiUnit.unit
        if (AIReinforcement.cannotReinforce(aiUnit, unit, map, availablePrestige)) {
            aiUnit.noReinforce = true
            return
        }
        val outcome = AIReinforcement.attemptReinforce(aiUnit, unit, map, availablePrestige, ::addAction)
        availablePrestige = outcome.availablePrestige
        if (!outcome.handled && aiUnit.noAttack && aiUnit.noMove) aiUnit.noReinforce = true
    }

    private fun considerResupply(aiUnit: AIUnit) {
        val unit = aiUnit.unit
        val cannotResupply =
            !GameRules.canResupply(map, unit) ||
                aiUnit.didResupplyReinforce ||
                aiUnit.didAttack ||
                aiUnit.didMove
        if (cannotResupply) {
            aiUnit.noResupply = true
            return
        }
        val needsResupply =
            (aiUnit.noAttack && aiUnit.noMove) ||
                unit.ammo < AMMO_THRESHOLD ||
                (GameRules.unitUsesFuel(unit) && unit.fuel < FUEL_THRESHOLD)
        if (needsResupply) {
            addAction(ActionType.RESUPPLY, arrayOf(unit))
            aiUnit.didResupplyReinforce = true
        } else if (aiUnit.noAttack && aiUnit.noMove) {
            aiUnit.noResupply = true
        }
    }

    private fun considerMove(aiUnit: AIUnit) {
        val unit = aiUnit.unit
        // The scenario author's own orders: Anchored, and Hold-until-turn-N. They constrain THIS
        // planner and nothing else -- a human commanding the same formation keeps the Move button
        // (`rules/AiOrders`).
        val orderedToStay = !AiOrders.mayMove(unit, map.turn)
        val moveRange =
            if (aiUnit.didMove || aiUnit.didResupplyReinforce || orderedToStay) {
                emptyArray<ExtendedCell>()
            } else {
                GameRules.getMoveRange(map, unit)
            }
        if (moveRange.isEmpty()) {
            aiUnit.noMove = true
            return
        }
        val currentPos = unit.getPos() ?: return
        val moved =
            AITurnPlanning.tryMove(
                aiUnit,
                unit,
                currentPos,
                moveRange,
                evalState,
                reservedCells,
                enemyCells,
                ::addAction,
            )
        if (!moved) aiUnit.noMove = true
    }

    private fun considerAttack(aiUnit: AIUnit) {
        val unit = aiUnit.unit
        if (unit.hasFired || aiUnit.didAttack || aiUnit.didResupplyReinforce) {
            aiUnit.noAttack = true
            return
        }
        val attacked = AITurnPlanning.tryAttack(aiUnit, unit, map, evalState, enemyStates, ::addAction)
        if (!attacked && (aiUnit.noMove || aiUnit.didMove)) aiUnit.noAttack = true
    }
}
