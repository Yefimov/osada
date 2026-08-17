package org.osada.model

import org.osada.CombatLog
import org.osada.GameHolder
import org.osada.PlayerType
import org.osada.addResupply
import org.osada.rules.GameRules
import org.osada.rules.SupplyContextRules
import org.osada.rules.getResupplyValue

/**
 * Hex-grid model and unit/player registry. Owns the grid state, selection ranges, and turn
 * sequencing, and delegates specialized behaviour to focused collaborators:
 * - [CombatApplication] — damage, capture, retreat
 * - [MoveExecutor] — path movement and undo
 * - [UnitMountOperations], [UnitDeployOperations] and [CoreUnitListOperations] —
 *   mount/embark/upgrade/deploy/supply lifecycle
 *
 * Split (Single Responsibility) into cohesive sibling files to stay within the project's
 * function-count limits: grid allocation/hex access ([GameMapGrid]), the unit/player registries
 * ([GameMapUnitRegistry]/[GameMapPlayerRegistry]), selection/range management
 * ([GameMapSelection]), and the pure one-line delegations to the collaborators above
 * ([GameMapCombatMoveDelegation]/[GameMapUnitOpsDelegation]/[GameMapDeployDelegation]). This
 * class keeps only turn sequencing and bulk copy/cleanup as members.
 */
@JsExport
@JsName("Map")
class GameMap {
    var rows: Int = 0
    var cols: Int = 0
    var isLastRowPartial: Boolean = false
    var isLastColPartial: Boolean = false
    var name: String = ""
    var terrainImage: String = ""
    var map: Array<Array<Hex>>? = null
    var victoryTurns: MutableList<Int> = mutableListOf()
    var turn: Int = 1
    var maxTurns: Int = 1
    var currentUnit: GameUnit? = null
    var sidesVictoryHexes: MutableList<MutableList<Cell>> = mutableListOf(mutableListOf(), mutableListOf())
    var currentPlayer: Player? = null

    internal val units: MutableList<GameUnit> = mutableListOf()
    internal val players: MutableList<Player> = mutableListOf()
    internal var nextUnitId: Int = 0
    internal val unitImages: MutableSet<Int> = mutableSetOf()
    internal val currentMoveRange: MutableList<Cell> = mutableListOf()
    internal val currentAttackRange: MutableList<Cell> = mutableListOf()
    internal val undoState = UndoState()

    internal val combatApplication: CombatApplication by lazy { CombatApplication(this) }
    internal val moveExecutor: MoveExecutor by lazy { MoveExecutor(this) }
    internal val unitMountOperations: UnitMountOperations by lazy { UnitMountOperations(this) }
    internal val unitDeployOperations: UnitDeployOperations by lazy { UnitDeployOperations(this) }
    internal val coreUnitListOperations: CoreUnitListOperations by lazy { CoreUnitListOperations(this) }

    internal var hasRailDataCache: Boolean? = null
    internal var hasWaterAccessCache: Boolean? = null
    internal var hasOpenWaterAccessCache: Boolean? = null

    /** Per-side deploy zones, cached because the renderer asks per hex per frame. Unlike the three
     *  caches above this one is NOT load-time-constant: capturing a port opens a new zone
     *  mid-scenario, so [invalidateDeployZones] drops it on every ownership change. */
    internal var deployZoneCache: MutableMap<Int, Set<Int>> = mutableMapOf()

    internal fun invalidateDeployZones() = deployZoneCache.clear()

    fun endTurn() {
        delMoveSel()
        delAttackSel()
        delCurrentUnit()
        undoState.clear()
        currentPlayer?.endTurn(turn)
        val currentIndex = players.indexOf(currentPlayer)
        currentPlayer = if (currentIndex + 1 < players.size) players[currentIndex + 1] else players[0]
        if (currentPlayer?.id == 0) {
            turn++
            units.forEach { unit ->
                if (unit.isMounted) unmountUnitHandler(unit)
                val supply = GameRules.getResupplyValue(this, unit, true)
                val needsAmmoOrFuel = supply.ammo > 0 || supply.fuel > 0
                val needsTransportSupply = supply.transportAmmo > 0 || supply.transportFuel > 0
                if (needsAmmoOrFuel || needsTransportSupply) {
                    unit.resupply(supply)
                    val context = SupplyContextRules.getSupplyContext(this, unit)
                    CombatLog.addResupply(unit, SupplyContextRules.logToken(context), context.adjacentEnemies)
                }
                unit.unitEndTurn(GameHolder.instance?.spotSide ?: 0)
            }
        }
        if (currentPlayer?.type == PlayerType.AI_LOCAL || currentPlayer?.type == PlayerType.AI_SCRIPTED) {
            currentPlayer?.handler?.buildActions()
        }
    }

    fun copy(other: GameMap) {
        rows = other.rows
        cols = other.cols
        terrainImage = other.terrainImage
        name = other.name
        turn = other.turn
        maxTurns = other.maxTurns
        allocMap()
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val otherHex = other.map?.getOrNull(r)?.getOrNull(c) ?: continue
                val hex = map!![r][c]
                hex.copy(otherHex)
                otherHex.unit?.let { unit ->
                    val copy = GameUnit(unit.eqid).apply { copy(unit) }
                    hex.setUnit(copy)
                }
                otherHex.airunit?.let { airunit ->
                    val copy = GameUnit(airunit.eqid).apply { copy(airunit) }
                    hex.setUnit(copy)
                }
                setHex(r, c)
            }
        }
        sidesVictoryHexes = mutableListOf(mutableListOf(), mutableListOf())
        other.sidesVictoryHexes[0].forEach { sidesVictoryHexes[0].add(Cell(it.row, it.col)) }
        other.sidesVictoryHexes[1].forEach { sidesVictoryHexes[1].add(Cell(it.row, it.col)) }
        other.victoryTurns.forEach { victoryTurns.add(it) }
    }

    fun cleanup() {
        map?.forEach { row -> row.forEach { hex -> hex.cleanup() } }
        map = null
    }
}
