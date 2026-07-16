package org.osada.model

import org.osada.*
import org.osada.rules.GameRules
import kotlin.js.JsExport
import kotlin.js.JsName

/**
 * Hex-grid model and unit/player registry. Owns the grid state, selection ranges, and turn
 * sequencing, and delegates specialized behaviour to focused collaborators:
 * - [CombatApplication] — damage, capture, retreat
 * - [MoveExecutor] — path movement and undo
 * - [UnitOperations] — mount/embark/upgrade/deploy/supply lifecycle
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

    private val combatApplication: CombatApplication by lazy { CombatApplication(this) }
    private val moveExecutor: MoveExecutor by lazy { MoveExecutor(this) }
    private val unitOperations: UnitOperations by lazy { UnitOperations(this) }

    // ---- Grid allocation & hex access ----

    fun allocMap() {
        map = Array(rows) { r -> Array(cols) { c -> Hex(r, c) } }
        _hasRailData = null
        _hasWaterAccess = null
        _hasOpenWaterAccess = null
    }

    private var _hasRailData: Boolean? = null

    /** Whether this map's grid carries ANY rail data. Computed once and cached on first access
     *  (the grid's rail is only ever populated at load time, never mutated mid-game) — gates
     *  MovementRules' strict "trains only move on rail" enforcement: a scenario never re-patched
     *  with rail= attributes (see tools/og-import/add_rails.py) has none, so trains there fall
     *  back to today's (pre-existing, unrestricted) behaviour rather than becoming immobile. */
    fun hasRailData(): Boolean {
        _hasRailData?.let { return it }
        val found = map?.any { row -> row.any { it.rail > RoadType.NONE.value } } ?: false
        _hasRailData = found
        return found
    }

    private var _hasWaterAccess: Boolean? = null

    /** Whether this map has any Ocean/River/Port hex — the terrain COASTAL movement needs to ever
     *  move at all (per movTableDry row 7: OCEAN/RIVER/PORT are all passable). NOT sufficient for
     *  DEEP_NAVAL/NAVAL — see [hasOpenWaterAccess]. Same cached-once shape as [hasRailData]; used
     *  by EquipmentWindowController to hide ships that could never be deployed anywhere on a
     *  land-locked map's Purchase list. */
    fun hasWaterAccess(): Boolean {
        _hasWaterAccess?.let { return it }
        val naval = setOf(TerrainType.OCEAN.value, TerrainType.RIVER.value, TerrainType.PORT.value)
        val found = map?.any { row -> row.any { it.terrain in naval } } ?: false
        _hasWaterAccess = found
        return found
    }

    private var _hasOpenWaterAccess: Boolean? = null

    /** Whether this map has any Ocean/Port hex. Per movTableDry rows 6 (DEEP_NAVAL) and 10
     *  (NAVAL), RIVER is 255 (impassable) for both — only COASTAL can actually cross a river (row
     *  7). A river-only map (e.g. Operation Uranus, all Don-river hexes, zero Ocean/Port) must NOT
     *  count as "water access" for submarines/destroyers/battleships, or the Purchase list offers
     *  ships that could never move a single hex (2026-07-15 bug report). */
    fun hasOpenWaterAccess(): Boolean {
        _hasOpenWaterAccess?.let { return it }
        val openWater = setOf(TerrainType.OCEAN.value, TerrainType.PORT.value)
        val found = map?.any { row -> row.any { it.terrain in openWater } } ?: false
        _hasOpenWaterAccess = found
        return found
    }

    fun setHex(row: Int, col: Int, hex: Hex? = null) {
        // Store a provided hex INTO the grid — this function silently dropped it before, which
        // is why a RESTORED game rendered terrain-only: GameStateRestore built fresh Hex objects
        // and passed them here, units got registered (so their images even preloaded), but the
        // grid kept allocMap()'s blank hexes and the renderer walks the grid. ScenarioLoader
        // never noticed — it mutates the grid's own hexes in place and calls the no-arg form.
        if (hex != null) map?.getOrNull(row)?.let { if (col in it.indices) it[col] = hex }
        val target = hex ?: map?.getOrNull(row)?.getOrNull(col) ?: return
        val vs = target.victorySide
        if (vs != -1) {
            val ownerSide = if (target.owner != -1) getPlayer(target.owner).side else -1
            val enemySide = if (ownerSide != -1) 1 - ownerSide else vs
            val pos = target.getPos()
            if (sidesVictoryHexes.getOrNull(enemySide)?.none { it.row == pos.row && it.col == pos.col } != false) {
                if (sidesVictoryHexes.size <= enemySide) sidesVictoryHexes.add(mutableListOf())
                sidesVictoryHexes[enemySide].add(pos)
            }
        }
        target.unit?.let { addUnit(it) }
        target.airunit?.let { addUnit(it) }
    }

    // sidesVictoryHexes[s] = the objectives side s still needs to capture. When side [side] takes
    // [pos], remove it from *its own* remaining list and hand it to the enemy (who must now retake
    // it); [side] wins once its own list is empty. This mirrors PM's updateVictorySides — the
    // sides were previously swapped, which fabricated victories and never fired the "you lost your
    // last objective -> defeat" case (an enemy capture of the player's final hex is this same call
    // with side = enemy).
    fun updateVictorySides(side: Int, pos: Cell): Boolean {
        val enemySide = 1 - side
        val ownList = sidesVictoryHexes.getOrNull(side) ?: return false
        val removed = ownList.removeAll { it.row == pos.row && it.col == pos.col }
        if (removed) {
            if (sidesVictoryHexes.size <= enemySide) sidesVictoryHexes.add(mutableListOf())
            sidesVictoryHexes[enemySide].add(pos)
        }
        return sidesVictoryHexes.getOrNull(side)?.isEmpty() ?: false
    }

    fun getDeployHexes(side: Int): Array<Cell> {
        val result = mutableListOf<Cell>()
        map?.forEachIndexed { r, row ->
            row.forEachIndexed { c, hex ->
                if (hex.isDeployment != -1 && getPlayer(hex.isDeployment).side == side) result.add(Cell(r, c))
            }
        }
        return result.toTypedArray()
    }

    // ---- Unit registry ----

    fun addUnit(unit: GameUnit) {
        unit.id = nextUnitId++
        units.add(unit)
        unitImages.add(unit.eqid)
        unit.transport?.let { unitImages.add(it.eqid) }
        if (unit.carrier > 0) unitImages.add(unit.carrier)
        unit.player = getPlayer(unit.owner)
        if (unit.flag == -1) unit.flag = getPlayer(unit.owner).country + 1
        GameRules.setZOCRange(this, unit, true)
        GameRules.setSpotRange(this, unit, true)
    }

    fun getUnits(): Array<GameUnit> = units.toTypedArray()

    fun getUnitById(id: Int): GameUnit? = units.find { it.id == id }

    fun getUnitImagesList(): dynamic {
        val result = js("{}")
        unitImages.forEach { eqid ->
            val icon = Equipment.equipment[eqid]?.icon as? String
            if (!icon.isNullOrEmpty()) result[eqid] = icon
        }
        return result
    }

    fun hasAliveUnits(side: Int): Boolean = units.any { it.player?.side == side && !it.destroyed }

    fun removeAllSideUnits(side: Int) {
        units.filter { it.player?.side == side }.forEach { it.destroyed = true }
        updateUnitList()
    }

    fun updateUnitList() {
        val iter = units.iterator()
        while (iter.hasNext()) {
            val unit = iter.next()
            if (unit.destroyed) {
                val pos = unit.getPos()
                if (pos != null) {
                    GameRules.setZOCRange(this, unit, false)
                    GameRules.setSpotRange(this, unit, false)
                    map?.getOrNull(pos.row)?.getOrNull(pos.col)?.delUnit(unit)
                }
                if (GameHolder.instance?.campaign != null && unit.nodossier != false) {
                    GameHolder.instance?.getCampaignPlayer()?.addDestroyedUnitToDossier(unit)
                }
                iter.remove()
            }
        }
    }

    fun getUnitNeighbor(unit: GameUnit, direction: Int, onlyUnmoved: Boolean): GameUnit {
        val sameSideUnits = units.filter {
            it.player?.id == unit.player?.id && (!onlyUnmoved || !it.hasMoved)
        }.toMutableList()
        val index = sameSideUnits.indexOf(unit)
        if (index == -1) return unit
        val newIndex = if (direction > 0) (index + 1) % sameSideUnits.size
        else (index - 1 + sameSideUnits.size) % sameSideUnits.size
        return sameSideUnits[newIndex]
    }

    // ---- Player registry ----

    fun addPlayer(player: Player) {
        players.add(player)
        if (currentPlayer == null) currentPlayer = player
        if (player.airTransports > 0) {
            Equipment.getCountryEquipmentByClass(UnitClass.AIR_TRANSPORT, player.country + 1).firstOrNull()
                ?.let { unitImages.add(it) }
        }
        if (player.navalTransports > 0) {
            Equipment.getCountryEquipmentByClass(UnitClass.NAVAL_TRANSPORT, player.country + 1).firstOrNull()
                ?.let { unitImages.add(it) }
        }
    }

    fun getPlayers(): Array<Player> = players.toTypedArray()

    fun getPlayer(id: Int): Player = if (id in players.indices) players[id] else players[0]

    fun getPlayersByCountry(country: Int): Array<Player> =
        players.filter { it.country == country }.toTypedArray()

    fun getCountriesBySide(side: Int): Array<Int> {
        val result = mutableListOf<Int>()
        players.filter { it.side == side }.forEach { player ->
            result.add(player.country)
            player.supportCountries.forEach { sc -> if (sc > 0) result.add(sc - 1) }
        }
        return result.distinct().toTypedArray()
    }

    // ---- Selection & range management ----

    fun setCurrentUnit(unit: GameUnit?) { currentUnit = unit }

    fun delCurrentUnit() {
        currentUnit?.let { if (it.carrier < 0) it.toggleEmbark() }
        currentUnit = null
        delMoveSel()
        delAttackSel()
    }

    fun delMoveSel() {
        currentMoveRange.forEach { cell ->
            map?.getOrNull(cell.row)?.getOrNull(cell.col)?.isMoveSel = false
        }
        currentMoveRange.clear()
    }

    fun delAttackSel() {
        currentAttackRange.forEach { cell ->
            map?.getOrNull(cell.row)?.getOrNull(cell.col)?.isAttackSel = false
        }
        currentAttackRange.clear()
    }

    fun setMoveRange(unit: GameUnit) {
        delMoveSel()
        val range = GameRules.getMoveRange(this, unit)
        range.forEach { cell ->
            currentMoveRange.add(cell)
            if (cell is ExtendedCell && cell.canMove) {
                map?.getOrNull(cell.row)?.getOrNull(cell.col)?.isMoveSel = true
            }
        }
    }

    fun getCurrentMoveRange(): Array<Cell> = currentMoveRange.toTypedArray()

    fun setAttackRange(unit: GameUnit) {
        delAttackSel()
        val cells = GameRules.getUnitAttackCells(this.map ?: return, unit, rows, cols)
        cells.forEach { cell ->
            currentAttackRange.add(cell)
            map?.getOrNull(cell.row)?.getOrNull(cell.col)?.isAttackSel = true
        }
    }

    fun selectUnit(unit: GameUnit): Boolean {
        if (unit == null || unit.player?.id != currentPlayer?.id) return false
        delCurrentUnit()
        delMoveSel()
        delAttackSel()
        setCurrentUnit(unit)
        if (unit.carrier < 0) {
            unit.carrier = -unit.carrier
            disembarkUnit(unit)
        }
        if (!unit.hasMoved && unit.carrier >= 0) setMoveRange(unit)
        if (!unit.hasFired) setAttackRange(unit)
        return true
    }

    // ---- Turn sequencing ----

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
                if (unit != null) {
                    if (unit.isMounted) unmountUnitHandler(unit)
                    val supply = GameRules.getResupplyValue(this, unit, true)
                    if (supply.ammo > 0 || supply.fuel > 0 || supply.transportAmmo > 0 || supply.transportFuel > 0) {
                        unit.resupply(supply)
                        CombatLog.addResupply(unit)
                    }
                    unit.unitEndTurn(GameHolder.instance?.spotSide ?: 0)
                }
            }
        }
        if (currentPlayer?.type == PlayerType.AI_LOCAL || currentPlayer?.type == PlayerType.AI_SCRIPTED) {
            currentPlayer?.handler?.buildActions()
        }
    }

    // ---- Bulk map copy / cleanup ----

    fun copy(other: GameMap) {
        if (other == null) return
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

    // ---- Delegation: combat (CombatApplication) ----

    fun attackUnit(attacker: GameUnit, defender: GameUnit, supportFire: Boolean, isOverrun: Boolean = false): CombatResults =
        combatApplication.attackUnit(attacker, defender, supportFire, isOverrun)

    fun retreatUnit(unit: GameUnit, to: Cell): MovementResults = combatApplication.retreatUnit(unit, to)

    fun captureHex(hex: Hex, unit: GameUnit): dynamic = combatApplication.captureHex(hex, unit)

    // ---- Delegation: movement + undo (MoveExecutor) ----

    fun moveUnit(unit: GameUnit, row: Int, col: Int): MovementResults = moveExecutor.moveUnit(unit, row, col)

    fun undoLastMove() = moveExecutor.undoLastMove()

    fun canUndoMove(unit: GameUnit): Boolean = moveExecutor.canUndoMove(unit)

    // ---- Delegation: unit lifecycle (UnitOperations) ----

    fun mountUnit(unit: GameUnit) = unitOperations.mountUnit(unit)

    fun mountUnitHandler(unit: GameUnit) = unitOperations.mountUnitHandler(unit)

    fun unmountUnit(unit: GameUnit) = unitOperations.unmountUnit(unit)

    fun unmountUnitHandler(unit: GameUnit) = unitOperations.unmountUnitHandler(unit)

    fun embarkUnit(unit: GameUnit): Boolean = unitOperations.embarkUnit(unit)

    fun disembarkUnit(unit: GameUnit): Boolean = unitOperations.disembarkUnit(unit)

    fun upgradeUnit(unitId: Int, newEqid: Int, transportEqid: Int): Boolean =
        unitOperations.upgradeUnit(unitId, newEqid, transportEqid)

    fun disbandUnit(unitId: Int): Boolean = unitOperations.disbandUnit(unitId)

    fun deployPlayerUnit(player: Player, index: Int, row: Int, col: Int): Boolean =
        unitOperations.deployPlayerUnit(player, index, row, col)

    @JsName("deployPlayerUnitByUnit")
    fun deployPlayerUnit(player: Player, unit: GameUnit, row: Int, col: Int): Boolean =
        unitOperations.deployPlayerUnit(player, unit, row, col)

    fun deployNewUnitByEqId(eqid: Int, row: Int, col: Int, owner: Int) =
        unitOperations.deployNewUnitByEqId(eqid, row, col, owner)

    fun deployReinforcement(unit: GameUnit, row: Int, col: Int): Cell? =
        unitOperations.deployReinforcement(unit, row, col)

    fun resupplyUnit(unit: GameUnit): Supply = unitOperations.resupplyUnit(unit)

    fun reinforceUnit(unit: GameUnit, overStrength: Boolean): dynamic =
        unitOperations.reinforceUnit(unit, overStrength)

    fun buildCoreUnitList(player: Player) = unitOperations.buildCoreUnitList(player)

    fun undeployCoreUnits(player: Player) = unitOperations.undeployCoreUnits(player)

    fun restoreCoreUnitList(player: Player, saved: List<dynamic>) =
        unitOperations.restoreCoreUnitList(player, saved)

    fun removeNonCampaignUnits(player: Player) = unitOperations.removeNonCampaignUnits(player)
}
