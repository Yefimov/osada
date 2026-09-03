package org.osada.rules

import org.osada.LeaderType
import org.osada.MovMethod
import org.osada.RoadType
import org.osada.TerrainType
import org.osada.model.ExtendedCell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.Leaders
import org.osada.model.hasRailData
import org.osada.model.isMountainTrained
import org.osada.movTable
import org.osada.rules.MoveRangeCalculation.ZOC_MOVE_COST

/**
 * [MovementRules.getMoveRange]'s BFS-style cost expansion. Split out purely to keep
 * [MovementRules] within the project's function-count/class-size limits -- not expected to be
 * called from elsewhere.
 */
internal object MoveRangeCalculation {
    // movTable sentinel costs (see Constants.kt movTableDry doc): 255 = impassable via this
    // table; 254 = a fixed high cost, notably enemy-ZOC. 17 is the road/bridge column index.
    internal const val IMPASSABLE_TERRAIN_COST = 255
    private const val ZOC_MOVE_COST = 254
    private const val ROAD_MOVE_TABLE_INDEX = 17
    private const val AIR_MOVE_COST = 1

    private class MoveContext(
        val gameMap: Array<Array<Hex>>,
        val pos: org.osada.model.Cell,
        val maxRange: Int,
        val unitSide: Int,
        val cells: MutableList<ExtendedCell>,
        val enforceRail: Boolean,
        val movementTable: List<Int>,
        val ignoresZoc: Boolean,
        val alpineTrained: Boolean,
        val mountainTrained: Boolean,
    )

    fun getMoveRange(
        map: GameMap,
        unit: GameUnit,
    ): Array<ExtendedCell> {
        val context = resolveMoveContext(map, unit) ?: return emptyArray()
        return if (UnitPredicates.isAir(unit)) {
            airMoveRange(context).toTypedArray()
        } else {
            groundMoveRange(context, unit).toTypedArray()
        }
    }

    private fun resolveMoveContext(
        map: GameMap,
        unit: GameUnit,
    ): MoveContext? {
        val gameMap = if (unit.hasMoved) null else map.map
        val pos = if (unit.hasMoved) null else unit.getPos()
        val unitSide = unit.player?.side
        if (gameMap == null || pos == null || unitSide == null) return null

        val maxRange = MovementRules.getUnitMoveRange(unit)
        val isTrain = UnitPredicates.isTrain(unit)
        // **A train moves on rail or it does not move.** No map-level escape hatch: this used to be
        // gated on `map.hasRailData()`, so on a scenario never patched by `add_rails.py` a train
        // fell back to WHEELED and drove cross-country. That was a workaround for missing DATA, and
        // it produced the visible defect it was meant to avoid -- an armoured train crossing open
        // steppe. The rule now stands on its own, and a formation the author parked off the line is
        // an immobile one: the Great Patriotic War used armoured trains exactly so once they were
        // cut off from their track, dug in at a terminus or a works siding and fought as fixed
        // firing points. `rcampode`'s own Odessa unit is named for it.
        //
        // The purchase list already assumed this shape: `EquipmentWindowState` hides RAIL equipment
        // outright on a map with no rail, so nobody can buy a train that would only ever be a
        // pillbox. What is gone is the pretence that one already on the map could drive.
        val enforceRail = isTrain
        val unitData = unit.unitData()
        // movTable[RAIL.value] is intentionally all-255 (Constants.kt) -- a placeholder that is
        // never meant to be read. `baseTerrainCost` short-circuits on `enforceRail` before the
        // table is consulted, but the substitution stays so no other reader can resolve that row.
        val method = if (isTrain) MovMethod.WHEELED.value else unitData.movmethod
        val movementTable = movTable[method]
        val ring =
            HexGeometry
                .getRing(pos.row, pos.col, maxRange, map.rows, map.cols, true)
                .map { it as ExtendedCell }
        val cells = ring.filter { cell -> isValidEdgeCell(cell, map) }.toMutableList()

        // OG's Superior Maneuver: "The unit may bypass enemy zones of control." The leader has
        // existed (and been offered to the player, with that exact description) since the port
        // began, with NO rule behind it -- `grep LeaderType.SUPERIOR_MANEUVER src/jsMain/.../rules`
        // found nothing. It bypasses the ZOC rule outright, not a cost discount: OG's manual states
        // the unit ignores enemy ZOC, and here ZOC is the only thing the flag may touch.
        val ignoresZoc =
            Leaders.unitHasLeader(unit, LeaderType.SUPERIOR_MANEUVER) ||
                UnitCapabilities.ignoresZoneOfControl(unitData)

        // OG's Alpine Training: "When moving the unit treats forest and mountain hexes as clear
        // terrain." Advertised since the port began with no rule behind it -- the same defect class
        // as Superior Maneuver above (`docs/og-fidelity-plan.md` A.4). It is a MOVEMENT-COST rule
        // only: the trait says nothing about combat, so terrain defence, entrenchment baselines and
        // close-combat all keep reading the real terrain.
        val alpineTrained = Leaders.unitHasLeader(unit, LeaderType.ALPINE_TRAINING)

        // OG's `Mountain` equipment ability -- the same shape of rule, from the record instead of
        // a leader. See [terrainColumn].
        val mountainTrained = unitData.isMountainTrained()

        return MoveContext(
            gameMap,
            pos,
            maxRange,
            unitSide,
            cells,
            enforceRail,
            movementTable,
            ignoresZoc,
            alpineTrained,
            mountainTrained,
        )
    }

    private fun isValidEdgeCell(
        cell: ExtendedCell,
        map: GameMap,
    ): Boolean {
        val isTopEdgeGap = cell.row == 0 && cell.col % 2 == 0
        val isPartialLastCol = map.isLastColPartial && cell.col == map.cols - 1
        val isPartialLastRow = map.isLastRowPartial && cell.row == map.rows - 1 && cell.col % 2 == 1
        return !isTopEdgeGap && !isPartialLastCol && !isPartialLastRow
    }

    private fun airMoveRange(context: MoveContext): List<ExtendedCell> {
        val result = mutableListOf<ExtendedCell>()
        context.cells.forEach { cell ->
            val hex = context.gameMap[cell.row][cell.col]
            val enemyAirVisible = hex.isSpotted(context.unitSide) || hex.airunit?.tempSpotted == true
            if (!enemyAirVisible || hex.airunit == null) {
                cell.canMove = true
                cell.cost = AIR_MOVE_COST
                result.add(cell)
            }
        }
        return result
    }

    private fun groundMoveRange(
        context: MoveContext,
        unit: GameUnit,
    ): List<ExtendedCell> {
        val result = mutableListOf<ExtendedCell>()
        val cells = context.cells
        cells.add(ExtendedCell(context.pos.row, context.pos.col))
        val enemySide = 1 - context.unitSide
        var k = 0
        while (k <= context.maxRange) {
            cells.filter { it.range == k }.forEach { current ->
                cells
                    .filter { neighbor ->
                        HexGeometry.isAdjacent(current.row, current.col, neighbor.row, neighbor.col) &&
                            neighbor.range >= k
                    }.forEach { neighbor -> expandNeighbor(neighbor, current, context, unit, enemySide) }
            }
            k++
        }
        cells.filter { it.canPass || it.canMove }.forEach { result.add(it) }
        return result
    }

    /** Resolves [neighbor]'s move cost from [current], applies the enemy-ZOC cost floor, then
     *  marks it canPass/canMove when reachable within [MoveContext.maxRange]. */
    private fun expandNeighbor(
        neighbor: ExtendedCell,
        current: ExtendedCell,
        context: MoveContext,
        unit: GameUnit,
        enemySide: Int,
    ) {
        val hex = context.gameMap[neighbor.row][neighbor.col]
        resolveNeighborCost(neighbor, hex, context, enemySide)
        updateReachDistance(neighbor, current)
        markPassableAndVisible(neighbor, hex, context, unit)
    }

    /** Sets [neighbor].cost from the movement table (or rail cost), floored to [ZOC_MOVE_COST]
     *  when the hex is a spotted enemy zone of control. */
    private fun resolveNeighborCost(
        neighbor: ExtendedCell,
        hex: Hex,
        context: MoveContext,
        enemySide: Int,
    ) {
        // The road column is a BONUS, so it may only ever be taken when this movement method can
        // actually use a road. PM applies it unconditionally (`openpanzer.js:2157`), and because
        // the road entry is 255 for all three naval rows, a river hex carrying a road -- i.e. a
        // BRIDGE -- became impassable to ships: in `Falciu 1` the Shtorm TB could run the river
        // freely but could not pass (19,23), `river/road9`. OG has no such rule. It does have a
        // per-method road cost, but in its own `[roads-cost]` section rather than as a column of
        // `[terrain-cost]`, and BASEKORP sets it to 255 for all three naval methods while giving
        // the same methods river cost 1 outright -- so 255 there reads "cannot use roads", i.e.
        // withholds the bonus, not "cannot enter a bridged hex". Falling back to the terrain
        // column changes nothing for land units (their road entry is 1, passable) and does not
        // float a deep-naval ship up a river (its river entry is 255 either way).
        val base =
            baseTerrainCost(
                hex = hex,
                roadCost = context.movementTable[ROAD_MOVE_TABLE_INDEX],
                enforceRail = context.enforceRail,
                unitSide = context.unitSide,
                terrainCost = context.movementTable[terrainColumn(hex.terrain, context)],
            )
        neighbor.cost = withRubbleSurcharge(base, hex)
        val inEnemyZoc =
            !context.ignoresZoc &&
                (hex.isSpotted(context.unitSide) || hex.unit?.tempSpotted == true) &&
                hex.isZOC(enemySide)
        // A minefield this side has DETECTED gets the ZOC cost floor, which is exactly OG's rule --
        // "entering a detected minefield consumes all remaining movement". Reusing the sentinel
        // rather than inventing one means the overlay, the pathfinder and `GameUnit.move`'s /254
        // normalisation all already understand it.
        //
        // An UNDETECTED field is deliberately absent from this calculation. Costing it here would
        // paint it on the move overlay, which is the whole ambush given away -- the same reason
        // `AAInterception.visibleThreatHexes` refuses to derive threat hexes from hidden guns.
        val onKnownMinefield = Minefields.isKnownThreat(hex, context.unitSide)
        if ((inEnemyZoc || onKnownMinefield) && neighbor.cost < ZOC_MOVE_COST) {
            neighbor.cost = ZOC_MOVE_COST
        }
    }

    /**
     * The terrain column this unit pays for: its own, unless difficult ground reads as clear.
     *
     * Two sources, deliberately sharing one code path because they are the same kind of rule:
     *  - **Alpine Training** (leader): forest and mountain as clear.
     *  - **`Mountain`** (equipment, `attr` bit 9): hill, mountain and rough as clear -- the same
     *    cost `ALL_TERRAIN_LEG` (OG's "Mountain Leg" method) pays for those three columns, which
     *    is how OG expresses mountain training for the records that use the method instead of the
     *    bit. Wired 2026-08-25, reported as *"Gornostrelky should have MOUNTAIN, they are mountain
     *    troops"* -- and, of the ability prose, *"let it be so in Osada"*.
     *
     * A COST rule only: terrain defence, entrenchment baselines and close combat all keep reading
     * the real terrain. Impassable stays impassable -- the table's 255 sentinel is never reached
     * for these columns by any land method, so nothing here floats a ship or an aircraft.
     */

    private fun terrainColumn(
        terrain: Int,
        context: MoveContext,
    ): Int =
        when {
            context.alpineTrained &&
                (terrain == TerrainType.FOREST.value || terrain == TerrainType.MOUNTAIN.value) ->
                TerrainType.CLEAR.value

            context.mountainTrained && terrain in MOUNTAIN_TRAINED_TERRAIN -> TerrainType.CLEAR.value
            else -> terrain
        }

    /**
     * OG 9.2: *"a successful barrage attack on an empty hex can make the terrain harder to move
     * through"*.
     *
     * Craters (OSADA's own rule, `rules/Craters`) cost the same on entry: a hole in the ground is a
     * hole whether shells dug it out of a field or out of a village.
     *
     * A surcharge rather than a terrain swap, for the reason [org.osada.model.Hex.rubble] gives —
     * OG's rubble is an efile-authored terrain index that most efiles use for something else.
     * Applied AFTER the road bonus on purpose: shelled ground is exactly what makes a road worth
     * less. A sentinel cost is left alone: impassable stays impassable, and the ZOC floor is a
     * floor rather than a price.
     */
    private fun withRubbleSurcharge(
        cost: Int,
        hex: Hex,
    ): Int {
        val churned = hex.rubble || (hex.crater && Craters.enabled())
        val chargeable = cost != IMPASSABLE_TERRAIN_COST && cost != ZOC_MOVE_COST
        return if (churned && chargeable) cost + Barrage.RUBBLE_MOVE_SURCHARGE else cost
    }

    /** The three columns OG's `Mountain` ability discounts; see [terrainColumn]. */
    private val MOUNTAIN_TRAINED_TERRAIN =
        setOf(TerrainType.HILL.value, TerrainType.MOUNTAIN.value, TerrainType.ROUGH.value)

    /** Propagates [current]'s cumulative cost (cout) into [neighbor]'s entry cost (cin/cout),
     *  taking the cheaper of any two paths already found to reach it. */
    private fun updateReachDistance(
        neighbor: ExtendedCell,
        current: ExtendedCell,
    ) {
        if (neighbor.cin == 0) neighbor.cin = current.cout
        if (neighbor.cout == 0) neighbor.cout = neighbor.cin + neighbor.cost
        if (neighbor.cin > current.cout && neighbor.cost <= ZOC_MOVE_COST) {
            neighbor.cin = current.cout
            neighbor.cout = neighbor.cin + neighbor.cost
        }
    }

    /** Marks [neighbor] canPass/canMove once it's within [MoveContext.maxRange]. */
    private fun markPassableAndVisible(
        neighbor: ExtendedCell,
        hex: Hex,
        context: MoveContext,
        unit: GameUnit,
    ) {
        val reachable =
            neighbor.cout <= context.maxRange || (neighbor.cost <= ZOC_MOVE_COST && neighbor.cin <= context.maxRange)
        if (!reachable) return
        if (MovementRules.canPassInto(context.gameMap, unit, neighbor)) neighbor.canPass = true
        val enemyVisible = hex.isSpotted(context.unitSide) || hex.unit?.tempSpotted == true
        if (!enemyVisible || hex.unit == null) {
            neighbor.canMove = true
        }
    }
}

/**
 * What a hex costs to enter, before rubble: the rail sentinel, a road, or [terrainCost].
 *
 * Top-level and taking plain values rather than the private `MoveContext`, because it is here to
 * keep `resolveNeighborCost` inside detekt's complexity budget -- the same reason
 * `EfileConfig.listKey` sits outside its object.
 *
 * **A pontoon costs exactly what a road costs.** OG's `allow_pontoon_ex` charges road cost + 1 by
 * default and `eqp-lxf` waives it, and that divergence WAS built on 2026-08-28 and reverted the
 * same day: equipment is merged into one `eqp-united` database, so a unit must move the same
 * distance whatever efile it came from. A per-efile movement rule contradicts the merge
 * (`docs/og-fidelity-plan.md` §AB).
 */
private fun baseTerrainCost(
    hex: Hex,
    roadCost: Int,
    enforceRail: Boolean,
    unitSide: Int,
    terrainCost: Int,
): Int {
    val roadUsable = roadCost != MoveRangeCalculation.IMPASSABLE_TERRAIN_COST
    val onRoad = hex.road > RoadType.NONE.value || MovementRules.isBridgeForSide(hex, unitSide)
    return when {
        enforceRail ->
            if (hex.rail > RoadType.NONE.value) 1 else MoveRangeCalculation.IMPASSABLE_TERRAIN_COST
        onRoad && roadUsable -> roadCost
        else -> terrainCost
    }
}
