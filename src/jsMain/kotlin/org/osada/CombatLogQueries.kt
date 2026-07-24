package org.osada

import org.osada.model.Cell
import org.osada.model.GameUnit

fun CombatLog.addResupply(
    unit: GameUnit,
    source: String? = null,
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

private fun newUnitEndTurnInfo(): dynamic {
    val o = js("{}")
    o.eqid = 0
    o.fuel = -1
    o.ammo = -1
    o.side = -1
    o.isCore = false
    o.source = null
    return o
}
