package org.osada.ai

import org.osada.ActionType
import org.osada.PlayerSide
import org.osada.PlayerType
import org.osada.model.GameUnit
import kotlin.js.json

// Extracted out of AIScripted (class-member split, so the class stays under detekt's
// TooManyFunctions budget) and out of buildTutorialActions (LongMethod split, so each turn's
// scripted steps live in their own cohesive function, grouped one turn per file to stay under
// detekt's per-file TooManyFunctions budget too). Both `unitAt` and `addAction` were private
// members of AIScripted; they're now internal so these extensions (here and in the
// AIScriptedTurn*.kt files) can reach them from another file.

internal fun AIScripted.unitAt(
    row: Int,
    col: Int,
): GameUnit? =
    map.map
        ?.getOrNull(row)
        ?.getOrNull(col)
        ?.getUnit(false)

internal fun AIScripted.addAction(
    type: ActionType,
    params: Array<dynamic>,
) {
    actions.add(json(Pair("type", type.value), Pair("param", params)))
}

internal fun AIScripted.buildTurn3Actions() {
    if (player.side == PlayerSide.AXIS.value) {
        var u = unitAt(row = 15, col = 10)
        select(u)
        message(
            "You can reinforce this unit casualties by clicking on the <span " +
                "class='smallButtonSubMenu'>#</span> button at the bottom of the screen. Reinforcements " +
                "costs prestige and the amount is reduced by enemy units adjacent to your unit. After " +
                "reinforce unit can no longer attack or move.",
            row = 15,
            col = 10,
        )
        reinforce(u)

        u = unitAt(row = 7, col = 15)
        select(u)
        message(
            "Let's move the rest of the units towards the objective, note how terrain influence movement " +
                "range. The longest move range is on road or clear terrain but it also depends on unit " +
                "movement type shown with symbol <span class='statsGlyph' style='float:none;'>~</span>",
            row = 7,
            col = 15,
        )
        mount(u)
        move(u, row = 14, col = 11)

        u = unitAt(row = 8, col = 14)
        mount(u)
        select(u)
        move(u, row = 14, col = 9)

        u = unitAt(row = 9, col = 14)
        mount(u)
        select(u)
        move(u, row = 14, col = 8)

        u = unitAt(row = 7, col = 9)
        mount(u)
        select(u)
        move(u, row = 11, col = 9)

        u = unitAt(row = 7, col = 13)
        mount(u)
        select(u)
        move(u, row = 13, col = 13)

        u = unitAt(row = 8, col = 15)
        select(u)
        move(u, row = 12, col = 12)
    }
}

internal fun AIScripted.buildDefaultTurnActions() {
    if (player.side == PlayerSide.AXIS.value) {
        player.handler = null
        player.type = PlayerType.HUMAN_LOCAL
    } else {
        player.handler = AI(player, map)
        player.type = PlayerType.AI_LOCAL
    }
}
