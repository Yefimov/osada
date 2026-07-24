package org.osada.ui

import org.osada.model.GameMap
import org.osada.model.GameUnit

/**
 * Transient "Sleep" flag for units the player wants to skip in the ready-unit navigator this
 * turn without ending the turn early. Scoped to "turn:player" rather than persisted on the unit
 * or in the save: the set is simply dropped whenever the turn or the current player changes, so
 * every turn starts with nothing asleep — no GameUnit field, no save-format change.
 */
internal object TurnSleep {
    private val ids = mutableSetOf<Int>()
    private var scope: String = ""

    private fun sync(map: GameMap) {
        val key = "${map.turn}:${map.currentPlayer?.id}"
        if (key != scope) {
            scope = key
            ids.clear()
        }
    }

    fun isAsleep(
        map: GameMap,
        unit: GameUnit,
    ): Boolean {
        sync(map)
        return unit.id in ids
    }

    fun toggle(
        map: GameMap,
        unit: GameUnit,
    ) {
        sync(map)
        if (!ids.add(unit.id)) ids.remove(unit.id)
    }

    fun reset() {
        ids.clear()
        scope = ""
    }
}
