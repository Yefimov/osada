package org.osada

import org.osada.model.Cell
import org.osada.model.mountUnit
import org.osada.model.reinforceUnit
import org.osada.model.resupplyUnit
import org.osada.model.setMoveRange
import org.osada.model.unmountUnit
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.multiplayer.command.EndPlayerTurn
import org.osada.rules.GameRules
import org.osada.rules.isInAttackRange
import org.osada.ui.UIBuilder
import org.osada.ui.message
import org.osada.ui.showAIStatus
import org.osada.ui.showGameToolTip
import org.osada.ui.uiEndTurnInfo
import org.osada.ui.uiSetUnitOnViewPort
import org.osada.ui.uiUnitAttack
import org.osada.ui.uiUnitMove

// Consume the pending campaign transition exactly once. Without clearing these,
// the 1s processTurn interval reloads the same scenario every tick (an infinite
// loop), which is especially visible when the briefing message is empty and never
// resets uiMessageClicked back to false.
internal fun Game.startPendingScenarioTransition() {
    val intro = nextScenarioData.intro as? String
    val scenarioFile = nextScenarioData.scenario as String
    pendingScenarioBriefing = resolveScenarioBriefing(nextScenarioData, scenarioFile)
    continueCampaignFlag = false
    nextScenarioData = null
    console.log("[OSADA] processTurn continueCampaign -> newScenario", scenarioFile)
    newScenario(scenarioFile, intro)
}

internal fun Game.continueCurrentTurn() {
    val current = scenario?.map?.currentPlayer ?: return
    // In a room only the host plays the AI: a peer receives the AI's turn as snapshots.
    if (!OsadaMultiplayer.isAuthority()) return
    if (current.type == PlayerType.AI_LOCAL || current.type == PlayerType.AI_SCRIPTED) {
        if (!waitUIAnimation && uiMessageClicked) {
            processAIActions()
        }
    }
}

fun Game.processAIActions() {
    val handler = scenario?.map?.currentPlayer?.handler ?: return
    val action = handler.getAction()
    if (action == null) {
        UIBuilder.showAIStatus(false)
        waitUIAnimation = true
        // In a room the AI's turn ends as an order, so its end reaches the peer in a snapshot.
        val player = scenario?.map?.currentPlayer
        if (OsadaMultiplayer.active && player != null) {
            // Refused only while paused: drop the wait so the turn loop asks again later.
            if (!OsadaMultiplayer.submitOrder(EndPlayerTurn(player.id))) waitUIAnimation = false
        } else {
            endTurn()
        }
        val showEndTurnInfo =
            forCurrentScenario {
                if (!gameEnded) ui?.uiEndTurnInfo()
            }
        js("setTimeout")(showEndTurnInfo, Game.END_TURN_INFO_DELAY_MS)
        return
    }
    executeAction(action)
}

/**
 * A planned AI attack. `hasFired` is re-read because the move the plan came after can have ended in
 * a surprise engagement (`GameMap.surpriseTarget`), which was this unit's attack for the turn --
 * the plan predates it.
 */
private fun Game.executeAttackAction(
    attacker: org.osada.model.GameUnit,
    defender: org.osada.model.GameUnit,
) {
    waitUIAnimation = true
    if (!attacker.hasFired && GameRules.isInAttackRange(attacker, defender)) {
        ui?.uiUnitAttack(attacker, defender)
    } else {
        waitUIAnimation = false
    }
}

@Suppress("UNCHECKED_CAST", "LongMethod")
private fun Game.executeAction(action: dynamic) {
    val param = action.param as? Array<dynamic> ?: return
    when (action.type as Int) {
        ActionType.MOVE.value -> {
            val unit = param[0] as org.osada.model.GameUnit
            val cell = param[1] as Cell
            val nm =
                org.osada.model.Equipment
                    .getEquipment(unit.eqid)
                    ?.name ?: ""
            console.log("[OSADA] AI move ${unit.id}($nm) -> ${cell.row},${cell.col}")
            scenario?.map?.setMoveRange(unit)
            waitUIAnimation = true
            ui?.uiUnitMove(unit, cell.row, cell.col)
        }

        ActionType.ATTACK.value -> {
            executeAttackAction(param[0] as org.osada.model.GameUnit, param[1] as org.osada.model.GameUnit)
        }

        ActionType.RESUPPLY.value -> {
            val unit = param[0] as org.osada.model.GameUnit
            scenario?.map?.resupplyUnit(unit)
        }

        ActionType.REINFORCE.value -> {
            val unit = param[0] as org.osada.model.GameUnit
            scenario?.map?.reinforceUnit(unit, false)
        }

        ActionType.MOUNT.value -> scenario?.map?.mountUnit(param[0] as org.osada.model.GameUnit)
        ActionType.UMOUNT.value -> scenario?.map?.unmountUnit(param[0] as org.osada.model.GameUnit)
        ActionType.SELECT.value -> {
            val unit = param[0] as org.osada.model.GameUnit
            ui?.uiSetUnitOnViewPort(unit)
            ui?.uiUnitSelect(unit)
        }

        ActionType.MESSAGE.value -> {
            val message = param[0] as String
            val cell = param[1] as Cell
            waitUIAnimation = true
            ui?.showGameToolTip(message, cell.row, cell.col)
            ui?.uiSetCellOnViewPort(cell)
        }

        ActionType.MODAL_MESSAGE.value -> {
            val title = param[0] as String
            val body = param[1] as String
            waitUIAnimation = true
            UIBuilder.message(
                title,
                body,
                callback =
                    forCurrentScenario {
                        uiMessageClicked = true
                        uiAnimationFinished()
                    },
            )
        }

        ActionType.VIEWPORT.value -> {
            ui?.uiSetCellOnViewPort(param[0] as Cell)
        }
    }
}

/**
 * Called by the UI when an animation (move or attack) finishes.
 * Resumes the AI turn loop.
 */
fun Game.uiAnimationFinished() {
    waitUIAnimation = false
    processTurn()
}
