package org.osada.rules

import org.osada.GameHolder
import org.osada.LeaderType
import org.osada.MovMethod
import org.osada.TerrainType
import org.osada.UnitClass
import org.osada.model.ATTR_MASK_AIR_SUPPORT
import org.osada.model.Cell
import org.osada.model.Equipment
import org.osada.model.ExtendedCell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.Leaders
import org.osada.model.isBridge
import org.osada.model.isWorking

/**
 * Movement, pathfinding, spotting, zone-of-control and embark/deploy rules.
 *
 * Owns everything about where a unit may go and what it can see, extracted from the
 * former `GameRules` god-object. Depends on [HexGeometry] for spatial primitives and
 * [UnitPredicates] for unit classification. Faithful port of the corresponding
 * `osada.js` rules.
 */
object MovementRules {
    /** Reachable cells for [unit] this turn, annotated with movement cost/path metadata. */
    fun getMoveRange(
        map: GameMap,
        unit: GameUnit,
    ): Array<ExtendedCell> = MoveRangeCalculation.getMoveRange(map, unit)

    /** Effective movement points for [unit] this turn (capped by fuel, boosted by leaders and
     *  attachments). Fast Speed's bonus and any other attachment's movement penalty (`malus-type`
     *  1) both land here, after the fuel clamp -- same ordering the existing leader bonuses use,
     *  so an attachment bonus isn't itself further capped by fuel.
     *
     *  `attach_minmove` is deliberately NOT read here. It was previously applied as a floor this
     *  range could not fall below, which was an INFERENCE and is now known to be wrong: the key
     *  selects Fast Speed's flat-vs-percentage spelling and floors THE BONUS, not the unit's
     *  resulting movement (the scaling lives in [Attachments.bonus]). Applying it here was DEFERRED.md
     *  §1.14, and the cap added to contain it was §1.16 -- both dissolve with the correct reading. */
    fun getUnitMoveRange(unit: GameUnit): Int {
        var range = unit.getMovesLeft()
        val data = unit.unitData()
        // The fuel clamp is in MOVEMENT POINTS, so it must divide by what a point actually costs --
        // in snow under `snow_fuel` that is two (OG 6.23). Reading the raw fuel here would promise a
        // route the unit runs dry halfway along, which is the whole reason `docs/og-fidelity-plan.md`
        // B.2 calls the preview the real work rather than the multiplication.
        val fuelPerPoint = WeatherCombatRules.fuelPerMovePoint()
        // Aircraft additionally have to be able to afford the whole sortie floor before they may
        // take off at all under `air_fuel`, so the budget is not simply fuel/point for them
        // ([AirOperations.affordableMovePoints]). Identical to the raw division with the key off.
        val affordableByFuel = AirOperations.affordableMovePoints(unit, fuelPerPoint)
        if (UnitPredicates.unitUsesFuel(unit) && affordableByFuel < range) range = affordableByFuel
        if (Leaders.unitHasLeader(unit, LeaderType.AGGRESSIVE_TANK_MANEUVER)) range += 1
        if (Leaders.unitHasLeader(unit, LeaderType.AGGRESSIVE_MANEUVER)) range += 1
        range += Attachments.bonus(unit, Attachments.SLOT_FAST_SPEED) + Attachments.movementPenalty(unit)
        if (range < 0) range = 0
        // OG 9.9: "While in a minefield a unit has 1 movement point and decreased defense." The cap
        // is applied AFTER every bonus, because it is a cap and not a subtraction -- a commander
        // with Aggressive Maneuver does not drive out of a minefield faster. The defence half lives
        // in `AttackCalculation.applyMinefieldPenalty`.
        if (Minefields.threatens(unit.getHex(), unit.player?.side ?: -1)) {
            range = minOf(range, Minefields.MOVEMENT_IN_MINEFIELD)
        }
        val isStrandedTowedGun =
            data.movmethod == MovMethod.TOWED.value &&
                unit.transport == null &&
                range == 0 &&
                data.uclass != UnitClass.FORTIFICATION.value
        if (isStrandedTowedGun) {
            range = 1
        }
        return range
    }

    /** A* shortest path from [start] to [end] over the precomputed [moveRange] cells. */
    fun getShortestPath(
        start: Cell,
        end: Cell,
        moveRange: List<Cell>,
    ): List<Cell> = PathFinding.getShortestPath(start, end, moveRange)

    /** True when [unit] may enter [cell] (empty, or occupied only by a friendly unit). */
    fun canPassInto(
        map: Array<Array<Hex>>?,
        unit: GameUnit,
        cell: Cell,
    ): Boolean {
        val hex = map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return false
        return when {
            UnitPredicates.isAir(unit) -> hex.airunit == null || hex.airunit!!.player?.side == unit.player?.side
            UnitPredicates.isGround(unit) || UnitPredicates.isSea(unit) ->
                hex.unit == null ||
                    hex.unit!!.player?.side == unit.player?.side

            else -> false
        }
    }

    /** A friendly non-mounted bridge unit on a river/stream hex acts as a road for [side]. The
     *  Bridging attachment (`Attachments.SLOT_BRIDGING`, DEFERRED.md §1.4 Tier 2) is a second,
     *  OR'd source of the same boolean capability -- `Attachments.availableSlots` already refuses
     *  to sell it to a unit that has [Equipment.isBridge] itself, so the two can never both be true
     *  for the same unit and there is nothing to double-count. */
    fun isBridgeForSide(
        hex: Hex?,
        side: Int,
    ): Boolean {
        val bridgeUnit = hex?.unit
        val onRiverOrStream = hex?.terrain == TerrainType.RIVER.value || hex?.terrain == TerrainType.STREAM.value
        val isFriendlyDismounted = bridgeUnit != null && !bridgeUnit.isMounted && bridgeUnit.player?.side == side
        if (bridgeUnit == null || !onRiverOrStream || !isFriendlyDismounted) return false
        return Equipment.isBridge(bridgeUnit.eqid) || Attachments.has(bridgeUnit, Attachments.SLOT_BRIDGING)
    }

    /**
     * Adds or removes [unit]'s zone of control on its neighbouring hexes.
     *
     * Two kinds of unit project none at all and are skipped here rather than at either reader:
     * air units (unless the scenario sets OG's `Air ZOC` option -- §6.30's *"usually"*, wired
     * 2026-08-27, see [AirZoneOfControl]), and records carrying OG's `No ZOC` attribute (wired 2026-08-25, see
     * [UnitCapabilities.projectsZoneOfControl] for why that ability stopped being "not
     * representable"). Skipping at this one choke point keeps the add and the remove symmetric,
     * which the hex's reference count depends on.
     */
    fun setZOCRange(
        map: GameMap,
        unit: GameUnit,
        add: Boolean,
    ) {
        val skip = !UnitCapabilities.projectsZoneOfControl(unit)
        val pos = if (skip) null else unit.getPos()
        val side = if (skip) null else unit.player?.side
        if (pos == null || side == null) return
        HexGeometry.getAdjacent(pos.row, pos.col).forEach { cell ->
            if (cell.row in 0 until map.rows && cell.col in 0 until map.cols) {
                map.map
                    ?.getOrNull(cell.row)
                    ?.getOrNull(cell.col)
                    ?.setZOC(side, add)
            }
        }
    }

    /**
     * Adds or removes [unit]'s spotting; returns how many enemy units became newly spotted.
     *
     * Under `extended_los` (OG 9.5 bullet 1) a cell whose line from this unit is cut by mountain,
     * hill, forest, city or bocage is skipped entirely. Skipping is safe here — and ONLY here —
     * because the test reads terrain, which is fixed for the scenario: the add and the matching
     * remove therefore cover the same cells, which is the invariant the per-side reference counts
     * depend on (`model/GameMapGrid.recomputeSpotting`). `rules/Engineering`, the one thing that
     * can change terrain mid-scenario, calls that wholesale rebuild for this reason.
     */
    fun setSpotRange(
        map: GameMap,
        unit: GameUnit,
        add: Boolean,
    ): Int {
        val pos = unit.getPos()
        val side = unit.player?.side
        if (pos == null || side == null) return 0
        val range = getUnitSpotRange(unit)
        val cells = HexGeometry.getRing(pos.row, pos.col, range, map.rows, map.cols, false)
        cells.add(Cell(pos.row, pos.col))
        var newlySpotted = 0
        cells.forEach { cell ->
            if (cell.row in 0 until map.rows && cell.col in 0 until map.cols) {
                val hex = map.map?.getOrNull(cell.row)?.getOrNull(cell.col) ?: return@forEach
                if (!ExtendedLos.hasLineOfSight(map.map, pos.row, pos.col, cell.row, cell.col)) return@forEach
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

    /** Spotting range for [unit] including recon leader bonuses, then the weather.
     *  Weather comes last so it halves the range the unit would otherwise have, bonuses included
     *  (`WeatherCombatRules.spotRange`). */
    fun getUnitSpotRange(unit: GameUnit): Int {
        var range = unit.unitData().spotrange
        // OG's `True spotting 0` INVERTED, and this is the one option OSADA had backwards.
        // `HexGeometry.getRing` returns nothing at radius 0, so a `spotrange = 0` formation has
        // always seen only its own hex here -- which is OG's behaviour with the option ON. The 295
        // scenarios that set it were already right; the rest were blinding 303 shipped records that
        // OG lets see their adjacent ring. Absent or unreadable reads as "not authored", which is
        // the direction that gives the formation its neighbours back.
        if (range == 0 && GameHolder.instance?.scenario?.trueSpottingZero != true) range = 1
        if (Leaders.unitHasLeader(unit, LeaderType.ELITE_RECON_VETERAN)) range += 2
        if (Leaders.unitHasLeader(unit, LeaderType.SKILLED_RECONNAISSANCE)) range += 1
        range += Attachments.bonus(unit, Attachments.SLOT_RECON)
        return WeatherCombatRules.spotRange(unit, range)
    }

    /** First free, passable hex at/around (row,col) where a reinforcement may deploy, or null. */
    fun getReinforcementDeployPositions(
        map: GameMap,
        unit: GameUnit,
        row: Int,
        col: Int,
    ): Cell? = ReinforcementDeployment.getReinforcementDeployPositions(map, unit, row, col)

    /**
     * True when [unit] is on or beside a friendly airfield/carrier. Used by air resupply
     * and reinforcement rules ([SupplyRules]).
     */
    internal fun hasAirfield(
        map: GameMap,
        unit: GameUnit,
    ): Boolean {
        val pos = unit.getPos()
        val hex = if (pos == null) null else map.map?.getOrNull(pos.row)?.getOrNull(pos.col)
        if (pos == null || hex == null) return false
        // OG's `Cannot use dirt airfields` (`attr2` bit 2): a jet or heavy bomber cannot base on a
        // strip the sappers scraped during the scenario. Read on the unit's REAL record -- the
        // ability belongs to the airframe, not to anything it is carrying. See [AirfieldQuality].
        val data = unit.unitData(true)
        val usable = { candidate: Hex? ->
            // `isWorking` rather than a bare terrain test: OG's wrecked airfield is "unusable until
            // Repaired", which has to mean an aircraft cannot base on it (`Hex.isWorking`).
            candidate?.isWorking(TerrainType.AIRFIELD.value) == true &&
                candidate.flag == unit.player?.country &&
                !AirfieldQuality.unusableBy(candidate, data)
        }
        val onOwnAirfield = usable(hex)
        val onOwnCarrier = hex.unit?.unitData()?.uclass == UnitClass.CARRIER.value && hex.unit?.owner == unit.player?.id
        val neighbours = HexGeometry.getAdjacent(pos.row, pos.col)
        val adjacentToAirfield =
            neighbours.any { cell ->
                usable(map.map?.getOrNull(cell.row)?.getOrNull(cell.col))
            }
        // OG's `Air support` (`attr` bit 13): "can supply air units, the same than an airfield".
        // Taken at its word -- an airfield services its own hex AND the ring around it, so a
        // seaplane tender or a forward depot does too. Own units only, as the carrier test above.
        val servicedByAirSupport =
            (listOf(pos) + neighbours).any { cell ->
                val holder =
                    map.map
                        ?.getOrNull(cell.row)
                        ?.getOrNull(cell.col)
                        ?.unit
                holder != null &&
                    !holder.destroyed &&
                    holder.owner == unit.player?.id &&
                    holder.unitData(true).attr and ATTR_MASK_AIR_SUPPORT != 0
            }
        return onOwnAirfield || onOwnCarrier || adjacentToAirfield || servicedByAirSupport
    }
}
