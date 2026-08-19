package org.osada

import org.osada.model.Cell
import org.osada.model.GameUnit

/** [source] is a stable [org.osada.rules.SupplySource] token (see
 *  `GameText.supplyContextToken`), never display text: the turn report is re-rendered after a
 *  live language change, so a pre-localized English label would freeze into the log. */
fun CombatLog.addResupply(
    unit: GameUnit,
    source: String? = null,
    adjacentEnemies: Int = 0,
) {
    val player = unit.player ?: return
    val id = unit.id
    var entry = log.resupply[id]
    if (entry == null || entry == undefined) {
        entry = newUnitEndTurnInfo()
        log.resupply[id] = entry
    }
    entry.eqid = unit.eqid
    entry.ammo = unit.ammo
    entry.fuel = unit.fuel
    entry.isCore = unit.isCore
    entry.side = player.side
    entry.source = source
    entry.sourceAdjacentEnemies = adjacentEnemies
}

/** [prestige] is the prestige ACTUALLY awarded for this capture, not the `objectiveCapture`
 *  constant: a Liberator doubles it, and a hex that is both flagged and a victory hex contributes
 *  twice. The Turn Report used to print the constant, so those cases reported the wrong number. */
fun CombatLog.addObjectiveCapture(
    cell: Cell,
    side: Int,
    prestige: Int = 0,
) {
    val entry = js("{}")
    entry.pos = Cell(cell.row, cell.col)
    entry.side = side
    entry.prestige = prestige
    pushTo(log.objectives, entry)
}

/** A unit lost to SURRENDER (forced retreat with nowhere legal to go). [side] is the CAPTOR's side,
 *  matching the objectives log, so the Turn Report shows it to the player who took the unit. */
fun CombatLog.addSurrender(
    unit: GameUnit,
    cell: Cell,
    side: Int,
    prestige: Int,
) {
    val entry = js("{}")
    entry.eqid = unit.eqid
    entry.pos = Cell(cell.row, cell.col)
    entry.side = side
    entry.prestige = prestige
    entry.strength = unit.hits
    pushTo(log.surrenders, entry)
}

/**
 * A formation lost between turns rather than in combat. [reason] is a stable token (today only
 * `out_of_fuel`), never display text, for the same reason [addResupply]'s `source` is: the Turn
 * Report is re-rendered after a live language change and a pre-localized label would freeze in.
 *
 * [side] is the OWNER's side, unlike [addSurrender]'s captor side -- nobody took this unit, and the
 * player who has to plan around the loss is the one who lost it.
 */
fun CombatLog.addAttritionLoss(
    unit: GameUnit,
    cell: Cell?,
    reason: String,
) {
    val entry = js("{}")
    entry.eqid = unit.eqid
    entry.id = unit.id
    entry.pos = if (cell == null) null else Cell(cell.row, cell.col)
    entry.side = unit.player?.side ?: -1
    entry.isCore = unit.isCore
    entry.reason = reason
    pushTo(log.attrition, entry)
}

private fun newUnitEndTurnInfo(): dynamic {
    val o = js("{}")
    o.eqid = 0
    o.fuel = -1
    o.ammo = -1
    o.side = -1
    o.isCore = false
    o.source = null
    o.sourceAdjacentEnemies = 0
    return o
}
