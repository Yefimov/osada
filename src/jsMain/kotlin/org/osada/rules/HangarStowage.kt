package org.osada.rules

import org.osada.UnitClass
import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.getUnitById
import org.osada.multiplayer.client.OsadaMultiplayer

/**
 * Getting formations INTO a hangar ([CarrierHangars] owns what happens inside one).
 *
 * Two ways in, as in OG:
 *
 *  - **landing during play** -- a formation on or beside a friendly container goes in, and that is
 *    the rest of its turn ([landingContainer], [CarrierHangars.board]);
 *  - **stowing at deployment** -- a reserve aircraft is placed straight into a friendly carrier or
 *    airbase instead of onto an airfield ([stowAtDeployment]). It may take off on the first turn: it
 *    never "landed".
 */
internal object HangarStowage {
    /**
     * The friendly containers next to [unit] that could ever take it -- its own hex first, then the
     * ring -- whether or not they have room. The Land chip uses the full list to say *full*
     * rather than vanish.
     */
    fun containersNear(
        map: GameMap,
        unit: GameUnit,
    ): List<GameUnit> {
        val pos = unit.getPos()
        val grid = map.map
        if (pos == null || grid == null) return emptyList()
        return (listOf(pos) + HexGeometry.getAdjacent(pos.row, pos.col))
            .mapNotNull { cell: Cell -> grid.getOrNull(cell.row)?.getOrNull(cell.col)?.unit }
            .filter { container -> container !== unit && fits(unit, container) }
    }

    /**
     * The friendly fortifications next to land formation [unit] that it could take cover in -- its own
     * hex first, then the ring -- whether or not they have room. A fortification with hangar room that
     * is not an Airbase, and only where the efile's `ground_carrier` lets land units in.
     */
    fun coverNear(
        map: GameMap,
        unit: GameUnit,
    ): List<GameUnit> {
        val pos = unit.getPos()
        val grid = map.map
        if (pos == null || grid == null || !UnitPredicates.isGround(unit)) return emptyList()
        return (listOf(pos) + HexGeometry.getAdjacent(pos.row, pos.col))
            .mapNotNull { cell: Cell -> grid.getOrNull(cell.row)?.getOrNull(cell.col)?.unit }
            .filter { container ->
                container !== unit &&
                    isCover(container) &&
                    container.player?.side == unit.player?.side
            }
    }

    /** The fortification [unit] would take cover in now: the first of [coverNear] that takes it. */
    fun coverContainer(
        map: GameMap,
        unit: GameUnit,
    ): GameUnit? = coverNear(map, unit).firstOrNull { CarrierHangars.canBoard(unit, it) }

    /** A friendly fortification with room for a garrison, under an efile that admits land units. */
    fun isCover(container: GameUnit): Boolean =
        !container.destroyed &&
            container.unitData(true).uclass == UnitClass.FORTIFICATION.value &&
            !CarrierHangars.holdsAircraft(container) &&
            CarrierHangars.capacity(container) > 0 &&
            CarrierHangars.admitsLandUnits()

    /** The container [unit] would land in now: the first of [containersNear] with room. */
    fun landingContainer(
        map: GameMap,
        unit: GameUnit,
    ): GameUnit? = containersNear(map, unit).firstOrNull { CarrierHangars.canBoard(unit, it) }

    /**
     * Everything about [container] except its free room: an aircraft, a friendly carrier or airbase,
     * a hangar. **Aircraft only** (owner's ruling 2026-10-07): land formations get no Land chip even
     * where the efile's `ground_carrier` lets them into bunkers and ships -- paratroops included,
     * which are infantry that ride an air transport, not aircraft.
     */
    private fun fits(
        unit: GameUnit,
        container: GameUnit,
    ): Boolean =
        UnitPredicates.isAir(unit) &&
            !container.destroyed &&
            container.player?.side == unit.player?.side &&
            CarrierHangars.capacity(container) > 0 &&
            CarrierHangars.holdsAircraft(container)

    /**
     * Whether reserve aircraft [unit] may be placed straight into [container] at deployment. Never in
     * a multiplayer room yet: the host has no stow order (`docs/multiplayer/pending-features.md`).
     */
    fun canStowAtDeployment(
        unit: GameUnit,
        container: GameUnit,
        side: Int,
    ): Boolean =
        CarrierHangars.active() &&
            !OsadaMultiplayer.active &&
            UnitPredicates.isAir(unit) &&
            !unit.isDeployed &&
            !container.destroyed &&
            container.player?.side == side &&
            CarrierHangars.holdsAircraft(container) &&
            CarrierHangars.hasRoom(container)

    /** Places reserve aircraft [unit] in [container]'s hangar, returning whether it went in. */
    fun stowAtDeployment(
        map: GameMap,
        unit: GameUnit,
        container: GameUnit,
    ): Boolean {
        val player = container.player
        if (player == null || !canStowAtDeployment(unit, container, player.side)) return false
        // A reserve formation may carry no id yet, or one a placed unit has taken since.
        val taken = map.getUnitById(unit.id) != null || allPassengers(map).any { it.id == unit.id }
        if (unit.id < 0 || taken) unit.id = map.nextUnitId++
        unit.player = player
        unit.isDeployed = true
        unit.landedTurn = -1
        unit.containedIn = container
        container.hangar.add(unit)
        map.unitImages.add(unit.eqid)
        return true
    }

    /** Takes [unit] back out of its hangar into the reserve tray -- the opening deployment's undo. */
    fun returnToReserve(unit: GameUnit): Boolean {
        val container = unit.containedIn ?: return false
        container.hangar.remove(unit)
        unit.containedIn = null
        unit.isDeployed = false
        return true
    }

    /** Every formation inside a hangar on [map]. */
    fun allPassengers(map: GameMap): List<GameUnit> = map.units.flatMap { it.hangar }
}
