package org.osada.model

import org.osada.GameHolder
import org.osada.TerrainType
import org.osada.rules.CarrierDeploy
import org.osada.rules.GameRules
import org.osada.rules.HexGeometry
import org.osada.rules.isAir

// Where a player may place reserve units. Split from GameMapGrid.kt (function-count limits) and
// also a distinct concern: the rest of that file answers questions about the grid as authored and
// cached once at load, while a deploy zone changes during the battle.

/**
 * Every hex [side] may place a reserve unit on: the author's own deploy/supply zone, **plus every
 * owned supply hex and the ring around it**.
 *
 * The second half was missing, and it made captured ports useless. [ownsSupplyHex] already lets a
 * player BUY once a port changes hands ([hasPurchaseAnchor]) — but placement read only
 * `hex.isDeployment`, an attribute fixed at scenario load, so N_Kiel (0 authored deploy hexes,
 * 3 enemy-held ports) let the player buy units with nowhere on the map to put them. Reported
 * 2026-07-31: "I've captured hexes (27,12) and (29,10) but no deployment hexes appeared near them."
 *
 * The ring is OG's measured behaviour, recorded on [ownsSupplyHex]: in N_Kiel both Kieler Hafen
 * ports open deployment on their adjacent hexes once genuinely owned — (27,12) →
 * (27,13)/(28,13)/(29,12), and (29,11) → (30,11). Only the *zone* is widened here; which units may
 * actually stand on a given hex in it stays with [canDeployOnTerrain] and the occupancy check,
 * exactly as for an authored zone.
 *
 * Cells are encoded `row * cols + col` so the result is a cheap `Set<Int>` the per-hex renderer can
 * probe without rebuilding anything. Cached per side and dropped by `GameMap.invalidateDeployZones`
 * on every ownership change — capturing a port has to open its zone the same turn.
 */
internal fun GameMap.deployZone(side: Int): Set<Int> =
    deployZoneCache.getOrPut(side) {
        val grid = map ?: return@getOrPut emptySet()
        val zone = mutableSetOf<Int>()
        grid.forEachIndexed { r, row ->
            row.forEachIndexed { c, hex ->
                if (hex.isDeployment != -1 && getPlayer(hex.isDeployment).side == side) {
                    zone += r * cols + c
                }
                if (isOwnedSupplyHex(hex, side)) {
                    zone += r * cols + c
                    HexGeometry.getAdjacent(r, c).forEach { cell ->
                        if (cell.row in 0 until rows && cell.col in 0 until cols) {
                            zone += cell.row * cols + cell.col
                        }
                    }
                }
            }
        }
        zone
    }

/** `hex.owner` is a PLAYER id, not a side, and `getPlayer(-1)` falls back to player 0 — so an
 *  unowned port must be excluded explicitly or it silently reads as "belongs to player 0".
 *
 *  A port shelled into rubble is not a supply hex until it is repaired ([Hex.isWorking]): OG calls
 *  a wrecked facility *"unusable"*, and a harbour nobody can unload at cannot open a deploy zone. */
private fun GameMap.isOwnedSupplyHex(
    hex: Hex,
    side: Int,
): Boolean {
    // OG's "ports do not deploy naval units", authored by 48 scenarios and read by nothing until
    // 2026-08-28 (`docs/og-fidelity-plan.md` §AD). A port that does not deploy also opens no ring,
    // which is the whole of what this function decides. Null (unreadable source) stays permitted.
    if (GameHolder.instance?.scenario?.portsNoNavalDeploy == true) return false
    return hex.isWorking(TerrainType.PORT.value) && hex.owner != -1 && getPlayer(hex.owner).side == side
}

fun GameMap.getDeployHexes(side: Int): Array<Cell> = deployZone(side).map { Cell(it / cols, it % cols) }.toTypedArray()

/**
 * The two places a reserve may be placed OUTSIDE its side's deploy zone.
 *
 * Shared by the deploy click gate and the deploy highlight, because when only the click gate knew a
 * rule the overlay lit hexes that then silently refused the unit — the defect [deployZone]'s own
 * header records, reported on `Falciu 1`.
 *
 *  - a FRIENDLY airfield, for an aircraft. `hex.owner` is a player id and -1 means unowned, and
 *    `getPlayer(-1)` falls back to player 0, so -1 must be excluded explicitly or an unowned
 *    airfield silently reads as player 0's.
 *  - a friendly CARRIER, for an aircraft carrying OG's `Carrier Deploy` (`rules/CarrierDeploy`).
 */
fun GameMap.isOutOfZoneDeployTarget(
    unit: GameUnit,
    row: Int,
    col: Int,
): Boolean {
    val hex = map?.getOrNull(row)?.getOrNull(col) ?: return false
    val side = currentPlayer?.side
    val friendlyAirfield =
        GameRules.isAir(unit) &&
            hex.terrain == TerrainType.AIRFIELD.value &&
            hex.owner != -1 &&
            getPlayer(hex.owner).side == side
    return friendlyAirfield || CarrierDeploy.permits(this, unit, row, col)
}

/** Whether [side] may place a reserve unit on ([row], [col]) — see [deployZone]. Shared by the
 *  deploy click gate, the deploy highlight and the AI's own placement pass, so all three agree on
 *  the zone instead of each testing `hex.isDeployment` for itself. */
fun GameMap.isInDeployZone(
    side: Int,
    row: Int,
    col: Int,
): Boolean = (row * cols + col) in deployZone(side)

/**
 * Every airfield [side] holds, as deploy cells. An airfield is friendly by its owner's side, with
 * -1 (unowned) excluded explicitly for the reason [isOutOfZoneDeployTarget] gives.
 */
internal fun GameMap.friendlyAirfields(side: Int): List<Cell> =
    friendlyAirfieldCache.getOrPut(side) {
        val grid = map ?: return@getOrPut emptyList()
        val cells = mutableListOf<Cell>()
        grid.forEachIndexed { r, row ->
            row.forEachIndexed { c, hex ->
                if (hex.terrain == TerrainType.AIRFIELD.value && hex.owner != -1 && getPlayer(hex.owner).side == side) {
                    cells += Cell(r, c)
                }
            }
        }
        cells
    }

/**
 * Whether an aircraft may be placed on ([row], [col]) at all: **only on a friendly airfield, or a
 * carrier where `Carrier Deploy` permits** ([isOutOfZoneDeployTarget]) -- never on an ordinary hex
 * of the deploy zone. OG manual p.12: *"Deployment of air units is different, as they can only be
 * deployed at airfields; some aircraft can be deployed from other units, like carriers."* A side
 * with no airfield therefore deploys no aircraft until it takes or builds one, which OG's own
 * scenario text confirms (*"You don't have an airport and won't be able to deploy planes till
 * you've captured a beachhead and built a landing strip"*). Owner decision 2026-09-29.
 *
 * Read by the one placement authority (`UnitDeployOperations.deployPlayerUnit`, so the AI and a
 * multiplayer host are bound too) and by the deploy highlight and click gate, so a hex is never lit
 * for a unit that would then be refused. Non-aircraft always pass.
 */
internal fun GameMap.aircraftMayDeployOn(
    unit: GameUnit,
    row: Int,
    col: Int,
): Boolean = !GameRules.isAir(unit) || isOutOfZoneDeployTarget(unit, row, col)
