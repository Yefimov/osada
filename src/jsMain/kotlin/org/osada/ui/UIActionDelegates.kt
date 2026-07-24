package org.osada.ui

import org.osada.model.GameUnit

/** Animation/turn-flow/menu delegation for [UI], split out to keep its function count in bounds. */
fun UI.uiUnitMove(
    unit: GameUnit,
    row: Int,
    col: Int,
): Boolean = animationOrchestrator.uiUnitMove(unit, row, col)

fun UI.uiUnitAttack(
    attacker: GameUnit,
    defender: GameUnit,
): Boolean = animationOrchestrator.uiUnitAttack(attacker, defender)

fun UI.toggleUnitsAndEquipmentWindow(show: Boolean) = eqWindowController.toggleUnitsAndEquipmentWindow(show)

fun UI.handleReinforcementDeployment() = eqWindowController.handleReinforcementDeployment()

fun UI.mainMenuButton(id: String) = mainMenuButtonHandler.mainMenuButton(id)

/** Top-bar turn controls (ready-unit navigator + End Turn state); refreshed after actions. */
internal fun UI.updateTurnControls() = readyUnitNavigator.updateTurnControls()

fun UI.cycleReadyUnit(direction: Int) = readyUnitNavigator.cycleReadyUnit(direction)

fun UI.onEndTurnClick() = endTurnFlow.onEndTurnClick()

internal fun UI.isUnitAsleep(unit: GameUnit): Boolean = readyUnitNavigator.isUnitAsleep(unit)

internal fun UI.toggleUnitSleep(unit: GameUnit) = readyUnitNavigator.toggleUnitSleep(unit)

internal fun UI.hasAnyAction(unit: GameUnit): Boolean = readyUnitNavigator.hasAnyAction(unit)
