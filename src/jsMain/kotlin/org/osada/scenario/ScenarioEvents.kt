package org.osada.scenario

import org.osada.model.GameUnit

/*
 * [ScenarioEvent] collection operations for [Scenario], split out for the same reason
 * [ScenarioReinforcements] is: [Scenario] is already at the project's per-class function limit.
 */

/** Ids of the events that have already fired — the fact `ScenarioActionRule.EventFired` reads. */
internal fun Scenario.firedEventIds(): Set<String> = events.filter { it.fired }.mapTo(mutableSetOf()) { it.id }

internal fun Scenario.eventById(id: String): ScenarioEvent? = events.firstOrNull { it.id == id }

/**
 * Deep-copies [other]'s events, mirroring how [Scenario.copy] treats reinforcements: the units are
 * cloned so the copy cannot share (and then mutate) the source scenario's order of battle, while
 * [ScenarioEvent.fired] and the spawned-unit ids are carried across because they are progress, not
 * authored data.
 */
internal fun Scenario.copyEventsFrom(other: Scenario) {
    events.clear()
    other.events.forEach { source ->
        val copy =
            ScenarioEvent(
                id = source.id,
                trigger = source.trigger,
                gate = source.gate,
                message = source.message,
                spawns = source.spawns.map { it.copy(unit = GameUnit(it.unit.eqid).apply { copy(it.unit) }) },
                removeFrom = source.removeFrom.toList(),
            )
        copy.fired = source.fired
        copy.spawnedUnitIds.addAll(source.spawnedUnitIds)
        events.add(copy)
    }
}
