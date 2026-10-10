package org.osada.ai

import org.osada.model.Cell
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.Player
import org.osada.model.getUnits
import org.osada.model.landInHangar
import org.osada.multiplayer.client.OsadaMultiplayer
import org.osada.rules.CarrierHangars
import org.osada.rules.HangarStowage
import org.osada.rules.HexGeometry
import org.osada.rules.UnitPredicates
import org.osada.rules.unitUsesFuel

/**
 * The AI's use of carriers, airbases and other containers (owner's request 2026-10-10). Three steps,
 * all at the start of the AI's turn, before its planner reads the map -- the same place
 * [AI.purchaseAndDeployPhase] already places reserve formations directly:
 *
 *  - **Stow** reserve aircraft that found no deploy hex into a friendly hangar with room, so a side
 *    whose only airfield is a carrier still gets its aircraft into the battle.
 *  - **Launch** every passenger that may leave this turn. An aircraft takes off from the container's
 *    own hex and is then planned like any other aircraft; a ground formation steps onto the offered
 *    hex nearest the objectives it is fighting for.
 *  - **Land** a worn aircraft -- under half strength, or short of fuel or ammunition -- that stands
 *    on or beside a friendly hangar with room. It spends the rest of its turn inside and is
 *    refuelled, rearmed and repaired with the round (`CarrierHangars.endRoundForContained`).
 *
 * The planner does not fly aircraft TOWARDS a hangar; a worn aircraft lands only where one is
 * already within reach. Uses the same rule objects as the player's chips, so every limit
 * (`ground_carrier` bits, capacity, landed-this-turn, terrain) holds for the AI too.
 *
 * **Not in a multiplayer room yet**, for the same reason the player's chips are hidden there
 * (`docs/multiplayer/pending-features.md`).
 */
internal object AIHangars {
    private const val WORN_STRENGTH = 5

    fun enabled(): Boolean = CarrierHangars.active() && !OsadaMultiplayer.active

    /** Puts [player]'s still-undeployed reserve aircraft into friendly hangars with room. */
    fun stowUndeployedAircraft(
        map: GameMap,
        player: Player,
    ) {
        if (!enabled()) return
        val containers = friendlyContainers(map, player)
        player.getCoreUnitList().filter { !it.isDeployed && UnitPredicates.isAir(it) }.forEach { unit ->
            containers
                .firstOrNull { HangarStowage.canStowAtDeployment(unit, it, player.side) }
                ?.let { HangarStowage.stowAtDeployment(map, unit, it) }
        }
    }

    /** Launches every passenger of [player]'s containers that may leave this turn. */
    fun launchReady(
        map: GameMap,
        player: Player,
        objectives: List<Cell>,
    ) {
        if (!enabled()) return
        friendlyContainers(map, player).forEach { container ->
            container.hangar.toList().forEach { passenger ->
                pickLaunchHex(map, passenger, container, objectives)
                    ?.let { CarrierHangars.launch(map, passenger, container, it) }
            }
        }
    }

    /** Lands each of [player]'s worn aircraft that stands on or beside a friendly hangar with room. */
    fun landWorn(
        map: GameMap,
        player: Player,
    ) {
        if (!enabled()) return
        map
            .getUnits()
            .filter { it.player?.id == player.id && UnitPredicates.isAir(it) && !it.hasMoved && isWorn(it) }
            .forEach { plane -> HangarStowage.landingContainer(map, plane)?.let { map.landInHangar(plane, it) } }
    }

    internal fun isWorn(unit: GameUnit): Boolean =
        unit.strength < WORN_STRENGTH ||
            unit.ammo < AMMO_THRESHOLD ||
            (UnitPredicates.unitUsesFuel(unit) && unit.fuel < FUEL_THRESHOLD)

    /**
     * Where [passenger] leaves [container]: an aircraft from the container's own hex (its air slot),
     * a ground formation onto the offered hex nearest one of [objectives], or the first offered hex
     * when there are none.
     */
    private fun pickLaunchHex(
        map: GameMap,
        passenger: GameUnit,
        container: GameUnit,
        objectives: List<Cell>,
    ): Hex? {
        val cells = CarrierHangars.launchCells(map, passenger, container)
        val home = container.getHex()
        return when {
            UnitPredicates.isAir(passenger) && home != null && home in cells -> home
            objectives.isEmpty() -> cells.firstOrNull()
            else ->
                cells.minByOrNull { hex ->
                    val at = hex.getPos()
                    objectives.minOf { HexGeometry.distance(at.row, at.col, it.row, it.col) }
                }
        }
    }

    private fun friendlyContainers(
        map: GameMap,
        player: Player,
    ): List<GameUnit> =
        map.getUnits().filter {
            it.player?.id == player.id && !it.destroyed && CarrierHangars.capacity(it) > 0
        }
}
