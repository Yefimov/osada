package org.osada.rules

import org.osada.*
import org.osada.model.*

/**
 * Movement, pathfinding, spotting, zone-of-control and embark/deploy rules.
 *
 * Owns everything about where a unit may go and what it can see, extracted from the
 * former `GameRules` god-object. Depends on [HexGeometry] for spatial primitives and
 * [UnitPredicates] for unit classification. Faithful port of the corresponding
 * `openpanzer.js` rules.
 */
object MovementRules {

    /** Reachable cells for [unit] this turn, annotated with movement cost/path metadata. */
    fun getMoveRange(map: GameMap, unit: GameUnit): Array<ExtendedCell> {
        val result = mutableListOf<ExtendedCell>()
        if (unit.hasMoved) return result.toTypedArray()
        val gameMap = map.map ?: return result.toTypedArray()
        val pos = unit.getPos() ?: return result.toTypedArray()
        val unitData = unit.unitData()
        val maxRange = getUnitMoveRange(unit)
        val unitSide = unit.player?.side ?: return result.toTypedArray()
        val enemySide = 1 - unitSide
        val isTrain = UnitPredicates.isTrain(unit)
        // Strict "trains only move on rail" only applies once the MAP actually carries rail data
        // (tools/og-import/add_rails.py) -- most scenarios haven't been re-patched yet. Only
        // RAIL(12)-flagged units need the WHEELED fallback below to reproduce their pre-fix
        // behaviour (fix_rail_units.py switched them from WHEELED to RAIL): the rarer legacy
        // "repurpose DEEP_NAVAL for trains" convention (e.g. eqp-adlerkorps's Armoured Train) was
        // already completely immobile on land before this fix and is left exactly as it was
        // unless the map has rail data to actually move it on.
        val enforceRail = isTrain && map.hasRailData()
        val method = if (unitData.movmethod == MovMethod.RAIL.value && !enforceRail) MovMethod.WHEELED.value else unitData.movmethod
        val movementTable = movTable[method]
        var cells = HexGeometry.getRing(pos.row, pos.col, maxRange, map.rows, map.cols, true)
            .map { it as ExtendedCell }
            .toMutableList()

        // Remove invalid edge cells
        cells = cells.filter { cell ->
            !(cell.row == 0 && cell.col % 2 == 0)
                    && !(map.isLastColPartial && cell.col == map.cols - 1)
                    && !(map.isLastRowPartial && cell.row == map.rows - 1 && cell.col % 2 == 1)
        }.toMutableList()

        if (UnitPredicates.isAir(unit)) {
            cells.forEach { cell ->
                val hex = gameMap[cell.row][cell.col]
                val enemyAirVisible = hex.isSpotted(unitSide) || hex.airunit?.tempSpotted == true
                if (!enemyAirVisible || hex.airunit == null) {
                    cell.canMove = true
                    cell.cost = 1
                    result.add(cell)
                }
            }
            return result.toTypedArray()
        }

        cells.add(ExtendedCell(pos.row, pos.col))
        var k = 0
        while (k <= maxRange) {
            cells.filter { it.range == k }.forEach { current ->
                cells.filter { neighbor ->
                    HexGeometry.isAdjacent(current.row, current.col, neighbor.row, neighbor.col)
                            && (neighbor.range >= k)
                }.forEach { neighbor ->
                    val hex = gameMap[neighbor.row][neighbor.col]
                    neighbor.cost = when {
                        enforceRail -> if (hex.rail > RoadType.NONE.value) 1 else 255
                        hex.road > RoadType.NONE.value || isBridgeForSide(hex, unitSide) -> movementTable[17]
                        else -> movementTable[hex.terrain]
                    }
                    if ((hex.isSpotted(unitSide) || hex.unit?.tempSpotted == true) && hex.isZOC(enemySide) && neighbor.cost < 254) {
                        neighbor.cost = 254
                    }
                    if (neighbor.cin == 0) neighbor.cin = current.cout
                    if (neighbor.cout == 0) neighbor.cout = neighbor.cin + neighbor.cost
                    if (neighbor.cin > current.cout && neighbor.cost <= 254) {
                        neighbor.cin = current.cout
                        neighbor.cout = neighbor.cin + neighbor.cost
                    }
                    if ((neighbor.cout <= maxRange || (neighbor.cost <= 254 && neighbor.cin <= maxRange))) {
                        if (canPassInto(gameMap, unit, neighbor)) neighbor.canPass = true
                        val enemyVisible = hex.isSpotted(unitSide) || hex.unit?.tempSpotted == true
                        if (!enemyVisible || hex.unit == null) {
                            neighbor.canMove = true
                        }
                    }
                }
            }
            k++
        }

        cells.filter { it.canPass || it.canMove }.forEach { result.add(it) }
        return result.toTypedArray()
    }

    /** Effective movement points for [unit] this turn (capped by fuel, boosted by leaders). */
    fun getUnitMoveRange(unit: GameUnit): Int {
        var range = unit.getMovesLeft()
        val data = unit.unitData()
        if (UnitPredicates.unitUsesFuel(unit) && unit.getFuel() < range) range = unit.getFuel()
        if (Leaders.unitHasLeader(unit, LeaderType.AGGRESSIVE_TANK_MANEUVER)) range += 1
        if (Leaders.unitHasLeader(unit, LeaderType.AGGRESSIVE_MANEUVER)) range += 1
        if (data.movmethod == MovMethod.TOWED.value && unit.transport == null && range == 0
            && data.uclass != UnitClass.FORTIFICATION.value
        ) {
            range = 1
        }
        return range
    }

    /** A* shortest path from [start] to [end] over the precomputed [moveRange] cells. */
    fun getShortestPath(start: Cell, end: Cell, moveRange: List<Cell>): List<Cell> {
        val result = mutableListOf<Cell>()
        val openList = mutableListOf<PathCell>()
        val closedList = mutableListOf<PathCell>()
        val startNode = PathCell(start.row, start.col).apply { dist = 0.0; prev = this }
        openList.add(startNode)

        val nodeMap = mutableMapOf<Pair<Int, Int>, PathCell>()
        nodeMap[start.row to start.col] = startNode

        moveRange.forEach { cell ->
            val node = PathCell(cell.row, cell.col)
            node.cost = if (cell is ExtendedCell) cell.cost else 1
            nodeMap[cell.row to cell.col] = node
            openList.add(node)
        }

        while (openList.isNotEmpty()) {
            val current = openList.minByOrNull { it.dist } ?: break
            if (current.dist == Double.POSITIVE_INFINITY) break
            openList.remove(current)
            closedList.add(current)

            if (current.row == end.row && current.col == end.col) {
                var node: PathCell? = current
                while (node != null) {
                    result.add(0, Cell(node.row, node.col))
                    if (node == startNode) break
                    node = node.prev
                }
                return result
            }

            openList.filter { HexGeometry.isAdjacent(current.row, current.col, it.row, it.col) }.forEach { neighbor ->
                val tentative = current.dist + neighbor.cost
                if (tentative < neighbor.dist) {
                    neighbor.dist = tentative
                    neighbor.prev = current
                }
            }
        }
        return result
    }

    /** True when [unit] may enter [cell] (empty, or occupied only by a friendly unit). */
    fun canPassInto(map: Array<Array<Hex>>?, unit: GameUnit, cell: Cell): Boolean {
        val hex = map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return false
        return when {
            UnitPredicates.isAir(unit) -> hex.airunit == null || hex.airunit!!.player?.side == unit.player?.side
            UnitPredicates.isGround(unit) || UnitPredicates.isSea(unit) -> hex.unit == null || hex.unit!!.player?.side == unit.player?.side
            else -> false
        }
    }

    /** A friendly non-mounted bridge unit on a river/stream hex acts as a road for [side]. */
    fun isBridgeForSide(hex: Hex?, side: Int): Boolean {
        if (hex == null || hex.unit == null) return false
        if (hex.terrain != TerrainType.RIVER.value && hex.terrain != TerrainType.STREAM.value) return false
        if (hex.unit!!.isMounted) return false
        if (hex.unit!!.player?.side != side) return false
        return Equipment.isBridge(hex.unit!!.eqid)
    }

    /** Adds or removes [unit]'s zone of control on its neighbouring hexes. */
    fun setZOCRange(map: GameMap, unit: GameUnit, add: Boolean) {
        if (unit == null || UnitPredicates.isAir(unit)) return
        val pos = unit.getPos() ?: return
        val side = unit.player?.side ?: return
        HexGeometry.getAdjacent(pos.row, pos.col).forEach { cell ->
            if (cell.row in 0 until map.rows && cell.col in 0 until map.cols) {
                map.map?.getOrNull(cell.row)?.getOrNull(cell.col)?.setZOC(side, add)
            }
        }
    }

    /** Adds or removes [unit]'s spotting; returns how many enemy units became newly spotted. */
    fun setSpotRange(map: GameMap, unit: GameUnit, add: Boolean): Int {
        if (unit == null) return 0
        val pos = unit.getPos() ?: return 0
        val side = unit.player?.side ?: return 0
        val range = getUnitSpotRange(unit)
        val cells = HexGeometry.getRing(pos.row, pos.col, range, map.rows, map.cols, false)
        cells.add(Cell(pos.row, pos.col))
        var newlySpotted = 0
        cells.forEach { cell ->
            if (cell.row in 0 until map.rows && cell.col in 0 until map.cols) {
                val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return@forEach
                if (add && !hex.isSpotted(side)) {
                    val enemy = hex.getUnit(false)
                    if (enemy != null && enemy.player?.side != side) {
                        enemy.tempSpotted = true
                        newlySpotted++
                    }
                }
                hex.setSpotted(side, add)
            }
        }
        return newlySpotted
    }

    /** Spotting range for [unit] including recon leader bonuses. */
    fun getUnitSpotRange(unit: GameUnit): Int {
        if (unit == null) return 0
        var range = unit.unitData().spotrange
        if (Leaders.unitHasLeader(unit, LeaderType.ELITE_RECON_VETERAN)) range += 2
        if (Leaders.unitHasLeader(unit, LeaderType.SKILLED_RECONNAISSANCE)) range += 1
        return range
    }

    /** The carrier class [unit] could embark onto at its current hex, or NONE. */
    fun getEmbarkType(map: GameMap, unit: GameUnit): Int {
        val pos = unit.getPos() ?: return UnitClass.NONE.value
        val hex = map.map?.getOrNull(pos.row)?.getOrNull(pos.col) ?: return UnitClass.NONE.value
        val data = unit.unitData()
        if (hex.terrain == TerrainType.AIRFIELD.value && unit.player?.airTransports ?: 0 > 0
            && data.embark > EmbarkType.NAVAL.value && hex.airunit == null
        ) {
            return UnitClass.AIR_TRANSPORT.value
        }
        if (hex.terrain == TerrainType.PORT.value && unit.player?.navalTransports ?: 0 > 0
            && data.embark > EmbarkType.NONE.value
        ) {
            return UnitClass.NAVAL_TRANSPORT.value
        }
        return UnitClass.NONE.value
    }

    /** Cells an embarked transport [unit] may disembark its cargo into. */
    fun getDisembarkPositions(map: GameMap, unit: GameUnit): List<Cell> {
        val result = mutableListOf<Cell>()
        if (unit.hasMoved) return result
        val data = unit.unitData()
        if (data.uclass != UnitClass.AIR_TRANSPORT.value && data.uclass != UnitClass.NAVAL_TRANSPORT.value) return result
        val movementMethod = Equipment.equipment[unit.eqid]?.movmethod ?: return result
        val movementTable = movTable[movementMethod]
        val pos = unit.getPos() ?: return result
        HexGeometry.getAdjacent(pos.row, pos.col).forEach { cell ->
            val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return@forEach
            if (hex.unit == null && movementTable[hex.terrain] < 255) {
                result.add(cell)
            }
        }
        return result
    }

    /** First free, passable hex at/around (row,col) where a reinforcement may deploy, or null. */
    fun getReinforcementDeployPositions(map: GameMap, unit: GameUnit, row: Int, col: Int): Cell? {
        Equipment.equipment[unit.eqid] ?: return null
        val isTrain = UnitPredicates.isTrain(unit)
        val enforceRail = isTrain && map.hasRailData()
        // Terrain-based fallback table: movTable[RAIL.value] is intentionally all-255 (see
        // Constants.kt) -- a real train must resolve through WHEELED for any plain-terrain check,
        // whether because the map has no rail data at all, OR because its author-declared
        // reinforcement hex simply isn't within this narrow (hex + 6 neighbours) radius of the
        // rail network despite the map having rail elsewhere. Resolving RAIL's own row here would
        // make deployReinforcement return null -> Game.kt's caller never adds the unit to the map
        // -- the reinforcement is silently and PERMANENTLY lost, not merely stuck.
        val movementTable = movTable[if (isTrain) MovMethod.WHEELED.value else Equipment.equipment[unit.eqid]!!.movmethod]
        val candidates = mutableListOf<Cell>()
        candidates.add(Cell(row, col))
        candidates.addAll(HexGeometry.getAdjacent(row, col))
        fun slotEmpty(hex: Hex) = if (UnitPredicates.isAir(unit)) hex.airunit == null
                                  else (UnitPredicates.isGround(unit) || UnitPredicates.isSea(unit)) && hex.unit == null
        if (enforceRail) {
            // Prefer landing right on the rail network...
            candidates.forEach { cell ->
                val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return@forEach
                if (cell.row >= 0 && cell.col >= 0 && slotEmpty(hex) && hex.rail > RoadType.NONE.value) return cell
            }
            // ...but fall through to the ordinary terrain check (same as a non-train unit) rather
            // than dropping the reinforcement entirely if no candidate hex is on rail. The unit
            // will simply need to reach the rail network before it can move.
        }
        candidates.forEach { cell ->
            val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return@forEach
            if (cell.row >= 0 && cell.col >= 0 && slotEmpty(hex) && movementTable[hex.terrain] < 255) {
                return cell
            }
        }
        return null
    }

    fun canEmbark(map: GameMap, unit: GameUnit): Boolean {
        return getEmbarkType(map, unit) > UnitClass.NONE.value || unit.carrier < 0
    }

    fun canDisembark(map: GameMap, unit: GameUnit): Boolean {
        return getDisembarkPositions(map, unit).isNotEmpty()
    }

    /**
     * True when [unit] is on or beside a friendly airfield/carrier. Used by air resupply
     * and reinforcement rules ([SupplyRules]).
     */
    internal fun hasAirfield(map: GameMap, unit: GameUnit): Boolean {
        val pos = unit.getPos() ?: return false
        val hex = map.map?.getOrNull(pos.row)?.getOrNull(pos.col) ?: return false
        if (hex.terrain == TerrainType.AIRFIELD.value && hex.flag == unit.player?.country) return true
        if (hex.unit?.unitData()?.uclass == UnitClass.CARRIER.value && hex.unit?.owner == unit.player?.id) return true
        HexGeometry.getAdjacent(pos.row, pos.col).forEach { cell ->
            val neighbor = map.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return@forEach
            if (neighbor.terrain == TerrainType.AIRFIELD.value && neighbor.flag == unit.player?.country) return true
        }
        return false
    }
}
