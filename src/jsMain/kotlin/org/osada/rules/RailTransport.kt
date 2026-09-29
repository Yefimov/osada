package org.osada.rules

import org.osada.RoadType
import org.osada.model.ATTR_EX_MASK_NO_NEED_STATION
import org.osada.model.GameMap
import org.osada.model.GameUnit
import org.osada.model.Hex
import org.osada.model.Player

/**
 * OG's **railway transport**, and with it the `No Need Station` equipment special.
 *
 * > *"The player must have some rail transport available"*, a station is required at **both** ends
 * > by default, and the unit *"must not already have acted"* before embarking. `No Need Station`
 * > lifts the station requirement at **both** ends — embarkation and disembarkation alike.
 *
 * ### The model: containment, like air and naval transport (rebuilt 2026-09-28)
 *
 * OG's railway is a container. The author's Features page asks the scenario for *"a train transport
 * able to move on rails, available in the Train Transport Pool"*, the changelog speaks of *"units
 * embarked in RTP"* (0.92.0.0) and of *"unit transported on trains ... movement on minefields"*, so
 * a unit boards a real train, the train drives the map over as many turns as the journey takes, and
 * it can be caught on the way.
 *
 * OSADA now does exactly that, through the machinery naval and air transport already use:
 * [GameUnit.carrier] holds the train's equipment id while the formation is aboard, so
 * [GameUnit.unitData] answers with the TRAIN — its movement, its defence, its icon. Because a train
 * record moves by [MovMethod.RAIL], `MoveRangeCalculation` confines it to track with no rail-specific
 * code here, enemy zones of control stop it, minefields catch it, and an attack on it is an attack on
 * a train. The formation's own strength is what the train carries, so a destroyed train is a
 * destroyed formation — the same rule a sunk naval transport follows.
 *
 * Until 2026-09-28 the railway was an ATOMIC relocation between two boarding points, recorded then
 * as a compression because OG's `RT` class is folded into Ground Transport and *"there is no train
 * record to become"*. There is: the RT records are still in the data as Ground Transport with RAIL
 * movement and no attack ([TrainRecords.isTrainRecord]) — 251 of them across the merged rosters, including the
 * country-0 trains LXF defines for every nation. The owner asked for the real train.
 *
 * ### What is still OSADA's reading rather than OG's text
 *
 *  - **Which train.** OG draws from the scenario's train pool; OSADA takes the formation's own
 *    country's first train record, then the always-loaded country-0 file's, then any loaded one
 *    ([TrainRecords.trainFor]). No source says how OG picks among several.
 *  - **Weights.** OG matches rail, unit and transport weights (`RtpW`); what the numbers mean for
 *    rail is unpublished (`rules/TransportWeights`), so no weight test is applied.
 *  - **Leaving the train.** Detraining where the train has not moved this turn is free — boarding
 *    and stepping straight off again changes nothing, and a formation that arrived last turn walks
 *    off at the start of this one. Detraining after the train has moved this turn ends the move.
 *    OG says only that the unit must not have acted BEFORE boarding.
 *  - **The pool.** A slot is taken on boarding and returned on detraining, exactly as air and naval
 *    are (`model/TransportPools`), and recounted as its owner's turn opens ([recountPool]).
 */
object RailTransport {
    /** Whether [unit] may board or leave the rail anywhere on it, with no station. Read on the
     *  REAL record: the permission belongs to the formation, not to the train carrying it. */
    fun needsNoStation(unit: GameUnit): Boolean = unit.unitData(true).attrEx and ATTR_EX_MASK_NO_NEED_STATION != 0

    /** Whether [hex] is a place [unit] may get on or off: track, plus a station unless the
     *  formation carries `No Need Station`. */
    fun isBoardingPoint(
        hex: Hex?,
        unit: GameUnit,
    ): Boolean =
        hex != null &&
            hex.rail > RoadType.NONE.value &&
            (hex.station || needsNoStation(unit))

    /** Whether [unit] is aboard a train right now. */
    fun isEntrained(unit: GameUnit): Boolean = TrainRecords.isTrainCarrier(unit.carrier)

    /**
     * Whether [unit] could board a train right now.
     *
     * Ground formations only — OG's railway carries neither aircraft nor ships — idle, holding a pool
     * point, permitted rail by its own record, not already carried, and standing somewhere it may
     * board. A formation riding its own lorries boards too: it dismounts onto the train.
     */
    fun canEntrain(unit: GameUnit): Boolean {
        val usable = UnitPredicates.isGround(unit) && !unit.destroyed && unit.carrier == 0 && permitsRail(unit)
        val idle = !unit.hasMoved && !unit.hasFired
        val hasPool = (unit.player?.railTransports ?: 0) > 0
        return usable && idle && hasPool && isBoardingPoint(unit.getHex(), unit) && TrainRecords.trainFor(unit) != null
    }

    /** Whether [unit] is aboard a train standing somewhere it may get off. */
    fun canDetrain(unit: GameUnit): Boolean = isEntrained(unit) && isBoardingPoint(unit.getHex(), unit)

    /**
     * OG's per-record rail permission — *"Units be configured to use Train Transport"*. A record
     * with no data is permitted, not refused ([EquipmentData.railTransportable]).
     */
    fun permitsRail(unit: GameUnit): Boolean = unit.unitData(true).canUseRailTransport()

    /**
     * Puts [unit] aboard a train, spending one point of its player's pool.
     *
     * Returns false and changes nothing unless [canEntrain] holds. The formation has not moved, so
     * the train may leave on this same turn.
     */
    fun entrain(
        map: GameMap,
        unit: GameUnit,
    ): Boolean {
        val train = if (canEntrain(unit)) TrainRecords.trainFor(unit) else null
        if (train == null) return false
        GameRules.setZOCRange(map, unit, false)
        GameRules.setSpotRange(map, unit, false)
        unit.isMounted = false
        unit.carrier = train
        GameRules.setZOCRange(map, unit, true)
        GameRules.setSpotRange(map, unit, true)
        unit.player?.let { it.railTransports = (it.railTransports - 1).coerceAtLeast(0) }
        map.unitImages.add(train)
        return true
    }

    /**
     * Takes [unit] off its train and returns the train to the pool.
     *
     * Free when the train has not moved this turn; after a journey it ends the formation's move.
     */
    fun detrain(
        map: GameMap,
        unit: GameUnit,
    ): Boolean {
        if (!canDetrain(unit)) return false
        val travelled = unit.hasMoved
        GameRules.setZOCRange(map, unit, false)
        GameRules.setSpotRange(map, unit, false)
        unit.carrier = 0
        GameRules.setZOCRange(map, unit, true)
        GameRules.setSpotRange(map, unit, true)
        unit.player?.let { if (it.railTransports < it.railTransportsMax) it.railTransports++ }
        if (travelled) unit.moveLeft = 0
        return true
    }

    /**
     * The pool as [player]'s turn opens: the authored size less the trains still carrying one of
     * their formations.
     *
     * Counted rather than tracked, so it cannot drift — a train destroyed with its cargo is no longer
     * *"in use"*, which is what OG's *"how many trains can be used at any time"* counts, and a save
     * written under the old atomic railway (which spent the pool and refreshed it every turn) comes
     * back with the right number.
     */
    fun recountPool(
        map: GameMap,
        player: Player,
    ) {
        val aboard = map.units.count { it.owner == player.id && !it.destroyed && isEntrained(it) }
        player.railTransports = (player.railTransportsMax - aboard).coerceAtLeast(0)
    }
}
