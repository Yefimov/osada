package org.osada.rules

import org.osada.GameHolder
import org.osada.TerrainType
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.isWorking

/**
 * *"Units can resupply if in/adjacent to cities or ports"* — `supply_ex` mode 2's test, and the
 * one *"Naval units can always supply in Ports"* names.
 *
 * A separate file rather than a twelfth function on [DepotSupply], which is at detekt's budget.
 *
 * `Hex.isWorking` rather than a bare terrain comparison, for the same reason
 * `MovementRules.hasAirfield` uses it: OG's shelled facility is *"unusable until Repaired"*, and a
 * city reduced to rubble by a barrage cannot be a supply source while it is rubble
 * (`docs/og-fidelity-plan.md` §R.7).
 */
internal object SupplyFacilities {
    /** Whether [unit] stands in, or beside, a working city or port. */
    fun inOrBesideCityOrPort(
        map: GameMap,
        unit: GameUnit,
    ): Boolean {
        val pos = unit.getPos() ?: return false
        // OG's "ports do not supply hexes", authored by 84 scenarios and read by nothing until
        // 2026-08-28 (`docs/og-fidelity-plan.md` §AD). Null -- an unreadable source -- stays
        // permitted, as every authored switch does.
        val portsSupply = GameHolder.instance?.scenario?.portsNoSupply != true
        val cells = listOf(pos) + HexGeometry.getAdjacent(pos.row, pos.col)
        return cells.any { cell ->
            val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col)
            hex != null &&
                (
                    hex.isWorking(TerrainType.CITY.value) ||
                        (portsSupply && hex.isWorking(TerrainType.PORT.value))
                )
        }
    }
}
