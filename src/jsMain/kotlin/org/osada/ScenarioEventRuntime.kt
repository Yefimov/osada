package org.osada

import org.osada.campaign.CampaignNarrative
import org.osada.model.Cell
import org.osada.model.Equipment
import org.osada.model.GameUnit
import org.osada.model.deployReinforcement
import org.osada.model.ensureFormationIds
import org.osada.model.getUnits
import org.osada.model.updateUnitList
import org.osada.rules.HexGeometry
import org.osada.scenario.Scenario
import org.osada.scenario.ScenarioEvent
import org.osada.scenario.ScenarioEventGate
import org.osada.scenario.ScenarioEventTrigger
import org.osada.scenario.ScenarioEventTriggerKind
import org.osada.scenario.eventById
import org.osada.ui.HudLog
import org.osada.ui.showGameToolTip

/*
 * Evaluation and application of authored [ScenarioEvent]s. See ScenarioEvent.kt for the model and
 * the rules this file implements.
 *
 * Called from exactly three places, all of which are moments where the world may have changed in a
 * way an event could care about:
 *
 *   - `UI.releaseToBattle`               - the true mission start, after the campaign briefing has
 *                                          committed its choices and BEFORE the restart checkpoint
 *                                          is captured, so "Restart mission" reproduces the same
 *                                          opening situation.
 *   - `AnimationOrchestrator.finishMoveAnimation` - a unit has arrived somewhere.
 *   - `Game.endTurn`                     - turn hand-off, as a safety net for any position change
 *                                          that did not come from a move animation.
 *
 * Evaluating more often than necessary is harmless: firing is once-only and guarded by
 * [ScenarioEvent.fired], which is set before any effect is applied.
 */

internal fun Game.evaluateScenarioEvents() {
    val current = scenario ?: return
    if (gameEnded || current.events.isEmpty()) return
    var anyFired = false
    // Single pass in authored order, so an event may satisfy a later one's `afterAny` /
    // `requiresUnitsFrom` within the same evaluation. Declare the cause before the consequence.
    current.events.forEach { event ->
        if (!event.fired && isReady(current, event)) {
            fireScenarioEvent(current, event)
            anyFired = true
        }
    }
    if (anyFired) {
        // A spawned eqid need not have been in the initial cacheImages() pass; without this the
        // new unit renders as a blank hex. Idempotent — already-loaded images are skipped.
        ui?.render?.cacheImages { ui?.render?.render() }
    }
}

private fun Game.isReady(
    current: Scenario,
    event: ScenarioEvent,
): Boolean = gateHolds(current, event.gate) && triggerHolds(current, event.trigger)

/**
 * Campaign flags are read through [CampaignNarrative]. Outside a campaign no flag is ever set, so
 * a `noneFlags` gate passes and an `allFlags` gate does not — a standalone launch of the same
 * scenario therefore gets the default branch without any extra authoring.
 */
private fun gateHolds(
    current: Scenario,
    gate: ScenarioEventGate,
): Boolean {
    val flags = CampaignNarrative.state
    return gate.allFlags.all { flags.hasFlag(it) } &&
        gate.noneFlags.none { flags.hasFlag(it) } &&
        (gate.afterAny.isEmpty() || gate.afterAny.any { current.eventById(it)?.fired == true }) &&
        (gate.requiresUnitsFrom.isEmpty() || gate.requiresUnitsFrom.any { hasLivingSpawn(current, it) })
}

private fun hasLivingSpawn(
    current: Scenario,
    sourceEventId: String,
): Boolean {
    val source = current.eventById(sourceEventId) ?: return false
    return current.map.getUnits().any { !it.destroyed && it.id in source.spawnedUnitIds }
}

private fun triggerHolds(
    current: Scenario,
    trigger: ScenarioEventTrigger,
): Boolean =
    when (trigger.kind) {
        ScenarioEventTriggerKind.START -> true
        ScenarioEventTriggerKind.PROXIMITY ->
            current.map.getUnits().any { unit -> trips(unit, trigger) }
    }

private fun trips(
    unit: GameUnit,
    trigger: ScenarioEventTrigger,
): Boolean {
    val pos = if (unit.destroyed) null else unit.getPos()
    return pos != null &&
        (trigger.side == -1 || unit.player?.side == trigger.side) &&
        (!trigger.combatOnly || isArmed(unit)) &&
        HexGeometry.distance(pos.row, pos.col, trigger.row, trigger.col) <= trigger.radius
}

/**
 * "Can this unit actually shoot?" — read off the equipment's own attack values rather than a unit
 * class list, so it stays correct for every efile. This is what keeps a supply cart from raising an
 * alarm, and — the case that actually matters — keeps the unarmed detainees an event spawns from
 * satisfying that same event's trigger.
 */
private fun isArmed(unit: GameUnit): Boolean {
    val data = Equipment.getEquipment(unit.eqid) ?: return false
    return data.softatk > 0 || data.hardatk > 0 || data.airatk > 0 || data.navalatk > 0
}

private fun Game.fireScenarioEvent(
    current: Scenario,
    event: ScenarioEvent,
) {
    // Set FIRST: applying effects can re-enter this file (rendering, unit list updates), and an
    // event that fires twice would duplicate its spawns.
    event.fired = true
    console.log("[OSADA] scenario event fired: ${event.id}")
    removeSourceUnits(current, event)
    placeSpawns(current, event)
    announce(current, event)
}

private fun removeSourceUnits(
    current: Scenario,
    event: ScenarioEvent,
) {
    if (event.removeFrom.isEmpty()) return
    val doomed = event.removeFrom.mapNotNull { current.eventById(it) }.flatMap { it.spawnedUnitIds }
    if (doomed.isEmpty()) return
    current.map
        .getUnits()
        .filter { it.id in doomed && !it.destroyed }
        .forEach { it.destroyed = true }
    // The sweep that actually takes them off their hexes and out of the unit list. Units removed
    // this way are authored as `nodossier`, so a conversion is never recorded as a casualty.
    current.map.updateUnitList()
}

private fun Game.placeSpawns(
    current: Scenario,
    event: ScenarioEvent,
) {
    event.spawns.forEach { spawn ->
        // A fresh instance per firing: the parsed unit is the authored template and must survive
        // a save/restore round-trip unmodified.
        val unit = GameUnit(spawn.unit.eqid).apply { copy(spawn.unit) }
        campaignPlayer
            ?.takeIf { it.id == unit.owner }
            ?.let { current.map.ensureFormationIds(it, listOf(unit)) }
        val pos = current.map.deployReinforcement(unit, spawn.row, spawn.col)
        if (pos == null) {
            console.warn("[OSADA] event '${event.id}' could not place eqid ${unit.eqid} near ${spawn.row},${spawn.col}")
        } else {
            event.spawnedUnitIds.add(unit.id)
        }
    }
}

/**
 * Concise, map-anchored presentation: a callout pinned to the hex the event is about, plus a
 * clickable LOG row. Deliberately NOT a modal — the campaign ceremony already owns the player's
 * full attention once per battle, and an event is a thing that happened on the map.
 *
 * The camera is only recentred for a START event, which fires before the player has moved anything.
 * A proximity event fires because one of their own units is standing within its radius, so the
 * anchor is already on screen and yanking the view would move the hex they are about to click.
 */
private fun Game.announce(
    current: Scenario,
    event: ScenarioEvent,
) {
    if (event.message.isBlank()) return
    val row = event.trigger.row
    val col = event.trigger.col
    if (event.trigger.kind == ScenarioEventTriggerKind.START) {
        current.map.map
            ?.getOrNull(row)
            ?.getOrNull(col)
            ?.let { ui?.uiSetCellOnViewPort(Cell(row, col)) }
    }
    ui?.showGameToolTip(event.message, row, col)
    HudLog.addAt(row, col, event.message)
}
