package org.osada.ai

import org.osada.ActionType
import org.osada.PlayerType
import org.osada.model.GameUnit
import kotlin.js.json

internal fun AIScripted.unitAt(
    row: Int,
    col: Int,
): GameUnit? =
    map.map
        ?.getOrNull(row)
        ?.getOrNull(col)
        ?.getUnit(false)

internal fun AIScripted.unitByEqid(
    eqid: Int,
    owner: Int,
): GameUnit? =
    map.units.firstOrNull { unit ->
        unit.eqid == eqid && unit.owner == owner && !unit.destroyed
    }

internal fun AIScripted.addAction(
    type: ActionType,
    params: Array<dynamic>,
) {
    actions.add(json(Pair("type", type.value), Pair("param", params)))
}

internal fun AIScripted.buildDefaultTurnActions() {
    if (player.side == SOVIET_TUTORIAL_SIDE) {
        player.handler = null
        player.type = PlayerType.HUMAN_LOCAL
    } else {
        player.handler = AI(player, map)
        player.type = PlayerType.AI_LOCAL
    }
}
