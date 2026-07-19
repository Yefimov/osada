package org.osada

import org.osada.model.Cell
import org.osada.model.GameUnit

fun CombatLog.addResupply(unit: GameUnit) {
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
}

fun CombatLog.addObjectiveCapture(
    cell: Cell,
    side: Int,
) {
    val entry = js("{}")
    entry.pos = Cell(cell.row, cell.col)
    entry.side = side
    pushTo(log.objectives, entry)
}

private fun newUnitEndTurnInfo(): dynamic {
    val o = js("{}")
    o.eqid = 0
    o.fuel = -1
    o.ammo = -1
    o.side = -1
    o.isCore = false
    return o
}
