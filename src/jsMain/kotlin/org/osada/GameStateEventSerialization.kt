package org.osada

import org.osada.scenario.Scenario
import org.osada.scenario.ScenarioEvent
import org.osada.scenario.ScenarioEventGate
import org.osada.scenario.ScenarioEventSpawn
import org.osada.scenario.ScenarioEventTrigger
import org.osada.scenario.ScenarioEventTriggerKind
import kotlin.js.json

/*
 * Save/restore for authored scenario events.
 *
 * Whole definitions are written, not just "which ones have fired". The restore path never touches
 * [org.osada.scenario.ScenarioLoader] — it rebuilds the scenario from the save alone, exactly as it
 * already does for reinforcements — so a save that carried only progress would restore into a
 * scenario with no events left to fire. Definitions are authored data and never mutate, so the cost
 * is a few hundred bytes and the guarantee is that restore, mission restart and a fresh load all
 * behave identically.
 *
 * Additive and optional, like every other save extension here: a save with no `events` key restores
 * to an empty event list, and a scenario with no events writes an empty array.
 */

internal fun serializeScenarioEvents(events: List<ScenarioEvent>): dynamic {
    val arr = js("[]")
    events.forEach { arr.push(serializeScenarioEvent(it)) }
    return arr
}

private fun serializeScenarioEvent(event: ScenarioEvent): dynamic {
    val spawns = js("[]")
    event.spawns.forEach { spawn ->
        spawns.push(
            json(
                Pair("row", spawn.row),
                Pair("col", spawn.col),
                Pair("unit", GameStateSerializer.serializeUnit(spawn.unit)),
            ),
        )
    }
    return json(
        Pair("id", event.id),
        Pair("kind", event.trigger.kind.name),
        Pair("row", event.trigger.row),
        Pair("col", event.trigger.col),
        Pair("radius", event.trigger.radius),
        Pair("side", event.trigger.side),
        Pair("combatOnly", event.trigger.combatOnly),
        Pair("allFlags", event.gate.allFlags.toTypedArray()),
        Pair("noneFlags", event.gate.noneFlags.toTypedArray()),
        Pair("afterAny", event.gate.afterAny.toTypedArray()),
        Pair("requiresUnitsFrom", event.gate.requiresUnitsFrom.toTypedArray()),
        Pair("removeFrom", event.removeFrom.toTypedArray()),
        Pair("message", event.message),
        Pair("spawns", spawns),
        Pair("fired", event.fired),
        Pair("spawnedUnitIds", event.spawnedUnitIds.toTypedArray()),
    )
}

internal fun restoreScenarioEvents(
    scenario: Scenario,
    data: dynamic,
) {
    scenario.events.clear()
    if (data == null || !(js("Array.isArray(data)") as Boolean)) return
    for (i in 0 until (data.length as Int)) {
        deserializeScenarioEvent(data[i])?.let { scenario.events.add(it) }
    }
}

private fun deserializeScenarioEvent(raw: dynamic): ScenarioEvent? {
    val id = raw?.id as? String ?: return null
    val event =
        ScenarioEvent(
            id = id,
            trigger = deserializeTrigger(raw),
            gate =
                ScenarioEventGate(
                    allFlags = stringList(raw.allFlags),
                    noneFlags = stringList(raw.noneFlags),
                    afterAny = stringList(raw.afterAny),
                    requiresUnitsFrom = stringList(raw.requiresUnitsFrom),
                ),
            message = raw.message as? String ?: "",
            spawns = deserializeSpawns(raw.spawns),
            removeFrom = stringList(raw.removeFrom),
        )
    event.fired = raw.fired as? Boolean ?: false
    event.spawnedUnitIds.addAll(intList(raw.spawnedUnitIds))
    return event
}

private fun deserializeTrigger(raw: dynamic): ScenarioEventTrigger =
    ScenarioEventTrigger(
        kind =
            if (raw.kind as? String == ScenarioEventTriggerKind.START.name) {
                ScenarioEventTriggerKind.START
            } else {
                ScenarioEventTriggerKind.PROXIMITY
            },
        row = raw.row as? Int ?: 0,
        col = raw.col as? Int ?: 0,
        radius = raw.radius as? Int ?: 1,
        side = raw.side as? Int ?: -1,
        combatOnly = raw.combatOnly as? Boolean ?: false,
    )

private fun deserializeSpawns(raw: dynamic): List<ScenarioEventSpawn> {
    if (raw == null) return emptyList()
    val out = mutableListOf<ScenarioEventSpawn>()
    for (i in 0 until (raw.length as Int)) {
        val entry = raw[i] ?: continue
        out.add(
            ScenarioEventSpawn(
                row = entry.row as? Int ?: 0,
                col = entry.col as? Int ?: 0,
                unit = GameStateDeserializer.deserializeUnit(entry.unit),
            ),
        )
    }
    return out
}

private fun stringList(raw: dynamic): List<String> {
    if (raw == null) return emptyList()
    val out = mutableListOf<String>()
    for (i in 0 until (raw.length as Int)) (raw[i] as? String)?.let { out.add(it) }
    return out
}

private fun intList(raw: dynamic): List<Int> {
    if (raw == null) return emptyList()
    val out = mutableListOf<Int>()
    for (i in 0 until (raw.length as Int)) (raw[i] as? Int)?.let { out.add(it) }
    return out
}
